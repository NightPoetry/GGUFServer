/*
 * 线性记忆插件 service.js —— 两层「角色记忆」，让一段对话可以无限延续，且不拖慢回复。
 *
 * 设计红线（回应用户「不影响回复速度」）：请求路径上只有 context 钩子读一次私有存储、拼出 head，
 * 不调模型、不做检索、逐轮字节稳定 → llama-server 的 KV 前缀缓存不失效。所有需要调模型的重活
 * （滚动摘要、长期记忆抽取）全部放到「模型闲置」时由 setInterval 里的 worker 完成——闲置 = 模型
 * 空闲，天然不与用户抢占；一旦用户发来新请求（context 钩子触发），worker 立即让位（abort）。
 *
 * ── 第一层：滚动摘要（前情摘要）
 *   未归纳的近期对话超过约 60% 上下文时，闲置 worker 把最早那一批未归纳消息合并进「前情摘要」，
 *   推进水位线 cursor，摘要注入 head。摘要反复被重摘要 → 长度有界、细节随时间淡出（这正是线性
 *   记忆机制本身）。
 * ── 第二层：长期记忆（稳定事实）
 *   闲置 worker 用受限解码从「尚未被压缩的新鲜内容」里抽取值得长期记住的稳定事实（用户身份/偏好、
 *   人设、长期约定），去重后存进 longterm，永不随摘要淡出、始终注入 head。任何一条消息在被折进
 *   摘要之前都先经过长期抽取（ltCursor ≥ foldEnd 不变式），所以「被压缩的内容」的要点已沉淀进
 *   长期记忆或摘要，不会丢。
 *
 * 存储：gguf.storage 键 mem:<sessionId>，值
 *   { summary, longterm:[...], cursor, ltCursor, updatedAt, log:[{ts,kind,detail}] }
 * cursor / ltCursor 是对「非 system 消息按出现顺序计数」的水位线（会话只追加不改旧 → 计数稳定）。
 */
