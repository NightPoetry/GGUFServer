package com.ggufserver.app.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生运行时插件（《工具插件规范》§13.5）的契约：认领一种能力 `type`（tool / skill / workflow / …），
 * 在 curate/context 面驱动该 type 的所有 callable。内核（Gateway）只认「callable 有个 type，
 * type → 运行时插件」这一条注册关系；**加一种能力类型 = 加一个 CapabilityRuntime，内核不改**。
 *
 * 运行时插件可以是原生（内置，如本文件的 [ToolRuntime]）也可以是 JS（用户上传）；内核对二者一视同仁。
 * 当前接口只含 curate/context 都需要的统一触点（[callablesIn] + [callableNamesOf] + [teachingClause]）；
 * stream/after 触点由具体 runtime 各自以专用方法暴露（如 [ToolRuntime.makeScanner]/[ToolRuntime.execute]）。
 *
 * **这个形状已被三个真样例验证，别再动它**（2026-07-26，桌面端「小模型轮椅」反哺，见规范 §13.5 末）：
 * 那边有真的技能引擎与真的工作流引擎，tool/skill/workflow 三个 runtime 做下来，共同触点仍然只有
 * 上面这三个；stream/after **不该**上提——三者的执行面天然分叉（tool 走分发、skill 走注入指令、
 * workflow 走协调器状态机），硬上提只会造出一个谁都不合身的形状。原先「只有一个样例怕抽错抽象」
 * 的顾虑到此作废，阶段 4 只剩「写引擎」。
 */
interface CapabilityRuntime {
    val type: String

    /** 从一组启用插件里挑出属于本 type 的 callable。 */
    fun callablesIn(active: List<PluginManifest>): List<PluginManifest>

    /**
     * 某插件在本 type 下提供的 callable 名字。内核用它算 curate 的**弃权豁免**（§13.4）：
     * 没在 curate 面表态的插件，其 callable 不因别人的收窄而消失。内核因此不必认识
     * `manifest.tools[].name` 这种 type 专属形状。
     */
    fun callableNamesOf(p: PluginManifest): List<String>

    /**
     * curate/context 面：教模型怎么触发本 type 的 callable，返回追加进首条 system 的教学条款；空则 null。
     * [exposure]＝本轮 curate 决定暴露的 callable 名集合（§13.4）：null = 全露（默认，等价 v1）；
     * 非 null = 只教 name ∈ exposure 的 callable（小模型上下文金贵，按本轮意图收窄）。
     */
    fun teachingClause(callables: List<PluginManifest>, exposure: Set<String>?): String?
}

/**
 * 内核认识的运行时插件集合（§13.5）。加一种能力类型 = 往这里加一个 [CapabilityRuntime]，
 * Gateway 只按 [all] 迭代教学条款、按 type 找执行者，本身不认识任何具体能力类型。
 * 阶段 2：仅内置 tool；阶段 4+ 会加入 skill/workflow 运行时（桌面端「小模型轮椅」实现同名 type）。
 */
object Runtimes {
    val all: List<CapabilityRuntime> = listOf(ToolRuntime)
    fun byType(type: String): CapabilityRuntime? = all.firstOrNull { it.type == type }
}

/** 一次被 [ToolTagScanner] 从令牌流里认出的工具调用（§6.9）。 */
data class ToolCall(val plugin: PluginManifest, val name: String, val input: String)

/** 工具执行产物：经收尾 chunk 的 `gguf` 扩展字段下发的 notice / card（§6.8 通知通道 / §6.12 消息卡片）。 */
class ToolOutcome {
    val notices = JSONArray()
    val cards = JSONArray()
    fun isEmpty(): Boolean = notices.length() == 0 && cards.length() == 0
}

/**
 * 内置「工具系统」= `type:tool` 运行时插件（§13.5）。把原先散在 `Gateway.kt` 里的三段收敛成一处，
 * 行为与迁移前逐字节一致（relay 的流循环结构不变，只是扫描器/执行改由本对象提供）：
 * - **curate/context**：[teachingClause] 生成 `<tool name="…">…</tool>` 教学条款（§6.9）。
 * - **stream**：[makeScanner] 造 [ToolTagScanner]，边转发边扫标记、暂扣≤4KB、吞完整标记。
 * - **after**：[execute] 触发 `gguf.hooks.tool`，把 notice/card 收进 [ToolOutcome]。
 *
 * 注：客户端自带原生 OpenAI `tools` 时整段不介入（不教学、不扫描），由 Gateway 在投影时把
 * `toolCallables` 置空（§6.9 透传），本对象不需要知道该请求级条件。
 */
object ToolRuntime : CapabilityRuntime {
    override val type = "tool"

    override fun callablesIn(active: List<PluginManifest>): List<PluginManifest> =
        active.filter { it.tools.isNotEmpty() }

    override fun callableNamesOf(p: PluginManifest): List<String> = p.tools.map { it.name }

    override fun teachingClause(callables: List<PluginManifest>, exposure: Set<String>?): String? {
        // 先按 exposure 逐工具过滤生成条目行；exposure==null（无 curate 处理器）时全部保留＝等价 v1。
        val lines = StringBuilder()
        for (p in callables) {
            for (t in p.tools) {
                if (exposure != null && t.name !in exposure) continue
                val sample = if (t.input == "json") "{...}" else "文本内容"
                lines.append("- <tool name=\"").append(t.name).append("\">").append(sample).append("</tool>")
                if (t.description.isNotEmpty()) lines.append("：").append(t.description)
                lines.append("\n")
            }
        }
        if (lines.isEmpty()) return null // 无 callable，或 curate 把本 type 全部收窄掉 → 不注入教学
        return "你可以在明确需要时于回复末尾追加下列标记来触发对应操作" +
            "（务必先用一段正常文字回应用户，标记只能跟在正文最后一行；没有明确需要时绝不要输出标记）：\n" + lines
    }

