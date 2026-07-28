package com.ggufserver.app.plugin

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推理能力点（§6.16）「回落方向永远朝本机」这条纪律的单测。
 *
 * 为什么写它：2026-07-28 真机上撞到——外援插件按规范用 `{}` 表示「我不接」，而宿主的 llmRoute
 * 只判 `null`，于是把这个空壳当成了答案交给调用方。表现是乐句插件答「这次没写出能用的片段」，
 * 本机模型压根没被问过：**打开一个保底插件，反而把原有能力弄丢了**，正是保底最不该有的失败方向。
 *
 * 这条纪律靠肉眼看不住（`{}` 与 `{content:"…"}` 在 Kotlin 里都是非 null 的 JSONObject），
 * 所以钉成编译期就跑的断言。
 */
class LlmProviderRouteTest {

    private fun tookOver(action: String, json: String) =
        PluginBridge.llmProviderTookOver(action, JSONObject(json))

    // ---------- 主动不接：一律回落本机 ----------

    @Test
    fun `chat 返回空对象 等于没接管`() {
        assertFalse(tookOver("chat", "{}"))
    }

    @Test
    fun `classify 返回空对象 等于没接管`() {
        assertFalse(tookOver("classify", "{}"))
    }

    @Test
    fun `chat 回了别的字段却没有 content 等于没接管`() {
        // 提供者报了统计/状态但没给答案：调用方要的是 content，没有就该去问本机
        assertFalse(tookOver("chat", """{"ok":1,"note":"skipped"}"""))
    }

    @Test
    fun `classify 认不出标签时给的空 label 等于没接管`() {
        assertFalse(tookOver("classify", """{"label":""}"""))
        assertFalse(tookOver("classify", """{"label":"   "}"""))
    }

    @Test
    fun `chat 给了空 content 等于没接管`() {
        // 外部端点回了个空答案：交给调用方也没用，回落本机至少还有一次机会
        assertFalse(tookOver("chat", """{"content":""}"""))
    }

    // ---------- 真接了：结果原样用，不许再去打本机 ----------

    @Test
    fun `chat 给了 content 就是接管`() {
        assertTrue(tookOver("chat", """{"content":"{\"patA\":\"C4 E4\"}"}"""))
    }

    @Test
    fun `classify 给了 label 就是接管`() {
        assertTrue(tookOver("classify", """{"label":"旋律"}"""))
    }

    // ---------- 将来新增的推理动作 ----------

    @Test
    fun `未知动作 有内容算接管 空对象算没接`() {
        assertTrue(tookOver("summarize", """{"content":"…"}"""))
        assertFalse(tookOver("summarize", "{}"))
    }
}
