package com.ggufserver.app.plugin

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.ggufserver.app.ServerBus
import com.ggufserver.app.data.ModelStore
import com.ggufserver.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模型市场（com.gguf.modelhub）的宿主侧三件套：
 *  - [HubApi]      hf-mirror 端点拼装与响应解析（纯 Kotlin，无 Android 依赖，可 JVM 单测）
 *  - [HubJudge]    按手机 RAM/磁盘判定某个模型的哪个量化「带得动」（纯 Kotlin，可 JVM 单测）
 *  - [ModelHubDownloader] 大文件下载器（应用级单例，HttpURLConnection + .part 断点续传）
 *
 * 为什么下载器在宿主：插件跑在 WebView 里，既没有后台存续保证（页面随管理页折叠销毁），
 * 也没有写模型目录的通道；而「下载 1.6GB 进模型目录」必须在界面关闭后继续。
 * 插件页面只负责浏览/搜索/选择，经桥（hub.download / hub.downloadStatus / hub.cancel）驱动宿主下载。
 *
 * 为什么判定器在宿主而不是插件 JS：阈值规则是要钉死的语义（设计文档04 §3），
 * 放 Kotlin 进 JVM 单测；插件只渲染判定结果，不复制规则。
 */
object ModelHub {

    /** 下载源站点。国内直连流畅（实测 11MB/s 级），是 HF 官方 API 与文件的同构镜像。单一出处见 [HubApi.BASE]。 */
    val BASE: String get() = HubApi.BASE

    /**
     * 下载目标目录：与设置页当前选中的模型位置一致（同一份 locationPath 口径），
     * 下载完成的文件经 MainViewModel.refresh() 直接进模型列表——用户无需手动导入。
     */
    fun selectedModelsDir(context: Context): File {
        val store = ModelStore(context)
        val settings = SettingsStore(context)
        val wanted = settings.locationPath
        val locs = store.locations()
        return locs.firstOrNull { it.path == wanted }?.modelsDir ?: locs.first().modelsDir
    }

    /** 手机侧配置快照（host.deviceInfo 桥返回值）。 */
    fun deviceInfoJson(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val dir = selectedModelsDir(context)
        val dirForStat = runCatching { dir }.getOrDefault(context.filesDir)
        val stat = runCatching { StatFs(dirForStat.path) }.getOrNull()
        val freeDisk = when {
            stat != null -> runCatching { stat.availableBytes }.getOrDefault(dirForStat.freeSpace)
            else -> dirForStat.freeSpace
        }
        return JSONObject()
            .put("totalRam", mem.totalMem)
            .put("availRam", mem.availMem)
            .put("freeDisk", freeDisk)
            .put("modelsDir", dir.absolutePath)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
    }
}

/** hf-mirror（HF 同构镜像）的 API 端点与解析。 */
object HubApi {

    const val BASE = "https://hf-mirror.com"

    data class HubModel(val id: String, val downloads: Long, val likes: Long)
    data class HubFile(val path: String, val size: Long)

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 热门列表：GGUF 库内按下载量降序（浏览器模式的默认页）。 */
    fun listPopularUrl(limit: Int, skip: Int = 0): String =
        "$BASE/api/models?filter=gguf&sort=downloads&direction=-1&limit=${limit.coerceIn(1, 100)}" + if (skip > 0) "&skip=$skip" else ""

    /**
     * 关键字搜索：**不带排序与过滤**——设计原话「直接搜索的话就不过滤了，搜出什么来就是什么」，
     * 顺序交给站点的相关性排序，结果也不做带不得动的隐藏（只打标记）。
     */
    fun searchUrl(keyword: String, limit: Int, skip: Int = 0): String =
        "$BASE/api/models?search=${enc(keyword)}&limit=${limit.coerceIn(1, 100)}" + if (skip > 0) "&skip=$skip" else ""

