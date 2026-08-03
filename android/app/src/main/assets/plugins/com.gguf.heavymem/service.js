/*
 * 重型记忆插件 service.js —— 双模型 + GrowBox 记忆置换原理的手机尺度移植。
 *
 * 铁律与飞轮（用户定案「核心染色和碎片原理相同即可」）：
 *  · 存放区（resident）：唯一临时记忆，两态——条目只有「在/移除」，插入序原位不动、绝不随热度重排
 *    → context 注入的 head 逐轮字节稳定 → 命中 KV 前缀缓存（不拖慢回复）。热度只决定「调入谁/淘汰谁」（元数据）。
 *  · 染色（scannedThrough）：顺序扫描把历史消息抽成记忆条目，覆盖到的高水位即「深绿」，底下无碎片。
 *  · 碎片（jumps→fragments）：召回时 BM25 在原文里跳到 scannedThrough 之上的相关消息、跳过中段直接抽条目
 *    （二次指针/锚），被跳过的未扫中段即碎片欠债。
 *  · 做梦（闲置）：顺序扫描前进还债；扫到 jump 位置时把二次指针升为一次；发散建关联边。
 *  全部重活跑在【记忆模型】（第二个 llama-server 实例）上、且只在【模型闲置】时——不占前台。
 *  召回结果一轮延迟：本轮问题闲置时链接好、注入下一轮 head。
 *
 * 【全局记忆】（可选开关 cfg.globalMem，默认关，用户 2026-07-15 定案）：
 *  · 开启：跨会话索引 + 跨会话检索。
 *      - 索引：闲置 worker 在本会话无事可做时，最低优先级主动爬取【其它全部会话】（含从未打开的，
 *        经 gguf.sessions.list/load 读原文）为它们各自的 hm:<sid> 建记忆——即「跨会话索引」。
 *      - 检索：recall/续段的候选池并入其它会话 hm:<sid> 的条目；命中的会话外条目被【导入】当前会话
 *        存放区（拷一份、打 origin=<来源 sid> 标记，沿边邻居一并导入），供 head 注入。
 *  · 关闭（重新隔离，用户定案语义）：凡是带 origin 的「会话外」条目一律跳过——buildHead 不注入、
 *    recall 不作候选、边遍历不进入；停止爬取其它会话。底层数据一律不删，再开启即恢复。
 *
 * 存储 hm:<sessionId> = { items:[{id,text,ts,src,heat,primary,edged,origin?}], edges:{id:[id]}, scannedThrough,
 *   jumps:[msgIdx], resident:[id], nextId, total, crawledAt, lastRecalled, updatedAt, log }
 *   · origin 缺省=本会话原生条目；origin=<来源 sid> 表示由全局检索从别的会话导入的拷贝。
 *   · crawledAt=全局爬取该会话时它的 updatedAt（== 会话当前 updatedAt 即已索引到底，跳过）。
 * 配置 config = { memModel, memModelName, residentMax, recallN, sameTurn, globalMem }（设置卡片写）。
 */
