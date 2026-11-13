package com.ggufserver.app.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ggufserver.app.App
import com.ggufserver.app.MainActivity
import com.ggufserver.app.R
import com.ggufserver.app.ServerBus
import com.ggufserver.app.ServerState
import com.ggufserver.app.data.SettingsStore
import com.ggufserver.app.model.ServerConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs the bundled `llama-server` binary as a child process and keeps it alive as a
 * foreground service. The binary lives in the app's nativeLibraryDir (extracted from
 * jniLibs), which is the only app-writable location on modern Android with exec permission.
 */
class LlamaServerService : Service() {

    private var process: Process? = null
    private var gateway: Gateway? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var stopRequested = false
    @Volatile private var reachedRunning = false
    @Volatile private var pendingRestart: Intent? = null
    private val cleanedUp = AtomicBoolean(false)

    // GPU→CPU 崩溃回退（fail-open）：记住本次子进程用的 -ngl 和模型，崩了自动降级重启一次。
    @Volatile private var launchedNgl = 0
    @Volatile private var launchedModelPath: String? = null
    @Volatile private var gpuFallbackDone = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论哪种触发（包括系统 sticky 空 intent 重启），先满足前台服务契约再说——
        // 任何提前 return 跳过 startForeground() 都会招来
        // ForegroundServiceDidNotStartInTimeException 直接杀进程（「应用配置」闪退的根因）。
        startForeground(NOTIF_ID, buildNotification(ServerBus.activeModel.value?.let { "运行中 · $it" } ?: "GGUF 服务器"))
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                val p = process
                if (p != null) p.destroy() else cleanup(ServerState.STOPPED)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val p = process
                if (p != null) {
                    // 运行中收到新配置 = 原地重启：杀旧子进程，等它退出后在本实例内拉起新配置，
                    // 避免 stop→start 两个 service 命令交错的竞态。
                    ServerBus.setState(ServerState.STARTING)
                    pendingRestart = intent
                    p.destroy()
                } else {
                    startServer(intent)
                }
            }
            else -> {
                // 系统 sticky 重启（null intent）：没有配置可恢复，优雅退场
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startServer(intent: Intent) {
        if (process != null) return

        val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "模型"
        val port = intent.getIntExtra(EXTRA_PORT, 8080)

        // Must call startForeground promptly after startForegroundService().
        ServerBus.setState(ServerState.STARTING)
        ServerBus.setActiveModel(modelName)
        ServerBus.setPort(port)
        startForeground(NOTIF_ID, buildNotification("正在启动 $modelName …"))
        acquireLocks()

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath.isNullOrBlank() || !File(modelPath).exists()) {
            fail("模型文件不存在：$modelPath")
            return
        }

        val nativeDir = applicationInfo.nativeLibraryDir
        val bin = File(nativeDir, SERVER_BIN)
        if (!bin.exists()) {
            fail("找不到 $SERVER_BIN。请先运行 scripts/build-llama-android.sh 生成二进制并放入 jniLibs 后重新打包。")
            return
        }

        val ctx = intent.getIntExtra(EXTRA_CTX, 4096)
        val threads = intent.getIntExtra(EXTRA_THREADS, 4)
        val ngl = intent.getIntExtra(EXTRA_NGL, 0)
        // 记住本次引擎，供 GPU→CPU 回退判断。ngl>0 是一次全新 GPU 尝试 → 允许再回退一次。
        launchedNgl = ngl
        launchedModelPath = modelPath
        if (ngl > 0) gpuFallbackDone = false
        val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        val disableThinking = intent.getBooleanExtra(EXTRA_DISABLE_THINKING, true)
        val noMmap = intent.getBooleanExtra(EXTRA_NO_MMAP, false)
        val compress = intent.getBooleanExtra(EXTRA_COMPRESS, true)
        val maxDim = intent.getIntExtra(EXTRA_MAXDIM, 256)
        // 端口拓扑（插件网关落地后固定不变，见 docs/工具插件规范.md §6.8）：llama-server 永远只
        // 绑 127.0.0.1:内部端口，Gateway 永远绑 0.0.0.0:公开端口——不再像过去那样只在开了图片
        // 压缩时才有代理；图片压缩降级为 Gateway 的第一个内置处理步骤，开关语义不变。
        val internalPort = if (port >= 65535) port - 1 else port + 1
        val bindHost = "127.0.0.1"

        val temp = intent.getFloatExtra(EXTRA_TEMP, 0.8f)
        val topP = intent.getFloatExtra(EXTRA_TOP_P, 0.95f)

        val cmd = mutableListOf(
            bin.absolutePath,
            "-m", modelPath,
            "--host", bindHost,
            "--port", internalPort.toString(),
            "-c", ctx.toString(),
            "-t", threads.toString(),
            "--temp", temp.toString(),
            "--top-p", topP.toString(),
            "--jinja"
        )
        // User-editable model call-name for the API `model` field (shown in the UI).
        // Single alias only: /v1/models then reports exactly this name. (llama-server doesn't
        // enforce the request's model field for single-model serving, so any name still works.)
        val rawAlias = intent.getStringExtra(EXTRA_ALIAS).orEmpty().trim()
        val alias = rawAlias.ifBlank { modelName.removeSuffix(".gguf").removeSuffix(".GGUF") }
            .replace(",", "").replace("\"", "").ifBlank { "model" }
        ServerBus.setActiveAlias(alias)
        cmd += "--alias"; cmd += alias
        // Vision projector (mmproj) → enables image input on /v1/chat/completions.
        val mmproj = intent.getStringExtra(EXTRA_MMPROJ).orEmpty()
        if (mmproj.isNotBlank() && File(mmproj).exists()) { cmd += "--mmproj"; cmd += mmproj }
        // Only pass -ngl when >0; passing it on a CPU-only build prints scary GPU warnings.
        if (ngl > 0) { cmd += "-ngl"; cmd += ngl.toString() }
        // Disable the model's chain-of-thought so replies aren't eaten by "thinking".
        // (--reasoning off sets enable_thinking=false in the chat template.)
        if (disableThinking) { cmd += "--reasoning"; cmd += "off" }
        // mmap off can help when the model lives on an SD card / exFAT.
        if (noMmap) { cmd += "--load-mode"; cmd += "none" }
        if (apiKey.isNotBlank()) {
            cmd += "--api-key"
            cmd += apiKey
        }

        val shown = cmd.joinToString(" ")
        ServerBus.appendLog("$ " + if (apiKey.isNotBlank()) shown.replace(apiKey, "***") else shown)

        try {
            val pb = ProcessBuilder(cmd).apply {
                redirectErrorStream(true)
                directory(File(nativeDir))
                environment()["LD_LIBRARY_PATH"] = nativeDir
                environment()["HOME"] = filesDir.absolutePath
                environment()["TMPDIR"] = cacheDir.absolutePath
                // 打包了 Vulkan 后端时的两条安全阀（见 docs/GPU加速与引擎测评.md）：
                // 1) 某些驱动（Mali）上报 bufferDeviceAddress 却没导出函数指针 → 关掉这个次要优化，
                //    否则连 -ngl 0 纯 CPU 路径都会在 host buffer 分配时 segfault。
                environment()["GGML_VK_DISABLE_BDA"] = "1"
                // 2) CPU 引擎（ngl==0）：让 Vulkan 报告 0 设备，强制计算图留在 CPU（含 dotprod 变体）。
                //    否则统一内存设备上即便 -ngl 0，整张图也会被塞到慢速 GPU 上跑。
                if (ngl <= 0) environment()["GGML_VK_VISIBLE_DEVICES"] = ""
            }
            val proc = pb.start()
            process = proc
            ServerBus.setInternalPort(internalPort)

            // 网关常驻启动（不再像旧 ImageProxy 那样只在开了图片压缩时才存在）：绑公开端口，
            // 转发到 127.0.0.1:内部端口，同时是插件中间件管线唯一的落点。
            try {
                val g = Gateway(port, internalPort, compressImages = compress, maxDim = maxDim, contextSize = ctx)
                g.start()
                gateway = g
                ServerBus.appendLog("[插件网关] 0.0.0.0:$port → llama-server:$internalPort" + if (compress) "，自动缩放图片 ≤${maxDim}px" else "")
            } catch (e: Exception) {
                fail("网关启动失败（端口 $port 被占用？）：${e.message}")
                return
            }

        // Flip to RUNNING once the model has loaded (poll /health, plus a log-based backup).
        startHealthPoll(internalPort, port, modelName)

        // 向量模型（嵌入服务）随主服务一并拉起：fail-open，坏了只记日志与状态，主服务不受影响。
        val embeddingPath = intent.getStringExtra(EXTRA_EMBEDDING).orEmpty()
        if (embeddingPath.isNotBlank()) {
            val embAlias = File(embeddingPath).nameWithoutExtension
            thread(name = "embedding-start", isDaemon = true) {
                try {
                    EmbeddingServer.ensure(applicationContext, embeddingPath, embAlias, apiKey, threads)
                } catch (e: Exception) {
                    ServerBus.appendLog("[向量模型] 启动失败（不影响主服务）：${e.message}")
                }
            }
        }

            thread(name = "llama-server-io", isDaemon = true) {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        ServerBus.appendLog(line)
                        val l = line.lowercase()
                        if (!reachedRunning && l.contains("listening on")) {
                            markRunning(modelName, port)
                        }
                    }
                } catch (_: Exception) {
                    // stream closed on process exit
                }
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                ServerBus.appendLog("[llama-server 退出，code=$code]")
                val restart = pendingRestart
                val gpuCrash = code != 0 && !stopRequested && launchedNgl > 0 && !gpuFallbackDone
                if (restart != null && !stopRequested) {
                    // 原地重启：清掉旧进程的痕迹，用新配置再拉一次
                    pendingRestart = null
                    runCatching { gateway?.stop() }
                    gateway = null
                    process = null
                    reachedRunning = false
                    ServerBus.resetRequests()
                    ServerBus.appendLog("[应用新配置，重启中…]")
                    startServer(restart)
                } else if (gpuCrash) {
                    // GPU 引擎子进程异常退出（多为 segfault）→ app 本体不受影响，自动降级：只回退一次，用纯 CPU 重启。
                    // 崩在“还没跑起来”阶段（!reachedRunning）= 确定不兼容 → 永久标记该模型 GPU 不可用，下次直接 CPU。
                    gpuFallbackDone = true
                    val mp = launchedModelPath
                    val definite = !reachedRunning
                    if (mp != null && definite) {
                        runCatching { SettingsStore(this@LlamaServerService).setModelGpuBad(mp, true) }
                    }
                    ServerBus.appendLog(
                        if (definite) "[GPU 不兼容] 子进程退出 code=$code，已标记该模型改用 CPU，回退重启中…"
                        else "[GPU 异常退出 code=$code] 本次回退 CPU 重启中…"
                    )
                    runCatching { gateway?.stop() }
                    gateway = null
                    process = null
                    reachedRunning = false
                    ServerBus.resetRequests()
                    startServer(Intent(intent).putExtra(EXTRA_NGL, 0))
                } else {
                    val finalState = when {
                        stopRequested -> ServerState.STOPPED
                        code == 0 -> ServerState.STOPPED
                        else -> ServerState.ERROR
                    }
                    cleanup(finalState)
                }
            }
        } catch (e: Exception) {
            fail("启动失败：${e.message}")
        }
    }

    private fun fail(message: String) {
        ServerBus.appendLog("[错误] $message")
        cleanup(ServerState.ERROR)
    }

    /** Runs once: tears down process, locks, notification and the service itself. */
    private fun cleanup(state: ServerState) {
        if (!cleanedUp.compareAndSet(false, true)) return
        runCatching { gateway?.stop() }
        gateway = null
        // 前台服务停了就没有对话在进行，记忆模型（第二个实例）也一并卸载，释放那份内存。
        runCatching { MemoryModelServer.stop() }
        runCatching { EmbeddingServer.stop() }
        process?.let { p ->
            runCatching { p.destroy() }
            runCatching { p.destroyForcibly() }
        }
        process = null
        releaseLocks()
        ServerBus.resetRequests()
        ServerBus.setState(state)
        ServerBus.setActiveModel(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gguf:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gguf:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LlamaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("GGUF 服务器")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_server)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    @Synchronized
    private fun markRunning(modelName: String, port: Int) {
        if (reachedRunning) return
        reachedRunning = true
        ServerBus.setState(ServerState.RUNNING)
        updateNotification("运行中 · $modelName · 端口 $port")
        // 模板能力**趁现在探**（两次 /apply-template，几十毫秒）：否则第一次探测会落在某个请求路径的
        // 预算里——真机上它正好落进 route 深判的预算，把本来就紧的那 3 秒又吃掉一截。
        thread(name = "template-probe", isDaemon = true) { runCatching { TemplateProbe.caps() } }
    }

    /** Polls the llama-server /health on [pollPort] until 200, then flips state to RUNNING. */
    private fun startHealthPoll(pollPort: Int, publicPort: Int, modelName: String) {
        thread(name = "health-poll", isDaemon = true) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 300_000L
            while (process != null && !reachedRunning &&
                android.os.SystemClock.elapsedRealtime() < deadline
            ) {
                val ok = try {
                    val conn = java.net.URL("http://127.0.0.1:$pollPort/health")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1000
                    conn.readTimeout = 1500
                    val code = conn.responseCode
                    conn.disconnect()
                    code == 200
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    markRunning(modelName, publicPort)
                    break
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!cleanedUp.get()) cleanup(ServerState.STOPPED)
    }

    companion object {
        const val SERVER_BIN = "libllamaserver.so"
        private const val NOTIF_ID = 42

        const val ACTION_START = "com.ggufserver.app.START"
        const val ACTION_STOP = "com.ggufserver.app.STOP"
        private const val EXTRA_MODEL_PATH = "modelPath"
        private const val EXTRA_MODEL_NAME = "modelName"
        private const val EXTRA_ALIAS = "alias"
        private const val EXTRA_MMPROJ = "mmproj"
        private const val EXTRA_COMPRESS = "compress"
        private const val EXTRA_MAXDIM = "maxDim"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_CTX = "ctx"
        private const val EXTRA_THREADS = "threads"
        private const val EXTRA_NGL = "ngl"
        private const val EXTRA_API_KEY = "apiKey"
        private const val EXTRA_DISABLE_THINKING = "disableThinking"
        private const val EXTRA_NO_MMAP = "noMmap"
        private const val EXTRA_TEMP = "temperature"
        private const val EXTRA_TOP_P = "topP"
        private const val EXTRA_EMBEDDING = "embeddingModelPath"

        fun start(context: Context, cfg: ServerConfig) {
            ServerBus.lastConfig = cfg
            val intent = Intent(context, LlamaServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_PATH, cfg.modelPath)
                putExtra(EXTRA_MODEL_NAME, cfg.modelName)
                putExtra(EXTRA_ALIAS, cfg.alias)
                putExtra(EXTRA_MMPROJ, cfg.mmprojPath)
                putExtra(EXTRA_COMPRESS, cfg.compressImages)
                putExtra(EXTRA_MAXDIM, cfg.maxImageDim)
                putExtra(EXTRA_PORT, cfg.port)
                putExtra(EXTRA_CTX, cfg.contextSize)
                putExtra(EXTRA_THREADS, cfg.threads)
                putExtra(EXTRA_NGL, cfg.nGpuLayers)
                putExtra(EXTRA_API_KEY, cfg.apiKey)
                putExtra(EXTRA_DISABLE_THINKING, cfg.disableThinking)
                putExtra(EXTRA_NO_MMAP, cfg.noMmap)
                putExtra(EXTRA_TEMP, cfg.temperature)
                putExtra(EXTRA_TOP_P, cfg.topP)
                putExtra(EXTRA_EMBEDDING, cfg.embeddingModelPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LlamaServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
