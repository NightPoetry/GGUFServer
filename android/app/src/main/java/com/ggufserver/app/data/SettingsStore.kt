package com.ggufserver.app.data

import android.content.Context

/** Persists user settings across launches (SharedPreferences). */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("gguf_settings", Context.MODE_PRIVATE)

    var locationPath: String
        get() = p.getString("locationPath", "") ?: ""
        set(v) { p.edit().putString("locationPath", v).apply() }

    /** 用户在 SD 卡/共享存储上自选的模型目录（空 = 未设置）。内部私有目录不可选。 */
    var customModelDir: String
        get() = p.getString("customModelDir", "") ?: ""
        set(v) { p.edit().putString("customModelDir", v).apply() }

    var port: Int
        get() = p.getInt("port", 8080)
        set(v) { p.edit().putInt("port", v).apply() }

    var contextSize: Int
        get() = p.getInt("ctx", 4096)
        set(v) { p.edit().putInt("ctx", v).apply() }

    var threads: Int
        get() = p.getInt("threads", defaultThreads())
        set(v) { p.edit().putInt("threads", v).apply() }

    var nGpuLayers: Int
        get() = p.getInt("ngl", 0)
        set(v) { p.edit().putInt("ngl", v).apply() }

    var apiKey: String
        get() = p.getString("apiKey", "") ?: ""
        set(v) { p.edit().putString("apiKey", v).apply() }

    var disableThinking: Boolean
        get() = p.getBoolean("disableThinking", true)
        set(v) { p.edit().putBoolean("disableThinking", v).apply() }

    var noMmap: Boolean
        get() = p.getBoolean("noMmap", false)
        set(v) { p.edit().putBoolean("noMmap", v).apply() }

    var compressImages: Boolean
        get() = p.getBoolean("compressImages", true)
        set(v) { p.edit().putBoolean("compressImages", v).apply() }

    var maxImageDim: Int
        get() = p.getInt("maxImageDim", 256)
        set(v) { p.edit().putInt("maxImageDim", v).apply() }

    // --- 聊天右滑抽屉里的模型配置 ---
    var chatSystemPrompt: String
        get() = p.getString("chatSysPrompt", "") ?: ""
        set(v) { p.edit().putString("chatSysPrompt", v).apply() }

    var chatTemperature: Float
        get() = p.getFloat("chatTemp", 0.8f)
        set(v) { p.edit().putFloat("chatTemp", v).apply() }

    var chatTopP: Float
        get() = p.getFloat("chatTopP", 0.95f)
        set(v) { p.edit().putFloat("chatTopP", v).apply() }

    /** 发送消息后自动失去输入焦点（收起键盘）。默认不收起。 */
    var chatBlurOnSend: Boolean
        get() = p.getBoolean("chatBlurOnSend", false)
        set(v) { p.edit().putBoolean("chatBlurOnSend", v).apply() }

    // 日志记录模式：开启后 ServerBus.appendLog 会把每条日志持久化到 filesDir/logs/debug-log.txt（用于分析问题）
    var logRecording: Boolean
        get() = p.getBoolean("logRecording", false)
        set(v) { p.edit().putBoolean("logRecording", v).apply() }

    // --- 按模型记住的最优引擎（"最优引擎选择测评"结果 / GPU 崩溃回退结果） ---
    // 键用模型绝对路径，天然唯一、无碰撞。ngl<0 表示"未测评"（用全局默认）。

    /** 某模型测评/回退后选定的 -ngl（0=纯 CPU；>0=GPU offload 层数）。null = 未测评过。 */
    fun modelNgl(modelPath: String): Int? =
        p.getInt("engineNgl:$modelPath", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    fun setModelNgl(modelPath: String, ngl: Int) {
        p.edit().putInt("engineNgl:$modelPath", ngl).apply()
    }

    /** 测评结果的人类可读摘要，展示在设置里（如 "CPU 91.6 t/s ✓ · GPU 崩溃"）。 */
    fun modelEngineNote(modelPath: String): String = p.getString("engineNote:$modelPath", "") ?: ""
    fun setModelEngineNote(modelPath: String, note: String) {
        p.edit().putString("engineNote:$modelPath", note).apply()
    }

    /** 该模型在本机 GPU 上会崩（测评或运行时子进程 segfault 探测到）→ 启动直接用 CPU，不再试 GPU。 */
    fun isModelGpuBad(modelPath: String): Boolean = p.getBoolean("gpuBad:$modelPath", false)
    fun setModelGpuBad(modelPath: String, bad: Boolean) {
        p.edit().putBoolean("gpuBad:$modelPath", bad).apply()
    }

    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
    }
}
