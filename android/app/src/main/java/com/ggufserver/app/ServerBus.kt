package com.ggufserver.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class ServerState { STOPPED, STARTING, RUNNING, ERROR }

/**
 * Process-wide bridge between the foreground [com.ggufserver.app.server.LlamaServerService]
 * (which owns the llama-server subprocess) and the Compose UI. The service writes; the UI reads.
 */
object ServerBus {
    private const val MAX_LINES = 800

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    /** 正在「应用模型配置」触发的重启中（此间 UI 停留在聊天页、服务器灯闪橙色）。 */
    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    /**
     * 正在处理中的 HTTP 请求数（由内置代理统计，远程客户端和本机聊天都会经过它）。
     * >0 = 服务器在干活 → 状态灯呼吸闪烁。
     */
    private val _activeRequests = MutableStateFlow(0)
    val activeRequests: StateFlow<Int> = _activeRequests.asStateFlow()

    fun beginRequest() { _activeRequests.update { it + 1 } }
    fun endRequest() { _activeRequests.update { (it - 1).coerceAtLeast(0) } }
    fun resetRequests() { _activeRequests.value = 0 }

    /** 最近一次启动用的完整配置，供「应用配置 → 重启」复用。 */
    @Volatile var lastConfig: com.ggufserver.app.model.ServerConfig? = null

    private val _activeModel = MutableStateFlow<String?>(null)
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    private val _activeAlias = MutableStateFlow("")
    val activeAlias: StateFlow<String> = _activeAlias.asStateFlow()

    private val _port = MutableStateFlow(8080)
    val port: StateFlow<Int> = _port.asStateFlow()

    /**
     * llama-server 实际监听的内部端口（永远 127.0.0.1，公开端口±1，见 Gateway 的端口拓扑）。
     * 插件桥的 gguf.llm.* 直连这里，天然不过网关的插件管线。
     */
    private val _internalPort = MutableStateFlow(8081)
    val internalPort: StateFlow<Int> = _internalPort.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun setState(s: ServerState) { _state.value = s }
    fun setRestarting(b: Boolean) { _restarting.value = b }
    fun setActiveModel(name: String?) { _activeModel.value = name }
    fun setActiveAlias(a: String) { _activeAlias.value = a }
    fun setPort(p: Int) { _port.value = p }
    fun setInternalPort(p: Int) { _internalPort.value = p }

    fun appendLog(line: String) {
        _logs.update { current ->
            val appended = current + line
            if (appended.size > MAX_LINES) appended.subList(appended.size - MAX_LINES, appended.size).toList()
            else appended
        }
        if (fileLogging) writeToFile(line)
    }

    fun clearLogs() {
        _logs.value = emptyList()
        runCatching { logFile()?.delete() }
    }

    // ---- 日志记录模式：把每条日志持久化到文件，供导出分析问题 ----
    @Volatile private var logDir: File? = null
    @Volatile var fileLogging: Boolean = false
    private const val MAX_LOG_BYTES = 4L * 1024 * 1024   // 4MB 上限，超了截掉前半、保留最近
    private val logFmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    /** App 启动时注入日志目录 + 当前开关（见 App.onCreate）。 */
    fun initFileLog(dir: File, enabled: Boolean) { logDir = dir; fileLogging = enabled }
    fun logFile(): File? = logDir?.let { File(it, "debug-log.txt") }
    fun logFileSize(): Long = logFile()?.let { if (it.exists()) it.length() else 0L } ?: 0L

    private fun writeToFile(line: String) {
        val f = logFile() ?: return
        synchronized(this) {
            runCatching {
                f.parentFile?.mkdirs()
                f.appendText(logFmt.format(java.util.Date()) + "  " + line + "\n")
                if (f.length() > MAX_LOG_BYTES) {
                    val txt = f.readText()
                    f.writeText("…（超过 4MB，前半已截断）\n" + txt.substring(txt.length / 2))
                }
            }
        }
    }
}
