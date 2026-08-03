package com.ggufserver.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ggufserver.app.model.StorageLocation
import java.io.File

data class ModelFile(val file: File, val name: String, val sizeBytes: Long)

/** Manages GGUF files across selectable storage volumes (internal / SD card). */
class ModelStore(private val context: Context) {

    /**
     * Available model directories:
     *  - "手机内部（私有）": app-private internal storage (invisible to file managers, always works)
     *  - "手机存储（可管理）": app external files dir on shared internal storage (adb/file-manager accessible)
     *  - "SD卡/外置存储": app external files dir on a removable card, when present
     * All are real filesystem paths readable by the llama-server child process, and need NO permission.
     */
    fun locations(): List<StorageLocation> {
        val out = ArrayList<StorageLocation>()

        val priv = File(context.filesDir, "models").apply { mkdirs() }
        out.add(StorageLocation("手机内部（私有）", priv, priv.freeSpace))

        val ext = runCatching { context.getExternalFilesDirs(null) }.getOrDefault(emptyArray())
        var sdSeen = 0
        ext.forEachIndexed { i, dir ->
            if (dir == null) return@forEachIndexed
            val models = File(dir, "models").apply { mkdirs() }
            if (i == 0) {
                // 主共享存储上的 app 专属目录（Android/data/...）：与「内部（私有）」同一块盘，
                // Android 11+ 文件管理器也进不去，有了「自定义目录」后纯属冗余——
                // 仅当老版本已经往里放过模型时才保留展示，避免文件被"藏起来"。
                val hasModels = models.listFiles { f ->
                    f.isFile && f.name.endsWith(".gguf", ignoreCase = true)
                }?.isNotEmpty() == true
                if (hasModels) out.add(StorageLocation("手机存储（旧位置）", models, models.freeSpace))
            } else {
                sdSeen++
                val label = if (sdSeen == 1) "SD卡/外置存储" else "外置存储 $sdSeen"
                // 真 SD 卡上的 app 专属目录：免任何授权即可读写，保留作为最省事的 SD 选项
                out.add(StorageLocation(label, models, models.freeSpace))
            }
        }

        // 用户自选目录（SD卡/共享存储任意位置）。目录可能因缺「所有文件访问」权限暂不可读，
        // 仍然列出来，由界面提示去授权。
        val customPath = SettingsStore(context).customModelDir
        if (customPath.isNotBlank()) {
            val dir = File(customPath)
            out.add(
                StorageLocation(
                    "自定义 · ${dir.name.ifBlank { customPath }}",
                    dir,
                    if (dir.exists()) dir.freeSpace else 0L
                )
            )
        }

        return out.distinctBy { runCatching { it.modelsDir.canonicalPath }.getOrDefault(it.modelsDir.path) }
    }

    private fun ggufs(dir: File): List<ModelFile> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .map { ModelFile(it, it.name, it.length()) }

    /** Regular chat/text models (excludes mmproj vision projectors). */
    fun list(dir: File): List<ModelFile> = ggufs(dir).filter { !isMmproj(it.name) }

    /** Multimodal projector files (for image input). Convention: name contains "mmproj". */
    fun mmprojs(dir: File): List<ModelFile> = ggufs(dir).filter { isMmproj(it.name) }

    fun isMmproj(name: String): Boolean = name.contains("mmproj", ignoreCase = true)

    /** Really deletes the file from disk. */
    fun delete(model: ModelFile): Boolean = model.file.delete()

    fun displayName(uri: Uri): String {
        var name = "model.gguf"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
        }
        if (!name.endsWith(".gguf", ignoreCase = true)) name = "$name.gguf"
        return name.replace(File.separatorChar, '_')
    }

    fun sizeOf(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
        }
        return -1L
    }

    /**
     * Streams the picked GGUF into [destDir].
     * @param onProgress (bytesCopied, totalBytes); totalBytes is -1 if unknown.
     */
    fun import(uri: Uri, destDir: File, onProgress: (Long, Long) -> Unit): File {
        destDir.mkdirs()
        val target = File(destDir, displayName(uri))
        val total = sizeOf(uri)
        val tmp = File(destDir, target.name + ".part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开所选文件" }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    onProgress(copied, total)
                }
                out.flush()
            }
        }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "写入模型文件失败（目标磁盘可能空间不足）" }
        return target
    }
}
