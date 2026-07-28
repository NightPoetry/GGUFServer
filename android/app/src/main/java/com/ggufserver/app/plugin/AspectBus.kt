package com.ggufserver.app.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 切面微内核（《工具插件规范》§13）。内核唯一的原语：**在一小组具名切面（join point）上注册
 * 处理器，并按预算/权限/熔断/fail-open 分发一次调用**。UI、中间件、工具、Skill、工作流全部降级
 * 成挂在这些切面上的插件。
 *
 * 本文件是该内核的**词汇表 + 分发纪律**，不含任何业务逻辑：
 * - [Aspects]：三类切面的具名常量与每切面预算（§13.1/§13.3）。预算属于「分发面」的自带属性，
 *   不是各功能各写一遍——所以集中在这里，`Gateway` 只引用。
 * - [AspectRegistry]：把「一组启用插件」投影成「每个管线面上挂了哪些处理器」。当前是 v1 绑定
 *   （用 §2.1 的钩子名/形态字段判断），把原先散落在 `Gateway.computePlan` 里的
 *   `active.filter { "route" in it.hooks }` 收敛到一处。
 * - [AspectBus]：分发组合子（串行到接管 / 并行 / 串行逐个消费），封装共享 deadline 的预算算术。
 *   真正的单次分发（切主线程、注入 WebView、withTimeout、熔断计数、fail-open）仍在
 *   [PluginRuntime.dispatch]——它就是「分发面」的手机端唯一实现；本类只编排「按什么顺序、给谁多少
 *   预算」。换平台（桌面端「小模型轮椅」）只换 dispatch 的实现，切面契约不变。
 *
 * 阶段 1（等价重构）：只把 `computePlan` 的编排搬到这里，行为逐字节不变（同顺序、同预算、
 * 同 fail-open、同零插件快路径）。工具系统收敛成 `type:tool` 运行时插件、curate/generate 显式化
 * 是后续阶段（§13.6），届时注册面的投影（callable/ui/capability）也在 [AspectRegistry] 里补齐。
 */
object Aspects {
    // ---- 注册面（声明「提供什么」，装载登记、长期有效）----
    const val CALLABLE = "callable"     // 具名可调用能力（今 tools[] 模型调 / messageActions[] 用户点，按 type 与调用者分）
    const val UI = "ui"                 // 人直接交互的界面表面（今 ui.html 面板 / settingsCard / card.html 消息卡片）
    const val CAPABILITY = "capability" // 具名能力槽，供别人改道（今 provides[]，补丁式）

    // ---- 管线面（一次请求依次经过；零处理器即退化裸转发）。规范顺序见 PIPELINE_ORDER ----
    const val ROUTE = "route"           // 快判定 + 可接管本轮（今 route/handle）
    const val CURATE = "curate"         // 本轮给模型露哪些 callable（新，§13.4；今默认"全露"= applySystemSuffix）
    const val CONTEXT = "context"       // 注入 head/tail + 系统条款（今 context 钩子 + systemClause）
    const val PARAMS = "params"         // 按任务定采样档（今 params 钩子）
    const val GENERATE = "generate"     // 产出本轮模型输出（默认转发 llama-server；takeOver=替换 generate）
    const val STREAM = "stream"         // 令牌流经处理器（今 ToolTagScanner + notice/card 收尾 + 续段）
    const val AFTER = "after"           // 回合结束后的副作用（今 afterReply + 工具执行 + continuation）

    // ---- 分发面 ----
    const val DISPATCH = "dispatch"     // 调用一个处理器（PluginRuntime.dispatch 是其手机端唯一实现）

    /** 管线面的规范执行顺序（§13.1）。curate 排在 context 之前——它决定"露哪些"，context 才据此教模型。 */
    val PIPELINE_ORDER = listOf(ROUTE, CURATE, CONTEXT, PARAMS, GENERATE, STREAM, AFTER)

