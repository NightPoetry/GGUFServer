package com.ggufserver.app.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * GGUF 文件头轻量探测：只读文件头部元数据（不碰张量区），判定模型类型。
 *
 * 判定依据（LM Studio 同口径，见 lmstudio-bug-tracker#2177）：
 *  - `<arch>.pooling_type` 存在且非 0（NONE）= 向量（嵌入）模型的强信号；
 *  - `general.architecture` 属于 BERT 编码器家族（bert/nomic-bert/jina-bert…）= 向量模型；
 *  - 其余 = 语言模型；头部解析失败 = UNKNOWN（交给用户人工判断）。
 *
 * 多模态信号：同目录存在 mmproj-*.gguf（视觉投影），由 ModelStore.mmprojs 提供，不在此判定。
 */
object GgufProbe {

    enum class Kind { LLM, EMBEDDING, UNKNOWN }

    data class GgufMeta(val ok: Boolean, val architecture: String?, val poolingType: Int?)

    /** 已知 BERT 系编码器架构（向量模型家族）。含子串匹配（如 jina-bert-v2）。 */
    private val BERT_ARCHS = listOf(
        "nomic-bert", "jina-bert", "bert", "gte", "e5", "xlm-roberta",
    )

    fun probe(file: File): GgufMeta = try {
        RandomAccessFile(file, "r").use { raf -> parseStream(raf) }
    } catch (t: Throwable) {
        GgufMeta(ok = false, architecture = null, poolingType = null)
    }

    /** 供单测：从字节流解析（文件头即完整输入）。 */
    fun parseStream(raf: RandomAccessFile): GgufMeta {
        val magic = ByteArray(4).also { raf.readFully(it) }
        if (String(magic, StandardCharsets.US_ASCII) != "GGUF") return GgufMeta(false, null, null)
        val version = readU32(raf)
        if (version < 2) return GgufMeta(false, null, null) // v1 是 u32 计数的化石格式，不做
        readU64(raf) // tensor_count（本探测不关心张量区）
        val kvCount = readU64(raf)
        if (kvCount > 10_000) return GgufMeta(false, null, null) // 上限保护：正常模型元数据远小于此
        var arch: String? = null
        var pooling: Int? = null
        var i = 0L
        while (i < kvCount) {
            val key = readString(raf)
            val type = readU32(raf)
            when (type) {
                8L -> {
                    val v = readString(raf)
                    if (key == "general.architecture") arch = v
                    if (key.endsWith(".pooling_type") && v.toIntOrNull() != null) pooling = v.toIntOrNull()
                }
                4L -> {
                    val v = readU32(raf)
                    if (key.endsWith(".pooling_type")) pooling = v.toInt()
                }
                9L -> {
                    val elemType = readU32(raf)
                    val count = readU64(raf)
                    skipArray(raf, elemType, count)
                }
                else -> skipScalar(raf, type)
            }
            i++
            if (arch != null && pooling != null) break // 两个都要的拿齐即止
        }
        return GgufMeta(ok = true, architecture = arch, poolingType = pooling)
    }

    fun kindOf(meta: GgufMeta): Kind = when {
        !meta.ok -> Kind.UNKNOWN
        meta.poolingType != null && meta.poolingType != 0 -> Kind.EMBEDDING
        meta.architecture?.lowercase()?.let { a -> BERT_ARCHS.any(a::contains) } == true -> Kind.EMBEDDING
        else -> Kind.LLM
    }

    // ── GGUF v2/v3 二进制遍历（小端） ──

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4).also { raf.readFully(it) }
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun readU64(raf: RandomAccessFile): Long {
        val b = ByteArray(8).also { raf.readFully(it) }
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun readString(raf: RandomAccessFile): String {
        val len = readU64(raf)
        if (len < 0 || len > 16L * 1024 * 1024) throw RuntimeException("metadata string too long")
        val b = ByteArray(len.toInt()).also { raf.readFully(it) }
        return String(b, StandardCharsets.UTF_8)
    }

    private fun skipScalar(raf: RandomAccessFile, type: Long) {
        val size = when (type) {
            0L, 1L, 7L -> 1L
            2L, 3L -> 2L
            4L, 5L, 6L -> 4L
            10L, 11L, 12L -> 8L
            else -> throw RuntimeException("unknown kv value type $type")
        }
        raf.seek(raf.filePointer + size)
    }

    private fun skipArray(raf: RandomAccessFile, elemType: Long, count: Long) {
        if (elemType == 8L) {
            var i = 0L
            while (i < count) { readString(raf); i++ }
        } else {
            val size = when (elemType) {
                0L, 1L, 7L -> 1L
                2L, 3L -> 2L
                4L, 5L, 6L -> 4L
                10L, 11L, 12L -> 8L
                else -> throw RuntimeException("unknown array elem type $elemType")
            }
            raf.seek(raf.filePointer + size * count)
        }
    }
}
