#!/usr/bin/env node
/*
 * **乐句生成质量的真机评测**（`node scripts/bench-compose.js`）。
 *
 * `bench-route.js` 量的是「该不该接管」，这里量的是接管之后**写出来的东西好不好**。
 * 走的是完整的真实路径：真 service.js 的 `handle` 钩子 → 真 sysPrompt → 真模型 → 真 JS 编排，
 * 最后从插件产出的乐句卡片上取指标。
 *
 * 为什么要它：判定量过两轮了，生成一次没量过——而「小模型到底能不能按提示词写出一段像样的旋律」
 * 才是这个插件成立的前提。
 *
 * 对照组：**带 / 不带 `json_schema`**。受限解码（GBNF）在判定上已经实测会毒化输出
 * （§6.2.1：object schema 下「理由全对、结论选错」），生成路径同样在用它，值得量一量代价。
 *
 * 用法：
 *   adb forward tcp:8080 tcp:8080
 *   node scripts/bench-compose.js [--rounds 5] [--url http://127.0.0.1:8080]
 */
'use strict';

const fs = require('fs');
const path = require('path');

const PLUGINS = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets', 'plugins');
const argv = process.argv.slice(2);
const argOf = (n, d) => { const i = argv.indexOf(n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const URL_BASE = argOf('--url', 'http://127.0.0.1:8080');
const ROUNDS = parseInt(argOf('--rounds', '5'), 10);

let useSchema = true;
let lastPrompt = null;

async function chatReal(opts) {
  const body = {
    model: 'local',
    messages: opts.messages,
    temperature: opts.temperature,
    max_tokens: opts.max_tokens,
  };
  if (useSchema && opts.json_schema) body.json_schema = opts.json_schema;
  lastPrompt = opts.messages;
  const r = await fetch(`${URL_BASE}/v1/chat/completions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const j = await r.json();
  return { content: j?.choices?.[0]?.message?.content || '' };
}

function loadService(pluginId) {
  const hooks = {};
  const noop = () => Promise.resolve({});
  global.gguf = {
    hooks: new Proxy({}, { get: (_, name) => (fn) => { hooks[name] = fn; } }),
    storage: { get: () => Promise.resolve(undefined), set: noop, remove: noop, keys: () => Promise.resolve([]) },
    llm: { chat: chatReal, classify: noop },
    card: { post(o) { global.__lastCard = (o && o.data) || null; } },
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

/** 跑一次 handle，返回插件最终产出的卡片与回复。 */
function composeOnce(hooks, text, sessionId) {
  return new Promise((resolve) => {
    global.__lastCard = null;
    const ctl = {
      status() {},
      finish(out) { const o = out || {}; resolve({ card: o.card || global.__lastCard, content: o.content, notice: o.notice }); },
    };
    Promise.resolve(hooks.handle({ text, sessionId, messages: [{ role: 'user', content: text }] }, ctl))
      .then((r) => { if (r && (r.card || r.content)) resolve(Object.assign({ card: global.__lastCard }, r)); })
      .catch((e) => resolve({ error: String(e) }));
  });
}

// 指标：模型真正负责的只有「片段音符」，所以指标都盯着片段本身
function score(card) {
  if (!card) return null;
  const mel = (card.tracks && card.tracks.melody) || [];
  const pitches = new Set(mel.map((n) => n.midi));
  // 片段里最长的一段连续同音（模型退化时会写 C4 C4 C4 C4…）
  let maxRun = 0, run = 0, prev = null;
  for (const n of mel) { run = n.midi === prev ? run + 1 : 1; prev = n.midi; maxRun = Math.max(maxRun, run); }
  // 相邻音程跳动（全是大跳 = 唱不下来；全是 0 = 没旋律）
  let leaps = 0;
  for (let i = 1; i < mel.length; i++) if (Math.abs(mel[i].midi - mel[i - 1].midi) > 7) leaps++;
  return {
    notes: mel.length,
    uniq: pitches.size,
    bars: Math.round((card.totalTicks || 0) / (card.barTicks || 1)),
    maxRun,
    leapRatio: mel.length > 1 ? leaps / (mel.length - 1) : 0,
    key: card.key, tempo: card.tempo,
  };
}

const ASKS = ['写一段悲伤的旋律', '来点欢快的曲子', '写首安静的小曲', '给我一段紧张的音乐', '随便写段旋律'];

(async () => {
  console.log(`乐句生成质量 · 真机评测（${URL_BASE}，每组 ${ROUNDS} 次）\n`);
  const hooks = loadService('com.gguf.midi');

  for (const mode of [true, false]) {
    useSchema = mode;
    console.log(`── ${mode ? '带 json_schema（现状：受限解码）' : '不带 json_schema（自由生成 + 宽容解析）'} ──`);
    const rows = [];
    for (let i = 0; i < ROUNDS; i++) {
      const ask = ASKS[i % ASKS.length];
      const t0 = Date.now();
      const out = await composeOnce(hooks, ask, `bench-${mode ? 's' : 'n'}-${i}`);
      const dt = ((Date.now() - t0) / 1000).toFixed(1);
      const s = score(out.card);
      if (!s) {
        console.log(`  ✗ ${ask.padEnd(16)} 没写出能用的片段  ${dt}s  ${(out.content || '').slice(0, 40)}`);
        rows.push(null);
        continue;
      }
      const flags = [];
      if (s.notes < 8) flags.push('片段过短');
      if (s.uniq <= 2) flags.push(`只有${s.uniq}个音高`);
      if (s.maxRun >= 4) flags.push(`同音连${s.maxRun}下`);
      if (s.leapRatio > 0.5) flags.push('大跳过多');
      console.log(`  ${flags.length ? '⚠' : '✓'} ${ask.padEnd(16)} ${String(s.notes).padStart(3)} 音 / ${String(s.uniq).padStart(2)} 音高 / ${String(s.bars).padStart(2)} 小节  ${s.key} ${s.tempo}BPM  ${dt}s  ${flags.join('、')}`);
      rows.push(s);
    }
    const okRows = rows.filter(Boolean);
    const avg = (f) => okRows.length ? (okRows.reduce((a, b) => a + f(b), 0) / okRows.length) : 0;
    console.log(`  小计：出片段 ${okRows.length}/${ROUNDS}，平均 ${avg((r) => r.notes).toFixed(1)} 音 / `
      + `${avg((r) => r.uniq).toFixed(1)} 个不同音高 / 最长同音连 ${avg((r) => r.maxRun).toFixed(1)} 下\n`);
  }
})();