    // repoId 的 "/" 是路径分隔符，必须保留原样（hf-mirror 按 path 路由，%2F 会 404）——
    // 只按段编码段内字符（模拟器真机验证抓出过这个 bug，见设计文档04 增补2 验证节）。
    fun treeUrl(repoId: String): String =
        "$BASE/api/models/${repoId.split("/").joinToString("/") { enc(it) }}/tree/main"

    fun downloadUrl(repoId: String, path: String): String =
        "$BASE/${repoId.split("/").joinToString("/") { enc(it) }}/resolve/main/${path.split("/").joinToString("/") { enc(it) }}"

    // ---- 原生抓取（v1.1.19 起页面转正为 Compose，不再经桥的 net.fetchJson）----

    /** 单次 GET，原样返回响应体；非 2xx 抛错（带状态码与错误体首段）。 */
    fun fetchBody(url: String, timeoutMs: Int = 30_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw RuntimeException("HTTP $code：${text.take(120)}")
        return text
    }

    /** 热门列表（GGUF 库内下载量降序）。 */
    fun popular(limit: Int = 30, skip: Int = 0): List<HubModel> = parseModels(fetchBody(listPopularUrl(limit, skip)))

    /** 关键字搜索（无 sort 无 filter——设计原话「搜出什么来就是什么」）。 */
    fun search(keyword: String, limit: Int = 30, skip: Int = 0): List<HubModel> =
        parseModels(fetchBody(searchUrl(keyword, limit, skip)))

    /** 单仓 .gguf 文件清单（已排除目录项与 mmproj）。 */
    fun tree(repoId: String): List<HubFile> = parseTree(fetchBody(treeUrl(repoId)))

    /** 列表响应 → 模型条目（容忍缺字段；downloads/likes 缺省 0）。 */
    fun parseModels(body: String): List<HubModel> {
        val arr = JSONArray(body)
        val out = ArrayList<HubModel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(HubModel(id, o.optLong("downloads", 0L), o.optLong("likes", 0L)))
        }
        return out
    }

    /** 目录树响应 → 仅 .gguf 文件（排除目录项与 mmproj 投影文件；排除不带 .gguf 后缀的一切）。 */
    fun parseTree(body: String): List<HubFile> {
        val arr = JSONArray(body)
        val out = ArrayList<HubFile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type", "file") != "file") continue
            val path = o.optString("path").trim()
            if (!path.endsWith(".gguf", ignoreCase = true)) continue
            if (path.contains("mmproj", ignoreCase = true)) continue
            // imatrix = 量化校准的重要性矩阵统计（unsloth 等附带），不可运行且体积小，
            // 按大小判定必得「可运行」并抢走推荐位（用户报障：27B 仓库 13MB imatrix 判 ok）
            if (path.contains("imatrix", ignoreCase = true)) continue
            val size = o.optLong("size", o.optLong("lfs.size", 0L))
            out.add(HubFile(path, size))
        }
        return out
    }
}

/**
 * 设备判定：文件多大算「带得动」。
 *
 * 阈值（钉死语义，改动须过单测并回写设计文档04）：
 *  - ok（舒适）：文件 ≤ 0.75 × 总内存 且 ≤ 0.9 × 目标目录可用磁盘
 *  - tight（勉强）：文件 ≤ 总内存 且 ≤ 可用磁盘（mmap 能撑住，但系统内存吃紧、易触发低内存杀）
 *  - no（带不动）：其余
 * 「完全不能运行」= 连 tight 都没有（最小的量化也超 RAM）→ 浏览模式整条隐藏，搜索模式只打标。
 */
object HubJudge {

    const val COMFORT_RAM = 0.75
    const val COMFORT_DISK = 0.9

