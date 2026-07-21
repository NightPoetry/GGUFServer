/*
 * 提示词插件 service.js（系统提示词中间件，《工具插件规范》§6.4）。
 *
 * 用途：局域网上的外部客户端（OpenAI SDK / curl）经常不带 system 消息；服务器上跑的 1B 级
 * 小模型没有系统提示词兜底时容易拒答/幻觉（真机已证实：同一段超长对话回忆测试，不带 system
 * 直接拒答，补一句普通 system 就能正常作答）。本插件把这句兜底提示词从「客户端自己负责」
 * 挪到「服务器网关侧统一兜底」，尤其覆盖外部客户端。
 *
 * 配置存 gguf.storage 键 'config'：{ text, mode: "missing"|"always", applyExternal, applyLocal }。
 * UI 保存后 gguf.events.broadcast('config-updated')，这里监听重新读取；本插件不持有会话状态，
 * 纯粹是「读配置 -> 判定 -> 出 head」的无状态中间件。
 *
 * 字节稳定性论证（§6.4 对 head 槽位的硬性要求）：
 * - head 的内容只由「配置」决定，不掺入任何逐轮变化的东西（不带时间戳、不带检索结果、
 *   不带会话状态）。只要用户不去插件面板改配置，同一 turn.source/mode/是否已有 system
 *   的组合下，本插件连续多轮吐出的 head 字符串逐字节相同 —— 满足 KV 前缀缓存对 head 的要求。
 * - 唯一会让 head 变化的事件是「用户主动改配置」（改文本/改模式/改开关），这本就是一次性、
 *   低频的操作，代价是一次缓存重建（该会话下一轮起点整窗重预填充），之后立即恢复稳定、
 *   重新进入快路径。UI 里对此有专门提示，避免用户误以为是 bug。
 */
(function () {
  'use strict';

  var DEFAULT_TEXT = '你是一个可靠的中文助手。认真利用对话历史和给你的背景资料回答问题；用户自己说过的内容（如名字、喜好、日程）直接引用作答，不要以隐私或无法获取信息为由拒绝。';

  function defaultConfig() {
    return { text: DEFAULT_TEXT, mode: 'missing', applyExternal: true, applyLocal: false };
  }

  /** 把 storage 里读到的任意值规整成合法配置，缺的字段补默认值（向前兼容旧版本存的配置）。 */
  function normalizeConfig(v) {
    var d = defaultConfig();
    if (!v || typeof v !== 'object') return d;
    return {
      text: typeof v.text === 'string' ? v.text : d.text,
      mode: v.mode === 'always' ? 'always' : 'missing',
      applyExternal: typeof v.applyExternal === 'boolean' ? v.applyExternal : d.applyExternal,
      applyLocal: typeof v.applyLocal === 'boolean' ? v.applyLocal : d.applyLocal,
    };
  }

  // 内存缓存：context 钩子直接读这个变量，不在钩子里等 storage 往返（钩子总预算 250ms 是
  // 多插件共享的，不该为一次本地 KV 读付网关同步等待）。
  var config = defaultConfig();

  function loadConfig() {
    return gguf.storage.get('config').then(function (v) {
      if (v && typeof v === 'object') {
        config = normalizeConfig(v);
      } else {
        // 第一次启动：storage 里还没有配置，写入默认值（下次直接读得到）。
        config = defaultConfig();
        return gguf.storage.set('config', config);
      }
    }).catch(function () {
      // storage 暂不可用：保持当前内存值（首次即默认配置），不让钩子因此出错。
    });
  }

  loadConfig(); // 启动时读一次
  gguf.events.on('config-updated', loadConfig); // UI 保存后广播，重新读取

  gguf.hooks.context(function (turn) {
    if (!turn) return {};

    if (turn.source === 'external' && !config.applyExternal) return {};
    if (turn.source === 'local' && !config.applyLocal) return {};

    var text = String(config.text || '').trim();
    if (!text) return {};

    if (config.mode === 'missing') {
      var msgs = (turn && turn.messages) || [];
      for (var i = 0; i < msgs.length; i++) {
        var m = msgs[i];
        if (m && m.role === 'system' && String(m.content || '').trim()) {
          return {}; // 客户端自己带了非空 system，不打扰
        }
      }
    }

    return { head: text };
  });
})();
