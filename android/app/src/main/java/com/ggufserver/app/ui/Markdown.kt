package com.ggufserver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 轻量 Markdown 渲染（零依赖，样式贴合暖色系）。
 * 覆盖大模型常用输出：# 标题、**粗**、*斜*、`行内代码`、``` 代码块、- / 1. 列表、
 * > 引用、--- 分隔线、| 表格 |、[链接](url)。
 * 流式时把光标 ▍ 追加进原文一起渲染，天然出现在最后一个块的末尾。
 */
@Composable
fun MarkdownMessage(md: String, streaming: Boolean) {
    val cursorAlpha = if (streaming) blinkAlphaMd() else null
    val source = fixLiteralNewlines(if (streaming) md + CURSOR else md)
    val blocks = remember(source) { parseBlocks(source) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b -> RenderBlock(b, cursorAlpha) }
    }
}

/**
 * 小模型偶尔把换行写成字面的 "\n"（反斜杠 + n 两个字符），显示出来很难看。
 * 渲染前在非代码区把它还原成真换行；围栏代码块里的保持原样——
 * printf("\n") 这类是合法代码，不能动。只改渲染，不改存储的原文。
 */
private fun fixLiteralNewlines(src: String): String {
    if (!src.contains("\\n")) return src
    val out = StringBuilder(src.length)
    var inFence = false
    var first = true
    for (line in src.lineSequence()) {
        if (!first) out.append('\n')
        first = false
        when {
            line.trimStart().startsWith("```") -> { inFence = !inFence; out.append(line) }
            inFence -> out.append(line)
            else -> out.append(line.replace("\\n", "\n"))
        }
    }
    return out.toString()
}

private const val CURSOR = "▍"

/**
 * 1s 硬闪（前半秒实、后半秒隐）。
 * 用 500ms 翻转一次的普通状态驱动，而不是无限动画——无限动画的值在组合期被读取，
 * 会让整条消息每帧（60/120fps）重组，长回复流式输出时白白掉帧。
 */
@Composable
private fun blinkAlphaMd(): Float {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) { delay(500); on = !on }
    }
    return if (on) 1f else 0f
}

// ---------- 块级解析 ----------

private sealed interface MdBlock
private data class Para(val text: String) : MdBlock
private data class Heading(val level: Int, val text: String) : MdBlock
private data class CodeBlock(val code: String) : MdBlock
private data class Quote(val text: String) : MdBlock
private data class ListBlock(val items: List<Pair<String, String>>) : MdBlock   // 标号 → 内容
private data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
private data object Hr : MdBlock

private val HEADING_RE = Regex("^#{1,6}\\s+")
private val HR_RE = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
private val LIST_RE = Regex("^([-*+]|\\d{1,3}[.、])\\s+(.*)$")
private val TABLE_SEP_RE = Regex("^\\|?[\\s:|-]+\\|?$")

private fun splitRow(s: String): List<String> =
    s.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private fun parseBlocks(md: String): List<MdBlock> {
    val lines = md.lines()
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) out += Para(para.toString().trim())
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trimStart()
        when {
            t.startsWith("```") -> {
                flushPara()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buf.append(lines[i]).append('\n'); i++
                }
                if (i < lines.size) i++   // 跳过闭合围栏；流式中未闭合则吃到结尾
                out += CodeBlock(buf.toString().trimEnd('\n'))
                continue
            }
            HEADING_RE.containsMatchIn(t) -> {
                flushPara()
                val level = t.takeWhile { it == '#' }.length
                out += Heading(level, t.dropWhile { it == '#' }.trim())
            }
            HR_RE.matches(t) -> { flushPara(); out += Hr }
            t.startsWith(">") -> {
                flushPara()
                val buf = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    buf += lines[i].trimStart().removePrefix(">").trim(); i++
                }
                out += Quote(buf.joinToString("\n"))
                continue
            }
            LIST_RE.matches(t) -> {
                flushPara()
                val items = ArrayList<Pair<String, String>>()
                var num = 0
                while (i < lines.size) {
                    val lt = lines[i].trimStart()
                    val m = LIST_RE.matchEntire(lt)
                    if (m != null) {
                        val marker = m.groupValues[1]
                        val label = if (marker.first().isDigit()) { num = marker.dropLast(1).toIntOrNull() ?: (num + 1); "$num." } else "•"
                        items += label to m.groupValues[2]
                        i++
                    } else if (lt.isNotBlank() && lines[i].startsWith("  ") && items.isNotEmpty()) {
                        // 缩进续行并入上一项
                        val last = items.removeAt(items.size - 1)
                        items += last.first to (last.second + "\n" + lt)
                        i++
                    } else break
                }
                out += ListBlock(items)
                continue
            }
            t.startsWith("|") && i + 1 < lines.size &&
                lines[i + 1].trim().let { TABLE_SEP_RE.matches(it) && it.contains('-') } -> {
                flushPara()
                val header = splitRow(t)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += splitRow(lines[i]); i++
                }
                out += Table(header, rows)
                continue
            }
            t.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(t)
            }
        }
        i++
    }
    flushPara()
    return out
}

