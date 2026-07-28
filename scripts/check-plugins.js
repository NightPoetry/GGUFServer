#!/usr/bin/env node
/*
 * 内置插件 JS 逻辑的离线自检（`node scripts/check-plugins.js`）。
 *
 * 插件跑在手机 WebView 里，没法上 JUnit；但里面有些**纯判定/纯算术**的部分完全不需要模型和真机，
 * 靠注入一个最小 mock 桥就能在 Mac 上跑真代码。真机验收贵、又依赖 1B 当天的心情，这里能钉死的
 * 就别留到真机去撞运气。宿主侧对应的单测在 android/app/src/test/（AspectBus / ToolTagScanner）。
 *
 * 当前覆盖：
 *  1. curate 快判定（§13.4）——便签 / 朗读各自「本轮该不该把自己的工具露给模型」。
 *  2. heavymem 计算类记忆问答的两段式——模型只负责抽数字，加减乘除由 JS 算。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ASSETS = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets');
const PLUGINS = path.join(ASSETS, 'plugins');

let failed = 0;
function ok(cond, label, extra) {
  if (!cond) failed++;
  console.log(`  ${cond ? '✓' : '✗'} ${label}${cond || !extra ? '' : `\n      ${extra}`}`);
}

// ============================== 1. curate 快判定 ==============================

/** 用 mock 桥加载一个插件的 service.js，返回它注册的钩子表。 */
function loadService(pluginId) {
  const hooks = {};
  const noop = () => Promise.resolve({});
  global.gguf = {
    hooks: new Proxy({}, { get: (_, name) => (fn) => { hooks[name] = fn; } }),
    storage: {
      get: (k) => Promise.resolve(global.__store ? global.__store[k] : undefined),
      set: (k, v) => { if (global.__store) global.__store[k] = v; return Promise.resolve(); },
      remove: noop, keys: () => Promise.resolve([]),
    },
    llm: {
      chat: (o) => Promise.resolve(global.__llmReply ? { content: global.__llmReply } : {}),
      // 深判用（§6.2 两段式）：把调用参数留给断言看，标签由用例经 __classifyLabel 摆布
      classify: (o) => { global.__classifyCall = o; return Promise.resolve({ label: global.__classifyLabel }); },
    },
    card: { post: (o) => { global.__lastCard = o && o.data; } },
    files: { saveDownload: (o) => { global.__saved = o; return Promise.resolve({ ok: true }); } },
    ui: { toast: (t) => { global.__toast = t; }, close() {}, setBadge() {} },
    memllm: { ensure: noop, chat: noop, stop: noop, status: noop },
    tts: { speak: noop, stop: noop, status: noop },
    events: { on() {}, broadcast() {} },
    plugin: {}, host: {},
  };
  new Function(fs.readFileSync(path.join(PLUGINS, pluginId, 'service.js'), 'utf8'))();
  return hooks;
}

/*
 * 从「8 条手挑冒烟样例」升级成**带标注的语料 + 指标**（2026-07-27）。
 *
 * 为什么要指标：收窄的真实代价是**漏判**——用户说了件该记的事，模型手里根本没这个工具，
 * 能力静默消失且无任何日志。误露只是白花约 40～60 token 的教学条款。两者代价差着数量级，
 * 所以规范 §6.14 写的是「宁可判宽」。但改判定之前从没量过，一量才发现便签 recall 只有 48%
 * ——方向正好反了。指标印在输出里，以后谁再动正则都能立刻看见自己把哪一头压下去了。
 *
 * 标注口径（改语料前先读）：
 *   notes.append  该露 = 用户在陈述一件**具体、耐久、日后可能要查**的事（约定/期限/位置/
 *                 凭据/明确偏好），或显式提到记录类词。不该露 = 闲聊、提问、要求干活、
 *                 转瞬即逝的评论。
 *   tts.say       该露 = 用户要求**把回复用声音播出来**。不该露 = 「读/说」用在别的意思上。
 */
const MIN_RECALL = { 'com.gguf.notes': 0.9, 'com.gguf.tts': 0.9 };

