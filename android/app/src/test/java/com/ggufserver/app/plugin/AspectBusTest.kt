package com.ggufserver.app.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切面微内核分发组合子（§13.1/§13.3/§13.4）的纯逻辑单测。
 *
 * 为什么写它：真机验收（2026-07-21）里 fail-open 只标了「结构性已证、未现场注错」——
 * 现场往插件 JS 里塞异常太麻烦。而 fail-open 是**分发面的自带属性**，谁也不许破：一个插件炸了
 * 或超预算，用户的那句话必须照样有回复。这里把它变成每次编译都跑的断言。
 *
 * 覆盖：处理器抛异常/超预算不阻断管线 · 协程取消不被吞 · curate 并集/表态/弃权豁免语义。
 */
class AspectBusTest {

    // ---------- 造 manifest（走真实 parse，顺带保证测试用的 manifest 合法） ----------

    private fun manifest(shortId: String, hooks: List<String> = emptyList(), tools: List<String> = emptyList()): PluginManifest {
        val perms = JSONArray(
            buildList {
                add("chat:annotate")
                if ("route" in hooks) add("chat:route")
                if ("curate" in hooks || "context" in hooks) add("chat:context")
                if ("params" in hooks) add("chat:params")
            }
        )
        val root = JSONObject()
            .put("id", "com.test.$shortId")
            .put("name", shortId)
            .put("version", "1.0.0")
            .put("apiVersion", 1)
            .put("entry", JSONObject().put("ui", "ui.html").put("service", "service.js"))
            .put("permissions", perms)
            .put("hooks", JSONArray(hooks))
            .put("tools", JSONArray(tools.map { JSONObject().put("name", it).put("description", "d").put("input", "text") }))
        return PluginManifest.parse(root).getOrThrow()
    }

    private fun takeOver() = JSONObject().put("takeOver", true)

    // ---------- fail-open ----------