    // ---- 每切面预算（§13.3，与 §6.1 时序一致）。分发面按此 withTimeout；request 路径同步点超预算立即跳过。----
    const val ROUTE_BUDGET_MS = 150L
    /**
     * **route 深判预算**（§6.2 两段式判定）：快判定拿不准时，插件返回 `deep:true` 申请一次慢判——
     * 通常是 `llm.classify`（真机 Qwen3.5-2B 稳态约 1.1s，首次 2.1s）。不占 [ROUTE_BUDGET_MS]：
     * 那 150ms 是给「一句话扫一遍正则」的，跟「问一次模型」差两个数量级，混在一起等于永远超时。
     *
     * 为什么值得多等这一秒：route 判错的代价是不对称的——漏接管 = 能力静默消失（用户要曲子却拿到
     * 一段歌词），误接管 = 整轮被替换。正则吃得下句法、吃不下语义（§6.14 的天花板：同一套方法
     * route 泛化集 100%、curate 只有 42%），而**关键字命中的轮次本就是少数**，只在这些轮次上花钱。
     *
     * 取 6s 而不是 3s：稳态 1.1s，但**模型加载后的第一次推理是冷的**（权重还没进 page cache），
     * 真机实测 3.4s——3s 预算下第一轮深判必然超时，等于「刚开机那几句话照样判不出来」，正是
     * 这个功能最该管住的场景。超时仍按 fail-open 不接管。
     */
    const val DEEP_ROUTE_BUDGET_MS = 6_000L
    const val CURATE_BUDGET_MS = 150L      // 与 route 同属请求路径同步点、同为快判定（禁调 LLM）
    const val CONTEXT_BUDGET_MS = 250L
    // params：JS 规则路径毫秒级返回；LLM 判断路径 = 前缀扩展分类（判断请求顺手预填了本轮对话前缀，
    // 是回答本来要付的工作），冷缓存长对话可能吃满预算 → 给足并 fail-open（§6.10）。
    const val PARAMS_BUDGET_MS = 20_000L
    const val HANDLE_BUDGET_MS = 120_000L  // route 接管一整回合
    const val TOOL_BUDGET_MS = 10_000L     // 单个 callable 执行
    const val AFTER_REPLY_BUDGET_MS = 10_000L
    const val CONTINUATION_BUDGET_MS = 90_000L // 同轮召回(记忆模型)+续段生成(前台模型)，都在手机上
}

/**
 * 把一组启用插件投影成「每个管线面上挂了哪些处理器」。当前用 §2.1 的 v1 绑定判断（钩子名/形态字段）。
 * 后续阶段工具系统内插件化后，`toolCallables` 会改由 `type:tool` 运行时插件在 curate/stream/after 面认领。
 */
class AspectRegistry(
    active: List<PluginManifest>,
    hasNativeTools: Boolean,
    /** 受限解码请求（json_schema/grammar）跳过 params 面：参数由调用方定型（§6.10 判定保护）。 */
    constrained: Boolean,
    /** 该插件的 service WebView 此刻是否**已就绪**（能接住一次 dispatch）。默认全就绪：
     *  单测与无宿主环境不关心冷启。见 [curate] / [curateAbstained] 对「没准备好 ≠ 坏了」的处理。 */
    ready: (PluginManifest) -> Boolean = { true },
) {
    val route: List<PluginManifest> = active.filter { "route" in it.hooks }

    /** curate 面的**登记者**（静态，只看 manifest hooks）。**教学注入位只能看它**——位置若随
     *  「本轮谁在线」变，首条 system 的字节就会逐轮抖动（§10 红线 5，见 Gateway.applySystemSuffix）。 */
    val curateRegistered: List<PluginManifest> = active.filter { "curate" in it.hooks }

    /** 本轮真正去分发的 curate 处理器：登记了**且此刻就绪**。 */
    val curate: List<PluginManifest> = curateRegistered.filter(ready)

    /**
     * curate 面的**弃权者**：其 callable 无条件并入暴露集（§13.4 纪律 2），见 [AspectBus.curateExposure]。
     *
     * 两类人：①没登记 curate 的（原语义）；②**登记了但此刻 service WebView 还没就绪的**（App 冷启
     * 头几秒）。第二类是 2026-07-27 补的：纪律 3「登记了却没表态 ≠ 弃权」的理由是「插件坏了它的
     * `tool` 钩子同样跑不了，教了也是白教」——这条推理**对没准备好的插件不成立**：等模型几秒后
     * 吐出标记时它早就绪了，`tool` 钩子跑得动，凭什么这一轮把它的工具藏起来。
     */
    val curateAbstained: List<PluginManifest> = active.filter { p -> curate.none { it.id == p.id } }
    val context: List<PluginManifest> = active.filter { "context" in it.hooks }
    val params: List<PluginManifest> = if (constrained) emptyList() else active.filter { "params" in it.hooks }
    val afterReply: List<PluginManifest> = active.filter { "afterReply" in it.hooks }
    val continuation: List<PluginManifest> = active.filter { "continuation" in it.hooks }

    /** callable(type=tool) 的 v1 绑定：声明了 tools[]，且本次不是客户端自带原生 OpenAI tools 请求（§6.9 透传）。 */
    val toolCallables: List<PluginManifest> = if (hasNativeTools) emptyList() else active.filter { it.tools.isNotEmpty() }
}

