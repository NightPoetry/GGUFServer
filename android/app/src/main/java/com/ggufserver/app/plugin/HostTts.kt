package com.ggufserver.app.plugin

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 宿主的系统语音朗读（`gguf.tts.*` 桥的落点）。Android WebView 不带 Web Speech API
 * （speechSynthesis 不存在），插件想发声只能经这里走系统 TextToSpeech 引擎。
 *
 * 引擎初始化是异步的：首次 speak 时惰性创建，就绪前收到的文本先记在 [pendingText]
 * （只留最后一条——朗读语义本就是「后点的顶掉先点的」，speak 也用 QUEUE_FLUSH）。
 * 引擎创建失败（设备没有 TTS 服务）不抛异常，只是无声——朗读是旁路能力，绝不影响对话。
 */
object HostTts {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pendingText: String? = null

    // 本次待读文本的音调/语速（变声提供者「调穿」默认实现时随 speak 传入；1.0 = 引擎默认）
    private var pendingPitch = 1f
    private var pendingRate = 1f

    fun speak(context: Context, text: String, pitch: Float = 1f, rate: Float = 1f) {
        // TextToSpeech 的最长输入有硬限制（约 4000 字符），超出部分静默截断
        val clipped = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        if (clipped.isBlank()) return
        val p = pitch.coerceIn(0.5f, 2f)
        val r = rate.coerceIn(0.5f, 2f)
        mainHandler.post {
            pendingPitch = p
            pendingRate = r
            val engine = tts
            when {
                engine == null -> {
                    pendingText = clipped
                    tts = TextToSpeech(context.applicationContext) { status ->
                        mainHandler.post {
                            if (status == TextToSpeech.SUCCESS) {
                                ready = true
                                pendingText?.let { doSpeak(it) }
                            } else {
                                tts = null // 下次 speak 再试一次
                            }
                            pendingText = null
                        }
                    }
                }
                ready -> doSpeak(clipped)
                else -> pendingText = clipped // 引擎还在初始化：顶掉更早的待读文本
            }
        }
    }

    private fun doSpeak(text: String) {
        runCatching {
            tts?.setPitch(pendingPitch)
            tts?.setSpeechRate(pendingRate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gguf-tts")
        }
    }

    fun stop() {
        mainHandler.post {
            pendingText = null
            runCatching { tts?.stop() }
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("ready", ready)
        .put("speaking", runCatching { tts?.isSpeaking == true }.getOrDefault(false))

    // ============================== 合成到文件（音频级变声管道的取料口） ==============================

    private val synthSeq = AtomicInteger(0)

    /**
     * 引擎就绪保证（阻塞版，供 synthesize 的后台线程调用；speak 仍走异步惰性路径）。
     * 引擎创建必须在主线程 post，这里轮询 ready 标志等待，超时即放弃。
     */
    private fun ensureReady(context: Context, timeoutMs: Long): Boolean {
        if (ready) return true
        mainHandler.post {
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) ready = true else tts = null
                }
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ready) return true
            Thread.sleep(50)
        }
        return ready
    }

    /**
     * 把文本合成为 WAV 文件（不出声）——变声/RVC 类插件的取料口：拿到原始音频后自行二次
     * 处理再播放（引擎的 setPitch 在部分 OEM 引擎上被忽略，音频级处理是唯一可靠路径）。
     * 输出在 cacheDir/tts-synth/<pluginId>/ 下（每次合成清空该插件的旧文件，只留最新），
     * 经插件虚拟域名 `__tts__/<文件名>` 提供给对应插件的 WebView 取用。阻塞等待，超时返回 null。
     */
    fun synthesize(context: Context, pluginId: String, text: String, timeoutMs: Long = 20_000): File? {
        val clipped = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        if (clipped.isBlank()) return null
        if (!ensureReady(context, 5_000)) return null
        val engine = tts ?: return null
        val dir = File(context.cacheDir, "tts-synth/$pluginId")
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        val out = File(dir, "s${synthSeq.incrementAndGet()}.wav")
        val done = CountDownLatch(1)
        var ok = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == out.name) { ok = true; done.countDown() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == out.name) done.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == out.name) done.countDown()
            }
        })
        val r = runCatching {
            engine.setPitch(1f)
            engine.setSpeechRate(1f)
            engine.synthesizeToFile(clipped, Bundle(), out, out.name)
        }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) return null
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return if (ok && out.exists() && out.length() > 44) out else null
    }
}
