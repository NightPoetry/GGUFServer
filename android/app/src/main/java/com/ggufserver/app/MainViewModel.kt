package com.ggufserver.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ggufserver.app.data.ModelFile
import com.ggufserver.app.data.GgufProbe
import com.ggufserver.app.data.ModelStore
import com.ggufserver.app.data.SettingsStore
import com.ggufserver.app.model.ServerConfig
import com.ggufserver.app.model.StorageLocation
import com.ggufserver.app.plugin.ModelHubDownloader
import com.ggufserver.app.server.EmbeddingServer
import com.ggufserver.app.server.EngineBenchmark
import com.ggufserver.app.server.LlamaServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportProgress(val copied: Long, val total: Long, val name: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val settings = SettingsStore(app)

    var locations by mutableStateOf(store.locations())
        private set
    var selectedLocation by mutableStateOf(pickLocation())
        private set

    var models by mutableStateOf<List<ModelFile>>(emptyList())
        private set
    var selected by mutableStateOf<ModelFile?>(null)
    var importing by mutableStateOf<ImportProgress?>(null)
        private set
    var pendingDelete by mutableStateOf<ModelFile?>(null)
        private set

    // --- config (kept as strings for text fields) ---
    var port by mutableStateOf(settings.port.toString())
    var contextSize by mutableStateOf(settings.contextSize.toString())
    var threads by mutableStateOf(settings.threads.toString())
    var nGpuLayers by mutableStateOf(settings.nGpuLayers.toString())
    var apiKey by mutableStateOf(settings.apiKey)
    var disableThinking by mutableStateOf(settings.disableThinking)
    var noMmap by mutableStateOf(settings.noMmap)
    var compressImages by mutableStateOf(settings.compressImages)
    var maxImageDim by mutableStateOf(settings.maxImageDim.toString())

    /** Editable model call-name (API `model` field). Auto-fills from the file name, user can change it. */
    var modelName by mutableStateOf("")

    /** Vision projector (mmproj) files in the current directory, and the chosen one (null = text only). */
    var mmprojFiles by mutableStateOf<List<ModelFile>>(emptyList())
        private set
    var selectedMmproj by mutableStateOf<ModelFile?>(null)

    /** 向量模型（嵌入服务）选中的文件；null = 不启用。与主模型共用同一批 .gguf，路径持久化在 settings。 */
    var selectedEmbedding by mutableStateOf<ModelFile?>(null)
        private set
    // 模型类型自动识别（GgufProbe 读 GGUF 元数据：pooling_type/BERT 系架构=向量模型）
    var modelKinds by mutableStateOf<Map<String, GgufProbe.Kind>>(emptyMap())
    var showAllInEmbeddingList by mutableStateOf(false)

    /** 嵌入服务运行状态（随主服务拉起/停止），设置页向量模型卡直接订阅。 */
    val embeddingStatus get() = EmbeddingServer.status

    /** 对嵌入服务打一次真实 /v1/embeddings 自检，结果进日志（服务运行中才有意义）。 */
    fun testEmbedding() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { EmbeddingServer.selfTest() }
                .onSuccess { dim -> ServerBus.appendLog("[向量模型] 自检通过：返回 $dim 维向量") }
                .onFailure { ServerBus.appendLog("[向量模型] 自检失败：${it.message}") }
        }
    }

    val state get() = ServerBus.state
    val activeModel get() = ServerBus.activeModel
    val logs get() = ServerBus.logs

    init {
        refresh()
        // 模型市场下载完成 → 自动刷新模型列表（下载器是应用级单例，跨界面存活；
        // 文件已落在当前选中位置，refresh() 即入列并保持原选中）
        viewModelScope.launch {
            ModelHubDownloader.state.collect { s ->
                if (s.phase == ModelHubDownloader.Phase.DONE) refresh()
            }
        }
    }

    private fun pickLocation(): StorageLocation =
        locations.firstOrNull { it.path == settings.locationPath } ?: locations.first()

    fun refreshLocations() {
        locations = store.locations()
        selectedLocation = pickLocation()
        refresh()
    }

    fun selectLocation(loc: StorageLocation) {
        selectedLocation = loc
        settings.locationPath = loc.path
        selected = null
        refresh()
    }

    /** 当前设置的自定义模型目录路径（空 = 未设置）。 */
    val customDirPath: String get() = settings.customModelDir

    /** 设置 SD 卡/共享存储上的自定义模型目录，并切换过去。 */
    fun setCustomDir(path: String) {
        settings.customModelDir = path
        locations = store.locations()
        locations.find { it.path == path }?.let { selectLocation(it) } ?: run {
            selectedLocation = pickLocation(); refresh()
        }
    }

    /** 移除自定义目录（不删除里面的文件），若正选中则退回默认位置。 */
    fun clearCustomDir() {
        val wasSelected = selectedLocation.path == settings.customModelDir
        settings.customModelDir = ""
        locations = store.locations()
        if (wasSelected) settings.locationPath = ""
        selectedLocation = pickLocation()
        selected = null
        refresh()
    }

    fun refresh() {
        models = store.list(selectedLocation.modelsDir)
        mmprojFiles = store.mmprojs(selectedLocation.modelsDir)
        val prev = selected
        selected = models.find { it.file == prev?.file } ?: models.firstOrNull()
        val s = selected
        if (s != null && (prev?.file != s.file || modelName.isBlank())) {
            modelName = defaultAliasFor(s)
        }
        // 手动选过且文件还在就保留；否则只自动配对同家族的 mmproj（配不上 = 无）
        selectedMmproj = selectedMmproj?.let { c -> mmprojFiles.find { it.file == c.file } }
            ?: autoMmprojFor(selected)
        // 向量模型：按持久化路径找回（文件被删了就退回「不启用」）
        val embPath = settings.embeddingModelPath
        selectedEmbedding = if (embPath.isBlank()) null else models.find { it.file.absolutePath == embPath }
        // 模型类型自动识别：后台逐个读 GGUF 头（毫秒级/个，不碰张量区）
        viewModelScope.launch(Dispatchers.IO) {
            val map = HashMap<String, GgufProbe.Kind>()
            for (m in models) {
                map[m.file.absolutePath] = GgufProbe.kindOf(GgufProbe.probe(m.file))
            }
            modelKinds = map
        }
    }

    fun kindOf(model: ModelFile): GgufProbe.Kind = modelKinds[model.file.absolutePath] ?: GgufProbe.Kind.UNKNOWN

    fun selectModel(m: ModelFile) {
        selected = m
        modelName = defaultAliasFor(m)
        // 切换模型时重新配对：同家族自动带上，跨家族自动退回「无」（仍可手动改选）
        selectedMmproj = autoMmprojFor(m)
    }

    fun selectMmproj(m: ModelFile?) { selectedMmproj = m }

    /** 选择/清空向量模型（立即持久化；启动服务时生效）。 */
    fun selectEmbedding(m: ModelFile?) {
        selectedEmbedding = m
        settings.embeddingModelPath = m?.file?.absolutePath ?: ""
    }

    /**
     * 只有文件名同源（去掉 mmproj- 前缀和量化后缀后同家族）的 mmproj 才自动配对。
     * 跨家族硬配 llama-server 会直接启动失败（n_embd 不一致），
     * 例如 mmproj-Qwen3.5-2B-BF16 配 MiniCPM5-1B。
     */
    private fun familyKey(fileName: String): String = fileName
        .removePrefix("mmproj-").removePrefix("MMPROJ-")
        .removeSuffix(".gguf").removeSuffix(".GGUF")
        .replace(Regex("-(?:[iI]?[qQ]\\d[\\w]*|[bB][fF]16|[fF](?:16|32))$"), "")
        .lowercase()

    private fun autoMmprojFor(model: ModelFile?): ModelFile? {
        model ?: return null
        val key = familyKey(model.name)
        return mmprojFiles.firstOrNull {
            val k = familyKey(it.name)
            key.startsWith(k) || k.startsWith(key)
        }
    }

    private fun defaultAliasFor(m: ModelFile): String =
        m.name.removeSuffix(".gguf").removeSuffix(".GGUF")

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val name = store.displayName(uri)
            val dest = selectedLocation.modelsDir
            importing = ImportProgress(0, store.sizeOf(uri), name)
            try {
                val file = withContext(Dispatchers.IO) {
                    var lastPct = -1
                    store.import(uri, dest) { copied, total ->
                        val pct = if (total > 0) ((copied * 100) / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            importing = ImportProgress(copied, total, name)
                        }
                    }
                }
                refresh()
                models.find { it.file == file }?.let { selectModel(it) }
            } catch (e: Exception) {
                ServerBus.appendLog("[导入失败] ${e.message}")
            } finally {
                importing = null
            }
        }
    }

    fun requestDelete(model: ModelFile) { pendingDelete = model }
    fun cancelDelete() { pendingDelete = null }
    fun confirmDelete() {
        pendingDelete?.let { m ->
            val ok = store.delete(m)
            ServerBus.appendLog(if (ok) "[已删除] ${m.name}" else "[删除失败] ${m.name}")
        }
        pendingDelete = null
        refresh()
    }

    fun startServer() {
        persist()
        val model = selected ?: return
        LlamaServerService.start(getApplication(), buildConfig(model))
    }

    fun stopServer() = LlamaServerService.stop(getApplication())

    fun clearLogs() = ServerBus.clearLogs()

    // 日志记录模式（持久化系统调用与行为到文件，供导出分析问题；见 SettingsStore.logRecording）
    var logRecording by mutableStateOf(settings.logRecording)
        private set
    fun toggleLogRecording(on: Boolean) {
        settings.logRecording = on
        ServerBus.fileLogging = on
        logRecording = on
        ServerBus.appendLog(if (on) "[日志] 日志记录模式已开启" else "[日志] 日志记录模式已关闭")
    }
    fun logFileSize(): Long = ServerBus.logFileSize()
    fun logFile(): java.io.File? = ServerBus.logFile()

    // --- 最优引擎选择测评 ---
    var benchmarking by mutableStateOf(false)
        private set
    var benchmarkProgress by mutableStateOf("")
        private set

    /** 当前选中模型上次测评/回退记住的引擎摘要（展示用；空=没测过）。 */
    val selectedEngineNote: String
        get() = selected?.let { settings.modelEngineNote(it.file.absolutePath) } ?: ""

    /** 是否打包了 GPU(Vulkan) 后端——没有就不显示 GPU 相关项。 */
    val hasGpuBackend: Boolean get() = EngineBenchmark.hasVulkan(getApplication())

    /**
     * 对当前选中模型跑一小段基准，实测 CPU 与 GPU 速度，自动选最快的引擎并记住（按模型）。
     * 必须在服务停止时运行（否则两个进程抢模型/内存）。
     */
    fun runEngineBenchmark() {
        val model = selected ?: return
        if (benchmarking) return
        if (state.value == ServerState.RUNNING || state.value == ServerState.STARTING) {
            ServerBus.appendLog("[测评] 请先停止服务再测评")
            return
        }
        val path = model.file.absolutePath
        benchmarking = true
        benchmarkProgress = "准备中…"
        viewModelScope.launch {
            try {
                val outcome = EngineBenchmark.run(getApplication(), path) { msg ->
                    benchmarkProgress = msg
                }
                settings.setModelNgl(path, outcome.bestNgl)
                settings.setModelEngineNote(path, outcome.summary)
                // GPU 档崩溃 → 永久标记不可用；选到 GPU → 清除该标记
                val gpuRow = outcome.rows.find { it.ngl > 0 }
                when {
                    gpuRow != null && !gpuRow.ok -> settings.setModelGpuBad(path, true)
                    outcome.bestNgl > 0 -> settings.setModelGpuBad(path, false)
                }
                // 同步到可见的全局 -ngl 字段，让“启动”直接用上
                nGpuLayers = outcome.bestNgl.toString()
                ServerBus.appendLog("[测评完成] ${outcome.summary}")
            } catch (e: Exception) {
                ServerBus.appendLog("[测评失败] ${e.message}")
            } finally {
                benchmarking = false
                benchmarkProgress = ""
            }
        }
    }

    /** 解析某模型实际要用的 -ngl：GPU 不兼容→0；已测评→测评值；否则用全局字段。 */
    private fun effectiveNgl(modelPath: String): Int {
        if (settings.isModelGpuBad(modelPath)) return 0
        settings.modelNgl(modelPath)?.let { return it.coerceIn(0, 999) }
        return nGpuLayers.toIntOrNull()?.coerceIn(0, 999) ?: 0
    }

    private fun persist() {
        settings.port = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
        settings.contextSize = contextSize.toIntOrNull()?.coerceIn(256, 131072) ?: 4096
        settings.threads = threads.toIntOrNull()?.coerceIn(1, 16) ?: SettingsStore.defaultThreads()
        settings.nGpuLayers = nGpuLayers.toIntOrNull()?.coerceIn(0, 999) ?: 0
        settings.apiKey = apiKey.trim()
        settings.disableThinking = disableThinking
        settings.noMmap = noMmap
        settings.compressImages = compressImages
        settings.maxImageDim = maxImageDim.toIntOrNull()?.coerceIn(128, 4096) ?: 256
    }

    private fun buildConfig(model: ModelFile) = ServerConfig(
        modelPath = model.file.absolutePath,
        modelName = model.name,
        alias = modelName.trim().ifBlank { defaultAliasFor(model) },
        mmprojPath = selectedMmproj?.file?.absolutePath ?: "",
        port = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080,
        contextSize = contextSize.toIntOrNull()?.coerceIn(256, 131072) ?: 4096,
        threads = threads.toIntOrNull()?.coerceIn(1, 16) ?: SettingsStore.defaultThreads(),
        nGpuLayers = effectiveNgl(model.file.absolutePath),
        apiKey = apiKey.trim(),
        disableThinking = disableThinking,
        noMmap = noMmap,
        compressImages = compressImages,
        maxImageDim = maxImageDim.toIntOrNull()?.coerceIn(128, 4096) ?: 256,
        temperature = settings.chatTemperature,
        topP = settings.chatTopP,
        embeddingModelPath = settings.embeddingModelPath
    )
}
