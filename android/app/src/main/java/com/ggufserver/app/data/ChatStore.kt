package com.ggufserver.app.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ChatSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val tags: List<String> = emptyList(),
)

/** 助手回复的候选版本（重试产生），与 ChatViewModel.AltReply 一一对应。 */
data class StoredAlt(
    val content: String = "",
    val reasoning: String = "",
    val genTokens: Int = 0,
    val genMs: Long = 0,
    val noted: String = "",
    val error: String = "",
)

data class StoredMsg(
    val role: String,
    val content: String,
    val reasoning: String,
    val genTokens: Int = 0,
    val genMs: Long = 0,
    val ts: Long = 0,
    val noted: String = "",
    val alts: List<StoredAlt> = emptyList(),  // 空 = 从未重试（旧会话文件天然兼容）
    val altIdx: Int = 0,
    // 插件消息卡片（§6.12）：cardPlugin 非空 = 由插件 WebView 渲染；空串 = 普通消息（旧文件天然兼容）
    val cardPlugin: String = "",
    val cardFile: String = "",
    val cardData: String = "",
)

/** 历史会话：每个会话一个 JSON 文件，存在应用私有目录 chats/ 下。 */
class ChatStore(context: Context) {

    private val dir = File(context.filesDir, "chats").apply { mkdirs() }

    fun list(): List<ChatSummary> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    ChatSummary(
                        o.getLong("id"), o.optString("title", "对话"), o.optLong("updatedAt"),
                        tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    /** 便签历史快照（仅供一次性迁移读取旧会话文件用，见 App.kt 的迁移逻辑）。 */
    data class NoteRevRow(val ts: Long, val note: String, val label: String)

    /** 便签已整体迁到插件私有存储（见《工具插件规范》§11），会话 JSON 只保留消息与标签。 */
    fun save(
        id: Long,
        title: String,
        messages: List<StoredMsg>,
        tags: List<String> = emptyList(),
    ) {
        if (messages.isEmpty() && tags.isEmpty()) return
        val arr = JSONArray()
        messages.forEach { m ->
            val mo = JSONObject()
                .put("role", m.role)
                .put("content", m.content)
                .put("reasoning", m.reasoning)
                .put("genTokens", m.genTokens)
                .put("genMs", m.genMs)
                .put("ts", m.ts)
                .put("noted", m.noted)
            if (m.cardPlugin.isNotEmpty()) {
                mo.put("cardPlugin", m.cardPlugin).put("cardFile", m.cardFile).put("cardData", m.cardData)
            }
            if (m.alts.isNotEmpty()) {
                val alts = JSONArray()
                m.alts.forEach { a ->
                    alts.put(
                        JSONObject()
                            .put("content", a.content)
                            .put("reasoning", a.reasoning)
                            .put("genTokens", a.genTokens)
                            .put("genMs", a.genMs)
                            .put("noted", a.noted)
                            .put("error", a.error)
                    )
                }
                mo.put("alts", alts).put("altIdx", m.altIdx)
            }
            arr.put(mo)
        }
        val o = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("updatedAt", System.currentTimeMillis())
            .put("tags", JSONArray(tags))
            .put("messages", arr)
        File(dir, "$id.json").writeText(o.toString())
    }

    /** 完整会话：消息 + 标签。note/noteHistory 仅在会话文件里还留有旧字段时才非空——
     *  只用于半迁移状态下的向后兼容读取（一次性迁移完成后这两个字段永远是空的）。 */
    data class StoredChat(
        val messages: List<StoredMsg>,
        val tags: List<String>,
        val note: String = "",
        val noteHistory: List<NoteRevRow> = emptyList(),
    )

    fun load(id: Long): StoredChat? = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText())
        val arr = o.getJSONArray("messages")
        StoredChat(
            messages = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val alts = m.optJSONArray("alts")?.let { a ->
                    (0 until a.length()).map { j ->
                        val v = a.getJSONObject(j)
                        StoredAlt(
                            v.optString("content"), v.optString("reasoning"),
                            v.optInt("genTokens"), v.optLong("genMs"),
                            v.optString("noted"), v.optString("error"),
                        )
                    }
                } ?: emptyList()
                StoredMsg(
                    m.getString("role"), m.optString("content"), m.optString("reasoning"),
                    m.optInt("genTokens"), m.optLong("genMs"), m.optLong("ts"),
                    m.optString("noted"),
                    alts = alts,
                    altIdx = m.optInt("altIdx", 0).coerceIn(0, (alts.size - 1).coerceAtLeast(0)),
                    cardPlugin = m.optString("cardPlugin"),
                    cardFile = m.optString("cardFile"),
                    cardData = m.optString("cardData"),
                )
            },
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            note = o.optString("note"),
            noteHistory = o.optJSONArray("noteHistory")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    NoteRevRow(r.optLong("ts"), r.optString("note"), r.optString("label"))
                }
            } ?: emptyList(),
        )
    }.getOrNull()

    fun delete(id: Long) {
        File(dir, "$id.json").delete()
    }
}
