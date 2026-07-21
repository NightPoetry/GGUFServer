/*
 * 参数导演 service.js（params 钩子中间件，《工具插件规范》§6.10）。
 *
 * 用途：聊天是唯一「任务类型事先未知」的请求路径——同一个对话框里，写诗要高温、算账/写代码
 * 要近贪心、闲聊要官方推荐档+DRY 防跨轮复读。本插件在网关转发前判断本轮任务类型，返回对应
 * 采样参数档；网关按白名单合并（external 客户端显式设过的参数不覆盖，见网关 §6.10 优先级）。
 *
 * 三档依据（《推理参数与外部知识架构研究》实测 + OpenBMB 官方推荐）：
 * - precise（代码/计算/翻译/抽取）：temp 0 近贪心（封闭式任务贪心普遍更优），DRY 0.8 当保险带
 *   （实测对取答案零副作用）；绝不用 repeat_penalty（会提前截断代码、扭曲输出）。
 * - creative（写作）：temp 0.9 / top_p 0.95（官方 think 档同值）+ min_p 0.05 兜住长尾 + DRY。
 * - chat（默认）：temp 0.7 / top_p 0.95（官方 no-think 推荐）+ DRY 0.8——DRY 惩罚窗口默认
 *   覆盖全上下文，是唯一治得住「模型逐字复读自己前几轮回答」的采样器（实测 0.22→0.03）。
 *
 * 判断分层：JS 规则打头阵（零延迟、高置信关键词）；规则拿不准且开了 useLlm 时走
 * gguf.llm.classify——前缀扩展判断，与本轮回答共用上文 KV（铁律：messages 原样透传，
 * 判断只在末尾追加指令；判断顺手预填了本轮用户消息，回答请求前缀全命中，缓存零残留）。
 * fail-open：判断失败/超时返回 chat 档；本插件永远不改 messages，不存在字节稳定性问题。
 */
(function () {
  'use strict';

  function defaultTiers() {
    return {
      precise: { temperature: 0, min_p: 0.05, dry_multiplier: 0.8, dry_allowed_length: 2, repeat_penalty: 1.0 },
      creative: { temperature: 0.9, top_p: 0.95, min_p: 0.05, dry_multiplier: 0.8, dry_allowed_length: 2, repeat_penalty: 1.0 },
      chat: { temperature: 0.7, top_p: 0.95, min_p: 0.05, dry_multiplier: 0.8, dry_allowed_length: 2, repeat_penalty: 1.0 },
    };
  }

  function defaultConfig() {
    return {
      applyLocal: true,
      applyExternal: false,
      useLlm: false,           // LLM 判断路径默认关：规则已覆盖高置信场景，判断费时由用户自选
      chatTemp: 0.7,
      creativeTemp: 0.9,
      dry: 0.8,
    };
  }

  function normalizeConfig(v) {
    var d = defaultConfig();
    if (!v || typeof v !== 'object') return d;
    return {
      applyLocal: typeof v.applyLocal === 'boolean' ? v.applyLocal : d.applyLocal,
      applyExternal: typeof v.applyExternal === 'boolean' ? v.applyExternal : d.applyExternal,
      useLlm: typeof v.useLlm === 'boolean' ? v.useLlm : d.useLlm,
      chatTemp: typeof v.chatTemp === 'number' ? v.chatTemp : d.chatTemp,
      creativeTemp: typeof v.creativeTemp === 'number' ? v.creativeTemp : d.creativeTemp,
      dry: typeof v.dry === 'number' ? v.dry : d.dry,
    };
  }

  var config = defaultConfig();

  function tiers() {
    var t = defaultTiers();
    t.chat.temperature = config.chatTemp;
    t.creative.temperature = config.creativeTemp;
    t.precise.dry_multiplier = config.dry;
    t.creative.dry_multiplier = config.dry;
    t.chat.dry_multiplier = config.dry;
    return t;
  }

  function loadConfig() {
    return gguf.storage.get('config').then(function (v) {
      if (v && typeof v === 'object') { config = normalizeConfig(v); }
      else { config = defaultConfig(); return gguf.storage.set('config', config); }
    }).catch(function () { /* storage 暂不可用：用内存值 */ });
  }
  loadConfig();
  gguf.events.on('config-updated', loadConfig);

  // ---- JS 规则分类：高置信关键词，命中即定档（宁可漏给 LLM/默认档，不误判） ----
  var RE_PRECISE = [
    /代码|函数|脚本|报错|编程|写个程序|重构|单元测试|正则/,
    /\b(json|sql|bug|api|html|css|python|kotlin|shell)\b/i,
    /算一下|计算|等于多少|共多少|总共|平均|百分之|汇率|利息/,
    /翻译|译成|改写|润色|总结一下|概括一下|摘要|提取|列成表/,
  ];
  var RE_CREATIVE = [
    /写[一首个篇段].{0,6}(诗|词|歌|故事|小说|散文|作文|文案|台词|段子)/,
    /绝句|律诗|对联|藏头诗|打油诗|作[一]?首|赋诗|歌词|十四行/,
    /(编|讲)[一个]{0,2}.{0,4}故事|续写|扩写成|起[个几]?名|取[个几]?名|想[几个]{1,2}.{0,4}(标题|名字|口号)/,
    /写.{0,8}(祝福|贺词|情书|演讲稿|开场白)/,
  ];

  function ruleClassify(text) {
    if (!text) return null;
    var i;
    for (i = 0; i < RE_CREATIVE.length; i++) if (RE_CREATIVE[i].test(text)) return 'creative';
    for (i = 0; i < RE_PRECISE.length; i++) if (RE_PRECISE[i].test(text)) return 'precise';
    return null; // 拿不准
  }

  var LLM_LABELS = ['写作创作', '代码或计算', '普通聊天'];
  var LLM_MAP = { '写作创作': 'creative', '代码或计算': 'precise', '普通聊天': 'chat' };

  function recordDecision(tier, via, ms) {
    gguf.storage.get('history').then(function (h) {
      var list = Array.isArray(h) ? h : [];
      list.unshift({ ts: Date.now(), tier: tier, via: via, ms: ms | 0 });
      if (list.length > 20) list.length = 20;
      return gguf.storage.set('history', list);
    }).then(function () {
      gguf.events.broadcast('decision', null);
    }).catch(function () {});
  }

  gguf.hooks.params(function (payload) {
    if (!payload) return {};
    if (payload.source === 'external' && !config.applyExternal) return {};
    if (payload.source === 'local' && !config.applyLocal) return {};

    var text = String(payload.text || '');
    var t0 = Date.now();
    var byRule = ruleClassify(text);
    if (byRule) {
      recordDecision(byRule, '规则', Date.now() - t0);
      return { params: tiers()[byRule] };
    }
    if (!config.useLlm || !text || !gguf.llm || !gguf.llm.classify) {
      recordDecision('chat', '默认', Date.now() - t0);
      return { params: tiers().chat };
    }
    // LLM 判断：messages 原样透传（KV 共用铁律——严格后缀扩展，见 SDK 注释）
    return gguf.llm.classify({
      messages: payload.messages || [],
      question: '用户最后这条消息要求的是哪类任务？',
      labels: LLM_LABELS,
    }).then(function (r) {
      var tier = (r && r.label && LLM_MAP[r.label]) || 'chat';
      recordDecision(tier, 'LLM判断', Date.now() - t0);
      return { params: tiers()[tier] };
    }).catch(function () {
      recordDecision('chat', 'LLM失败兜底', Date.now() - t0);
      return { params: tiers().chat };
    });
  });
})();
