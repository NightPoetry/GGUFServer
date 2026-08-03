package com.ggufserver.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ggufserver.app.data.ChatStore
import com.ggufserver.app.data.ChatSummary
import com.ggufserver.app.data.SettingsStore
import com.ggufserver.app.data.StoredMsg
import com.ggufserver.app.plugin.PluginRuntime
import com.ggufserver.app.server.LlamaServerService
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** 助手回复的一个候选版本：重试（重新生成）会积累多个候选，用户用 ‹ › 切换采用哪个。 */
data class AltReply(
    val content: String = "",
    val reasoning: String = "",
    val genTokens: Int = 0,
    val genMs: Long = 0,
    val noted: String? = null,
    val error: String? = null,
)

data class ChatMessage(
    val id: Long,
    val role: String,               // "user" | "assistant"
    val content: String = "",
    val reasoning: String = "",     // 思考模型的 reasoning_content（默认被 --reasoning off 关掉，开着时才有）
    val streaming: Boolean = false,
    val error: String? = null,
    // 生成速度标注：流式时按收到的增量实时累计，结束时用服务端 timings 校准
    val genTokens: Int = 0,         // 已生成 token 数（含 reasoning）
    val genMs: Long = 0,            // 从首个 token 起的生成耗时（毫秒）
    val ts: Long = 0,               // 消息产生时间（epoch ms）——给模型时间感用，随消息带 gguf_ts 发给网关
    val noted: String? = null,      // 插件确认条文案（来自网关收尾 chunk 的 gguf.notices），UI 渲染成图标确认条，不进正文
    // 重试候选：为空 = 从未重试过（正文就在上面的字段里）；非空 = 全部候选（含当前采用的），
    // 上面的展示字段始终镜像 alts[altIdx]。后续对话的上下文用的就是当前采用的候选（见 buildHistory.textOf）。
    val alts: List<AltReply> = emptyList(),
    val altIdx: Int = 0,
    // 插件消息卡片（《工具插件规范》§6.12）：cardPlugin 非空 = 这条消息由该插件的 WebView 卡片渲染，
    // content 存卡片的「内部文本」（进上下文、可复制、插件禁用时的降级显示）。
    val cardPlugin: String? = null, // 插件 id
    val cardFile: String? = null,   // 插件目录内的卡片页文件名（默认 card.html）
    val cardData: String? = null,   // 传给卡片页的 JSON（window.__ggufCard.data）
)

