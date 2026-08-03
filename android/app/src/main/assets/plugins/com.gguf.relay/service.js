/*
 * 外援（com.gguf.relay）—— 推理能力点的提供者（《工具插件规范》§6.16）。
 *
 * 这个项目的立场是「一切在本机」，本插件是那条立场的**保底出口**：本机小模型有它的天花板
 * （2B 写得出旋律但写不长、判得出宾语但综合判断就跟着选项顺序走），需要更强的模型时，
 * 让插件发起的推理调用改道到一个 OpenAI 兼容端点，其余一切不变。
 *
 * 关键设计：
 * - **接管的是「插件发起的推理」，不是整个对话**。乐句编曲、便签抽取、参数判断、记忆摘要都会
 *   受益，而用户与模型的正常对话仍然走本机（想整轮走外部是另一件事，见 §6.16 的「不做什么」）。
 * - **默认关**，且必须自己填端点与 key。打开时设置卡片会明说：这些内容会离开这台手机。
 * - **回落方向永远朝本机**：没配好、被筛掉、请求失败、格式不对——一律返回 {}，宿主照旧打本机
 *   模型。保底不该反过来变成新的单点故障。
 * - **只按调用方筛选，不猜内容**。哪些插件走外援由用户在设置里勾，插件不去揣摩「这次难不难」。
 */
(function () {
  'use strict';

  var DEFAULT_CFG = {
    enabled: false,
    endpoint: '',                 // 例：https://api.openai.com/v1/chat/completions
    apiKey: '',
    model: '',
    only: [],                     // 空 = 所有插件都走外援；非空 = 只有列出的插件 id 走
    timeoutMs: 60000,
  };

  var cfgCache = null;
  function cfg() {
    if (cfgCache) return Promise.resolve(cfgCache);
    return gguf.storage.get('cfg').then(function (v) {
      var c = {};
      for (var k in DEFAULT_CFG) if (Object.prototype.hasOwnProperty.call(DEFAULT_CFG, k)) c[k] = DEFAULT_CFG[k];
      if (v && typeof v === 'object') for (var k2 in v) if (Object.prototype.hasOwnProperty.call(v, k2)) c[k2] = v[k2];
      cfgCache = c;
      return c;
    }).catch(function () { return DEFAULT_CFG; });
  }
  gguf.events.on('relay-cfg-changed', function () { cfgCache = null; });

  /** 统计：让用户在设置卡片上看得见「外援接了多少次、失败多少次」，而不是凭感觉。 */
  function bump(field) {
    return gguf.storage.get('stat').then(function (s) {
      var st = (s && typeof s === 'object') ? s : { ok: 0, fail: 0, skip: 0 };
      st[field] = (st[field] || 0) + 1;
      return gguf.storage.set('stat', st);
    }).catch(function () {});
  }

  function takes(c, from) {
    if (!c.enabled) return false;
    if (!c.endpoint || !c.model) return false;
    if (c.only && c.only.length) return c.only.indexOf(from) >= 0;
    return true;
  }

  /**
   * 调外部端点。**必须经 gguf.net.fetchJson**：插件页面源是 https://plugin.gguf，
   * 直接 fetch 云端 API 一律撞 CORS（人家不会为一个虚拟域名开 CORS）。
   */
  function callRemote(c, body) {
    var headers = { Authorization: 'Bearer ' + c.apiKey };
    return gguf.net.fetchJson({
      url: c.endpoint,
      json: body,
      headers: headers,
      timeoutMs: c.timeoutMs,
    }).then(function (r) {
      if (!r || r.status < 200 || r.status >= 300) {
        throw new Error('HTTP ' + ((r && r.status) || '?') + ' ' + String((r && r.body) || '').slice(0, 120));
      }
      var j = JSON.parse(r.body);
      var msg = j && j.choices && j.choices[0] && j.choices[0].message;
      return String((msg && msg.content) || '');
    });
  }

  /** classify 的解析与宿主同规矩：认出现位置最靠前的标签（并列取更长的），认不出返回 null。 */
  function pickLabel(text, labels) {
    var best = null;
    for (var i = 0; i < labels.length; i++) {
      var at = String(text || '').indexOf(labels[i]);
      if (at < 0) continue;
      if (!best || at < best.at || (at === best.at && labels[i].length > best.label.length)) {
        best = { at: at, label: labels[i] };
      }
    }
    return best ? best.label : null;
  }

  gguf.hooks.llm(function (payload) {
    var action = payload && payload.action;
    var from = (payload && payload.from) || '';
    return cfg().then(function (c) {
      if (!takes(c, from)) { bump('skip'); return {}; }          // 不接：宿主回落本机模型

      if (action === 'chat') {
        var body = {
          model: c.model,
          messages: payload.messages || [],
          stream: false,
        };
        if (typeof payload.temperature === 'number') body.temperature = payload.temperature;
        if (typeof payload.max_tokens === 'number') body.max_tokens = payload.max_tokens;
        // json_schema 不透传：各家云端 API 的结构化输出参数互不相同（response_format /
        // tools / 自家字段），硬塞过去多半 400。调用方本来就有宽容解析兜底（乐句的 looseJson）。
        return callRemote(c, body).then(function (content) {
          bump('ok');
          return { content: content };
        });
      }

      if (action === 'classify') {
        var labels = payload.labels || [];
        if (labels.length < 2) { bump('skip'); return {}; }
        var instruction = String(payload.question || '')
          + '\n只回答下列之一，不要解释：' + labels.join(' / ');
        // 与宿主同构：判断指令并进末条用户消息（不新增角色，任何模板都合法），自由生成后解析
        var msgs = (payload.messages || []).map(function (m) { return m; });
        var lastUser = -1;
        for (var i = msgs.length - 1; i >= 0; i--) if (msgs[i] && msgs[i].role === 'user') { lastUser = i; break; }
        if (lastUser >= 0 && typeof msgs[lastUser].content === 'string') {
          msgs = msgs.slice();
          msgs[lastUser] = { role: 'user', content: msgs[lastUser].content + '\n\n' + instruction };
        } else {
          msgs = msgs.concat([{ role: 'system', content: instruction }]);
        }
        return callRemote(c, { model: c.model, messages: msgs, stream: false, temperature: 0, max_tokens: 16 })
          .then(function (content) {
            var label = pickLabel(content, labels);
            bump(label ? 'ok' : 'fail');
            // 认不出标签就当没接管：宿主会拿本机模型再判一次，好过把一个错标签递出去
            return label ? { label: label } : {};
          });
      }

      bump('skip');
      return {};
    }).catch(function (e) {
      bump('fail');
      gguf.ui.toast('外援没接住：' + ((e && e.message) || e) + '，本轮用本机模型');
      return {};                                                  // 一切失败都朝本机回落
    });
  });
})();
