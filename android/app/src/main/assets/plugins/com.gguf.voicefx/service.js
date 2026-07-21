/*
 * 简单变声插件 service.js —— 音频级发声管道的验证实现（RVC 类插件的原型）。
 *
 * 与「猫嗓」（文本级补丁）不同，这里走音频级三步：
 *   1. 取料：gguf.tts.synthesize(text) → 系统引擎合成 WAV（不出声），拿到本插件域名下的 URL；
 *   2. 处理：fetch → Web Audio 解码 → 二次处理（v1 用 playbackRate 变调变速，任何引擎都听得出；
 *      将来 RVC 插件把这一步换成模型推理——悠米/小火龙音色即在此落地）；
 *   3. 播放：AudioContext 直接在本 service WebView 里出声（宿主已放开无手势媒体播放）。
 *
 * 引擎的 setPitch 在部分 OEM 引擎（如小米）上被静默忽略——音频级处理是变声的唯一可靠路径，
 * 这正是本插件存在的意义。合成失败时调穿 gguf.tts.speak 保底出声（宁可原声不可无声）。
 */
(function () {
  'use strict';

  var ctx = null;      // AudioContext 懒创建
  var src = null;      // 当前播放源
  var speaking = false;

  function stopPlayback() {
    if (src) { try { src.stop(); } catch (e) {} src = null; }
    speaking = false;
  }

  async function playShifted(url, rate) {
    if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') { try { await ctx.resume(); } catch (e) {} }
    var buf = await fetch(url).then(function (r) { return r.arrayBuffer(); });
    var audio = await ctx.decodeAudioData(buf);
    stopPlayback();
    src = ctx.createBufferSource();
    src.buffer = audio;
    src.playbackRate.value = rate;   // >1 = 音调升高+变快（花栗鼠感），<1 = 低沉
    src.connect(ctx.destination);
    src.onended = function () { speaking = false; src = null; };
    speaking = true;
    src.start();
  }

  gguf.hooks.tts(async function (p) {
    if (p.action === 'speak') {
      var text = String(p.text || '').trim();
      if (!text) return {};
      var rate = await gguf.storage.get('rate').then(function (v) { return typeof v === 'number' ? v : 1.35; }).catch(function () { return 1.35; });
      try {
        var r = await gguf.tts.synthesize(text);
        await playShifted(r.url, rate);
        gguf.storage.set('last', { from: p.from, chars: text.length, rate: rate, ts: Date.now() }).catch(function () {});
      } catch (e) {
        // 取料/解码失败：调穿原声保底，绝不无声
        await gguf.tts.speak(text);
      }
      return {};
    }
    if (p.action === 'stop') {
      stopPlayback();
      await gguf.tts.stop();   // 万一保底路径正在响
      return {};
    }
    if (p.action === 'status') {
      return { ready: true, speaking: speaking };
    }
    return {};
  });
})();