    // 近似位宽（bits per weight），只用于挑「最小量化」排序，不是精确值。
    private val RANK = mapOf(
        "iq1_s" to 1.5, "iq1_m" to 1.6,
        "iq2_xxs" to 2.1, "iq2_xs" to 2.3, "q2_k" to 2.6, "q2_k_s" to 2.6, "iq2_s" to 2.5, "iq2_m" to 2.7,
        "iq3_xxs" to 2.8, "iq3_xs" to 3.0, "q3_k_s" to 3.0, "iq3_s" to 3.1, "q3_k_m" to 3.3, "iq3_m" to 3.3, "q3_k_l" to 3.5,
        "iq4_nl" to 4.3, "iq4_xs" to 4.3, "q4_0" to 4.5, "q4_1" to 4.8, "q4_k_s" to 4.8, "q4_k" to 4.9, "q4_k_m" to 5.0,
        "q4_k_xl" to 5.2, "q5_0" to 5.5, "q5_1" to 5.7, "q5_k_s" to 5.5, "q5_k" to 5.7, "q5_k_m" to 5.7, "q5_k_xl" to 5.9,
        "q6_k" to 6.6, "q6_k_xl" to 6.8, "q7" to 7.2,
        "q8_0" to 8.5, "bf16" to 16.0, "f16" to 16.0, "fp16" to 16.0, "f32" to 32.0, "fp32" to 32.0,
    )
    private const val DEFAULT_RANK = 6.0 // 认不出的命名按中等位宽处理

    /**
     * 从文件名尾部提取量化段：「MiniCPM5-2B-Q4_K_XL.gguf」→「q4_k_xl」，「model.Q4_K_M.gguf」→「q4_k_m」。
     * 认不出（如「-cyber」这类自定义后缀）返回 null，调用方按 DEFAULT_RANK 处理。
     */
    fun parseQuant(fileName: String): String? {
        val base = fileName.removeSuffix(".gguf").removeSuffix(".GGUF").lowercase()
        // 取最后一个 '-' 或 '.' 之后的段（"Model.Q4_K_M" 与 "Model-Q4_K_M" 两种命名都要接住）
        val cut = maxOf(base.lastIndexOf('-'), base.lastIndexOf('.'))
        val tail = if (cut >= 0) base.substring(cut + 1) else base
        if (tail.isEmpty()) return null
        // 量化段形如 q4_k_m / iq3_xxs / q4_k_xl / bf16 / f16 …（字母数字下划线，含 q/iq 开头或 bf/f/fp 开头的纯位宽名）
        val m = Regex("^(i?q\\d[\\w]*|bf16|fp16|f16|f32|fp32)$").find(tail) ?: return null
        return m.value
    }

    private fun rankOf(fileName: String): Double =
        parseQuant(fileName)?.let { RANK[it] } ?: DEFAULT_RANK

    fun verdictOf(size: Long, totalRam: Long, freeDisk: Long): String = when {
        totalRam <= 0 -> "no"
        size <= 0 -> "no"
        size <= COMFORT_RAM * totalRam && size <= (COMFORT_DISK * freeDisk).toLong() -> "ok"
        size <= totalRam && size <= freeDisk -> "tight"
        else -> "no"
    }

    data class FileVerdict(val name: String, val size: Long, val quant: String?, val verdict: String)
    data class Judgment(
        val files: List<FileVerdict>,
        val bestName: String?,
        val bestSize: Long,
        val runnable: Boolean,
    ) {
        fun toJson(): JSONObject {
            val arr = JSONArray()
            files.forEach { f ->
                arr.put(JSONObject().put("name", f.name).put("size", f.size)
                    .put("quant", f.quant ?: JSONObject.NULL).put("verdict", f.verdict))
            }
            return JSONObject()
                .put("files", arr)
                .put("bestName", bestName ?: JSONObject.NULL)
                .put("bestSize", if (bestSize > 0) bestSize else JSONObject.NULL)
                .put("runnable", runnable)
        }
    }