    @Test
    fun `route 面某个处理器抛异常 后面的处理器照常轮到`() = runBlocking {
        val a = manifest("a", hooks = listOf("route"))
        val b = manifest("b", hooks = listOf("route"))
        val seen = ArrayList<String>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a, b), 1_000) { p, _ ->
            seen += p.id
            if (p.id == a.id) throw IllegalStateException("插件炸了")
            takeOver()
        }
        assertEquals(listOf(a.id, b.id), seen)   // 没有在第一个身上卡死
        assertEquals(b.id, winner?.id)
    }

    @Test
    fun `context 面并行分发 一个处理器抛异常只让它自己为空`() = runBlocking {
        val a = manifest("a", hooks = listOf("context"))
        val b = manifest("b", hooks = listOf("context"))
        val results = AspectBus.parallel(listOf(a, b), 1_000) { p, _ ->
            if (p.id == a.id) throw RuntimeException("WebView 挂了")
            JSONObject().put("head", "ok")
        }
        assertEquals(2, results.size)
        assertNull(results.first { it.first.id == a.id }.second)                    // 炸的那个 = 无返回
        assertEquals("ok", results.first { it.first.id == b.id }.second?.optString("head"))
    }

    @Test
    fun `params 面消费结果时抛异常不牵连后面的处理器`() = runBlocking {
        val a = manifest("a", hooks = listOf("params"))
        val b = manifest("b", hooks = listOf("params"))
        val consumed = ArrayList<String>()
        AspectBus.serialForEach(
            listOf(a, b), 1_000,
            invoke = { _, _ -> JSONObject().put("params", JSONObject().put("temperature", 0.7)) },
        ) { p, _ ->
            consumed += p.id
            if (p.id == a.id) throw IllegalArgumentException("合并时算错了")
        }
        assertEquals(listOf(a.id, b.id), consumed)
    }

    @Test
    fun `预算耗尽后不再叫醒剩下的处理器`() = runBlocking {
        val a = manifest("a", hooks = listOf("route"))
        val b = manifest("b", hooks = listOf("route"))
        val seen = ArrayList<String>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a, b), 60) { p, remain ->
            seen += p.id
            assertTrue("处理器拿到的应是剩余预算而不是总预算", remain <= 60)
            delay(80)          // 第一个就把预算吃光
            null
        }
        assertEquals(listOf(a.id), seen)   // b 根本没被叫醒
        assertNull(winner)
    }

    @Test
    fun `协程取消必须原样上抛 不能被当成插件故障吞掉`() {
        val a = manifest("a", hooks = listOf("route"))
        var propagated = false
        try {
            runBlocking {
                AspectBus.routeUntilTakeOver(listOf(a), 1_000) { _, _ -> throw CancellationException("请求断了") }
            }
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("取消是调用方不要结果了，吞掉会让结构化并发失效", propagated)
    }

    // ---------- curate 语义（§13.4） ----------

    private suspend fun exposure(
        handlers: List<PluginManifest>,
        abstained: List<PluginManifest>,
        reply: (PluginManifest) -> JSONObject?,
    ) = AspectBus.curateExposure(handlers, abstained, 1_000) { p, _ -> reply(p) }

    @Test
    fun `没有任何 curate 处理器就是全露`() = runBlocking {
        val notes = manifest("notes", tools = listOf("notes.append"))
        assertNull(exposure(emptyList(), listOf(notes)) { null })
    }

    @Test
    fun `处理器全都没表态也算全露`() = runBlocking {
        val a = manifest("a", hooks = listOf("curate"))
        assertNull(exposure(listOf(a), emptyList()) { JSONObject() })          // 返回了但没有 expose 字段
        assertNull(exposure(listOf(a), emptyList()) { null })                  // 超时/异常 → 无返回
    }

    @Test
    fun `表态空数组等于本轮我这边啥都别露`() = runBlocking {
        val a = manifest("a", hooks = listOf("curate"), tools = listOf("a.do"))
        val e = exposure(listOf(a), emptyList()) { JSONObject().put("expose", JSONArray()) }
        assertEquals(emptySet<String>(), e)      // 非 null：表态了，且结果是「一个都不露」
    }

    @Test
    fun `多个处理器的 expose 取并集`() = runBlocking {
        val a = manifest("a", hooks = listOf("curate"), tools = listOf("a.do"))
        val b = manifest("b", hooks = listOf("curate"), tools = listOf("b.do"))
        val e = exposure(listOf(a, b), emptyList()) { p ->
            JSONObject().put("expose", JSONArray(listOf(if (p.id == a.id) "a.do" else "b.do")))
        }
        assertEquals(setOf("a.do", "b.do"), e)
    }

    @Test
    fun `弃权插件的工具不因别人的收窄而消失`() = runBlocking {
        val a = manifest("a", hooks = listOf("curate"), tools = listOf("a.do"))
        val third = manifest("third", tools = listOf("third.run"))   // 第三方插件：压根没登记 curate
        val e = exposure(listOf(a), listOf(third)) { JSONObject().put("expose", JSONArray()) }
        assertEquals(setOf("third.run"), e)   // a 自己收了，但不许替 third 做决定
    }

    // ---------- 收窄真的落到教学条款上 ----------

    @Test
    fun `教学条款按 exposure 逐条收窄 全收窄则整段不注入`() {
        val notes = manifest("notes", tools = listOf("notes.append"))
        val tts = manifest("tts", tools = listOf("tts.say"))
        val all = listOf(notes, tts)

        val full = ToolRuntime.teachingClause(all, null)!!
        assertTrue(full.contains("notes.append") && full.contains("tts.say"))

        val narrowed = ToolRuntime.teachingClause(all, setOf("notes.append"))!!
        assertTrue(narrowed.contains("notes.append"))
        assertTrue("被收窄掉的工具不该出现在 system 里", !narrowed.contains("tts.say"))

        assertNull("一个都不露时整段教学不注入", ToolRuntime.teachingClause(all, emptySet()))
    }
}
