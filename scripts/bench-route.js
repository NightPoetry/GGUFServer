#!/usr/bin/env node
/*
 * **route 两段式判定的真机评测**（`node scripts/bench-route.js`）。
 *
 * 与 `check-plugins.js` 的分工：那边是**离线**自检（纯正则、无模型，每次编译都跑）；这里要**真模型**
 * ——深判（§6.2.1）的质量取决于当天那个 GGUF 怎么想，只能连着手机量。
 *
 * 为什么必须量：规范 §6.14 自己写着「改判定前先跑带指标的语料」，而 route 改成两段式是个**新的
 * 判定形态**，此前只有四五条手挑用例。上一次不量就改的下场记在 §6.2 里（便签误接管率 31%）。
 *
 * 怎么量：加载**真的** service.js，走**真的** `hooks.route`；插件申请深判时，用与宿主
 * `PluginBridge.llmClassify` **同构**的构造去打真机上的 llama-server。所以量到的是
 * 「正则分档 + 模型判断」合起来的端到端判定，而不是其中某一半。
 *
 * ⚠️ 同构是手工保证的（脚本在 Mac 上、宿主在 Kotlin 里）。改 llmClassify 时记得同步这里的
 *    buildJudged/askModel，否则量的就不是线上那套了。
 *
 * 用法：
 *   adb forward tcp:8080 tcp:8080     # 手机上先把服务跑起来
 *   node scripts/bench-route.js [--plugin com.gguf.midi] [--url http://127.0.0.1:8080]
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ASSETS = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets');
const PLUGINS = path.join(ASSETS, 'plugins');

const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const URL_BASE = argOf('--url', 'http://127.0.0.1:8080');
const PLUGIN_ID = argOf('--plugin', 'com.gguf.midi');

// ============================== 与宿主同构的 classify ==============================

let stats = { calls: 0, ms: 0 };

/**
 * 复刻 `PluginBridge.llmClassify`：
 * - 判断指令挂在**末条用户消息的末尾**（模板不吃中途 system 时宿主就是这么挂的；Qwen 系全是这样）
 * - temperature 0、max_tokens 12、**不下发 json_schema**（受限解码在 2B 上会毒化判断，见 §6.2.1）
 * - 解析：出现位置最靠前的标签（并列取更长的）
 */