// ---------- 行内解析 ----------

private val LINK_RE = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)")

private fun buildInline(s: String, tones: WarmTones, cursorAlpha: Float?): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '▍' -> {
                    withStyle(SpanStyle(color = tones.accent.copy(alpha = cursorAlpha ?: 1f))) { append('▍') }
                    i++
                }
                s.startsWith("`", i) -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                                background = tones.side, color = tones.ink
                            )
                        ) { append(s.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(s[i]); i++ }
                }
                s.startsWith("**", i) -> {
                    val end = s.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(buildInline(s.substring(i + 2, end), tones, cursorAlpha))
                        }
                        i = end + 2
                    } else { append(s[i]); i++ }
                }
                s.startsWith("~~", i) -> {
                    val end = s.indexOf("~~", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(s.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(s[i]); i++ }
                }
                s[i] == '*' && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                    val end = s.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(s.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(s[i]); i++ }
                }
                s[i] == '[' -> {
                    val m = LINK_RE.find(s.substring(i))
                    if (m != null) {
                        withLink(
                            LinkAnnotation.Url(
                                m.groupValues[2],
                                TextLinkStyles(SpanStyle(color = tones.accent, textDecoration = TextDecoration.Underline))
                            )
                        ) { append(m.groupValues[1]) }
                        i += m.value.length
                    } else { append(s[i]); i++ }
                }
                else -> { append(s[i]); i++ }
            }
        }
    }

// ---------- 块级渲染 ----------

@Composable
private fun RenderBlock(b: MdBlock, cursorAlpha: Float?) {
    val tones = LocalWarmTones.current
    when (b) {
        is Para -> Text(
            buildInline(b.text, tones, cursorAlpha),
            fontSize = 15.sp, lineHeight = 25.sp, color = tones.ink
        )
        is Heading -> Text(
            buildInline(b.text, tones, cursorAlpha),
            fontSize = when (b.level) { 1 -> 18.5.sp; 2 -> 17.sp; else -> 16.sp },
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = tones.ink,
            modifier = Modifier.padding(top = 4.dp)
        )
        is CodeBlock -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2D2A26))
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                b.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp, lineHeight = 18.sp,
                color = Color(0xFFF0EEE6),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        is Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(tones.line2, RoundedCornerShape(2.dp))
            )
            Text(
                buildInline(b.text, tones, cursorAlpha),
                fontSize = 14.sp, lineHeight = 23.sp, color = tones.muted,
                modifier = Modifier.padding(start = 9.dp)
            )
        }
        is ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            b.items.forEach { (label, text) ->
                Row {
                    Text(
                        label,
                        fontSize = 14.sp, lineHeight = 24.sp, color = tones.faint,
                        modifier = Modifier.padding(end = 7.dp)
                    )
                    Text(
                        buildInline(text, tones, cursorAlpha),
                        fontSize = 15.sp, lineHeight = 24.sp, color = tones.ink
                    )
                }
            }
        }
        is Table -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, tones.line, RoundedCornerShape(8.dp))
        ) {
            Row(Modifier.fillMaxWidth().background(tones.side)) {
                b.header.forEach { cell ->
                    Text(
                        buildInline(cell, tones, null),
                        fontSize = 13.sp, lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold, color = tones.ink2,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            b.rows.forEach { row ->
                HorizontalDivider(color = tones.line)
                Row(Modifier.fillMaxWidth()) {
                    repeat(b.header.size) { c ->
                        Text(
                            buildInline(row.getOrElse(c) { "" }, tones, null),
                            fontSize = 13.sp, lineHeight = 19.sp, color = tones.ink,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Hr -> HorizontalDivider(color = tones.line, modifier = Modifier.padding(vertical = 2.dp))
    }
}
