package com.ggufserver.app.server

import android.content.Context
import com.ggufserver.app.ServerBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 第二个 llama-server 实例——「记忆模型」，供重型记忆插件（com.gguf.heavymem）在后台做记忆链接/检索。
 * 与前台模型和网关**完全解耦**：独立进程、固定内部端口 [MEM_PORT]、只绑 127.0.0.1、小上下文省 RAM、关思考。
 *
 * 由插件经桥 `gguf.memllm.*` 驱动：[ensure] 确保加载了指定模型（换模型即重启）、[chat] 直连它、[stop] 卸载省内存。
 * 双模型意味着双份内存占用，手机上是硬约束——故重型记忆插件默认关闭、启用时红字提示。
 *
 * 线程模型：ensure/chat 都在插件桥的后台线程上同步调用（阻塞等就绪/等响应）；本对象内部对进程生命周期加锁。
 */
object MemoryModelServer {
    const val MEM_PORT = 8100
    private const val CTX = 2048
    private const val THREADS = 4
    private const val TAG = "记忆模型"

    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var currentModel: String? = null
    @Volatile private var ready = false

    fun status(): JSONObject = JSONObject()
        .put("running", process != null)
        .put("ready", ready)
        .put("model", currentModel ?: JSONObject.NULL)
        .put("port", MEM_PORT)

    /**
     * 确保记忆模型已加载并就绪。已是同一模型且就绪 → 直接返回；未运行或换了模型 → （重）启动并轮询 /health。
     * 返回 { ready, model }。抛异常 = 启动失败（文件不存在 / 二进制缺失 / 端口占用等），插件应捕获降级。
     */
    fun ensure(context: Context, modelPath: String): JSONObject {
        synchronized(lock) {
            val f = File(modelPath)
            if (!f.exists() || !f.isFile) throw RuntimeException("记忆模型文件不存在：$modelPath")
            if (process != null && ready && currentModel == modelPath) {
                return JSONObject().put("ready", true).put("model", modelPath)
            }
            stopLocked()

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val bin = File(nativeDir, LlamaServerService.SERVER_BIN)
            if (!bin.exists()) throw RuntimeException("找不到 ${LlamaServerService.SERVER_BIN}")

            val cmd = listOf(
                bin.absolutePath,
                "-m", modelPath,
                "--host", "127.0.0.1",
                "--port", MEM_PORT.toString(),
                "-c", CTX.toString(),
                "-t", THREADS.toString(),
                "--reasoning", "off",   // 记忆检索/链接要直接输出，绝不能被思考吃掉 token
                "--jinja",
                "--alias", "memory"
            )
            ServerBus.appendLog("[$TAG] 启动：${File(modelPath).name} @127.0.0.1:$MEM_PORT")
            val pb = ProcessBuilder(cmd).apply {
                redirectErrorStream(true)
                directory(File(nativeDir))
                environment()["LD_LIBRARY_PATH"] = nativeDir
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            val proc = pb.start()
            process = proc
            currentModel = modelPath
            ready = false

            // 排空 stdout，否则子进程管道满了会卡死；顺带把关键行写进日志。
            thread(name = "memllm-io", isDaemon = true) {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("listening", ignoreCase = true) || line.contains("error", ignoreCase = true)) {
                            ServerBus.appendLog("[$TAG] $line")
                        }
                    }
                }
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                synchronized(lock) {
                    if (process === proc) { process = null; ready = false; currentModel = null }
                }
                ServerBus.appendLog("[$TAG] 退出 code=$code")
            }

            // 轮询就绪（最长 ~60s：小模型加载通常几秒，SD 卡上慢些）。
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (process == null) throw RuntimeException("记忆模型进程启动后立即退出（模型不兼容？看日志）")
                if (health()) { ready = true; ServerBus.appendLog("[$TAG] 就绪"); return JSONObject().put("ready", true).put("model", modelPath) }
                Thread.sleep(500)
            }
            stopLocked()
            throw RuntimeException("记忆模型 60s 内未就绪")
        }
    }

    /** 非流式对话，直连记忆模型端口。opts: { messages, temperature?, top_p?, max_tokens?, json_schema? }。 */
    fun chat(opts: JSONObject): JSONObject {
        if (!ready) throw RuntimeException("记忆模型未就绪，请先 memllm.ensure(modelPath)")
        val body = JSONObject()
            .put("model", "memory")
            .put("messages", opts.optJSONArray("messages") ?: JSONArray())
            .put("stream", false)
        opts.opt("temperature")?.let { body.put("temperature", it) }
        opts.opt("top_p")?.let { body.put("top_p", it) }
        opts.opt("max_tokens")?.let { body.put("max_tokens", it) }
        opts.opt("json_schema")?.let { body.put("json_schema", it) }
        // 判定档缺省：受限解码 + 未指定温度 → 温度 0（封闭式判定贪心最准；调用方显式给温度则尊重）
        if (body.has("json_schema") && !body.has("temperature")) body.put("temperature", 0)

        val conn = URL("http://127.0.0.1:$MEM_PORT/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("记忆模型调用失败 HTTP $code：${err.take(200)}")
        }
        val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val content = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        return JSONObject().put("content", content).put("timings", resp.optJSONObject("timings") ?: JSONObject())
    }

    fun stop() { synchronized(lock) { stopLocked() } }

    private fun stopLocked() {
        val p = process ?: return
        process = null; ready = false; currentModel = null
        runCatching { p.destroy() }
        ServerBus.appendLog("[$TAG] 已卸载")
    }

    private fun health(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$MEM_PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500
        val ok = c.responseCode == 200
        c.disconnect(); ok
    }.getOrDefault(false)
}
