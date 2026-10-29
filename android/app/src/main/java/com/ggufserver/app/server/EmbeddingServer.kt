package com.ggufserver.app.server

import android.content.Context
import com.ggufserver.app.ServerBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/**
 * 第二个 llama-server 实例——「向量模型」（嵌入服务），对外提供 OpenAI 兼容的 /v1/embeddings。
 * 与主服务/网关**完全解耦**：独立进程、固定端口 [EMB_PORT]、直接绑 0.0.0.0 对局域网开放
 * （嵌入是给局域网内其它设备的 RAG/检索用的，这是它存在的意义——不像记忆模型只做内部管道绑 127.0.0.1）。
 *
 * 启动参数 `--embedding`（llama-server 文档原文：restrict to only support embedding use case,
 * use only with dedicated embedding models）——所以**只配专用嵌入模型**（如 Qwen3-Embedding / EmbeddingGemma），
 * 普通对话模型配进来 llama-server 会拒绝或产出垃圾；pooling 不传，按 GGUF 元数据自动。
 *
 * 生命周期跟随主服务（LlamaServerService.startServer 拉起、cleanup 卸载）：换嵌入模型 = 重启服务生效。
 * 拉起是 fail-open 的：嵌入模型坏了只影响本实例状态（ERROR + 日志），主服务照常。
 *
 * 线程模型：ensure 在独立后台线程调用（不阻塞服务启动）；进程生命周期内部加锁。
 */
object EmbeddingServer {
    const val EMB_PORT = 8101
    private const val CTX = 2048
    private const val TAG = "向量模型"

    enum class Phase { IDLE, STARTING, RUNNING, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val model: String? = null,
        val alias: String? = null,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var currentModel: String? = null
    @Volatile private var currentAlias: String? = null

    /**
     * 纯函数组装命令行（无 Android 依赖，供 JVM 单测锁参数形态）。
     * --embedding 用 --embeddings 拼写（llama-server 两种都认，取语义更明确的复数形式）。
     */
    fun buildCommand(
        binPath: String,
        modelPath: String,
        port: Int,
        alias: String,
        apiKey: String,
        threads: Int,
        contextSize: Int,
    ): List<String> = buildList {
        add(binPath)
        add("-m"); add(modelPath)
        add("--host"); add("0.0.0.0")
        add("--port"); add(port.toString())
        add("-c"); add(contextSize.toString())
        add("-t"); add(threads.toString())
        add("--embeddings")
        add("--alias"); add(alias)
        if (apiKey.isNotBlank()) { add("--api-key"); add(apiKey) }
    }

    /** 确保嵌入模型已加载并就绪。同一模型且运行中 → 原样返回；否则（重）启动并轮询 /health。 */
    fun ensure(context: Context, modelPath: String, alias: String, apiKey: String, threads: Int) {
        synchronized(lock) {
            val f = File(modelPath)
            if (!f.exists() || !f.isFile) {
                _status.value = Status(Phase.ERROR, error = "向量模型文件不存在：$modelPath")
                throw RuntimeException("向量模型文件不存在：$modelPath")
            }
            if (process != null && currentModel == modelPath) return
            stopLocked()
            _status.value = Status(Phase.STARTING, model = File(modelPath).name, alias = alias)

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val bin = File(nativeDir, LlamaServerService.SERVER_BIN)
            if (!bin.exists()) {
                _status.value = Status(Phase.ERROR, error = "找不到 ${LlamaServerService.SERVER_BIN}")
                throw RuntimeException("找不到 ${LlamaServerService.SERVER_BIN}")
            }

            val cmd = buildCommand(
                binPath = bin.absolutePath, modelPath = modelPath, port = EMB_PORT,
                alias = alias, apiKey = apiKey, threads = threads, contextSize = CTX,
            )
            ServerBus.appendLog("[$TAG] 启动：${File(modelPath).name} @0.0.0.0:$EMB_PORT")
            val pb = ProcessBuilder(cmd).apply {
                redirectErrorStream(true)
                directory(File(nativeDir))
                environment()["LD_LIBRARY_PATH"] = nativeDir
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                // 与主服务同一套 Vulkan 安全阀（统一内存设备上防止计算图被塞进慢速 GPU）
                environment()["GGML_VK_DISABLE_BDA"] = "1"
                environment()["GGML_VK_VISIBLE_DEVICES"] = ""
            }
            val proc = pb.start()
            process = proc
            currentModel = modelPath
            currentAlias = alias

            // 排空 stdout（管道满了会卡死子进程）；启动失败原因多在前几行
            thread(name = "embedding-io", isDaemon = true) {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("listening", ignoreCase = true) ||
                            line.contains("error", ignoreCase = true) ||
                            line.contains("failed", ignoreCase = true)
                        ) ServerBus.appendLog("[$TAG] $line")
                    }
                }
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                synchronized(lock) {
                    if (process === proc) {
                        process = null
                        val model = currentModel; currentModel = null; currentAlias = null
                        _status.value = Status(
                            Phase.ERROR, model = model?.let { File(it).name },
                            error = "向量模型进程退出 code=$code（该文件是专用嵌入模型吗？看日志）"
                        )
                    }
                }
                ServerBus.appendLog("[$TAG] 进程退出 code=$code")
            }

            // 轮询就绪（最长 ~90s：嵌入模型小，通常几秒；SD 卡上慢些）
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                if (process == null) {
                    throw RuntimeException("向量模型进程启动后立即退出（不是专用嵌入模型？看日志）")
                }
                if (health()) {
                    ServerBus.appendLog("[$TAG] 就绪 · http://<手机IP>:$EMB_PORT/v1/embeddings")
                    _status.value = Status(Phase.RUNNING, model = File(modelPath).name, alias = alias)
                    return
                }
                Thread.sleep(500)
            }
            stopLocked()
            _status.value = Status(Phase.ERROR, model = File(modelPath).name, error = "向量模型 90s 内未就绪")
            throw RuntimeException("向量模型 90s 内未就绪")
        }
    }

    fun stop() { synchronized(lock) { stopLocked() } }

    private fun stopLocked() {
        val p = process ?: return
        process = null; currentModel = null; currentAlias = null
        runCatching { p.destroy() }
        _status.value = Status(Phase.IDLE)
        ServerBus.appendLog("[$TAG] 已停止")
    }

    private fun health(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$EMB_PORT/health").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500
        val ok = c.responseCode == 200
        c.disconnect(); ok
    }.getOrDefault(false)

    /** 直接打一次 /v1/embeddings 自检（给 UI「自检」用），返回向量维度；报错原样抛出。 */
    fun selfTest(input: String = "嵌入服务自检"): Int {
        val st = status.value
        if (st.phase != Phase.RUNNING) throw RuntimeException("向量模型未就绪")
        val body = JSONObject().put("model", st.alias ?: "embedding").put("input", input)
        val conn = URL("http://127.0.0.1:$EMB_PORT/v1/embeddings").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("自检失败 HTTP $code：${err.take(200)}")
        }
        val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val dim = resp.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("embedding")?.length() ?: -1
        if (dim <= 0) throw RuntimeException("自检失败：返回向量维度异常")
        return dim
    }
}
