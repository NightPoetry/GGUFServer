package com.ggufserver.app.plugin

import org.json.JSONObject

/**
 * 插件手势声明（《工具插件规范》§4）。v1 宿主只实现 top 边缘，其余边缘按“无手势”处理，
 * 字段本身仍然解析出来，供管理页展示与将来扩展。
 */
data class PluginGesture(
    val edge: String,          // top | bottom | left | right
    val type: String,          // v1 只有 swipe
    val rangeStart: Float,     // 0~1
    val rangeEnd: Float,       // 0~1，要求 > rangeStart
    val thresholdDp: Int,
)

/** manifest » tools[] 里的一项（§6.9 工具调用协议）。 */
data class PluginTool(
    val name: String,          // 必须以插件短名为前缀，如 notes.append
    val description: String,
    val input: String,         // "text" | "json"
)

/**
 * manifest » messageActions[] 里的一项（§6.11 消息角标）：消息下方动作行（复制/重试那排）
 * 里属于本插件的一个角标按钮，点击时把该条消息派发给 service.js 的 messageAction 钩子。
 */
data class PluginMessageAction(
    val id: String,            // 必须以插件短名为前缀，如 tts.speak
    val label: String,         // 角标文字，≤ 6 字符（视觉上与「复制」同排，宜 2 字）
    val icon: String?,         // 可选：插件目录内的专属图标（96×96 alpha 蒙版）；省略用 icon.png
    val roles: Set<String>,    // 出现在哪些消息下："assistant" / "user"，默认仅 assistant
)

