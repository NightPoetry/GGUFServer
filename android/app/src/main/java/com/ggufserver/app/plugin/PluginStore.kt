package com.ggufserver.app.plugin

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** 单个插件已装载的完整状态：manifest + 宿主侧运行状态，供管理页（第二阶段）渲染。 */
data class PluginRecord(
    val manifest: PluginManifest,
    val dir: File,
    val enabled: Boolean,
    val bypassed: Boolean,
    val badge: Int,
    val order: Int,
    val gestureOverride: Pair<Float, Float>?,
    /** 用户在管理页选的生效范围（§6.8 三选一），null = 跟随 manifest.scope 默认。 */
    val scopeOverride: Set<String>?,
    val errors: List<PluginErrorEntry>,
    /** 随 APK assets 携带的内置插件：不可卸载（卸载会清私有数据，而下次启动 assets 又会重新释放，等于变相清空用户数据）。 */
    val builtin: Boolean = false,
)

data class PluginErrorEntry(val ts: Long, val message: String)

sealed class InstallResult {
    data class Installed(val manifest: PluginManifest, val upgraded: Boolean) : InstallResult()
    /** 同 id 且版本相同或更低：需要用户确认强制覆盖后带 force=true 重新调用。 */
    data class NeedsForce(val installedVersion: String, val newVersion: String) : InstallResult()
    data class Rejected(val reason: String) : InstallResult()
}

/**
 * 插件安装、状态持久化与私有 KV 存储的单例仓库。只管「有哪些插件、装没装、启没启用」，
 * 不碰 WebView——运行时执行落在 [PluginRuntime]，两者通过本对象的状态回调解耦
 * （Runtime 观察 [plugins] 的变化来创建/销毁 WebView，反过来用 [recordDispatchFailure] 等
 * 方法把执行结果写回这里）。
 */
object PluginStore {
    private const val MAX_ZIP_BYTES = 20L * 1024 * 1024
    private const val MAX_KV_BYTES = 5L * 1024 * 1024
    private const val MAX_ERRORS = 20
    private const val CONSECUTIVE_FAILURES_TO_BYPASS = 3

    /**
     * 在网关请求管线上执行的钩子。manifest.hooks 里不在此集合的钩子（如 messageAction 消息角标，
     * 由聊天界面直接派发）不算中间件——只声明这类钩子的插件绝不能把网关拖出零插件快路径。
     */
    // `curate` 是 §13.1 里正式的管线面，2026-07-27 补进来：漏了它的话，一个**只登记 curate**
    // 的插件（不提供工具、只决定本轮该露别家的哪些能力——正是 §13.4 收窄语义的典型形态）会被
    // 判成「不是中间件」，进而走零插件快路径 / 被 activePlugins 过滤掉，curate 面永不分发，
    // 且零日志零提示。今天内置的 tts 侥幸没踩到，是因为它另有 tools 撑着，不是因为 curate。
    private val GATEWAY_HOOKS = setOf("route", "curate", "context", "afterReply", "continuation", "params")

    private lateinit var appContext: Context
    private lateinit var pluginsDir: File
    private lateinit var dataDir: File
    private lateinit var stagingDir: File
    private lateinit var stateFile: File
    @Volatile private var initialized = false

    private val lock = Any()

    // --- 持久化状态（filesDir/plugins/_state.json） ---
    private var enabledIds = LinkedHashSet<String>()
    private var order = ArrayList<String>()
    private var gestureOverrides = HashMap<String, Pair<Float, Float>>()
    private var scopeOverrides = HashMap<String, Set<String>>()
    private var bypassedIds = LinkedHashSet<String>()

    // --- 运行时状态（不持久化：角标、错误环形缓冲、连续失败计数） ---
    private val badges = HashMap<String, Int>()
    private val errorLogs = HashMap<String, ArrayDeque<PluginErrorEntry>>()
    private val consecutiveFailures = HashMap<String, Int>()

    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    /** 供第二阶段管理页 / 顶栏订阅：插件列表、启用状态、角标、最近错误一次性给全。 */
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    @Volatile private var anyMiddlewareEnabled = false

