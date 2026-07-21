/*
 * 定制音色插件 service.js —— 局域网 TTS 推理服务的发声提供者（§6.13）。
 *
 * 管道：接管发声 → 把文本 POST 给局域网上的推理服务器 → 拿回 WAV → Web Audio 播放。
 * 两种接口模式（2026 年自建服务器的两大事实标准）：
 *   - openai：POST {model, input, voice, response_format:"wav", speed} → <url>/v1/audio/speech
 *     覆盖 vLLM-Omni(VoxCPM2)、Kokoro-FastAPI、IndexTTS-2 的 OpenAI 封装、openedai-speech 等
 *   - gptsovits：POST {text, text_lang, ref_audio_path, prompt_text, prompt_lang, media_type:"wav"}
 *     → <url>/tts（api_v2）。角色音色克隆生态（悠米/小火龙等现成模型）走这条
 *
 * 与提供者预算的配合：网络推理可能远超钩子 5s 预算——speak 收到后【立即返回】，
 * 取音与播放全部异步进行；失败调穿 gguf.tts.speak 回退系统原声（宁可原声不可无声）。
 */
(function () {
  'use strict';

  var ctx = null;
  var src = null;
  var speaking = false;
  var abortCtl = null;
  var reqSeq = 0;

  function stopPlayback() {
    if (abortCtl) { try { abortCtl.abort(); } catch (e) {} abortCtl = null; }
    if (src) { try { src.stop(); } catch (e) {} src = null; }
    speaking = false;
  }

  async function getCfg() {
    var cfg = await gguf.storage.get('cfg').catch(function () { return null; });
    return cfg && typeof cfg === 'object' ? cfg : {};
  }

  function buildRequest(cfg, text) {
    var base = String(cfg.url || '').replace(/\/+$/, '');
    if (cfg.mode === 'gptsovits') {
      return {
        url: base + '/tts',
        body: {
          text: text,
          text_lang: cfg.textLang || 'zh',
          ref_audio_path: cfg.refAudio || '',
          prompt_text: cfg.promptText || '',
          prompt_lang: cfg.promptLang || 'zh',
          media_type: 'wav',
          streaming_mode: false,
        },
      };
    }
    // 默认 openai 模式
    return {
      url: base + '/v1/audio/speech',
      body: {
        model: cfg.model || 'tts-1',
        input: text,
        voice: cfg.voice || 'default',
        response_format: 'wav',
        speed: typeof cfg.speed === 'number' ? cfg.speed : 1.0,
      },
    };
  }

  async function fetchAndPlay(text) {
    var cfg = await getCfg();
    var isLocal = cfg.mode === 'local';
    if (!isLocal && !cfg.url) { await gguf.tts.speak(text); return; }  // 未配置：直接原声

    var my = ++reqSeq;             // 后点顶掉先点：旧请求的结果作废
    stopPlayback();
    speaking = true;               // 取音期间也算 speaking（角标"再点=停止"语义成立）
    var t0 = Date.now();
    try {
      var r;
      if (isLocal) {
        // 内置神经推理（sherpa-onnx VITS，完全离线）：sid 选音色
        r = await gguf.tts.synthesizeLocal({ text: text, sid: Number(cfg.sid) || 0 });
      } else {
        var req = buildRequest(cfg, text);
        // 经宿主取音（gguf.net.fetchAudio）：页面直接 fetch 自建服务器会撞 CORS，
        // 宿主原生 HTTP 取回后给一个本插件域名下的同源 URL
        r = await gguf.net.fetchAudio({ url: req.url, json: req.body, timeoutMs: 60000 });
      }
      if (my !== reqSeq) return;   // 已被更新的请求顶掉
      var buf = await fetch(r.url).then(function (x) { return x.arrayBuffer(); });
      if (my !== reqSeq) return;

      if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
      if (ctx.state === 'suspended') { try { await ctx.resume(); } catch (e) {} }
      var audio = await ctx.decodeAudioData(buf);
      if (my !== reqSeq) return;

      src = ctx.createBufferSource();
      src.buffer = audio;
      src.connect(ctx.destination);
      src.onended = function () { speaking = false; src = null; };
      src.start();
      gguf.storage.set('last', {
        mode: isLocal ? 'local' : (cfg.mode || 'openai'), chars: text.length,
        fetchMs: Date.now() - t0, audioSec: Math.round(audio.duration * 10) / 10, ts: Date.now(),
      }).catch(function () {});
    } catch (e) {
      speaking = false;
      if (e && e.name === 'AbortError') return;  // 用户主动停止，不回退
      gguf.storage.set('lastError', { msg: String(e && e.message || e), ts: Date.now() }).catch(function () {});
      await gguf.tts.speak(text);  // 服务器不可达/解码失败：调穿系统原声保底
    }
  }

  gguf.hooks.tts(function (p) {
    if (p.action === 'speak') {
      var text = String(p.text || '').trim();
      // 立即返回：网络推理可能远超钩子预算，取音+播放全异步
      if (text) fetchAndPlay(text);
      return {};
    }
    if (p.action === 'stop') {
      stopPlayback();
      return gguf.tts.stop().then(function () { return {}; });
    }
    if (p.action === 'status') {
      return { ready: true, speaking: speaking };
    }
    return {};
  });

  // 设置卡片的「试听」经插件内广播过来（设置卡片与 service 同权限、互通）
  gguf.events.on('voicelab-test', function () {
    fetchAndPlay('你好，这是定制音色的试听。如果你听到的是系统原声，说明服务器没有连上。');
  });
})();
