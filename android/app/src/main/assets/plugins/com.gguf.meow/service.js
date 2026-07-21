/*
 * 猫嗓插件 service.js —— 能力提供者（§6.13 补丁式依赖）的参考实现。
 *
 * manifest 声明 provides:["tts"] 后，本机所有发声调用（朗读插件的角标、语音消息卡片……）
 * 都会改道进下面的 gguf.hooks.tts 钩子。补丁语义：
 *   - 别人调 gguf.tts.speak → 进到这里（入参带 action/from/text）；
 *   - 这里再调 gguf.tts.speak → 「调穿」到宿主默认实现（系统 TextToSpeech），
 *     并可带 pitch/rate 变调——加工完交还原引擎，天然无回环。
 * 插件被禁用或熔断时，发声自动恢复系统默认——补丁揭掉，原流程完好。
 */
(function () {
  'use strict';

  /** 猫娘化：句尾补喵，语气词替换。刻意保守，不破坏原文信息。 */
  function meowify(text) {
    return String(text || '')
      .replace(/([。！!?？])/g, '喵$1')
      .replace(/([^喵])$/, '$1喵～');
  }

  gguf.hooks.tts(async function (p) {
    if (p.action === 'speak') {
      var t = meowify(p.text);
      // 调试可观测：最近一次接管的原文/加工结果落在私有 KV，真机可查
      gguf.storage.set('last', { from: p.from, raw: String(p.text || '').slice(0, 80), meow: t.slice(0, 80), ts: Date.now() }).catch(function () {});
      await gguf.tts.speak(t, { pitch: 1.45, rate: 1.05 }); // 调穿：系统引擎 + 猫式变调
      return {};
    }
    if (p.action === 'stop') { await gguf.tts.stop(); return {}; }
    if (p.action === 'status') { return await gguf.tts.status(); }
    return {};
  });
})();
