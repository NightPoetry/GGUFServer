/*
 * 乐句（com.gguf.midi）—— 让模型作曲，端上合成播放。
 *
 * 设计取向沿用本项目的老路子：**模型只出创意里最小的那一点，确定性的活全交给 JS**。
 * 1B 写 8 个音是可靠的，写 64 小节是不可能的——但音乐本来就是**重复与变形**（AABA、副歌升五度、
 * 段落循环）。所以 1.1.0 起模型写的是一份**编排脚本**：
 *
 *   tempo:100 key:Am
 *   inst: melody=piano bass=bass
 *   mix: melody=0.9 bass=0.55
 *   pat A: A4 C5 E5 - | G4 B4 D5 -
 *   pat B: F4 A4 C5 - | E4 G4 B4 -
 *   bass A: A2 - E2 -
 *   play: A A B A+12 A*2
 *
 *   模型负责 → 两三个 4 小节片段 + 一行播放顺序（可移调 +n/-n、可重复 *k）
 *   JS 负责  → 展开成绝对时间轴、没写低音就按调式自动配和声、三轨音域折叠、
 *              多乐器音色、逐轨混音、钢琴卷帘、Web Audio 合成（卡片里滚动预排，长曲子不炸内存）
 *
 * 老格式仍然认（单行音符 = 片段 A 奏一次），1.0.x 存下的乐句也照样能播。
 *
 * 为什么叫 MIDI：音符模型就是 MIDI 的语义（音高 0–127、力度、以 tick 计时长），卡片里的合成器
 * 只是它的一个播放后端。**诚实标注**：本版仍不导出 .mid 文件（WebView 里存二进制没有好通道），
 * 但数据模型是 MIDI 的，将来加导出不用改契约。
 *
 * 不需要新桥接口、不需要宿主改动：发声用卡片页里的 Web Audio。
 */
