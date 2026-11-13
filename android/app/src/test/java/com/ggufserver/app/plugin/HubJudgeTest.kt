package com.ggufserver.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「带得动」判定与量化识别的语义钉子（模型市场，设计文档04 §3）。
 * 阈值：ok = size ≤ 0.75×RAM 且 ≤ 0.9×磁盘；tight = ≤ RAM 且 ≤ 磁盘；否则 no。
 * 改阈值必须先改这里——这些数字就是「浏览页要不要隐藏一条模型」的裁决依据。
 */
class HubJudgeTest {

    private val ram8g = 8L * 1024 * 1024 * 1024
    private val diskBig = 100L * 1024 * 1024 * 1024

    // ---- 量化段识别 ----

    @Test
    fun `识别常见量化命名`() {
        assertEquals("q4_k_xl", HubJudge.parseQuant("MiniCPM5-2B-Q4_K_XL.gguf"))
        assertEquals("q4_k_m", HubJudge.parseQuant("model.Q4_K_M.gguf"))
        assertEquals("q8_0", HubJudge.parseQuant("Qwen3-4B-Q8_0.gguf"))
        assertEquals("iq3_xxs", HubJudge.parseQuant("Tiny-iq3_xxs.gguf"))
        assertEquals("f16", HubJudge.parseQuant("mmproj-model-f16.gguf"))
        assertEquals("bf16", HubJudge.parseQuant("Model-BF16.gguf"))
    }

    @Test
    fun `非量化后缀返回null`() {
        assertNull(HubJudge.parseQuant("model-cyber.gguf"))
        assertNull(HubJudge.parseQuant("some-model.gguf"))
        assertNull(HubJudge.parseQuant("plain.gguf"))
    }

    // ---- 阈值判定 ----

    @Test
    fun `舒适档_75pct内存以内`() {
        val f4 = 4L * 1024 * 1024 * 1024 // 4GB ≤ 6GB(0.75×8G)
        assertEquals("ok", HubJudge.verdictOf(f4, ram8g, diskBig))
    }

    @Test
    fun `勉强档_内存以内但超舒适线`() {
        val f7 = 7L * 1024 * 1024 * 1024 // 7GB > 6GB 且 ≤ 8GB
        assertEquals("tight", HubJudge.verdictOf(f7, ram8g, diskBig))
    }

    @Test
    fun `带不动_超总内存`() {
        val f9 = 9L * 1024 * 1024 * 1024
        assertEquals("no", HubJudge.verdictOf(f9, ram8g, diskBig))
    }

    @Test
    fun `磁盘不够也不能算舒适`() {
        val diskSmall = 4L * 1024 * 1024 * 1024
        val f4 = 4L * 1024 * 1024 * 1024 // 内存舒适（4≤6），但 4GB > 0.9×4GB=3.6GB
        assertEquals("tight", HubJudge.verdictOf(f4, ram8g, diskSmall))
    }

    @Test
    fun `异常输入一律no`() {
        assertEquals("no", HubJudge.verdictOf(1024L, 0L, diskBig))          // RAM 未知
        assertEquals("no", HubJudge.verdictOf(0L, ram8g, diskBig))         // 大小未知
    }

    // ---- 组合判定与最佳量化挑选 ----

    @Test
    fun `best挑最小的舒适档`() {
        val files = listOf(
            "m-Q6_K.gguf" to 7L * 1024 * 1024 * 1024,  // tight（>6GB ≤8GB）
            "m-Q4_K_M.gguf" to 2L * 1024 * 1024 * 1024, // ok
            "m-Q8_0.gguf" to 9L * 1024 * 1024 * 1024,   // no
        )
        val j = HubJudge.judge(files, ram8g, diskBig)
        assertTrue(j.runnable)
        assertEquals("m-Q4_K_M.gguf", j.bestName)
        assertEquals("ok", j.files.first { it.name == "m-Q4_K_M.gguf" }.verdict)
        // 排序：轻→重
        assertEquals(listOf("m-Q4_K_M.gguf", "m-Q6_K.gguf", "m-Q8_0.gguf"), j.files.map { it.name })
    }

    @Test
    fun `全是带不动时runnable为false且无best`() {
        val files = listOf(
            "big-Q4_K_M.gguf" to 10L * 1024 * 1024 * 1024,
            "bigger-Q8_0.gguf" to 16L * 1024 * 1024 * 1024,
        )
        val j = HubJudge.judge(files, ram8g, diskBig)
        assertFalse(j.runnable)
        assertNull(j.bestName)
        assertTrue(j.files.all { it.verdict == "no" })
    }

    @Test
    fun `没有舒适档但能勉强时取最小勉强`() {
        val files = listOf(
            "m-Q6_K.gguf" to 7L * 1024 * 1024 * 1024,   // tight
            "m-Q8_0.gguf" to 9L * 1024 * 1024 * 1024,   // no
        )
        val j = HubJudge.judge(files, ram8g, diskBig)
        assertTrue(j.runnable)
        assertEquals("m-Q6_K.gguf", j.bestName)
        assertEquals("tight", j.files.first { it.name == "m-Q6_K.gguf" }.verdict)
    }

    @Test
    fun `认不出的量化按中等位宽参与排序`() {
        // 自定义后缀（如 -cyber）位宽未知，按 6.0 排在 q4(≈5) 之后 q6_k(6.6) 之前
        val files = listOf(
            "m-cyber.gguf" to 1L * 1024 * 1024 * 1024,
            "m-Q4_K_M.gguf" to 1L * 1024 * 1024 * 1024,
            "m-Q6_K.gguf" to 1L * 1024 * 1024 * 1024,
        )
        val j = HubJudge.judge(files, ram8g, diskBig)
        assertEquals(listOf("m-Q4_K_M.gguf", "m-cyber.gguf", "m-Q6_K.gguf"), j.files.map { it.name })
    }

    @Test
    fun `toJson形状完整`() {
        val j = HubJudge.judge(listOf("m-Q4_K_M.gguf" to 1024L), ram8g, diskBig)
        val o = j.toJson()
        assertTrue(o.has("files") && o.has("bestName") && o.has("runnable"))
        assertEquals(1, o.getJSONArray("files").length())
        assertTrue(o.getBoolean("runnable"))
    }
}