async function askModel(messages, question, labels) {
  const instruction = `${question}\n只回答下列之一，不要解释：${labels.join(' / ')}`;
  const judged = messages.map((m) => ({ ...m }));
  let lastUser = -1;
  for (let i = judged.length - 1; i >= 0; i--) if (judged[i].role === 'user') { lastUser = i; break; }
  if (lastUser >= 0 && typeof judged[lastUser].content === 'string') {
    judged[lastUser] = { ...judged[lastUser], content: `${judged[lastUser].content}\n\n${instruction}` };
  } else {
    judged.push({ role: 'system', content: instruction });
  }

  const t0 = Date.now();
  const r = await fetch(`${URL_BASE}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'local', messages: judged, temperature: 0, max_tokens: 12 }),
  });
  const j = await r.json();
  stats.calls++; stats.ms += Date.now() - t0;
  const text = String(j?.choices?.[0]?.message?.content || '').trim().replace(/^"|"$/g, '');
  let best = null;
  for (const l of labels) {
    const at = text.indexOf(l);
    if (at < 0) continue;
    if (!best || at < best.at || (at === best.at && l.length > best.label.length)) best = { at, label: l };
  }
  return { label: best ? best.label : null, raw: text };
}

// ============================== 加载真插件 ==============================

function loadService(pluginId) {
  const hooks = {};
  const noop = () => Promise.resolve({});
  global.gguf = {
    hooks: new Proxy({}, { get: (_, name) => (fn) => { hooks[name] = fn; } }),
    storage: { get: () => Promise.resolve(undefined), set: noop, remove: noop, keys: () => Promise.resolve([]) },
    llm: {
      chat: noop,
      classify: (o) => askModel(o.messages || [], o.question, o.labels),
    },
    card: { post() {} },
    files: { saveDownload: noop },
    ui: { toast() {}, close() {}, setBadge() {} },
    memllm: { ensure: noop, chat: noop, stop: noop, status: noop },
    tts: { speak: noop, stop: noop, status: noop },
    events: { on() {}, broadcast() {} },
    plugin: {}, host: {},
  };
  new Function(fs.readFileSync(path.join(PLUGINS, pluginId, 'service.js'), 'utf8'))();
  return hooks;
}

/** 走完整两段式：快判定 → （申请深判则）深判。返回 {takeOver, path}。 */
async function routeOnce(hooks, text) {
  const turn = { sessionId: 'bench', text, messages: [{ role: 'user', content: text }] };
  const first = (await hooks.route(turn)) || {};
  if (first.takeOver === true) return { takeOver: true, path: '快判定' };
  if (first.deep !== true) return { takeOver: false, path: '快判定' };
  const second = (await hooks.route({ ...turn, deep: true })) || {};
  return { takeOver: second.takeOver === true, path: '深判' };
}

// ============================== 语料 ==============================
//
// 标注的是**期望的最终行为**（接管 / 不接管），不是「该走哪条路」——走快判定还是深判是实现细节，
// 判对了才算数。分两组：调参集（可以据此改判定）与泛化集（**只报告不断言**，防止把语料背进正则，
// 这条纪律来自 §6.14 的教训）。

const MIDI_TUNE = [
  // —— 明确在要一段曲子 ——
  ['写一段悲伤的旋律', true],
  ['帮我作曲', true],
  ['来首钢琴曲', true],
  ['write a midi song', true],
  ['帮我写一个悲伤的调', true],
  ['帮我写个欢快的音乐🎵', true],
  ['写一段音乐', true],
  ['给我整首歌', true],
  ['创作一首歌', true],
  ['搞段旋律', true],
  ['谱一段轻快的小曲子', true],
  ['来点安静的背景音乐', true],
  ['写个 bgm', true],
  // —— 有音乐词，但要的是程序 ——
  ['写个音乐播放器', false],
  ['帮我写一段播放音乐的代码', false],
  ['做一个音乐网站', false],
  ['用 python 写个脚本把 mp3 批量转格式', false],
  ['写个脚本把歌词按行分割', false],
  // —— 在聊音乐，不是在要 ——
  ['这首歌真好听', false],
  ['我最近在听爵士', false],
  ['你喜欢什么类型的音乐', false],
  // —— 在问知识 ——
  ['midi 是什么格式', false],
  ['音乐版权是怎么算的', false],
  ['五度圈是干什么用的', false],
  // —— 完全无关（应该一分钱不花） ——
  ['今天天气真不错', false],
  ['帮我写段排序代码', false],
  ['把这段翻译成英文', false],
  // —— 由第一轮泛化集升级上来（据此改过判定，见 HOLDOUT_SET 顶部说明） ——
  ['帮我做个能播放本地音乐的小工具', false],
  ['给我推荐几首适合工作时听的歌', false],
  ['给我的播放器写段背景音乐', false],   // 有意的取舍：代码语境硬否决，宁可漏这条
  ['来段激烈一点的战斗音乐', true],
  ['帮我编一小段前奏', true],
];

const MIDI_HOLDOUT = [
  // ⚠️ 第一轮泛化集里有两条抓到了**类别性**缺口（「小工具」没进代码语境、「推荐几首歌」被叉乘
  // 命中），已据此改判定 —— 那两条因此升级进调参集，不再算泛化。下面是新一批没参与调参的。
  ['能不能给我弄段听着舒服的调子', true],
  ['我想要一段适合睡前听的音乐', true],
  ['给这首诗配个曲', true],
  ['帮我攒一段婚礼上用的小调', true],
  ['随便来点什么曲子听听', true],
  ['给视频配段轻快的伴奏', true],
  ['音乐 app 一般怎么做推荐算法', false],
  ['帮我把这首歌的歌词整理一下', false],
  ['这段旋律你觉得怎么样', false],
  ['吉他怎么调音', false],
  ['帮我分析下这首歌为什么这么火', false],
  ['把这段音频转成文字', false],
];


// —— 便签（com.gguf.notes）——
// 它的代价方向比乐句更极端：误接管会去**改用户真实的便签数据**。§6.2 记着它曾经 31% 误接管，
// 收紧成「动词与便签在句法上真的挨着」后降到 0——但 recall 从没量过，这里补上。
const NOTES_TUNE = [
  ['便签里加一条：周五交房租', true],
  ['帮我把便签清空', true],
  ['便签第二条删掉', true],
  ['把便签里的房租改成周六', true],
  ['帮我记一下：周五下午三点开会', true],
  ['记一下我的车牌是京A12345', true],
  // ↓ 动词与「便签」隔开：句法判定够不着，但用户确实在操作它
  ['便签里那条房租的，帮我改成周六', true],
  ['我便签里记的那个会议，时间挪一下', true],
  // ↓ §6.2 记下的四条真实误接管（在**谈论**便签，不是在操作）
  ['便签这个功能怎么用？我想改一下我的工作流', false],
  ['帮我把这段代码改一下，顺便讲讲便签原理', false],
  ['你觉得便签和备忘录哪个好用？我在考虑换成印象笔记', false],
  ['便签的数据存在哪里？误删了能恢复吗', false],
  ['便签能导出吗', false],
  ['帮我写段排序代码', false],
  ['今天天气真不错', false],
];

const NOTES_HOLDOUT = [
  ['把便签里过期的那几条清理掉', true],
  ['便签最后一行写错了，重写一下', true],
  ['帮我在便签里补一句：牛奶买两盒', true],
  ['便签为什么会丢内容', false],
  ['便签和你的记忆功能有什么区别', false],
  ['我在想要不要把便签换成清单形式', false],
];

// ============================== 跑 ==============================

function report(name, rows) {
  let tp = 0, fp = 0, fn = 0, tn = 0;
  const wrong = [];
  for (const r of rows) {
    if (r.want && r.got) tp++;
    else if (!r.want && r.got) { fp++; wrong.push(r); }
    else if (r.want && !r.got) { fn++; wrong.push(r); }
    else tn++;
  }
  const recall = tp + fn ? (tp / (tp + fn)) * 100 : 100;
  const precision = tp + fp ? (tp / (tp + fp)) * 100 : 100;
  console.log(`\n${name}（n=${rows.length}）`);
  for (const r of rows) {
    const mark = r.want === r.got ? '✓' : '✗';
    console.log(`  ${mark} ${r.text.padEnd(28)} 接管=${String(r.got).padEnd(5)} 走${r.path}${r.label ? `→「${r.label}」` : ''}`);
  }
  console.log(`  ── recall ${recall.toFixed(0)}%（漏接管 ${fn}） · precision ${precision.toFixed(0)}%（误接管 ${fp}）`);
  return { recall, precision, fn, fp, wrong };
}

(async () => {
  console.log(`route 两段式判定 · 真机评测（${PLUGIN_ID} @ ${URL_BASE}）`);
  const hooks = loadService(PLUGIN_ID);
  if (!hooks.route) { console.error('该插件没有 route 钩子'); process.exit(1); }

  const run = async (set) => {
    const out = [];
    for (const [text, want] of set) {
      let label = null;
      const origClassify = global.gguf.llm.classify;
      global.gguf.llm.classify = async (o) => { const r = await origClassify(o); label = r.label; return r; };
      const r = await routeOnce(hooks, text);
      global.gguf.llm.classify = origClassify;
      out.push({ text, want, got: r.takeOver, path: r.path, label });
    }
    return out;
  };

  const CORPUS = {
    'com.gguf.midi': { tune: MIDI_TUNE, holdout: MIDI_HOLDOUT },
    'com.gguf.notes': { tune: NOTES_TUNE, holdout: NOTES_HOLDOUT },
  };
  const corpus = CORPUS[PLUGIN_ID];
  if (!corpus) { console.error(`没有 ${PLUGIN_ID} 的语料`); process.exit(1); }
  const tune = report('调参集', await run(corpus.tune));
  const hold = report('泛化集（只报告不断言）', await run(corpus.holdout));

  console.log(`\n深判成本：调用 ${stats.calls} 次，平均 ${stats.calls ? Math.round(stats.ms / stats.calls) : 0}ms/次`
    + `（没走深判的轮次一分钱不花）`);

  // 硬门：route 的第一纪律是 precision 优先——误接管 = 整轮被替换，比漏接管贵得多
  if (tune.fp > 0) {
    console.log(`\n✗ 调参集有 ${tune.fp} 条误接管，route 的取向是 precision 第一，不接受`);
    process.exit(1);
  }
  if (tune.recall < 85) {
    console.log(`\n✗ 调参集 recall ${tune.recall.toFixed(0)}% < 85%，漏得太多（能力静默消失）`);
    process.exit(1);
  }
  console.log('\n✓ 调参集通过（零误接管，recall ≥ 85%）');
})();
