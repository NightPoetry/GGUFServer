package com.ggufserver.app.plugin

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端侧神经 TTS（sherpa-onnx VITS，`gguf.tts.synthesizeLocal` 桥的落点）。
 *
 * 走 sherpa-onnx 的 **JNI 进程内推理**（libsherpa-onnx-jni.so + libonnxruntime.so，
 * 与其官方安卓 App 完全同一条路）——曾试过静态链接的 exec 二进制路线，onnxruntime
 * 在两个版本上都于 CreateSession 段错误（静态注册表被链接器裁剪的经典病），JNI 共享库
 * 是安卓上唯一被大规模验证过的形态。进程内加载的额外好处：模型常驻，首句后合成明显更快。
 *
 * 模型（model.onnx + lexicon.txt + tokens.txt [+ *.fst]）放 filesDir/tts-models/<名字>/，
 * aishell3 一类多说话人模型用 sid 选音色（0~173）。完全离线。
 */
object NeuralTts {
    private val seq = AtomicInteger(0)
    private val lock = Any()

    @Volatile private var engine: OfflineTts? = null
    @Volatile private var loadedModel: String? = null
    @Volatile private var jniOk: Boolean? = null

    private fun ensureJni(): Boolean {
        jniOk?.let { return it }
        synchronized(lock) {
            jniOk?.let { return it }
            val ok = runCatching {
                System.loadLibrary("onnxruntime")
                System.loadLibrary("sherpa-onnx-jni")
            }.isSuccess
            jniOk = ok
            if (!ok) com.ggufserver.app.ServerBus.appendLog("[NeuralTts] JNI 库加载失败（本版本 APK 可能未内置）")
            return ok
        }
    }

    private fun modelsRoot(context: Context): File = File(context.filesDir, "tts-models")

    /** 已安装的本地音色模型目录名列表（含 model.onnx 才算数）。 */
    fun models(context: Context): List<String> =
        modelsRoot(context).listFiles { f -> f.isDirectory && File(f, "model.onnx").exists() }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun status(context: Context): JSONObject = JSONObject()
        .put("available", ensureJni() && models(context).isNotEmpty())
        .put("binReady", ensureJni())
        .put("models", JSONArray(models(context)))

    /** 惰性加载/切换模型（常驻直到换模型；内存吃紧时宿主可调 [release]）。 */
    private fun ensureEngine(context: Context, modelName: String?): OfflineTts? {
        if (!ensureJni()) return null
        val name = (if (modelName.isNullOrBlank()) models(context).firstOrNull() else modelName) ?: return null
        synchronized(lock) {
            if (engine != null && loadedModel == name) return engine
            engine?.let { runCatching { it.release() } }
            engine = null
            loadedModel = null
            val dir = File(modelsRoot(context), name)
            if (!File(dir, "model.onnx").exists()) return null
            val fsts = listOf("phone.fst", "date.fst", "number.fst")
                .map { File(dir, it) }.filter { it.exists() }
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, "model.onnx").absolutePath,
                        lexicon = File(dir, "lexicon.txt").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                    ),
                    numThreads = 4,
                ),
                ruleFsts = fsts.joinToString(",") { it.absolutePath },
            )
            return runCatching { OfflineTts(config = cfg) }
                .onFailure { com.ggufserver.app.ServerBus.appendLog("[NeuralTts] 模型加载失败：${it.message}") }
                .getOrNull()
                ?.also { engine = it; loadedModel = name }
        }
    }

    fun release() {
        synchronized(lock) {
            engine?.let { runCatching { it.release() } }
            engine = null
            loadedModel = null
        }
    }

    /**
     * 合成到 WAV（阻塞；模型已常驻时 1B 手机 CPU 上一句话秒级）。输出放调用插件的
     * 合成缓存目录（__tts__ 域下可取），每次合成清旧只留最新。
     */
    fun synthesize(
        context: Context,
        pluginId: String,
        text: String,
        sid: Int = 0,
        speed: Float = 1f,
        modelName: String? = null,
    ): File? {
        val clipped = text.take(2000)
        if (clipped.isBlank()) return null
        val eng = ensureEngine(context, modelName) ?: return null

        val outDir = File(context.cacheDir, "tts-synth/$pluginId")
        runCatching { outDir.deleteRecursively() }
        outDir.mkdirs()
        val out = File(outDir, "n${seq.incrementAndGet()}.wav")

        return runCatching {
            val audio = eng.generate(text = clipped, sid = sid.coerceAtLeast(0), speed = speed.coerceIn(0.5f, 2f))
            if (audio.samples.isEmpty()) return null
            audio.save(out.absolutePath)
            if (out.exists() && out.length() > 44) out else null
        }.onFailure {
            com.ggufserver.app.ServerBus.appendLog("[NeuralTts] 合成失败：${it.message}")
        }.getOrNull()
    }
}
