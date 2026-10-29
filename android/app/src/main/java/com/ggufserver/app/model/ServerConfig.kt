package com.ggufserver.app.model

/** Launch parameters passed from the UI to the server service. */
data class ServerConfig(
    val modelPath: String,
    val modelName: String,
    val alias: String = "",
    val mmprojPath: String = "",
    val port: Int = 8080,
    val contextSize: Int = 4096,
    val threads: Int = 4,
    val nGpuLayers: Int = 0,
    val apiKey: String = "",
    val disableThinking: Boolean = true,
    val noMmap: Boolean = false,
    val compressImages: Boolean = true,
    val maxImageDim: Int = 256,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val embeddingModelPath: String = ""
)
