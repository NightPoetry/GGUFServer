package com.ggufserver.app.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ggufserver.app.ServerBus
import com.ggufserver.app.data.ChatStore
import com.ggufserver.app.data.ModelStore
import com.ggufserver.app.data.SettingsStore
import com.ggufserver.app.server.MemoryModelServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * `@JavascriptInterface` 宿主对象，以 `__ggufHost` 注入每个插件 WebView（规范 §8）。
 * 一个实例只服务一个 WebView（构造时绑好 pluginId/kind/webView），JS 侧的 gguf-sdk.js
 * 把这里暴露的原始回调协议包装成 `window.gguf` 的 Promise API。
 *
 * 权限在两处裁剪：SDK 初始化时按 granted permissions 不暴露对应命名空间（宿主注入时给出），
 * 这里对每个 api 名再做一次服务器端校验（[requirePermission]）——防止被改过的 JS 绕开 SDK
 * 直接调 `__ggufHost.request(...)`。
 */
class PluginBridge(
    private val pluginId: String,
    private val kind: WebViewKind,
    private val context: Context,
    private val webView: WebView,
    /** 消息卡片（kind = CARD）时的卡片键（= 消息 id），高度回报按它归位。 */
    private val cardKey: String? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 钩子分发的回传通道：respond()/status() 对应 PluginRuntime.dispatch() 发起的调用 ----

    @JavascriptInterface
    fun reportHeight(px: Int) {
        // 设置卡片与消息卡片消费：撑高到内容高度、避免被固定高度裁掉；面板 / 后台 WebView 的回报忽略
        if (kind == WebViewKind.SETTINGS && px > 0) PluginRuntime.updateSettingsHeight(pluginId, px)
        if (kind == WebViewKind.CARD && cardKey != null && px > 0) PluginRuntime.updateCardHeight(pluginId, cardKey, px)
    }

    @JavascriptInterface
    fun respond(callId: Int, resultJson: String) {
        PluginRuntime.respond(callId, resultJson)
    }

    @JavascriptInterface
    fun status(callId: Int, text: String) {
        PluginRuntime.status(callId, text)
    }

    // ---- 通用 API 通道：gguf.* 的所有调用最终都走这里，异步在后台线程处理，经主线程回传结果 ----

    @JavascriptInterface
    fun request(reqId: String, api: String, argsJson: String) {
        EXECUTOR.execute {
            try {
                val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                val result = handle(api, args)
                resolve(reqId, result)
            } catch (e: Exception) {
                reject(reqId, e.message ?: e.javaClass.simpleName ?: "调用失败")
            }
        }
    }

    private fun resolve(reqId: String, result: Any?) {
        val json = when (result) {
            null -> "null"
            is JSONObject -> result.toString()
            is JSONArray -> result.toString()
            else -> result.toString()
        }
        val script = "__ggufResolve(" + JSONObject.quote(reqId) + "," + JSONObject.quote(json) + ")"
        mainHandler.post { runCatching { webView.evaluateJavascript(script, null) } }
    }

    private fun reject(reqId: String, message: String) {
        val script = "__ggufReject(" + JSONObject.quote(reqId) + "," + JSONObject.quote(message) + ")"
        mainHandler.post { runCatching { webView.evaluateJavascript(script, null) } }
    }

    private fun pushStreamDelta(streamId: String, piece: String) {
        val script = "__ggufStreamDelta(" + JSONObject.quote(streamId) + "," + JSONObject.quote(piece) + ")"
        mainHandler.post { runCatching { webView.evaluateJavascript(script, null) } }
    }

    private fun manifest(): PluginManifest? = PluginStore.manifestOf(pluginId)

    private fun requirePermission(perm: String) {
        val m = manifest() ?: throw SecurityException("插件不存在或已卸载")
        if (perm !in m.permissions) throw SecurityException("缺少权限 $perm，未在 manifest.permissions 声明")
    }

    /** 按 api 名分发；抛异常 = reject，否则其返回值序列化后 resolve。 */
    private fun handle(api: String, args: JSONObject): Any? = when (api) {
        "storage.get" -> JSONObject().put("value", PluginStore.kvGet(pluginId, args.getString("key")) ?: JSONObject.NULL)
        "storage.set" -> {
            val err = PluginStore.kvSet(pluginId, args.getString("key"), args.opt("value"))
            if (err != null) throw RuntimeException(err)
            JSONObject()
        }
        "storage.remove" -> {
            PluginStore.kvRemove(pluginId, args.getString("key"))
            JSONObject()
        }
        "storage.keys" -> JSONObject().put("keys", JSONArray(PluginStore.kvKeys(pluginId)))
        "llm.chat" -> { requirePermission("llm"); llmChat(args) }
        "llm.chatStream" -> { requirePermission("llm"); llmChatStream(args) }
        "llm.classify" -> { requirePermission("llm"); llmClassify(args) }
        // 记忆模型（第二个 llama-server 实例）：重型记忆插件的双模型后台检索。gate 在 llm 权限。
        "memllm.ensure" -> { requirePermission("llm"); MemoryModelServer.ensure(context, args.getString("modelPath")) }
        "memllm.chat" -> { requirePermission("llm"); MemoryModelServer.chat(args) }
        "memllm.status" -> MemoryModelServer.status()
        "memllm.unload" -> { MemoryModelServer.stop(); JSONObject() }
        "host.models" -> hostModels()
        "chat.current" -> { requirePermission("chat:read"); chatCurrent() }
        "chat.messages" -> { requirePermission("chat:read"); chatMessages(args) }
        "sessions.list" -> { requirePermission("sessions:read"); sessionsList() }
        "sessions.load" -> { requirePermission("sessions:read"); sessionsLoad(args) }
        // 消息卡片（§6.12）：插件主动往当前会话插一条自绘卡片消息（工具路径走网关 gguf.cards，不经这里）
        "card.post" -> {
            requirePermission("chat:card")
            PluginStore.postCard(
                pluginId,
                args.optString("file").ifBlank { "card.html" },
                (args.optJSONObject("data") ?: JSONObject()).toString(),
                PluginRuntime.lastLocalSessionId,
            )
            JSONObject()
        }
        // 系统语音朗读（gguf.tts.*）：WebView 无 speechSynthesis，发声必须经宿主。只出声不读数据，
        // 与 ui.* 同级、不需声明权限。speak 后点顶掉先点（QUEUE_FLUSH），stop 立即静音。
        // 发声是可被接管的能力点（§6.13）：有提供者时改道进它的 tts 钩子（见 ttsRoute）。
        "tts.speak" -> ttsRoute("speak", args)
        "tts.stop" -> ttsRoute("stop", args)
        "tts.status" -> ttsRoute("status", args)
        // 合成到 WAV 不出声（变声/RVC 管道取料口）：不走提供者路由——它就是"原始引擎"的一部分，
        // 提供者拿它取料再自行处理。返回本插件虚拟域名下的音频 URL，WebView fetch 后 Web Audio 处理。
        "tts.synthesize" -> {
            val f = HostTts.synthesize(context, pluginId, args.optString("text"))
                ?: throw RuntimeException("语音合成失败（引擎不可用或超时）")
            JSONObject().put("url", "https://plugin.gguf/$pluginId/__tts__/${f.name}")
        }
        // 端侧神经 TTS（sherpa-onnx VITS，完全离线）：与 synthesize 同一取用方式，
        // 多说话人模型用 sid 选音色。模型放 filesDir/tts-models/<名>/，localStatus 查询可用性。
        "tts.synthesizeLocal" -> {
            val f = NeuralTts.synthesize(
                context, pluginId, args.optString("text"),
                sid = args.optInt("sid", 0),
                speed = args.optDouble("speed", 1.0).toFloat(),
                modelName = args.optString("model").ifBlank { null },
            ) ?: throw RuntimeException("本地神经合成失败（二进制/模型缺失或超时）")
            JSONObject().put("url", "https://plugin.gguf/$pluginId/__tts__/${f.name}")
        }
        "tts.localStatus" -> NeuralTts.status(context)
        "ui.close" -> { PluginStore.requestClose(pluginId); JSONObject() }
        "ui.setBadge" -> { PluginStore.setBadge(pluginId, args.optInt("n", 0)); JSONObject() }
        "ui.toast" -> { PluginStore.toast(args.optString("text")); JSONObject() }
        "events.broadcast" -> {
            PluginRuntime.broadcast(pluginId, kind, args.optString("name"), args.optJSONObject("payload"))
            JSONObject()
        }
        // 宿主侧取音（net:lan / net:internet）：插件 WebView 直接 fetch 局域网服务器会撞 CORS
        // （页面源是 https://plugin.gguf，自建推理服务器普遍不回 CORS 头）。宿主用原生 HTTP
        // 取回音频、落到插件的网络缓存目录，返回本插件虚拟域名 __net__/ 下的 URL——同源、无 CORS。
        "net.fetchAudio" -> netFetchAudio(args)
        // 把插件生成的二进制存进系统「下载」目录（§6.15）。给「导出 .mid / 导出音频」这类动作用——
        // 插件在 WebView 里能生成字节，但没有任何通道把它交给用户，只能经宿主落盘。
        "files.saveDownload" -> { requirePermission("files:save"); saveDownload(args) }
        else -> throw RuntimeException("未知桥接口：$api")
    }

    /**
     * 把插件给的 base64 字节写进系统「下载」目录，返回可见的文件名。
     *
     * 为什么要它：插件跑在 WebView 里，能算出 .mid / .wav 的字节，却**没有任何通道交给用户**
     * （data: URL 下载在 WebView 里不可靠、storage 只存 JSON）。API 29+ 走 MediaStore 的
     * Downloads 集合，不需要任何运行时权限；更老的系统退回公共下载目录。
     * 同名文件不覆盖：自动加 -1 / -2 后缀（用户导出多首曲子是常态）。
     */
    private fun saveDownload(args: JSONObject): JSONObject {
        val rawName = args.optString("name").ifBlank { "gguf-export.bin" }
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(80)
        val mime = args.optString("mime").ifBlank { "application/octet-stream" }
        val bytes = android.util.Base64.decode(args.optString("base64"), android.util.Base64.DEFAULT)
        if (bytes.isEmpty()) throw RuntimeException("saveDownload: 内容为空")
        if (bytes.size > 8 * 1024 * 1024) throw RuntimeException("saveDownload: 超过 8MB 上限")

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("saveDownload: 系统没给下载目录的写入位置")
            resolver.openOutputStream(uri).use { it?.write(bytes) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            PluginStore.toast("已保存到「下载」：$name")
            return JSONObject().put("ok", true).put("name", name).put("uri", uri.toString())
        }
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        var f = java.io.File(dir, name)
        var i = 1
        while (f.exists()) { f = java.io.File(dir, name.replace(Regex("(\\.[^.]*)$"), "-$i$1")); i++ }
        f.writeBytes(bytes)
        PluginStore.toast("已保存到「下载」：${f.name}")
        return JSONObject().put("ok", true).put("name", f.name).put("uri", f.absolutePath)
    }

    /**
     * 发声能力点的路由（§6.13 补丁式依赖）：
     * - 有生效提供者（启用、未熔断、声明 provides:["tts"]）且**调用方不是提供者本人** →
     *   调用改道进提供者 service.js 的 `gguf.hooks.tts` 钩子（入参 {action, from, ...原参数}）。
     * - **调用方就是提供者** → 直通宿主默认实现（系统 TextToSpeech）。这就是补丁的「调穿」：
     *   提供者接住别人的发声、加工后再调 gguf.tts 时落到原始实现（可带 pitch/rate 变调），
     *   天然无回环。
     * - 提供者超时/异常 → fail-open 落回默认实现（超时同时计入熔断，连挂 3 次自动旁路，
     *   发声整体回到出厂行为）。
     */
    private fun ttsRoute(action: String, args: JSONObject): JSONObject {
        val provider = PluginStore.activeProvider("tts")
        if (provider != null && provider != pluginId) {
            val payload = JSONObject().put("action", action).put("from", pluginId)
            args.keys().forEach { k -> payload.put(k, args.opt(k)) }
            val r = runBlocking { PluginRuntime.dispatch(provider, "tts", payload, TTS_PROVIDER_BUDGET_MS) }
            if (r != null) return r
        }
        return when (action) {
            "speak" -> {
                HostTts.speak(
                    context, args.optString("text"),
                    args.optDouble("pitch", 1.0).toFloat(),
                    args.optDouble("rate", 1.0).toFloat(),
                )
                JSONObject()
            }
            "stop" -> { HostTts.stop(); JSONObject() }
            else -> HostTts.status()
        }
    }

    /**
     * gguf.net.fetchAudio：宿主代插件取音频（POST JSON 或 GET）。目标主机按权限校验
     * （net:lan = 环回/私网段，net:internet = 任意），响应字节写入 cacheDir/plugin-net/<插件id>/
     * （每次清旧只留最新），返回 { status, url, bytes }——url 是本插件虚拟域名 __net__/ 下的
     * 同源地址，WebView fetch 它不受 CORS 约束。上限 20MB，超时默认 60s（推理服务器可能较慢）。
     */
    private fun netFetchAudio(args: JSONObject): JSONObject {
        val urlStr = args.optString("url").ifBlank { throw RuntimeException("缺少 url") }
        val u = URL(urlStr)
        if (u.protocol != "http" && u.protocol != "https") throw RuntimeException("仅支持 http/https")
        val m = manifest() ?: throw SecurityException("插件不存在或已卸载")
        val hostOk = when {
            "net:internet" in m.permissions -> true
            "net:lan" in m.permissions -> isLanHost(u.host)
            else -> false
        }
        if (!hostOk) throw SecurityException("目标主机不在授权范围（需 net:lan / net:internet 权限）")

        val conn = u.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = args.optInt("timeoutMs", 60_000).coerceIn(1_000, 300_000)
        val bodyJson = args.optJSONObject("json")
        if (bodyJson != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(bodyJson.toString().toByteArray(Charsets.UTF_8)) }
        } else {
            conn.requestMethod = args.optString("method", "GET")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("HTTP $code：${err.take(160)}")
        }
        val dir = File(context.cacheDir, "plugin-net/$pluginId")
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        val out = File(dir, "a${System.nanoTime()}.wav")
        var total = 0L
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 20L * 1024 * 1024) throw RuntimeException("响应超过 20MB 上限")
                    o.write(buf, 0, n)
                }
            }
        }
        return JSONObject()
            .put("status", code)
            .put("bytes", total)
            .put("url", "https://plugin.gguf/$pluginId/__net__/${out.name}")
    }

    /** net:lan 的主机范围：环回 / 10.x / 172.16-31.x / 192.168.x / localhost。 */
    private fun isLanHost(host: String?): Boolean {
        host ?: return false
        if (host == "localhost" || host == "127.0.0.1") return true
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        return false
    }

    // ---- gguf.llm.*：直连内部端口（127.0.0.1:内部端口），天然不过网关的插件管线；
    //      仍带上 X-GGUF-Plugin 头做二次保险（万一将来什么路径又绕回了公开端口）。 ----

    private fun openInternal(): HttpURLConnection {
        val port = ServerBus.internalPort.value
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-GGUF-Plugin", "1")
        val apiKey = SettingsStore(context).apiKey
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        return conn
    }

    private fun baseChatBody(args: JSONObject, stream: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", ServerBus.activeAlias.value.ifBlank { "local" })
            .put("messages", args.optJSONArray("messages") ?: JSONArray())
            .put("stream", stream)
        args.opt("temperature")?.let { body.put("temperature", it) }
        args.opt("top_p")?.let { body.put("top_p", it) }
        args.opt("max_tokens")?.let { body.put("max_tokens", it) }
        args.opt("json_schema")?.let { body.put("json_schema", it) }
        // 判定档缺省：受限解码 + 未指定温度 → 温度 0（封闭式判定贪心最准；调用方显式给温度则尊重）
        if (body.has("json_schema") && !body.has("temperature")) body.put("temperature", 0)
        return body
    }

    private fun llmChat(args: JSONObject): JSONObject {
        val conn = openInternal()
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        conn.outputStream.use { it.write(baseChatBody(args, stream = false).toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("本机模型调用失败 HTTP $code：${err.take(200)}")
        }
        val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val content = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        return JSONObject().put("content", content).put("timings", resp.optJSONObject("timings") ?: JSONObject())
    }

    private fun llmChatStream(args: JSONObject): JSONObject {
        val streamId = args.optString("streamId")
        val conn = openInternal()
        conn.connectTimeout = 10_000
        conn.readTimeout = 300_000
        conn.outputStream.use { it.write(baseChatBody(args, stream = true).toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("本机模型调用失败 HTTP $code：${err.take(200)}")
        }
        val acc = StringBuilder()
        var timings: JSONObject = JSONObject()
        conn.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                if (delta != null && !delta.isNull("content")) {
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty()) {
                        acc.append(piece)
                        if (streamId.isNotEmpty()) pushStreamDelta(streamId, piece)
                    }
                }
                obj.optJSONObject("timings")?.let { timings = it }
            }
        }
        return JSONObject().put("content", acc.toString()).put("timings", timings)
    }

    /**
     * gguf.llm.classify：前缀扩展式单选判断（《工具插件规范》§6.10，参数导演的 LLM 判断路径）。
     *
     * KV 共用铁律：判断请求必须是本轮回答请求同一前缀的**严格后缀扩展**——调用方（params 钩子）
     * 把网关组装完的最终 messages 原样传入，这里只在**末尾追加**一条判断 system 消息，绝不改动、
     * 重排或删减前面的任何消息。这样判断时公共前缀（整个对话上文+本轮用户消息）全部命中缓存、
     * 只预填判断指令几十 token；判断后 llama-server 剪尾（与「重试候选」同机制），回答请求只补
     * assistant 开头即开始流式——判断对缓存零残留、还顺手替回答付了本轮用户消息的预填。
     *
     * 采样固定判定档：temperature 0；**绝不带 repeat_penalty/DRY**（实测惩罚会把枚举判定打偏：
     * 假阴 0→9，见《推理参数与外部知识架构研究》§三）。
     *
     * **先自由生成，受限解码只当保底**（2026-07-28 真机改）：原来一律下发 `json_schema` 顶层 enum，
     * 那是在 MiniCPM-1B 上验证过的。换到 Qwen3.5-2B 上一量，受限 enum **完全没有区分力**——同一批
     * 用例不论问什么都返回同一个标签；用 object schema 让它「先写理由再给结论」时，**理由写得完全
     * 正确、结论却仍然选错**（「用户请求创作欢快音乐，属于创意生成任务」→ 结论「代码」）。也就是说
     * 模型判得出来，是 GBNF 把第一个 token 钉死在某个选项上、模型没有「想」的余地。
     * 同样六条用例改成不受限、只让它回一个词：**5/6 正确、每次 1.1s**。所以先自由生成 + 解析标签，
     * 解析不到再退回受限解码（老模型上 enum 仍然稳，两头都保住）。
     */
    private fun llmClassify(args: JSONObject): JSONObject {
        val labels = args.optJSONArray("labels") ?: throw RuntimeException("classify 缺少 labels")
        if (labels.length() < 2) throw RuntimeException("classify 至少需要两个选项")
        val question = args.optString("question").ifBlank { throw RuntimeException("classify 缺少 question") }
        val messages = args.optJSONArray("messages") ?: JSONArray()

        val labelList = ArrayList<String>(labels.length())
        for (i in 0 until labels.length()) labels.optString(i).ifBlank { null }?.let { labelList += it }

        val instruction = question + "\n只回答下列之一，不要解释：" + labelList.joinToString(" / ")
        val judged = JSONArray()
        for (i in 0 until messages.length()) judged.put(messages.optJSONObject(i))
        // **判断指令怎么挂，随模板走**（2026-07-28 修）：追加一条 system 是 KV 最优的挂法（前面一字
        // 不动 = 严格后缀扩展），但 Qwen 系模板显式禁止中途 system——整个判断请求 HTTP 400，判断
        // 静默失败、调用方只看到「判不出来」。真机上乐句插件的深判每次都是这么废掉的。
        // 模板不吃中途 system 时改为并进**末条用户消息的末尾**：不新增角色、任何模板都合法，
        // 前面的消息同样一字不动（只有最后一条多了个尾巴，前缀分叉点后移到它，代价是几十 token）。
        val midSystemOk = runCatching { com.ggufserver.app.server.TemplateProbe.caps().midSystem }.getOrDefault(true)
        val lastUserIdx = (judged.length() - 1 downTo 0)
            .firstOrNull { judged.optJSONObject(it)?.optString("role") == "user" } ?: -1
        val lastUserText = judged.optJSONObject(lastUserIdx)?.opt("content") as? String
        if (!midSystemOk && lastUserIdx >= 0 && lastUserText != null) {
            // 拷一份再改：messages 是调用方（插件）传进来的那份，就地改会污染它本轮的其它用途
            judged.put(
                lastUserIdx,
                JSONObject(judged.optJSONObject(lastUserIdx).toString())
                    .put("content", lastUserText + "\n\n" + instruction),
            )
        } else {
            judged.put(JSONObject().put("role", "system").put("content", instruction))
        }

        // 第一遍：不受限，只让它回一个词（12 token 足够，实测 1.1s）
        val t0 = System.currentTimeMillis()
        var resp = postJudge(judged, null, 12)
        var raw = rawOf(resp)
        var label = pickLabel(raw, labelList)
        var constrained = false
        if (label == null) {
            // 保底：受限解码。老模型（MiniCPM-1B）上 enum 稳；这里是「自由生成没说出任何一个标签」
            // 才会走到——多花一次往返，好过整个判断作废。
            resp = postJudge(judged, labelList, args.optInt("maxTokens", 24))
            raw = rawOf(resp)
            label = pickLabel(raw, labelList)
            constrained = true
        }
        // 判断是用户实打实等着的那一秒多，且判错就是能力错位——把「判成了什么」记进运行日志，
        // 否则出问题只能靠猜（这次真机就是卡在「深判到底判成了什么」上）。
        ServerBus.appendLog(
            "[插件:$pluginId] LLM 判断 ${System.currentTimeMillis() - t0}ms" +
                (if (constrained) "（自由生成没认出标签，已退回受限解码）" else "") +
                "：得到「${label ?: "—"}」，模型原话「${raw.trim().take(24)}」",
        )
        return JSONObject()
            .put("label", label ?: JSONObject.NULL)
            .put("timings", resp.optJSONObject("timings") ?: JSONObject())
    }

    /** 发一次判断请求。[enumLabels] 非空则下发受限解码（GBNF），否则自由生成。 */
    private fun postJudge(judged: JSONArray, enumLabels: List<String>?, maxTokens: Int): JSONObject {
        val body = JSONObject()
            .put("model", ServerBus.activeAlias.value.ifBlank { "local" })
            .put("messages", judged)
            .put("stream", false)
            .put("temperature", 0)
            .put("max_tokens", maxTokens)
        if (enumLabels != null) {
            body.put("json_schema", JSONObject().put("type", "string").put("enum", JSONArray(enumLabels)))
        }
        val conn = openInternal()
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("判断调用失败 HTTP $code：${err.take(200)}")
        }
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    private fun rawOf(resp: JSONObject): String =
        resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()

    /**
     * 从模型输出里认出标签：**出现位置最靠前的那个**（并列时取更长的，避免「不是要作曲」被
     * 「要作曲」抢先）。受限解码的输出是 JSON 字符串字面量（`"\"聊天\""`），先去壳再找。
     */
    private fun pickLabel(raw: String, labelList: List<String>): String? {
        val text = runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrNull() ?: raw.trim().trim('"')
        if (text.isBlank()) return null
        return labelList
            .mapNotNull { l -> text.indexOf(l).takeIf { it >= 0 }?.let { it to l } }
            .minWithOrNull(compareBy({ it.first }, { -it.second.length }))
            ?.second
    }

    // ---- gguf.chat.* / gguf.sessions.*：经 ChatStore + “最近一次本机来源请求的 sessionId” ----

    /** gguf.host.models()：列出各存储位置下可用的 GGUF（排除 mmproj），供设置卡片选「记忆模型」等。
     *  只返回文件名/路径/大小/位置标签，不读内容——低风险，不需专门权限。 */
    private fun hostModels(): JSONArray {
        val store = ModelStore(context)
        val arr = JSONArray()
        runCatching {
            for (loc in store.locations()) {
                for (m in store.list(loc.modelsDir)) {
                    arr.put(
                        JSONObject()
                            .put("name", m.name)
                            .put("path", m.file.absolutePath)
                            .put("sizeMB", m.sizeBytes / (1024L * 1024L))
                            .put("location", loc.label)
                    )
                }
            }
        }
        return arr
    }

    private fun chatCurrent(): JSONObject? {
        val sid = PluginRuntime.lastLocalSessionId?.toLongOrNull() ?: return null
        // 会话可能还没落盘（刚 newChat、一条消息都没发）：也要给出 id，让便签面板等 UI 立即可用
        val summary = ChatStore(context).list().firstOrNull { it.id == sid }
        return JSONObject()
            .put("id", sid)
            .put("title", summary?.title ?: "对话")
            .put("tags", JSONArray(summary?.tags ?: emptyList<String>()))
    }

    private fun chatMessages(args: JSONObject): JSONArray {
        val sid = PluginRuntime.lastLocalSessionId?.toLongOrNull() ?: return JSONArray()
        val stored = ChatStore(context).load(sid) ?: return JSONArray()
        val limit = args.optInt("limit", -1)
        val list = if (limit > 0) stored.messages.takeLast(limit) else stored.messages
        val arr = JSONArray()
        list.forEach { m -> arr.put(JSONObject().put("role", m.role).put("content", m.content).put("ts", m.ts)) }
        return arr
    }

    private fun sessionsList(): JSONArray {
        val arr = JSONArray()
        ChatStore(context).list().forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("updatedAt", s.updatedAt))
        }
        return arr
    }

    private fun sessionsLoad(args: JSONObject): JSONObject? {
        val id = args.optLong("id", -1)
        if (id < 0) return null
        val stored = ChatStore(context).load(id) ?: return null
        val arr = JSONArray()
        stored.messages.forEach { m -> arr.put(JSONObject().put("role", m.role).put("content", m.content).put("ts", m.ts)) }
        return JSONObject().put("id", id).put("messages", arr).put("note", stored.note).put("tags", JSONArray(stored.tags))
    }

    companion object {
        /** 所有插件桥共用一个后台线程池处理 request()：JS 桥回调本就跑在 WebView 私有线程上，
         *  这里只是避免各插件之间互相卡（一个插件的慢网络调用不拖慢另一个插件）。 */
        private val EXECUTOR = Executors.newCachedThreadPool()

        /** 发声提供者钩子的预算：提供者只做文本加工 + 调穿，正常毫秒级；超时即落回默认实现。 */
        private const val TTS_PROVIDER_BUDGET_MS = 5_000L
    }
}
