/*
 * 便签插件 service.js —— 原 ChatViewModel 便签逻辑的逐条移植（《工具插件规范》§11 参考实现）。
 * 三条管线：清空（纯代码）、编辑（两阶段：自由思考 + json_schema 受限执行）、记录（json_schema
 * 提取 + 正则兜底）。任何一步失败都不动便签（轮椅哲学：模型只管语义，关键动作代码保底）。
 * 存储：gguf.storage 键 note:<sessionId>，值 { note, history:[{ts,note,label}] 上限 100 }。
 */
(function () {
  'use strict';

  // ============================== 意图正则（与原 ChatViewModel 逐字一致） ==============================

  // 记录意图（原 noteIntentRe）：需要「帮我/给我/请/麻烦 + 记…」，或明确提到便签，或英文 note down / remember this
  var noteIntentRe = new RegExp(
    '^(?:please\\s+)?(?:(?:请|麻烦)?(?:帮我|给我)(?:记一下|记下来?|记录一下|记到便签里?|写到便签里?)|' +
      '(?:记到|写到|加到)便签里?|' +
      'note (?:this |it )?down(?: for me)?|remember this)[：:，,、。!！\\s]*([\\s\\S]*)$',
    'i'
  );
  // 编辑意图（原 isNoteEditRequest）：必须显式提到「便签」+ 编辑动词，避免误伤普通对话
  var editVerbRe = /改|更新|替换|换成|删|去掉|清空/;
  // 清空意图（原 noteClear）：编辑意图之上再命中清空类说法
  var clearRe = /清空|全部删除|删除全部|全都删|都删掉|所有.*删/;

  function extractUserNoteRequest(msg) {
    var m = noteIntentRe.exec(String(msg || '').trim());
    if (!m) return null;
    var body = (m[1] || '').trim();
    return body ? body : null;
  }

  function isNoteEditRequest(msg) {
    var s = String(msg || '');
    return s.indexOf('便签') >= 0 && editVerbRe.test(s);
  }

  // ============================== BM25-lite（原 MemoryIndex 的 JS 版，序号定位兜底用） ==============================

  function isCjk(code) { return code >= 0x4E00 && code <= 0x9FFF; }

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

  /** 在 docs 中检索与 query 相关的条目，返回 [{index, score}]（分数>0，降序，最多 topK 条）。 */
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

  // ============================== 存储（唯一写入口保快照语义） ==============================

  function storageKey(sessionId) { return 'note:' + sessionId; }

  function loadNoteState(sessionId) {
    return gguf.storage.get(storageKey(sessionId)).then(function (v) {
      if (!v || typeof v !== 'object') return { note: '', history: [] };
      return { note: String(v.note || ''), history: Array.isArray(v.history) ? v.history : [] };
    });
  }

  /**
   * 便签唯一写入口（对应原 setNote）：内容真的变化才生效，并留一条历史快照（保存修改后的状态，
   * 上限 100 条）。route/handle/tool/UI 所有写入都必须走这里。
   */
  function setNote(sessionId, newNote, label) {
    var n = String(newNote || '').trim();
    return loadNoteState(sessionId).then(function (st) {
      if (n === st.note) return false;
      var history = st.history.concat([{ ts: Date.now(), note: n, label: String(label || '') }]);
      if (history.length > 100) history = history.slice(history.length - 100);
      return gguf.storage.set(storageKey(sessionId), { note: n, history: history }).then(function () {
        // 通知本插件的 UI 面板（若开着）刷新
        gguf.events.broadcast('note-changed', { sessionId: sessionId });
        return true;
      });
    });
  }

  function noteLines(note) {
    return String(note || '').split('\n').map(function (s) { return s.trim(); }).filter(function (s) { return !!s; });
  }

  /**
   * 追加便签行（对应原 appendNoteLines，唯一的追加入口，边界规则集中在此）：
   * 逐行去重——与现有行完全相同的不再追加。返回真正新增的行字符串，全是重复则返回 null。
   */
  function appendNoteLines(sessionId, text) {
    return loadNoteState(sessionId).then(function (st) {
      var existing = noteLines(st.note);
      var added = [];
      noteLines(text).forEach(function (line) {
        if (existing.indexOf(line) < 0) {
          existing.push(line);
          added.push(line);
        }
      });
      if (!added.length) return null;
      return setNote(sessionId, existing.join('\n'), '记录：' + added[0].slice(0, 20)).then(function () {
        return added.join('\n');
      });
    });
  }

  // ============================== route：快速意图判定（禁止调 LLM） ==============================

  gguf.hooks.route(function (turn) {
    if (!turn || !turn.sessionId) return {};
    var text = String(turn.text || '');
    if (isNoteEditRequest(text)) return { takeOver: true };       // 编辑/清空（清空是编辑的子集）
    if (extractUserNoteRequest(text) !== null) return { takeOver: true }; // 记录
    return {};
  });

  // ============================== curate：本轮要不要把 notes.append 露给模型（§13.4，快判定禁 LLM） ==============================

  // 显式的「帮我记一下」已被上面的 route 接管（根本走不到 curate）；这里管的是**隐含**场景：
  // 用户随口提到一件带时间/承诺性质的事，模型可以自己决定要不要在回复末尾补 <tool name="notes.append">。
  // 与本轮无关的闲聊一律不露——省下的是 1B 极其金贵的 system 预算（这条教学条款约 60 token），
  // 也少一个让小模型"手痒乱调"的诱因。漏判无伤：用户显式说「帮我记一下」永远走 route 确定性接管。
  // **判定结构（1.0.3 重做）**：原先要求「显式记录词」或「时间词 ∧ 承诺动词」双命中，标注语料实测
  // recall 只有 48%、precision 100%——过严，与「宁可判宽」的取向正好相反。漏判是**能力静默消失**
  // （用户说了件该记的事，模型手里根本没有这个工具），误露只是白花约 40 token 的教学条款，代价差着
  // 数量级。所以改成「**一个正信号 + 一道否定闸门**」：
  //   否定闸门 askRe —— 这句是在问问题 / 指使干活，不是在陈述要记的事（"明天天气怎么样"）。
  //   正信号 ①显式记录词 ②具体时刻/日期（非疑问句里出现钟点或月日，基本都是约定）
  //          ③时间词 ∧ 承诺动词（原规则保留）④耐久事实（密码/号码/位置/过敏/尺码…）
  // 语料与量法见 scripts/check-plugins.js 的 curate 段（新口径 recall 97% / precision 100%）。
  var noteWorthRe = /便签|备忘|待办|记(?:一下|下来|录|住)|别忘|忘不了|提醒我?|todo|note (?:this |that )?down|remember (?:this|that)|don'?t forget/i;
  // 「记得」两义：祈使＝提醒（记得帮我买牛奶）算记录意图；陈述＝回忆（我记得你说过…）不算。
  // 不用 lookbehind（旧 WebView 不一定支持），用一条排除式区分。
  var remindRe = /记得/;
  var recallRe = /我(?:还|依然|一直)?记得|印象(?:中|里)|好像记得|如果我没记错/;
  var timeHintRe = /今天|明天|后天|大后天|下周|下星期|下个?月|月底|月初|年底|周末|周[一二三四五六日天]|星期[一二三四五六日天]|\d{1,2}\s*[月日号]|\d{1,2}\s*[:：]\s*\d{2}|早上|上午|中午|下午|晚上|凌晨/;
  var commitHintRe = /要去|得去|需要|计划|安排|约(?:了|好|在)|预约|截止|deadline|开会|体检|还(?:款|钱|信用卡|贷|花呗)|生日|出差|面试|考试|报名|缴费|入职|离职|休假|请假|搬家|搬走|出发|到达|开始|结束|签(?:了|字|约|合同)|接(?:人|机|站|孩子)|交(?![通流易易换往接谈朋])|上映|开庭|办理/;
  // 具体时刻/日期：钟点（七点半 / 10:30 / 下午三点）或月日（3月15号）。非疑问句里出现它，几乎都是约定。
  var sharpTimeRe = /\d{1,2}\s*[:：]\s*\d{2}|\d{1,2}\s*[月日号]|[零一二两三四五六七八九十]+\s*点(?:半|钟)?|\d{1,2}\s*点(?:半|钟)?/;
  // 耐久事实：日后可能要翻出来查的具体值（凭据 / 号码 / 位置 / 身体 / 尺码 / 纪念日）。
  var factRe = /密码|口令|账号|账户|卡号|房号|车位|工号|学号|订单号|快递单|手机号|电话号|号码|邮箱|地址|放在|存在|藏在|收在|锁在|过敏|忌口|血型|尺码|码的鞋|穿.{0,3}码|纪念日/;
  // 形似凭据的串：邮箱、4 位以上数字、字母+数字编号（B2-108 / abc12345）。
  var idLikeRe = /[A-Za-z0-9._%-]+@[A-Za-z0-9.-]+|\d{4,}|[A-Za-z]{1,4}\d{2,}|[A-Za-z]\d[-–]\d{2,}/;
  // 否定闸门：疑问句 / 指使干活。命中它就只认显式记录词（①），其余正信号一律不算。
  var askRe = /[?？]\s*$|吗\s*[?？]?\s*$|呢\s*[?？]?\s*$|怎么样|如何|为什么|为啥|什么时候|哪个|哪些|多少钱|好不好|行不行|^\s*(?:帮我|请|麻烦|给我|你)?\s*(?:写|翻译|解释|生成|画|总结|改一?下|优化|润色|讲讲|说说|介绍|分析|查一?下|搜一?下|算一?下|推荐)/;

  gguf.hooks.curate(function (turn) {
    if (!turn || !turn.sessionId) return { expose: [] };   // 无会话（外部请求）：便签无从依附，露了也没用
    var text = String(turn.text || '');
    // ① 显式：不受否定闸门约束（「记得」要排掉「我记得…」那种回忆用法）
    if (noteWorthRe.test(text) || (remindRe.test(text) && !recallRe.test(text))) {
      return { expose: ['notes.append'] };
    }
    if (askRe.test(text)) return { expose: [] };                       // 在提问/指使，不是在交代事情
    var worth = sharpTimeRe.test(text)                                  // ②
      || (timeHintRe.test(text) && commitHintRe.test(text))             // ③
      || factRe.test(text) || idLikeRe.test(text);                      // ④
    return { expose: worth ? ['notes.append'] : [] };
  });

  // ============================== handle：接管回合 ==============================

  gguf.hooks.handle(function (turn, ctl) {
    var text = String(turn.text || '');
    var sessionId = turn.sessionId;
    var edit = isNoteEditRequest(text);
    var clear = edit && clearRe.test(text);

    var p;
    if (clear) {
      // 清空：纯代码，不调模型
      p = setNote(sessionId, '', '清空').then(function () {
        ctl.finish({ notice: '已清空本会话便签（可在便签历史里恢复）' });
      });
    } else if (edit) {
      p = loadNoteState(sessionId).then(function (st) {
        if (!st.note) {
          ctl.finish({ content: '便签目前是空的，没有可修改的内容。' });
          return;
        }
        return runNoteOp(turn, ctl, st);
      });
    } else {
      p = runNoteExtract(turn, ctl);
    }
    return p.catch(function () {
      // 任何异常都不动便签，统一话术
      ctl.finish({ content: '这条便签操作没执行成功，请换个说法再试一次。' });
    });
  });

  /**
   * 便签操作两阶段管线（原 runNoteOp 的逐条移植）：
   * 阶段一「思考」：带最近对话，自由文本分析——要做什么操作、对哪条、新内容、依据；
   * 阶段二「执行」：纯净上下文（只有便签清单 + 思考结论 + 原请求），json_schema
   * 受限解码输出完整动作空间 {action: append/update/delete/clear/none, index, content}，
   * 代码按动作精确执行。任何一步失败都不动便签。
   */
  function runNoteOp(turn, ctl, st) {
    ctl.status('正在整理便签…');
    var items = noteLines(st.note);
    var numbered = items.map(function (s, i) { return (i + 1) + '. ' + s; }).join('\n');
    var userText = String(turn.text || '');
    var sessionId = turn.sessionId;

    // 阶段一：思考（带最近 5 条截断对话帮助指代消解，每条截 200 字控制预填充）
    var planMsgs = [{
      role: 'system',
      content:
        '你是便签管家。当前便签共 ' + items.length + ' 条：\n' + numbered + '\n\n' +
        '用户提出了一个便签操作请求。结合最近对话，在上面的清单里找出用户指的是哪一条' +
        '（用户可能只说关键词，比如说『备份』指的就是包含备份的那条），然后给出结论：' +
        '操作类型（追加/修改/删除/清空/无操作）+ 目标条目的序号和原文 + 新内容（如有）。' +
        '直接下结论，不要反问。',
    }];
    (turn.messages || [])
      .filter(function (m) { return m.role !== 'system' && String(m.content || '').trim(); })
      .slice(-5)
      .forEach(function (m) {
        planMsgs.push({ role: m.role, content: String(m.content).slice(0, 200) });
      });

    return gguf.llm.chat({ messages: planMsgs, temperature: 0.2, max_tokens: 160 }).then(function (planRes) {
      var plan = String((planRes && planRes.content) || '').trim();

      // 阶段二：执行（纯净上下文 + 完整动作空间的受限解码）
      var execSys =
        '你是便签操作执行器。当前便签共 ' + items.length + ' 条：\n' + numbered + '\n\n' +
        '对本次请求的分析结论：' + plan + '\n\n' +
        '据此输出 JSON：action = append(追加)/update(修改)/delete(删除单条)/clear(清空全部)/none(不操作)，' +
        'index = 目标条目序号（append/clear/none 时填 0），content = 新内容（delete/clear/none 时为空字符串）。';
      return gguf.llm.chat({
        messages: [
          { role: 'system', content: execSys },
          { role: 'user', content: userText },
        ],
        temperature: 0.2,
        max_tokens: 200,
        json_schema: {
          type: 'object',
          properties: {
            action: { type: 'string', enum: ['append', 'update', 'delete', 'clear', 'none'] },
            index: { type: 'integer' },
            content: { type: 'string' },
          },
          required: ['action', 'index', 'content'],
        },
      });
    }).then(function (execRes) {
      var args = null;
      try { args = JSON.parse(String((execRes && execRes.content) || '')); } catch (e) { args = null; }
      var action = args ? String(args.action || '') : '';
      var index = args && typeof args.index === 'number' ? args.index : 0;
      var content = args ? String(args.content || '').trim() : '';

      var lines = items.slice();
      // 序号无效时用 BM25 按用户原话的关键词定位（「删掉备份那条」→ 含“备份”的行）
      function locate() {
        var i = index - 1;
        if (i >= 0 && i < lines.length) return i;
        var hits = bm25Search(userText, lines, 1);
        return hits.length ? hits[0].index : -1;
      }

      var failText = '这条便签操作没执行成功，请换个说法再试一次。';
      if (action === 'append' && content) {
        return appendNoteLines(sessionId, content).then(function (added) {
          ctl.finish({ notice: added ? '已记入便签：' + added : '便签里已有这条，未重复添加' });
        });
      }
      if (action === 'update') {
        var iu = locate();
        if (iu >= 0 && content) {
          lines[iu] = content;
          return setNote(sessionId, lines.join('\n'), '修改第' + (iu + 1) + '条').then(function () {
            ctl.finish({ notice: '已更新便签第' + (iu + 1) + '条：' + content });
          });
        }
        ctl.finish({ content: failText });
        return;
      }
      if (action === 'delete') {
        var idl = locate();
        if (idl >= 0) {
          var old = lines.splice(idl, 1)[0];
          return setNote(sessionId, lines.join('\n'), '删除：' + old.slice(0, 20)).then(function () {
            ctl.finish({ notice: '已从便签删除：' + old });
          });
        }
        ctl.finish({ content: failText });
        return;
      }
      if (action === 'clear') {
        return setNote(sessionId, '', '清空').then(function () {
          ctl.finish({ notice: '已清空本会话便签（可在便签历史里恢复）' });
        });
      }
      if (action === 'none') {
        ctl.finish({ content: '我理解这次不需要改动便签。要操作的话请说明是哪一条、改成什么。' });
        return;
      }
      ctl.finish({ content: failText });
    });
  }

  /**
   * 记录意图管线（原 runNoteTool 的移植）：json_schema 受限解码提取要记的内容
   * （从第一个 token 就硬约束输出合法 JSON，1B 也 100% 结构可靠），失败退回正则兜底
   * （原 fallbackNoteFromUser 语义），两条路都没记上才道歉——托付绝不落空。
   */
  function runNoteExtract(turn, ctl) {
    ctl.status('正在记录…');
    var sessionId = turn.sessionId;
    var msgs = [{
      role: 'system',
      content: '你是用户的备忘助手。把用户最新要求记录的内容提取出来，' +
        '输出 JSON：content = 要记录的要点（简洁、完整、保留关键细节）。',
    }];
    (turn.messages || []).forEach(function (m) {
      if (m.role !== 'system') msgs.push({ role: m.role, content: String(m.content || '') });
    });

    function finishWith(text) {
      return appendNoteLines(sessionId, text).then(function (added) {
        ctl.finish({ notice: added ? '已记入便签：' + added : '便签里已有这条，未重复添加' });
      });
    }
    function fallback() {
      var body = extractUserNoteRequest(turn.text);
      if (body) return finishWith(body);
      ctl.finish({ content: '抱歉，这条没记上，请再说一次要记的内容。' });
    }

    return gguf.llm.chat({
      messages: msgs,
      temperature: 0.2,
      max_tokens: 256,
      json_schema: { type: 'object', properties: { content: { type: 'string' } }, required: ['content'] },
    }).then(function (res) {
      var noted = null;
      try { noted = String(JSON.parse(String((res && res.content) || '')).content || '').trim() || null; } catch (e) { noted = null; }
      if (noted) return finishWith(noted);
      return fallback();
    }, function () {
      return fallback();
    });
  }

  // ============================== context：便签拼进首条 system（head，逐轮字节稳定） ==============================

  gguf.hooks.context(function (turn) {
    if (!turn || !turn.sessionId) return {};
    return loadNoteState(turn.sessionId).then(function (st) {
      var items = noteLines(st.note);
      if (!items.length) return {};
      // 与原 buildHistory 的拼法逐字一致；便签不变则逐轮字节稳定（KV 前缀缓存友好）
      var head = '本会话便签（用户备忘，按条编号，直接利用即可，不要复述或提及它）：\n' +
        items.map(function (s, i) { return (i + 1) + '. ' + s; }).join('\n');
      return { head: head };
    });
  });

  // ============================== tool：模型主动 <tool name="notes.append"> 标记（替代原 <note> 协议） ==============================

  gguf.hooks.tool(function (name, input, turn) {
    if (name !== 'notes.append' || !turn || !turn.sessionId) return {};
    var text = String(input || '').trim();
    if (!text) return {};
    return appendNoteLines(turn.sessionId, text).then(function (added) {
      return { notice: added ? '已记入便签：' + added : '便签里已有这条，未重复添加' };
    });
  });
})();
