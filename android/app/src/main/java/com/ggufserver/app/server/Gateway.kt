package com.ggufserver.app.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.ggufserver.app.ServerBus
import com.ggufserver.app.data.MemoryIndex
import com.ggufserver.app.plugin.AspectBus
import com.ggufserver.app.plugin.AspectRegistry
import com.ggufserver.app.plugin.Aspects
import com.ggufserver.app.plugin.PluginManifest
import com.ggufserver.app.plugin.PluginRuntime
import com.ggufserver.app.plugin.PluginStore
import com.ggufserver.app.plugin.Runtimes
import com.ggufserver.app.plugin.ToolCall
import com.ggufserver.app.plugin.ToolRuntime
import com.ggufserver.app.plugin.ToolTagScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 请求网关：常驻公开端口，取代旧 ImageProxy 的挂载方式（图片压缩变成它的第一个内置处理步骤，
 * 见 [maybeDownscale]），并在其上叠加插件中间件管线（《工具插件规范》§6.8）。
 *
 * 端口拓扑（LlamaServerService 负责落实）：llama-server 永远只绑 127.0.0.1:[upstreamPort]，
 * 本类永远绑 0.0.0.0:[listenPort]（ServerBus.port 语义不变：公开端口）。
 *
 * 性能红线：零插件（或启用插件都没有 hooks/tools/systemClause）时，[PluginStore.hasAnyMiddleware]
 * 为 false，本类的行为退化成过去 ImageProxy 的原样转发——不解析、不等待、不分配。
 */