    // --- ui.close / ui.toast 桥接口落点：第二阶段的面板容器订阅这两个流来响应 ---
    private val _closeRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val closeRequests: SharedFlow<String> = _closeRequests.asSharedFlow()
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** gguf.card.post 桥路径（§6.12 消息卡片）：ChatViewModel 订阅并落成会话消息。 */
    data class CardPost(val pluginId: String, val file: String, val dataJson: String, val sessionId: String?)
    private val _cardPosts = MutableSharedFlow<CardPost>(extraBufferCapacity = 8)
    val cardPosts: SharedFlow<CardPost> = _cardPosts.asSharedFlow()
    fun postCard(pluginId: String, file: String, dataJson: String, sessionId: String?) {
        _cardPosts.tryEmit(CardPost(pluginId, file, dataJson, sessionId))
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            pluginsDir = File(appContext.filesDir, "plugins").apply { mkdirs() }
            dataDir = File(appContext.filesDir, "plugin-data").apply { mkdirs() }
            stagingDir = File(pluginsDir, ".staging").apply { mkdirs() }
            stateFile = File(pluginsDir, "_state.json")
            loadState()
            bootstrapBuiltins()
            reload()
            initialized = true
        }
    }

    // ============================== 状态持久化 ==============================

    private fun loadState() {
        if (!stateFile.exists()) return
        runCatching {
            val o = JSONObject(stateFile.readText())
            enabledIds = LinkedHashSet(jsonStrings(o.optJSONArray("enabled")))
            order = ArrayList(jsonStrings(o.optJSONArray("order")))
            bypassedIds = LinkedHashSet(jsonStrings(o.optJSONArray("bypassed")))
            gestureOverrides = HashMap()
            o.optJSONObject("gestureOverrides")?.let { m ->
                m.keys().forEach { k ->
                    val a = m.optJSONArray(k)
                    if (a != null && a.length() == 2) gestureOverrides[k] = a.optDouble(0).toFloat() to a.optDouble(1).toFloat()
                }
            }
            scopeOverrides = HashMap()
            o.optJSONObject("scopeOverrides")?.let { m ->
                m.keys().forEach { k ->
                    val s = jsonStrings(m.optJSONArray(k)).filter { it == "local" || it == "external" }.toSet()
                    if (s.isNotEmpty()) scopeOverrides[k] = s
                }
            }
        }
    }

    private fun jsonStrings(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, null) }
    }

    private fun persistState() {
        val o = JSONObject()
        o.put("enabled", JSONArray(enabledIds.toList()))
        o.put("order", JSONArray(order.toList()))
        o.put("bypassed", JSONArray(bypassedIds.toList()))
        val g = JSONObject()
        gestureOverrides.forEach { (id, range) -> g.put(id, JSONArray().put(range.first.toDouble()).put(range.second.toDouble())) }
        o.put("gestureOverrides", g)
        val sc = JSONObject()
        scopeOverrides.forEach { (id, scopes) -> sc.put(id, JSONArray(scopes.toList())) }
        o.put("scopeOverrides", sc)
        // 原子写：进程在写入中途被杀（升级安装、系统回收）不能留下截断文件——
        // 截断文件下次启动解析失败会静默回落到“全部禁用”的默认态，用户启用状态被清空。
        runCatching {
            val tmp = File(stateFile.parentFile, "_state.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(o.toString())
                tmp.delete()
            }
        }
    }

    /** 从磁盘重新扫描已装插件目录，重建 [plugins] 状态流；安装/卸载/启用禁用后调用。 */
    private fun reload() {
        val dirs = pluginsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()
        val manifests = HashMap<String, Pair<PluginManifest, File>>()
        for (d in dirs) {
            val mf = File(d, "manifest.json")
            if (!mf.exists()) continue
            val parsed = runCatching { PluginManifest.parse(mf.readText()) }.getOrNull()?.getOrNull() ?: continue
            manifests[parsed.id] = parsed to d
        }
        // 显示顺序：已记录顺序在前，新发现的插件（新装/新释放的内置插件）追加在后
        val ids = LinkedHashSet<String>()
        order.forEach { if (it in manifests) ids += it }
        manifests.keys.forEach { ids += it }
        order = ArrayList(ids)

        val records = order.mapIndexed { idx, id ->
            val (m, dir) = manifests.getValue(id)
            PluginRecord(
                manifest = m,
                dir = dir,
                enabled = id in enabledIds,
                bypassed = id in bypassedIds,
                badge = badges[id] ?: 0,
                order = idx,
                gestureOverride = gestureOverrides[id],
                scopeOverride = scopeOverrides[id],
                errors = errorLogs[id]?.toList() ?: emptyList(),
                builtin = id in builtinIds,
            )
        }
        _plugins.value = records
        anyMiddlewareEnabled = records.any { r ->
            r.enabled && !r.bypassed && (r.manifest.hooks.any { it in GATEWAY_HOOKS } || r.manifest.tools.isNotEmpty() || r.manifest.systemClause.isNotEmpty())
        }
        persistState()
    }

    // ============================== 内置插件释放 ==============================

    /**
     * 首次启动（以及每次 init，成本很低）把 assets/plugins/<id>/ 释放到安装目录。
     * 本阶段 assets 里还没有内置插件，这里只是把机制铺好：目录不存在时 list() 直接返回空数组。
     * 释放条件：未安装，或资产版本号比已装版本更高（覆盖时保留 plugin-data）。
     */
    /** assets 里携带的内置插件 id 集合（init 时填充，用于禁止卸载）。 */
    private var builtinIds: Set<String> = emptySet()

    private fun bootstrapBuiltins() {
        val am = appContext.assets
        val ids = runCatching { am.list("plugins") }.getOrNull() ?: return
        builtinIds = ids.toSet()
        for (id in ids) {
            runCatching { releaseBuiltin(am, id) }
        }
    }

    private fun releaseBuiltin(am: AssetManager, id: String) {
        val assetRoot = "plugins/$id"
        val manifestText = am.open("$assetRoot/manifest.json").bufferedReader().use { it.readText() }
        val assetManifest = PluginManifest.parse(manifestText).getOrNull() ?: return
        val installedDir = File(pluginsDir, assetManifest.id)
        val installedManifestFile = File(installedDir, "manifest.json")
        if (installedManifestFile.exists()) {
            val installed = runCatching { PluginManifest.parse(installedManifestFile.readText()) }.getOrNull()?.getOrNull()
            if (installed != null && PluginManifest.compareVersions(assetManifest.version, installed.version) <= 0) {
                return // 已装且不比资产版本旧，跳过
            }
        }
        val staging = File(stagingDir, "builtin-${assetManifest.id}-${System.currentTimeMillis()}")
        copyAssetTree(am, assetRoot, staging)
        installedDir.deleteRecursively()
        staging.renameTo(installedDir)
    }

    private fun copyAssetTree(am: AssetManager, assetPath: String, dest: File) {
        val children = am.list(assetPath)
        if (children == null || children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAssetTree(am, "$assetPath/$c", File(dest, c))
        }
    }

    // ============================== 安装 / 卸载 ==============================

    fun installFromZip(input: InputStream, force: Boolean = false): InstallResult {
        val tempZip = File(appContext.cacheDir, "plugin-import-${System.currentTimeMillis()}.zip")
        try {
            var total = 0L
            tempZip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ZIP_BYTES) return InstallResult.Rejected("插件包超过 20MB 上限")
                    out.write(buf, 0, n)
                }
            }
            return installFromZipFile(tempZip, force)
        } catch (e: Exception) {
            return InstallResult.Rejected("读取插件包失败：${e.message}")
        } finally {
            runCatching { tempZip.delete() }
        }
    }

    private fun installFromZipFile(zipFile: File, force: Boolean): InstallResult {
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().toList()
            for (e in entries) {
                val name = e.name
                if (name.startsWith("/") || name.split("/").any { it == ".." }) {
                    return InstallResult.Rejected("插件包内路径不合法：$name")
                }
            }
            var totalUncompressed = 0L
            for (e in entries) if (!e.isDirectory) totalUncompressed += maxOf(e.size, 0L)
            if (totalUncompressed > MAX_ZIP_BYTES) return InstallResult.Rejected("插件包解压后超过 20MB 上限")

            val manifestEntry = zf.getEntry("manifest.json")
                ?: return InstallResult.Rejected("插件包缺少 manifest.json")
            val manifestText = zf.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            val manifest = PluginManifest.parse(manifestText).fold({ it }, { err ->
                return InstallResult.Rejected(err.message ?: "manifest.json 校验失败")
            })

            fun entryExists(path: String): Boolean {
                val en = zf.getEntry(path) ?: return false
                return !en.isDirectory
            }
            PluginManifest.validateEntries(manifest, ::entryExists)?.let { return InstallResult.Rejected(it) }

            val installedDir = File(pluginsDir, manifest.id)
            val existing = runCatching {
                File(installedDir, "manifest.json").takeIf { it.exists() }
                    ?.let { PluginManifest.parse(it.readText()).getOrNull() }
            }.getOrNull()
            if (existing != null && PluginManifest.compareVersions(manifest.version, existing.version) <= 0 && !force) {
                return InstallResult.NeedsForce(existing.version, manifest.version)
            }

            val staging = File(stagingDir, "install-${manifest.id}-${System.currentTimeMillis()}")
            staging.mkdirs()
            for (e in entries) {
                if (e.isDirectory) continue
                val target = File(staging, e.name)
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            }

            synchronized(lock) {
                installedDir.deleteRecursively()
                staging.renameTo(installedDir)
                if (manifest.id !in order) order.add(manifest.id)
                // 默认禁用（新装 / 升级都不会自动开启，见规范 §0/§9）
                reload()
            }
            return InstallResult.Installed(manifest, upgraded = existing != null)
        }
    }

    /** 卸载：删目录 + KV 存储，并清掉这个 id 的一切持久化痕迹。调用方（UI）负责弹窗确认。 */
    fun uninstall(id: String) {
        if (id in builtinIds) return   // 内置插件不可卸载（保护用户数据，UI 侧同样不给入口）
        synchronized(lock) {
            enabledIds.remove(id)
            bypassedIds.remove(id)
            gestureOverrides.remove(id)
            scopeOverrides.remove(id)
            order.remove(id)
            badges.remove(id)
            errorLogs.remove(id)
            consecutiveFailures.remove(id)
            File(pluginsDir, id).deleteRecursively()
            File(dataDir, id).deleteRecursively()
            reload()
        }
    }

    // ============================== 启用 / 禁用 / 顺序 / 手势 ==============================

    fun manifestOf(id: String): PluginManifest? = _plugins.value.firstOrNull { it.manifest.id == id }?.manifest

    fun isEnabled(id: String): Boolean = id in enabledIds

    /**
     * 启用插件。权限确认弹窗是 UI 的职责（第二阶段），这里只做两件事：
     * apiVersion 兼容性检查、写状态。真正创建 service WebView 由 PluginRuntime 观察状态变化后完成。
     */
    fun setEnabled(id: String, enabled: Boolean): String? {
        val manifest = manifestOf(id) ?: return "插件不存在：$id"
        if (enabled && manifest.apiVersion > PluginManifest.HOST_API_VERSION) {
            return "该插件需要更新版本的宿主 App（要求桥接口 v${manifest.apiVersion}，当前 v${PluginManifest.HOST_API_VERSION}）"
        }
        synchronized(lock) {
            if (enabled) {
                enabledIds.add(id)
                consecutiveFailures.remove(id)
            } else {
                enabledIds.remove(id)
            }
            reload()
        }
        return null
    }

    fun setOrder(ids: List<String>) {
        synchronized(lock) {
            val known = _plugins.value.map { it.manifest.id }.toSet()
            val filtered = ids.filter { it in known }
            val rest = order.filter { it !in filtered }
            order = ArrayList(filtered + rest)
            reload()
        }
    }

    /** 用户在管理页手动改过的手势区间，覆盖 manifest 声明；传 null 恢复为 manifest 默认。 */
    fun setGestureOverride(id: String, range: Pair<Float, Float>?) {
        synchronized(lock) {
            if (range == null) gestureOverrides.remove(id) else gestureOverrides[id] = range
            reload()
        }
    }

    /**
     * 用户在管理页选的生效范围（§6.8 三选一：仅对内/内外通用/仅对外），覆盖 manifest.scope；
     * 传 null 恢复默认。守门：manifest 未声明 chat:external 时静默丢弃含 external 的覆盖
     * （UI 只对 middleware.configurable 的插件展示选择器，这里是第二道保险）。
     */
    fun setScopeOverride(id: String, scopes: Set<String>?) {
        synchronized(lock) {
            val m = manifestOf(id)
            val cleaned = scopes
                ?.filter { it == "local" || it == "external" }
                ?.filter { it != "external" || (m != null && "chat:external" in m.permissions) }
                ?.toSet()
            if (cleaned.isNullOrEmpty()) scopeOverrides.remove(id) else scopeOverrides[id] = cleaned
            reload()
        }
    }

    /** 熔断后用户一键恢复：清掉旁路标记与失败计数。 */
    fun clearBypass(id: String) {
        synchronized(lock) {
            bypassedIds.remove(id)
            consecutiveFailures[id] = 0
            reload()
        }
    }

    fun setBadge(id: String, n: Int) {
        synchronized(lock) {
            badges[id] = n.coerceAtLeast(0)
            reload()
        }
    }

    fun requestClose(id: String) { _closeRequests.tryEmit(id) }
    fun toast(text: String) { _toasts.tryEmit(text) }

    /**
     * 某能力点（§6.13）当前生效的提供者插件 id：已启用、未熔断、声明了该能力，
     * 按管理页顺序取第一个；null = 无提供者，走宿主默认实现。熔断/禁用即自动落回默认。
     */
    fun activeProvider(cap: String): String? =
        _plugins.value.asSequence()
            .filter { it.enabled && !it.bypassed && cap in it.manifest.provides }
            .sortedBy { it.order }
            .firstOrNull()?.manifest?.id

    // ============================== 网关消费面 ==============================

    /** 零插件（或启用的插件都无 hooks/tools/systemClause）时为 false —— 网关据此走零开销快路径。 */
    fun hasAnyMiddleware(): Boolean = anyMiddlewareEnabled

    /**
     * 某个来源（local/external）当前生效的插件清单：已启用、未熔断、middleware.scope 覆盖该来源，
     * 按管理页显示顺序排列。网关自己按 hooks/tools/systemClause 字段再做用途筛选。
     */
    fun activePlugins(scope: String): List<PluginManifest> {
        if (!anyMiddlewareEnabled) return emptyList()
        return _plugins.value
            .asSequence()
            .filter { it.enabled && !it.bypassed }
            // 生效范围：用户三选一覆盖优先（§6.8 内外通用管线），没改过则用 manifest 默认
            .filter { scope in (it.scopeOverride ?: it.manifest.scope) }
            .filter { it.manifest.hooks.any { h -> h in GATEWAY_HOOKS } || it.manifest.tools.isNotEmpty() || it.manifest.systemClause.isNotEmpty() }
            .sortedBy { it.order }
            .map { it.manifest }
            .toList()
    }

    // ============================== 熔断记录（PluginRuntime 回调） ==============================

    fun recordDispatchSuccess(id: String) {
        synchronized(lock) { consecutiveFailures[id] = 0 }
    }

    /** 连续 3 次超时/异常 → 自动旁路 + 记录最近错误（环形缓冲 20 条）。 */
    fun recordDispatchFailure(id: String, message: String) {
        synchronized(lock) {
            val ring = errorLogs.getOrPut(id) { ArrayDeque() }
            ring.addLast(PluginErrorEntry(System.currentTimeMillis(), message))
            while (ring.size > MAX_ERRORS) ring.removeFirst()

            val n = (consecutiveFailures[id] ?: 0) + 1
            consecutiveFailures[id] = n
            if (n >= CONSECUTIVE_FAILURES_TO_BYPASS) {
                bypassedIds.add(id)
                consecutiveFailures[id] = 0
            }
            reload()
        }
    }

    // ============================== 插件私有 KV 存储 ==============================

    private fun kvFile(id: String): File = File(dataDir, id).apply { mkdirs() }.let { File(it, "kv.json") }

    private fun readKv(id: String): JSONObject =
        runCatching { kvFile(id).takeIf { it.exists() }?.readText()?.let { JSONObject(it) } }.getOrNull() ?: JSONObject()

    fun kvGet(id: String, key: String): Any? {
        val v = readKv(id).opt(key)
        return if (v == JSONObject.NULL) null else v
    }

    fun kvKeys(id: String): List<String> = readKv(id).keys().asSequence().toList()

    /** 返回 null 表示成功；非 null 是拒绝原因（如超出 5MB 总量）。 */
    fun kvSet(id: String, key: String, value: Any?): String? {
        synchronized(lock) {
            val o = readKv(id)
            o.put(key, value ?: JSONObject.NULL)
            val bytes = o.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_KV_BYTES) return "插件私有存储超过 5MB 上限"
            runCatching { kvFile(id).writeBytes(bytes) } .onFailure { return "写入失败：${it.message}" }
            return null
        }
    }

    fun kvRemove(id: String, key: String) {
        synchronized(lock) {
            val o = readKv(id)
            o.remove(key)
            runCatching { kvFile(id).writeText(o.toString()) }
        }
    }
}
