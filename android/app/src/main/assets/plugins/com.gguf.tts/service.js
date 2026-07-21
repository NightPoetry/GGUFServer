/*
 * 朗读插件 service.js —— 消息角标（messageAction）+ 语音消息卡片（tool → card）的参考实现。
 *
 * 两条路径：
 *   1. 用户点消息下的「朗读」角标 → 清洗 Markdown → gguf.tts 直接朗读（再点同一条 = 停止）。
 *   2. 模型输出 <tool name="tts.say">…</tool> → 本钩子返回 { card }，宿主在对话里
 *      插入一条由 card.html 自绘的语音消息（自动播放与否由插件设置决定，见 ui.html）。
 *
 * 发声必须经宿主桥 gguf.tts（Android TextToSpeech）——WebView 里没有 speechSynthesis。
 */
(function () {
  'use strict';

  var lastMsgId = null; // 最近一次角标朗读的消息 id，用于「再点同一条 = 停止」

  /** 把 Markdown 记号清洗成适合朗读的纯文本：代码块读不出来，用占位句带过。 */
  function toSpeakable(md) {
    return String(md || '')
      .replace(/```[\s\S]*?(```|$)/g, '。此处有一段代码。')
      .replace(/`([^`]*)`/g, '$1')
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')          // 图片整体略去
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')       // 链接只读文字
      .replace(/^\s{0,3}#{1,6}\s+/gm, '')            // 标题井号
      .replace(/(\*\*|__|~~)/g, '')                  // 粗体/删除线记号
      .replace(/^\s*[-*+]\s+/gm, '')                 // 列表符号
      .replace(/^\s*>\s?/gm, '')                     // 引用竖线
      .replace(/^\s*\|.*\|\s*$/gm, function (row) {  // 表格行：竖线换成顿号朗读
        return row.replace(/^\s*\||\|\s*$/g, '').replace(/\s*\|\s*/g, '、');
      })
      .replace(/^\s*[-:| ]+\s*$/gm, '')              // 表格分隔行整行删除
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }

  gguf.hooks.messageAction(async function (a) {
    var msg = a && a.message ? a.message : {};
    var text = toSpeakable(msg.content);
    if (!text) return {};

    var st = await gguf.tts.status().catch(function () { return null; });
    if (st && st.speaking && lastMsgId === msg.id) {
      lastMsgId = null;
      await gguf.tts.stop();
      return {};
    }
    lastMsgId = msg.id;
    await gguf.tts.speak(text);
    return {};
  });

  // 模型主动发语音：工具标签体 = 要说的话。返回 card → 宿主插入语音消息卡片（card.html 渲染）。
  // data.text 同时是卡片的「内部文本」：进上下文、可复制、长按展开、插件禁用时降级为普通文字。
  gguf.hooks.tool(async function (name, input) {
    if (name !== 'tts.say') return {};
    var text = String(input || '').trim();
    if (!text) return {};
    return { card: { file: 'card.html', data: { text: text, postedAt: Date.now() } } };
  });
})();