(function () {
  'use strict';

  var CTX = (gguf.host && gguf.host.contextSize) || 4096;
  var HEAD_BUDGET = Math.max(64, Math.floor(CTX / 8));
  var TICK_MS = 4000, QUIET_MS = 8000, MAX_GEN_MS = 300000, BACKOFF_MS = 20000, ENSURE_BACKOFF_MS = 60000;
  var MIN_CONV = 4, SCAN_BATCH = 6, EDGE_BATCH = 1, LOG_CAP = 30;
  var CRAWL_COOLDOWN_MS = 30000, FOREIGN_ITEM_CAP = 800;   // 全局：一轮爬完歇 30s；跨会话候选条目上限

  var cfg = { memModel: '', memModelName: '', residentMax: 12, recallN: 6, sameTurn: true, globalMem: false };
  var memReady = false;

  var busy = false, busySince = 0, working = false, abort = false, lastActivityTs = Date.now(), backoffUntil = 0;
  var lastQuery = '', lastQuerySid = null;
  var crawlCache = {}, crawlCooldownUntil = 0;   // 全局爬取状态：sid→已索引到底时的会话 updatedAt；空闲冷却

  // ============================== 工具 ==============================
  function isCjk(c) { return c >= 0x4E00 && c <= 0x9FFF; }
  function estTok(t) { t = String(t || ''); var a = 0, o = 0; for (var i = 0; i < t.length; i++) { if (isCjk(t.charCodeAt(i))) a++; else o++; } return a + Math.floor(o / 3.5) + 1; }
  function truncate(s, n) { s = String(s || ''); return s.length > n ? s.slice(0, n) + '…' : s; }
  function speaker(r) { return r === 'user' ? '用户：' : '角色：'; }
  function dbg(m) { gguf.storage.set('last-debug', { ts: Date.now(), msg: String(m) }).catch(function () {}); }

  function tokenize(text) {
    var out = [], ascii = '', prev = null;
    for (var i = 0; i < text.length; i++) {
      var ch = text[i], code = text.charCodeAt(i);
      if (code < 128 && /[a-zA-Z0-9]/.test(ch)) { ascii += ch.toLowerCase(); prev = null; }
      else if (isCjk(code)) { if (ascii) { out.push(ascii); ascii = ''; } if (prev !== null) out.push(prev + ch); prev = ch; }
      else { if (ascii) { out.push(ascii); ascii = ''; } prev = null; }
    }
    if (ascii) out.push(ascii);
    return out;
  }
  function bm25(query, docs, topK) {
    if (!docs.length || topK <= 0) return [];
    var q = [], seen = {}; tokenize(query).forEach(function (t) { if (!seen[t]) { seen[t] = true; q.push(t); } });
    if (!q.length) return [];
    var tf = docs.map(function (d) { var m = {}; tokenize(d).forEach(function (t) { m[t] = (m[t] || 0) + 1; }); return m; });
    var n = docs.length, total = 0; tf.forEach(function (m) { for (var k in m) total += m[k]; });
    var avg = Math.max(total, 1) / n, k1 = 1.2, b = 0.4, scored = [];
    for (var i = 0; i < n; i++) {
      var len = 0; for (var k2 in tf[i]) len += tf[i][k2];
      var s = 0;
      for (var qi = 0; qi < q.length; qi++) {
        var f = tf[i][q[qi]]; if (!f) continue;
        var df = 0; for (var di = 0; di < n; di++) if (tf[di][q[qi]]) df++;
        var idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
        s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len / avg));
      }
      if (s > 0) scored.push({ index: i, score: s });
    }
    scored.sort(function (a, c) { return c.score - a.score; });
    return scored.slice(0, topK);
  }

  // ============================== 存储 ==============================
  function key(sid) { return 'hm:' + sid; }
  function blank() { return { items: [], edges: {}, scannedThrough: 0, jumps: [], resident: [], nextId: 1, total: 0, crawledAt: 0, lastRecalled: '', updatedAt: 0, log: [] }; }
  function load(sid) {
    return gguf.storage.get(key(sid)).then(function (v) {
      if (!v || typeof v !== 'object') return blank();
      var b = blank();
      b.items = Array.isArray(v.items) ? v.items : [];
      b.edges = (v.edges && typeof v.edges === 'object') ? v.edges : {};
      b.scannedThrough = v.scannedThrough | 0;
      b.jumps = Array.isArray(v.jumps) ? v.jumps : [];
      b.resident = Array.isArray(v.resident) ? v.resident : [];
      b.nextId = v.nextId | 0 || 1;
      b.total = v.total | 0;
      b.crawledAt = v.crawledAt | 0;
      b.lastRecalled = String(v.lastRecalled || '');
      b.log = Array.isArray(v.log) ? v.log : [];
      return b;
    });
  }
  function save(sid, st) { st.updatedAt = Date.now(); return gguf.storage.set(key(sid), st); }
  function pushLog(st, detail) { st.log = (st.log || []).concat([{ ts: Date.now(), detail: String(detail) }]); if (st.log.length > LOG_CAP) st.log = st.log.slice(st.log.length - LOG_CAP); }
  function itemById(st, id) { for (var i = 0; i < st.items.length; i++) if (st.items[i].id === id) return st.items[i]; return null; }

  function loadConfig() {
    return gguf.storage.get('config').then(function (c) {
      if (c && typeof c === 'object') {
        cfg.memModel = c.memModel || ''; cfg.memModelName = c.memModelName || '';
        cfg.residentMax = Math.min(40, Math.max(4, c.residentMax || 12));
        cfg.recallN = Math.min(16, Math.max(1, c.recallN || 6));
        cfg.sameTurn = c.sameTurn !== false;   // 两段式续段（同轮回忆），默认开
        cfg.globalMem = c.globalMem === true;  // 全局记忆（跨会话索引+检索），默认关
      }
    }).catch(function () {});
  }
  loadConfig();
  gguf.events.on('config-changed', function () {
    memReady = false; crawlCache = {}; crawlCooldownUntil = 0;   // 重扫会话列表：全局开关/会话集可能刚变
    loadConfig();
  });

  // ============================== 记忆条目去重合并 ==============================
  function addItems(st, texts, src, primary) {
    var norm = {}; st.items.forEach(function (it) { norm[String(it.text).trim().toLowerCase()] = it; });
    var added = [];
    texts.forEach(function (t) {
      t = String(t || '').trim(); if (!t) return;
      var k = t.toLowerCase(), ex = norm[k];
      if (ex) { if (primary) ex.primary = true; return; }  // 已有：顺序扫达则升为一次指针
      var it = { id: st.nextId++, text: t, ts: Date.now(), src: src, heat: 0, primary: !!primary, edged: false };
      st.items.push(it); norm[k] = it; added.push(it);
    });
    return added;
  }

  /** 全局检索命中的【会话外】条目：拷进当前会话 st.items、打 origin 标记，返回本地 id（已存在同文则复用）。
   *  edged=true 让本会话的发散建边步骤别去动这些导入拷贝（它们只是快照，边留在来源会话里）。 */
  function importForeign(st, text, originSid) {
    text = String(text || '').trim(); if (!text) return null;
    var k = text.toLowerCase();
    for (var i = 0; i < st.items.length; i++) { if (String(st.items[i].text).trim().toLowerCase() === k) return st.items[i].id; }
    var it = { id: st.nextId++, text: text, ts: Date.now(), src: -1, heat: 0, primary: false, edged: true, origin: String(originSid) };
    st.items.push(it);
    return it.id;
  }

  /** 全局检索用：枚举本插件所有 hm:<数字sid> 存储（自己的 KV，无需 sessions:read），
   *  收集【除当前会话外】所有会话的记忆条目作候选，每条附来源 sid + 沿边邻居的文本（供导入时一并带入）。 */
  function gatherForeignItems(currentSid) {
    var curKey = key(currentSid);
    return gguf.storage.keys().then(function (keys) {
      var storeKeys = (keys || []).filter(function (k) { return /^hm:\d/.test(k) && k !== curKey; });
      return Promise.all(storeKeys.map(function (k) {
        return gguf.storage.get(k).then(function (v) { return { sid: k.slice(3), st: v }; }, function () { return null; });
      })).then(function (arr) {
        var out = [];
        for (var a = 0; a < arr.length; a++) {
          var e = arr[a]; if (!e || !e.st || !Array.isArray(e.st.items)) continue;
          var byId = {}; e.st.items.forEach(function (it) { byId[it.id] = it; });
          var edges = e.st.edges || {};
          for (var b = 0; b < e.st.items.length; b++) {
            var it = e.st.items[b];
            if (!it || !String(it.text || '').trim()) continue;
            var nb = (edges[it.id] || []).map(function (nid) { return byId[nid] && byId[nid].text; }).filter(Boolean).slice(0, 3);
            out.push({ text: it.text, sid: e.sid, edges: nb, _foreign: true });
            if (out.length >= FOREIGN_ITEM_CAP) return out;
          }
        }
        return out;
      });
    }).catch(function () { return []; });
  }

  // ============================== 存放区（两态 · 置换） ==============================
  function pageIn(st, id) {
    var it = itemById(st, id); if (!it) return;
    it.heat = (it.heat || 0) + 1;
    if (st.resident.indexOf(id) >= 0) return;              // 已在场：两态——不动位置，只升热度
    st.resident.push(id);                                  // 追加到末尾，插入序固定
    while (st.resident.length > cfg.residentMax) {         // 置换：淘汰热度最低者
      var lowIdx = 0, low = Infinity;
      for (var i = 0; i < st.resident.length; i++) { var h = (itemById(st, st.resident[i]) || {}).heat || 0; if (h < low) { low = h; lowIdx = i; } }
      st.resident.splice(lowIdx, 1);
    }
  }

  /** 沿边扩散调入邻居——全局关闭时不进入「会话外节点」（origin 条目），实现重新隔离下的边遍历隔离。 */
  function pageInEdge(st, nb) {
    if (!cfg.globalMem) { var it = itemById(st, nb); if (it && it.origin) return; }
    pageIn(st, nb);
  }

  function buildHead(st) {
    var lines = [];
    st.resident.forEach(function (id) {
      var it = itemById(st, id); if (!it) return;
      if (!cfg.globalMem && it.origin) return;   // 全局关闭=重新隔离：会话外条目不注入
      lines.push('· ' + it.text);
    });
    if (!lines.length) return '';
    var head = '【长期记忆】以下是与当前对话相关的历史记忆，直接利用，不要复述或提及本条：\n' + lines.join('\n');
    while (estTok(head) > HEAD_BUDGET && lines.length > 1) { lines.pop(); head = '【长期记忆】以下是与当前对话相关的历史记忆，直接利用，不要复述或提及本条：\n' + lines.join('\n'); }
    if (estTok(head) > HEAD_BUDGET) head = head.slice(0, Math.max(0, HEAD_BUDGET - 1));
    return head;
  }

  // ============================== 钩子 ==============================
  gguf.hooks.context(function (turn) {
    busy = true; busySince = Date.now(); lastActivityTs = busySince;
    if (working) abort = true;
    if (!turn || !turn.sessionId) return {};
    lastQuery = String(turn.text || ''); lastQuerySid = String(turn.sessionId);
    return load(String(turn.sessionId)).then(function (st) {
      var head = buildHead(st);
      return head ? { head: head } : {};
    }).catch(function () { return {}; });
  });
  gguf.hooks.afterReply(function () { busy = false; lastActivityTs = Date.now(); return {}; });

  // 两段式续段（网关在前台回复流完后调用）：同轮召回相关记忆 → 用前台模型生成一句衔接补充。
  // 前台第一段已流完、不受影响；这里产出的续段由网关作为增量追加。默认开，可在设置关（回退纯一轮延迟）。
  gguf.hooks.continuation(function (turn) {
    if (!cfg.sameTurn) return {};
    var sid = turn && turn.sessionId ? String(turn.sessionId) : lastQuerySid;
    var query = String((turn && turn.text) || '');
    var reply = String((turn && turn.content) || '');
    if (!sid || !query) return {};
    return sameTurnContinuation(sid, query, reply).catch(function (e) { dbg('continuation err: ' + (e && e.message)); return {}; });
  });

  function sameTurnContinuation(sid, query, reply) {
    // 候选用纯 BM25 选（不碰记忆模型 → 不与闲置 worker 的建边抢占 → 续段稳定触发）；
    // 续段正文由前台模型生成（下面 genContinuation），记忆模型只在闲置时干活。
    // 全局开启时候选并入其它会话条目、命中即导入；关闭时只用本会话原生条目（跳过 origin）。
    return load(sid).then(function (st) {
      var localCand = st.items.filter(function (it) { return cfg.globalMem || !it.origin; });
      var poolP = cfg.globalMem ? gatherForeignItems(sid) : Promise.resolve([]);
      return poolP.then(function (foreign) {
        var seen = {}; localCand.forEach(function (it) { seen[String(it.text).trim().toLowerCase()] = true; });
        var pool = localCand.map(function (it) { return { text: it.text, item: it }; });
        foreign.forEach(function (f) { var fk = String(f.text).trim().toLowerCase(); if (!seen[fk]) { seen[fk] = true; pool.push(f); } });
        if (!pool.length) return {};
        var hits = bm25(query, pool.map(function (p) { return p.text; }), cfg.recallN).map(function (h) { return pool[h.index]; });
        if (!hits.length) return {};
        // 相关度**升序**排布（bm25 给的是降序，这里反过来）：最相关的一条排在最后、紧贴后面的
        // 任务指令与问题。2026-07-13 实测（docs/记忆检索基准与重型记忆设计.md）——1B 判"资料能否
        // 回答问题"，资料在前、任务框架在后 69% vs 反过来 46%；同理，块内也让最该被用上的那条
        // 离指令最近。顺带 pageIn 也按同序，最相关者最后调入 → 在存放区（head）里也排最后。
        hits = hits.reverse();
        var memLines = [], cross = 0;
        hits.forEach(function (o) {
          var localId;
          if (o._foreign) {
            localId = importForeign(st, o.text, o.sid); cross++;
            (o.edges || []).slice(0, 1).forEach(function (nt) { var lid = importForeign(st, nt, o.sid); if (lid != null) pageIn(st, lid); });
          } else localId = o.item.id;
          if (localId != null) { pageIn(st, localId); (st.edges[localId] || []).slice(0, 2).forEach(function (nb) { pageInEdge(st, nb); }); }
          memLines.push('· ' + o.text);
        });
        st.lastRecalled = query;
        var memBlock = memLines.join('\n');
        pushLog(st, '同轮召回 ' + hits.length + ' 条并续段' + (cross ? '（含跨会话 ' + cross + '）' : ''));
        return save(sid, st).then(function () {
          gguf.events.broadcast('mem-changed', { sessionId: sid });
          var kind = calcKind(query);
          if (!kind) return genContinuation(query, reply, memBlock);
          // 涉及计算的问题走两段式；抽不到数值/出错都退回自然语言续段（续段绝不因此落空）
          return calcContinuation(query, memBlock, kind).then(function (r) {
            return r || genContinuation(query, reply, memBlock);
          }, function () { return genContinuation(query, reply, memBlock); });
        });
      });
    });
  }

  /** 用前台模型（gguf.llm，非记忆模型）生成一句衔接补充——续段是「角色」在说话，不是检索器。
   *  召回已确认相关（hitIds 非空才会走到这里），故直接让模型用这些记忆答/补一句，不给「无需补充」逃逸
   *  （否则 1B 容易过度触发 bail）；提示词精简为单条 system + 一句 user，避免多轮把小模型绕晕。 */
  function genContinuation(query, reply, memBlock) {
    var sys = '用户刚问：「' + query + '」。你查到以下与之相关的记忆：\n' + memBlock +
      '\n请基于这些记忆，用一两句话自然地补充或回答（像「对了，我记得…」开头），直接说内容，不要复述记忆原文、不要说多余的话。';
    return gguf.llm.chat({
      messages: [{ role: 'system', content: sys }, { role: 'user', content: '（根据刚想起的记忆补一句）' }],
      temperature: 0.5, max_tokens: 120
    }).then(function (res) {
      var c = String((res && res.content) || '').trim();
      if (c) return { content: '\n\n' + c };
      // 前台 1B 偶尔不肯生成——兜底直接把想起的记忆亮出来，保证「回忆中」后一定有产出。
      return { content: '\n\n（我想起来了）' + memBlock.replace(/^·\s*/gm, '').replace(/\n/g, '；') };
    }, function () {
      return { content: '\n\n（我想起来了）' + memBlock.replace(/^·\s*/gm, '').replace(/\n/g, '；') };
    });
  }

  // ============================== 计算类记忆问答：模型只抽数字，算术交给代码 ==============================

  /** 快判定本轮问题要不要算数（正则级、零成本），返回运算种类或 null。 */
  function calcKind(q) {
    var s = String(q || '');
    if (!/一共|总共|总计|合计|加起来|总和|平均|最多|最少|最大|最小|最贵|最便宜|最高|最低|几次|几个|几天|几笔|多少次|多少笔|多少条|多少钱|花了多少|用了多少/.test(s)) return null;
    if (/平均/.test(s)) return 'avg';
    if (/最多|最大|最贵|最高/.test(s)) return 'max';
    if (/最少|最小|最便宜|最低/.test(s)) return 'min';
    if (/几次|几个|几天|几笔|多少次|多少笔|多少条/.test(s)) return 'count';
    return 'sum';
  }

  /** 整数不带小数点、小数最多两位（免得冒出 0.30000000000000004 这种浮点噪声）。 */
  function fmtNum(n) { return String(Math.round(Number(n) * 100) / 100); }

  /**
   * 两段式：① 模型只干「把记忆里的相关数值逐条抽出来」（json_schema 受限解码 + temp 0 +
   * 资料在前/任务在后的排布）；② 加减乘除全部由 JS 算。1B 的算术不可靠，但"从文本里挑数字"
   * 很稳——把不擅长的那半外包给代码，是本项目一贯的轮椅哲学（见《推理参数与外部知识架构研究》）。
   * 抽不到数值就返回 null，交回自然语言续段，绝不硬凑一个算出来的数。
   */
  function calcContinuation(query, memBlock, kind) {
    var user = '【记忆】\n' + memBlock + '\n\n从上面的记忆里，把与「' + truncate(query, 80) +
      '」有关的数值逐条抽出来：label = 该条对应的事项（简短），value = 数字本身（不要单位）。' +
      '记忆里没有相关数值就返回空数组，不要编造。';
    return gguf.llm.chat({
      messages: [{ role: 'user', content: user }],
      temperature: 0, max_tokens: 256,
      json_schema: {
        type: 'object',
        properties: {
          items: {
            type: 'array',
            items: {
              type: 'object',
              properties: { label: { type: 'string' }, value: { type: 'number' } },
              required: ['label', 'value']
            }
          }
        },
        required: ['items']
      }
    }).then(function (res) {
      var raw = [];
      try { var o = JSON.parse(String((res && res.content) || '')); if (o && Array.isArray(o.items)) raw = o.items; } catch (e) { raw = []; }
      var items = [];
      raw.forEach(function (it) {
        // 注意 Number(null)===0、Number('')===0：受限解码偶尔给出空值，直接 Number() 会把"没有数"
        // 悄悄算成 0，污染总和/计数/平均。先把空值挡掉，只留真的数字。
        if (!it || it.value === null || it.value === undefined || it.value === '') return;
        var v = Number(it.value);
        if (isFinite(v)) items.push({ label: String(it.label || '').trim() || '?', value: v });
      });
      if (!items.length) return null;
      var vals = items.map(function (it) { return it.value; });
      var detail = items.map(function (it) { return it.label + ' ' + fmtNum(it.value); }).join('、');
      var head;
      if (kind === 'count') {
        head = '一共有 ' + items.length + ' 条';
      } else if (kind === 'sum') {
        head = '加起来一共是 ' + fmtNum(vals.reduce(function (a, b) { return a + b; }, 0));
      } else if (kind === 'avg') {
        head = '平均是 ' + fmtNum(vals.reduce(function (a, b) { return a + b; }, 0) / items.length);
      } else {
        var pick = items[0];
        items.forEach(function (it) { if (kind === 'max' ? it.value > pick.value : it.value < pick.value) pick = it; });
        head = (kind === 'max' ? '最多的是' : '最少的是') + '「' + pick.label + '」' + fmtNum(pick.value);
      }
      return { content: '\n\n（我按记忆算了一下）' + head + '。依据：' + detail + '。' };
    }, function () { return null; });
  }

  // ============================== 记忆模型 ==============================
  function ensureMem() {
    if (memReady) return Promise.resolve(true);
    if (!cfg.memModel) return Promise.resolve(false);
    return gguf.memllm.ensure(cfg.memModel).then(function (r) { memReady = !!(r && r.ready); return memReady; })
      .catch(function (e) { memReady = false; backoffUntil = Date.now() + ENSURE_BACKOFF_MS; dbg('ensure 失败: ' + (e && e.message)); return false; });
  }

  // ============================== 闲置做梦 worker ==============================
  function idleReady() {
    var now = Date.now();
    if (working) return false;
    if (busy && now - busySince < MAX_GEN_MS) return false;
    if (now - lastActivityTs < QUIET_MS) return false;
    if (backoffUntil && now < backoffUntil) return false;
    return true;
  }

  function tick() {
    if (!idleReady()) return;
    working = true; abort = false;
    var sid = null, conv = null, st = null;
    Promise.resolve()
      .then(function () { return gguf.chat.current(); })
      .then(function (cur) { if (abort || !cur || cur.id == null) return null; sid = String(cur.id); return gguf.chat.messages(); })
      .then(function (raw) {
        if (abort || sid == null || !raw) return null;
        conv = [];
        for (var i = 0; i < raw.length; i++) { var m = raw[i]; if (m && (m.role === 'user' || m.role === 'assistant') && String(m.content || '').trim()) conv.push(m); }
        // 本会话内容太少：若未开全局记忆则无事可做；开了全局仍要后台索引其它会话（不能被空/短会话卡住）。
        var shortConv = conv.length < MIN_CONV;
        if (shortConv && !cfg.globalMem) return null;
        if (!cfg.memModel) { return load(sid).then(function (s) { if (!s.log.length || s.log[s.log.length - 1].detail.indexOf('未配置') < 0) { pushLog(s, '未配置记忆模型，去设置里选一个'); return save(sid, s); } }); }
        return ensureMem().then(function (ok) {
          if (!ok) return null;
          if (shortConv) return crawlStep(sid);
          return load(sid).then(function (s) { st = s; st.total = conv.length; return step(sid, conv, st); });
        });
      })
      .catch(function (e) { backoffUntil = Date.now() + BACKOFF_MS; dbg('tick error: ' + (e && e.message)); })
      .then(function () { working = false; }, function () { working = false; });
  }

  /** 一次做梦选一件事做（每 tick 一个工作单元，保持单次闲置调用短、可让位）。 */
  function step(sid, conv, st) {
    if (abort) return null;
    // ① 顺序扫描抽取（染色前进）
    if (st.scannedThrough < conv.length) {
      var end = Math.min(st.scannedThrough + SCAN_BATCH, conv.length);
      return scanExtract(sid, conv, st, st.scannedThrough, end, true).then(function () {
        st.scannedThrough = end;
        // 扫到 jump 位置 → 二次升一次、消碎片
        st.jumps = (st.jumps || []).filter(function (j) { return j >= end; });
        return finish(sid, st, '顺序扫描到第 ' + end + ' 条（染色前进）');
      });
    }
    // ② 一轮延迟召回（用户要的「提问检索」，高优先——不能被建边饿死）
    if (lastQuery && lastQuerySid === sid && lastQuery !== st.lastRecalled) {
      return recall(sid, conv, st, lastQuery);
    }
    // ③ 碎片整理（还债：补扫 jump 之下未扫的中段，二次→一次）
    if ((st.jumps || []).length) {
      var j = Math.min.apply(null, st.jumps);
      var e2 = Math.min(j + 1, conv.length);
      return scanExtract(sid, conv, st, Math.min(st.scannedThrough, j), e2, true).then(function () {
        st.jumps = st.jumps.filter(function (x) { return x !== j; });
        return finish(sid, st, '碎片整理：补扫第 ' + j + ' 条（二次→一次）');
      });
    }
    // ④ 发散建边（enrichment，最低优先，闲得没别的事才做）
    var noEdge = st.items.filter(function (it) { return !it.edged; });
    if (noEdge.length) return buildEdges(sid, st, noEdge.slice(0, EDGE_BATCH));
    // ⑤ 全局记忆：本会话彻底无事可做 → 最低优先级主动爬取其它会话（跨会话索引）
    if (cfg.globalMem) return crawlStep(sid);
    return null;
  }

  // ============================== 全局记忆：主动爬取其它会话（跨会话索引） ==============================
  /** 仅全局开启且本会话已无事时进入。挑一个「尚未索引到底」的其它会话，读它的原文抽一批记忆进它自己的
   *  hm:<sid>（原生条目、不打 origin）。用 crawlCache（sid→已索引到底时的会话 updatedAt）避免重复加载，
   *  一整轮找不到活可干就歇 CRAWL_COOLDOWN_MS，避免空转。会话新增消息 → updatedAt 变 → 重新进入待办集。 */
  function crawlStep(currentSid) {
    if (!gguf.sessions || abort) return null;
    var now = Date.now();
    if (crawlCooldownUntil && now < crawlCooldownUntil) return null;
    return gguf.sessions.list().then(function (list) {
      if (!Array.isArray(list) || !list.length) { crawlCooldownUntil = Date.now() + CRAWL_COOLDOWN_MS; return null; }
      return pickAndCrawl(list, String(currentSid), 0);
    }).catch(function (e) { crawlCooldownUntil = Date.now() + CRAWL_COOLDOWN_MS; dbg('crawl err: ' + (e && e.message)); return null; });
  }

  function pickAndCrawl(list, currentSid, i) {
    if (abort) return null;
    if (i >= list.length) { crawlCooldownUntil = Date.now() + CRAWL_COOLDOWN_MS; return null; }  // 一整轮无活 → 歇一会
    var s = list[i], sid = String(s.id), u = s.updatedAt || 0;
    if (sid === currentSid || (u && crawlCache[sid] === u)) return pickAndCrawl(list, currentSid, i + 1);
    return load(sid).then(function (st) {
      if (u && st.crawledAt === u) { crawlCache[sid] = u; return pickAndCrawl(list, currentSid, i + 1); }  // 已索引到底，跳过
      return gguf.sessions.load(s.id).then(function (sess) {
        var raw = (sess && sess.messages) || [], conv = [];
        for (var j = 0; j < raw.length; j++) { var m = raw[j]; if (m && (m.role === 'user' || m.role === 'assistant') && String(m.content || '').trim()) conv.push(m); }
        st.total = conv.length;
        if (conv.length < MIN_CONV || st.scannedThrough >= conv.length) {   // 无足够内容 / 已扫完 → 标记到底
          st.crawledAt = u; crawlCache[sid] = u;
          return save(sid, st).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); });
        }
        var end = Math.min(st.scannedThrough + SCAN_BATCH, conv.length);
        return scanExtract(sid, conv, st, st.scannedThrough, end, true).then(function () {
          st.scannedThrough = end;
          st.jumps = (st.jumps || []).filter(function (x) { return x >= end; });
          if (st.scannedThrough >= conv.length) { st.crawledAt = u; crawlCache[sid] = u; }   // 这轮扫到底
          return finish(sid, st, '全局索引：会话 ' + sid + ' 扫描到第 ' + end + ' 条');
        });
      }, function () { crawlCache[sid] = u; return pickAndCrawl(list, currentSid, i + 1); });   // 该会话读不到，跳过本轮
    });
  }

  /** 用记忆模型从 conv[a,b) 抽取记忆条目（受限解码 · 资料在前排布，据实测更准）。primary=顺序扫达=一次指针。 */
  function scanExtract(sid, conv, st, a, b, primary) {
    if (abort || a >= b) return Promise.resolve();
    var batch = conv.slice(a, b);
    var text = batch.map(function (m) { return speaker(m.role) + truncate(m.content, 200); }).join('\n');
    // 资料在前、指令在后（第三节实测：小模型这样判/抽更准）
    var sys = '你在为长期对话建立记忆。只抽取值得长期记住的稳定事实：用户身份/称呼/偏好/经历、人设、约定、关键事件。';
    var user = '【对话片段】\n' + text + '\n\n从上面片段抽取记忆条目，输出 JSON：{"items":["一句话事实",...]}，' +
      '每条简短独立、能长期成立；没有值得记的就返回空数组。';
    return gguf.memllm.chat({
      messages: [{ role: 'system', content: sys }, { role: 'user', content: user }],
      temperature: 0.2, max_tokens: 240,
      json_schema: { type: 'object', properties: { items: { type: 'array', items: { type: 'string' } } }, required: ['items'] }
    }).then(function (res) {
      var arr = parseArr(String((res && res.content) || ''), 'items');
      var added = addItems(st, arr, a, primary);
      if (added.length) pushLog(st, '抽取记忆 ' + added.length + ' 条');
    });
  }

  /** 发散思考：给一个条目在已有条目里找关联，建双向边。 */
  function buildEdges(sid, st, targets) {
    if (abort || !targets.length) return Promise.resolve();
    var it = targets[0];
    var others = st.items.filter(function (o) { return o.id !== it.id; });
    if (!others.length) { it.edged = true; return save(sid, st); }
    var cand = bm25(it.text, others.map(function (o) { return o.text; }), 8).map(function (h) { return others[h.index]; });
    if (!cand.length) { it.edged = true; return finish(sid, st, ''); }
    var numbered = cand.map(function (o, i) { return (i + 1) + '. ' + truncate(o.text, 60); }).join('\n');
    var user = '【已有记忆】\n' + numbered + '\n\n【目标记忆】' + truncate(it.text, 80) +
      '\n\n上面哪几条与目标记忆有关联（同一人/事/主题/因果）？输出 JSON：{"related":[序号,...]}，没有就空数组。';
    return gguf.memllm.chat({
      messages: [{ role: 'user', content: user }], temperature: 0.2, max_tokens: 80,
      json_schema: { type: 'object', properties: { related: { type: 'array', items: { type: 'integer' } } }, required: ['related'] }
    }).then(function (res) {
      var rel = parseArr(String((res && res.content) || ''), 'related');
      var linked = 0;
      rel.forEach(function (n) {
        var o = cand[(n | 0) - 1]; if (!o) return;
        (st.edges[it.id] = st.edges[it.id] || []); (st.edges[o.id] = st.edges[o.id] || []);
        if (st.edges[it.id].indexOf(o.id) < 0) { st.edges[it.id].push(o.id); linked++; }
        if (st.edges[o.id].indexOf(it.id) < 0) st.edges[o.id].push(it.id);
      });
      it.edged = true;
      return finish(sid, st, linked ? ('发散建边 ' + linked + ' 条（' + truncate(it.text, 14) + '）') : '');
    }, function () { it.edged = true; return save(sid, st); });
  }

  /**
   * 召回：BM25 在【原文消息】里找相关（可跳到 scannedThrough 之上 = 制造碎片），也在【已有条目】里找；
   * L2 用记忆模型逐条判定（资料在前），命中的条目 + 沿边扩散的邻居一起调入存放区。一轮延迟、供下一轮。
   */
  function recall(sid, conv, st, query) {
    if (abort) return Promise.resolve();
    // (a) 原文消息召回——可能命中 scannedThrough 之上的相关消息 → 跳达抽条目(二次指针) + 记碎片
    var msgDocs = conv.map(function (m) { return String(m.content || ''); });
    var msgHits = bm25(query, msgDocs, 3);
    var jumpTargets = msgHits.map(function (h) { return h.index; }).filter(function (i) { return i >= st.scannedThrough; });
    var jumpP = Promise.resolve();
    if (jumpTargets.length) {
      var j = jumpTargets[0];
      // 记碎片：跳过了 [scannedThrough, j) 这段未扫中段
      if (j > st.scannedThrough && st.jumps.indexOf(j) < 0) st.jumps.push(j);
      jumpP = scanExtract(sid, conv, st, j, j + 1, false).then(function () { pushLog(st, '召回跳达第 ' + j + ' 条（二次指针，欠碎片债）'); });
    }
    return jumpP.then(function () {
      // (b) 条目召回 + L2 逐条判定。全局开启：候选并入其它会话条目；命中的会话外条目导入存放区（打 origin）。
      //     全局关闭：只用本会话原生条目（filter 掉带 origin 的会话外拷贝 = 重新隔离）。
      var localCand = st.items.filter(function (it) { return cfg.globalMem || !it.origin; });
      var poolP = cfg.globalMem ? gatherForeignItems(sid) : Promise.resolve([]);
      return poolP.then(function (foreign) {
        var seen = {}; localCand.forEach(function (it) { seen[String(it.text).trim().toLowerCase()] = true; });
        var pool = localCand.slice();
        foreign.forEach(function (f) { var fk = String(f.text).trim().toLowerCase(); if (!seen[fk]) { seen[fk] = true; pool.push(f); } });
        if (!pool.length) { st.lastRecalled = query; return save(sid, st); }
        var cand = bm25(query, pool.map(function (p) { return p.text; }), Math.max(cfg.recallN * 2, 6)).map(function (h) { return pool[h.index]; });
        if (!cand.length) { st.lastRecalled = query; return save(sid, st); }
        return l2Judge(query, cand, cfg.recallN).then(function (hitObjs) {
          var paged = 0, cross = 0;
          hitObjs.forEach(function (o) {
            var localId;
            if (o._foreign) {
              localId = importForeign(st, o.text, o.sid); cross++;
              (o.edges || []).slice(0, 2).forEach(function (nt) { var lid = importForeign(st, nt, o.sid); if (lid != null) pageIn(st, lid); });
            } else localId = o.id;
            if (localId != null) { pageIn(st, localId); paged++; (st.edges[localId] || []).slice(0, 2).forEach(function (nb) { pageInEdge(st, nb); }); }
          });
          st.lastRecalled = query;
          return finish(sid, st, paged ? ('召回并调入存放区 ' + paged + ' 条' + (cross ? '（含跨会话 ' + cross + '）' : '') + ' + 沿边邻居') : '召回无命中');
        });
      });
    });
  }

  /** L2：记忆模型逐条判定候选是否相关（资料在前排布、受限解码枚举），返回命中的【候选对象】
   *  （本会话条目对象含 .id；跨会话候选含 _foreign/.sid/.edges）——由调用方决定 pageIn 还是导入。 */
  function l2Judge(query, cand, want) {
    var hits = [];
    function one(i) {
      if (abort || i >= cand.length || hits.length >= want) return Promise.resolve();
      var it = cand[i];
      var user = '【记忆】' + truncate(it.text, 160) + '\n\n判断上面这条记忆能否帮助回答下面的问题，只输出 相关 或 不相关。\n【问题】' + truncate(query, 120);
      return gguf.memllm.chat({
        messages: [{ role: 'user', content: user }], temperature: 0, max_tokens: 16,
        json_schema: { type: 'string', enum: ['相关', '不相关'] }
      }).then(function (res) {
        var v; try { v = JSON.parse(String((res && res.content) || '')); } catch (e) { v = String((res && res.content) || '').replace(/["\s]/g, ''); }
        if (v === '相关') hits.push(it);
        return one(i + 1);
      }, function () { return one(i + 1); });
    }
    return one(0).then(function () { return hits; });
  }

  function finish(sid, st, detail) { if (detail) pushLog(st, detail); return save(sid, st).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); }); }

  function parseArr(content, field) {
    try { var o = JSON.parse(content); if (o && Array.isArray(o[field])) return o[field]; } catch (e) {}
    var out = [], m = content.match(new RegExp('"' + field + '"\\s*:\\s*\\[([\\s\\S]*)'));
    var body = m ? m[1] : content, re = /"((?:[^"\\]|\\.)*)"|(-?\d+)/g, mm;
    while ((mm = re.exec(body))) { if (mm[1] !== undefined) { try { out.push(JSON.parse('"' + mm[1] + '"')); } catch (e2) { out.push(mm[1]); } } else if (mm[2] !== undefined) out.push(parseInt(mm[2], 10)); }
    return out;
  }

  setInterval(function () { try { tick(); } catch (e) { dbg('tick throw: ' + (e && e.message)); } }, TICK_MS);

  // ============================== UI 命令 ==============================
  gguf.events.on('force-tick', function () { lastActivityTs = 0; busy = false; backoffUntil = 0; tick(); });
  gguf.events.on('reset', function () {
    gguf.chat.current().then(function (cur) { if (!cur || cur.id == null) return; var sid = String(cur.id); var fresh = blank(); pushLog(fresh, '已清空本会话重型记忆'); return save(sid, fresh).then(function () { gguf.events.broadcast('mem-changed', { sessionId: sid }); }); }).catch(function () {});
  });
})();