/**
 * 管线面的分发组合子。每个方法封装一种「共享 deadline 的预算算术」，把单次分发交给调用方传入的
 * [invoke]（通常是 `PluginRuntime.dispatch(id, hook, payload, remain)`）——本类不认识 PluginRuntime，
 * 只负责编排顺序与预算，因此换平台只需在 `invoke` 里换一种 dispatch 实现。
 *
 * fail-open 语义：[invoke] 返回 null（超时/异常/插件不可用）一律当「该处理器本轮无返回」，绝不阻断。
 */
object AspectBus {
    /**
     * fail-open 的**内核侧**兜底（§13.3）：任何一次处理器调用抛出的异常都降级成「该处理器本轮无返回」，
     * 绝不阻断管线。`PluginRuntime.dispatch` 自身已 catch 并记熔断，这里再兜一层是因为
     * fail-open 是**分发面自带属性**、不该依赖某个 invoke 实现的自觉——换平台（桌面端进程内 JS /
     * 子进程）时新的 dispatch 实现照样受这条纪律保护，编排层的意外（如 payload 构造抛错）也不会
     * 把一次普通聊天变成 500。
     *
     * 协程取消（[CancellationException]）必须原样上抛：那是「调用方不要这个结果了」，不是插件故障，
     * 吞掉会让结构化并发失效（parallel 的兄弟协程/整轮请求取消不掉）。
     */
    private suspend fun guard(
        p: PluginManifest,
        remain: Long,
        invoke: suspend (PluginManifest, Long) -> JSONObject?,
    ): JSONObject? = try {
        invoke(p, remain)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        android.util.Log.w("GGUF-gw", "aspect dispatch ${p.id} 异常，按无返回继续：${e.message}")
        null
    }

    /**
     * **可用预算下限**（2026-07-27 补）：串行面共享一个 deadline，排在后面的处理器只拿得到剩下的零头。
     * 一次分发的固定开销是「切主线程 → `evaluateJavascript` → JS 执行 → `respond` 回程」两跳 looper，
     * 剩 10ms 派下去必然超时——而超时会被 `PluginRuntime.dispatch` 记进**熔断账本**，连续三次就把插件
     * 永久旁路（熔断状态落盘）。**被同面的前一个处理器饿死 ≠ 这个插件坏了**，跟「没准备好 ≠ 坏了」
     * （§13.4 纪律 3 补注）是同一条道理，所以剩余预算低于下限时**根本不派**，也就不会记成它的错。
     *
     * 取 40ms：真机实测稳态一次 route/curate 往返约 45ms（含两个插件），单个来回压到 40ms 已是下限。
     */
    const val MIN_VIABLE_BUDGET_MS = 40L

    /**
     * 一次**冷** WebView 往返的实测上界（真机 190ms：App 刚起来、主线程还在渲染 Compose，
     * `evaluateJavascript` 要排在 UI 工作后面）。稳态只要 45ms——保底期按冷的给，否则补问照样超时，
     * 等于白补（这个坑真机撞了两次：先是没保底，后是保底只给了 80ms）。
     */
    const val COLD_ROUNDTRIP_MS = 250L

    /**
     * route 保底期的总上限。route 面漏接管 = 整轮能力消失，值得为它多花这些毫秒；但也不能没边，
     * 500ms 够补问两个处理器（route 处理器本来就少），再多的仍按「没轮到」记日志。
     */
    const val ROUTE_GRACE_MS = 500L

