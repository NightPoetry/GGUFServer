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

function checkCurate(pluginId, title, cases) {
  console.log(`\n${title}（${pluginId}）curate 快判定：`);
  const hooks = loadService(pluginId);
  if (!hooks.curate) { ok(false, '注册了 curate 钩子'); return; }
  for (const [text, want] of cases) {
    const r = hooks.curate({ sessionId: 's1', text }) || {};
    const list = r.expose || [];
    ok(list.length > 0 === want, `${want ? '露' : '不露'}「${text}」→ [${list.join(',')}]`);
  }
}

checkCurate('com.gguf.notes', '便签', [
  ['今天天气真不错，适合出去走走', false],
  ['你觉得 Kotlin 和 Java 哪个更适合安卓开发', false],
  ['我记得你上次说过这个方案有风险', false],     // 「记得」不是「记一下」，不该触发
  ['讲个笑话吧', false],
  ['帮我把这个记录下来', true],
  ['顺便提醒我买牛奶', true],
  ['我下周三要去体检', true],
  ['这周五得交房租', true],
  ['便签里还有什么', true],
  ['3月15号要面试', true],
]);

checkCurate('com.gguf.tts', '朗读', [
  ['今天天气真不错', false],
  ['帮我写一段排序代码', false],
  ['我下周三要去体检', false],
  ['读一下这段', true],
  ['念给我听', true],
  ['用语音说声你好', true],
  ['把结果说出来', true],
  ['read this aloud', true],
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

checkHeavymemCalc().then(() => {
  console.log(failed ? `\n✗ ${failed} 项不符` : '\n✓ 全部通过');
  process.exit(failed ? 1 : 0);
});