/**
 * manifest.json 的强类型表示（《工具插件规范》§2.1）。所有解析/校验逻辑集中在
 * [parse]，供 zip 安装、内置插件释放两条路径共用，保证同一套规则。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val description: String,
    val author: String,
    val entryUi: String,
    val entryService: String?,
    val gesture: PluginGesture?,
    val panelSize: Float,
    /** panel.topbar（默认 true）：false = 不占顶栏直显图标位（纯角标/纯后台插件用），
     *  启动台、门户选项卡、手势、管理页照常可达——启动台是永远的兜底入口。 */
    val panelTopbar: Boolean,
    val permissions: Set<String>,
    val hooks: Set<String>,
    /** middleware.scope：作者声明的默认生效范围（local/external）。 */
    val scope: Set<String>,
    /** middleware.configurable（§6.8）：true = 在管理页向用户暴露「生效范围」三选一
     *  （仅对内 / 内外通用 / 仅对外），用户选择覆盖 scope 默认值。要求 chat:external 权限。 */
    val scopeConfigurable: Boolean,
    val tools: List<PluginTool>,
    val messageActions: List<PluginMessageAction>,
    /** provides（§6.13 能力提供者）：本插件接管的宿主能力点（v1 仅 "tts"）。
     *  补丁语义——所有对该能力的调用改道进本插件的同名钩子；插件自己调该能力 = 调穿到默认实现。 */
    val provides: Set<String>,
    val systemClause: String,
    /** 可选：设置页 UI 卡片入口（html 文件名）。声明后，插件在插件管理页展开时渲染一张属于自己的
     *  常驻配置卡片（如重型记忆插件选记忆模型），跑在与面板同源的 WebView 里、共用 window.gguf 桥。 */
    val settingsCard: String?,
) {
    /** id 的最后一段，如 com.gguf.notes -> notes。工具名前缀、管理页短显示用。 */
    val shortId: String get() = id.substringAfterLast('.')

    companion object {
        /** 宿主当前提供的桥接口版本，见规范 §12：插件要求 > 此值即拒绝启用。 */
        const val HOST_API_VERSION = 1

        private val ID_RE = Regex("^[a-z0-9]+(\\.[a-z0-9-]+)+$")
        private val TOOL_NAME_RE = Regex("^[a-z0-9.-]+$")
        private val EDGES = setOf("top", "bottom", "left", "right")

        /** 校验通过返回 manifest；失败返回中文原因（调用方据此拒绝安装/启用）。 */
        fun parse(json: String): Result<PluginManifest> {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return Result.failure(ManifestError("manifest.json 不是合法 JSON：${e.message}"))
            }
            return parse(root)
        }

        fun parse(root: JSONObject): Result<PluginManifest> {
            fun fail(msg: String) = Result.failure<PluginManifest>(ManifestError(msg))

            val id = root.optString("id").trim()
            if (id.isEmpty()) return fail("缺少必需字段 id")
            if (!ID_RE.matches(id)) return fail("id 格式不合法，需形如反域名（如 com.gguf.notes）：$id")

            val name = root.optString("name").trim()
            if (name.isEmpty()) return fail("缺少必需字段 name")

            val version = root.optString("version").trim()
            if (version.isEmpty()) return fail("缺少必需字段 version")

            if (!root.has("apiVersion")) return fail("缺少必需字段 apiVersion")
            val apiVersion = root.optInt("apiVersion", -1)
            if (apiVersion <= 0) return fail("apiVersion 字段非法")

            val entry = root.optJSONObject("entry") ?: return fail("缺少必需字段 entry")
            val entryUi = entry.optString("ui").trim()
            if (entryUi.isEmpty()) return fail("entry.ui 未指定")
            val entryService = entry.optString("service").trim().ifEmpty { null }

            val hooks = jsonStrings(root.optJSONArray("hooks"))
            if (hooks.isNotEmpty() && entryService == null) return fail("声明了 hooks 但未指定 entry.service")

            val permissions = jsonStrings(root.optJSONArray("permissions"))

            for (h in hooks) {
                val need = when (h) {
                    "route" -> "chat:route"
                    "curate" -> "chat:context" // 本轮给模型露哪些 callable（§13.4；与 context 同属"影响模型所见"域）
                    "context" -> "chat:context"
                    "afterReply" -> "chat:annotate"
                    "continuation" -> "chat:annotate" // 流式回复后追加续段（两段式：回复→回忆中→续段）
                    "params" -> "chat:params" // 按任务自适应采样参数（§6.10 参数导演）
                    "messageAction" -> "chat:action" // 消息角标点击（§6.11，纯 UI 侧钩子，不进网关管线）
                    else -> null // 未知钩子名：向前兼容忽略，宿主也不会分发给它
                }
                if (need != null && need !in permissions) {
                    return fail("钩子 $h 需要权限 $need，但 permissions 未声明该权限")
                }
            }

            val middlewareObj = root.optJSONObject("middleware")
            val scope = middlewareObj?.optJSONArray("scope")
                ?.let { jsonStrings(it) }
                ?.ifEmpty { setOf("local") }
                ?: setOf("local")
            if ("external" in scope && "chat:external" !in permissions) {
                return fail("middleware.scope 含 external 需要权限 chat:external")
            }
            val scopeConfigurable = middlewareObj?.optBoolean("configurable", false) ?: false
            if (scopeConfigurable && "chat:external" !in permissions) {
                // 三选一里有「对外」选项，用户改选就是在授予对外影响——权限必须在启用时已确认过
                return fail("middleware.configurable 需要权限 chat:external")
            }

            val shortId = id.substringAfterLast('.')
            val toolsArr = root.optJSONArray("tools")
            val tools = ArrayList<PluginTool>()
            if (toolsArr != null && toolsArr.length() > 0) {
                if ("chat:annotate" !in permissions) return fail("声明 tools 需要权限 chat:annotate")
                for (i in 0 until toolsArr.length()) {
                    val t = toolsArr.optJSONObject(i) ?: continue
                    val tname = t.optString("name").trim()
                    if (tname.isEmpty() || !TOOL_NAME_RE.matches(tname)) {
                        return fail("工具名不合法（需匹配 ^[a-z0-9.-]+$）：$tname")
                    }
                    if (!tname.startsWith("$shortId.")) {
                        return fail("工具名必须以插件短名 “$shortId.” 为前缀：$tname")
                    }
                    val inputRaw = t.optString("input", "text")
                    tools += PluginTool(tname, t.optString("description"), if (inputRaw == "json") "json" else "text")
                }
            }

            val actionsArr = root.optJSONArray("messageActions")
            val messageActions = ArrayList<PluginMessageAction>()
            if (actionsArr != null && actionsArr.length() > 0) {
                // 角标点击要派发进 service.js，走与网关钩子相同的分发机制：必须显式声明
                // messageAction 钩子（连带保证 chat:action 权限与 entry.service 存在）。
                if ("messageAction" !in hooks) return fail("声明 messageActions 需要在 hooks 声明 messageAction")
                for (i in 0 until actionsArr.length()) {
                    val a = actionsArr.optJSONObject(i) ?: continue
                    val aid = a.optString("id").trim()
                    if (aid.isEmpty() || !TOOL_NAME_RE.matches(aid)) {
                        return fail("角标 id 不合法（需匹配 ^[a-z0-9.-]+$）：$aid")
                    }
                    if (!aid.startsWith("$shortId.")) {
                        return fail("角标 id 必须以插件短名 “$shortId.” 为前缀：$aid")
                    }
                    val label = a.optString("label").trim()
                    if (label.isEmpty()) return fail("角标 $aid 缺少 label")
                    if (label.length > 6) return fail("角标 label 超过 6 字符上限：$label")
                    val roles = jsonStrings(a.optJSONArray("roles"))
                        .filter { it == "assistant" || it == "user" }.toSet()
                        .ifEmpty { setOf("assistant") }
                    messageActions += PluginMessageAction(
                        id = aid,
                        label = label,
                        icon = a.optString("icon").trim().ifEmpty { null },
                        roles = roles,
                    )
                }
            }

            val provides = jsonStrings(root.optJSONArray("provides"))
            if (provides.isNotEmpty()) {
                // 提供者钩子在 service.js 里响应，必须有后台脚本；未知能力名向前兼容忽略（不会被分发）
                if (entryService == null) return fail("声明 provides 需要指定 entry.service")
                for (cap in provides) {
                    val need = when (cap) {
                        "tts" -> "cap:tts" // 接管发声：其它插件/宿主的朗读文本改由本插件发声
                        // 接管推理：别的插件调 gguf.llm.* 时改由本插件回答（外部 API 兜底，§6.16）。
                        // 风险最高的一个能力点——**对话内容会离开这台手机**，所以单列权限、装时明示。
                        "llm" -> "cap:llm"
                        else -> null
                    }
                    if (need != null && need !in permissions) {
                        return fail("提供能力 $cap 需要权限 $need，但 permissions 未声明该权限")
                    }
                }
            }

            val systemClause = root.optString("systemClause")
            if (systemClause.length > 400) return fail("systemClause 超过 400 字符上限")
            if (systemClause.isNotEmpty() && "chat:context" !in permissions) {
                return fail("systemClause 需要权限 chat:context")
            }

            val settingsCard = root.optString("settingsCard").trim().ifEmpty { null }

            val gesture = root.optJSONObject("gesture")?.let { g ->
                val edge = g.optString("edge")
                if (edge !in EDGES) return@let null
                val rangeArr = g.optJSONArray("range")
                val start = (rangeArr?.optDouble(0) ?: 0.0).toFloat().coerceIn(0f, 1f)
                val end = (rangeArr?.optDouble(1) ?: 1f.toDouble()).toFloat().coerceIn(0f, 1f)
                if (end <= start) return@let null
                PluginGesture(edge, g.optString("type", "swipe"), start, end, g.optInt("threshold", 48))
            }
            val panelObj = root.optJSONObject("panel")
            val panelSize = (panelObj?.optDouble("size", 0.6) ?: 0.6)
                .toFloat().coerceIn(0.3f, 1.0f)
            val panelTopbar = panelObj?.optBoolean("topbar", true) ?: true

            return Result.success(
                PluginManifest(
                    id = id,
                    name = name,
                    version = version,
                    apiVersion = apiVersion,
                    description = root.optString("description"),
                    author = root.optString("author"),
                    entryUi = entryUi,
                    entryService = entryService,
                    gesture = gesture,
                    panelSize = panelSize,
                    panelTopbar = panelTopbar,
                    permissions = permissions,
                    hooks = hooks,
                    scope = scope,
                    scopeConfigurable = scopeConfigurable,
                    tools = tools,
                    messageActions = messageActions,
                    provides = provides,
                    systemClause = systemClause,
                    settingsCard = settingsCard,
                )
            )
        }

        private fun jsonStrings(arr: org.json.JSONArray?): Set<String> {
            arr ?: return emptySet()
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out += s
            }
            return out
        }

        /**
         * 校验 entry 指向的文件确实存在。zip 安装与已装目录复用同一份规则，
         * [exists] 由调用方给出（zip 条目查找 / File.exists），保持 manifest 解析不依赖 IO。
         */
        fun validateEntries(m: PluginManifest, exists: (String) -> Boolean): String? {
            if (!exists("icon.png")) return "缺少必需文件 icon.png"
            if (!exists(m.entryUi)) return "entry.ui 指向的文件不存在：${m.entryUi}"
            if (m.hooks.isNotEmpty()) {
                val svc = m.entryService
                if (svc == null || !exists(svc)) return "声明了 hooks，但 entry.service 指向的文件不存在：${svc ?: "(未指定)"}"
            }
            if (m.settingsCard != null && !exists(m.settingsCard)) return "settingsCard 指向的文件不存在：${m.settingsCard}"
            for (a in m.messageActions) {
                val icon = a.icon ?: continue
                if (!exists(icon)) return "角标 ${a.id} 的 icon 指向的文件不存在：$icon"
            }
            return null
        }

        /**
         * 粗粒度 semver 比较：按 `.`/`-`/`+` 切分后逐段按数字比较（非数字段视为 0）。
         * 返回值 >0 表示 a 版本更高，用于「同 id 高版本覆盖升级」判定。
         */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-', '+')
            val pb = b.split('.', '-', '+')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val na = pa.getOrNull(i)?.toIntOrNull() ?: 0
                val nb = pb.getOrNull(i)?.toIntOrNull() ?: 0
                if (na != nb) return na - nb
            }
            return 0
        }
    }
}

/** manifest 校验失败的原因（中文），installFromZip/内置插件释放都靠 message 展示给用户。 */
class ManifestError(message: String) : Exception(message)
