package com.ggufserver.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式工具标记扫描器（§6.9）的纯逻辑单测。
 *
 * 为什么写它：真机验收（2026-07-21）里「吞标记」这条走不到——1B 不肯自发吐 `<tool>`，
 * 便签走的是 route+handle 的确定性路径。而这段代码恰恰是**流式回合零标记泄漏**的唯一防线：
 * 一旦它错了，用户会在对话里看见半截 `<tool name=...`。真机测不到的路径，就在 JVM 上钉死。
 *
 * 覆盖：无标记全转发 / 整块标记 / 逐字符跨 chunk / 未声明标签名放行 / 4KB 证伪 / finish 尾部。
 */
class ToolTagScannerTest {

    /** 与 ToolRuntime.makeScanner 同款正则（只认已声明的工具名）。 */
    private fun scanner(vararg names: String) = ToolTagScanner(
        Regex("<tool\\s+name=\"(${names.joinToString("|") { Regex.escape(it) }})\"\\s*>([\\s\\S]*?)</tool>")
    )

    /** 把整段文本按 [chunk] 大小切片喂进扫描器，返回 (转发出去的全文, 认出的调用)。 */
    private fun feedAll(s: ToolTagScanner, text: String, chunk: Int): Pair<String, List<Pair<String, String>>> {
        val forwarded = StringBuilder()
        val calls = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < text.length) {
            val piece = text.substring(i, minOf(i + chunk, text.length))
            i += chunk
            when (val o = s.feed(piece)) {
                is ToolTagScanner.Outcome.Forward -> forwarded.append(o.text)
                is ToolTagScanner.Outcome.Hold -> {}
                is ToolTagScanner.Outcome.Resolved -> {
                    forwarded.append(o.forwardText)
                    calls += (o.toolName ?: "") to (o.toolInput ?: "")
                }
            }
        }
        val tail = s.finish()
        forwarded.append(tail.leftoverText)
        if (tail.toolName != null) calls += tail.toolName!! to (tail.toolInput ?: "")
        return forwarded.toString() to calls
    }

    @Test
    fun `没有标记时逐字节原样转发`() {
        val text = "好的，我记住了。要不要我顺便提醒你一下？"
        val (out, calls) = feedAll(scanner("notes.append"), text, chunk = 3)
        assertEquals(text, out)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `完整标记在同一个分片里被吞掉且认出调用`() {
        val s = scanner("notes.append")
        val o = s.feed("知道了。<tool name=\"notes.append\">周五交房租</tool>")
        assertTrue(o is ToolTagScanner.Outcome.Resolved)
        val r = o as ToolTagScanner.Outcome.Resolved
        assertEquals("知道了。", r.forwardText)
        assertEquals("notes.append", r.toolName)
        assertEquals("周五交房租", r.toolInput)
    }

    @Test
    fun `标记逐字符跨分片到达时零泄漏（真机没触发到的路径）`() {
        val text = "好的。<tool name=\"notes.append\">周五交房租</tool>"
        val (out, calls) = feedAll(scanner("notes.append"), text, chunk = 1)
        assertEquals("好的。", out)                       // 标记一个字符都没漏出去
        assertEquals(1, calls.size)
        assertEquals("notes.append" to "周五交房租", calls[0])
    }

    @Test
    fun `标记前后都有正文时只吞标记本身`() {
        val text = "前面这句要留下。<tool name=\"tts.say\">你好呀</tool>后面这句也要留下。"
        val (out, calls) = feedAll(scanner("tts.say"), text, chunk = 2)
        assertEquals("前面这句要留下。后面这句也要留下。", out)
        assertEquals("tts.say" to "你好呀", calls[0])
    }

    @Test
    fun `未声明的工具名不算标记 尾部原样放行`() {
        // curate 收窄后没被暴露、或第三方乱编的名字：不能吞，必须原样交回给用户
        val text = "看这个：<tool name=\"evil.rm\">rm -rf</tool>"
        val (out, calls) = feedAll(scanner("notes.append"), text, chunk = 5)
        assertEquals(text, out)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `疑似标记超过4KB暂扣上限即证伪放行`() {
        val junk = "<tool name=\"notes.append\"" + "x".repeat(5000)   // 永远等不到闭合
        val (out, calls) = feedAll(scanner("notes.append"), junk, chunk = 512)
        assertEquals(junk, out)      // 一个字符都没吃掉
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `闭合符落在最后一个分片时由该片认出 finish 不再重复`() {
        val s = scanner("notes.append")
        val mid = s.feed("记一下：<tool name=\"notes.append\">买牛奶</tool")
        assertEquals("记一下：", (mid as ToolTagScanner.Outcome.Forward).text)  // 标记前的正文先走
        val last = s.feed(">")
        assertTrue(last is ToolTagScanner.Outcome.Resolved)
        assertEquals("买牛奶", (last as ToolTagScanner.Outcome.Resolved).toolInput)
        val tail = s.finish()
        assertNull(tail.toolName)          // 已经认过，不能再触发一次工具执行
        assertEquals("", tail.leftoverText)
    }

    @Test
    fun `流结束时半截标记原样吐出 不静默丢字`() {
        val s = scanner("notes.append")
        s.feed("在写：<tool name=\"notes.")
        val tail = s.finish()
        assertNull(tail.toolName)
        assertEquals("<tool name=\"notes.", tail.leftoverText)   // 暂扣的部分必须还回来
    }
}