/**
 * 预览聊天：直接调用本机正在运行的 llama-server（走 127.0.0.1:公开端口，
 * 与外部客户端同一条链路，开了图片压缩/插件时同样经过网关）。
 * 会话自动持久化到 ChatStore，可随时切换/删除历史会话。
 *
 * 第三阶段瘦身后，本类只剩：消息状态、SSE 流式客户端（带 X-GGUF-Session 头 + 每条消息的
 * gguf_ts 字段）、解析收尾 chunk 的 gguf.notices 渲染确认条、HTML 预览提取、会话持久化。
 * 便签路由/两阶段管线、窗口裁剪、时间标注、回忆注入全部迁到网关与内置插件
 * （见《工具插件规范》§11 迁移终态）。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /**
         * 内置系统提示词（仅本机聊天）。用户在「模型配置」里填了自己的提示词时被整体覆盖（无感替换）。
         * 保持逐字节恒定 + 永远排在消息最前 → 命中 llama-server 的前缀缓存，KV 只预填充一次。
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个可靠且有边界感的助手。请始终遵守：\n" +
                "1. 拒绝提供可能在现实中造成危害的具体指导（如暴力伤害、武器制作、违法犯罪、入侵破坏等），简短说明原因即可，不说教。\n" +
                "2. 角色扮演、小说等虚构创作可以正常进行；若情节涉及危险或有害行为，必须在回复末尾另起一行注明：现实中请勿模仿。\n" +
                "3. 涉及医疗、用药或健康的问题，可以给出常识性信息，但必须在回复末尾另起一行注明：仅供参考，如有不适请及时就医。\n" +
                "4. 回答使用 Markdown。所有代码（HTML、CSS、脚本、命令、配置等）必须放进 ``` 围栏代码块并标注语言" +
                "（如 ```html），绝不把代码散写在正文段落里；行内提及代码用反引号包住。" +
                "换行就直接换行，不要在正文里写 \\n 这样的转义字符。\n" +
                "5. 你运行在一台手机的本地应用里，没有联网能力，但有一项特殊能力：" +
                "只要你把完整网页代码放进 ```html 代码块，应用就会自动打开内置浏览器把页面渲染给用户看。" +
                "所以用户要网页/页面/小工具时，直接给出完整的单文件 HTML（CSS 和 JS 内联），不用解释怎么保存或打开。\n" +
                "6. 注意时间：对话历史里带「（X月X日）」前缀的消息是那一天说的，结合背景资料里的现在时间判断间隔。" +
                "过去消息里的『今天/明天』指的是消息发生当天的今天/明天，不是现在——" +
                "比如两天前用户说『今天下雨』，现在问今天是否下雨，你应该说不确定现在、但两天前下过。\n" +
                "7. 其余情况正常、直接地回答，不要在无关话题上滥用以上声明。"
    }

    private val settings = SettingsStore(app)
    private val chatStore = ChatStore(app)

    val messages = mutableStateListOf<ChatMessage>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set

    var sessions by mutableStateOf<List<ChatSummary>>(chatStore.list())
        private set
    var currentId by mutableStateOf<Long?>(null)
        private set

    // --- 右滑抽屉的模型配置（应用后重启服务生效） ---
    var sysPrompt by mutableStateOf(settings.chatSystemPrompt)
    var temperature by mutableStateOf(settings.chatTemperature)
    var topP by mutableStateOf(settings.chatTopP)

    /** 发送后自动收起键盘（立即生效，无需应用）。 */
    var blurOnSend by mutableStateOf(settings.chatBlurOnSend)
        private set

    fun updateBlurOnSend(b: Boolean) {
        blurOnSend = b
        settings.chatBlurOnSend = b
    }

    private var job: Job? = null
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var userStopped = false
    private var nextId = 0L

    init {
        // 插件经 gguf.card.post 主动插入的消息卡片（§6.12 桥路径；工具路径走收尾 chunk 的 gguf.cards）。
        // 只落进它声明的会话（null = 当前会话）；不在看的会话里发的卡片直接丢弃——插件应即时发、不做跨会话投递。
        viewModelScope.launch {
            com.ggufserver.app.plugin.PluginStore.cardPosts.collect { post ->
                val cur = currentId?.toString()
                if (post.sessionId == null || post.sessionId == cur) {
                    appendCardMessage(post.pluginId, post.file, post.dataJson)
                    persist()
                }
            }
        }
    }

    /**
     * 工具路径的卡片附到本轮回复消息上（正文照常保留，正文为空时用卡片内部文本充当 content，
     * 供上下文/复制/降级用）。该消息已经带了卡片时才退化为追加独立消息（将来一轮多工具的情形）。
     */
    private fun attachCard(msgId: Long, pluginId: String, file: String, dataJson: String) {
        if (pluginId.isBlank()) return
        val i = messages.indexOfLast { it.id == msgId }
        if (i < 0 || messages[i].cardPlugin != null) {
            appendCardMessage(pluginId, file, dataJson)
            return
        }
        val text = runCatching { JSONObject(dataJson).optString("text") }.getOrDefault("")
        messages[i] = messages[i].copy(
            cardPlugin = pluginId,
            cardFile = file.ifBlank { "card.html" },
            cardData = dataJson,
            content = messages[i].content.ifBlank { text },
        )
    }

    /** 追加一条独立的插件卡片消息（gguf.card.post 桥路径；content = data.text，进上下文/复制/降级显示）。 */
    private fun appendCardMessage(pluginId: String, file: String, dataJson: String) {
        if (pluginId.isBlank()) return
        val text = runCatching { JSONObject(dataJson).optString("text") }.getOrDefault("")
        messages.add(
            ChatMessage(
                id = nextId++, role = "assistant", content = text,
                ts = System.currentTimeMillis(),
                cardPlugin = pluginId,
                cardFile = file.ifBlank { "card.html" },
                cardData = dataJson,
            )
        )
    }

    /**
     * 最新完成的回复里提取到的 HTML（```html 代码块）。
     * 非空时聊天界面会自动拉下「交互门户」并切到浏览器选项卡渲染它；消费后置空。
     */
    var htmlPreview by mutableStateOf<String?>(null)
        private set

    fun consumeHtmlPreview() { htmlPreview = null }

    /** 从回复全文提取最后一个 ```html 围栏代码块（写网页时通常是完整文档）。 */
    private fun extractHtml(text: String): String? {
        val re = Regex("```[ \\t]*html[ \\t]*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val block = re.findAll(text).lastOrNull()?.groupValues?.get(1)?.trim()
        return if (block != null && block.contains('<') && block.length > 40) block else null
    }

    // --- 会话标签（会话元数据，服务历史抽屉的智能分组；编辑入口在历史抽屉，见 ChatScreen） ---
    var chatTags by mutableStateOf<List<String>>(emptyList())
        private set

    /** 标签规范化（参考小模型轮椅）：去 #、去空白、每个 ≤24 字、去重、最多 12 个。 */
    private fun cleanTags(tags: List<String>): List<String> = tags
        .flatMap { it.split(Regex("[,，;；#、\\s]+")) }
        .map { it.trim().removePrefix("#").take(24) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(12)

    /**
     * 编辑任意会话的标签（历史抽屉入口，标签留在宿主——它是会话元数据而非插件数据）：
     * 正是当前会话时更新内存态并走正常 persist()；不是当前会话则只改磁盘上的会话文件，
     * 不影响正在看的这个会话。
     */
    fun updateTags(id: Long, tags: List<String>) {
        val cleaned = cleanTags(tags)
        if (id == currentId) {
            chatTags = cleaned
            persist()
            return
        }
        val stored = chatStore.load(id) ?: return
        val title = sessions.firstOrNull { it.id == id }?.title ?: "对话"
        chatStore.save(id, title, stored.messages, cleaned)
        sessions = chatStore.list()
    }

    fun newChat() {
        stopStreaming()
        persist()
        messages.clear()
        chatTags = emptyList()
        val id = System.currentTimeMillis()
        currentId = id
        // 让插件桥的 gguf.chat.current()/messages() 从「新对话」这一刻起就有会话 id 可用，
        // 不必等到真正发出第一条消息（面板 UI 主动打开时也用得上）。
        PluginRuntime.noteLocalTurn(id.toString())
    }

    fun openChat(id: Long) {
        if (id == currentId) return
        stopStreaming()
        persist()
        val stored = chatStore.load(id) ?: return
        messages.clear()
        stored.messages.forEach { m ->
            messages += ChatMessage(
                nextId++, m.role, m.content, m.reasoning,
                genTokens = m.genTokens, genMs = m.genMs, ts = m.ts,
                noted = m.noted.ifEmpty { null },
                alts = m.alts.map {
                    AltReply(it.content, it.reasoning, it.genTokens, it.genMs, it.noted.ifEmpty { null }, it.error.ifEmpty { null })
                },
                altIdx = m.altIdx,
                cardPlugin = m.cardPlugin.ifEmpty { null },
                cardFile = m.cardFile.ifEmpty { null },
                cardData = m.cardData.ifEmpty { null },
            )
        }
        chatTags = stored.tags
        currentId = id
        PluginRuntime.noteLocalTurn(id.toString())
    }

    fun deleteChat(id: Long) {
        chatStore.delete(id)
        if (id == currentId) {
            stopStreaming()
            messages.clear()
            currentId = null
        }
        sessions = chatStore.list()
    }

    /** 批量导出结果提示（null = 无）。 */
    var exportResult by mutableStateOf<String?>(null)
        private set

    fun clearExportResult() { exportResult = null }

    /** 把选中的会话导出为一个 Markdown 文件（目标由系统「另存为」对话框给出）。 */
    fun exportSessions(ids: Collection<Long>, uri: Uri) {
        val metas = sessions.filter { it.id in ids }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                val sb = StringBuilder()
                metas.sortedByDescending { it.updatedAt }.forEach { meta ->
                    val msgs = chatStore.load(meta.id)?.messages ?: return@forEach
                    sb.append("# ").append(meta.title).append('\n')
                    sb.append('（').append(fmt.format(java.util.Date(meta.updatedAt))).append("）\n\n")
                    msgs.forEach { m ->
                        sb.append(if (m.role == "user") "**用户：**" else "**助手：**").append("\n\n")
                        sb.append(m.content.trim()).append("\n\n")
                    }
                    sb.append("---\n\n")
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                } ?: error("无法打开导出目标")
            }.isSuccess
            exportResult = if (ok) "已导出 ${metas.size} 个会话。" else "导出失败，请重试。"
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        if (ServerBus.state.value != ServerState.RUNNING) return
        input = ""
        val id = currentId ?: System.currentTimeMillis().also {
            currentId = it
            PluginRuntime.noteLocalTurn(it.toString())
        }
        messages += ChatMessage(nextId++, "user", text, ts = System.currentTimeMillis())
        val history = buildHistory()
        val holderId = nextId++
        messages += ChatMessage(holderId, "assistant", streaming = true, ts = System.currentTimeMillis())
        busy = true
        userStopped = false
        val port = ServerBus.port.value
        val model = ServerBus.activeAlias.value.ifBlank { "local" }
        val apiKey = settings.apiKey
        job = viewModelScope.launch(Dispatchers.IO) { runStream(holderId, port, model, apiKey, id, history) }
    }

    /** 停止生成：断开连接让阻塞的读线程退出，已生成的部分保留。 */
    fun stopStreaming() {
        if (!busy) return
        userStopped = true
        runCatching { conn?.disconnect() }
        job?.cancel()
    }

    private fun snapshotOf(m: ChatMessage) =
        AltReply(m.content, m.reasoning, m.genTokens, m.genMs, m.noted, m.error)

    /**
     * 重试（重新生成）最后一条助手回复：把当前版本收进候选列表，重新发同样的上下文再生成一版。
     * 相同前缀 → llama-server 的 KV 前缀缓存直接命中，重试的预填充成本≈0。
     * 只允许对「最后一条」助手消息重试：中途消息的重试会让其后的对话失去所依附的上下文。
     */
    fun retryLast() {
        if (busy) return
        if (ServerBus.state.value != ServerState.RUNNING) return
        val last = messages.lastOrNull() ?: return
        if (last.role != "assistant" || last.streaming) return
        val sessionId = currentId ?: return
        // 首次重试：先把现有回复收进候选列表（之后展示字段清空、开始流式新候选）
        val baseAlts = last.alts.ifEmpty { listOf(snapshotOf(last)) }
        update(last.id) {
            it.copy(
                alts = baseAlts, content = "", reasoning = "", noted = null, error = null,
                genTokens = 0, genMs = 0, streaming = true, ts = System.currentTimeMillis(),
            )
        }
        val history = buildHistory(excludeId = last.id)
        busy = true
        userStopped = false
        val port = ServerBus.port.value
        val model = ServerBus.activeAlias.value.ifBlank { "local" }
        val apiKey = settings.apiKey
        job = viewModelScope.launch(Dispatchers.IO) { runStream(last.id, port, model, apiKey, sessionId, history) }
    }

    /** 在候选回复之间切换（‹ ›）：切换的那个候选就是后续对话采用的上下文。 */
    fun switchAlt(msgId: Long, delta: Int) {
        if (busy) return
        update(msgId) { m ->
            if (m.alts.isEmpty()) m
            else {
                val i = (m.altIdx + delta).coerceIn(0, m.alts.lastIndex)
                if (i == m.altIdx) m
                else {
                    val v = m.alts[i]
                    m.copy(
                        altIdx = i, content = v.content, reasoning = v.reasoning,
                        genTokens = v.genTokens, genMs = v.genMs, noted = v.noted, error = v.error,
                    )
                }
            }
        }
        persist()
    }

    /**
     * 应用模型配置：持久化后重启服务生效。
     * 重启期间 [ServerBus.restarting] 为 true——UI 停在聊天页、服务器灯闪橙色。
     */
    fun applyModelConfig() {
        settings.chatSystemPrompt = sysPrompt
        settings.chatTemperature = temperature
        settings.chatTopP = topP
        val cfg = ServerBus.lastConfig ?: return
        if (ServerBus.state.value != ServerState.RUNNING || ServerBus.restarting.value) return
        val app = getApplication<Application>()
        ServerBus.setRestarting(true)
        stopStreaming()
        // 运行中直接再 start：服务收到新配置后自己原地重启子进程（不走 stop→start，避免竞态闪退）
        LlamaServerService.start(app, cfg.copy(temperature = temperature, topP = topP))
        viewModelScope.launch {
            try {
                withTimeoutOrNull(30_000) { ServerBus.state.first { it != ServerState.RUNNING } }
                withTimeoutOrNull(300_000) {
                    ServerBus.state.first { it == ServerState.RUNNING || it == ServerState.ERROR }
                }
                delay(300)   // 让 AppRoot 先在 restarting=true 下看到 RUNNING，避免误开新会话
            } finally {
                ServerBus.setRestarting(false)
            }
        }
    }

    /** 把当前会话落盘（既无消息也无标签则不存）。可从任意线程调。 */
    private fun persist() {
        val id = currentId ?: return
        val stored = messages
            .filter { it.content.isNotBlank() || !it.noted.isNullOrBlank() || it.alts.isNotEmpty() || it.cardPlugin != null }
            .map { m ->
                StoredMsg(
                    m.role, m.content, m.reasoning, m.genTokens, m.genMs, m.ts, m.noted ?: "",
                    alts = m.alts.map {
                        com.ggufserver.app.data.StoredAlt(
                            it.content, it.reasoning, it.genTokens, it.genMs, it.noted ?: "", it.error ?: "",
                        )
                    },
                    altIdx = m.altIdx,
                    cardPlugin = m.cardPlugin ?: "",
                    cardFile = m.cardFile ?: "",
                    cardData = m.cardData ?: "",
                )
            }
        if (stored.isEmpty() && chatTags.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }
            ?.content?.replace('\n', ' ')?.take(24) ?: "对话"
        chatStore.save(id, title, stored, chatTags)
        sessions = chatStore.list()
    }

    /**
     * 组装发给网关的消息数组：系统提示词（用户自填的整体覆盖内置的，留空则用内置安全提示词）
     * + 近期消息原样（不裁剪、不加时间前缀、不注入回忆——这些全部是网关的活，见 Gateway.assembleWindow /
     * applyContext）。每条消息带 gguf_ts（epoch ms），供网关做窗口裁剪与时间标注；网关消费后会
     * 把这个扩展字段剥掉再转发给 llama-server。
     */
    private fun buildHistory(excludeId: Long? = null): JSONArray {
        val arr = JSONArray()
        val sys = sysPrompt.trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
        arr.put(JSONObject().put("role", "system").put("content", sys))

        val sendable = messages.filter {
            it.id != excludeId && it.error == null && (it.content.isNotBlank() || !it.noted.isNullOrBlank())
        }
        // 只有插件确认条、没有正文的回合（如便签插件的 takeOver 回合），给模型一句等价文本以保持上下文完整
        fun textOf(m: ChatMessage): String = when {
            m.content.isNotBlank() -> m.content
            m.noted.isNullOrBlank() -> m.content
            m.noted.startsWith("已") || m.noted.startsWith("便签") -> "（${m.noted}）"
            else -> "（已把「${m.noted}」记入便签）"
        }
        sendable.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", textOf(m)).put("gguf_ts", m.ts))
        }
        return arr
    }

    private fun update(id: Long, transform: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfLast { it.id == id }
        if (i >= 0) messages[i] = transform(messages[i])
    }

    /** 纯流式客户端：构造请求、解析 SSE、按收尾 chunk 的 gguf.notices 渲染确认条。不含任何意图路由。 */
    private fun runStream(msgId: Long, port: Int, model: String, apiKey: String, sessionId: Long, history: JSONArray) {
        val acc = StringBuilder()
        val think = StringBuilder()
        var tokCount = 0        // 每个 SSE 增量 ≈ 1 token，够标注速度用
        var tFirst = 0L
        try {
            val body = JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("temperature", temperature.toDouble())
                .put("top_p", topP.toDouble())
                .put("messages", history)
            val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 300_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            // 本机来源标识：网关据此把这一轮判定为 source=local，并驱动窗口裁剪的按会话保持
            c.setRequestProperty("X-GGUF-Session", sessionId.toString())
            if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                update(msgId) { it.copy(error = "请求失败 HTTP $code${if (err.isNotBlank()) "：${err.take(300)}" else ""}") }
                return
            }

            c.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    if (obj.has("error")) {
                        val msg = obj.optJSONObject("error")?.optString("message") ?: payload.take(200)
                        update(msgId) { it.copy(error = "服务端错误：$msg") }
                        break
                    }
                    val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    // 注意：org.json 对 JSON null 的 optString 会返回 "null" 字符串，必须先 isNull
                    if (!delta.isNull("content")) {
                        val piece = delta.optString("content", "")
                        if (piece.isNotEmpty()) {
                            acc.append(piece)
                            val now = System.currentTimeMillis()
                            if (tFirst == 0L) tFirst = now
                            tokCount++
                            update(msgId) { it.copy(content = acc.toString(), genTokens = tokCount, genMs = now - tFirst) }
                        }
                    }
                    if (!delta.isNull("reasoning_content")) {
                        val piece = delta.optString("reasoning_content", "")
                        if (piece.isNotEmpty()) {
                            think.append(piece)
                            val now = System.currentTimeMillis()
                            if (tFirst == 0L) tFirst = now
                            tokCount++
                            update(msgId) { it.copy(reasoning = think.toString(), genTokens = tokCount, genMs = now - tFirst) }
                        }
                    }
                    // 最后一个 chunk 带服务端计时，用它把速度校准成精确值
                    obj.optJSONObject("timings")?.let { t ->
                        val n = t.optInt("predicted_n", 0)
                        val ms = t.optDouble("predicted_ms", 0.0)
                        if (n > 0 && ms > 0) update(msgId) { it.copy(genTokens = n, genMs = ms.toLong()) }
                    }
                    // 插件确认条（§6.8）：网关把 notice 附在收尾 chunk 顶层 gguf.notices，原样透传，
                    // 这里只负责渲染成现有的确认条 UI（msg.noted），不做任何解释/路由。
                    obj.optJSONObject("gguf")?.optJSONArray("notices")?.let { notices ->
                        val text = (0 until notices.length())
                            .mapNotNull { i -> notices.optJSONObject(i)?.optString("text")?.trim()?.ifEmpty { null } }
                            .joinToString("\n")
                        if (text.isNotEmpty()) update(msgId) { it.copy(noted = text) }
                    }
                    // 插件消息卡片（§6.12 工具路径）：工具是本轮回复调用的，卡片就属于本条回复——
                    // 附到同一条助手消息上（正文与卡片同条混排；模型只输出了工具标签、正文为空时，
                    // 这条消息就只有卡片，绝不能留一个空白行再另起一条）。
                    obj.optJSONObject("gguf")?.optJSONArray("cards")?.let { cards ->
                        for (i in 0 until cards.length()) {
                            val c = cards.optJSONObject(i) ?: continue
                            attachCard(
                                msgId, c.optString("plugin"), c.optString("file"),
                                (c.optJSONObject("data") ?: JSONObject()).toString(),
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!userStopped) {
                update(msgId) { m ->
                    m.copy(error = m.error ?: "连接本机服务失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        } finally {
            conn = null
            update(msgId) { it.copy(streaming = false) }
            // 重试回合：把刚生成的这版收进候选列表并采用（alts 非空即重试，见 retryLast）
            update(msgId) { m ->
                if (m.alts.isEmpty()) m
                else m.copy(alts = m.alts + snapshotOf(m), altIdx = m.alts.size)
            }
            busy = false
            if (!userStopped) {
                // 回复里有完整 HTML 代码块时，触发门户浏览器自动预览
                extractHtml(acc.toString())?.let { htmlPreview = it }
            }
            persist()
        }
    }
}
