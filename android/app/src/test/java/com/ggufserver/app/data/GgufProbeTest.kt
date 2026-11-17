package com.ggufserver.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile

/** GGUF 头探测：类型自动识别（向量/语言）的字节级钉子。 */
class GgufProbeTest {

    private fun bytes(vararg chunks: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (c in chunks) for (b in c) out.add(b)
        return out.toByteArray()
    }
    private fun u64(v: Long): ByteArray = ByteArray(8).also { b ->
        for (i in 0 until 8) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
    private fun u32(v: Long): ByteArray = ByteArray(4).also { b ->
        for (i in 0 until 4) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
    private fun str(s: String): ByteArray = bytes(u64(s.length.toLong()), s.toByteArray(Charsets.UTF_8))
    private fun kvString(key: String, value: String): ByteArray = bytes(str(key), u32(8), str(value))
    private fun kvU32(key: String, value: Long): ByteArray = bytes(str(key), u32(4), u32(value))
    private fun gguf(kvs: List<ByteArray>): ByteArray =
        bytes("GGUF".toByteArray(Charsets.US_ASCII), u32(3), u64(0), u64(kvs.size.toLong()), *kvs.toTypedArray())

    private fun probe(bytes: ByteArray): GgufProbe.GgufMeta {
        val f = java.io.File.createTempFile("probe", ".gguf")
        f.writeBytes(bytes)
        return java.io.RandomAccessFile(f, "r").use { raf -> GgufProbe.parseStream(raf) }
    }

    @Test
    fun `bert架构加pooling类型识别为向量模型`() {
        val bytes = gguf(listOf(
            kvString("general.architecture", "nomic-bert"),
            kvU32("nomic-bert.pooling_type", 2),   // CLS
        ))
        val meta = probe(bytes)
        assertTrue(meta.ok)
        assertEquals("nomic-bert", meta.architecture)
        assertEquals(2, meta.poolingType)
        assertEquals(GgufProbe.Kind.EMBEDDING, GgufProbe.kindOf(meta))
    }

    @Test
    fun `qwen2架构带pooling也识别为向量模型`() {
        // Qwen3-Embedding 用 qwen2 架构——判据是 pooling_type 非 NONE，不是架构名
        val bytes = gguf(listOf(
            kvString("general.architecture", "qwen2"),
            kvU32("qwen2.pooling_type", 1),        // MEAN
        ))
        assertEquals(GgufProbe.Kind.EMBEDDING, GgufProbe.kindOf(probe(bytes)))
    }

    @Test
    fun `qwen2无pooling识别为语言模型`() {
        val bytes = gguf(listOf(
            kvString("general.architecture", "qwen2"),
        ))
        val meta = probe(bytes)
        assertTrue(meta.ok)
        assertEquals(GgufProbe.Kind.LLM, GgufProbe.kindOf(meta))
    }

    @Test
    fun `非GGUF文件返回UNKNOWN`() {
        val meta = probe("not a gguf".toByteArray())
        assertEquals(GgufProbe.Kind.UNKNOWN, GgufProbe.kindOf(meta))
    }
}