class Gateway(
    private val listenPort: Int,
    private val upstreamPort: Int,
    private val compressImages: Boolean,
    private val maxDim: Int,
    private val contextSize: Int,
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool = Executors.newCachedThreadPool()

    /**
     * 窗口起点按会话保持（阶梯式前移，见类头注释与 §6.8）：本机请求带 X-GGUF-Session 时
     * 用会话 id 做键，跨请求保持 winStart 不回退——KV 前缀缓存才能命中。外部请求没有
     * 会话概念，每次都从 0 现算（“无状态裁剪”），不占用这个表。普通内存 Map 即可，
     * 与旧的 ChatViewModel 单会话字段等价（旧实现本就只服务一个当前会话）。
     */
    private val winStartBySession = ConcurrentHashMap<String, Int>()

    /** afterReply 在“没有工具标记”的流式回合里是纯异步旁路，不能用请求线程的协程作用域（那个作用域随响应结束而结束）。 */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", listenPort))
        serverSocket = ss
        running = true
        pool.execute {
            while (running) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { runCatching { handle(c) } }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { pool.shutdownNow() }
        runCatching { bgScope.cancel() }
    }

    // ============================== 连接入口 ==============================

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 600_000
            val cin = BufferedInputStream(c.getInputStream())
            val head = readHead(cin) ?: return
            val (requestLine, headers) = head
            val lower = headers.map { it.lowercase() }

            if (lower.any { it.startsWith("expect:") && it.contains("100-continue") }) {
                c.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                c.getOutputStream().flush()
            }

            val contentLength = lower.firstOrNull { it.startsWith("content-length:") }
                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

            // 从这里开始算「一个请求在处理中」，直到响应转发完毕——本机聊天与外部客户端都经过网关，
            // 状态灯覆盖面比以前（只覆盖开了图片压缩时）更全。
            ServerBus.beginRequest()
            try {
                // 日志记录模式：记一条请求行（方法+路径+来源），排除 /health 轮询噪音
                if (ServerBus.fileLogging && !requestLine.contains("/health")) {
                    ServerBus.appendLog("[请求] " + requestLine.substringBefore(" HTTP") + "  ← " + (c.inetAddress?.hostAddress ?: "?"))
                }
                var body = if (contentLength > 0) readN(cin, contentLength) else ByteArray(0)

                val isChat = requestLine.startsWith("POST", true) && requestLine.contains("/v1/chat/completions")
                // 桥的 gguf.llm.* 直连内部端口，不应该打到这个公开端口；带这个头来了也不过插件管线，
                // 只是双保险（见规范 §6.8 防回环）。
                val fromPlugin = lower.any { it.startsWith("x-gguf-plugin:") }

                if (isChat && body.isNotEmpty() && compressImages) {
                    body = maybeDownscale(body)
                }

                if (isChat && body.isNotEmpty() && !fromPlugin && PluginStore.hasAnyMiddleware()) {
                    val handled = runBlocking { handleChatPipeline(c, requestLine, headers, lower, body) }
                    if (handled) return
                }

                forwardPlain(c, requestLine, headers, body)
            } finally {
                ServerBus.endRequest()
            }
        }
    }

    // ============================== 插件管线 ==============================

    private data class Plan(
        val takeOver: PluginManifest?,
        val forwardBody: ByteArray?,
        val toolPlugins: List<PluginManifest>,
        val afterReplyPlugins: List<PluginManifest>,
        val continuationPlugins: List<PluginManifest>,
        val isStream: Boolean,
    )

    /**
     * 只有 POST /v1/chat/completions 且 [PluginStore.hasAnyMiddleware] 为真时才会走到这里。
     * 返回 true = 本次请求已经完整处理（转发或接管），调用方不用再做任何事。
     */
    private suspend fun handleChatPipeline(
        client: Socket, requestLine: String, headers: List<String>, lowerHeaders: List<String>, rawBody: ByteArray,
    ): Boolean {
        val root = try { JSONObject(String(rawBody, Charsets.UTF_8)) } catch (e: Exception) { return false }

        // 判定保护（宿主内置，不可关——正确性约束而非功能）：受限解码请求（json_schema/grammar/
        // response_format）+ 惩罚采样 = 系统性判定偏置（实测枚举判定假阴 0→9：指令里已出现的答案词
        // 被惩罚，首 token 偏向另一枝，《推理参数与外部知识架构研究》§三）。很多客户端库默认静默携带
        // repeat_penalty 1.1，几乎不可能是对「受限判定」的有意选择 → 一律中和并记日志。
        // DRY/温度不动（DRY 实测对枚举判定无害；温度是调用方的正当选择）。
        val constrained = isConstrainedRequest(root)
        if (constrained) applyJudgmentGuard(root)

        val rawMessages = root.optJSONArray("messages") ?: JSONArray()
        val msgList = ArrayList<JSONObject>(rawMessages.length())
        for (i in 0 until rawMessages.length()) rawMessages.optJSONObject(i)?.let { msgList += it }

        val sessionHeader = lowerHeaders.firstOrNull { it.startsWith("x-gguf-session:") }
            ?.substringAfter(":")?.trim()?.ifEmpty { null }
        val source = if (sessionHeader != null) "local" else "external"
        if (source == "local") PluginRuntime.noteLocalTurn(sessionHeader)

        val active = PluginStore.activePlugins(source)
        if (active.isEmpty()) {
            // 该来源没有生效插件，但既然已经进了管线（有面向别的来源的插件启用着），上下文组装这个
            // 宿主内置中间件照常生效：窗口预算裁剪 + 时间标注 + 跨天时的时间背景块（懒注入）。
            // 真正的零插件快路径在 handle() 里由 hasAnyMiddleware() 拦住，根本不会走到这里。
            runCatching {
                val assembly = assembleWindow(sessionHeader, msgList, contextSize)
                stripGgufFields(root, msgList)
                applyContext(msgList, emptyList(), assembly.anyCrossDay)
            }.onFailure { stripGgufFields(root, msgList) }
            root.put("messages", toJsonArray(msgList))
            forwardPlain(client, requestLine, headers, root.toString().toByteArray(Charsets.UTF_8))
            return true
        }

        // fail-open：从这里到拿到 plan 之前的一切（窗口裁剪、构造 turn、route/context 分发、注入拼接）
        // 都还没往客户端写过一个字节，任何异常都安全地退化成“按零插件转发这一次请求”。
        val planOrNull = try {
            val hasNativeTools = root.has("tools") && (root.optJSONArray("tools")?.length() ?: 0) > 0
            // 上下文组装（宿主内置中间件，非插件）：窗口预算裁剪 + 时间标注，消费完 gguf_ts 后
            // 再统一剥离 gguf_* 扩展字段——上游 llama-server 永远看不到这些字段。
            val assembly = assembleWindow(sessionHeader, msgList, contextSize)
            stripGgufFields(root, msgList)
            val turn = JSONObject()
                .put("source", source)
                .put("sessionId", sessionHeader ?: JSONObject.NULL)
                .put("text", lastUserMessageText(msgList))
                .put("messages", toJsonArray(msgList))
                .put("dropped", assembly.dropped)
            turn to computePlan(root, msgList, active, turn, hasNativeTools, assembly.anyCrossDay, constrained)
        } catch (e: Exception) {
            ServerBus.appendLog("[插件网关] 处理请求时异常，已按零插件转发本次请求：${e.message}")
            null
        }
        if (planOrNull == null) {
            stripGgufFields(root, msgList)
            root.put("messages", toJsonArray(msgList))
            forwardPlain(client, requestLine, headers, root.toString().toByteArray(Charsets.UTF_8))
            return true
        }
        val (turn, plan) = planOrNull

        if (plan.takeOver != null) {
            if (handleTakeOver(client, plan.takeOver, turn, root)) return true
            // handle 没给出可用结果：fail-open 退回到把这一轮当普通请求转发（不再重跑 context）。
            root.put("messages", toJsonArray(msgList))
            forwardPlain(client, requestLine, headers, root.toString().toByteArray(Charsets.UTF_8))
            return true
        }

        val forwardBody = plan.forwardBody ?: run {
            root.put("messages", toJsonArray(msgList))
            root.toString().toByteArray(Charsets.UTF_8)
        }
        if (plan.toolPlugins.isEmpty() && plan.afterReplyPlugins.isEmpty() && plan.continuationPlugins.isEmpty()) {
            forwardPlain(client, requestLine, headers, forwardBody)
            return true
        }
        if (!plan.isStream) {
            relayNonStream(client, requestLine, headers, forwardBody, plan.afterReplyPlugins)
        } else {
            relayStreamWithScanning(client, requestLine, headers, forwardBody, plan.afterReplyPlugins, plan.toolPlugins, plan.continuationPlugins, turn)
        }
        return true
    }

    private suspend fun computePlan(
        root: JSONObject, msgList: MutableList<JSONObject>, active: List<PluginManifest>, turn: JSONObject,
        hasNativeTools: Boolean, anyCrossDay: Boolean, constrained: Boolean = false,
    ): Plan {
        // 切面微内核（§13）：把启用插件投影成「每个管线面挂了谁」，再按管线面顺序经 AspectBus 分发。
        // 阶段 1 等价重构——顺序/预算/fail-open 与旧内联流水线逐字节一致，仅收敛了编排。
        val reg = AspectRegistry(active, hasNativeTools, constrained)
        val isStream = root.optBoolean("stream", false)

        // ---- route 面：串行、可接管，总预算 150ms（红线：request 路径同步点之一） ----
        val takeOver = AspectBus.routeUntilTakeOver(reg.route, Aspects.ROUTE_BUDGET_MS) { p, remain ->
            PluginRuntime.dispatch(p.id, "route", turn, remain)
        }
        // generate 面（接管分支）：某插件接管本轮 = 用它的 handle 替换默认 generate（默认 generate = 转发 llama-server）。
        if (takeOver != null) return Plan(takeOver, null, reg.toolCallables, reg.afterReply, reg.continuation, isStream)

        // ---- curate 面：本轮给模型露哪些 callable（§13.4）。串行、快判定、总预算 150ms（禁调 LLM，同 route）。
        // 无 curate 处理器 → exposure=null=全露，逐字节等价 v1；有处理器则取各自 expose[] 的并集
        // ∪ 弃权者（没登记 curate 的插件）的 callable，收窄本轮教学。语义细节见 AspectBus.curateExposure。 ----
        val exposure: Set<String>? = AspectBus.curateExposure(
            reg.curate, reg.curateAbstained, Aspects.CURATE_BUDGET_MS,
        ) { p, remain -> PluginRuntime.dispatch(p.id, "curate", turn, remain) }
        if (exposure != null) {
            android.util.Log.d("GGUF-gw", "curate exposure=${exposure.joinToString(",").ifEmpty { "(空)" }}")
        }

        // ---- context 面：并行，总预算 250ms；懒注入判定（尾部背景块）在 applyContext 里做 ----
        val results = AspectBus.parallel(reg.context, Aspects.CONTEXT_BUDGET_MS) { p, remain ->
            PluginRuntime.dispatch(p.id, "context", turn, remain)
        }
        applyContext(msgList, results, anyCrossDay)

        // ---- context 面（教学部分）：systemClause + 各运行时插件按 curate 的 exposure 生成的 callable 教学，逐轮字节稳定 ----
        applySystemSuffix(msgList, active, hasNativeTools, exposure)
        root.put("messages", toJsonArray(msgList))

        // ---- params 面：按任务自适应采样参数（§6.10）。必须在 context/systemClause 之后——
        // 插件若走 LLM 判断（llm.classify），判断请求要以「最终组装完的 messages」为前缀才能与
        // 本次转发共用 KV（严格后缀扩展，判断后 llama-server 剪尾零残留）。串行分发，fail-open。
        // 受限解码请求跳过（reg.params 已排除）：判定/抽取类请求的参数由调用方定型，聊天三档不适用。
        if (reg.params.isNotEmpty()) {
            // 客户端显式设置过的采样键快照：external 来源不可覆盖（尊重 API 调用者的显式意图）；
            // local 来源的 body 参数只是配置抽屉滑杆的通用缺省 → 插件按任务的判断可以覆盖。
            val clientKeys = SAMPLING_KEYS.filterTo(HashSet()) { root.has(it) }
            val protectClient = turn.optString("source") == "external"
            val payload = JSONObject(turn.toString())
                .put("messages", toJsonArray(msgList))
                .put("clientParams", JSONArray(clientKeys.toList()))
            AspectBus.serialForEach(
                reg.params, Aspects.PARAMS_BUDGET_MS,
                invoke = { p, remain -> PluginRuntime.dispatch(p.id, "params", payload, remain) },
            ) { p, r ->
                val params = r.optJSONObject("params") ?: return@serialForEach
                val applied = ArrayList<String>()
                for (k in SAMPLING_KEYS) {
                    if (!params.has(k)) continue
                    if (protectClient && k in clientKeys) continue
                    val v = params.opt(k)
                    if (v is Number) { root.put(k, v); applied += "$k=$v" }
                }
                if (applied.isNotEmpty()) {
                    android.util.Log.d("GGUF-gw", "params ${p.id}: ${applied.joinToString(" ")}")
                    if (ServerBus.fileLogging) ServerBus.appendLog("[插件:${p.id}] 采样参数档：${applied.joinToString(" ")}")
                }
            }
        }
        // generate 面（默认分支）：forwardBody = 组装完的请求体，交默认 generate 处理器 = 转发内部 llama-server。
        return Plan(null, root.toString().toByteArray(Charsets.UTF_8), reg.toolCallables, reg.afterReply, reg.continuation, isStream)
    }

    /**
     * head/tail 注入（§6.4）：head 拼进首条 system，要求逐轮字节稳定；tail 由宿主拼壳
     * （现在时间 + 各插件内容 + 收尾提醒）后作为独立 system 消息插到最后一条用户消息之前。
     * 懒注入判定（血泪教训 §3）：插件都没给 tail、窗口内也没有跨天消息 → 整个 tail 块零注入，
     * 走完整 KV 缓存快路径；一旦需要注入，必须是独立 system 消息，绝不能拼进用户消息前缀。
     */
    private fun applyContext(msgList: MutableList<JSONObject>, results: List<Pair<PluginManifest, JSONObject?>>, anyCrossDay: Boolean) {
        val perPluginBudget = (contextSize / 8).coerceAtLeast(64)
        val heads = ArrayList<String>()
        val tails = ArrayList<String>()
        for ((plugin, r) in results) {
            android.util.Log.d(
                "GGUF-gw",
                "context ${plugin.id}: " +
                    (r?.let { "head=${it.optString("head").length} tail=${it.optString("tail").length}" } ?: "null(超时/异常)")
            )
            r ?: continue
            var head = r.optString("head", "")
            var tail = r.optString("tail", "")
            if (head.isEmpty() && tail.isEmpty()) continue
            val headTok = MemoryIndex.estimateTokens(head)
            val tailTok = MemoryIndex.estimateTokens(tail)
            if (headTok + tailTok > perPluginBudget) {
                ServerBus.appendLog("[插件:${plugin.id}] context 注入超出预算（上下文的 1/8），已截断")
                if (headTok >= perPluginBudget) {
                    head = head.take(perPluginBudget); tail = ""
                } else {
                    tail = tail.take((perPluginBudget - headTok).coerceAtLeast(0))
                }
            }
            if (head.isNotEmpty()) heads += head
            if (tail.isNotEmpty()) tails += tail
        }
        // head：拼进首条 system（没有则创建），要求逐轮字节稳定——插件自己保证，宿主只负责拼接位置固定。
        if (heads.isNotEmpty()) {
            val block = heads.joinToString("\n")
            val idx = msgList.indexOfFirst { it.optString("role") == "system" }
            if (idx >= 0) {
                val m = msgList[idx]
                val cur = m.optString("content")
                m.put("content", if (cur.isEmpty()) block else "$cur\n\n$block")
            } else {
                msgList.add(0, JSONObject().put("role", "system").put("content", block))
            }
        }
        // tail：宿主拼壳，插件只出中间内容；懒注入——两个触发条件任一即可。
        if (tails.isNotEmpty() || anyCrossDay) {
            val fullFmt = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA)
            val block = buildString {
                append("现在时间：").append(fullFmt.format(Date(System.currentTimeMillis()))).append('\n')
                if (tails.isNotEmpty()) append(tails.joinToString("\n")).append('\n')
                append("以上是给你的背景资料：回答时利用即可，不要复述、引用或提及这些资料本身。")
            }
            val insertIdx = msgList.indexOfLast { it.optString("role") == "user" }
            val m = JSONObject().put("role", "system").put("content", block)
            if (insertIdx >= 0) msgList.add(insertIdx, m) else msgList.add(m)
            android.util.Log.d("GGUF-gw", "tail 注入 ${block.length} 字（插在 index=$insertIdx 前）")
        } else {
            android.util.Log.d("GGUF-gw", "tail 零注入（无插件内容且窗口内无跨天消息）")
        }
    }

    // ============================== 上下文组装：窗口预算裁剪 + 时间标注（宿主内置中间件，非插件） ==============================

    private data class WindowAssembly(val dropped: JSONArray, val anyCrossDay: Boolean)

    /**
     * 消息窗口预算裁剪 + 时间标注，从旧 ChatViewModel.buildHistory 原样迁来（§6.8/§11）：
     * - winStart 阶梯式前移：只有超预算才一次裁到目标的 3/4，之后多轮保持不动（KV 前缀缓存友好）；
     *   按会话保持（本机请求靠 X-GGUF-Session 维持 winStart 不回退），外部请求没有会话概念、
     *   每次都从 0 现算（“无状态裁剪”）。
     * - 非当天消息加「（M月d日）」前缀；只有本机消息才带 gguf_ts，外部消息没有该字段、永不标注。
     * - 裁掉的消息直接从 msgList 移除，只把 {role, text, ts} 事实通过返回值交给 context 钩子——
     *   要不要把它们捞回来、怎么检索，都是插件的事，宿主自己不做任何回忆检索。
     */
    private fun assembleWindow(sessionId: String?, msgList: MutableList<JSONObject>, contextSize: Int): WindowAssembly {
        val sysIdx = msgList.indexOfFirst { it.optString("role") == "system" }
        val indices = msgList.indices.filter { it != sysIdx }
        if (indices.isEmpty()) return WindowAssembly(JSONArray(), false)

        val sysText = if (sysIdx >= 0) msgList[sysIdx].optString("content") else ""
        val promptBudget = (contextSize * 4) / 5
        val memBudget = (contextSize / 8).coerceAtLeast(1)
        val fixed = MemoryIndex.estimateTokens(sysText) + 48
        val recentBudget = (promptBudget - fixed - memBudget).coerceAtLeast(256)

        fun tokensOf(i: Int) = MemoryIndex.estimateTokens(textOfMessage(msgList[i])) + 8
        fun usedFrom(start: Int) = indices.drop(start).sumOf { tokensOf(it) }

        var winStart = if (sessionId != null) (winStartBySession[sessionId] ?: 0) else 0
        if (winStart > indices.lastIndex) winStart = 0
        if (usedFrom(winStart) > recentBudget) {
            // 一次裁到预算的 3/4，接下来多轮窗口起点保持稳定（KV 缓存友好）
            val target = recentBudget * 3 / 4
            var s = winStart
            while (s < indices.lastIndex && usedFrom(s) > target) s++
            winStart = s
        }
        if (sessionId != null) winStartBySession[sessionId] = winStart

        val droppedIdx = indices.subList(0, winStart)
        val keptIdx = indices.subList(winStart, indices.size)

        val dropped = JSONArray()
        for (i in droppedIdx) {
            val m = msgList[i]
            dropped.put(
                JSONObject().put("role", m.optString("role"))
                    .put("text", textOfMessage(m))
                    .put("ts", m.optLong("gguf_ts", 0L))
            )
        }

        val dayFmt = SimpleDateFormat("M月d日", Locale.CHINA)
        val today = dayFmt.format(Date(System.currentTimeMillis()))
        var anyCrossDay = false
        for (i in keptIdx) {
            val m = msgList[i]
            val ts = m.optLong("gguf_ts", 0L)
            if (ts <= 0) continue
            val d = dayFmt.format(Date(ts))
            if (d != today) {
                anyCrossDay = true
                val content = m.opt("content")
                if (content is String) m.put("content", "（$d）$content")
            }
        }

        // 从大到小删，避免边删边错位
        for (i in droppedIdx.sortedDescending()) msgList.removeAt(i)

        return WindowAssembly(dropped, anyCrossDay)
    }

    /** systemClause + 各运行时插件的 callable 教学条款（curate/context 面），追加到首条 system 末尾（§6.5/§6.9/§13.5）。
     *  [exposure]＝本轮 curate 决定暴露的 callable 名集合（§13.4）：null = 全露（默认，等价 v1）。 */
    private fun applySystemSuffix(
        msgList: MutableList<JSONObject>, active: List<PluginManifest>, hasNativeTools: Boolean, exposure: Set<String>?,
    ) {
        val sb = StringBuilder()
        for (p in active) {
            if (p.systemClause.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(p.systemClause)
            }
        }
        // 运行时插件的教学条款（今仅 type:tool 的 <tool> 协议）。客户端自带原生 tools 时整段不注入（§6.9 透传）。
        // exposure 由 curate 面算出；null（无 curate 处理器）= 全露。
        val teaching = if (hasNativeTools) emptyList() else Runtimes.all.mapNotNull { rt ->
            rt.teachingClause(rt.callablesIn(active), exposure)
        }
        // 注入位置由「curate 有没有表态」决定，这是 KV 前缀缓存红线（§3 血泪教训的直接推论）：
        // - exposure == null（默认，没有插件登记 curate）：教学条款逐轮完全一样 → 照旧拼进首条
        //   system，逐字节等价 v1，前缀缓存全命中。
        // - exposure != null：教学条款随本轮意图变（闲聊轮不露、记事轮露）。这种动态内容一旦拼进
        //   首条 system，整条 prompt 从第一个 token 就变了 → 每次意图切换都要在手机 CPU 上重算
        //   整段 prefill（秒级），省下的 150 token 远不够抵。所以改插到「末条用户消息之前」的
        //   动态位（与 tail 同一位置纪律）：历史 KV 全保住，只重算末尾一小段。顺带也符合实测排布
        //   结论——任务指令紧贴问题比压在最前面更容易被 1B 真正执行。
        if (exposure == null) {
            for (c in teaching) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) {
            val suffix = sb.toString().trimEnd()
            val idx = msgList.indexOfFirst { it.optString("role") == "system" }
            if (idx >= 0) {
                val m = msgList[idx]
                val cur = m.optString("content")
                m.put("content", if (cur.isEmpty()) suffix else "$cur\n\n$suffix")
            } else {
                msgList.add(0, JSONObject().put("role", "system").put("content", suffix))
            }
        }
        if (exposure != null && teaching.isNotEmpty()) {
            val block = teaching.joinToString("\n\n").trimEnd()
            val insertIdx = msgList.indexOfLast { it.optString("role") == "user" }
            val m = JSONObject().put("role", "system").put("content", block)
            if (insertIdx >= 0) msgList.add(insertIdx, m) else msgList.add(m)
            android.util.Log.d("GGUF-gw", "curate 动态教学注入 ${block.length} 字（末条用户消息前，不动 head）")
        }
    }

    // ============================== 接管回合（takeOver） ==============================

    /** 返回 false = handle 没给出可用结果（超时/异常/空回应），调用方应 fail-open 退回普通转发。 */
    private suspend fun handleTakeOver(client: Socket, plugin: PluginManifest, turn: JSONObject, root: JSONObject): Boolean {
        val result = PluginRuntime.dispatch(plugin.id, "handle", turn, Aspects.HANDLE_BUDGET_MS) ?: return false
        val hasContent = result.has("content")
        val hasNotice = result.has("notice")
        if (!hasContent && !hasNotice) return false
        val content = if (hasContent) result.optString("content") else ""
        val notice = if (hasNotice) result.optString("notice").ifBlank { null } else null
        val model = root.optString("model", "local")
        if (root.optBoolean("stream", false)) {
            writeTakeOverStream(client, model, content, notice, plugin.id)
        } else {
            writeTakeOverJson(client, model, content, notice, plugin.id)
        }
        return true
    }

    private fun writeTakeOverJson(client: Socket, model: String, content: String, notice: String?, pluginId: String) {
        val resp = JSONObject()
            .put("id", "chatcmpl-gguf-" + System.nanoTime())
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put("index", 0)
                        .put("message", JSONObject().put("role", "assistant").put("content", content))
                        .put("finish_reason", "stop")
                )
            )
        if (notice != null) {
            resp.put("gguf", JSONObject().put("notices", JSONArray().put(JSONObject().put("plugin", pluginId).put("text", notice))))
        }
        val bytes = resp.toString().toByteArray(Charsets.UTF_8)
        writeRawHeaders(client, "HTTP/1.1 200 OK", listOf("Content-Type: application/json", "Content-Length: ${bytes.size}", "Connection: close"))
        client.getOutputStream().write(bytes)
        client.getOutputStream().flush()
    }

    private fun writeTakeOverStream(client: Socket, model: String, content: String, notice: String?, pluginId: String) {
        writeRawHeaders(client, "HTTP/1.1 200 OK", listOf("Content-Type: text/event-stream", "Cache-Control: no-cache", "Connection: close"))
        val out = client.getOutputStream()
        val id = "chatcmpl-gguf-" + System.nanoTime()
        val created = System.currentTimeMillis() / 1000
        fun chunk(delta: JSONObject, finish: String?, extra: JSONObject?): String {
            val o = JSONObject()
                .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)))
            if (extra != null) o.put("gguf", extra)
            return o.toString()
        }
        out.write(("data: " + chunk(JSONObject().put("role", "assistant"), null, null) + "\n\n").toByteArray(Charsets.UTF_8))
        if (content.isNotEmpty()) {
            out.write(("data: " + chunk(JSONObject().put("content", content), null, null) + "\n\n").toByteArray(Charsets.UTF_8))
        }
        val extra = notice?.let { JSONObject().put("notices", JSONArray().put(JSONObject().put("plugin", pluginId).put("text", it))) }
        out.write(("data: " + chunk(JSONObject(), "stop", extra) + "\n\n").toByteArray(Charsets.UTF_8))
        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ============================== 非流式响应中继 + afterReply ==============================

    private suspend fun relayNonStream(
        client: Socket, requestLine: String, headers: List<String>, body: ByteArray, afterReplyPlugins: List<PluginManifest>,
    ) {
        Socket("127.0.0.1", upstreamPort).use { up ->
            up.soTimeout = 600_000
            val uout = BufferedOutputStream(up.getOutputStream())
            uout.write(buildRequest(requestLine, headers, body.size).toByteArray(Charsets.ISO_8859_1))
            uout.write(body)
            uout.flush()

            val uin = BufferedInputStream(up.getInputStream())
            val head = readHead(uin) ?: return
            val (statusLine, respHeaders) = head
            val lower = respHeaders.map { it.lowercase() }
            val clientOut = client.getOutputStream()

            if (statusCode(statusLine)?.let { it !in 200..299 } != false) {
                writeRawHeaders(client, statusLine, respHeaders)
                uin.copyTo(clientOut, 16 * 1024)
                clientOut.flush()
                return
            }

            val respBytes = readResponseBody(uin, lower)
            val respObj = if (afterReplyPlugins.isEmpty()) null else runCatching { JSONObject(String(respBytes, Charsets.UTF_8)) }.getOrNull()
            if (respObj == null) {
                writeRawHeaders(client, statusLine, respHeaders)
                clientOut.write(respBytes)
                clientOut.flush()
                return
            }

            var content = respObj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            val notices = JSONArray()
            for (p in afterReplyPlugins) {
                val msg = JSONObject().put("id", respObj.optString("id")).put("content", content).put("reasoning", "")
                val r = PluginRuntime.dispatch(p.id, "afterReply", msg, Aspects.AFTER_REPLY_BUDGET_MS) ?: continue
                if (r.has("content")) content = r.optString("content")
                if (r.has("notice")) r.optString("notice").ifBlank { null }?.let { notices.put(JSONObject().put("plugin", p.id).put("text", it)) }
            }
            respObj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.put("content", content)
            if (notices.length() > 0) respObj.put("gguf", JSONObject().put("notices", notices))

            val outBytes = respObj.toString().toByteArray(Charsets.UTF_8)
            val filtered = respHeaders.filter { h ->
                val l = h.lowercase()
                !l.startsWith("content-length:") && !l.startsWith("transfer-encoding:")
            } + listOf("Content-Length: ${outBytes.size}")
            writeRawHeaders(client, statusLine, filtered)
            clientOut.write(outBytes)
            clientOut.flush()
        }
    }

    // ============================== 流式响应中继 + 工具标记扫描 ==============================

    private suspend fun relayStreamWithScanning(
        client: Socket, requestLine: String, headers: List<String>, body: ByteArray,
        afterReplyPlugins: List<PluginManifest>, toolPlugins: List<PluginManifest>,
        continuationPlugins: List<PluginManifest>, turn: JSONObject,
    ) {
        Socket("127.0.0.1", upstreamPort).use { up ->
            up.soTimeout = 600_000
            val uout = BufferedOutputStream(up.getOutputStream())
            uout.write(buildRequest(requestLine, headers, body.size).toByteArray(Charsets.ISO_8859_1))
            uout.write(body)
            uout.flush()

            val uin = BufferedInputStream(up.getInputStream())
            val head = readHead(uin) ?: return
            val (statusLine, respHeaders) = head
            val lower = respHeaders.map { it.lowercase() }
            val clientOut = client.getOutputStream()

            if (statusCode(statusLine)?.let { it !in 200..299 } != false) {
                writeRawHeaders(client, statusLine, respHeaders)
                uin.copyTo(clientOut, 16 * 1024)
                clientOut.flush()
                return
            }

            val chunked = lower.any { it.startsWith("transfer-encoding:") && it.contains("chunked") }
            val contentLen = lower.firstOrNull { it.startsWith("content-length:") }?.substringAfter(":")?.trim()?.toIntOrNull()
            val bodyStream: InputStream = when {
                chunked -> ChunkedInputStream(uin)
                contentLen != null -> LimitedInputStream(uin, contentLen.toLong())
                else -> uin
            }

            val downstreamHeaders = respHeaders.filter { h ->
                val l = h.lowercase()
                !l.startsWith("content-length:") && !l.startsWith("transfer-encoding:") && !l.startsWith("connection:")
            } + listOf("Connection: close")
            writeRawHeaders(client, statusLine, downstreamHeaders)

            val fullContent = StringBuilder()
            var toolCall: ToolCall? = null
            // stream 面：type:tool 运行时给出的令牌流扫描器（无工具 callable → null，走"不扫描"快路径）。
            val scanner = ToolRuntime.makeScanner(toolPlugins)

            if (scanner == null) {
                // 没有插件工具需要扫描：只累计正文供 afterReply，其余字节原样转发（不解析）。
                bodyStream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
                    emitRawLine(clientOut, raw)
                    val payload = raw.trim().removePrefix("data:").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        runCatching { JSONObject(payload) }.getOrNull()?.let { fullContent.append(extractContentPieceFromObj(it)) }
                    }
                }
            } else {
                bodyStream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
                    val line = raw.trim()
                    if (!line.startsWith("data:")) {
                        if (line.isNotEmpty()) emitRawLine(clientOut, raw)
                        return@forEachLine
                    }
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") return@forEachLine
                    val obj = runCatching { JSONObject(payload) }.getOrNull()
                    if (obj == null) { emitRawLine(clientOut, raw); return@forEachLine }
                    val piece = extractContentPieceFromObj(obj)
                    if (piece.isNotEmpty()) fullContent.append(piece)
                    when (val outcome = scanner.feed(piece)) {
                        is ToolTagScanner.Outcome.Forward ->
                            if (outcome.text == piece) emitRawLine(clientOut, raw) else emitContentChunk(clientOut, obj, outcome.text)
                        is ToolTagScanner.Outcome.Hold -> { /* 暂扣，这一行不转发 */ }
                        is ToolTagScanner.Outcome.Resolved -> {
                            emitContentChunk(clientOut, obj, outcome.forwardText)
                            outcome.toolName?.let { name ->
                                ToolRuntime.ownerOf(toolPlugins, name)
                                    ?.let { toolCall = ToolCall(it, name, outcome.toolInput.orEmpty()) }
                            }
                        }
                    }
                }
                val tail = scanner.finish()
                if (tail.toolName != null) {
                    ToolRuntime.ownerOf(toolPlugins, tail.toolName)
                        ?.let { toolCall = ToolCall(it, tail.toolName, tail.toolInput.orEmpty()) }
                } else if (tail.leftoverText.isNotEmpty()) {
                    emitContentChunk(clientOut, JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject()))), tail.leftoverText)
                }
            }

            // after 面（工具执行）：吞掉过工具标记才需要暂缓收尾（执行 hooks.tool ≤10s，把 notice/card
            // 带在补发的最后一个 chunk 上）；没有标记则 execute 返回空产物、[DONE] 零延迟——这一段
            // 完全不触碰已经发出的正文字节。执行逻辑收敛在 type:tool 运行时（§13.5），网关只负责下发。
            val toolOutcome = ToolRuntime.execute(toolCall, turn)
            if (!toolOutcome.isEmpty()) {
                val ggufExt = JSONObject()
                if (toolOutcome.notices.length() > 0) ggufExt.put("notices", toolOutcome.notices)
                if (toolOutcome.cards.length() > 0) ggufExt.put("cards", toolOutcome.cards)
                val extraChunk = JSONObject()
                    .put("id", "chatcmpl-gguf-notice").put("object", "chat.completion.chunk")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject())))
                    .put("gguf", ggufExt)
                clientOut.write(("data: $extraChunk\n\n").toByteArray(Charsets.UTF_8))
            }

            // 两段式续段（§重型记忆）：前台回复已流完（客户端已看到第一段），若有 continuation 插件 →
            // 先发「回忆中」提示，再让插件同轮召回相关记忆并用前台模型生成续段，作为增量追加到同一条消息。
            // 第一段绝不因此延迟；[DONE] 推迟到续段完成（用户此时看到的是「回忆中→续段」）。fail-open：插件超时/空则直接收尾。
            if (continuationPlugins.isNotEmpty()) {
                for (p in continuationPlugins) {
                    emitNoticeChunk(clientOut, p.id, "正在回忆…")
                    // 扁平：turn 的字段（source/sessionId/text/messages）平铺，再加 content（第一段回复），与其它钩子入参一致。
                    val payload = JSONObject(turn.toString()).put("content", fullContent.toString())
                    val r = runCatching { PluginRuntime.dispatch(p.id, "continuation", payload, Aspects.CONTINUATION_BUDGET_MS) }.getOrNull()
                    val cont = r?.optString("content")?.ifBlank { null }
                    if (cont != null) emitSyntheticContent(clientOut, cont)
                }
            }

            clientOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            clientOut.flush()

            // afterReply：流式回合里纯异步旁路，不影响这条连接（已经关闭），忽略其 content 改写（§6.9）。
            if (afterReplyPlugins.isNotEmpty()) {
                val content = fullContent.toString()
                bgScope.launch {
                    for (p in afterReplyPlugins) {
                        val msg = JSONObject().put("id", "").put("content", content).put("reasoning", "")
                        val r = PluginRuntime.dispatch(p.id, "afterReply", msg, Aspects.AFTER_REPLY_BUDGET_MS) ?: continue
                        r.optString("notice").ifBlank { null }?.let { ServerBus.appendLog("[插件:${p.id}] $it") }
                    }
                }
            }
        }
    }

    private fun emitRawLine(out: OutputStream, raw: String) {
        out.write((raw + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 克隆原始 chunk，只替换 delta.content——保留 finish_reason/timings 等其它字段。 */
    private fun emitContentChunk(out: OutputStream, original: JSONObject, newContent: String) {
        val clone = JSONObject(original.toString())
        val delta = clone.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        if (delta != null) delta.put("content", newContent) else return
        out.write(("data: $clone\n\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun extractContentPieceFromObj(obj: JSONObject): String {
        val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return ""
        return if (!delta.isNull("content")) delta.optString("content", "") else ""
    }

    /** 合成一个 content 增量 chunk（两段式续段用）：把续段文本作为 delta.content 追加到同一条流式消息。 */
    private fun emitSyntheticContent(out: OutputStream, text: String) {
        val chunk = JSONObject()
            .put("id", "chatcmpl-gguf-cont").put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject().put("content", text))))
        out.write(("data: $chunk\n\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 发一个「回忆中」提示 chunk（走 gguf.notices 扩展字段）：本机客户端渲染成状态条，外部 OpenAI 客户端忽略未知字段。 */
    private fun emitNoticeChunk(out: OutputStream, pluginId: String, text: String) {
        val chunk = JSONObject()
            .put("id", "chatcmpl-gguf-status").put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject())))
            .put("gguf", JSONObject().put("notices", JSONArray().put(JSONObject().put("plugin", pluginId).put("text", text))))
        out.write(("data: $chunk\n\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ============================== 判定保护（受限解码请求） ==============================

    /** 受限解码请求：带 json_schema / grammar，或 response_format 指定了 JSON 输出。 */
    private fun isConstrainedRequest(root: JSONObject): Boolean {
        if (root.has("json_schema") || root.has("grammar")) return true
        val rf = root.optJSONObject("response_format") ?: return false
        val t = rf.optString("type")
        return t == "json_schema" || t == "json_object" || rf.has("json_schema") || rf.has("schema")
    }

    /** 中和受限解码请求携带的惩罚采样（机制见 handleChatPipeline 处的注释）；改动过才记日志。 */
    private fun applyJudgmentGuard(root: JSONObject) {
        val fixed = ArrayList<String>()
        val rp = root.optDouble("repeat_penalty", 1.0)
        if (rp != 1.0) { root.put("repeat_penalty", 1.0); fixed += "repeat_penalty $rp→1.0" }
        val pp = root.optDouble("presence_penalty", 0.0)
        if (pp != 0.0) { root.put("presence_penalty", 0); fixed += "presence_penalty $pp→0" }
        val fp = root.optDouble("frequency_penalty", 0.0)
        if (fp != 0.0) { root.put("frequency_penalty", 0); fixed += "frequency_penalty $fp→0" }
        if (fixed.isNotEmpty()) {
            android.util.Log.d("GGUF-gw", "判定保护: ${fixed.joinToString(" ")}")
            ServerBus.appendLog("[判定保护] 受限解码请求携带惩罚采样，已中和：${fixed.joinToString("、")}（惩罚会系统性扭曲枚举判定）")
        }
    }

    // ============================== 通用小工具 ==============================

    private fun toJsonArray(list: List<JSONObject>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(it) }
        return a
    }

    private fun stripGgufFields(root: JSONObject, msgList: List<JSONObject>) {
        root.keys().asSequence().filter { it.startsWith("gguf_") }.toList().forEach { root.remove(it) }
        msgList.forEach { m -> m.keys().asSequence().filter { it.startsWith("gguf_") }.toList().forEach { m.remove(it) } }
    }

    /** 取一条消息的纯文本内容：普通字符串直接用；多模态数组（图文混排）只取其中的 text 分段拼起来。 */
    private fun textOfMessage(m: JSONObject): String = when (val c = m.opt("content")) {
        is String -> c
        is JSONArray -> {
            val sb = StringBuilder()
            for (j in 0 until c.length()) {
                val part = c.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
            sb.toString()
        }
        else -> ""
    }

    private fun lastUserMessageText(msgList: List<JSONObject>): String {
        for (i in msgList.indices.reversed()) {
            val m = msgList[i]
            if (m.optString("role") != "user") continue
            return textOfMessage(m)
        }
        return ""
    }

    private fun statusCode(statusLine: String): Int? =
        Regex("HTTP/1\\.[01]\\s+(\\d{3})").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()

    private fun readResponseBody(uin: InputStream, lowerHeaders: List<String>): ByteArray {
        val chunked = lowerHeaders.any { it.startsWith("transfer-encoding:") && it.contains("chunked") }
        val contentLen = lowerHeaders.firstOrNull { it.startsWith("content-length:") }?.substringAfter(":")?.trim()?.toIntOrNull()
        return when {
            chunked -> ChunkedInputStream(uin).readBytes()
            contentLen != null -> readN(uin, contentLen)
            else -> uin.readBytes()
        }
    }

    private fun writeRawHeaders(client: Socket, statusLine: String, headers: List<String>) {
        val sb = StringBuilder()
        sb.append(statusLine).append("\r\n")
        for (h in headers) sb.append(h).append("\r\n")
        sb.append("\r\n")
        client.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        client.getOutputStream().flush()
    }

    // ============================== 原样转发（零插件快路径 / 非 chat 端点） ==============================

    private fun forwardPlain(client: Socket, requestLine: String, headers: List<String>, body: ByteArray) {
        Socket("127.0.0.1", upstreamPort).use { up ->
            up.soTimeout = 600_000
            val uout = BufferedOutputStream(up.getOutputStream())
            uout.write(buildRequest(requestLine, headers, body.size).toByteArray(Charsets.ISO_8859_1))
            if (body.isNotEmpty()) uout.write(body)
            uout.flush()
            up.getInputStream().copyTo(client.getOutputStream(), 16 * 1024)
            client.getOutputStream().flush()
        }
    }

    private fun buildRequest(requestLine: String, headers: List<String>, bodyLen: Int): String {
        val sb = StringBuilder()
        sb.append(requestLine).append("\r\n")
        for (h in headers) {
            val l = h.lowercase()
            if (l.startsWith("content-length:") || l.startsWith("connection:") ||
                l.startsWith("expect:") || l.startsWith("transfer-encoding:") || l.startsWith("host:")
            ) continue
            sb.append(h).append("\r\n")
        }
        sb.append("Host: 127.0.0.1\r\n")
        sb.append("Content-Length: ").append(bodyLen).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString()
    }

    // ============================== 图片压缩（原 ImageProxy 逻辑，网关的第一个内置处理步骤） ==============================

    private fun maybeDownscale(body: ByteArray): ByteArray {
        val s = String(body, Charsets.UTF_8)
        if (!s.contains("image_url")) return body // 快路径：纯文本请求不碰
        return try {
            val root = JSONObject(s)
            val messages = root.optJSONArray("messages") ?: return body
            var changed = false
            for (i in 0 until messages.length()) {
                val content = messages.optJSONObject(i)?.opt("content")
                if (content !is JSONArray) continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") != "image_url") continue
                    val img = part.optJSONObject("image_url") ?: continue
                    val nu = downscale(img.optString("url"))
                    if (nu != null) { img.put("url", nu); changed = true }
                }
            }
            if (changed) {
                ServerBus.appendLog("[图片压缩] 已缩放图片到 ≤${maxDim}px")
                root.toString().toByteArray(Charsets.UTF_8)
            } else body
        } catch (_: Exception) { body }
    }

    private fun downscale(url: String): String? {
        if (!url.startsWith("data:") || !url.contains(";base64,")) return null
        val bytes = try {
            Base64.decode(url.substringAfter(";base64,"), Base64.DEFAULT)
        } catch (_: Exception) { return null }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) return null
        if (maxOf(ow, oh) <= maxDim) return null

        var sample = 1
        while (maxOf(ow, oh) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val scale = maxDim.toDouble() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1.0) Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true
        ) else bmp

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ============================== 原始 HTTP 读取（请求头 / 定长体） ==============================

    private fun readHead(input: InputStream): Pair<String, List<String>>? {
        val buf = ByteArrayOutputStream()
        var acc = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            acc = (acc shl 8) or b
            if (acc == 0x0D0A0D0A) break
            if (buf.size() > 65536) return null
        }
        val lines = String(buf.toByteArray(), Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return lines[0] to lines.drop(1)
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) out else out.copyOf(off)
    }

    companion object {
        // 每切面预算已上移到切面微内核 [Aspects]（§13.3 分发面自带属性，不再由 Gateway 各自持有）。
        // 引用点：computePlan 用 Aspects.ROUTE/CONTEXT/PARAMS_BUDGET_MS；handleTakeOver/relay 用
        // Aspects.HANDLE/TOOL/AFTER_REPLY/CONTINUATION_BUDGET_MS。

        /** params 钩子可写的采样参数白名单（llama-server /v1/chat/completions 每请求字段）。
         *  阶段 2 工具/参数系统内插件化后，随 type:params 运行时插件一并迁走；当前留在网关。 */
        private val SAMPLING_KEYS = listOf(
            "temperature", "top_p", "top_k", "min_p",
            "repeat_penalty", "presence_penalty", "frequency_penalty",
            "dry_multiplier", "dry_base", "dry_allowed_length", "dry_penalty_last_n",
            "top_n_sigma", "xtc_probability", "xtc_threshold",
        )
    }
}

/** 把上游 chunked transfer-encoding 解成普通字节流，供逐行 SSE 解析用。 */
private class ChunkedInputStream(private val src: InputStream) : InputStream() {
    private var remaining = 0
    private var finished = false

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val b = src.read()
            if (b < 0) break
            if (b == '\r'.code) continue
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun nextChunk() {
        val sizeLine = readLine().substringBefore(';').trim()
        remaining = sizeLine.toIntOrNull(16) ?: 0
        if (remaining == 0) {
            finished = true
            while (true) {
                val l = readLine()
                if (l.isEmpty()) break
            }
        }
    }

    override fun read(): Int {
        if (finished) return -1
        if (remaining <= 0) {
            nextChunk()
            if (finished) return -1
        }
        val b = src.read()
        if (b < 0) { finished = true; return -1 }
        remaining--
        if (remaining == 0) { src.read(); src.read() } // 吞掉 chunk 末尾的 CRLF
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (finished) return -1
        if (remaining <= 0) {
            nextChunk()
            if (finished) return -1
        }
        val toRead = minOf(len, remaining)
        val n = src.read(b, off, toRead)
        if (n < 0) { finished = true; return -1 }
        remaining -= n
        if (remaining == 0) { src.read(); src.read() }
        return n
    }
}

/** 按 Content-Length 截断读取，避免读穿到下一段（极少见：SSE 端点一般不带 Content-Length）。 */
private class LimitedInputStream(private val src: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = src.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = src.read(b, off, toRead)
        if (n > 0) remaining -= n
        return n
    }
}