    /** stream 面：为本 type 的 callable 造流扫描器；无 callable 返回 null（relay 据此走"不扫描"快路径）。 */
    fun makeScanner(callables: List<PluginManifest>): ToolTagScanner? {
        if (callables.isEmpty()) return null
        val names = callables.flatMap { it.tools.map { t -> Regex.escape(t.name) } }
        return ToolTagScanner(Regex("<tool\\s+name=\"(${names.joinToString("|")})\"\\s*>([\\s\\S]*?)</tool>"))
    }

    /** 把扫描器认出的工具名映射回拥有它的插件。 */
    fun ownerOf(callables: List<PluginManifest>, toolName: String): PluginManifest? =
        callables.firstOrNull { p -> p.tools.any { it.name == toolName } }

    /** after 面：执行一次工具调用（§6.9 执行时机）。[call] 为 null（本轮无标记）→ 空产物、零副作用。 */
    suspend fun execute(call: ToolCall?, turn: JSONObject): ToolOutcome {
        val outcome = ToolOutcome()
        call ?: return outcome
        val payload = JSONObject().put("name", call.name).put("input", call.input).put("turn", turn)
        val result = PluginRuntime.dispatch(call.plugin.id, "tool", payload, Aspects.TOOL_BUDGET_MS)
        if (result != null && result.has("notice")) {
            result.optString("notice").ifBlank { null }?.let {
                outcome.notices.put(JSONObject().put("plugin", call.plugin.id).put("text", it))
            }
        }
        // 消息卡片（§6.12 工具路径）：工具返回 card = 在回复后追加一条插件自绘的卡片消息；
        // 要求 chat:card 权限；外部标准 OpenAI 客户端忽略 gguf 扩展字段，零影响。
        if (result != null && "chat:card" in call.plugin.permissions) {
            result.optJSONObject("card")?.let { c ->
                outcome.cards.put(
                    JSONObject().put("plugin", call.plugin.id)
                        .put("file", c.optString("file").ifBlank { "card.html" })
                        .put("data", c.optJSONObject("data") ?: JSONObject())
                )
            }
        }
        return outcome
    }
}

/**
 * 边转发边扫描 `<tool` 标记（规范 §6.9）：疑似标记起点开始暂扣（上限 [CAP]=4KB），
 * 确认为完整合法标记（标签名必须是 [tagRe] 里已声明的工具之一）→ 吞下不转发；
 * 证伪（超出暂扣上限仍未匹配）→ 原样放行。由 [ToolRuntime.makeScanner] 构造。
 *
 * 从 Gateway 原样迁入（逻辑逐字节不变）——它是流式回合零标记泄漏的核心，属 stream 面的工具化专用件。
 */
class ToolTagScanner(private val tagRe: Regex) {
    private var suspect: StringBuilder? = null

    sealed class Outcome {
        data class Forward(val text: String) : Outcome()
        object Hold : Outcome()
        data class Resolved(val forwardText: String, val toolName: String?, val toolInput: String?) : Outcome()
    }

    fun feed(piece: String): Outcome {
        val cur = suspect
        if (cur == null) {
            if (piece.isEmpty()) return Outcome.Forward(piece)
            val idx = piece.indexOf(TAG_START)
            if (idx < 0) {
                val partial = partialPrefixLen(piece)
                return if (partial == 0) {
                    Outcome.Forward(piece)
                } else {
                    val headText = piece.substring(0, piece.length - partial)
                    suspect = StringBuilder(piece.substring(piece.length - partial))
                    if (headText.isEmpty()) Outcome.Hold else Outcome.Forward(headText)
                }
            }
            val headText = piece.substring(0, idx)
            suspect = StringBuilder(piece.substring(idx))
            return tryResolve(headText)
        }
        cur.append(piece)
        return tryResolve("")
    }

    private fun partialPrefixLen(piece: String): Int {
        val maxLen = minOf(TAG_START.length - 1, piece.length)
        for (len in maxLen downTo 1) {
            if (TAG_START.startsWith(piece.substring(piece.length - len))) return len
        }
        return 0
    }

    private fun tryResolve(precedingForward: String): Outcome {
        val cur = suspect!!
        val m = tagRe.find(cur)
        if (m != null) {
            val toolName = m.groupValues[1]
            val toolInput = m.groupValues[2]
            val before = cur.substring(0, m.range.first)
            val after = cur.substring((m.range.last + 1).coerceAtMost(cur.length))
            suspect = null
            return Outcome.Resolved(precedingForward + before + after, toolName, toolInput)
        }
        if (cur.length > CAP) {
            val text = precedingForward + cur.toString()
            suspect = null
            return Outcome.Forward(text)
        }
        return if (precedingForward.isEmpty()) Outcome.Hold else Outcome.Forward(precedingForward)
    }

    data class Tail(val leftoverText: String, val toolName: String?, val toolInput: String?)

    /** 流结束时调用，解决尾部还没确认的暂扣内容。 */
    fun finish(): Tail {
        val cur = suspect ?: return Tail("", null, null)
        val m = tagRe.find(cur)
        return if (m != null) {
            val before = cur.substring(0, m.range.first)
            val after = cur.substring((m.range.last + 1).coerceAtMost(cur.length))
            Tail(before + after, m.groupValues[1], m.groupValues[2])
        } else {
            Tail(cur.toString(), null, null)
        }
    }

    companion object {
        private const val TAG_START = "<tool"
        private const val CAP = 4096
    }
}
