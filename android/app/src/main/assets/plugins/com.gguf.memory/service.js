/*
 * 记忆插件 service.js —— 原 MemoryIndex（BM25-lite）+ ChatViewModel.buildHistory 回忆注入的
 * JS 移植（《工具插件规范》§11）。宿主负责窗口裁剪并把被裁掉的旧消息作为 turn.dropped
 * （{role, text, ts} 数组）交进来；本插件用本轮用户消息在其中检索 top-6，按 memBudget 截断，
 * 作为 context.tail 输出——宿主再拼「现在时间 … 以上是背景资料」的外壳。
 * 没有命中 → 返回空（零注入，走完整 KV 缓存快路径）。
 */
(function () {
  'use strict';

  // ============================== BM25-lite（与原 MemoryIndex 完全同参） ==============================

  function isCjk(code) { return code >= 0x4E00 && code <= 0x9FFF; }

  /** 词元化：ASCII 连续字母/数字为一个词（小写）；CJK 出二元组（bigram）。 */
  function tokenize(text) {
    var out = [];
    var ascii = '';
    var prevCjk = null;
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      var code = text.charCodeAt(i);
      if (code < 128 && /[a-zA-Z0-9]/.test(ch)) {
        ascii += ch.toLowerCase();
        prevCjk = null;
      } else if (isCjk(code)) {
        if (ascii) { out.push(ascii); ascii = ''; }
        if (prevCjk !== null) out.push(prevCjk + ch);
        prevCjk = ch;
      } else {
        if (ascii) { out.push(ascii); ascii = ''; }
        prevCjk = null;
      }
    }
    if (ascii) out.push(ascii);
    return out;
  }

  /** 打分：idf = ln(1 + (N - df + 0.5)/(df + 0.5))，tf 饱和 k1 = 1.2，长度归一 b = 0.4。 */
  function bm25Search(query, docs, topK) {
    if (!docs.length || topK <= 0) return [];
    var qTerms = [];
    var seen = {};
    tokenize(query).forEach(function (t) { if (!seen[t]) { seen[t] = true; qTerms.push(t); } });
    if (!qTerms.length) return [];
    var docTf = docs.map(function (d) {
      var tf = {};
      tokenize(d).forEach(function (t) { tf[t] = (tf[t] || 0) + 1; });
      return tf;
    });
    var n = docs.length;
    var total = 0;
    docTf.forEach(function (tf) { for (var k in tf) total += tf[k]; });
    var avgLen = Math.max(total, 1) / n;
    var k1 = 1.2, b = 0.4;
    var scored = [];
    for (var i = 0; i < n; i++) {
      var tf = docTf[i];
      var len = 0;
      for (var k2 in tf) len += tf[k2];
      var s = 0;
      for (var qi = 0; qi < qTerms.length; qi++) {
        var t = qTerms[qi];
        var f = tf[t];
        if (!f) continue;
        var df = 0;
        for (var di = 0; di < n; di++) if (docTf[di][t]) df++;
        var idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
        s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len / avgLen));
      }
      if (s > 0) scored.push({ index: i, score: s });
    }
    scored.sort(function (a, c) { return c.score - a.score; });
    return scored.slice(0, topK);
  }

  /** 粗略 token 估算（与宿主 MemoryIndex.estimateTokens 同公式）：CJK 1 字/token，其余 3.5 字符/token。 */
  function estimateTokens(text) {
    var cjk = 0, other = 0;
    for (var i = 0; i < text.length; i++) {
      if (isCjk(text.charCodeAt(i))) cjk++; else other++;
    }
    return cjk + Math.floor(other / 3.5) + 1;
  }

  // ============================== 回忆注入（原 buildHistory 的检索段，逐字对齐） ==============================

  function pad2(n) { var s = String(n); return s.length < 2 ? '0' + s : s; }

  function fmtWhen(ts) {
    var d = new Date(ts);
    return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  gguf.hooks.context(function (turn) {
    var dropped = (turn && turn.dropped) || [];
    if (!dropped.length) return {};   // 没有溢出消息：零注入
    var t0 = Date.now();
    var query = String((turn && turn.text) || '');
    var docs = dropped.map(function (m) { return String(m.text || ''); });

    var hits = bm25Search(query, docs, 6);
    var memBudget = Math.floor(((gguf.host && gguf.host.contextSize) || 4096) / 8);
    var picked = [];   // {index, line, score}
    var used = 0;
    for (var i = 0; i < hits.length; i++) {
      var h = hits[i];
      var m = dropped[h.index];
      var excerpt = String(m.text || '').slice(0, 360);
      var cost = estimateTokens(excerpt) + 8;
      if (used + cost > memBudget) continue;
      var who = m.role === 'user' ? '用户' : '你';
      var whenStr = m.ts > 0 ? '，' + fmtWhen(m.ts) : '';
      picked.push({ index: h.index, line: '· ' + who + whenStr + ' 曾说：' + excerpt, score: h.score });
      used += cost;
    }

    // 把本轮召回结果写进 storage 供 UI 展示（只读调试视图）
    var record = {
      ts: t0,
      source: (turn && turn.source) || '',
      query: query.slice(0, 200),
      droppedCount: dropped.length,
      tookMs: Date.now() - t0,
      hits: picked.map(function (p) {
        // 调试视图完整保留命中行（注入上下文的截断预算是另一回事），面板里不能显示不全
        return { score: Math.round(p.score * 1000) / 1000, line: p.line };
      }),
    };
    gguf.storage.set('last-recall', record).catch(function () {});
    gguf.events.broadcast('recall-updated', null);

    if (!picked.length) return {};    // 没有命中：零注入
    // 按原会话顺序（index 升序）拼行集合，与原 buildHistory 逐字对齐；
    // 「现在时间 …」「以上是给你的背景资料 …」的外壳由宿主拼，这里只出中间内容。
    picked.sort(function (a, b) { return a.index - b.index; });
    var memoryBlock = picked.map(function (p) { return p.line; }).join('\n');
    return {
      tail: '更早对话中与当前问题相关的片段（注意各自的发生时间，与现在可能隔了数天）：\n' + memoryBlock,
    };
  });
})();
