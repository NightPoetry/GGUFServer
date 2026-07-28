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
        val winner = AspectBus.routeUntilTakeOver(listOf(a, b), 1_000) { p, _, _ ->
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
    fun `route 预算被前面的处理器吃光时 后面的仍能靠保底被问到`() = runBlocking {
        // 真机教训：App 刚起来主线程还在渲染，排第一的插件一次 WebView 往返就吃掉整个 route 预算，
        // 排在后面的插件永远轮不到 —— 「开 App 说的第一句话丢接管」就是这么来的。漏接管比多等
        // 几十毫秒贵得多，所以预算见底后还要逐个补问一遍。
        val a = manifest("a", hooks = listOf("route"))
        val b = manifest("b", hooks = listOf("route"))
        val seen = ArrayList<String>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a, b), 60) { p, remain, _ ->
            seen += p.id
            assertTrue("处理器拿到的应是剩余预算而不是总预算", remain <= AspectBus.COLD_ROUNDTRIP_MS)
            if (p.id == a.id) { delay(80); null } else takeOver()   // 第一个就把预算吃光
        }
        assertEquals(listOf(a.id, b.id), seen)   // b 靠保底被问到了
        assertEquals(b.id, winner?.id)
    }

    @Test
    fun `保底也用尽后 才真的不再叫醒`() = runBlocking {
        val a = manifest("a", hooks = listOf("route"))
        val b = manifest("b", hooks = listOf("route"))
        val seen = ArrayList<String>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a, b), 60) { p, _, _ ->
            seen += p.id
            delay(AspectBus.ROUTE_GRACE_MS + 100)   // 连保底期也吃穿
            null
        }
        assertEquals(listOf(a.id), seen)
        assertNull(winner)
    }

    // ---------- route 两段式判定（§6.2）：快判定拿不准 → 独立预算的深判 ----------

    @Test
    fun `快判定返回 deep 时 用独立预算再问一遍 且这一遍带 deep 标记`() = runBlocking {
        val a = manifest("a", hooks = listOf("route"))
        val calls = ArrayList<Pair<String, Boolean>>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a), 150, 3_000) { p, _, deep ->
            calls += p.id to deep
            if (deep) takeOver() else JSONObject().put("deep", true)
        }
        assertEquals(listOf(a.id to false, a.id to true), calls)
        assertEquals(a.id, winner?.id)
    }

    @Test
    fun `第一遍已经有人接管 就不再进深判`() = runBlocking {
        // 深判是实打实的一秒多（一次 llm.classify），已经判明白了就不该再花
        val a = manifest("a", hooks = listOf("route"))
        val b = manifest("b", hooks = listOf("route"))
        val calls = ArrayList<Pair<String, Boolean>>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a, b), 150, 3_000) { p, _, deep ->
            calls += p.id to deep
            if (p.id == a.id) JSONObject().put("deep", true) else takeOver()
        }
        assertEquals(listOf(a.id to false, b.id to false), calls)   // 没有任何 deep=true 的调用
        assertEquals(b.id, winner?.id)
    }

    @Test
    fun `没给深判预算时 deep 申请被忽略`() = runBlocking {
        val a = manifest("a", hooks = listOf("route"))
        val calls = ArrayList<Boolean>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a), 150) { _, _, deep ->
            calls += deep
            JSONObject().put("deep", true)
        }
        assertEquals(listOf(false), calls)
        assertNull(winner)
    }

    @Test
    fun `深判也吃预算 排在后面的申请者预算见底就不派`() = runBlocking {
        val a = manifest("a", hooks = listOf("route"))
        val b = manifest("b", hooks = listOf("route"))
        val deepSeen = ArrayList<String>()
        val winner = AspectBus.routeUntilTakeOver(listOf(a, b), 1_000, 60) { p, _, deep ->
            if (!deep) return@routeUntilTakeOver JSONObject().put("deep", true)
            deepSeen += p.id
            delay(80)      // 第一个深判就把深判预算吃光
            null
        }
        assertEquals(listOf(a.id), deepSeen)   // b 的深判根本没被叫醒
        assertNull(winner)
    }

    @Test
    fun `协程取消必须原样上抛 不能被当成插件故障吞掉`() {
        val a = manifest("a", hooks = listOf("route"))
        var propagated = false
        try {
            runBlocking {
                AspectBus.routeUntilTakeOver(listOf(a), 1_000) { _, _, _ -> throw CancellationException("请求断了") }
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

    // ---------- 「被饿死」≠「坏了」：串行面共享预算的下限（2026-07-27） ----------

    @Test
    fun `剩余预算低于下限时根本不派 免得把饿死记成它超时`() = runBlocking {
        val a = manifest("a", hooks = listOf("curate"))
        val b = manifest("b", hooks = listOf("curate"))
        val asked = ArrayList<String>()
        AspectBus.serialForEach(
            listOf(a, b), budgetMs = 150,
            invoke = { p, _ ->
                asked += p.id
                delay(140)          // 第一个几乎吃光预算，b 只剩十几毫秒
                JSONObject()
            },
            onSkipped = {},
        ) { _, _ -> }
        assertEquals("b 只剩零头，派下去必然超时、还要被记一次熔断，所以根本不该派", listOf(a.id), asked)
    }

    @Test
    fun `没机会表态的处理器按弃权处理 它的工具不该被藏起来`() = runBlocking {
        val notes = manifest("notes", hooks = listOf("curate"), tools = listOf("notes.append"))
        val tts = manifest("tts", hooks = listOf("curate"), tools = listOf("tts.say"))
        // notes 吃光预算（连保底期一起）并表态「我这边啥都别露」；tts 排在后面，压根没被问到。
        val e = AspectBus.curateExposure(listOf(notes, tts), emptyList(), 150) { p, _ ->
            if (p.id == notes.id) { delay(400); JSONObject().put("expose", JSONArray()) } else JSONObject()
        }
        assertEquals("没被问到 ≠ 表态失败：tts 的工具应按弃权豁免保留", setOf("tts.say"), e)
    }

    @Test
    fun `派下去了但自己抛异常的 仍按纪律 3 藏它的工具`() = runBlocking {
        val a = manifest("a", hooks = listOf("curate"), tools = listOf("a.do"))
        val b = manifest("b", hooks = listOf("curate"), tools = listOf("b.do"))
        val e = AspectBus.curateExposure(listOf(a, b), emptyList(), 5_000) { p, _ ->
            if (p.id == a.id) JSONObject().put("expose", JSONArray()) else throw RuntimeException("插件炸了")
        }
        // b 真的被派了、是它自己坏的 → 教了也白教（它的 tool 钩子同样跑不了），维持原语义
        assertEquals(emptySet<String>(), e)
    }

    // ---------- 「没准备好」≠「坏了」：service WebView 冷启窗口（2026-07-27） ----------

    @Test
    fun `登记了 curate 但 service 未就绪时 按弃权处理 工具不该被藏起来`() {
        val notes = manifest("notes", hooks = listOf("curate"), tools = listOf("notes.append"))
        val tts = manifest("tts", hooks = listOf("curate"), tools = listOf("tts.say"))
        val active = listOf(notes, tts)

        // tts 的 service.js 还没加载完（App 冷启头几秒）
        val reg = AspectRegistry(active, hasNativeTools = false, constrained = false) { it.id == notes.id }

        assertEquals("只分发给就绪的处理器", listOf(notes.id), reg.curate.map { it.id })
        assertTrue("未就绪者进弃权集：等模型吐标记时它早就绪了，tool 钩子跑得动", reg.curateAbstained.any { it.id == tts.id })

        val e = runBlocking {
            AspectBus.curateExposure(reg.curate, reg.curateAbstained, 1_000) { _, _ ->
                JSONObject().put("expose", JSONArray())      // notes 表态「我这边啥都别露」
            }
        }
        assertEquals("tts 冷启中不该被 notes 的收窄连累掉工具", setOf("tts.say"), e)
    }

    @Test
    fun `教学注入位的判据是登记 不随本轮谁在线变`() {
        val notes = manifest("notes", hooks = listOf("curate"), tools = listOf("notes.append"))
        val active = listOf(notes)

        val cold = AspectRegistry(active, hasNativeTools = false, constrained = false) { false }
        val warm = AspectRegistry(active, hasNativeTools = false, constrained = false) { true }

        // 位置只能看 curateRegistered：它俩必须一致，否则 head 的字节会随冷热抖动（§10 红线 5）
        assertEquals(1, cold.curateRegistered.size)
        assertEquals(cold.curateRegistered.size, warm.curateRegistered.size)
        assertTrue("冷启轮没有可分发的处理器，但登记事实不变", cold.curate.isEmpty() && warm.curate.isNotEmpty())
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
