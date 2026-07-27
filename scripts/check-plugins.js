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
    storage: { get: noop, set: noop, remove: noop, keys: () => Promise.resolve([]) },
    llm: { chat: noop, classify: noop },
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
paramsCheck.then(checkHeavymemCalc).then(() => {
  console.log(failed ? `\n✗ ${failed} 项不符` : '\n✓ 全部通过');
  process.exit(failed ? 1 : 0);
});
