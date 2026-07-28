/*
 * gguf-sdk.js —— 注入到每个插件 WebView（service.js 的隐藏后台 / ui.html 的可见面板）里的
 * 垫片，把宿主 __ggufHost 的原始回调协议包装成《工具插件规范》§8 描述的 window.gguf Promise API。
 * 插件作者不需要读这个文件——它是传输细节，不是接口本身。
 *
 * 宿主在加载本文件之前，已经往 window.__ggufInit 塞好了：
 *   { permissions: string[], plugin: {id,name,version}, host: {appVersion,model,contextSize,serverRunning} }
 */
(function () {
  'use strict';
  var init = window.__ggufInit || { permissions: [], plugin: {}, host: {} };
  var granted = {};
  (init.permissions || []).forEach(function (p) { granted[p] = true; });

  // ---- 通用 request/resolve 协议：JS 发起 -> 宿主异步处理 -> __ggufResolve/__ggufReject 回传 ----
  var reqSeq = 0;
  var pendingReq = {}; // reqId -> {resolve, reject}

  function callHost(api, args) {
    return new Promise(function (resolve, reject) {
      var reqId = 'r' + (++reqSeq);
      pendingReq[reqId] = { resolve: resolve, reject: reject };
      try {
        window.__ggufHost.request(reqId, api, JSON.stringify(args === undefined ? {} : args));
      } catch (e) {
        delete pendingReq[reqId];
        reject(e);
      }
    });
  }

  window.__ggufResolve = function (reqId, resultJson) {
    var p = pendingReq[reqId];
    if (!p) return;
    delete pendingReq[reqId];
    var value;
    try { value = resultJson ? JSON.parse(resultJson) : undefined; } catch (e) { value = undefined; }
    p.resolve(value);
  };

  window.__ggufReject = function (reqId, message) {
    var p = pendingReq[reqId];
    if (!p) return;
    delete pendingReq[reqId];
    p.reject(new Error(message || '插件桥调用失败'));
  };

  // ---- gguf.llm.chatStream 的增量推送 ----
  var streamHandlers = {};
  window.__ggufStreamDelta = function (streamId, piece) {
    var fn = streamHandlers[streamId];
    if (fn) { try { fn(piece); } catch (e) { /* 插件自己的回调出错不影响流继续 */ } }
  };

  // ---- 钩子注册表：route/curate/handle/context/params/afterReply/continuation/tool/messageAction/tts ----
  var hookHandlers = {};
  function register(name) {
    return function (fn) { hookHandlers[name] = fn; };
  }

  // 宿主调用入口：执行已注册钩子。未注册时立即给出空结果，绝不让宿主等到超时。
  window.__ggufDispatch = function (callId, hook, payloadJson) {
    var payload;
    try { payload = payloadJson ? JSON.parse(payloadJson) : {}; } catch (e) { payload = {}; }
    var handler = hookHandlers[hook];

    function respond(obj) {
      try { window.__ggufHost.respond(callId, JSON.stringify(obj || {})); } catch (e) { /* ignore */ }
    }
    function respondError(err) {
      respond({ __error: String((err && err.message) || err || (hook + ' 钩子异常')) });
    }

    if (!handler) { respond({}); return; }

    try {
      if (hook === 'tool') {
        var r = handler(payload.name, payload.input, payload.turn);
        if (r && typeof r.then === 'function') r.then(respond, respondError); else respond(r);
        return;
      }
      if (hook === 'handle') {
        var ctl = {
          status: function (text) { try { window.__ggufHost.status(callId, String(text)); } catch (e) {} },
          finish: function (obj) { respond(obj || {}); },
        };
        var hr = handler(payload, ctl);
        // handle 靠 ctl.finish() 完成，这里只兜底捕获同步/异步异常，不重复 respond。
        if (hr && typeof hr.then === 'function') hr.catch(respondError);
        return;
      }
      var result = handler(payload);
      if (result && typeof result.then === 'function') {
        result.then(respond, respondError);
      } else {
        respond(result);
      }
    } catch (e) {
      respondError(e);
    }
  };

  // ---- 事件：show/hide/enable/disable + 插件内广播 ----
  var eventHandlers = {};
  window.__ggufEmit = function (name, payloadJson) {
    var list = eventHandlers[name];
    if (!list || !list.length) return;
    var payload;
    try { payload = payloadJson && payloadJson !== 'null' ? JSON.parse(payloadJson) : undefined; } catch (e) { payload = undefined; }
    list.slice().forEach(function (fn) { try { fn(payload); } catch (e) { /* ignore */ } });
  };

  // ---- 组装 window.gguf ----
  var gguf = {
    version: 1,
    plugin: init.plugin || {},
    host: init.host || {},
    storage: {
      get: function (key) {
        return callHost('storage.get', { key: key }).then(function (r) { return r && 'value' in r ? r.value : null; });
      },
      set: function (key, value) { return callHost('storage.set', { key: key, value: value }); },
      remove: function (key) { return callHost('storage.remove', { key: key }); },
      keys: function () { return callHost('storage.keys', {}).then(function (r) { return (r && r.keys) || []; }); },
    },
    hooks: {
      route: register('route'),
      // 本轮给模型露哪些 callable（§13.4）：入参 turn，返回 { expose: ["工具名", …] }。
      // 请求路径同步点、总预算 150ms —— 必须是正则/关键词级快判定，禁止在这里调 LLM。
      // 不返回 expose（或没有任何插件登记 curate）= 保持默认「全露」。
      curate: register('curate'),
      handle: register('handle'),
      context: register('context'),
      afterReply: register('afterReply'),
      continuation: register('continuation'),
      params: register('params'),
      tool: register('tool'),
      // 消息角标点击（§6.11）：入参 { actionId, message: {id, role, content, reasoning, ts}, sessionId }
      messageAction: register('messageAction'),
      // 发声能力提供者（§6.13，manifest 声明 provides:["tts"] 后被分发）：
      // 入参 { action: 'speak'|'stop'|'status', from: 调用方插件id, text?, pitch?, rate? }。
      // status 需返回 { ready, speaking }。钩子内再调 gguf.tts.* = 调穿到宿主默认实现（系统 TTS）。
      tts: register('tts'),
      // 推理能力提供者（§6.16，manifest 声明 provides:["llm"] 后被分发）：
      // 入参 { action: 'chat'|'classify', from: 调用方插件id, ...原调用参数 }。
      // 返回 {} = 我不接这一次（宿主照旧打本机模型）——「保底」的回落方向。
      // 钩子内再调 gguf.llm.* = 调穿到本机模型（提供者自己调不会递归回自己）。
      llm: register('llm'),
    },
    events: {
      on: function (name, fn) {
        (eventHandlers[name] = eventHandlers[name] || []).push(fn);
      },
      broadcast: function (name, payload) {
        callHost('events.broadcast', { name: name, payload: payload }).catch(function () {});
      },
    },
    ui: {
      close: function () { callHost('ui.close', {}).catch(function () {}); },
      setBadge: function (n) { callHost('ui.setBadge', { n: n | 0 }).catch(function () {}); },
      toast: function (text) { callHost('ui.toast', { text: String(text) }).catch(function () {}); },
    },
    // 系统语音朗读：WebView 没有 speechSynthesis，发声必须经宿主的 TextToSpeech。
    // speak 后点顶掉先点；status() → { ready, speaking }。与 ui.* 一样不需声明权限。
    // 发声是可接管的能力点（§6.13）：有提供者插件时这些调用会改道进它；提供者自己调用
    // 则直达系统默认实现，opts 可带 { pitch, rate }（0.5~2.0）做变调——补丁的「调穿」参数。
    tts: {
      speak: function (text, opts) {
        var a = { text: String(text) };
        if (opts && typeof opts.pitch === 'number') a.pitch = opts.pitch;
        if (opts && typeof opts.rate === 'number') a.rate = opts.rate;
        return callHost('tts.speak', a);
      },
      stop: function () { return callHost('tts.stop', {}); },
      status: function () { return callHost('tts.status', {}); },
      // 合成到 WAV 不出声，返回 { url }（本插件虚拟域名下的音频地址）——变声/RVC 类
      // 提供者的取料口：fetch(url) → Web Audio 解码 → 二次处理 → 自己播放。
      // 注意 OEM TTS 引擎普遍忽略 pitch 参数，想改音色只有音频级处理这一条可靠路。
      synthesize: function (text) { return callHost('tts.synthesize', { text: String(text) }); },
      // 端侧神经 TTS（sherpa-onnx VITS，完全离线）：opts { text, sid?, speed?, model? } → { url }。
      // 多说话人模型（如 aishell3 174 音色）用 sid 选音色；localStatus() → { available, models }。
      synthesizeLocal: function (opts) { return callHost('tts.synthesizeLocal', opts || {}); },
      localStatus: function () { return callHost('tts.localStatus', {}); },
    },
  };

  // host 快照上再挂一个方法：列出可用 GGUF（设置卡片选「记忆模型」用）。不需权限，只返回文件名/路径/大小。
  gguf.host = gguf.host || {};
  gguf.host.models = function () { return callHost('host.models', {}); };

  // 设置卡片 / 消息卡片自适应高度：把内容高度回报给宿主（宿主据此把卡片撑到内容高度，
  // 避免固定高度把滑块、钢琴卷帘等内容裁掉；面板 / 后台 WebView 的回报会被宿主忽略）。
  //
  // **量 body，不量 documentElement**（2026-07-28 修）：宿主会把 WebView 的高度设成上报值，
  // 此后 `documentElement.scrollHeight` 至少等于视口高——被 WebView 自己撑着，于是「内容变矮」
  // 永远报不回去，而且 documentElement 的尺寸不再变化、连 ResizeObserver 都不触发。真机现象：
  // 长按乐句卡片展开看音符，把下面的「复制/朗读/下载」推下去；再收起时卡片不缩回，留一大块空白。
  // body 是 block、高度由内容决定，缩得回去；它的 margin 不含在 rect 里，要单独加。
  (function () {
    var last = 0;
    function report() {
      try {
        var b = document.body;
        if (!b) return;
        var cs = window.getComputedStyle ? window.getComputedStyle(b) : null;
        var m = cs ? (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0) : 0;
        var h = Math.ceil(b.getBoundingClientRect().height + m);
        // 兜底：body 若被 CSS 撑成 0（比如子元素全 float/absolute），退回旧口径，别把卡片压没
        if (h <= 0) h = Math.ceil(b.scrollHeight || 0);
        if (h > 0 && h !== last && window.__ggufHost && window.__ggufHost.reportHeight) {
          last = h; window.__ggufHost.reportHeight(h);
        }
      } catch (e) { /* ignore */ }
    }
    try {
      if (window.ResizeObserver) {
        var ro = new ResizeObserver(report);
        var watch = function () { if (document.body) ro.observe(document.body); };
        if (document.body) watch(); else document.addEventListener('DOMContentLoaded', watch);
      }
    } catch (e) {}
    document.addEventListener('DOMContentLoaded', report);
    window.addEventListener('load', report);
    setTimeout(report, 80); setTimeout(report, 300); setTimeout(report, 800);
  })();

  if (granted['llm']) {
    // 记忆模型（第二个 llama-server 实例，重型记忆插件的双模型后台检索）。
    gguf.memllm = {
      ensure: function (modelPath) { return callHost('memllm.ensure', { modelPath: modelPath }); },
      chat: function (opts) { return callHost('memllm.chat', opts || {}); },
      status: function () { return callHost('memllm.status', {}); },
      unload: function () { return callHost('memllm.unload', {}); },
    };
    gguf.llm = {
      chat: function (opts) { return callHost('llm.chat', opts || {}); },
      // 前缀扩展式单选判断（params 钩子的 LLM 判断路径）：messages 必须是钩子入参里
      // 网关组装完的最终 messages 原样透传（KV 共用铁律，宿主只在末尾追加判断指令）。
      // 返回 { label: string|null, timings }。
      classify: function (opts) { return callHost('llm.classify', opts || {}); },
      chatStream: function (opts, onDelta) {
        var streamId = 's' + (++reqSeq);
        streamHandlers[streamId] = onDelta;
        var args = {};
        for (var k in (opts || {})) if (Object.prototype.hasOwnProperty.call(opts, k)) args[k] = opts[k];
        args.streamId = streamId;
        return callHost('llm.chatStream', args).then(
          function (r) { delete streamHandlers[streamId]; return r; },
          function (e) { delete streamHandlers[streamId]; throw e; }
        );
      },
    };
  }
  if (granted['net:lan'] || granted['net:internet']) {
    // 宿主代取网络音频：插件页面直接 fetch 自建服务器会撞 CORS（页面源 https://plugin.gguf），
    // 走宿主原生 HTTP 则同源无碍。opts: { url, json?（有则 POST）, method?, timeoutMs? }
    // → { status, url（本插件域名下的音频地址，fetch 后交给 Web Audio）, bytes }
    gguf.net = {
      fetchAudio: function (opts) { return callHost('net.fetchAudio', opts || {}); },
      // 宿主代取 JSON（§6.16）：{ url, json?, method?, headers?, timeoutMs? } → { status, body }
      // body 是**没被解析过的原文**——宿主不理解外部 API 的格式，解析由插件自己做。
      fetchJson: function (opts) { return callHost('net.fetchJson', opts || {}); },
    };
  }
  if (granted['files:save']) {
    // 把插件生成的二进制存进系统「下载」（§6.15）。opts: { name, base64, mime } → { ok, name, uri }
    // 插件在 WebView 里算得出 .mid/.wav 的字节，却没有别的通道交给用户，只能经宿主落盘。
    gguf.files = {
      saveDownload: function (opts) { return callHost('files.saveDownload', opts || {}); },
    };
  }

  if (granted['chat:card']) {
    // 消息卡片（§6.12）：往当前会话插入一条由本插件 card 页渲染的消息。
    // opts: { file?: 'card.html', data?: {...} }；data.text 是卡片的「内部文本」
    // （进模型上下文、可复制、插件禁用时降级显示为普通文字），务必填。
    gguf.card = {
      post: function (opts) { return callHost('card.post', opts || {}); },
    };
  }
  if (granted['chat:read']) {
    gguf.chat = {
      current: function () { return callHost('chat.current', {}); },
      messages: function (opts) { return callHost('chat.messages', opts || {}); },
    };
  }
  if (granted['sessions:read']) {
    gguf.sessions = {
      list: function () { return callHost('sessions.list', {}); },
      load: function (id) { return callHost('sessions.load', { id: id }); },
    };
  }

  window.gguf = gguf;
})();