(function () {
  'use strict';


  var PITCH_CLASS = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
  var MAJOR = [0, 2, 4, 5, 7, 9, 11];
  var MINOR = [0, 2, 3, 5, 7, 8, 10];
  var TPQ = 480;                 // 每四分音符的 tick 数（MIDI 惯例）
  var STEP = TPQ / 2;            // 一个记谱位 = 八分音符
  var BAR = TPQ * 2;             // 一小节 = 2 拍 = 4 个记谱位
  var MAX_TICKS = TPQ * 2 * 4 * 64;   // 上限 64 小节，防跑飞
  var TRACKS = ['melody', 'bass', 'pad'];
  var KNOWN_INST = ['piano', 'bass', 'strings', 'bell', 'pluck'];

  function noteNameToMidi(name) {
    var m = /^([A-Ga-g])([#b]?)(-?\d)?$/.exec(name);
    if (!m) return null;
    var pc = PITCH_CLASS[m[1].toUpperCase()];
    if (pc == null) return null;
    if (m[2] === '#') pc += 1; else if (m[2] === 'b') pc -= 1;
    var oct = m[3] == null ? 4 : parseInt(m[3], 10);
    var midi = (oct + 1) * 12 + pc;
    return midi >= 0 && midi <= 127 ? midi : null;
  }

  /**
   * 调号是不是小调。**`major` 里有个 m**，所以绝不能用 /m|min|小调/ 这种松判定——真机上就栽在
   * 这儿：模型写了「C major」，判定说「已经是小调」于是情绪夹取整个跳过，结果「悲伤」配了 C 大调。
   * 判法：削掉音名后看剩下的部分，先排除 maj/大调，再认 m / min / minor / 小调。
   */
  function keyIsMinor(k) {
    var rest = String(k || '').replace(/^[A-Ga-g][#b]?/i, '').replace(/[\s\-_]/g, '').toLowerCase();
    if (!rest) return false;
    if (rest.indexOf('小调') >= 0) return true;
    if (rest.indexOf('maj') === 0 || rest.indexOf('大调') >= 0) return false;
    return rest === 'm' || rest.indexOf('min') === 0;
  }

  function parseKey(s) {
    var t = String(s || '').trim();
    var m = /^([A-Ga-g])([#b]?)/.exec(t);
    if (!m) return { root: 0, minor: false };
    var pc = PITCH_CLASS[m[1].toUpperCase()];
    if (m[2] === '#') pc += 1; else if (m[2] === 'b') pc -= 1;
    return { root: ((pc % 12) + 12) % 12, minor: keyIsMinor(t) };
  }

  function degreeToMidi(deg, accidental, octShift, key) {
    var scale = key.minor ? MINOR : MAJOR;
    var idx = deg - 1;
    if (idx < 0 || idx > 6) return null;
    var semi = scale[idx] + (accidental === '#' ? 1 : accidental === 'b' ? -1 : 0);
    var midi = 60 + key.root + semi + octShift * 12;
    return midi >= 0 && midi <= 127 ? midi : null;
  }

  /** 一行音符 → 音符数组（相对 tick 从 0 起）。认音名、简谱、`-` 延长、`0` 休止、`|` 小节线。 */
  function parseNotes(line, key, vel) {
    var steps = [], unknown = 0;
    var tokens = String(line || '').split(/[\s,;|/]+/);
    for (var i = 0; i < tokens.length; i++) {
      var t = tokens[i].trim();
      if (!t) continue;
      if (/^[-.~_]+$/.test(t)) { for (var k = 0; k < t.length; k++) steps.push({ hold: true }); continue; }
      if (/^(?:0|r|rest|休)$/i.test(t)) { steps.push({ rest: true }); continue; }
      var midi = noteNameToMidi(t);
      if (midi != null) { steps.push({ midi: midi }); continue; }
      var mD = /^([#b]?)([1-7])((?:['’]|[,，])*)$/.exec(t);
      if (mD) {
        var shift = 0, tail = mD[3] || '';
        for (var j = 0; j < tail.length; j++) shift += (tail[j] === "'" || tail[j] === '’') ? 1 : -1;
        var dm = degreeToMidi(parseInt(mD[2], 10), mD[1], shift, key);
        if (dm != null) { steps.push({ midi: dm }); continue; }
      }
      unknown++;
    }
    var notes = [], t0 = 0, cur = null;
    for (i = 0; i < steps.length; i++) {
      var s = steps[i];
      if (s.hold) { if (cur) cur.dur += STEP; t0 += STEP; continue; }
      if (s.rest) { cur = null; t0 += STEP; continue; }
      cur = { midi: s.midi, start: t0, dur: STEP, vel: vel || 96 };
      notes.push(cur);
      t0 += STEP;
    }
    return { notes: notes, ticks: t0, unknown: unknown };
  }

  /** 自动低音：没写 bass 轨时，按小节从旋律推调内三和弦的根音 + 五音（模型不必懂和声）。 */
  function autoBass(melody, key, spanTicks) {
    var out = [];
    var scale = key.minor ? MINOR : MAJOR;
    for (var start = 0; start < spanTicks; start += BAR) {
      var end = start + BAR;
      var inBar = melody.filter(function (n) { return n.start >= start && n.start < end; });
      if (!inBar.length) continue;
      var pc = ((inBar[0].midi - key.root) % 12 + 12) % 12;
      var deg = 0, best = 99;
      for (var d = 0; d < 7; d++) {
        var diff = Math.min(Math.abs(scale[d] - pc), 12 - Math.abs(scale[d] - pc));
        if (diff < best) { best = diff; deg = d; }
      }
      var rootMidi = 36 + key.root + scale[deg];
      out.push({ midi: rootMidi, start: start, dur: BAR, vel: 58 });
      out.push({ midi: rootMidi + 7, start: start, dur: BAR, vel: 44 });
    }
    return out;
  }

  /** 音域折叠：只动八度、不动音名（调性与旋律轮廓不变），把跑出窗口的音折回来。 */
  function fold(notes, lo, hi) {
    for (var i = 0; i < notes.length; i++) {
      var m = notes[i].midi;
      while (m > hi) m -= 12;
      while (m < lo) m += 12;
      notes[i].midi = m;
    }
  }

  function parseScore(raw) {
    var text = String(raw || '').replace(/\r/g, '');
    var tempo = 92, mood = '', keyStr = 'C';
    var inst = { melody: 'piano', bass: 'bass', pad: 'strings' };
    var mix = { melody: 0.9, bass: 0.55, pad: 0.32 };

    // 表头正则**不许跨行**（用 [ \t] 而不是 \s）：写成 \s* 时「key:A」后面紧跟一行「mood:欢快」，
    // 调号会把下一行的 m 吃进来变成 A 小调——大调秒变小调，情绪整个反了（单测抓到的真 bug）。
    var mT = /tempo[ \t]*[:：][ \t]*(\d{2,3})/i.exec(text);
    if (mT) tempo = Math.min(200, Math.max(40, parseInt(mT[1], 10)));
    // 捕获要把 major/minor/大调/小调 整段吃进来（曾只捕到 "C"，把 C-major 当成 C 大调是碰巧对，
    // 但 Cmajor 会被 (?:m|…) 当成小调——都由 keyIsMinor 统一裁决，这里只负责别截断）。
    var mK = /key[ \t]*[:：][ \t]*([A-Ga-g][#b]?[ \t\-_]*(?:maj(?:or)?|min(?:or)?|m|大调|小调)?)/i.exec(text);
    if (mK) keyStr = mK[1].trim();
    var mM = /mood[ \t]*[:：][ \t]*([A-Za-z一-鿿]{1,8})/i.exec(text);
    if (mM) mood = mM[1];
    var key = parseKey(keyStr);

    // inst: melody=piano bass=bass  ·  也认只写一个乐器名（当旋律音色）
    var mI = /inst(?:rument)?\s*[:：]\s*([^\n]*)/i.exec(text);
    if (mI) {
      var seg = mI[1];
      var any = false;
      seg.replace(/(melody|bass|pad)\s*=\s*([a-z]+)/gi, function (_, tr, v) {
        v = v.toLowerCase();
        if (KNOWN_INST.indexOf(v) >= 0) { inst[tr.toLowerCase()] = v; any = true; }
        return '';
      });
      if (!any) {
        var one = /([a-z]+)/i.exec(seg.trim());
        if (one && KNOWN_INST.indexOf(one[1].toLowerCase()) >= 0) inst.melody = one[1].toLowerCase();
      }
    }
    var mX = /mix\s*[:：]\s*([^\n]*)/i.exec(text);
    if (mX) {
      mX[1].replace(/(melody|bass|pad)\s*=\s*(0?\.\d+|[01](?:\.\d+)?)/gi, function (_, tr, v) {
        var g = parseFloat(v);
        if (g >= 0 && g <= 1) mix[tr.toLowerCase()] = g;
        return '';
      });
    }

    // ---- 片段定义 ----
    var pats = {};                 // name -> { melody:{notes,ticks}, bass:?, pad:? , ticks }
    var order = [];                // 定义顺序（没有 play: 时按它奏）
    var lines = text.split('\n');
    var patRe = /^\s*(pat(?:tern)?|melody|bass|pad)\s+([A-Za-z0-9_一-鿿]{1,12})\s*[:：]\s*(.+)$/i;
    var playLine = null;
    for (var li = 0; li < lines.length; li++) {
      var line = lines[li];
      var mp = patRe.exec(line);
      if (mp) {
        var kind = mp[1].toLowerCase();
        var track = (kind === 'pat' || kind === 'pattern' || kind === 'melody') ? 'melody' : kind;
        var name = mp[2];
        var parsed = parseNotes(mp[3], key, track === 'bass' ? 58 : (track === 'pad' ? 46 : 96));
        if (!parsed.notes.length) continue;
        // **折叠在这里做，不在展开之后**：它要规整的是「模型把片段写得上下乱跑八度」，
        // 而 play 里的 +n/-n 是**编排决定**，必须原样保留（先前放在展开后，A+12 被折回原音区）。
        if (track === 'melody') {
          var c0 = parsed.notes[0].midi;
          fold(parsed.notes, Math.max(48, c0 - 7), Math.min(84, c0 + 16));
        } else if (track === 'bass') { fold(parsed.notes, 33, 55); } else { fold(parsed.notes, 48, 72); }
        if (!pats[name]) { pats[name] = { ticks: 0 }; order.push(name); }
        pats[name][track] = parsed;
        pats[name].ticks = Math.max(pats[name].ticks, parsed.ticks);
        continue;
      }
      var mPlay = /^\s*play\s*[:：]\s*(.+)$/i.exec(line);
      if (mPlay) { playLine = mPlay[1]; continue; }
    }

    // ---- 没有片段：退回 1.0 单行记谱（整段当片段 A） ----
    var legacy = false;
    if (!order.length) {
      var body = text
        .replace(/^[ \t]*(?:play|inst(?:rument)?|mix)[ \t]*[:：][^\n]*$/gim, ' ')
        .replace(/(?:tempo|key|mood)[ \t]*[:：][ \t]*[^\s]*/gi, ' ');
      var one = parseNotes(body, key, 96);
      if (!one.notes.length) return null;
      var cL = one.notes[0].midi;
      fold(one.notes, Math.max(48, cL - 7), Math.min(84, cL + 16));
      pats.A = { melody: one, ticks: one.ticks };
      order = ['A'];
      legacy = true;
    }

    // ---- 播放顺序 ----
    var seq = [];
    if (playLine) {
      var items = playLine.split(/[\s,，]+/);
      for (var ii = 0; ii < items.length; ii++) {
        var it = items[ii].trim();
        if (!it) continue;
        var mi = /^([A-Za-z0-9_一-鿿]{1,12})([+\-]\d{1,2})?(?:[*x×](\d{1,2}))?$/i.exec(it);
        if (!mi || !pats[mi[1]]) continue;                 // 认不出或没定义过的片段名：跳过
        var rep = mi[3] ? Math.min(16, Math.max(1, parseInt(mi[3], 10))) : 1;
        var tr = mi[2] ? Math.max(-24, Math.min(24, parseInt(mi[2], 10))) : 0;
        for (var rr = 0; rr < rep; rr++) seq.push({ name: mi[1], transpose: tr });
      }
    }
    if (!seq.length) {
      for (var oi = 0; oi < order.length; oi++) seq.push({ name: order[oi], transpose: 0 });
    }

    // ---- 展开成绝对时间轴 ----
    var tracks = { melody: [], bass: [], pad: [] };
    var sections = [];
    var at = 0;
    for (var si = 0; si < seq.length; si++) {
      var s = seq[si];
      var pat = pats[s.name];
      var span = Math.max(pat.ticks, STEP);
      if (at + span > MAX_TICKS) break;                    // 长度上限，防跑飞
      var secMelody = [];
      for (var t = 0; t < TRACKS.length; t++) {
        var name = TRACKS[t];
        var src = pat[name];
        if (!src) continue;
        for (var ni = 0; ni < src.notes.length; ni++) {
          var n = src.notes[ni];
          var mv = n.midi + s.transpose;
          if (mv < 0 || mv > 127) continue;
          var copy = { midi: mv, start: at + n.start, dur: n.dur, vel: n.vel };
          tracks[name].push(copy);
          if (name === 'melody') secMelody.push({ midi: mv, start: n.start, dur: n.dur });
        }
      }
      if (!pat.bass && secMelody.length) {                 // 没写低音就自动配（含移调后的音）
        var ab = autoBass(secMelody, key, span);
        for (var bi = 0; bi < ab.length; bi++) {
          tracks.bass.push({ midi: ab[bi].midi, start: at + ab[bi].start, dur: ab[bi].dur, vel: ab[bi].vel });
        }
      }
      sections.push({ name: s.name, transpose: s.transpose, start: at, dur: span });
      at += span;
    }
    if (!tracks.melody.length) return null;

    return {
      tempo: tempo, key: keyStr, minor: key.minor, mood: mood,
      tpq: TPQ, stepTicks: STEP, barTicks: BAR, totalTicks: at,
      tracks: tracks, inst: inst, mix: mix, sections: sections,
      // 兼容 1.0 的卡片与旧存档：旋律/低音仍以平铺形式给出
      melody: tracks.melody, bass: tracks.bass,
      patterns: order, legacy: legacy,
    };
  }

  /** 编排摘要，例如 `A A B A+12 A×2`（给卡片和面板显示用）。 */
  function arrangeSummary(sc) {
    var out = [], last = null, count = 0;
    function flush() {
      if (!last) return;
      out.push(last + (count > 1 ? '×' + count : ''));
    }
    for (var i = 0; i < sc.sections.length; i++) {
      var s = sc.sections[i];
      var label = s.name + (s.transpose ? (s.transpose > 0 ? '+' : '') + s.transpose : '');
      if (label === last) { count++; continue; }
      flush(); last = label; count = 1;
    }
    flush();
    return out.join(' ');
  }

  /** 卡片的等价文本（data.text）：插件被禁用也不丢内容，同时进模型上下文。 */
  function scoreToText(sc) {
    var names = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
    var bars = Math.max(1, Math.round(sc.totalTicks / sc.barTicks));
    var secs = (sc.totalTicks / sc.tpq) * (60 / sc.tempo);
    var head = '🎵 乐句（' + sc.key + (sc.minor ? ' 小调' : ' 大调') + ' · ' + sc.tempo + ' BPM · '
      + bars + ' 小节 · ' + fmtDur(secs) + '）';
    var arr = arrangeSummary(sc);
    var body = sc.patterns.map(function (p) {
      var pat = sc.sections.filter(function (s) { return s.name === p; })[0];
      if (!pat) return '';
      var notes = sc.tracks.melody
        .filter(function (n) { return n.start >= pat.start && n.start < pat.start + pat.dur; })
        .map(function (n) { return names[n.midi % 12] + (Math.floor(n.midi / 12) - 1); })
        .join(' ');
      return notes ? (sc.patterns.length > 1 ? p + '：' : '') + notes : '';
    }).filter(Boolean).join('\n');
    return head + (arr ? '\n编排：' + arr : '') + '\n' + body;
  }

  function fmtDur(sec) {
    var s = Math.max(1, Math.round(sec));
    return Math.floor(s / 60) + ':' + (s % 60 < 10 ? '0' : '') + (s % 60);
  }


  // ============================== curate：本轮要不要把 midi.play 露给模型 ==============================

  // 用户在要求音乐/旋律时才露。教学条款约 120 token，露多了既费预算又给 1B「手痒乱调」的机会；
  // 漏了则用户要曲子却拿不到工具——按 §6.14 的取向（recall 优先），这里判得稍宽一点。
  // `.{0,8}` 而不是 `.{0,4}`：「写首 C 大调的小曲子」「来首 C 大调的小曲子」里隔了 7 个字（实测漏判）。
  // **不再逐条枚举「动词+名词」，改成叉乘**（2026-07-28 第三次栽在同一件事上之后）：
  // 手写枚举总会漏掉最朴素的说法——先漏「调」（帮我写一个悲伤的调），再漏「音乐」
  // （帮我写个欢快的音乐🎵：`写…` 分支的宾语集合里偏偏没有「音乐」，于是模型自己去写了个 HTML 播放器）。
  // 现在是「要一段东西的动词」×「音乐类名词」，中间允许十个字以内的定语。
  var MK_VERB = '(?:写|来|作|谱|编|生成|整|弄|搞|做|创作|给我(?:来|写|整|弄)?)';
  var TUNE = '调(?!用|整|试|查|节|度|侃|剂|研|配|色|味|料|停|派|职|岗|价|息|解|和|皮|教|养|理|动|换|来|走|回)';
  var MK_NOUN = '(?:曲子?|歌(?:曲|儿)?|旋律|乐句|音乐|伴奏|主旋律|前奏|间奏|melody|bgm|' + TUNE + '子?)';
  // 挡掉「写个音乐播放器/音乐软件/…的代码」这类**要程序不是要曲子**的请求（否则整轮被劫持）
  // 「工具/小工具」是真机泛化集抓到的：「帮我做个能播放本地音乐的小工具」被叉乘命中而误接管
  var CODE_CTX = /播放器|软件|网站|网页|代码|程序|脚本|应用|app|html|页面|接口|界面|组件|插件|库|框架|工具/i;
  var musicRe = new RegExp(
    '作曲|即兴|钢琴曲|主题曲|背景音乐|bgm|midi|哼[一段个]|弹[一段个首]'
    + '|' + MK_VERB + '[^。！？\\n]{0,10}' + MK_NOUN
    + '|配[个段]?[^。！？\\n]{0,8}(?:旋律|伴奏|曲)|听.{0,3}(?:曲|旋律)', 'i');
  // 否定闸门：在**谈论**音乐/工具本身，不是在要一段（同 route 那条教训：别把谈论当成指令）
  var talkRe = /怎么用|如何使用|原理|为什么|支持哪些|能不能导出|什么格式|midi 是什么|midi是什么/i;
  // 要的是**现成作品**不是创作——这是一整类而不是零碎词条（真机泛化集抓到：
  // 「给我推荐几首适合工作时听的歌」被「给我…歌」的叉乘命中而误接管）。
  var pickRe = /推荐|介绍[几一]?[首支]|有什么好听|找[几一]?[首支]|放[一]?[首支]|搜[一]?[首支]|哪[几]?[首支]/;

  // ---- 两段式判定的分层（§6.2.1，2026-07-28；分档按真机评测重排）----
  //
  // **先量后改**：第一版分层想当然地把「音乐」「歌」这类歧义大的词整个降级给 LLM 判，
  // `node scripts/bench-route.js` 一量就露馅——「写一段音乐」「创作一首歌」「给我整首歌」
  // 这些**最朴素的作曲请求**被模型判成了「闲聊」，recall 掉到 77%。教训是：
  // **别把正则已经判得准的东西交给判不准的模型**。叉乘正则在离线语料上是 100%/100%，
  // 它判不了的只有「同一句里既像要曲子又像要程序」和「有音乐味但没有『要一段』的结构」。
  //
  // 现在的分档：
  // - `musicRe` 命中且**无**代码语境 → 直接接管（零成本，绝大多数真作曲请求走这条）
  // - `musicRe` 命中但**有**代码语境（「做一个音乐网站」）→ **不接管**。这里不交给 LLM：
  //   实测它把「做一个音乐网站」判成「旋律」，而误接管是整轮被替换，route 的第一纪律是
  //   precision 优先。代价是「给我的播放器写段背景音乐」这类复合句会漏——漏了模型自己还能答。
  // - 只有音乐味、没有「要一段东西」的结构（「我想要一段适合睡前听的音乐」）→ 深判。
  //   这才是 LLM 真正救得回来的那一档。
  // - 都不命中 → 一分钱不花。
  //
  // 宽召回门闸：只管有没有音乐味，判不判得出来交给 LLM。
  // 「调」用与叉乘同一条否定预查（挡掉调整/调用/调试…），否则「攒一段婚礼上用的小调」这种
  // 动词不在表里的说法连深判都进不去（真机泛化集抓到）。
  var softRe = new RegExp('音乐|歌曲?|旋律|乐句|伴奏|和弦|前奏|间奏|melody|midi|bgm|作曲|谱曲|' + TUNE + '子?', 'i');

  // 「使用工具」这类祈使：本身不含音乐词，但在**刚出过乐句的会话**里就是「用你那个作曲工具」。
  // 与续写同一道门（pieceSessions），所以不会在别的语境里劫持。
  var toolHintRe = /用(?:一下)?(?:这个|那个|你的)?工具|使用工具|调用工具|用插件|用那个插件/;

  function wantsMusic(t) { return !talkRe.test(t) && !pickRe.test(t) && !CODE_CTX.test(t) && musicRe.test(t); }

  gguf.hooks.curate(function (turn) {
    var t = String((turn && turn.text) || '');
    var want = wantsMusic(t)
      || ((contRe.test(t) || toolHintRe.test(t)) && isPieceSession(turn && turn.sessionId));
    return { expose: want ? ['midi.play'] : [] };
  });

  // ============================== route + handle：确定性那条路 ==============================
  //
  // **为什么不能只靠工具标签**：真机实测（MiniCPM5-1B），用户说「写一段轻快的旋律」时 curate 正确
  // 露了 midi.play、教学也注入了，模型却把音符写进了 ```midi 代码块而不是 `<tool>` 标签——它懂格式、
  // 不懂调用协议。这与项目里早就记下的结论一致：**1B 自发吐 <tool> 不可靠，受限解码才可靠**。
  // 所以走便签验证过的那条路：**代码路由（正则判意图）→ 模型只填几个受限的槽（json_schema）→
  // 关键动作由 JS 完成**。工具标签那条路保留，给会用协议的模型（桌面端/更大的模型）。
  //
  // 接管的取向按 route 的纪律来（§6.2：precision 第一）：只在**明确**要音乐时接管，且
  // musicRe/talkRe 已被标注语料钉住（零误露/零漏露）。默认 scope=local，对外 API 不受影响。
  // 「哪些会话刚由我们出过乐句」的同步标记。放模块变量而不是 storage：route 是同步快判定，
  // 这个判断必须同步做（见下面为什么不能无条件接管「再来一段」）。
  // **按会话记而不是记一个**：用户在两个聊天窗口之间来回切时，续写不该因为另一个会话出过曲子就失效
  // ——单测抓到的（一个变量被后一个会话覆盖，前一个会话的「再来一段」就接不住了）。
  // service WebView 被回收后标记丢失，那时续写不接管（失败方向是「不劫持」，安全的一侧）。
  var pieceSessions = {};
  function markPieceSession(sid) {
    if (!sid) return;
    pieceSessions[sid] = Date.now();
    var keys = Object.keys(pieceSessions);
    if (keys.length > 24) {                       // 有界：只留最近的 24 个会话
      keys.sort(function (a, b) { return pieceSessions[a] - pieceSessions[b]; });
      for (var i = 0; i < keys.length - 24; i++) delete pieceSessions[keys[i]];
    }
  }
  function isPieceSession(sid) { return !!(sid && pieceSessions[sid]); }

  /**
   * 深判：关键字已经命中，问一次模型「他到底是不是要我写一段曲子」。
   *
   * 走 `llm.classify`（前缀扩展单选判断，与本轮回答共用上文 KV）。turn.messages 原样透传是铁律——
   * 判断只在末尾追加指令，绝不改动前面的消息，否则缓存全废。
   * fail-open 的方向按 route 的纪律选：**判不出来就不接管**（precision 第一，宁可漏；
   * 漏了模型自己还能答，误接管则是整轮被替换）。
   */
  function deepJudge(turn) {
    if (!gguf.llm || !gguf.llm.classify) return Promise.resolve({});
    return gguf.llm.classify({
      messages: (turn && turn.messages) || [],
      // **问「是哪一类」而不是「是不是作曲」**：二选一的是非题上，模型答什么全看选项怎么排；
      // 换成语义彼此分离的四类（第一个字就不同）之后判得准多了——真机 Qwen3.5-2B 六条对五条。
      question: '用户对助手说了一句话。他想要的是哪一类？只回答一个词。',
      labels: ['代码', '旋律', '闲聊', '知识'],
    }).then(function (r) {
      return { takeOver: !!(r && r.label === '旋律') };
    }).catch(function () { return {}; });
  }

  gguf.hooks.route(function (turn) {
    var t = String((turn && turn.text) || '');
    var sid = turn && turn.sessionId;
    // 第二遍（宿主给了独立预算）：这里才允许调 LLM
    if (turn && turn.deep) return deepJudge(turn);

    // 第一遍：只跑正则，150ms 预算内必须返回
    if (talkRe.test(t) || pickRe.test(t)) return {};  // 在谈论音乐 / 要的是现成作品，都不是「写一段」
    if (CODE_CTX.test(t)) return {};                  // 要的是程序不是曲子——见上面为什么这里不问 LLM
    if (musicRe.test(t)) return { takeOver: true };   // 「动词 × 音乐名词」：正则在这一档上很准
    // **续写只在这个会话刚出过乐句时才接管**：「再来一段」在别的语境里是「再写一段文章/故事」，
    // 无条件接管就是劫持整轮（§6.2 route 的第一纪律：precision 优先，宁可漏）。
    if ((contRe.test(t) || toolHintRe.test(t)) && isPieceSession(sid)) return { takeOver: true };
    // 有音乐味、但没有「要一段东西」的句法结构 —— 这一档正则判不了，交给 LLM
    if (softRe.test(t)) return { deep: true };
    return {};
  });

  gguf.hooks.handle(function (turn, ctl) {
    var ask = String((turn && turn.text) || '');
    var sessionId = turn && turn.sessionId;
    var mood = detectMood(ask);
    var wantsCont = contRe.test(ask) || toolHintRe.test(ask);

    var memPrev = wantsCont && sessionId ? lastPiece[sessionId] : null;
    return (memPrev ? Promise.resolve(memPrev)
      : (wantsCont && sessionId ? gguf.storage.get('last:' + sessionId).catch(function () { return null; })
                                : Promise.resolve(null))).then(function (prev) {
      if (prev && (!prev.pats || !prev.pats.length)) prev = null;
      ctl.status(prev ? '接着上一段写…' : '正在编曲…');
      return gguf.llm.chat({
        messages: [
          { role: 'system', content: sysPrompt(prev, mood) },
          { role: 'user', content: ask },
        ],
        temperature: 0.95,        // 续写要的是「变化」，温度给高些；结构与调性由 JS 把住
        max_tokens: 300,
        // **字符串必须限长**：schema 只约束形状不约束内容，实测模型会在某个字符串里跑飞
        // （`♯\b\bd♯\b\bd…` 一路刷到 max_tokens），JSON 被截断后整份解析失败、这一轮白给。
        // maxLength 让语法层面就停得住。
        json_schema: {
          type: 'object',
          properties: {
            tempo: { type: 'integer' },
            key: { type: 'string', maxLength: 6 },
            patA: { type: 'string', maxLength: 96 },
            patB: { type: 'string', maxLength: 96 },
            play: { type: 'string', maxLength: 48 },
            inst: { type: 'string', enum: KNOWN_INST },
          },
          required: prev ? ['patA'] : ['tempo', 'key', 'patA', 'play'],
        },
      }).then(function (res) {
        var a = looseJson(String((res && res.content) || ''));
        var varied = false;
        if (!a || !a.patA) {
          // 续写时模型偶尔交白卷（真机 2 次里遇到 1 次）。这时不该让用户白等——**用已有主题做个
          // 变奏**：移调 + 逆行都是正经的变奏手法，而且完全确定性。首轮没东西可变，才真的空手。
          if (prev) { a = variationOf(prev, a); varied = true; }
          if (!a) {
            ctl.finish({ content: '这次没写出能用的片段，要不换个说法再试试？' });
            return;
          }
        }
        if (prev) {                       // 续写：调、速、音色一律沿用上一轮（这才叫风格统一）
          a.tempo = prev.tempo; a.key = prev.key; a.inst = prev.inst; a.mood = prev.mood;
        } else {
          a = applyMood(a, mood);
          if (mood) a.mood = mood.name;
        }
        var script = buildScript(a, prev);
        var sc = parseScore(script);
        if (!sc) {
          ctl.finish({ content: '这次生成的音符没解析出来（要的是像 C4 E4 G4 这样的音名或 1 2 3 简谱）。' });
          return;
        }
        var piece = pieceData(sc);
        markPieceSession(sessionId);                 // 之后这个会话里的「再来一段」才接得住
        remember(piece);
        rememberLast(sessionId, script, sc);
        // 卡片走桥路径（§6.12）：接管的响应体只支持 content/notice，卡片得自己插一条消息。
        gguf.card.post({ file: 'card.html', data: piece });
        ctl.finish({ content: intro(sc, !!prev) + (varied ? '（这段是拿主题做的变奏）' : '') });
      });
    }).catch(function () {
      ctl.finish({ content: '编曲这一步没跑通（模型忙或超时），稍后再试。' });
    });
  });

  /**
   * 宽容取 JSON：先正常 parse；失败就**按字段抢救**（截断的 JSON 往前面几个字段通常是完整的）。
   * 与本插件解析音符时一样的取向——能救回多少算多少，别因为尾巴烂了把好的部分一起扔掉。
   */
  function looseJson(raw) {
    var t = String(raw || '').trim();
    var m = /\{[\s\S]*\}/.exec(t);
    if (m) { try { return JSON.parse(m[0]); } catch (e) { /* 落到抢救 */ } }
    try { return JSON.parse(t); } catch (e) { /* 落到抢救 */ }
    var out = {}, got = false;
    ['patA', 'patB', 'play', 'key', 'inst'].forEach(function (k) {
      var mm = new RegExp('"' + k + '"\\s*:\\s*"([^"\\\\]{0,120})').exec(t);
      if (mm) { out[k] = mm[1]; got = true; }
    });
    var mt = /"tempo"\s*:\s*(\d{2,3})/.exec(t);
    if (mt) { out.tempo = parseInt(mt[1], 10); got = true; }
    return got ? out : null;
  }

  /**
   * 模型交白卷时的确定性变奏：拿已有片段做移调 / 逆行 / 移调+逆行，按已有段数轮换，
   * 所以连续几次兜底不会给出同一段。纯 JS，不需要模型。
   */
  function variationOf(prev, a) {
    var src = prev.pats[prev.pats.length - 1] || prev.pats[0];
    if (!src) return null;
    var toks = String(src.notes).split(/\s+/).filter(Boolean);
    if (!toks.length) return null;
    var kind = (prev.pats.length + String(prev.play).split(/\s+/).length) % 3;
    if (kind === 1) toks = toks.slice().reverse();                       // 逆行
    var shift = kind === 0 ? 5 : (kind === 2 ? -3 : 0);                  // 上行四度 / 下行小三度
    var play = 'A A';
    var out = { patA: toks.join(' '), play: play };
    if (shift) out.play = 'A' + (shift > 0 ? '+' : '') + shift + ' A' + (shift > 0 ? '+' : '') + shift;
    return out;
  }

  /** 系统提示词：首轮要调性与曲式，续写只要「一个新片段」，其余全部沿用（模型不必重复劳动）。 */
  function sysPrompt(prev, mood) {
    if (prev) {
      var known = prev.pats.map(function (p) { return p.name + '：' + p.notes; }).join('\n');
      return '你是作曲助手，只输出 JSON。这是一首已经开始的曲子，'
        + prev.key + ' 调，' + prev.tempo + ' BPM' + (prev.mood ? '，情绪：' + prev.mood : '') + '。\n'
        + '已有的片段：\n' + known + '\n\n'
        + 'patA 写**一个新片段**，8～16 个音，要与上面的片段**同调同风格但有变化**'
        + '（换走向、换音区、或做变奏），能接在它们后面听着自然。\n'
        + 'play 只写这个新片段要怎么排，例如 "A A B"（用你新写的这一段的名字就写 A）。\n'
        + '音符写法：音名 C4 E4 G4 或简谱 1 2 3，一个音占一个八分拍，- 延长，0 休止，| 分小节。\n'
        + '不要重写已有片段、不要写和弦与低音、不要解释。\n'
        + '例：{"patA":"E5 D5 C5 - | B4 A4 G4 -","play":"A A"}';
    }
    var moodLine = mood
      ? '情绪要求：' + mood.name + '——' + (mood.minor ? '用小调' : '用大调') + '，速度 '
        + mood.bpm[0] + '～' + mood.bpm[1] + ' BPM，音色建议 ' + mood.inst
        + (mood.dense === 'sparse' ? '，音符别太密、多用 - 延长' : '，节奏可以密一些') + '。\n'
      : '';
    // **示例要轮换、还要明说别照抄**：实测同一个「悲伤」问三次，模型三次都原样吐回示例里的
    // A4 C5 E5 —— 每首曲子都一样。轮换 + 禁止照抄之后才有变化。
    var ex = EXAMPLES[exampleCursor++ % EXAMPLES.length];
    return '你是作曲助手，只输出 JSON。\n' + moodLine
      + 'patA 写主题（8～16 个音），patB 写与它有对比的段落（换音区或换走向）。\n'
      + '音符写法：音名 C4 E4 G4（C4 是中央 C）或简谱 1 2 3，一个音占一个八分拍，'
      + '- 让前一个音延长一拍，0 是休止，| 分小节。旋律要能唱得下来。\n'
      + 'play 写播放顺序，用 A 和 B，可加 +数字/-数字 移调、*数字 重复，例如 "A A B A"。\n'
      + 'inst 从 piano / bell / pluck / strings 里选一个。\n'
      + '不要写和弦、不要写低音、不要解释——低音、和声、混音由程序自动配。\n'
      + '**下面只是格式示例，音符必须你自己写，不要照抄示例里的音**：\n' + ex;
  }

  // ============================== 情绪 → 乐理（知识外置） ==============================
  //
  // 「悲伤的曲子」这种要求，模型不需要懂「悲伤 = 小调 + 慢 + 弦乐 + 低音区」——**这是可以查表的
  // 常识，属于该外置给 JS 的部分**。模型只管写旋律轮廓，情绪的乐理参数由这里定；模型如果写了
  // 与情绪矛盾的速度/调式（要悲伤却给了 140 BPM 大调），JS 按情绪夹回去。
  var MOODS = [
    { re: /悲伤|忧伤|难过|伤感|失落|哀|sad|melanch/i, name: '悲伤', minor: true,  bpm: [58, 76],  inst: 'strings', dense: 'sparse' },
    { re: /安静|平静|舒缓|温柔|轻柔|放松|睡|calm|quiet|gentle/i, name: '安静', minor: false, bpm: [64, 84],  inst: 'bell',    dense: 'sparse' },
    { re: /欢快|轻快|开心|愉快|活泼|明亮|happy|cheer|bright/i,   name: '欢快', minor: false, bpm: [108, 132], inst: 'pluck',   dense: 'busy' },
    { re: /紧张|急促|悬疑|不安|阴森|tense|dark|suspense/i,        name: '紧张', minor: true,  bpm: [116, 148], inst: 'pluck',   dense: 'busy' },
    { re: /浪漫|抒情|深情|温暖|romantic|warm/i,                   name: '抒情', minor: false, bpm: [72, 92],  inst: 'piano',   dense: 'sparse' },
    { re: /激昂|磅礴|史诗|壮阔|epic|grand/i,                      name: '激昂', minor: true,  bpm: [96, 124], inst: 'strings', dense: 'busy' },
  ];

  // 格式示例轮换（见 sysPrompt 里的说明）：不同的调、不同的旋律走向，避免模型每次照抄同一串音。
  var EXAMPLES = [
    '{"tempo":76,"key":"Am","patA":"A4 C5 E5 - | G4 B4 D5 -","patB":"F4 A4 C5 - | E4 G4 B4 -","play":"A A B A","inst":"strings"}',
    '{"tempo":104,"key":"G","patA":"G4 A4 B4 D5 | B4 A4 G4 -","patB":"D5 C5 B4 A4 | G4 - - -","play":"A A B A","inst":"pluck"}',
    '{"tempo":88,"key":"F","patA":"F4 - A4 C5 | D5 C5 A4 -","patB":"C5 Bb4 A4 G4 | F4 - - -","play":"A B A B","inst":"piano"}',
    '{"tempo":70,"key":"Em","patA":"E4 G4 B4 - | A4 G4 E4 -","patB":"B4 A4 G4 F#4 | E4 - - -","play":"A A B A","inst":"bell"}',
    '{"tempo":120,"key":"D","patA":"D5 - C5 A4 | G4 A4 C5 -","patB":"A4 G4 F#4 E4 | D4 - - -","play":"A B A A","inst":"pluck"}',
  ];
  var exampleCursor = 0;

  function detectMood(text) {
    for (var i = 0; i < MOODS.length; i++) if (MOODS[i].re.test(text)) return MOODS[i];
    return null;
  }

  /** 把模型给的 tempo/key 按情绪夹回去（只在与情绪明显矛盾时动手）。 */
  function applyMood(a, mood) {
    if (!mood) return a;
    var t = parseInt(a.tempo, 10);
    if (!(t >= 40 && t <= 200)) t = Math.round((mood.bpm[0] + mood.bpm[1]) / 2);
    if (t < mood.bpm[0] || t > mood.bpm[1]) t = Math.min(mood.bpm[1], Math.max(mood.bpm[0], t));
    a.tempo = t;
    var k = String(a.key || 'C').trim();
    var bare = (/^[A-Ga-g][#b]?/i.exec(k) || ['C'])[0];      // 只留音名，调式由情绪定
    if (mood.minor !== keyIsMinor(k)) a.key = bare + (mood.minor ? 'm' : '');
    if (!a.inst) a.inst = mood.inst;
    return a;
  }

  // ============================== 曲式兜底 + 多轮续写 ==============================
  //
  // 用户的话（2026-07-27）：「不用奢求一次性生成超长音乐；可以多次生成，从而得到风格统一、
  // 但富有变化的旋律」。所以这里**不再机械重复凑时长**（那是假的长曲子），改成两件事：
  //   ① 单轮只保证**有对比段**（AABA 型），长度就按模型写的来；
  //   ② 说「再来一段 / 接着写」时**接着上一轮长**：同调、同速、同音色（风格统一），
  //      让模型只写一个**新片段**（富有变化），JS 把它接到已有编排后面。
  var contRe = /再来一段|再写一段|接着写|继续写|续写|接下去|再变|变化一点|下一段|再来点|加一段/;

  function normalizePlay(raw, names) {
    var items = String(raw || '').split(/[\s,，]+/).filter(function (t) {
      return /^[A-Za-z0-9_]{1,12}([+\-]\d{1,2})?(?:[*x×]\d{1,2})?$/.test(t)
        && names.indexOf(t.replace(/[+\-*x×].*$/, '')) >= 0;
    });
    if (!items.length) items = [names[0]];
    // 只有一种片段时补一个对比段：有 B 用 B，没有就用「主题上行四度」当副歌
    var distinct = {};
    items.forEach(function (t) { distinct[t.replace(/[+\-*x×].*$/, '')] = 1; });
    if (Object.keys(distinct).length < 2) {
      var contrast = names.length > 1 ? names[1] : names[0] + '+5';
      items = items.concat([contrast, names[0]]);
    }
    return items.slice(0, 24).join(' ');
  }

  function buildScript(a, prev) {
    var pats = [], names = [];
    if (prev) {                                  // 续写：把上一轮的片段原样带上，风格才连得住
      for (var i = 0; i < prev.pats.length; i++) {
        pats.push('pat ' + prev.pats[i].name + ': ' + prev.pats[i].notes);
        names.push(prev.pats[i].name);
      }
    }
    var newName = prev ? String.fromCharCode(65 + Math.min(23, names.length)) : 'A';
    if (a.patA) { pats.push('pat ' + newName + ': ' + String(a.patA).trim()); names.push(newName); }
    if (a.patB && !prev) { pats.push('pat B: ' + String(a.patB).trim()); names.push('B'); }
    var play;
    if (prev) {
      // **模型说的「A」指的是它新写的那一段**（提示词里就是这么让它写的），而脚本里 A 已经是
      // 上一轮的主题——直接拼会导致新片段定义了却从没被演奏（实测踩到）。所以只取它的**段数**，
      // 名字一律重映射到新片段，末尾再回一次主题（新段 + 主题重述，是个正经的曲式收尾）。
      var n = Math.min(4, Math.max(1, String(a.play || '').split(/[\s,，]+/).filter(Boolean).length || 2));
      var seq = [];
      for (var j = 0; j < n; j++) seq.push(newName);
      seq.push(names[0]);
      play = prev.play + ' ' + seq.join(' ');
    } else {
      play = normalizePlay(a.play, names);
    }
    return 'tempo:' + (a.tempo || 96) + ' key:' + (a.key || 'C') + '\n'
      + (a.mood ? 'mood:' + a.mood + '\n' : '')
      + 'inst: melody=' + (a.inst || 'piano') + '\n'
      + pats.join('\n') + '\nplay: ' + play;
  }

  function durSec(sc) { return (sc.totalTicks / sc.tpq) * (60 / sc.tempo); }

  /**
   * 记住本会话上一轮的片段与编排，供续写接着长。
   *
   * **同步内存副本 + 异步落盘两条**：只落 storage 会有竞态——真机上「写一首悲伤的调」紧接着
   * 「使用工具」，第二轮赶在 storage.set 落地之前到达，prev 读成空、于是另起了一首新曲子
   * （调、速度、情绪全变了）。内存副本立刻可用，落盘那份留给 service WebView 重建后接着用。
   */
  var lastPiece = {};
  function rememberLast(sessionId, script, sc) {
    if (!sessionId) return Promise.resolve();
    var pats = [];
    String(script).replace(/^pat\s+([A-Za-z0-9_]{1,12})\s*[:：]\s*(.+)$/gim, function (_, n, notes) {
      pats.push({ name: n, notes: notes.trim() }); return '';
    });
    var play = (/^play\s*[:：]\s*(.+)$/im.exec(script) || [])[1] || '';
    var state = {
      key: sc.key, tempo: sc.tempo, inst: sc.inst.melody, mood: sc.mood,
      pats: pats, play: play.trim(), ts: Date.now(),
    };
    lastPiece[sessionId] = state;                       // 同步，下一轮立刻读得到
    var keys = Object.keys(lastPiece);
    if (keys.length > 24) {                            // 有界
      keys.sort(function (a, b) { return lastPiece[a].ts - lastPiece[b].ts; });
      for (var i = 0; i < keys.length - 24; i++) delete lastPiece[keys[i]];
    }
    return gguf.storage.set('last:' + sessionId, state).catch(function () {});
  }

  function intro(sc, isCont) {
    var bars = Math.max(1, Math.round(sc.totalTicks / sc.barTicks));
    var secs = (sc.totalTicks / sc.tpq) * (60 / sc.tempo);
    var arr = arrangeSummary(sc);
    return (isCont ? '接着上一段又长了一节：' : '给你写了一段：')
      + sc.key + (sc.minor ? ' 小调' : ' 大调') + '，' + sc.tempo + ' BPM，'
      + (sc.mood ? sc.mood + '，' : '')
      + bars + ' 小节 / ' + fmtDur(secs) + (arr ? '，编排 ' + arr : '') + '。';
  }

  function pieceData(sc) {
    return {
      tempo: sc.tempo, key: sc.key, minor: sc.minor, mood: sc.mood,
      tpq: sc.tpq, barTicks: sc.barTicks, totalTicks: sc.totalTicks,
      tracks: sc.tracks, inst: sc.inst, mix: sc.mix, sections: sc.sections,
      melody: sc.melody, bass: sc.bass,          // 兼容 1.0 的卡片与旧存档
      arrange: arrangeSummary(sc),
      text: scoreToText(sc), postedAt: Date.now(),
    };
  }

  // ============================== tool：模型自己用协议时的那条路 ==============================

  gguf.hooks.tool(function (name, input) {
    if (name !== 'midi.play') return {};
    var sc = parseScore(input);
    if (!sc) {
      // 认不出来就老实说，不出卡片、不假装（宁可空手，不给用户一个假的播放器）
      return { notice: '这段乐句没认出可用的音符，已跳过（音名如 C4 E4 G4，或简谱 1 2 3）' };
    }
    var piece = pieceData(sc);
    remember(piece);
    return { card: { file: 'card.html', data: piece } };
  });

  /** 存最近 12 段，供面板回看/重发（卡片 WebView 会被回收，状态不能留在页面里）。 */
  function remember(piece) {
    gguf.storage.get('recent').then(function (v) {
      var list = Array.isArray(v) ? v : [];
      list.unshift(piece);
      if (list.length > 12) list.length = 12;
      return gguf.storage.set('recent', list);
    }).then(function () {
      gguf.events.broadcast('midi-updated', null);
    }).catch(function () { /* storage 不可用不影响出卡片 */ });
  }

  // ============================== 导出 .mid（标准 MIDI 文件）==============================
  //
  // 之前一直标注「不导出 .mid，因为 WebView 里存二进制没有好通道」——现在宿主补了
  // `gguf.files.saveDownload`（§6.15），通道有了，就该把这件事补上：音符模型本来就是 MIDI 语义
  // （音高 0–127、力度、以 tick 计时长），编码成 SMF 只是把它写成字节。
  //
  // 格式：SMF format 1，division = TPQ；轨 0 只放速度元事件，其余每轨一个 GM 音色 + 音符。
  var GM = { piano: 0, bell: 9, pluck: 45, strings: 48, bass: 32 };

  function vlq(n) {                                   // MIDI 的变长整数
    var bytes = [n & 0x7f];
    n >>= 7;
    while (n > 0) { bytes.unshift((n & 0x7f) | 0x80); n >>= 7; }
    return bytes;
  }
  function be(n, len) {
    var out = [];
    for (var i = len - 1; i >= 0; i--) out.push((n >> (i * 8)) & 0xff);
    return out;
  }
  function chunk(tag, body) {
    var out = [];
    for (var i = 0; i < tag.length; i++) out.push(tag.charCodeAt(i));
    return out.concat(be(body.length, 4), body);
  }
  function trackBody(notes, channel, program, name) {
    var evts = [];                                    // {t, bytes}
    for (var i = 0; i < name.length; i++) { /* 轨名在下面单独写 */ }
    var nameBytes = [];
    for (i = 0; i < name.length; i++) nameBytes.push(name.charCodeAt(i) & 0x7f);
    var body = [].concat(vlq(0), [0xff, 0x03], vlq(nameBytes.length), nameBytes,   // 轨名
                         vlq(0), [0xc0 | channel, program & 0x7f]);                // 音色
    for (i = 0; i < notes.length; i++) {
      var n = notes[i];
      evts.push({ t: n.start, b: [0x90 | channel, n.midi & 0x7f, Math.max(1, Math.min(127, n.vel || 90))] });
      evts.push({ t: n.start + n.dur, b: [0x80 | channel, n.midi & 0x7f, 0] });
    }
    evts.sort(function (a, b) { return a.t - b.t || (a.b[0] & 0xf0) - (b.b[0] & 0xf0); });
    var last = 0;
    for (i = 0; i < evts.length; i++) {
      body = body.concat(vlq(evts[i].t - last), evts[i].b);
      last = evts[i].t;
    }
    return body.concat(vlq(0), [0xff, 0x2f, 0x00]);   // End of Track
  }

  /** 乐句 → 标准 MIDI 文件的 base64（给 gguf.files.saveDownload）。 */
  function toSmfBase64(piece) {
    var tracks = piece.tracks && piece.tracks.melody
      ? piece.tracks
      : { melody: piece.melody || [], bass: piece.bass || [], pad: [] };
    var inst = piece.inst || { melody: 'piano', bass: 'bass', pad: 'strings' };
    var tpq = piece.tpq || 480;
    var usPerQuarter = Math.round(60000000 / (piece.tempo || 92));
    var head = [].concat(vlq(0), [0xff, 0x51, 0x03], be(usPerQuarter, 3),
                         vlq(0), [0xff, 0x2f, 0x00]);
    var bodies = [chunk('MTrk', head)];
    var order = ['melody', 'bass', 'pad'], ch = 0;
    for (var i = 0; i < order.length; i++) {
      var name = order[i], notes = tracks[name] || [];
      if (!notes.length) continue;
      var prog = GM[inst[name]] != null ? GM[inst[name]] : (name === 'bass' ? 32 : 0);
      bodies.push(chunk('MTrk', trackBody(notes, ch, prog, name)));
      ch = (ch + 1) % 16;
    }
    var mthd = chunk('MThd', [].concat(be(1, 2), be(bodies.length, 2), be(tpq, 2)));
    var all = mthd;
    for (i = 0; i < bodies.length; i++) all = all.concat(bodies[i]);
    var bin = '';
    for (i = 0; i < all.length; i++) bin += String.fromCharCode(all[i] & 0xff);
    return (typeof btoa === 'function' ? btoa(bin) : Buffer.from(bin, 'binary').toString('base64'));
  }

  function fileNameFor(piece) {
    var d = new Date(piece.postedAt || Date.now());
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    // 调号本身可能已经带 m/小调（Am），别再补一个（曾出现「Amm」）
    var key = String(piece.key || 'C').replace(/\s+/g, '');
    if (piece.minor && !/m|min|小调/i.test(key)) key += 'm';
    return '乐句-' + key + '-' + piece.tempo + 'bpm-'
      + p(d.getMonth() + 1) + p(d.getDate()) + p(d.getHours()) + p(d.getMinutes()) + '.mid';
  }

  // ============================== 消息角标：下载这段乐句 ==============================
  //
  // 与朗读插件同一个位置（消息下方的复制/朗读那一行）。角标是**静态声明**的，每条助手消息都会有，
  // 所以点在非乐句消息上时要说明白，而不是默不作声。
  gguf.hooks.messageAction(function (payload) {
    if (!payload || payload.actionId !== 'midi.download') return {};
    var text = String((payload.message && payload.message.content) || '');
    if (text.indexOf('🎵 乐句') < 0) {
      gguf.ui.toast('这条不是乐句，没什么可下载的');
      return {};
    }
    return gguf.storage.get('recent').then(function (list) {
      var arr = Array.isArray(list) ? list : [];
      var hit = null;
      for (var i = 0; i < arr.length; i++) {
        if (String(arr[i].text || '').trim() === text.trim()) { hit = arr[i]; break; }
      }
      if (!hit) {
        gguf.ui.toast('这段乐句不在最近记录里（只留最近 12 段）');
        return {};
      }
      return gguf.files.saveDownload({
        name: fileNameFor(hit), mime: 'audio/midi', base64: toSmfBase64(hit),
      }).then(function () { return {}; }).catch(function (e) {
        gguf.ui.toast('导出失败：' + ((e && e.message) || e));
        return {};
      });
    }).catch(function () { gguf.ui.toast('读取最近乐句失败'); return {}; });
  });
})();
