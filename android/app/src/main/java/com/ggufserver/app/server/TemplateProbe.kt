package com.ggufserver.app.server

import com.ggufserver.app.ServerBus
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * **模型 chat 模板能力探测**：这个模型的模板吃不吃「首条 system」「中途 system」。
 *
 * 为什么需要它（v1.1.12 的教训）：Qwen 系模板显式 `raise_exception("System message must be at the
 * beginning.")`，凡是在对话中途插一条 system 的请求**整轮 HTTP 400**——这不是能 fail-open 掉的小事，
 * 是整轮报错。而 MiniCPM 的 chatml 吃得下。拼法必须随模型走，不能写死。
 *
 * 探法：拿 llama-server 的 `/apply-template` 试两个样本，看 200 还是 400。一个模型只探一次，按
 * alias 记住（换模型自然重探）。探不通就当「都支持」——维持既有行为，不因探测失败改变拼法。
 *
 * **为什么是共享 object 而不是 Gateway 的私有方法**（2026-07-28 抽出）：模板能力是「这个模型」的
 * 客观事实，不是某个调用方的事。原先只有网关探，于是 `gguf.llm.classify`（插件的 LLM 判断路径，
 * 在末尾追加一条判断 system）在 Qwen 上一路 400 —— 乐句插件的深判每次都判不出来、静默退回不接管。
 * 同一个坑在第二条路径上又踩了一遍，正是「该共享的事实没共享」。
 */
object TemplateProbe {

    data class Caps(val headSystem: Boolean, val midSystem: Boolean)

    private val cache = ConcurrentHashMap<String, Caps>()

    /** 按当前模型 alias 取能力（首次会探两次 `/apply-template`，之后走缓存）。 */
    fun caps(port: Int = ServerBus.internalPort.value): Caps {
        val alias = ServerBus.activeAlias.value.ifBlank { "?" }
        cache[alias]?.let { return it }
        fun m(role: String, text: String) = JSONObject().put("role", role).put("content", text)
        val head = applyTemplateOk(port, listOf(m("system", "A"), m("user", "hi")))
        val mid = head && applyTemplateOk(
            port, listOf(m("system", "A"), m("user", "hi"), m("system", "B"), m("user", "yo")),
        )
        val c = Caps(headSystem = head, midSystem = mid)
        cache[alias] = c
        ServerBus.appendLog(
            "[网关] 模板能力（$alias）：首条 system=" + (if (head) "支持" else "**不支持**（系统提示词将并入首条用户消息）") +
                "，中途 system=" + (if (mid) "支持" else "**不支持**（逐轮内容将并入末条用户消息，位置同样靠后、KV 前缀不受影响）"),
        )
        return c
    }

    private fun applyTemplateOk(port: Int, messages: List<JSONObject>): Boolean = runCatching {
        val arr = JSONArray()
        messages.forEach { arr.put(it) }
        val body = JSONObject().put("messages", arr).toString()
        val url = java.net.URL("http://127.0.0.1:$port/apply-template")
        (url.openConnection() as java.net.HttpURLConnection).run {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3_000
            readTimeout = 5_000
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = responseCode
            runCatching { inputStream.close() }
            runCatching { errorStream?.close() }
            code in 200..299
        }
    }.getOrDefault(true)
}