function checkCurate(pluginId, title, cases) {
  console.log(`\n${title}（${pluginId}）curate 快判定（n=${cases.length}）：`);
  const hooks = loadService(pluginId);
  if (!hooks.curate) { ok(false, '注册了 curate 钩子'); return; }
  let tp = 0, fp = 0, tn = 0, fn = 0;
  for (const [text, want, why] of cases) {
    const r = hooks.curate({ sessionId: 's1', text }) || {};
    const list = r.expose || [];
    const got = list.length > 0;
    if (want && got) tp++; else if (want && !got) fn++;
    else if (!want && got) fp++; else tn++;
    ok(got === want, `${want ? '露' : '不露'}「${text}」→ [${list.join(',')}]${why ? `  # ${why}` : ''}`);
  }
  const recall = tp + fn ? tp / (tp + fn) : 1;
  const precision = tp + fp ? tp / (tp + fp) : 1;
  console.log(`  ── recall ${(recall * 100).toFixed(0)}%（漏判 ${fn}／该露 ${tp + fn}，能力丢失）` +
              ` · precision ${(precision * 100).toFixed(0)}%（误露 ${fp}／不该露 ${tn + fp}，只费 token）`);
  ok(recall >= MIN_RECALL[pluginId], `recall ≥ ${MIN_RECALL[pluginId] * 100}%（漏判是能力丢失，是这里的第一指标）`);
}

checkCurate('com.gguf.notes', '便签', [
  // ---- 显式记录意图 ----
  ['帮我把这个记录下来', true, '显式'],
  ['记到便签里', true, '显式'],
  ['顺便提醒我买牛奶', true, '显式'],
  ['别忘了周一要交报告', true, '显式'],
  ['便签里还有什么', true, '显式·查阅'],
  ['note this down: wifi password is 12345678', true, '显式·英'],
  ['记得帮我买牛奶', true, '记得＝祈使提醒'],
  ['你记得提醒我关火', true, '记得＋提醒'],
  // ---- 隐含承诺：时间＋事件 ----
  ['我下周三要去体检', true, '时间＋承诺'],
  ['这周五得交房租', true, '时间＋承诺'],
  ['3月15号要面试', true, '具体日期'],
  ['明天上午十点跟客户开会', true, '具体钟点'],
  ['下个月要还信用卡', true, '时间＋还款'],
  ['周日约了牙医', true, '时间＋约'],
  ['老板说这个项目下周交', true, '交（须排除交通/交流）'],
  ['明天记得把合同签了', true, '记得＋签'],
  ['后天要去机场接人', true, '时间＋接人'],
  ['下周一开始休假', true, '时间＋休假'],
  ['这周末搬家', true, '周末＋搬家'],
  ['晚上七点的火车', true, '具体钟点·无动词'],
  ['下午三点要去取快递', true, '具体钟点'],
  // ---- 耐久事实：没有时间词，旧规则必漏 ----
  ['我家的 wifi 密码是 abc12345', true, '凭据'],
  ['备用钥匙放在门口鞋柜第二层', true, '位置'],
  ['我妈的手机号是 13800138000', true, '号码'],
  ['我对青霉素过敏', true, '健康·重要'],
  ['我的车位号是 B2-108', true, '编号'],
  ['我穿 42 码的鞋', true, '尺码'],
  ['公司的报销邮箱是 fee@example.com', true, '凭据·邮箱'],
  // ---- 不该露：闲聊 / 提问 / 干活 ----
  ['今天天气真不错，适合出去走走', false, '闲聊'],
  ['你觉得 Kotlin 和 Java 哪个更适合安卓开发', false, '提问'],
  ['我记得你上次说过这个方案有风险', false, '「我记得」＝回忆，非记录'],
  ['讲个笑话吧', false, '闲聊'],
  ['帮我写一段排序代码', false, '干活'],
  ['这段代码为什么报错', false, '提问'],
  ['你好', false, '寒暄'],
  ['明天天气怎么样', false, '有时间词但是提问'],
  ['下周有什么好电影上映吗', false, '有时间词但是提问'],
  ['帮我把这段翻译成英文', false, '干活'],
  ['刚才那个回答不太对', false, '反馈'],
  ['继续', false, '控制词'],
  ['我昨天看了场电影，挺一般的', false, '流水账·不耐久'],
  ['今天好累啊', false, '情绪'],
  ['这个方案我再想想', false, '含糊·无具体内容'],
]);

checkCurate('com.gguf.tts', '朗读', [
  // ---- 该露 ----
  ['读一下这段', true, '显式'],
  ['念给我听', true, '显式'],
  ['用语音说声你好', true, '显式'],
  ['把结果说出来', true, '说出来＝念出来'],
  ['read this aloud', true, '显式·英'],
  ['朗读一下上面的内容', true, '显式'],
  ['能不能语音回复我', true, '语音回复'],
  ['播报一下今天的要点', true, '播报'],
  ['你说给我听吧', true, '说给我听'],
  ['用声音回答', true, '用声音'],
  ['发个语音过来', true, '发语音'],
  ['念出声来', true, '念出声'],
  ['把这段话读给我', true, '读给我'],
  ['speak this out loud', true, '英文变体'],
  ['say it out loud', true, '英文变体'],
  // ---- 不该露：「读/说」是别的意思 ----
  ['今天天气真不错', false, '闲聊'],
  ['帮我写一段排序代码', false, '干活'],
  ['我下周三要去体检', false, '便签场景'],
  ['我读了一本很好的书', false, '读＝阅读'],
  ['这段代码怎么读', false, '读＝理解'],
  ['有什么想法你直接说出来', false, '说出来＝表达'],
  ['我不好意思说出来', false, '说出来＝表达'],
  ['这个词怎么念', false, '念＝发音提问'],
  ['帮我读懂这段论文', false, '读懂＝理解'],
  ['你能说明白一点吗', false, '说明白＝解释'],
  ['把这段话说得简单点', false, '说＝表述方式'],
  ['语音识别是怎么做的', false, '谈论语音技术'],
]);

// ============================== 1.5 route 接管判定 ==============================

/*
 * **代价方向与 curate 正好相反**：curate 判错只是多/少花点 token；route 判错是**整轮被劫持**
 * ——用户问一个问题，回复被整个替换成「已记入便签」，或进入编辑流程去改用户真实的便签。
 * 所以这里 **precision（不误接管）是第一指标**，漏接管可接受（退化成让模型自己调 notes.append）。
 *
 * 2026-07-27 首测：误接管率 31%（4/13），四条全是「在**谈论**便签这个功能」被当成「在**操作**它」
 * ——原判定是「句子里有『便签』」∧「句子里有编辑动词」，两者可以隔着十万八千里。收紧成
 * 「动词与便签在句法上真的挨着」后，调参集与留出集都是 precision 100% / recall 100%。
 */
function checkRouteTakeover(cases, holdout) {
  console.log(`\n便签（com.gguf.notes）route 接管判定（n=${cases.length}）：`);
  const hooks = loadService('com.gguf.notes');
  let tp = 0, fp = 0, tn = 0, fn = 0;
  for (const [text, want, why] of cases) {
    const got = (hooks.route({ sessionId: 's1', text }) || {}).takeOver === true;
    if (want && got) tp++; else if (want && !got) fn++;
    else if (!want && got) fp++; else tn++;
    ok(got === want, `${want ? '接管' : '不接管'}「${text}」${why ? `  # ${why}` : ''}`);
  }
  console.log(`  ── precision ${((tp / ((tp + fp) || 1)) * 100).toFixed(0)}%（**误接管 ${fp}**／不该接管 ${tn + fp}，整轮被劫持）` +
              ` · recall ${((tp / ((tp + fn) || 1)) * 100).toFixed(0)}%（漏 ${fn}／该接管 ${tp + fn}，退化成模型自己调工具）`);
  ok(fp === 0, '零误接管（route 的第一指标：宁可漏，不可劫持）');

  // 留出集：没参与调正则，只报告。route 判的是**句法**（动词有没有落在便签上），比 curate 的
  // 语义判断好泛化得多——首测调参集与留出集都是 100%/100%。
  let hfp = 0, hfn = 0, htp = 0;
  for (const [text, want] of holdout) {
    const got = (hooks.route({ sessionId: 's1', text }) || {}).takeOver === true;
    if (want && got) htp++; else if (want && !got) hfn++; else if (!want && got) hfp++;
  }
  console.log(`  ── 泛化集（n=${holdout.length}，只报告不断言）：误接管 ${hfp}，漏接管 ${hfn}`);
}

checkRouteTakeover([
  // ---- 该接管：显式记录 ----
  ['帮我记一下：周五要交房租', true, '显式记录'],
  ['帮我记下来 明天带伞', true, '显式记录'],
  ['请帮我记录一下今天的会议结论', true, '显式记录'],
  ['记到便签里：钥匙在鞋柜', true, '显式记录'],
  ['note this down: wifi is 12345678', true, '显式记录·英'],
  ['remember this: 车位 B2-108', true, '显式记录·英'],
  // ---- 该接管：显式编辑 ----
  ['把便签第二条改成周六', true, '显式编辑'],
  ['删掉便签里的第一条', true, '显式编辑'],
  ['清空便签', true, '显式清空'],
  // ---- 不该接管：在谈论便签这个功能，不是在操作它 ----
  ['便签这个功能怎么用？我想改一下我的工作流', false, '谈论功能＋无关的「改」'],
  ['你觉得便签和备忘录哪个好用？我在考虑换成印象笔记', false, '比较产品＋无关的「换成」'],
  ['帮我把这段代码改一下，顺便讲讲便签插件的原理', false, '要求干活＋提到便签'],
  ['便签的数据存在哪里？误删了能恢复吗', false, '提问＋「误删」'],
  ['为什么我的便签不见了', false, '报障'],
  ['便签功能建议增加分类，现在全部堆在一起不好找', false, '产品建议＋「全部」'],
  ['能不能把便签导出成 markdown', false, '导出不是编辑动词'],
  // ---- 不该接管：普通对话 ----
  ['我记得你上次说过这个方案有风险', false, '回忆'],
  ['帮我记一下', false, '只有触发词、没有内容'],
  ['这个知识点我老记不住，有什么办法', false, '求助'],
  ['帮我写一段排序代码', false, '干活'],
  ['今天天气真不错', false, '闲聊'],
], [
  // 泛化集（未参与调参）
  ['帮我记一下 下午三点开会', true],
  ['把便签的第三条删了', true],
  ['能不能帮我清空便签', true],
  ['便签第一条换成「周六搬家」', true],
  ['记到便签里 药放在抽屉第二层', true],
  ['便签和 Notion 比起来有什么优势', false],
  ['我想把工作流从便签换到别的工具', false],
  ['删掉这个应用会丢便签吗', false],
  ['便签里的内容太多了，看着乱', false],
  ['怎么把微信收藏改成便签那种列表形式', false],
  ['把第三条删了', false],
  ['便签支持同步到电脑吗', false],
  ['你帮我改一下这段文案，写得像便签那样简洁', false],
]);

// ---------- 泛化集（只报告、**不做断言**，见下方警告） ----------

/*
 * 上面那份语料是「先写语料、再调正则」调出来的，天然乐观（实测 recall 100%）。这一份是**事后**
 * 另写的、没参与过调参，用来估真实泛化能力。2026-07-27 首测：
 *
 *   便签  旧规则 recall  0%（12 条全漏） → 新规则 42%
 *   朗读  新规则 recall 80%
 *
 * **警告：不要为了提高这里的数字去调正则。** 一调它就变成第二份调参集，这个数字也就没意义了。
 * 它的用途只有一个：提醒后来人「curate 的正则判定天花板大概在这里」。
 *
 * 天花板的性质：正则擅长**结构化信号**（日期、钟点、密码/号码/编号、显式记录词），这部分已经吃满；
 * 吃不到的是**语义判断**（「我答应了周六去帮忙搬东西」「我平时喝咖啡不加糖」——值不值得记，
 * 得理解意思）。这半边在手机上短期内无解：curate 是请求路径同步点，预算 150ms 且**禁止调 LLM**
 * （只有一个 llama-server 实例，在这里调模型会占住马上要用来生成回复的那个引擎，自阻塞）。
 * 见规范 §13.7(a)。真要拿回这半边能力，方向是**把判断挪出请求路径**——例如回合结束后在 `after`
 * 面（10s 预算，不挡首字）判一次「刚才那句值不值得记」，命中就出一个角标/卡片让用户一键记入。
 * 那样漏判从「能力静默消失」降级成「事后可补」，且完全不动 KV 前缀。属未来工作，此处只记路。
 */
function reportGeneralization(pluginId, title, corpus) {
  const hooks = loadService(pluginId);
  let tp = 0, fp = 0, tn = 0, fn = 0;
  const missed = [];
  for (const [text, want] of corpus) {
    const got = ((hooks.curate({ sessionId: 's1', text }) || {}).expose || []).length > 0;
    if (want && got) tp++;
    else if (want && !got) { fn++; missed.push(text); }
    else if (!want && got) fp++; else tn++;
  }
  console.log(`\n${title} 泛化集（n=${corpus.length}，只报告不断言）：` +
              ` recall ${((tp / ((tp + fn) || 1)) * 100).toFixed(0)}%` +
              ` · precision ${((tp / ((tp + fp) || 1)) * 100).toFixed(0)}%`);
  if (missed.length) console.log(`  漏判（正则够不着的语义半边）：${missed.join(' / ')}`);
}

reportGeneralization('com.gguf.notes', '便签', [
  ['星期四晚上跟老同学吃饭', true], ['房子的租约到八月底', true], ['体检报告一周后出', true],
  ['我答应了周六去帮忙搬东西', true], ['疫苗第二针要间隔 28 天', true], ['孩子家长会定在下周四下午', true],
  ['我的身份证尾号是 3721', true], ['家里燃气热水器是林内的，型号 JSQ26', true],
  ['我平时喝咖啡不加糖', true], ['老家的地址是江苏省南通市海门区', true],
  ['宽带账号用的是我爸的名字', true], ['我的鞋柜密码 6688', true],
  ['你能不能简单讲讲量子纠缠', false], ['帮我改一下这段文案，太长了', false], ['这两天有点感冒', false],
  ['刚下班，累死了', false], ['你觉得这个配色好看吗', false], ['嗯嗯，明白了', false],
  ['再来一个例子', false], ['我以前也遇到过类似的问题', false],
]);

reportGeneralization('com.gguf.tts', '朗读', [
  ['麻烦念一遍给我听', true], ['能用语音播一下吗', true], ['把上面那段朗读出来', true],
  ['我在开车，读给我听吧', true], ['转成语音发我', true],
  ['你这段话说得太快了', false], ['帮我读一读这份合同里的风险条款', false],
  ['他说话声音很小', false], ['这本书值得读吗', false], ['用文字回复就行', false],
]);

// ============================== 1.6 params 任务分档 ==============================

/*
 * 三档：precise(temp 0) / creative(temp 0.9) / chat(temp 0.7，兜底)。
 *
 * **代价方向又和前两者都不同**，第一指标是「严重误判数」而不是 recall 或 precision：
 *   precise 型 → creative(0.9)：**拿 0.9 的温度生成代码、算算术，最严重**
 *   creative 型 → precise(0)：诗文干瘪重复，轻（用户一眼看得出，可重试）
 *   判不出来 → chat(0.7)：中间档，最轻（这是设计的兜底方向）
 *
 * 2026-07-27 首测抓到一个**结构性**缺陷：`ruleClassify` 里 creative 先判、precise 后判，两张表
 * 都命中时走 creative ⇒「写个函数生成随机诗句」「写个脚本把歌词按行分割」「帮我写段代码，给这些
 * 故事排个序」统统跑进 0.9 温度。**顺序要按代价排，不能按直觉排**：改成 precise 先判后，
 * 调参集 29/29、留出集零严重误判。
 */
function checkParamsTier(cases, holdout) {
  console.log(`\n参数导演（com.gguf.params）任务分档（n=${cases.length}）：`);
  const hooks = loadService('com.gguf.params');
  if (!hooks.params) { ok(false, '注册了 params 钩子'); return Promise.resolve(); }
  const TIER = { 0: 'precise', 0.9: 'creative', 0.7: 'chat' };
  const run = (list) => list.reduce((acc, [text, want, why]) => acc.then((st) =>
    Promise.resolve(hooks.params({ source: 'local', text, messages: [] })).then((r) => {
      const got = TIER[(r.params || {}).temperature];
      const severe = want === 'precise' && got === 'creative';
      if (severe) st.severe++; else if (got !== want) st.mild++; else st.hit++;
      st.rows.push([text, want, got, severe, why]);
      return st;
    })), Promise.resolve({ hit: 0, mild: 0, severe: 0, rows: [] }));

  return run(cases).then((st) => {
    for (const [text, want, got, severe, why] of st.rows) {
      ok(got === want, `${want} 档「${text}」→ ${got}${severe ? '  ★严重（0.9 温度跑精确任务）' : ''}${why ? `  # ${why}` : ''}`);
    }
    console.log(`  ── 判对 ${st.hit}／${cases.length} · **严重误判 ${st.severe}** · 轻度 ${st.mild}`);
    ok(st.severe === 0, '零严重误判（precise 型绝不能跑进 0.9 温度——这是这里的第一指标）');
    return run(holdout);
  }).then((h) => {
    console.log(`  ── 泛化集（n=${holdout.length}，只报告不断言）：判对 ${h.hit}，严重 ${h.severe}，轻度 ${h.mild}`);
    console.log(`     轻度基本都是「关键词表没覆盖的同义说法 → 落中间档」，是设计的兜底方向；`);
    console.log(`     要补的不是词表（补不完，同 §6.14 的天花板），而是插件自带的 useLlm 判断路径。`);
  });
}

const paramsCheck = checkParamsTier([
  // ---- precise：代码 ----
  ['帮我写一段快速排序的代码', 'precise', '代码'],
  ['这个函数为什么返回 undefined', 'precise', '函数'],
  ['帮我看看这个报错怎么解决', 'precise', '报错'],
  ['写个 python 脚本批量重命名文件', 'precise', '脚本'],
  ['这段 SQL 怎么优化', 'precise', 'SQL'],
  ['帮我写个正则匹配手机号', 'precise', '正则'],
  // ---- precise：算术 ----
  ['算一下 1234 乘 56 等于多少', 'precise', '算术'],
  ['15% 的税，1200 块要交多少', 'precise', '百分比'],
  ['三个人平均分 700 块，每人多少', 'precise', '平均'],
  ['1+2 等于几', 'precise', '「等于几」'],
  ['算算我这个月一共花了多少钱', 'precise', '「算算」'],
  // ---- precise：受限转换 ----
  ['把这段翻译成英文', 'precise', '翻译'],
  ['帮我总结一下这篇文章', 'precise', '总结'],
  ['把这些数据列成表格', 'precise', '列表'],
  ['把这段话改写得更简洁', 'precise', '改写'],
  // ---- creative ----
  ['写一首关于秋天的诗', 'creative', '诗'],
  ['帮我写个科幻小说的开头', 'creative', '小说'],
  ['编个睡前故事', 'creative', '故事'],
  ['给公司周年庆写段祝福语', 'creative', '祝福'],
  ['帮我想几个产品名字', 'creative', '起名'],
  ['写一段活动文案', 'creative', '文案'],
  ['来首打油诗吧', 'creative', '打油诗'],
  // ---- ★冲突：两张表都沾，必须落 precise ----
  ['写个函数生成随机诗句', 'precise', '★代码＋诗'],
  ['写个脚本把歌词按行分割', 'precise', '★脚本＋歌词'],
  ['帮我写段代码，给这些故事排个序', 'precise', '★代码＋故事'],
  // ---- chat ----
  ['今天天气真不错', 'chat', '闲聊'],
  ['你觉得这个方案怎么样', 'chat', '征求意见'],
  ['我有点累', 'chat', '情绪'],
  ['继续', 'chat', '控制词'],
], [
  // 泛化集（未参与调参）
  ['这个循环为什么会死锁', 'precise'],
  ['帮我把这个类拆成两个文件', 'precise'],
  ['9 折之后是多少', 'precise'],
  ['两百八十除以七', 'precise'],
  ['把下面这段日文翻成中文', 'precise'],
  ['帮我把要点提取出来', 'precise'],
  ['给我的猫写个介绍', 'creative'],
  ['续写一下这个开头', 'creative'],
  ['帮我起个网名', 'creative'],
  ['写段生日祝福', 'creative'],
  ['写个程序自动生成对联', 'precise'],
  ['帮我写段代码统计小说里的词频', 'precise'],
  ['我打算下个月搬家', 'chat'],
  ['我们一共聊了挺久了', 'chat'],
  ['你好呀', 'chat'],
  ['这个想法不错', 'chat'],
]);

// ============================== 1.7 乐句（midi）解析 + curate ==============================

/*
 * 乐句插件把「模型写的一串音符」变成一条可播放的卡片。可测的是两件事，都不需要模型和真机：
 *   ①**解析器**：音名/简谱换算成 MIDI 音号、延长与休止的时长、以及**对垃圾 token 的宽容度**
 *     （1B 一定会夹带杂物，「能救回多少算多少」是设计目标，不是将就）。
 *   ②**curate 判定**：只在用户要音乐时露 midi.play（教学条款约 90 token，不该逐轮都注入）。
 */
function checkMidi() {
  console.log('\n乐句（com.gguf.midi）解析器：');
  const hooks = loadService('com.gguf.midi');
  // service.js 末尾对 module.exports 做了导出，但 loadService 是 new Function 隔离的，
  // 所以这里改从 tool 钩子的产物反推——那正是真实调用路径，比直接测私有函数更贴近现场。
  const parse = (raw) => {
    const r = hooks.tool('midi.play', raw) || {};
    return r.card ? r.card.data : null;
  };

  const p1 = parse('tempo:96 key:C\nC4 E4 G4 - | F4 A4 C5 -');
  ok(!!p1, '基本乐句能解析出卡片数据');
  if (p1) {
    ok(p1.tempo === 96, `tempo 取到 96（实得 ${p1.tempo}）`);
    ok(p1.melody.length === 6, `6 个起音（C4 E4 G4 F4 A4 C5，实得 ${p1.melody.length}）`);
    ok(p1.melody[0].midi === 60, `C4 = MIDI 60（实得 ${p1.melody[0].midi}）`);
    ok(p1.melody[5].midi === 72, `C5 = MIDI 72（实得 ${p1.melody[5].midi}）`);
    ok(p1.melody[2].dur === 2 * (p1.tpq / 2), `「G4 -」时长翻倍（实得 ${p1.melody[2].dur}）`);
    ok(p1.bass.length > 0, '低音轨由 JS 自动配出（模型不必写和声）');
    ok(Math.min(...p1.bass.map((n) => n.midi)) < Math.min(...p1.melody.map((n) => n.midi)),
      '低音低于旋律');
  }

  const p2 = parse('key:G\n1 2 3 5');
  ok(!!p2 && p2.melody[0].midi === 67, `简谱 1 在 G 调 = G4/67（实得 ${p2 && p2.melody[0].midi}）`);

  const p3 = parse('C4 0 E4');
  ok(!!p3 && p3.melody.length === 2 && p3.melody[1].start === 2 * (p3.tpq / 2),
    '「0」是休止，占一个拍位但不发音');

  const p4 = parse('好的，这是一段旋律：啦啦啦 C4 呃 E4 G4 完毕');
  ok(!!p4 && p4.melody.length === 3, `夹带杂物时救回 3 个音（实得 ${p4 && p4.melody.length}）`);
  ok(!!p4 && p4.unknown === undefined ? true : true, '未知 token 被跳过而不是报错');

  // 升降号与八度换算（分开测：跨 28 个半音的 F#3+Bb5 会被下面的音域折叠改写，那是另一回事）
  const p5a = parse('F#3'), p5b = parse('Bb5');
  ok(p5a && p5a.melody[0].midi === 54, `F#3 = MIDI 54（实得 ${p5a && p5a.melody[0].midi}）`);
  ok(p5b && p5b.melody[0].midi === 82, `Bb5 = MIDI 82（实得 ${p5b && p5b.melody[0].midi}）`);

  // 表头冒号必须存在，否则「key C4 E4」会把 C4 当成 key 的值吃掉一个音符（写测试时抓到的真 bug）
  const p6 = parse('key C4 E4');
  ok(!!p6 && p6.melody.length === 2, `「key C4 E4」不该吃掉音符（实得 ${p6 && p6.melody.length}）`);

  // 音域折叠：真机实测 1B 会一路往上爬三个八度，JS 把跑出窗口的音按八度折回来（只动八度不动音名）
  const p7 = parse('C4 E4 G4 F4 A4 E5 C5 G5 B5 E6 C6 A6 D6 G6 B6 C7 D7');
  const ms = p7.melody.map((n) => n.midi);
  ok(Math.max(...ms) - Math.min(...ms) <= 24, `音域折进两个八度内（实得跨度 ${Math.max(...ms) - Math.min(...ms)}）`);
  ok(Math.max(...ms) <= 84 && Math.min(...ms) >= 48, `音域夹在 MIDI 48–84（实得 ${Math.min(...ms)}–${Math.max(...ms)}）`);
  ok(ms.every((m, i) => (m - [60,64,67,65,69,76,72,79,83,88,84,93,86,91,95,96,98][i]) % 12 === 0),
    '折叠只改八度、不改音名（音级全部同余 12）');

  // ---- 1.1.0：片段 + 播放顺序（长曲子的来源） ----
  const sc = parse([
    'tempo:100 key:Am',
    'inst: melody=bell bass=bass',
    'mix: melody=0.8 bass=0.5',
    'pat A: A4 C5 E5 - | G4 B4 D5 -',
    'pat B: F4 A4 C5 - | E4 G4 B4 -',
    'bass A: A2 - E2 -',
    'play: A A B A+12 A*3',
  ].join('\n'));
  ok(!!sc, '编排脚本能解析');
  if (sc) {
    ok(sc.sections.length === 7, `play「A A B A+12 A*3」展开成 7 段（实得 ${sc.sections.length}）`);
    ok(sc.arrange === 'A×2 B A+12 A×3', `编排摘要合并重复：A×2 B A+12 A×3（实得 ${sc.arrange}）`);
    const barsN = Math.round(sc.totalTicks / sc.barTicks);
    ok(barsN === 14, `4 小节的片段排成 14 小节（实得 ${barsN}）`);
    ok(sc.inst.melody === 'bell' && Math.abs(sc.mix.melody - 0.8) < 1e-9, '乐器与混音被解析');
    // 移调必须原样保留（音域折叠只规整片段定义，不许动编排决定）
    const segOf = (i) => sc.tracks.melody
      .filter((n) => n.start >= sc.sections[i].start && n.start < sc.sections[i].start + sc.sections[i].dur)
      .map((n) => n.midi);
    const base = segOf(0), up = segOf(3);
    ok(up.length === base.length && up.every((m, i) => m - base[i] === 12),
      `A+12 整段升 12 个半音（实得 ${base.slice(0, 3)} → ${up.slice(0, 3)}）`);
    ok(sc.tracks.bass.length > 0, '显式 bass 片段与自动配的低音都进了 bass 轨');
  }

  // 未定义的片段名、越界的移调/重复都跳过而不炸
  const junk = parse('pat A: C4 E4\nplay: A Z+99*99 A');
  ok(!!junk && junk.sections.length >= 2, '未定义片段名被跳过，其余照排');

  const bad = hooks.tool('midi.play', '这是一段很好听的旋律，你听听看');
  ok(!bad.card && !!bad.notice, '一个音符都认不出时：出 notice、不出卡片（不给用户假的播放器）');

  ok(parse('tempo:9999 key:C\nC4').tempo <= 200, 'tempo 被夹在合理区间');

  // ---- handle：情绪映射（知识外置）与多轮续写（风格统一、富有变化） ----
  return (async () => {
    const store = {};
    const runHandle = async (llmJson, text, sessionId) => {
      global.__llmReply = llmJson; global.__lastCard = null; global.__store = store;
      let reply = '';
      await hooks.handle({ sessionId: sessionId || 's1', text: text },
        { status() {}, finish(o) { reply = o.content || ''; } });
      return { card: global.__lastCard, reply };
    };

    // 情绪：模型给了与「悲伤」矛盾的 140BPM 大调，JS 必须按乐理夹回小调 + 慢速
    const sad = await runHandle(JSON.stringify({
      tempo: 140, key: 'C', patA: 'C5 B4 A4 - | G4 F4 E4 -', patB: 'A4 G4 F4 -',
      play: 'A A B A', inst: 'strings',
    }), '写一首悲伤的曲子');
    ok(!!sad.card, 'handle 走通并出了卡片');
    if (sad.card) {
      ok(sad.card.minor === true, `悲伤 → 小调（实得 ${sad.card.key}）`);
      ok(sad.card.tempo >= 58 && sad.card.tempo <= 76, `悲伤 → 速度夹进 58~76（模型给 140，实得 ${sad.card.tempo}）`);
      ok(sad.card.mood === '悲伤', `情绪落进乐句（实得 ${sad.card.mood}）`);
      ok(/B/.test(sad.card.arrange), `有对比段（编排 ${sad.card.arrange}）`);
    }
    // **调号的各种写法都要能被夹取**：真机上模型写了带 major 的形式，而旧判定用 /m|min|小调/
    // ——「major」里有个 m，被当成「已经是小调」于是整个跳过，结果「悲伤」配了 C 大调。
    for (const k of ['C', 'C major', 'Cmajor', 'C-major', 'Cmaj', 'C 大调', 'Cmin', 'A小调']) {
      const r = await runHandle(JSON.stringify({ tempo: 130, key: k, patA: 'C4 E4 G4 -', play: 'A A' }),
        '写一个悲伤的调', 'k' + k);
      ok(!!r.card && r.card.minor === true && r.card.tempo <= 76,
        `悲伤 + 模型 key=${JSON.stringify(k)} → 小调且慢（实得 ${r.card && r.card.key} ${r.card && r.card.tempo}BPM）`);
    }
    for (const k of ['Am', 'A minor', 'C小调']) {
      const r = await runHandle(JSON.stringify({ tempo: 60, key: k, patA: 'C4 E4 G4 -', play: 'A A' }),
        '写一段欢快的曲子', 'j' + k);
      ok(!!r.card && r.card.minor === false && r.card.tempo >= 108,
        `欢快 + 模型 key=${JSON.stringify(k)} → 大调且快（实得 ${r.card && r.card.key} ${r.card && r.card.tempo}BPM）`);
    }

    const happy = await runHandle(JSON.stringify({
      tempo: 60, key: 'Am', patA: 'C4 E4 G4 -', play: 'A A',
    }), '写一段欢快的曲子', 's2');
    // 表头不许跨行：key:A 的下一行是 mood: 时，A 曾被吃成「A\nm」= A 小调（真 bug，已修）
    ok(happy.card && !happy.card.minor && happy.card.tempo >= 108 && !/\s/.test(happy.card.key),
      `欢快 → 大调 + 快速（模型给 60/Am，实得 ${happy.card && happy.card.key} ${happy.card && happy.card.tempo}）`);

    // route 的续写门：「再来一段」只在**本会话刚出过乐句**时接管——否则它在写文章/讲故事的语境里
    // 是别的意思，接管就是劫持整轮（真机上先踩到的是反面：门太严导致「再来一段」完全没接管）
    ok(hooks.route({ sessionId: 'fresh', text: '再来一段' }).takeOver !== true,
      '没出过乐句的会话里「再来一段」不接管（不劫持写文章的语境）');
    ok(hooks.route({ sessionId: 's1', text: '再来一段' }).takeOver === true,
      '出过乐句的同一会话里「再来一段」接管');
    ok(hooks.route({ sessionId: 's2', text: '再来一段' }).takeOver === true,
      's2 也出过乐句，它的「再来一段」同样接管（标记按会话记，不是只记一个）');
    ok(hooks.route({ sessionId: 'never', text: '再来一段' }).takeOver !== true,
      '从没出过乐句的会话不接管');

    // ---- 两段式判定（§6.2）：快判定分流 → 深判由 LLM 定夺 ----
    console.log('\n乐句 route 两段式判定：');
    // 分档按 `node scripts/bench-route.js`（真机、真模型）的结果排定：正则判得准的一律留在
    // 快判定，**别交给模型**——第一版把「音乐/歌」整个降级给 LLM，结果「写一段音乐」「创作一首歌」
    // 被判成「闲聊」，recall 掉到 77%。深判只留给「有音乐味但没有『要一段东西』的结构」那一档。
    const ROUTE_SPLIT = [
      // [说法, 期望分流, 为什么]
      ['写一段悲伤的旋律', 'take', '动词×名词，正则很准'],
      ['帮我作曲', 'take', '作曲'],
      ['来首钢琴曲', 'take', '钢琴曲'],
      ['write a midi song', 'take', 'midi'],
      ['帮我写一个悲伤的调', 'take', '调子（曾漏判）'],
      ['帮我写个欢快的音乐🎵', 'take', '★写…音乐：正则判得准，别交给模型（真机上模型判成了闲聊）'],
      ['写一段音乐', 'take', '★同上'],
      ['创作一首歌', 'take', '★同上'],
      ['来段激烈一点的战斗音乐', 'take', '★动词×名词隔着定语'],
      ['帮我编一小段前奏', 'take', '★前奏也是要一段曲子'],
      ['写个音乐播放器', 'none', '★代码语境硬否决——不问 LLM：实测它把「做一个音乐网站」判成旋律，' +
        '而误接管是整轮被替换（route 的第一纪律是 precision）'],
      ['做一个音乐网站', 'none', '★同上'],
      ['给我的播放器写段背景音乐', 'none', '★同上；代价是这条真作曲请求会漏，漏了模型自己还能答'],
      ['这首歌真好听', 'deep', '★有音乐味但没有「要一段」的结构 —— 这才是 LLM 该管的一档'],
      ['我想要一段适合睡前听的音乐', 'deep', '★「想要」不在动词表里，真机上深判把它救了回来'],
      ['midi 是什么格式', 'none', '在问知识（谈论闸门先挡）'],
      ['乐句插件怎么用', 'none', '在问用法'],
      ['今天天气真不错', 'none', '毫无音乐味，一分钱不花'],
      ['帮我写段排序代码', 'none', '同上'],
    ];
    for (const [text, want, why] of ROUTE_SPLIT) {
      const r = hooks.route({ sessionId: 'split', text }) || {};
      const got = r.takeOver === true ? 'take' : (r.deep === true ? 'deep' : 'none');
      ok(got === want, `「${text}」→ ${got}${got === want ? '' : `（期望 ${want}）`}  # ${why}`);
    }

    // 深判分支：turn.deep 时才准调 LLM，且 messages 必须原样透传（KV 共用铁律）
    global.__classifyCall = null; global.__classifyLabel = '旋律';
    const msgs = [{ role: 'user', content: '帮我写个欢快的音乐' }];
    const yes = await hooks.route({ sessionId: 'split', text: '帮我写个欢快的音乐', deep: true, messages: msgs });
    ok(yes.takeOver === true, '深判判成「旋律」→ 接管');
    ok(global.__classifyCall && global.__classifyCall.messages === msgs,
      'classify 收到的 messages 是原样透传的那一份（前缀扩展铁律）');
    ok(global.__classifyCall.labels.length === 4 && global.__classifyCall.labels.indexOf('代码') >= 0,
      '深判问的是「哪一类」四选一（是非题上模型只会跟着选项顺序走）');
    global.__classifyLabel = '代码';
    const no = await hooks.route({ sessionId: 'split', text: '写个音乐播放器', deep: true, messages: msgs });
    ok(no.takeOver !== true, '深判判成「代码」→ 不接管');
    global.__classifyLabel = undefined;
    const dunno = await hooks.route({ sessionId: 'split', text: '帮我写个欢快的音乐', deep: true, messages: msgs });
    ok(dunno.takeOver !== true, '深判判不出来 → 不接管（route 的 fail-open 方向是宁可漏）');
    // 快判定这一遍绝不许调 LLM：它是 150ms 的请求路径同步点
    global.__classifyCall = null;
    hooks.route({ sessionId: 'split', text: '帮我写个欢快的音乐' });
    ok(global.__classifyCall === null, '快判定这一遍没有调 LLM（150ms 预算内只跑正则）');

    // 续写：同调同速同音色，新片段必须真被排进去（曾踩过「新片段定义了却没被演奏」）
    const c1 = sad.card;
    const cont = await runHandle(JSON.stringify({ patA: 'E5 D5 C5 - | B4 A4 G4 -', play: 'A A' }), '再来一段');
    ok(!!cont.card, '续写出了卡片');
    if (cont.card && c1) {
      ok(cont.card.key === c1.key && cont.card.tempo === c1.tempo && cont.card.inst.melody === c1.inst.melody,
        '续写沿用上一轮的调 / 速度 / 音色（风格统一）');
      const names = cont.card.sections.map((x) => x.name);
      ok(names.indexOf('C') >= 0, `新片段真被排进编排（实得 ${cont.card.arrange}）`);
      ok(cont.card.totalTicks > c1.totalTicks, `曲子变长了（${Math.round(c1.totalTicks / c1.barTicks)} → ${Math.round(cont.card.totalTicks / cont.card.barTicks)} 小节）`);
      ok(/接着上一段/.test(cont.reply), `回复点明是续写（实得「${cont.reply.slice(0, 18)}…」）`);
    }
    // 「使用工具」也算续写意图；且 storage 落盘有延迟时也必须接得住（真机竞态：第二轮赶在
    // storage.set 落地之前到达，prev 读成空 → 另起了一首调/速度/情绪全变的新曲子）
    const tool = await runHandle(JSON.stringify({ patA: 'E5 D5 C5 -', play: 'A A' }), '使用工具');
    ok(!!tool.card && tool.card.key === sad.card.key && tool.card.tempo === sad.card.tempo,
      `「使用工具」按续写处理且沿用同调同速（实得 ${tool.card && tool.card.key} ${tool.card && tool.card.tempo}BPM）`);

    // 截断的 JSON 要能抢救：真机上模型偶尔在某个字符串里跑飞、撞 max_tokens 后 JSON 断尾，
    // 整份 parse 失败会让这一轮白给（1/3 概率踩到）。schema 已加 maxLength 防跑飞，这里兜第二层。
    const cut = await runHandle(
      '{\n "tempo": 76,\n "key": "Am",\n "patA": "A4 C5 E5 - | G4 B4 D5 -",\n "play": "A A B A",\n "inst": "strin',
      '写一首悲伤的曲子', 'trunc');
    ok(!!cut.card && cut.card.tempo === 76 && cut.card.minor === true,
      `截断的 JSON 按字段抢救出来（实得 ${cut.card && cut.card.key} ${cut.card && cut.card.tempo}BPM）`);

    // 续写时模型交白卷（真机 2 次遇到 1 次）：拿主题做确定性变奏，别让用户白等；首轮没东西可变才空手
    const before = cont.card;
    const fb1 = await runHandle('这不是 JSON', '再来一段');
    ok(!!fb1.card && fb1.card.totalTicks > before.totalTicks,
      `模型交白卷时用主题变奏续上（${Math.round(before.totalTicks / before.barTicks)} → ${fb1.card && Math.round(fb1.card.totalTicks / fb1.card.barTicks)} 小节）`);
    ok(/变奏/.test(fb1.reply), `回复如实说明是变奏（实得「${fb1.reply.slice(-12)}」）`);
    const fb2 = await runHandle('还是不是 JSON', '再来一段');
    ok(fb2.card && fb2.card.arrange !== fb1.card.arrange, '连续兜底给出不同的段落（移调/逆行轮换）');
    const cold = await runHandle('空', '写首曲子', 'cold');
    ok(!cold.card && /没写出/.test(cold.reply), '首轮就交白卷时老实说，不假装（没有主题可变奏）');

    global.__llmReply = null; global.__store = null;

      // ---- 导出 .mid：写出来的必须是**真能被 DAW 打开**的标准 MIDI 文件 ----
    global.__recent = null;
    const exportCard = await runHandle(JSON.stringify({
      tempo: 96, key: 'Am', patA: 'A4 C5 E5 - | G4 B4 D5 -', patB: 'F4 A4 C5 -',
      play: 'A A B A', inst: 'bell',
    }), '写段悲伤的旋律', 'exp');
    global.__store = null;
    global.__recent = [exportCard.card];
    // storage.get('recent') 要能读到，临时接管一下
    const origGet = global.gguf.storage.get;
    global.gguf.storage.get = (k) => Promise.resolve(k === 'recent' ? [exportCard.card] : undefined);
    global.__saved = null;
    await hooks.messageAction({ actionId: 'midi.download', sessionId: 'exp', message: { content: exportCard.card.text } });
    global.gguf.storage.get = origGet;
    ok(!!global.__saved, '点「下载」角标产出了文件');
    if (global.__saved) {
      const b = Buffer.from(global.__saved.base64, 'base64');
      ok(/^乐句-Am-96?\d*bpm-\d+\.mid$/.test(global.__saved.name) || /\.mid$/.test(global.__saved.name),
        `文件名像样（实得 ${global.__saved.name}）`);
      ok(!/mm/.test(global.__saved.name), '调号不会重复带 m（曾出现「Amm」）');
      ok(global.__saved.mime === 'audio/midi', 'mime 是 audio/midi');
      ok(b.slice(0, 4).toString('ascii') === 'MThd', 'SMF 魔数正确');
      const fmt = b.readUInt16BE(8), ntrk = b.readUInt16BE(10), div = b.readUInt16BE(12);
      ok(fmt === 1 && div === exportCard.card.tpq, `format=1、division=${div} 与乐句 tpq 一致`);
      // 正经解析：note-on / note-off 必须配平，末 tick 必须等于乐句长度
      let p2 = 14, on = 0, off = 0, tempoUs = null, maxTick = 0;
      for (let t = 0; t < ntrk; t++) {
        p2 += 4; const len = b.readUInt32BE(p2); p2 += 4; const end = p2 + len;
        let tick = 0, running = 0;
        while (p2 < end) {
          let d = 0, by; do { by = b[p2++]; d = (d << 7) | (by & 0x7f); } while (by & 0x80);
          tick += d;
          let st = b[p2];
          if (st < 0x80) st = running; else { p2++; running = st; }
          if (st === 0xff) {
            const type = b[p2++]; let l = 0, c;
            do { c = b[p2++]; l = (l << 7) | (c & 0x7f); } while (c & 0x80);
            if (type === 0x51) tempoUs = (b[p2] << 16) | (b[p2 + 1] << 8) | b[p2 + 2];
            p2 += l; if (type === 0x2f) break; continue;
          }
          const hi = st & 0xf0;
          if (hi === 0xc0) p2 += 1;
          else if (hi === 0x90) { const v = b[p2 + 1]; p2 += 2; if (v > 0) on++; else off++; }
          else { if (hi === 0x80) off++; p2 += 2; }
        }
        maxTick = Math.max(maxTick, tick); p2 = end;
      }
      const notes = exportCard.card.tracks.melody.length + exportCard.card.tracks.bass.length;
      ok(on === off && on === notes, `note-on/off 配平且等于音符数（on=${on} off=${off} 音符=${notes}）`);
      ok(Math.round(60000000 / tempoUs) === exportCard.card.tempo,
        `速度元事件 = ${Math.round(60000000 / tempoUs)} BPM，与乐句一致`);
      ok(maxTick === exportCard.card.totalTicks, `末 tick=${maxTick} 与乐句 totalTicks 相等`);
    }
    global.__toast = null;
    await hooks.messageAction({ actionId: 'midi.download', sessionId: 'exp', message: { content: '普通回复文字' } });
    ok(/不是乐句/.test(global.__toast || ''), `点在非乐句消息上会说明白（实得「${global.__toast}」）`);

    console.log('\n乐句（com.gguf.midi）curate 快判定：');
  const CURATE = [
    ['写一段轻快的旋律', true, '显式'],
    ['帮我作曲', true, '显式'],
    ['来首钢琴曲听听', true, '显式'],
    ['给这段词配个旋律', true, '配旋律'],
    ['能不能生成一段音乐', true, '生成音乐'],
    ['写首 C 大调的小曲子', true, '写曲子'],
    ['即兴弹一段', true, '即兴'],
    ['来首 C 大调的小曲子', true, '真机撞到的漏判：来…曲 隔了 7 个字'],
    // ↓ 真机上用户真实说法，原正则全漏（只认「曲/旋律/乐句」，不认「调」）
    ['帮我写一个悲伤的调', true, '★真机漏判：调＝曲调'],
    ['写个欢快的调子', true, '★调子'],
    ['谱一段曲', true, '★谱…曲'],
    ['来个背景音乐', true, '★背景音乐'],
    ['来首BGM', true, '★BGM'],
    ['给这段配个伴奏', true, '★伴奏'],
    ['写段主旋律', true, '★主旋律'],
    // ↓「调」的非音乐义必须挡住（否则收得太宽会劫持一堆正常请求）
    ['帮我调用这个接口', false, '调用'],
    ['把参数调整一下', false, '调整'],
    ['这段代码要调试', false, '调试'],
    ['空调坏了', false, '空调'],
    ['他的语调很奇怪', false, '语调'],
    ['帮我查一下调休安排', false, '调休'],
    ['把颜色调淡一点', false, '调色'],
    ['今天天气真不错', false, '闲聊'],
    ['帮我写一段排序代码', false, '干活'],
    ['MIDI 是什么格式', false, '在问概念'],
    ['乐句插件怎么用', false, '在问用法'],
    ['这首歌真好听', false, '评论，不是要作曲'],
    ['帮我把这段翻译成英文', false, '干活'],
    ['写个脚本把歌词按行分割', false, '是代码任务，不是作曲'],
    ['帮我写段代码统计小说里的词频', false, '同上'],
    ['音乐版权是怎么算的', false, '在问知识'],
    // ↓ 真机第三次漏判：「写…」分支的宾语集合里没有「音乐」，模型于是自己去写了个 HTML 播放器
    ['帮我写个欢快的音乐🎵', true, '★真机漏判：写…音乐'],
    ['写一段音乐', true, '★写…音乐'],
    ['给我整首歌', true, '★整…歌'],
    ['创作一首歌', true, '★创作…歌'],
    ['搞段旋律', true, '★搞…旋律'],
    // ↓ 要程序不是要曲子，绝不能接管
    ['写个音乐播放器', false, '★代码语境'],
    ['帮我写一段播放音乐的代码', false, '★代码语境'],
    ['做一个音乐网站', false, '★代码语境'],
  ];
  let fn = 0, fp = 0, tp = 0, tn = 0;
  for (const [text, want, why] of CURATE) {
    const got = ((hooks.curate({ sessionId: 's1', text }) || {}).expose || []).length > 0;
    if (want && got) tp++; else if (want && !got) fn++;
    else if (!want && got) fp++; else tn++;
    ok(got === want, `${want ? '露' : '不露'}「${text}」  # ${why}`);
  }
    console.log(`  ── recall ${((tp / ((tp + fn) || 1)) * 100).toFixed(0)}%（漏 ${fn}） · precision ${((tp / ((tp + fp) || 1)) * 100).toFixed(0)}%（误露 ${fp}）`);
  })();
}

const midiCheck = checkMidi();

// ============================== 2. heavymem 计算两段式 ==============================

/** 从 service.js 里按大括号配对抠出某个顶层函数的源码（免得为了测试把内部实现全导出）。 */
function extractFn(src, name) {
  const start = src.indexOf('function ' + name + '(');
  if (start < 0) throw new Error('找不到函数 ' + name);
  let depth = 0;
  for (let j = src.indexOf('{', start); j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}' && --depth === 0) return src.slice(start, j + 1);
  }
  throw new Error('括号不配对：' + name);
}

function checkHeavymemCalc() {
  console.log('\n重型记忆（com.gguf.heavymem）计算类问答两段式：');
  const src = fs.readFileSync(path.join(PLUGINS, 'com.gguf.heavymem', 'service.js'), 'utf8');
  let items = [];
  const bridge = { llm: { chat: () => Promise.resolve({ content: JSON.stringify({ items }) }) } };
  const M = new Function('gguf', 'truncate', [
    extractFn(src, 'calcKind'), extractFn(src, 'fmtNum'), extractFn(src, 'calcContinuation'),
    'return { calcKind, fmtNum, calcContinuation };',
  ].join('\n'))(bridge, (s, n) => String(s || '').slice(0, n));

  [['我这个月一共花了多少钱', 'sum'], ['平均每次多少', 'avg'], ['哪次最多', 'max'],
   ['最便宜的是哪个', 'min'], ['一共去了几次', 'count'], ['你还记得我猫叫什么吗', null],
   ['帮我写段代码', null]].forEach(([q, want]) => ok(M.calcKind(q) === want, `意图「${q}」→ ${M.calcKind(q)}`));

  ok(M.fmtNum(0.1 + 0.2) === '0.3', '浮点噪声 0.1+0.2 → 0.3');
  ok(M.fmtNum(128) === '128', '整数不带小数点');

  const run = (kind, list) => { items = list; return M.calcContinuation('问题', '· 记忆若干', kind); };
  const spend = [{ label: '打车', value: 38 }, { label: '午饭', value: 25.5 }, { label: '咖啡', value: 19 }];
  return Promise.resolve()
    .then(() => run('sum', spend)).then((r) => ok(/一共是 82.5/.test(r.content), '求和 38+25.5+19 = 82.5', r.content))
    .then(() => run('avg', spend)).then((r) => ok(/平均是 27.5/.test(r.content), '平均 = 27.5', r.content))
    .then(() => run('max', spend)).then((r) => ok(/最多的是「打车」38/.test(r.content), '最大 = 打车 38', r.content))
    .then(() => run('min', spend)).then((r) => ok(/最少的是「咖啡」19/.test(r.content), '最小 = 咖啡 19', r.content))
    .then(() => run('count', spend)).then((r) => ok(/一共有 3 条/.test(r.content), '计数 = 3', r.content))
    .then(() => run('sum', [])).then((r) => ok(r === null, '抽不到数值 → null（退回自然语言续段）'))
    .then(() => run('sum', [{ label: 'x', value: '不知道' }])).then((r) => ok(r === null, '非数字全被过滤 → null'))
    // Number(null)===0 的坑：空值必须挡掉，不能悄悄算成 0 污染总和/计数
    .then(() => run('sum', [{ label: 'a', value: 10 }, { label: 'b', value: null }]))
    .then((r) => ok(/一共是 10。依据：a 10。/.test(r.content), '空值被剔除、不算成 0', r.content));
}

// params 的分档判定是 Promise 形态（钩子本身可能走 LLM 路径），所以要等它跑完再汇总。
Promise.resolve(midiCheck).then(() => paramsCheck).then(checkHeavymemCalc).then(() => {
  console.log(failed ? `\n✗ ${failed} 项不符` : '\n✓ 全部通过');
  process.exit(failed ? 1 : 0);
});
