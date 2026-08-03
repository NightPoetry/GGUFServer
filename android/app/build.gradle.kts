import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名密码等敏感值不进 git：从仓库根目录 config/secrets/keystore.properties 读取
// （模板见 config/keystore.properties.example，该目录已被 .gitignore 排除）。
val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("../config/secrets/keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.ggufserver.app"
    compileSdk = 35
    // 指定 NDK，让 AGP 能找到 llvm-strip 在 release 构建时剥离 jniLibs 里 .so 的调试符号
    // （否则会 "Unable to strip ... packaging them as they are"，正式包白白大出 ~20MB）。
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.ggufserver.app"
        minSdk = 28
        targetSdk = 35
        // 版本规则：每次发正式包 versionName +0.0.1、versionCode +1，旧 APK 按版本号留档（见 docs/打包流程.md）
        // versionNote: **插件消息角标支持"条件显示"（requiresCard），修乐句"下载"角标乱挂的问题**。反馈：多插件同时启用后，消息下方角标会跟着每条消息一起堆出来，即使这条消息用不上——典型例子是乐句插件的"下载"角标，此前只要是 assistant 消息、`roles` 匹配就无条件显示，哪怕这条回复根本不是一段可播放的乐句、没有 .mid 可导出，点了也没意义。修法：manifest 的 `messageActions[]` 每一项新增可选字段 `requiresCard`（默认 false，向后兼容——已有的朗读角标 `tts.speak` 不用改，任何文字都能读，保持无条件按 `roles` 显示）。声明 `requiresCard: true` 的角标，只有在这条消息的卡片确实由本插件渲染（`msg.cardPlugin == 本插件 id`，§6.12）时才出现。乐句 `midi.download` 据此声明 `requiresCard: true`（1.3.3→1.3.4）。真机验证：同一助手回合里，紧邻乐句卡片的纯文字消息不再挂"下载"，卡片本身正常挂"下载"；卡片之后新发的纯文字消息也不受"会话里出现过卡片"影响，照常不挂——按消息逐条判断，无状态泄漏。JVM 单测（含 BuiltinManifestTest 等 39 项）与 assembleDebug/assembleRelease 均验证通过。
        // 上一版 versionNote: **第 12 个内置插件「外援」真机验收 + 修它暴露出来的一个宿主缺陷**。外援（com.gguf.relay，推理能力点 `llm`，§6.16）的代码写于 1.1.13 打包之后，所以 1.1.13 的正式包里其实没有它，**这一版才是它第一次出厂**；上一版日志里标的「⚠️ 真机未验」这次补上了。①**接管路径真机通了**：手机上说「帮我写段悲伤的旋律」→ 乐句接管 → `gguf.llm.chat` 改道进外援 → `gguf.net.fetchJson` 经宿主代取（插件页面源是 https://plugin.gguf，直接 fetch 一律撞 CORS）→ 打到局域网里的一个 OpenAI 兼容端点 → 回来的片段照常编排成可播放的卡片。整轮 1.2s（本机 1B 编曲要十几秒），端点侧收到的请求证实：`Authorization: Bearer` 带对、temperature/max_tokens 透传、`json_schema` 按设计没透传、内容确实离开了手机。②**修「不接管时没有回落」**（真机撞出来的真缺陷，也是这次验收唯一的收获）：规范 §6.16 白纸黑字写着提供者返回 `{}` = 我不接、宿主去打本机模型，而宿主的 `llmRoute` 只判 `null`——`{}` 在 Kotlin 里是个非 null 的 JSONObject，于是这个空壳被当成答案交给了调用方。而调用方多半没有「空壳」这个分支：真机上把外援配成「只服务便签」（乐句被筛掉）后说同一句话，0.28s 就回「这次没写出能用的片段，要不换个说法再试试？」——外部端点一个请求没收到，本机模型也压根没被问过。**打开一个保底插件反而把原有能力弄丢了**，正是保底最不该有的失败方向。判据改成「它有没有交出这次调用要的东西」（chat 看 content、classify 看 label），空壳一律回落本机；不写日志不记熔断，因为主动不接是常态（用户只勾了某几个插件走外援）。**注意发声（§6.13）的 `{}` 含义相反**——那边的提供者是自己把声音放完再返回 `{}`，表示「已处理」，所以 ttsRoute 保持原样，同样是空对象、两个能力点不能共用一套判断。③JVM 单测 31 → **39 项**：新增 8 项把这条纪律钉死（空对象/缺字段/空 label/空 content 都算没接管，给了 content/label 才算接管，未知动作向前兼容）。
        // 上一版 versionNote: **乐句能导出成 .mid 了**，外加两个真机撞出来的缺陷。①宿主补第 15 个能力点 `files:save` / `gguf.files.saveDownload`（§6.15）：插件在 WebView 里算得出二进制却没有任何通道交给用户（data: URL 下载在 WebView 里不可靠、storage 只存 JSON），现在经宿主写进系统「下载」（API 29+ 走 MediaStore Downloads，不需要任何运行时权限；8MB 上限、文件名消毒、同名不覆盖）。乐句 1.3.0 据此加消息角标「下载」（与朗读同一行），把乐句编成标准 MIDI（SMF format 1，轨 0 放速度元事件，其余每轨一个 GM 音色）——音符模型本来就是 MIDI 语义（音高 0–127、力度、以 tick 计时长），编码只是写成字节。自检里正经解析写出来的文件：魔数/format/division、note-on 与 note-off 配平且等于音符数、速度元事件与乐句一致、末 tick 等于 totalTicks。②**冷启宽限**：App 刚起来 service WebView 要几秒才加载完，而用户的第一句话往往紧跟着来，route 拿不到接管、能力就在第一句话上丢了（真机反复撞到）。现在分发前给一次有界等待（最多 2.5s），只等「从没就绪过」的插件——一个插件只可能有一次「从没就绪过」，不是每轮开销。③**notice 不再硬套便签的措辞**：notice 是所有插件共用的通道（§6.6），旧代码给不以「已/便签」开头的一律加「已记入便签：」前缀，于是真机上出现了「已记入便签：这段乐句没认出可用的音符」。④**route 判定改两段式：关键字门闸 + LLM 精判**（用户的提议，也是 §6.14 早就写下的方向）。手写枚举补不完——这个插件为「写…音乐」栽过三次；一放宽又会把「写个音乐播放器」误接管成作曲。正则吃得下句法、吃不下语义，所以不再指望一条正则同时高召回高精度：**高置信说法**（作曲/编曲/写段旋律/midi…）快判定直接接管、零成本；**拿不准的**（句子里有音乐味，但可能在聊音乐、可能要程序）返回 `deep:true` 向宿主申请第二遍，用独立预算（3s，不占 route 的 150ms——那 150ms 是给扫正则的，跟问模型差两个数量级）调一次 `llm.classify` 定夺；都不命中则一分钱不花。真机实测一次判断 Qwen3.5-2B 稳态 1.1s（首次 2.1s），且只落在命中关键字的少数轮次上。fail-open 方向按 route 纪律选：判不出来就不接管（宁可漏，漏了模型自己还能答，误接管是整轮被替换）。宿主侧 `AspectBus.routeUntilTakeOver` 加深判轮 + 4 项纪律单测（已接管就不再深判、没给深判预算则忽略申请、深判预算见底不派、深判带 deep 标记）。**顺带修好了 `llm.classify` 在 Qwen 上一直是废的**：它把判断指令挂成一条末尾 system，而 Qwen 模板禁止中途 system → 整个判断请求 HTTP 400 → 判断静默失败、调用方只看到「判不出来」（v1.1.12 只修了网关的注入位，classify 是第二条路径，同一个坑又踩一遍）。根因是「模板能力」这个客观事实没共享，现在抽成 `TemplateProbe` 由网关与插件桥共用；模板不吃中途 system 时判断指令改为并进末条用户消息末尾（不新增角色、任何模板都合法，前面消息一字不动）⑤**消息卡片展开后收不回去**（用户报的）：长按乐句卡片看音符会把下面的「复制/朗读/下载」推下去，再收起时卡片不缩回、留一大块空白。根因在 SDK 的高度回报量的是 `documentElement.scrollHeight`——宿主把 WebView 高度设成上报值之后，它至少等于视口高（被 WebView 自己撑着），于是「内容变矮」永远报不回去，连 ResizeObserver 都不再触发；改成量 body（block 盒，高度随内容缩得回去）并改观察 body⑥**route 面预算见底不再静默**：curate 面 2026-07-27 已补日志，route 面漏了，而它丢的是「整轮接管」——这次真机定位「乐句插件为什么没接管」为它多花了很久⑦**就绪宽限覆盖 WebView 重建**：原先只等「从没就绪过」的插件，可 App 切后台再切回来系统会回收 WebView，重建期间的第一句话照样丢接管（真机复现：切回来第一句「write a midi song」没接管、第二句就接管了）；现在重建中的也等，给一半预算
        // 上一版 versionNote: **chat 模板随模型走，宿主自动适配**。换 Qwen3.5-2B 一试就炸：凡是 curate 露了工具或 context 注入了 tail 的轮次**整轮 HTTP 400**——Qwen 的模板显式 raise_exception("System message must be at the beginning.")，而 v1.1.4 为保 KV 前缀把逐轮可变内容放在「末条用户消息之前的一条独立 system」，MiniCPM 的 chatml 吃得下、Qwen 不吃。这不是能 fail-open 掉的小事，是整轮报错。现在宿主用 llama-server 的 /apply-template 在**每个模型首次使用时探两件事**（按 alias 缓存，换模型自然重探）：①首条 system 支不支持 ②中途 system 支不支持；据此选注入位——中途 system 不支持就把逐轮内容并进末条用户消息开头（位置一样靠后，**KV 前缀同样不受影响**，这本就是当初挪位置的目的），首条 system 都不支持（Gemma 系）就把所有系统内容并进首条用户消息。探测结论写进运行日志。真机：Qwen3.5-2B 上 400 全消（「你能发语音吗」「读一下这句话」prompt=210/212 正常），日志显示「首条 system=支持，中途 system=不支持」。**顺带回答了「为什么模型不认识自己的能力」**：v1.1.11 加的能力条款在 1B 上被无视（它背通用助手清单），换 2B 后它把三条能力原样列出，还**自己吐出了 <tool name="tts.say"> / <tool name="notes.append"> 标签**——工具协议在 2B 上是通的，1B 从来不行。所以那是模型档位的上限，不是管线问题，现在有对照实证
        // 上一版 versionNote: 回答「为什么模型不认识自己的能力」这个反馈，做三件事。①**能力清单进首条 system**：便签/朗读/乐句各加一句 systemClause（「- 作曲：用户要旋律/曲子/伴奏时直接写给他，别说自己不懂音乐」等，三条合计约 106 字）。它进 head、逐轮字节稳定，所以 KV 前缀命中、只在会话首轮付一次预填——与 curate 收窄不矛盾：curate 省的是「怎么调用」的长教学（逐轮可变、走动态位），这里说的是「你会不会」，必须常在。实测「你能发语音吗」从「作为文本模型我无法发声」变成「当然可以，你希望我发什么内容」。**诚实标注**：问「你都会做什么」时 1B 仍然背通用助手清单、不读系统提示词里的能力条款（prompt 里确实有），这是模型档位的上限、不是管线问题。②**首次分发预热**：service.js 加载完即用一个 SDK 不认识的钩子名走一遍完整分发路径（SDK 对未知钩子立即 respond({})，无副作用、不记熔断），把冷启成本从用户的第一句话上挪走——真机 23:35 那轮整条管线花了 480ms，而 route 只有 150ms 预算、乐句插件排在最后一个，直接被预算地板挡掉，能力就在第一句话上丢了。③**让「能力静默丢失」可观测**：「后台脚本尚未就绪」与「本面预算已见底，本轮没轮到它」两类跳过原先只写 logcat（会滚掉，事后无从追查——这次为定位它查了很久），现在进用户可见的运行日志。插件版本：便签 1.0.6 / 朗读 1.1.6 / 乐句 1.2.9
        // 上一版 versionNote: 修乐句插件三处真机撞出来的缺陷（用户反馈「让它创造音乐时，它对自己已有的插件好像没有感觉」）：①**音乐意图漏判**——用户说「帮我写一个悲伤的调」，而正则只认「曲/旋律/乐句」不认「调」（曲调/调子），于是既没接管也没露工具，模型只能自己发挥去反问「你想要什么风格」；补上 调/调子/谱…曲/伴奏/主旋律/背景音乐/BGM，「调」用否定预查挡掉调用/调整/调试/调查/空调/语调/调休/调色等非音乐义；「使用工具」这类祈使在**刚出过乐句的会话**里也算续写意图。②**情绪夹取被 major 里的 m 骗过**——判定小调用的是 /m|min|小调/，而「C major」里有个 m，于是被当成「已经是小调」整个跳过，结果「悲伤」配了 C 大调；改成削掉音名后先排除 maj/大调再认 m/min/minor/小调，parseKey 与表头捕获同步收紧（Cmajor 曾被解析成 C 小调），14 种写法双向夹取全部正确。③**续写竞态**——上一轮的状态只落 storage，紧接着的「使用工具」赶在 storage.set 落地前到达、prev 读成空，于是另起了一首调/速度/情绪全变的新曲子；加同步内存副本（落盘那份留给 WebView 重建后用）。真机复现用户原话：悲伤→Am 小调 76BPM，「使用工具」「再来一段」接着长到 23 小节 / 0:36。语料补 15 条真实说法与「调」的非音乐义负例
        // 上一版 versionNote: 新增第 11 个内置插件「乐句」（com.gguf.midi 1.2.2）——让模型作曲，端上合成播放，一条可播放的乐句卡片。设计沿用本项目「以小博大」的路子：模型只出创意里最小的那一点（一个 4 小节片段 + 一行播放顺序），确定性的活全交给 JS（宽容解析音名/简谱、按调式自动配低音与和声、三轨音域折叠、多乐器音色、逐轨混音 + 软限幅、钢琴卷帘、Web Audio 滚动预排）。**情绪可点菜**：说「悲伤/欢快/安静/紧张…」时，调式速度音色由插件按乐理查表定并把模型矛盾的取值夹回去（模型给「悲伤 + 140BPM 大调」会被改成小调 76BPM）——这类常识属于该外置给代码的部分。**长曲子靠多轮续写而不是机械重复**：说「再来一段」时沿用同调同速同音色（风格统一）、只让模型写一个新片段（富有变化），JS 接到已有编排后面并回一次主题；真机实测 10→20→32 小节。续写的接管门是「本会话刚出过乐句」的同步标记（按会话记）——否则「再来一段」在写文章的语境里会被劫持整轮。模型交白卷时用主题做确定性变奏（移调/逆行轮换）接上并如实说明。真机验证：卡片渲染与语音卡片同构（点按播放/长按看音符）、声音已由用户耳朵确认、curate 只在要音乐时露工具、别的会话说「再来一段」不被劫持。另新增 BuiltinManifestTest：内置插件 manifest 过不了校验时会「改了却静默不释放到设备」（本次真踩到），现在编译期就能发现
        // 上一版 versionNote: params 任务分档修一个「顺序按直觉排」造成的最坏方向误判。三档 precise(0温)/creative(0.9温)/chat(0.7兜底) 的误判代价不对称：precise 型跑进 0.9 温度最严重（拿 0.9 的温度生成代码、算算术），creative 型跑进 0 温度只是干瘪，判不出来落 0.7 中间档最轻。而 ruleClassify 原本 creative 先判、precise 后判，两张关键词表都命中时谁在前谁赢 ⇒「写个函数生成随机诗句」「写个脚本把歌词按行分割」「帮我写段代码，给这些故事排个序」统统进 0.9 温度，正好是最坏的一种。改成 precise 优先（冲突时偏精确档：把诗写干一点远好过把代码写错），并补齐算术同义说法（「1+2 等于几」「算算我这个月一共花了多少钱」「15% 的税要交多少」原先都落 0.7 档而算术在 0.7 下会算错）。调参集 29/29、未参与调参的留出集零严重误判（留出集还抓到我为补算术放宽正则时的自伤：「我打算下个月搬家」「我们一共聊了挺久」被判进 0 温度，已收回）。另实测了插件自带的 LLM 判断路径（默认关）：规则漏掉的 7 条里判对 6 条、零严重误判，但每次判断 1150-1288ms，而「规则拿不准」本就包含所有普通闲聊 ⇒ 打开后每句闲聊多等 1.2s。结论是默认关正确、不改；但设置卡原文写「额外耗时约等于预填几十 token」低估了一个数量级（成本在解码标签不在预填），已按实测重写，让这个开关变成知情选择。params 插件 1.0.2；check-plugins.js 增 params 语料 29 条（含零严重误判硬门）+ 泛化集 16 条
        // 上一版 versionNote: route 接管判定收紧——把 curate 那套「带标注语料 + 指标」的方法用到 route 上，实测**误接管率 31%**（4/13）。四条误接管全是「在**谈论**便签这个功能」被当成「在**操作**它」：原判定只要求「句子里有『便签』」∧「句子里有编辑动词」，两者可以隔着十万八千里，于是「便签这个功能怎么用？我想改一下我的工作流」「帮我把这段代码改一下，顺便讲讲便签原理」「你觉得便签和备忘录哪个好用？我在考虑换成印象笔记」「便签的数据存在哪里？误删了能恢复吗」都会被整轮劫持进编辑流程——而那个流程会去改用户真实的便签。route 与 curate 的代价方向相反（curate 判错只多花点 token，route 判错是整轮被替换），所以这里 precision 第一、宁可漏接管。收紧办法不是加否定词表（列不完），而是要求**动词与便签在句法上真的挨着**：调参集与**未参与调参的泛化集**双双 precision 100% / recall 100%。顺带记下一条经验：**句法判定能靠正则、语义判定不能**——同一套方法下 route 泛化集 100%，curate 泛化集只有 42%，判定形态决定了正则的天花板。便签 1.0.4；check-plugins.js 增 route 语料 22 条（含零误接管硬门）+ 泛化集 13 条
        // 上一版 versionNote: 两块。①**定向审计修 5 个失败路径缺陷**——按 v1.1.5 那两个缺陷的病型（逐轮可变的运行结果影响本应字节稳定的东西 / 瞬时失败被记成持久惩罚 / 能力静默丢失）成建制找了一遍，5 条全部核实成立并修复：context 插件本轮超时会让它的 head 整段消失、首条 system 字节随之抖动（现改为复用它上一轮成功的 head，模型也不会凭空少掉人设或长期记忆）；串行面共享预算无下限，排在后面的插件拿到十几毫秒必然超时、而超时记进熔断账本连续三次就永久旁路且落盘（现加 MIN_VIABLE_BUDGET_MS=40，预算见底就根本不派——被饿死不是它坏了）；承上，被饿死的 curate 处理器原按「登记了却没表态」藏掉它的工具，现区分「派了但它自己坏了」与「压根没派到」，后者按弃权豁免；GATEWAY_HOOKS 漏了 curate，只登记 curate 的插件会被判成不是中间件、走零插件快路径而永不分发；reconcile 与 destroyService 的 TOCTOU 会留下「已启用却没有 service WebView」的终态、此后 dispatch 静默返回 null 且不记任何错。单测 21→24 项。②**curate 快判定按标注语料重做**
        // 同版本第二块 versionNote: curate 快判定按标注语料重做——此前从没量过判定质量，一量发现便签 recall 只有 48%、precision 100%，过严，与规范「宁可判宽」的取向正好反了。漏判是能力静默消失（用户说了件该记的事，模型手里根本没这个工具，且无任何日志），误露只白花约 40 token 教学条款，代价差着数量级。便签 1.0.3：判定从「显式记录词 ∨（时间词 ∧ 承诺动词）」改成「一个正信号 + 一道否定闸门」——闸门挡掉提问/指使干活，正信号含显式记录词（区分「记得帮我买牛奶」与「我记得你说过」）、具体钟点日期、时间∧承诺动词（动词表补 交/签/接/休假/搬家/还款）、耐久事实（密码/号码/位置/过敏/尺码/邮箱及形似凭据的串）。朗读 1.1.4：补「用声音/发个语音/念出声」等同义说法，「说出来」加伴随词闸门（想法/不好意思/憋…）区分「念出来」与「讲出来」。check-plugins.js 的 curate 段从 8+8 条冒烟样例升级成 43+27 条带标注语料 + recall/precision 指标 + recall≥90% 硬门。同时诚实记下天花板：另写了一份没参与调参的泛化集，便签在其上 recall 仅 42%（旧规则 0%）——正则吃得下结构化信号、吃不下语义判断，泛化集以「只报告不断言」入库并警告别拿它调参；语义那半边要靠把判断挪出请求路径（after 面事后判、出角标补记），已写进规范 §6.14
        // 上一版 versionNote: 修两个 KV/熔断层面的真缺陷（都由「桌面端切面反哺评估」核对纪律时撞见，不是新功能）：①教学注入位曾用「本轮 exposure 是否为 null」判定，而 null 有两种成因——「无人登记 curate」（静态）与「登记了但全员异常/超时」（逐轮可变的运行结果），后者会让首条 system 的字节随 curate 的成败逐轮抖动（违反红线 5「head 必须字节稳定」），抖动那轮连同抖回去的下一轮各重算一次整段 prefill（实测 169 token ≈ 1.9s）；判据改为「有没有插件登记 curate」，失败轮退化成「在动态位全露」，head 恒定、历史 KV 不受牵连。②App 冷启时 service WebView 有几百毫秒到几秒未就绪，此间 dispatch 石沉大海只能等超时，既白吃满钩子预算又被按「超时」记进熔断计数——而熔断状态是落盘的，冷启后连发三条请求就能把一个好插件永久旁路；现在 dispatch 遇未就绪立即返回且不记熔断，curate 面把「登记了但未就绪」按弃权处理（「没准备好」≠「坏了」：等模型几秒后吐标记时它早就绪、tool 钩子跑得动，不该藏它的工具）。另：真机补量 curate 收窄的延迟收益（省 1641ms prefill vs 45ms 判定成本，净赚约 36:1）；规范 §13.7 新增「跨宿主契约：哪些是不变量、哪些是宿主绑定」，吸收桌面端「小模型轮椅」反哺的 scope/governs 辖域契约（含 own/global 记账相反这一条关键坑）。单测 19→21 项
        // 更早 versionNote: curate 切面真正开始收窄（阶段 3.5）——便签/朗读各自登记 curate 处理器，用正则级快判定决定「本轮该不该把自己的工具露给模型」，闲聊轮不再注入工具教学（省 1B 极其金贵的 system 预算）；SDK 补上 gguf.hooks.curate（阶段 3 只做了宿主侧，JS 侧一直 undefined、插件根本注册不上）；内核补两条纪律——弃权豁免（没登记 curate 的插件的 callable 不因别人收窄而消失）+ 动态注入位（有人表态时教学条款从首条 system 改插到末条用户消息之前，否则每次意图切换整条 prompt 前缀作废、手机上要重算整段 prefill）；fail-open 升为 AspectBus 自带属性（处理器抛错一律当无返回，CancellationException 原样上抛）；heavymem 1.0.6 召回块按相关度升序（最相关贴指令）+ 计算类问答改「模型只抽数字(json_schema)、加减乘除交 JS」两段式；新增 19 项 JVM 单测（AspectBus 分发纪律/curate 语义 + ToolTagScanner 流式零泄漏，正是真机验收里两条 ⚠️ 覆盖不到的路径）与 node scripts/check-plugins.js 插件离线自检。设计见规范 §6.14/§13.4
        versionCode = 26
        versionName = "1.1.15"

        // The bundled llama-server binary is arm64 only. Virtually every phone
        // from the last ~8 years is arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }
    }

    // 发布签名：密钥库在 android/keystore/（个人项目；密码等敏感值在
    // config/secrets/keystore.properties，未配置时留空，assembleDebug 不受影响，
    // assembleRelease 会在签名步骤报错——丢了密钥就没法给老用户发同签名升级包，注意备份）
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/ggufserver-release.jks")
            storePassword = keystoreProps.getProperty("storePassword", "")
            keyAlias = keystoreProps.getProperty("keyAlias", "")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // 纯 JVM 单测（./gradlew :app:testDebugUnitTest）：只覆盖不需要真机的纯逻辑内核件——
    // 流式工具标记扫描器、切面分发组合子的 fail-open/预算/curate 语义。这些正是真机验收里
    // 「模型不肯自发吐标记」「不方便现场注错」而覆盖不到的路径（见 docs/切面微内核迁移进展.md）。
    // returnDefaultValues：内核里的 android.util.Log 在 JVM 上是 stub，让它静默返回 0 而不是抛异常。
    testOptions { unitTests.isReturnDefaultValues = true }

    packaging {
        jniLibs {
            // Keep native libs uncompressed & extracted to nativeLibraryDir so the
            // llama-server executable can actually be exec()'d at runtime.
            useLegacyPackaging = true
        }
    }
}

// 只让 Debug 包保留 .so 调试符号（真机崩溃时 logcat 直接显示函数名/行号，便于定位）；
// 正式包（release）仍按上面的 ndkVersion 自动 strip，体积小、给用户。
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.packaging.jniLibs.keepDebugSymbols.add("**/*.so")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单测：JUnit4 + 真 org.json 实现（android.jar 里的 org.json 在 JVM 上只是空壳）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
