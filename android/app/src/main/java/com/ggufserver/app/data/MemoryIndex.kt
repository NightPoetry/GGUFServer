package com.ggufserver.app.data

/**
 * 宿主侧仅保留 token 粗估——BM25-lite 检索本体已迁到记忆插件的 service.js
 * （《工具插件规范》§11：回忆检索由插件做，宿主只负责窗口预算等事实性工作）。
 * Gateway 用它估算窗口预算里各消息/背景块占用的 token 数。
 */
object MemoryIndex {

    private fun isCjk(c: Char) = c.code in 0x4E00..0x9FFF

    /**
     * 粗略 token 估算（无 tokenizer，可控偏保守）：
     * CJK ≈ 1 token/字，其余字符 ≈ 1 token/3.5 字符。
     */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) if (isCjk(c)) cjk++ else other++
        return cjk + (other / 3.5f).toInt() + 1
    }
}