    /**
     * @param files 该仓库的 .gguf 文件（已排除 mmproj），name → size 字节
     */
    fun judge(files: List<Pair<String, Long>>, totalRam: Long, freeDisk: Long): Judgment {
        val verdicts = files
            .map { FileVerdict(it.first, it.second, parseQuant(it.first), verdictOf(it.second, totalRam, freeDisk)) }
            .sortedBy { rankOf(it.name) } // 轻→重
        // best 只认有效量化命名的文件：认不出的（如残留校准文件）不进推荐位
        val runnablePool = verdicts.filter { it.quant != null }
        val best = runnablePool.firstOrNull { it.verdict == "ok" }
            ?: runnablePool.firstOrNull { it.verdict == "tight" }
        return Judgment(
            files = verdicts,
            bestName = best?.name,
            bestSize = best?.size ?: 0L,
            runnable = best != null,
        )
    }
}

/**
 * 模型下载器（应用级单例）：界面关掉也继续跑；同 zip 内 .part 断点续传（hf-mirror 支持 Range）。
 * 一次只跑一个下载——模型的体量决定了并发没有意义，还互相抢带宽。
 */
object ModelHubDownloader {

    enum class Phase { IDLE, RUNNING, DONE, FAILED, CANCELLED }

    data class Snap(
        val phase: Phase = Phase.IDLE,
        val repoId: String = "",
        val name: String = "",
        val received: Long = 0,
        val total: Long = 0,
        val speedBps: Long = 0,
        val error: String = "",
        val file: String = "",
    ) {
        val running: Boolean get() = phase == Phase.RUNNING
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(Snap())
    val state: StateFlow<Snap> = _state

    private val cancelFlag = AtomicBoolean(false)

    /** 下载名清洗：与 files.saveDownload 同一条纪律（文件名不碰盘符/路径分隔符）。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(120).ifBlank { "model.gguf" }

    fun start(context: Context, repoId: String, name: String, url: String, bytes: Long) {
        if (_state.value.running) throw RuntimeException("已有下载在进行（${_state.value.name}），先取消或等它完成")
        if (!url.startsWith(ModelHub.BASE + "/")) {
            // 下载源钉死在镜像站：这是一个「从模型市场下载」的通道，不是任意 URL 下载器（设计文档04 §3）。
            throw RuntimeException("下载地址必须是 ${ModelHub.BASE} 下的文件")
        }
        val safe = sanitize(name)
        val dir = ModelHub.selectedModelsDir(context)
        val target = File(dir, safe)
        val part = File(dir, "$safe.part")

        cancelFlag.set(false)
        _state.value = Snap(Phase.RUNNING, repoId, safe, received = if (part.exists()) part.length() else 0, total = bytes)

        scope.launch {
            val result = runCatching { downloadTo(url, target, part) }
            result.fold(
                onSuccess = { done ->
                    ServerBus.appendLog("[模型市场] 下载完成：${done.name}（${formatBytes(done.length())}）")
                    _state.value = Snap(Phase.DONE, repoId, done.name, done.length(), done.length(), file = done.absolutePath)
                },
                onFailure = { e ->
                    if (cancelFlag.get()) {
                        ServerBus.appendLog("[模型市场] 已取消：${safe}（.part 已保留，可断点续传）")
                        _state.value = Snap(Phase.CANCELLED, repoId, safe, error = "已取消")
                    } else {
                        ServerBus.appendLog("[模型市场] 下载失败：${safe} · ${e.message}")
                        _state.value = Snap(Phase.FAILED, repoId, safe, error = e.message ?: "未知错误")
                    }
                },
            )
        }
    }

    fun cancel() {
        if (_state.value.running) cancelFlag.set(true)
    }

    /** 清掉一次已结束的记录（页面关闭轮询后调用，避免残留 Done/Failed 卡住下一次判断）。 */
    fun dismiss() {
        if (!_state.value.running) _state.value = Snap()
    }

    fun snapshotJson(): JSONObject {
        val s = _state.value
        return JSONObject()
            .put("phase", s.phase.name.lowercase())
            .put("repoId", s.repoId)
            .put("name", s.name)
            .put("received", s.received)
            .put("total", s.total)
            .put("speed", s.speedBps)
            .put("error", s.error)
            .put("file", s.file)
    }

    private suspend fun downloadTo(urlStr: String, target: File, part: File): File {
        target.parentFile?.mkdirs()
        val total = _state.value.total

        // 多线程分片：已知大小且 ≥8MB 时 4 连接并行（hf-mirror 支持 Range）；
        // 分片文件 "<part>.0..3" 各自独立断点续传，全片完成后顺序合并；失败回退单线程。
        if (total >= 8L * 1024 * 1024) {
            runCatching { return downloadMulti(urlStr, target, part, total) }.onFailure { e ->
                if (e.message == "cancelled") throw e
                ServerBus.appendLog("[模型市场] 分片下载异常（${e.message}），回退单线程断点续传")
                chunkFiles(part).forEach { if (it.exists()) it.delete() }
            }
        }
        return downloadSingle(urlStr, target, part)
    }

    private fun chunkFiles(part: File): List<File> =
        (0 until CHUNKS).map { File(part.parentFile, "${part.name}.$it") }

    private fun chunkSum(parts: List<File>): Long = parts.sumOf { if (it.exists()) it.length() else 0L }

    private const val CHUNKS = 4

    private suspend fun downloadSingle(urlStr: String, target: File, part: File): File {
        val total = _state.value.total
        var have = if (part.exists()) part.length() else 0L

        var attempt = 0
        while (true) {
            attempt++
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw RuntimeException("HTTP $code")
            }
            if (code == 200 && have > 0) {
                // 服务器不支持 Range，回了个全量——从头来（.part 重开），别拼错位。
                have = 0
                part.delete()
            }

            val declared = when {
                code == 206 -> conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: total
                else -> conn.contentLengthLong.takeIf { it > 0 } ?: total
            }
            if (declared > 0) _state.value = _state.value.copy(total = declared)

            var lastTick = System.currentTimeMillis()
            var lastBytes = have
            var speed = _state.value.speedBps

            FileOutputStream(part, code == 206).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelFlag.get()) throw RuntimeException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        val now = System.currentTimeMillis()
                        val dt = now - lastTick
                        if (dt >= 600) {
                            val inst = ((have - lastBytes) * 1000) / dt
                            speed = if (speed <= 0) inst else (speed * 2 + inst) / 3
                            _state.value = _state.value.copy(received = have, speedBps = speed)
                            lastTick = now; lastBytes = have
                        }
                    }
                }
            }
            conn.disconnect()

            val expect = if (declared > 0) declared else have
            if (have < expect) {
                // 流断了（连接重置/超时）。.part 已落盘，续传重试；次数封顶防死循环。
                if (attempt >= 8) throw RuntimeException("传输中断（已收 $have / $expect 字节，重试次数用尽）")
                ServerBus.appendLog("[模型市场] 传输中断，3 秒后断点续传（已收 ${formatBytes(have)}）")
                delay(3000)
                continue
            }

            // 同名不覆盖：自动加 (1)/(2)（与 files.saveDownload 同一口径）
            var final = target
            if (final.exists()) {
                val base = target.name.removeSuffix(".gguf").removeSuffix(".GGUF")
                var i = 1
                while (File(target.parentFile, "$base ($i).gguf").exists()) i++
                final = File(target.parentFile, "$base ($i).gguf")
            }
            if (!part.renameTo(final)) {
                // 极端情况（跨卷 rename 失败）：拷贝兜底
                part.copyTo(final, overwrite = true)
                part.delete()
            }
            return final
        }
    }

    private suspend fun downloadMulti(urlStr: String, target: File, part: File, total: Long): File = kotlinx.coroutines.coroutineScope {
        val parts = chunkFiles(part)
        val per = total / CHUNKS
        val ranges = (0 until CHUNKS).map { i ->
            val from = i * per
            val to = if (i == CHUNKS - 1) total - 1 else (i + 1) * per - 1
            from to to
        }
        val lastTick = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val lastBytes = java.util.concurrent.atomic.AtomicLong(chunkSum(parts))
        val speed = java.util.concurrent.atomic.AtomicLong(_state.value.speedBps)

        val ticker = launch {
            while (true) {
                delay(600)
                val now = System.currentTimeMillis()
                val have = chunkSum(parts)
                val dt = now - lastTick.get()
                if (dt >= 400) {
                    val inst = ((have - lastBytes.get()) * 1000) / dt
                    val prev = speed.get()
                    speed.set(if (prev <= 0) inst else (prev * 2 + inst) / 3)
                    _state.value = _state.value.copy(received = have, speedBps = speed.get())
                    lastTick.set(now); lastBytes.set(have)
                }
            }
        }
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        (0 until CHUNKS).map { i ->
            launch {
                try {
                    downloadChunk(urlStr, parts[i], ranges[i].first, ranges[i].second)
                } catch (e: Exception) {
                    failures.add("分片$i: ${e.message}")
                }
            }
        }
        // 等全部分片结束（轮询聚合；分片失败/取消即退出）
        while (chunkSum(parts) < total && failures.isEmpty() && !cancelFlag.get()) delay(500)
        ticker.cancel()
        if (cancelFlag.get()) throw RuntimeException("cancelled")
        if (failures.isNotEmpty()) throw RuntimeException(failures.first())
        // 校验+合并（顺序 part0..3 拼接即完整文件）
        parts.forEachIndexed { i, p ->
            val expectLen = ranges[i].second - ranges[i].first + 1
            require(p.exists() && p.length() == expectLen) { "分片$i 不完整（${if (p.exists()) p.length() else -1}/$expectLen）" }
        }
        var final = target
        if (final.exists()) {
            val base = target.name.removeSuffix(".gguf").removeSuffix(".GGUF")
            var i = 1
            while (File(target.parentFile, "$base ($i).gguf").exists()) i++
            final = File(target.parentFile, "$base ($i).gguf")
        }
        FileOutputStream(final).use { out ->
            parts.forEach { p -> p.inputStream().use { it.copyTo(out, 256 * 1024) } }
        }
        parts.forEach { it.delete() }
        part.delete()   // 清掉可能残留的单线程 .part
        return@coroutineScope final
    }

    /** 单分片：区间 Range 下载，分片文件自带断点（长度=已收）。服务器不支持 Range（回 200）即抛错走单线程回退。 */
    private suspend fun downloadChunk(urlStr: String, file: File, from: Long, to: Long) {
        val want = to - from + 1
        var have = if (file.exists()) file.length() else 0L
        if (have > want) { file.delete(); have = 0L }
        var attempt = 0
        while (have < want) {
            attempt++
            if (attempt > 8) throw RuntimeException("分片重试次数用尽（已收 $have/$want）")
            if (cancelFlag.get()) throw RuntimeException("cancelled")
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=${from + have}-$to")
            }
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); throw RuntimeException("HTTP $code") }
            if (code != 206) { conn.disconnect(); throw RuntimeException("分片下载不被支持（服务器未回 206）") }
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(have)
                conn.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (have < want) {
                        if (cancelFlag.get()) throw RuntimeException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        val cap = (want - have).toInt().coerceAtMost(n)
                        raf.write(buf, 0, cap)
                        have += cap
                    }
                }
            }
            conn.disconnect()
            if (have < want) {
                delay(3000)
                ServerBus.appendLog("[模型市场] 分片中断续传（${file.name}，已收 ${formatBytes(have)}/${formatBytes(want)}）")
            }
        }
    }

    private fun formatBytes(b: Long): String
 = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.0f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