    /**
     * route 语义：串行分发，共享 [budgetMs] deadline，第一个返回 `takeOver:true` 的处理器接管本轮
     * （后续不再询问）。返回接管者，或 null（无人接管 / 无处理器 / 预算耗尽）。
     *
     * **两段式判定**（§6.2，2026-07-28 加）：快判定这一遍只准跑正则级判断，谁也不许调 LLM——它是
     * 请求路径同步点，150ms 是给「扫一遍句子」的。拿不准的处理器返回 `deep:true` 申请第二遍，
     * 第二遍拿 [deepBudgetMs] 的**独立预算**，可以调 `llm.classify`。
     *
     * 这解决的是关键字判定的天花板：手写枚举总会漏掉最朴素的说法（乐句插件为「写…音乐」栽过三次），
     * 而放宽枚举又会把「写个音乐播放器」这类代码请求误接管成作曲。宽关键字负责召回、LLM 负责精度，
     * 代价只落在**命中关键字的少数轮次**上。
     */
    suspend fun routeUntilTakeOver(
        handlers: List<PluginManifest>,
        budgetMs: Long,
        deepBudgetMs: Long = 0L,
        invoke: suspend (p: PluginManifest, remain: Long, deep: Boolean) -> JSONObject?,
    ): PluginManifest? {
        if (handlers.isEmpty()) return null
        val deadline = System.currentTimeMillis() + budgetMs
        // **保底期**（2026-07-28）：预算见底时不是直接不派，而是再给每个没被问过的处理器一次
        // 最小可用预算，总共不超过再来一份 budgetMs。理由是代价不对称——route 丢的是「整轮接管」
        // （用户要曲子、拿到一段模型自己编的音名文本），而多花的是几十毫秒首字延迟。
        // 真机撞到的就是这个：App 刚起来主线程还在渲染，排在前面的插件一次 WebView 往返就吃掉
        // 190ms，排在后面的乐句永远轮不到——「开 App 说的第一句话丢接管」的真凶是它，不是没就绪。
        val hardDeadline = deadline + ROUTE_GRACE_MS
        var overrunLogged = false
        val wantDeep = ArrayList<PluginManifest>()
        for ((i, p) in handlers.withIndex()) {
            var remain = deadline - System.currentTimeMillis()
            if (remain < MIN_VIABLE_BUDGET_MS) {
                val spare = hardDeadline - System.currentTimeMillis()
                if (spare < COLD_ROUNDTRIP_MS) {
                    // 保底也用尽了：这才是真的没轮到。**留痕**——静默跳过等于能力凭空消失，
                    // 事后无从追查（curate 面 2026-07-27 已补日志，route 面一直漏着）。
                    com.ggufserver.app.ServerBus.appendLog(
                        "[网关] route 面预算与保底都已用尽（${budgetMs}+${ROUTE_GRACE_MS}ms），本轮没轮到：" +
                            handlers.drop(i).joinToString("、") { it.id } + "（它们的接管能力本轮缺席）",
                    )
                    break
                }
                if (!overrunLogged) {
                    overrunLogged = true
                    com.ggufserver.app.ServerBus.appendLog(
                        "[网关] route 面 ${budgetMs}ms 预算已见底，转保底逐个补问（漏掉接管比多等几十毫秒贵）：" +
                            handlers.drop(i).joinToString("、") { it.id },
                    )
                }
                remain = minOf(spare, COLD_ROUNDTRIP_MS)
            }
            val r = guard(p, remain) { pp, rr -> invoke(pp, rr, false) }
            if (r?.optBoolean("takeOver") == true) return p
            if (r?.optBoolean("deep") == true) wantDeep += p
        }
        if (wantDeep.isEmpty() || deepBudgetMs < MIN_VIABLE_BUDGET_MS) return null

        // ---- 第二遍：慢判。用户会实打实地多等这段时间，所以**必须留痕**（延迟要可解释）----
        com.ggufserver.app.ServerBus.appendLog(
            "[网关] route 快判定拿不准，转深判（最多 ${deepBudgetMs}ms）：" + wantDeep.joinToString("、") { it.id },
        )
        val deepDeadline = System.currentTimeMillis() + deepBudgetMs
        for ((i, p) in wantDeep.withIndex()) {
            val remain = deepDeadline - System.currentTimeMillis()
            if (remain < MIN_VIABLE_BUDGET_MS) {
                com.ggufserver.app.ServerBus.appendLog(
                    "[网关] 深判预算已见底，没轮到：" + wantDeep.drop(i).joinToString("、") { it.id },
                )
                break
            }
            val r = guard(p, remain) { pp, rr -> invoke(pp, rr, true) }
            if (r?.optBoolean("takeOver") == true) return p
        }
        return null
    }

    /**
     * context 语义：并行分发，共享 [budgetMs] deadline，每个处理器拿「此刻的剩余预算」，超时=null。
     * 返回 (处理器, 结果?) 列表，顺序与 [handlers] 一致（供调用方按管理页顺序拼接）。
     */
    suspend fun parallel(
        handlers: List<PluginManifest>,
        budgetMs: Long,
        invoke: suspend (PluginManifest, Long) -> JSONObject?,
    ): List<Pair<PluginManifest, JSONObject?>> {
        if (handlers.isEmpty()) return emptyList()
        val deadline = System.currentTimeMillis() + budgetMs
        return coroutineScope {
            handlers.map { p ->
                async {
                    val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                    // 并行面理论上大家同时起跑、都拿得到接近满额的预算；真出现零头（协程调度被主线程
                    // 拖住）时同样不派，别把「没派下去」记成这个插件超时（见 MIN_VIABLE_BUDGET_MS）。
                    p to (if (remain < MIN_VIABLE_BUDGET_MS) null else guard(p, remain, invoke))
                }
            }.awaitAll()
        }
    }

