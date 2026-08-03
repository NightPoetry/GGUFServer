package com.ggufserver.app.model

import java.io.File

/** A place models can be stored (internal storage or an SD card), LM-Studio style. */
data class StorageLocation(
    val label: String,
    val modelsDir: File,
    val freeBytes: Long
) {
    val path: String get() = modelsDir.absolutePath
}