(function () {
  'use strict';

  // ============================== 参数 ==============================

  var CTX = (gguf.host && gguf.host.contextSize) || 4096;
  var compressPct = 60;                              // 触发压缩的上下文占比（%），设置卡片可调
  function compressThreshold() { return Math.round(CTX * compressPct / 100); }
  function loadConfig() {
    gguf.storage.get('config').then(function (c) {
      if (c && typeof c.compressPct === 'number') compressPct = Math.min(90, Math.max(30, c.compressPct));
    }).catch(function () {});
  }
  loadConfig();
  gguf.events.on('config-changed', loadConfig);      // 设置卡片改动后即时采用
  var HEAD_BUDGET = Math.max(64, Math.floor(CTX / 8)); // head 注入上限（与宿主 applyContext 一致）
  var SUMMARY_MAX_TOK = Math.max(120, Math.floor(CTX / 12)); // 单份摘要上限
  var COMPACT_MAX_TOK = Math.max(160, Math.floor(CTX / 10));  // 摘要生成的 max_tokens（须 > SUMMARY_MAX_TOK，留足重发余量）

  var TICK_MS = 4000;        // 闲置 worker 心跳
  var QUIET_MS = 8000;       // 最近一次活动后至少静默这么久才动模型
  var MAX_GEN_MS = 300000;   // busy 兜底超时（afterReply 没来时不至于永久卡住）
  var BACKOFF_MS = 20000;    // 模型调用失败（如 llama-server 未运行）后的退避
  var MIN_CONV = 6;          // 少于这么多轮不折腾
  var TAIL_GUARD = 4;        // 最近这么多条消息永远保留原文，不折进摘要
  var BATCH = 8;             // 单次最多折进摘要的消息数（小批 → 单次闲置调用短，碰撞代价小）
  var LT_MIN_NEW = 4;        // 攒够这么多条新鲜消息才做一次长期抽取
  var LT_BATCH = 10;         // 单次长期抽取最多看这么多条（限批 → 单次调用短，避免一口气吞几十条）
  var LT_CAP = 50;           // 长期记忆条数上限（超出保留最近的，护住 head 预算）
  var LOG_CAP = 30;          // 活动日志上限

  // ============================== 运行时闲/忙状态（单线程 JS，无需锁） ==============================

  var busy = false;        // context 触发→true，afterReply 触发→false：一轮回复是否在生成
  var busySince = 0;
  var working = false;     // 一次闲置任务是否在进行（防重入）
  var abort = false;       // 闲置进行中来了新请求 → 置位，任务在安全点让位
  var lastActivityTs = Date.now();
  var backoffUntil = 0;

  // ============================== 工具 ==============================

  function isCjk(code) { return code >= 0x4E00 && code <= 0x9FFF; }

  /** 粗略 token 估算（与宿主 MemoryIndex.estimateTokens 同公式）：CJK 1 字/token，其余 3.5 字符/token。 */
  function estimateTokens(text) {
    text = String(text || '');
    var cjk = 0, other = 0;
    for (var i = 0; i < text.length; i++) {
      if (isCjk(text.charCodeAt(i))) cjk++; else other++;
    }
    return cjk + Math.floor(other / 3.5) + 1;
  }

  function truncate(s, n) { s = String(s || ''); return s.length > n ? s.slice(0, n) + '…' : s; }
  function dbg(msg) { gguf.storage.set('last-debug', { ts: Date.now(), msg: String(msg) }).catch(function () {}); }

  function speakerLabel(role) { return role === 'user' ? '用户：' : '角色：'; }

  // ============================== 存储 ==============================

  function storageKey(sid) { return 'mem:' + sid; }

  function loadState(sid) {
    return gguf.storage.get(storageKey(sid)).then(function (v) {
      if (!v || typeof v !== 'object') return { summary: '', longterm: [], cursor: 0, ltCursor: 0, updatedAt: 0, log: [] };
      return {
        summary: String(v.summary || ''),
        longterm: Array.isArray(v.longterm) ? v.longterm.filter(Boolean).map(String) : [],
        cursor: v.cursor | 0,
        ltCursor: v.ltCursor | 0,
        updatedAt: v.updatedAt | 0,
        log: Array.isArray(v.log) ? v.log : [],
      };
    });
  }

  function saveState(sid, state) {
    state.updatedAt = Date.now();
    return gguf.storage.set(storageKey(sid), state);
  }

  function pushLog(state, kind, detail) {
    state.log = (state.log || []).concat([{ ts: Date.now(), kind: kind, detail: String(detail) }]);
    if (state.log.length > LOG_CAP) state.log = state.log.slice(state.log.length - LOG_CAP);
  }

  // ============================== head 拼接（context 钩子专用，逐轮字节稳定） ==============================

  function ltBlock(lt) {
    if (!lt || !lt.length) return '';
    return '【长期记忆】以下是关于用户与本角色的长期事实，请始终记住并自然运用，不要主动复述或提及本条：\n' +
      lt.map(function (s, i) { return (i + 1) + '. ' + s; }).join('\n');
  }
  function summaryBlock(summary) {
    if (!summary) return '';
    return '【前情摘要】以下是更早对话的归纳（越靠后越近期），请当作已经发生的事实继续对话，不要复述或提及本条：\n' + summary;
  }
  function composeHead(lt, summary) {
    var parts = [];
    var a = ltBlock(lt); if (a) parts.push(a);
    var b = summaryBlock(summary); if (b) parts.push(b);
    return parts.join('\n\n');
  }

  /** 在 HEAD_BUDGET 内拼 head：优先保长期记忆（「长期记住」是本插件承诺），先把摘要往下裁（可裁到空），
   *  仍超才丢最早的长期记忆条目，再超才对结果硬截断（仅在极小上下文下前缀本身超预算时发生）。
   *  只裁「注入用的副本」，存储里始终保留完整内容（面板照常展示全量）。纯函数、无时间量 → 字节稳定。 */
  function buildHeadBudgeted(state) {
    var lt = (state.longterm || []).slice();
    var summary = state.summary || '';
    if (estimateTokens(composeHead(lt, summary)) <= HEAD_BUDGET) return composeHead(lt, summary);
    // 1) 先裁摘要（保长期记忆），必要时一直裁到空
    while (summary && estimateTokens(composeHead(lt, summary)) > HEAD_BUDGET) {
      if (summary.length <= 8) { summary = ''; break; }
      summary = summary.slice(0, Math.floor(summary.length * 0.85));
    }
    // 2) 只剩长期记忆仍超预算 → 丢最早的
    while (lt.length && estimateTokens(composeHead(lt, summary)) > HEAD_BUDGET) lt = lt.slice(1);
    // 3) 兜底硬截断（CJK≈1字/token，截到 HEAD_BUDGET-1 字必 ≤ 预算）
    var head = composeHead(lt, summary);
    if (estimateTokens(head) > HEAD_BUDGET) head = head.slice(0, Math.max(0, HEAD_BUDGET - 1));
    return head;
  }

  // ============================== 钩子：context（拼 head） + afterReply（标记回复结束） ==============================

  gguf.hooks.context(function (turn) {
    busy = true; busySince = Date.now(); lastActivityTs = busySince;
    if (working) abort = true;   // 用户来请求了，正在跑的闲置任务尽快让位
    if (!turn || !turn.sessionId) return {};   // 仅本机会话（外部请求无状态，线性记忆无从依附）
    return loadState(String(turn.sessionId)).then(function (state) {
      var head = buildHeadBudgeted(state);
      return head ? { head: head } : {};
    }).catch(function () { return {}; });
  });

  gguf.hooks.afterReply(function () {
    busy = false; lastActivityTs = Date.now();   // 仅作闲/忙标记，不调模型、瞬时返回
    return {};
  });

  // ============================== 闲置 worker ==============================

  function idleReady() {
    var now = Date.now();
    if (working) return false;
    if (busy && now - busySince < MAX_GEN_MS) return false;   // 一轮回复可能还在生成
    if (now - lastActivityTs < QUIET_MS) return false;        // 静默不够久
    if (backoffUntil && now < backoffUntil) return false;     // 上次调用失败，退避中
    return true;
  }

  function tick() {
    if (!idleReady()) return;
    working = true; abort = false;
    var sid = null;
    Promise.resolve()
      .then(function () { return gguf.chat.current(); })
      .then(function (cur) {
        if (abort || !cur || cur.id == null) return null;
        sid = String(cur.id);
        return gguf.chat.messages();
      })
      .then(function (raw) {
        if (abort || sid == null || !raw) return null;
        var conv = [];
        for (var i = 0; i < raw.length; i++) {
          var m = raw[i];
          if (m && (m.role === 'user' || m.role === 'assistant') && String(m.content || '').trim()) conv.push(m);
        }
        if (conv.length < MIN_CONV) return null;
        return loadState(sid).then(function (state) {
          state.cursor = Math.min(Math.max(state.cursor | 0, 0), conv.length);
          state.ltCursor = Math.min(Math.max(state.ltCursor | 0, 0), conv.length);

          var liveTok = estimateTokens(buildHeadBudgeted(state));
          for (var j = state.cursor; j < conv.length; j++) liveTok += estimateTokens(conv[j].content) + 6;

          // 优先级 1：超过阈值（默认 60%，设置卡片可调）→ 折进摘要（先保证要折的这批已做过长期抽取）
          if (liveTok > compressThreshold()) {
            var foldEnd = Math.min(state.cursor + BATCH, conv.length - TAIL_GUARD);
            if (foldEnd > state.cursor) {
              if (state.ltCursor < foldEnd) return runLongterm(sid, conv, state, foldEnd);
              return runCompact(sid, conv, state, foldEnd);
            }
          }
          // 优先级 2：上下文还健康 → 对新鲜内容做长期抽取（限批，避免一口气吞几十条）
          if (conv.length - state.ltCursor >= LT_MIN_NEW) {
            return runLongterm(sid, conv, state, Math.min(state.ltCursor + LT_BATCH, conv.length));
          }
          return null;
        });
      })
      .catch(function (e) {
        backoffUntil = Date.now() + BACKOFF_MS;   // llama-server 未运行 / 调用异常 → 退避
        dbg('tick error: ' + (e && e.message));
      })
      .then(function () { working = false; }, function () { working = false; });
  }

  /** 把 conv[cursor..foldEnd) 合并进前情摘要，推进 cursor。 */
  function runCompact(sid, conv, state, foldEnd) {
    if (abort) return null;
    var batch = conv.slice(state.cursor, foldEnd);
    var convText = batch.map(function (m) { return speakerLabel(m.role) + truncate(m.content, 220); }).join('\n');
    var prev = state.summary ? state.summary : '（暂无，从头开始归纳）';
    var sys = '你是对话记忆整理器，负责维护一段长对话的「前情摘要」。下面给出已有摘要和一段新增对话，' +
      '请把新增对话的关键信息合并进摘要，输出更新后的完整前情摘要。要求：' +
      '① 第三人称、简洁、按时间顺序记叙；' +
      '② 保留人物与身份设定、关键事件、双方约定、情节推进、称呼与指代关系；' +
      '③ 不要丢失已有摘要里的要点，也不要逐句复述对话；' +
      '④ 直接输出摘要正文，不加标题、编号或任何解释。';
    var user = '【已有前情摘要】\n' + prev + '\n\n【新增对话】\n' + convText + '\n\n【请输出更新后的前情摘要】';
    if (abort) return null;
    // 纯文本补全（不用 json_schema）：字符串型 schema 没有天然停止点，模型会一直往 summary 里灌到
    // 填满 max_tokens → JSON 截断 → 解析全损；纯文本让模型输出摘要后自然 EOS 收尾，即便偶到上限
    // 也只是「截断但可用的摘要」。前提是服务端已关思考（app 的推荐配置，见插件说明）。
    return gguf.llm.chat({
      messages: [{ role: 'system', content: sys }, { role: 'user', content: user }],
      temperature: 0.3, max_tokens: COMPACT_MAX_TOK,
    }).then(function (res) {
      var summary = String((res && res.content) || '').trim();
      if (!summary) {
        dbg('compact: 空摘要（模型可能仍开着思考模式），冷却 60s');
        backoffUntil = Date.now() + 60000;   // 空结果 → 冷却，杜绝每 4s 重试同一批
        return;
      }
      state.summary = clampSummary(summary);
      state.cursor = foldEnd;
      pushLog(state, 'compact', '归纳了 ' + batch.length + ' 条消息（累计已归纳 ' + foldEnd + ' 条）');
      return saveState(sid, state).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); });
    });
  }

  function clampSummary(s) {
    while (s.length > 40 && estimateTokens(s) > SUMMARY_MAX_TOK) s = s.slice(0, Math.floor(s.length * 0.9));
    return s;
  }

  /** 解析 {"facts":[...]}；JSON 完整就正常解析，被 max_tokens 截断时从残缺 JSON 里抢救出已闭合的字符串。 */
  function parseFacts(content) {
    try { var o = JSON.parse(content); if (o && Array.isArray(o.facts)) return o.facts; } catch (e) { /* 落到抢救逻辑 */ }
    var facts = [];
    var m = content.match(/"facts"\s*:\s*\[([\s\S]*)/);
    var body = m ? m[1] : content;
    var re = /"((?:[^"\\]|\\.)*)"/g, mm;
    while ((mm = re.exec(body))) {
      try { facts.push(JSON.parse('"' + mm[1] + '"')); } catch (e2) { facts.push(mm[1]); }
    }
    return facts;
  }

  /** 从 conv[ltCursor..upTo) 抽取长期事实，去重后追加，推进 ltCursor。 */
  function runLongterm(sid, conv, state, upTo) {
    if (abort) return null;
    var from = Math.min(state.ltCursor, upTo);
    var batch = conv.slice(from, upTo);
    if (!batch.length) { state.ltCursor = Math.max(state.ltCursor, upTo); return saveState(sid, state); }
    var convText = batch.map(function (m) { return speakerLabel(m.role) + truncate(m.content, 220); }).join('\n');
    var existing = state.longterm || [];
    var sys = '你在为一段长期陪伴的对话维护「长期记忆」——只记录值得长期、跨越整段关系一直成立的稳定事实：' +
      '关于用户本人（称呼、身份、长期偏好、重要经历）、角色/人设的设定、双方的长期约定或重要承诺。' +
      '忽略闲聊、一次性话题、临时情绪、可从常识推断的内容。若这段对话没有值得长期记住的，返回空数组。' +
      '输出 JSON：{"facts": ["一句话事实", ...]}，每条尽量短、独立、能长期成立，用中文。';
    var user = (existing.length ? ('【已有长期记忆，勿重复】\n' + existing.map(function (s, i) { return (i + 1) + '. ' + s; }).join('\n') + '\n\n') : '') +
      '【最近对话】\n' + convText;
    if (abort) return null;
    return gguf.llm.chat({
      messages: [{ role: 'system', content: sys }, { role: 'user', content: user }],
      temperature: 0.2, max_tokens: 220,
      json_schema: { type: 'object', properties: { facts: { type: 'array', items: { type: 'string' } } }, required: ['facts'] },
    }).then(function (res) {
      var facts = parseFacts(String((res && res.content) || ''));
      var added = mergeLongterm(state, facts);
      state.ltCursor = Math.max(state.ltCursor, upTo);
      if (added.length) pushLog(state, 'longterm', '新增长期记忆 ' + added.length + ' 条');
      return saveState(sid, state).then(function () {
        if (added.length) gguf.events.broadcast('mem-changed', { sessionId: sid });
      });
    });
  }

  /** 逐条去重追加长期事实；超上限保留最近的。返回真正新增的条目。 */
  function mergeLongterm(state, facts) {
    var lt = state.longterm || [];
    var norm = {};
    lt.forEach(function (s) { norm[String(s).trim().toLowerCase()] = true; });
    var added = [];
    facts.forEach(function (f) {
      var s = String(f || '').trim();
      if (!s) return;
      var k = s.toLowerCase();
      if (norm[k]) return;
      norm[k] = true; lt.push(s); added.push(s);
    });
    if (lt.length > LT_CAP) {
      var trimmed = lt.length - LT_CAP;
      lt = lt.slice(lt.length - LT_CAP);
      pushLog(state, 'longterm', '长期记忆超过 ' + LT_CAP + ' 条上限，淘汰最早 ' + trimmed + ' 条');
    }
    state.longterm = lt;
    return added;
  }

  setInterval(function () { try { tick(); } catch (e) { dbg('tick throw: ' + (e && e.message)); } }, TICK_MS);

  // ============================== UI 面板发来的命令（同插件广播） ==============================

  function withCurrent(fn) {
    return gguf.chat.current().then(function (cur) {
      if (!cur || cur.id == null) { gguf.ui.toast('还没有当前会话'); return; }
      var sid = String(cur.id);
      return loadState(sid).then(function (state) { return fn(sid, state); });
    }).catch(function () {});
  }

  // 立即整理：清空静默门限，马上跑一次 worker（仍受 busy/working 保护）
  gguf.events.on('force-tick', function () { lastActivityTs = 0; busy = false; backoffUntil = 0; tick(); });

  // 删除某条长期记忆
  gguf.events.on('lt-delete', function (p) {
    var idx = p && typeof p.index === 'number' ? p.index : -1;
    withCurrent(function (sid, state) {
      if (idx < 0 || idx >= (state.longterm || []).length) return;
      var removed = state.longterm.splice(idx, 1)[0];
      pushLog(state, 'longterm', '手动删除长期记忆：' + truncate(removed, 20));
      return saveState(sid, state).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); });
    });
  });

  // 手动添加一条长期记忆
  gguf.events.on('lt-add', function (p) {
    var text = p && p.text ? String(p.text).trim() : '';
    if (!text) return;
    withCurrent(function (sid, state) {
      var added = mergeLongterm(state, [text]);
      if (!added.length) { gguf.ui.toast('已有相同的长期记忆'); return; }
      pushLog(state, 'longterm', '手动添加长期记忆：' + truncate(text, 20));
      return saveState(sid, state).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); });
    });
  });

  // 编辑前情摘要（面板直接改）
  gguf.events.on('summary-set', function (p) {
    var text = p && typeof p.text === 'string' ? p.text : null;
    if (text == null) return;
    withCurrent(function (sid, state) {
      state.summary = clampSummary(String(text).trim());
      pushLog(state, 'compact', '手动编辑前情摘要');
      return saveState(sid, state).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); });
    });
  });

  // 清空本会话记忆（摘要 + 长期 + 水位线全部归零；对话本身不动）
  gguf.events.on('reset', function () {
    withCurrent(function (sid, state) {
      var fresh = { summary: '', longterm: [], cursor: 0, ltCursor: 0, updatedAt: Date.now(), log: [] };
      pushLog(fresh, 'compact', '已清空本会话线性记忆');
      return saveState(sid, fresh).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); });
    });
  });
})();