    /**
     * curate 语义（§13.4）：串行快判定，各处理器 `expose[]` 取**并集**算出本轮暴露集。
     *
     * 四条内核纪律：
     * 1. **无人登记 → null = 全露**（默认等价 v1，curate 只是收窄能力，不改默认）。
     * 2. **表态即计入**：返回了 `expose` 字段就算表态，哪怕是空数组（= 本轮我这边啥都别露）。
     *    一个处理器都没表态（全超时/全异常/都没给 expose）→ 仍是 null = 全露（fail-open）。
     * 3. **弃权豁免**：[abstained] 的 callable 无条件并入暴露集。收窄是「我对我的能力表态」，
     *    不是「替沉默的人做决定」——否则装一个带 curate 的插件会静默掐掉别家插件的工具，
     *    这与 fail-open 同源：不参与决策者不该被决策伤害。弃权者含**未就绪**者，见
     *    [AspectRegistry.curateAbstained]（「没准备好」≠「坏了」）。
     * 4. **给模型看的东西必须自洽**：暴露集算出后，凡「让模型知道有什么可用」的通道（教学条款、
     *    能力清单、参数 enum、受限解码语法白名单）都得用同一份，否则会出现「清单里没有、enum
     *    里却有」的自相矛盾提示。手机今天只有教学一条通道，阶段 4 加 skill/workflow 时生效。
     */
    suspend fun curateExposure(
        handlers: List<PluginManifest>,
        abstained: List<PluginManifest>,
        budgetMs: Long,
        invoke: suspend (PluginManifest, Long) -> JSONObject?,
    ): Set<String>? {
        if (handlers.isEmpty()) return null
        val exposed = LinkedHashSet<String>()
        var anyOpinion = false
        // 预算见底、压根没派到的处理器：**没机会表态 ≠ 弃权失败**，按弃权处理（纪律 3 的第二个例外，
        // 与「没准备好」同源）。注意区分：真的派下去了、它自己抛异常/超时的，仍按纪律 3 藏它的工具。
        val neverAsked = ArrayList<PluginManifest>()
        serialForEach(
            handlers, budgetMs, invoke,
            onSkipped = { neverAsked += it },
        ) { _, r ->
            val arr = r.optJSONArray("expose") ?: return@serialForEach
            anyOpinion = true
            for (i in 0 until arr.length()) arr.optString(i).trim().ifEmpty { null }?.let { exposed += it }
        }
        if (!anyOpinion) return null
        for (rt in Runtimes.all) {
            for (p in rt.callablesIn(abstained)) exposed += rt.callableNamesOf(p)
            for (p in rt.callablesIn(neverAsked)) exposed += rt.callableNamesOf(p)
        }
        return exposed
    }

    /**
     * params 语义：串行分发，共享 [budgetMs] deadline，每个非空结果交给 [onResult] 就地消费
     * （如白名单合并采样参数）。[invoke] 返回 null 的处理器跳过。
     */
    suspend fun serialForEach(
        handlers: List<PluginManifest>,
        budgetMs: Long,
        invoke: suspend (PluginManifest, Long) -> JSONObject?,
        /** 预算见底、**根本没派给它**的处理器（不是它的错，见 [MIN_VIABLE_BUDGET_MS]）。 */
        onSkipped: (PluginManifest) -> Unit = {},
        onResult: (PluginManifest, JSONObject) -> Unit,
    ) {
        if (handlers.isEmpty()) return
        val deadline = System.currentTimeMillis() + budgetMs
        var starved = false
        for (p in handlers) {
            val remain = deadline - System.currentTimeMillis()
            if (starved || remain < MIN_VIABLE_BUDGET_MS) {
                starved = true            // 预算见底：后面的一个都不派，逐个记成「没机会表态」
                // 同样是「能力静默丢失」：这个插件本轮压根没被问到，用户可见的日志里要有痕迹
                com.ggufserver.app.ServerBus.appendLog("[插件:${p.id}] 跳过：本面预算已见底，本轮没轮到它（不计熔断）")
                onSkipped(p)
                continue
            }
            val r = guard(p, remain, invoke) ?: continue
            try {
                onResult(p, r)
            } catch (e: Throwable) {
                // 消费侧（如采样参数合并、expose 并集）出错同样按「该处理器无返回」处理，不牵连整轮
                android.util.Log.w("GGUF-gw", "aspect result ${p.id} 消费异常，已跳过：${e.message}")
            }
        }
    }
}
