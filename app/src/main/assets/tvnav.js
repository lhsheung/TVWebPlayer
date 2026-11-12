/*
 * TV 空间导航（注入到每一个 frame）。
 *
 * 目标：让遥控器在「真实网页」上能像在空间里移动一样操作，
 * 同时不改写网页结构、不替换网页功能。
 *
 * 设计：
 *  - 候选元素集合按任务要求自定义（含 [onclick] / [role=button] / [tabindex]），
 *    比 WICG polyfill 的 tabIndex-only 判据更适合真实中文站点。
 *  - 方向键 = 几何最近邻；确认键由 Android 侧派发真实 MotionEvent（见 D-004）。
 *  - 焦点框用独立的浮层，不给元素加 transform —— 否则 getBoundingClientRect
 *    会跟着缩放变化，下一轮几何计算就漂移了。
 *  - 每个 frame 都有自己的实例；跨域 iframe 由 postMessage 转发按键。
 */
(function () {
  'use strict';

  if (window.__tvnavBooted) return;
  window.__tvnavBooted = true;

  var IS_TOP;
  try { IS_TOP = (window.top === window.self); } catch (e) { IS_TOP = false; }

  var FOCUS_CLASS = '__tvnav_focus__';
  var BOX_ID = '__tvnav_box__';
  var STYLE_ID = '__tvnav_style__';

  // 任务要求覆盖的可交互元素
  var SELECTOR = [
    'a[href]', 'a[onclick]', 'button', 'input', 'select', 'textarea',
    '[onclick]', '[role="button"]', '[role="link"]', '[role="menuitem"]',
    '[role="tab"]', '[tabindex]', '[contenteditable="true"]', 'summary',
    'area[href]', 'iframe'
  ].join(',');

  // ---------- 抓视频真实流地址 ----------
  //
  // 站点用 hls.js / MSE 时 `<video>.src` 是 `blob:`，真实 m3u8 只在网络层可见。
  // 电视端的 WebView 常常「有声音没画面」，唯一的解法是把地址交给 App 内置的
  // 原生播放器（ExoPlayer）。这段 hook 必须在站点脚本之前执行，所以放在
  // 本文件顶部——tvnav.js 是通过 addDocumentStartJavaScript 注入的。
  var lastM3u8 = '';
  var lastTsUrl = '';

  function noteMediaUrl(u) {
    if (!u || typeof u !== 'string') return;
    var abs = u;
    try { abs = new URL(u, location.href).href; } catch (e) {}
    if (/\.m3u8(\?|#|$)/i.test(abs)) { lastM3u8 = abs; return; }
    if (/\.ts(\?|#|$)/i.test(abs)) lastTsUrl = abs;
  }

  (function hookMediaUrl() {
    try {
      var xo = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (m, u) {
        try { noteMediaUrl(u); } catch (e) {}
        return xo.apply(this, arguments);
      };
    } catch (e) {}
    try {
      var f = window.fetch;
      if (f) {
        window.fetch = function (input) {
          try {
            noteMediaUrl(typeof input === 'string' ? input : (input && input.url));
          } catch (e) {}
          return f.apply(this, arguments);
        };
      }
    } catch (e) {}
  })();

  /**
   * 挂 hls.js / 站点播放器的钩子。
   *
   * 粤漫之家用 Artplayer + hls.js，playlist 地址由一个全局函数 playM3u8 传进去，
   * 请求走的是 WebView 网络层看不到的通道（worker / 内部 XHR），
   * shouldInterceptRequest 和 performance 都抓不到。唯一稳的入口就是这两个。
   * hls.js / playM3u8 属于页面脚本，晚于本文件执行，所以轮询等它出现。
   */
  function wrapHlsCtor(Orig) {
    if (typeof Orig !== 'function' || Orig.__tvWrapped) return Orig;
    function W(cfg) {
      var inst = new Orig(cfg);
      try {
        var ls = inst.loadSource;
        if (ls) {
          inst.loadSource = function (u) {
            try { if (typeof u === 'string') { noteMediaUrl(u); lastM3u8 = u; } } catch (e) {}
            return ls.apply(inst, arguments);
          };
        }
      } catch (e) {}
      return inst;
    }
    W.prototype = Orig.prototype;
    W.__tvWrapped = true;
    return W;
  }

  function wrapFn(fn) {
    if (typeof fn !== 'function' || fn.__tvWrapped) return fn;
    var w = function (u) {
      try { if (typeof u === 'string') { noteMediaUrl(u); lastM3u8 = u; } } catch (e) {}
      return fn.apply(this, arguments);
    };
    w.__tvWrapped = true;
    return w;
  }

  /**
   * 在全局变量被赋值的那一刻就挂钩子。
   *
   * 轮询会晚于页面脚本：粤漫之家实测「钩子确实挂上了，但播放器已经拿走了
   * 原始的 Hls / playM3u8 引用」，地址照样抓不到。用 defineProperty 拦赋值，
   * 页面一设置 window.Hls、window.playM3u8，拿到的就是包装版。
   */
  (function interceptPlayerGlobals() {
    function watch(name, wrap) {
      try {
        var val = wrap(window[name]);
        Object.defineProperty(window, name, {
          configurable: true,
          enumerable: true,
          get: function () { return val; },
          set: function (v) { val = wrap(v); }
        });
      } catch (e) {}
    }
    watch('Hls', wrapHlsCtor);
    watch('playM3u8', wrapFn);
  })();

  // 兜底：少数打包方式用 var 声明，赋值不走 setter，靠轮询再补一次
  var hookTries = 0;
  var hookTimer = setInterval(function () {
    try {
      if (window.Hls && !window.Hls.__tvWrapped) window.Hls = wrapHlsCtor(window.Hls);
    } catch (e) {}
    try {
      if (window.playM3u8 && !window.playM3u8.__tvWrapped) {
        window.playM3u8 = wrapFn(window.playM3u8);
      }
    } catch (e) {}
    if (++hookTries > 300) { clearInterval(hookTimer); hookTimer = null; }
  }, 150);

  function currentM3u8() {
    if (lastM3u8) return lastM3u8;
    try {
      var h = window.hls || window.__hls;
      if (h && h.url) return h.url;
    } catch (e) {}
    return '';
  }

  /**
   * 从页面里把真实播放地址挖出来。
   *
   * 电视端的 WebView 播这类站点经常「有声音没画面」，靠 CSS 修不回来。
   * 可行的路线是把地址交给 App 内置播放器，所以这里按命中率从高到低找：
   *   1. 苹果CMS / MacCMS 的播放页全局变量（国内影视站绝大多数是这套）
   *   2. hls.js 实例上记录的源
   *   3. 网络层 hook 到的 .m3u8
   *   4. <video> / <source> 的 src（排除 blob:）
   *   5. performance 资源列表、页面 HTML 里出现的 m3u8 文本
   * 拿不到就返回空串，页面维持原样，不会更糟。
   */
  function extractMediaUrl() {
    var cands = [];
    try {
      var pa = window.player_aaaa;
      if (pa) { cands.push(pa.url, pa.link); }
      var pd = window.player_data;
      if (pd) { cands.push(pd.url); }
      if (window.MacPlayer && window.MacPlayer.Config) {
        try {
          var pl = window.MacPlayer.Config.player_list;
          if (pl) { for (var k in pl) { if (pl[k] && pl[k].url) cands.push(pl[k].url); } }
        } catch (e) {}
      }
    } catch (e) {}
    try { if (window.hls && window.hls.url) cands.push(window.hls.url); } catch (e) {}
    try { if (window.__hls && window.__hls.url) cands.push(window.__hls.url); } catch (e) {}
    if (lastM3u8) cands.push(lastM3u8);

    var v = document.querySelector('video');
    if (v) {
      cands.push(v.currentSrc, v.src, v.getAttribute && v.getAttribute('data-src'));
      var ss = v.querySelectorAll('source');
      for (var i = 0; i < ss.length; i++) cands.push(ss[i].src);
    }
    try {
      var rs = performance.getEntriesByType('resource') || [];
      for (var j = 0; j < rs.length; j++) {
        if (/\.m3u8(\?|#|$)/i.test(rs[j].name)) cands.push(rs[j].name);
      }
    } catch (e) {}
    try {
      var html = document.documentElement.innerHTML || '';
      var m = html.match(/https?:\/\/[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*/gi);
      if (m) cands = cands.concat(m);
    } catch (e) {}

    for (var c = 0; c < cands.length; c++) {
      var u = cands[c];
      if (!u || typeof u !== 'string') continue;
      if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) continue;
      if (u.slice(0, 2) === '//') u = location.protocol + u;
      if (!/^https?:\/\//i.test(u)) continue;
      if (/\.(ts|jpg|jpeg|png|gif|webp|css|js|json)(\?|#|$)/i.test(u)) continue;
      return u;
    }
    return '';
  }

  var handoffDone = false;     // 本次页面是否已经交给原生（成功后不再重试）
  var handoffTimer = null;

  function tryHandoff() {
    if (handoffDone) return true;
    var u = extractMediaUrl();
    var v = document.querySelector('video');
    var ok = handoffToNative(u);
    if (ok) {
      handoffDone = true;
      // 等原生接住了再暂停网页播放，避免地址没抓到时白停一下
      try { if (v) v.pause(); } catch (e) {}
      if (handoffTimer) { clearInterval(handoffTimer); handoffTimer = null; }
    }
    return ok;
  }

  /**
   * 进入播放页后反复尝试接管。
   *
   * 首次尝试往往太早——hls.js 还没请求 m3u8，原生侧也还没嗅探到地址。
   * 每 2 秒试一次，最多 10 次，成功即停。
   */
  function startHandoffWatch() {
    if (handoffDone || handoffTimer) return;
    var tries = 0;
    handoffTimer = setInterval(function () {
      tries++;
      if (tries > 12 || handoffDone) {
        clearInterval(handoffTimer);
        handoffTimer = null;
        return;
      }
      if (tryHandoff()) return;
    }, 2000);
  }

  /**
   * 4K 电视的视口缩放。
   *
   * 3840×2160 且 dpr=1 的电视上，CSS 视口就是 3840 宽。站点仍按 1920（或更窄的
   * 移动版）设计，于是在这块「超宽画布」上所有文字、卡片只有一半大小，
   * 播放器也容易被挤到视口外——这就是「电视上字太小 / 看不到播放框」的根因。
   *
   * 做法：把 viewport 固定成 width=1920，再按 screen.width/1920 放大铺满。
   * dpr=2 的 4K 屏 screen.width 本来就是 1920，不会触发，保持站点原样。
   */
  function applyViewportScale() {
    try {
      var sw = (window.screen && window.screen.width) || window.innerWidth || 1920;
      if (sw < 2560) return;
      var scale = Math.min(3, sw / 1920);
      var m = document.querySelector('meta[name="viewport"]');
      if (!m) {
        m = document.createElement('meta');
        m.setAttribute('name', 'viewport');
        (document.head || document.documentElement).appendChild(m);
      }
      m.setAttribute('content', 'width=1920, initial-scale=' + scale.toFixed(2));
    } catch (e) {}
  }

  // 尽早生效：document-start 时就把 viewport 定下来，避免页面先按 3840 排一遍再跳变
  applyViewportScale();
  try {
    document.addEventListener('DOMContentLoaded', applyViewportScale, { once: true });
  } catch (e) {}

  function handoffToNative(url) {
    try {
      if (window.TvHost && window.TvHost.openNativePlayer) {
        // 原生返回 false 表示它手上也还没有地址（可能正在异步反推 m3u8），
        // 这种情况要当作「没接住」，让 JS 继续按秒重试。
        return !!window.TvHost.openNativePlayer(url || '', document.title || '');
      }
    } catch (e) {}
    return false;
  }

  var current = null;
  var focusSeq = 0;         // 每次换焦点自增，供 Android 判断"是不是同一个输入框"
  var currentId = 0;
  var activeFrame = null;   // 仅主 frame 使用：焦点进入跨域 iframe 后的目标 window
  var activeFrameEl = null;

  // ---------- 样式 ----------

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var s = document.createElement('style');
    s.id = STYLE_ID;
    s.textContent = [
      // 只用元素自身的描边表示焦点：不额外画浮框，也就不会挡住字幕或
      // 在全屏时浮在视频上面。2px 在电视上够看清，又不会糊住文字。
      '.__tvnav_focus__{outline:2px solid rgba(255,255,255,.9)!important;outline-offset:2px!important;}',
      // 输入类元素单独用高对比蓝色 + 外阴影：站点普遍给 input/select 设了
      // border:0、白底、甚至 outline:none，白描边落在白底输入框上等于看不见，
      // 用户就会觉得「选择框不高亮」。
      'input.__tvnav_focus__,select.__tvnav_focus__,textarea.__tvnav_focus__,' +
        '[contenteditable="true"].__tvnav_focus__{' +
        'outline:3px solid #3B8CFF!important;outline-offset:2px!important;' +
        'box-shadow:0 0 0 4px rgba(0,0,0,.55)!important;}'
    ].join('');
    var host = document.head || document.documentElement;
    if (host) host.appendChild(s);
  }

  // 焦点框：恢复「跟随浮框」，因为只靠元素自身 outline 在真实站点上不可靠 ——
  // 粤漫之家这类站点把卡片放在 overflow:hidden 的容器里，outline 被裁掉，
  // 遥控器移动时画面上什么都没有，用户无法确认当前选中哪一项。
  // 全屏播放浮层（fsOverlay）里仍然不画，避免浮在视频上挡字幕。
  function updateBox() {
    var box = document.getElementById(BOX_ID);
    if (!current || !current.isConnected || fsOverlay) {
      if (box && box.parentNode) box.parentNode.removeChild(box);
      return;
    }
    var tag = current.tagName.toLowerCase();
    if (tag === 'video' || tag === 'iframe') {
      if (box) box.style.display = 'none';
      return;
    }
    var r;
    try { r = rect(current); } catch (e) { return; }
    if (!r || r.width < 2 || r.height < 2) {
      if (box) box.style.display = 'none';
      return;
    }
    if (!box) {
      box = document.createElement('div');
      box.id = BOX_ID;
      box.style.cssText =
        'position:fixed;pointer-events:none;z-index:2147483646;box-sizing:border-box;' +
        'border:3px solid #3B8CFF;border-radius:6px;' +
        'box-shadow:0 0 0 2px rgba(0,0,0,.6),0 0 14px rgba(59,140,255,.55);';
      (document.body || document.documentElement).appendChild(box);
    }
    box.style.display = 'block';
    box.style.left = (r.left - 4) + 'px';
    box.style.top = (r.top - 4) + 'px';
    box.style.width = (r.width + 8) + 'px';
    box.style.height = (r.height + 8) + 'px';
  }

  // ---------- 候选元素 ----------

  function rect(el) { return el.getBoundingClientRect(); }

  /**
   * 站点里常有「有尺寸、可点击、但没有任何可见内容」的占位块
   * （懒加载海报的空壳、广告预留位、绝对定位的空 a）。
   * 焦点落上去看起来就是一块黑框，用户会以为遥控器失灵。
   * 判据：自身或后代有文字 / 图片 / 图形 / 背景图 / 伪元素内容，
   * 或元素本身就是原生控件。
   */
  function hasVisibleContent(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' || tag === 'select' || tag === 'textarea' ||
        tag === 'button' || tag === 'iframe' || tag === 'area' || tag === 'summary') return true;

    var txt = el.innerText || el.textContent || '';
    if (txt.replace(/\s+/g, '').length > 0) return true;

    var media = el.querySelectorAll('img,svg,canvas,video,picture,object,embed');
    for (var i = 0; i < media.length; i++) {
      var r = rect(media[i]);
      if (r.width > 2 && r.height > 2) return true;
      // 懒加载：图片还没解码，但 src 已经在（或存在 data-src 占位）
      if (media[i].getAttribute && (
        media[i].getAttribute('src') || media[i].getAttribute('srcset') ||
        media[i].getAttribute('data-src') || media[i].getAttribute('data-original')
      )) return true;
    }

    var st = window.getComputedStyle(el);
    if (st.backgroundImage && st.backgroundImage !== 'none') return true;
    try {
      var before = window.getComputedStyle(el, '::before');
      var after = window.getComputedStyle(el, '::after');
      if (pseudoContent(before) || pseudoContent(after)) return true;
    } catch (e) {}
    return false;
  }

  function pseudoContent(st) {
    if (!st) return false;
    if (st.backgroundImage && st.backgroundImage !== 'none') return true;
    var c = st.content;
    return !!(c && c !== 'none' && c !== 'normal' && c !== '""' && c !== "''");
  }

  function usable(el) {
    if (!el || el.nodeType !== 1 || el.disabled) return false;
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' && el.type === 'hidden') return false;
    var st;
    try { st = window.getComputedStyle(el); } catch (e) { return false; }
    if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) === 0) return false;
    var r = rect(el);
    if (r.width < 8 || r.height < 8) return false;
    // 只保留当前视口上下 3 屏以内的元素，避免长页面里一次跳跃到页脚
    var vh = window.innerHeight || 800;
    if (r.top < -vh * 3 || r.top > vh * 4) return false;
    // 小尺寸 + 没有任何可读文字 = 装饰占位（筛选区的空方块、图标壳）。
    // 图标字体字符在私用区，不算可读；有 title/aria-label 的图标按钮会保留。
    var label = String(el.innerText || el.getAttribute('aria-label') ||
      el.getAttribute('placeholder') || el.getAttribute('title') || '').trim();
    if (!/[0-9A-Za-z\u4e00-\u9fff]/.test(label) && r.width < 70 && r.height < 70) {
      return false;
    }
    // 站点常同时保留桌面 / 移动两套 DOM，重复的链接可能只是 display 不为 none
    // 但被别的层完全盖住。elementFromPoint 是「这个位置真的点得到它吗」的
    // 最可靠判据，能挡掉这类幽灵候选（焦点框套在看不见的元素上就是空框）。
    if (r.top >= 0 && r.top < vh && r.left >= 0 && r.left < (window.innerWidth || 1200)) {
      var hit = document.elementFromPoint(
        r.left + r.width / 2, r.top + r.height / 2
      );
      if (!hit || !(hit === el || el.contains(hit) || hit.contains(el))) return false;
    }
    return hasVisibleContent(el);
  }

  function candidates() {
    var list = [];
    var nodes;
    try { nodes = document.querySelectorAll(SELECTOR); } catch (e) { return list; }
    for (var i = 0; i < nodes.length; i++) {
      if (usable(nodes[i])) list.push(nodes[i]);
    }
    // 去掉「包着别的候选的大容器」，否则一次按键总落在最外层 div 上
    return list.filter(function (el) {
      var r1 = rect(el);
      var a1 = r1.width * r1.height;
      for (var j = 0; j < list.length; j++) {
        var o = list[j];
        if (o === el || !el.contains(o)) continue;
        var r2 = rect(o);
        if (r2.width * r2.height > a1 * 0.22) return false;
      }
      return true;
    });
  }

  // ---------- 几何最近邻 ----------

  /**
   * 站点里常有小尺寸装饰元素（筛选行的空方块、图标占位），它们也能点，
   * 但遥控器落上去用户看不出聚焦了什么，且会挡住去海报的路。
   * 有文字的元素、海报这类大图、原生控件算「主目标」；
   * 无文字的小图标只在同方向完全没有主目标时才作为备选。
   */
  function isPrimaryTarget(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'textarea' || tag === 'iframe' || el.isContentEditable === true) return true;
    if (tag === 'input') {
      // 文本类输入框永远优先；checkbox / radio / 空 button 造型的占位不算
      var it = String(el.type || 'text').toLowerCase();
      if (it === 'text' || it === 'search' || it === 'url' || it === 'tel' ||
          it === 'email' || it === 'password' || it === 'number') return true;
    }
    var t = String(el.innerText || el.getAttribute('aria-label') ||
      el.getAttribute('placeholder') || el.getAttribute('title') || '').trim();
    if (t.length > 0) return true;
    var r = rect(el);
    return r.width >= 80 || r.height >= 80;
  }

  function choose(el, dir, list) {
    var main = list.filter(isPrimaryTarget);
    // 页面里只要有主目标，就只在主目标之间走位；只有整页都是小图标时
    // 才退回全量候选，保证纯图标工具栏仍然可操作。
    return findBest(el, dir, main.length ? main : list);
  }

  function findBest(el, dir, list) {
    var cur = el ? rect(el) : {
      left: 0, top: 0, width: 0, height: 0,
      right: 0, bottom: 0
    };
    var cx = cur.left + cur.width / 2;
    var cy = cur.top + cur.height / 2;
    var best = null, bestScore = Infinity;
    var vh = window.innerHeight || 800;

    for (var i = 0; i < list.length; i++) {
      var cand = list[i];
      if (cand === el) continue;
      var r = rect(cand);
      var ex = r.left + r.width / 2;
      var ey = r.top + r.height / 2;
      var dx = ex - cx, dy = ey - cy;
      var primary = 0, secondary = 0, ok = false;

      if (dir === 'right') { primary = dx; secondary = Math.abs(dy); ok = dx > 6; }
      else if (dir === 'left') { primary = -dx; secondary = Math.abs(dy); ok = -dx > 6; }
      else if (dir === 'down') { primary = dy; secondary = Math.abs(dx); ok = dy > 6; }
      else { primary = -dy; secondary = Math.abs(dx); ok = -dy > 6; }

      if (!ok) continue;
      // 主轴方向占主导，避免往右按却跳到斜下方的整块区域
      var slack = (dir === 'left' || dir === 'right')
        ? cur.height * 0.65 + 48
        : cur.width * 0.65 + 48;
      if (secondary > primary * 1.35 + slack) continue;

      var score = primary + secondary * 2.1;
      // 同屏元素略优先，减少无谓滚动
      if (r.top >= 0 && r.bottom <= vh) score -= 24;
      if (score < bestScore) { bestScore = score; best = cand; }
    }
    return best;
  }

  // ---------- 焦点 ----------

  function emitHover(el) {
    var n = el, depth = 0;
    while (n && n.nodeType === 1 && depth < 5) {
      ['pointerover', 'pointerenter', 'mouseover', 'mouseenter'].forEach(function (type) {
        try {
          n.dispatchEvent(new MouseEvent(type, {
            bubbles: type === 'pointerover' || type === 'mouseover',
            cancelable: true, view: window
          }));
        } catch (e) { /* 合成事件可能不被接受，忽略 */ }
      });
      n = n.parentElement;
      depth++;
    }
  }

  function isEditable(el) {
    if (!el) return false;
    var tag = el.tagName.toLowerCase();
    return tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable === true;
  }

  function setFocus(el, scroll) {
    if (current && current.classList) {
      try { current.classList.remove(FOCUS_CLASS); } catch (e) {}
    }
    // 有新的焦点动作：把自动收起的状态清掉，重新计时
    current = el || null;
    currentId = ++focusSeq;
    if (!current) { updateBox(); return; }

    try { current.classList.add(FOCUS_CLASS); } catch (e) {}
    try { current.focus({ preventScroll: true }); } catch (e) { try { current.focus(); } catch (e2) {} }
    if (scroll !== false) {
      // 不能用 scrollIntoView({block:'center',behavior:'smooth'})：
      // 每次换焦点都把页面滚到居中，画面看起来原地不动，而且平滑滚动期间
      // getBoundingClientRect() 一直在变，焦点框会漂到文字旁边的空白处。
      // 只在元素快出视口时滚一点点，且即时滚动，滚完立刻重算框的位置。
      var r = rect(current);
      var vh = window.innerHeight || 800;
      var pad = Math.min(120, vh * 0.15);
      var delta = 0;
      if (r.top < pad) delta = r.top - pad;
      else if (r.bottom > vh - pad) delta = r.bottom - (vh - pad);
      if (delta !== 0) {
        try { window.scrollBy(0, delta); } catch (e) {}
      }
    }
    updateBox();
    emitHover(current);
  }

  function firstVisible() {
    var list = candidates();
    var inView = list.filter(function (el) {
      var r = rect(el);
      return r.top >= -4 && r.top < (window.innerHeight || 800) - 4;
    });
    return inView[0] || list[0] || null;
  }

  function move(dir) {
    if (!current || !current.isConnected) {
      setFocus(firstVisible(), false);
      return !!current;
    }
    // 这里不再因为「焦点在输入框 / select 上」就 return false：
    // 遥控器一旦落到搜索框或筛选下拉，方向键若被全部吃掉，焦点就永远出不来，
    // 画面上的高亮框也不动，用户会以为遥控器失灵。左右键由原生端单独
    // 交给 WebView 移动光标，上下键必须能继续走位。
    var list = candidates();
    var next = choose(current, dir, list);
    if (!next) return false;
    setFocus(next, true);
    return true;
  }

  /**
   * 真的需要弹键盘的元素：input / textarea / contentEditable，且当前可见。
   *
   * select 是用方向键选的、input[type=hidden] 是表单里的隐形字段，两者都不该
   * 被当成「要打字」。过去把它们一并算作 input，遥控器焦点落到这类元素上后
   * 按确定永远走「弹键盘」分支，页面上的链接和登录按钮就再也点不动了。
   */
  function typingTarget(el) {
    if (!el) return false;
    var tag = el.tagName.toLowerCase();
    if (tag === 'input') {
      var t = String(el.type || 'text').toLowerCase();
      if (t === 'hidden') return false;
    } else if (tag !== 'textarea' && el.isContentEditable !== true) {
      return false;
    }
    var r = null;
    try { r = el.getBoundingClientRect(); } catch (e) {}
    return !!(r && r.width > 1 && r.height > 1);
  }

  function info() {
    if (!current) return null;
    var tag = current.tagName.toLowerCase();
    var type = 'other';
    if (typingTarget(current)) type = 'input';
    else if (tag === 'iframe') type = 'iframe';
    var text = current.getAttribute && (current.getAttribute('aria-label') || current.getAttribute('placeholder') || '');
    if (!text) text = current.innerText || current.value || '';
    return {
      fid: currentId,
      type: type,
      tag: tag,
      text: String(text).replace(/\s+/g, ' ').trim().slice(0, 80),
      href: (current.getAttribute && current.getAttribute('href')) || ''
    };
  }

  /** 归一化中心点：CSS px → View px 的比例，避开 density / pin-zoom 换算 */
  function center() {
    if (!current) return null;
    var r = rect(current);
    var w = window.innerWidth || 1, h = window.innerHeight || 1;
    return {
      fx: (r.left + r.width / 2) / w,
      fy: (r.top + r.height / 2) / h,
      x: r.left + r.width / 2,
      y: r.top + r.height / 2
    };
  }

  /** 返回键优先关弹层：派发 Escape，很多站点都监听它 */
  function dismiss() {
    var sels = '[role="dialog"],[aria-modal="true"],.modal.show,.modal.in,' +
      '.dropdown-menu.show,.popup.show,.layer.show,[data-tv-overlay]';
    var nodes;
    try { nodes = document.querySelectorAll(sels); } catch (e) { return false; }
    for (var i = 0; i < nodes.length; i++) {
      var r = rect(nodes[i]);
      var st = window.getComputedStyle(nodes[i]);
      if (r.width < 2 || r.height < 2) continue;
      if (st.display === 'none' || st.visibility === 'hidden') continue;
      try {
        var ev = new KeyboardEvent('keydown', {
          key: 'Escape', code: 'Escape', keyCode: 27, which: 27,
          bubbles: true, cancelable: true
        });
        document.dispatchEvent(ev);
        nodes[i].dispatchEvent(ev);
      } catch (e) {}
      return true;
    }
    return false;
  }

  // ---------- 文本输入（不依赖系统输入法） ----------

  /**
   * 电视 / 盒子上系统软键盘经常唤不起来（应用内派发的触摸不经过 InputDispatcher，
   * WebView 不建立 Android 编辑态）。所以 App 侧提供遥控器键盘，这里负责把文字
   * 真正写进网页输入框，并派发 input/change 让 React / Vue 也能感知。
   */
  function setNativeValue(el, v) {
    if (el.isContentEditable) {
      el.textContent = v;
      try { el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' })); }
      catch (e) { el.dispatchEvent(new Event('input', { bubbles: true })); }
      return;
    }
    var desc = null, proto = Object.getPrototypeOf(el);
    while (proto && !desc) {
      desc = Object.getOwnPropertyDescriptor(proto, 'value');
      proto = Object.getPrototypeOf(proto);
    }
    if (desc && desc.set) desc.set.call(el, v);
    else el.value = v;
    try { el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' })); }
    catch (e) { el.dispatchEvent(new Event('input', { bubbles: true })); }
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function currentValue() {
    if (!current) return '';
    if (current.isContentEditable) return String(current.textContent || '');
    return String(current.value || '');
  }

  function editable() {
    return !!(current && isEditable(current) &&
      current.tagName.toLowerCase() !== 'select');
  }

  // ---------- 跨 frame 协调 ----------

  function postToFrame(win, msg) {
    try { win.postMessage(msg, '*'); } catch (e) {}
  }

  function forwardToActive(action, dir) {
    if (!activeFrame || !activeFrameEl || !activeFrameEl.isConnected) return false;
    postToFrame(activeFrame, { __tvnav: 1, action: action, dir: dir });
    return true;
  }

  function enterFrame(iframe) {
    activeFrameEl = iframe;
    try { activeFrame = iframe.contentWindow; } catch (e) { activeFrame = null; }
    postToFrame(activeFrame, { __tvnav: 1, action: 'activate' });
  }

  // ---------- 网页播放器全屏 ----------
  //
  // 遥控器按不到站点播放器上那颗很小的全屏按钮；而 requestFullscreen() 需要
  // 用户手势（user activation），我们是从原生端 evaluateJavascript 进来的，
  // 没有激活状态，调了只会被拒。所以这里绕开 Fullscreen API：把 <video>
  // 连同它的控制条一起搬进一个铺满视口的浮层，退出时原样搬回原位。
  //
  // 播放器在跨域 iframe 里时，主文档拿不到里面的 <video>，就改把那个 iframe
  // 元素本身撑满视口，再 postMessage 让它把自己的 video 铺满（脚本注入到了
  // 所有 frame，所以子 frame 里同样有这套逻辑）。

  var fsEl = null;      // 被搬走的元素（优先播放器容器，不是 <video> 本身）
  var fsPar = null;     // 原父节点
  var fsNext = null;    // 原下一个兄弟，用来插回原位
  var fsStyle = null;   // 原 style 属性
  var fsIsFrame = false; // 搬走的是 iframe（要连带通知里面退出）
  var fsOverlay = null;
  var fsHidden = null;  // 被临时压掉滚动的节点
  var fsVideos = null;  // 被改过内联样式的 <video>，退出时要还原
  var fsAutoDone = false; // 本页是否已经自动全屏过（用户退出后不再自动弹回）

  function pickVideo() {
    var list = document.querySelectorAll('video');
    var best = null, bestArea = 0;
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!v.isConnected) continue;
      var r = rect(v);
      if (r.width < 160 || r.height < 90) continue;
      var a = r.width * r.height;
      if (a > bestArea) { bestArea = a; best = v; }
    }
    return best;
  }

  // 从 video 往上找“播放器容器”：高度明显超出视频本身就停，
  // 否则容易把整块内容区甚至整个 body 搬走。
  function pickHost(video) {
    var host = video, node = video.parentElement;
    var vh = Math.max(1, rect(video).height);
    for (var i = 0; i < 4 && node; i++) {
      var tag = node.tagName.toLowerCase();
      if (tag === 'body' || tag === 'html') break;
      var r = rect(node);
      if (r.height > vh * 2.2 || r.width < 160) break;
      host = node;
      node = node.parentElement;
    }
    return host;
  }

  function pickFrame() {
    var list = document.querySelectorAll('iframe');
    var best = null, bestArea = 0;
    for (var i = 0; i < list.length; i++) {
      var f = list[i];
      var r = rect(f);
      if (r.width < 200 || r.height < 120) continue;
      var a = r.width * r.height;
      if (a > bestArea) { bestArea = a; best = f; }
    }
    return best;
  }

  function notifyFs(active) {
    try {
      if (window.TvHost && window.TvHost.onFullscreen) {
        window.TvHost.onFullscreen(!!active);
      }
    } catch (e) {}
  }

  function hidePageScroll() {
    // 页面还留着滚动条的话，方向键会把页面滚走
    var de = document.documentElement, bd = document.body;
    fsHidden = [[de, de.style.overflow], [bd, bd ? bd.style.overflow : '']];
    de.style.overflow = 'hidden';
    if (bd) bd.style.overflow = 'hidden';
  }

  function enterFullscreen() {
    if (fsEl) return true;

    var host = null, target = pickVideo();
    if (target) host = pickHost(target);

    // 播放器在跨域 iframe 里：主文档看不到里面的 <video>。
    // 注意不能把 iframe 搬进浮层——搬动 iframe 会让它重新加载，视频直接从头开始。
    // 原地改成 fixed 铺满视口就够了，里面由它自己的脚本把 video 撑满。
    if (!host) {
      var fr = pickFrame();
      if (!fr) return false;
      fsEl = fr;
      fsPar = null;
      fsIsFrame = true;
      fsStyle = fr.getAttribute('style');
      var fw = Math.max(1, window.innerWidth || 1920);
      var fh = Math.max(1, window.innerHeight || 1080);
      var fimp = [
        ['position', 'fixed'], ['left', '0'], ['top', '0'],
        ['width', fw + 'px'], ['height', fh + 'px'], ['max-width', 'none'],
        ['max-height', fh + 'px'], ['margin', '0'], ['transform', 'none'],
        ['z-index', '2147483646']
      ];
      for (var fi = 0; fi < fimp.length; fi++) {
        fr.style.setProperty(fimp[fi][0], fimp[fi][1], 'important');
      }
      hidePageScroll();
      postToFrame(fr.contentWindow, { __tvnav: 1, action: 'fullscreen' });
      notifyFs(true);
      return true;
    }

    fsEl = host;
    fsPar = host.parentNode;
    fsNext = host.nextSibling;
    fsStyle = host.getAttribute('style');

    if (!fsOverlay) {
      fsOverlay = document.createElement('div');
      fsOverlay.id = '__tv_fs_overlay';
      fsOverlay.setAttribute(
        'style',
        'position:fixed;left:0;top:0;margin:0;padding:0;' +
        'background:#000;z-index:2147483646;overflow:hidden;'
      );
      document.documentElement.appendChild(fsOverlay);
    }
    // 4K 电视（3840×2160）上 100vw/100vh 和百分比高度经常算成 0 ——
    // 旧 WebView 对超大视口的 vh 单位有 bug。浮层高度一塌，视频就只剩声音、
    // 连播放框都看不到。这里全部改成具体 px，并在 resize 时重算。
    var fsW = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1920);
    var fsH = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1080);
    fsOverlay.style.setProperty('width', fsW + 'px', 'important');
    fsOverlay.style.setProperty('height', fsH + 'px', 'important');
    if (!window.__tvFsResize) {
      window.__tvFsResize = function () {
        var w = Math.max(1, window.innerWidth || 1920);
        var h = Math.max(1, window.innerHeight || 1080);
        if (fsOverlay) {
          fsOverlay.style.setProperty('width', w + 'px', 'important');
          fsOverlay.style.setProperty('height', h + 'px', 'important');
        }
        if (fsEl && fsEl.style) {
          fsEl.style.setProperty('width', w + 'px', 'important');
          fsEl.style.setProperty('height', h + 'px', 'important');
        }
      };
      window.addEventListener('resize', window.__tvFsResize);
    }
    fsOverlay.appendChild(host);

    // 容器自带的固定尺寸 / 居中 / 变换都要压平，否则搬进来仍然只占原来那块
    var imp = [
      ['position', 'relative'], ['width', fsW + 'px'], ['height', fsH + 'px'],
      ['max-width', 'none'], ['max-height', fsH + 'px'], ['margin', '0'],
      ['padding', '0'], ['transform', 'none'], ['z-index', '1']
    ];
    for (var k = 0; k < imp.length; k++) {
      host.style.setProperty(imp[k][0], imp[k][1], 'important');
    }

    // 同一 frame 内的 <video> 一并铺满；跨域 iframe 由它自己的脚本处理
    var vs = document.querySelectorAll('video');
    fsVideos = [];
    for (var j = 0; j < vs.length; j++) {
      if (host !== vs[j] && !host.contains(vs[j])) continue;
      fsVideos.push([vs[j], vs[j].getAttribute('style')]);
      vs[j].style.setProperty('width', '100%', 'important');
      vs[j].style.setProperty('height', '100%', 'important');
      vs[j].style.setProperty('max-width', 'none', 'important');
      vs[j].style.setProperty('max-height', 'none', 'important');
      vs[j].style.setProperty('object-fit', 'contain', 'important');
    }

    hidePageScroll();
    hideDanmaku();
    scheduleDanmakuOff();
    notifyFs(true);
    // 兜底：全屏 1.6 秒后仍拿不到画面尺寸（videoWidth == 0），说明这块 WebView
    // 解不了这条视频轨（典型是只支持 H.264、片源却是 HEVC 的电视盒子），
    // 留在全屏只会是一块黑底 + 有声音。自动退回页面内播放器，
    // 让站点自己的提示 / 备用线路接管，用户至少还能看到播放框。
    setTimeout(function () {
      if (!fsEl) return;
      var v = pickVideo();
      if (v && v.videoWidth === 0) {
        exitFullscreen();
        notifyFs(false);
      }
    }, 1600);
    return true;
  }

  function exitFullscreen() {
    if (!fsEl) return false;
    if (fsIsFrame) {
      try { postToFrame(fsEl.contentWindow, { __tvnav: 1, action: 'exitfullscreen' }); }
      catch (e) {}
      fsIsFrame = false;
    }
    try {
      if (fsNext && fsNext.parentNode === fsPar) fsPar.insertBefore(fsEl, fsNext);
      else if (fsPar) fsPar.appendChild(fsEl);
    } catch (e) {}
    try {
      if (fsStyle === null) fsEl.removeAttribute('style');
      else fsEl.setAttribute('style', fsStyle);
    } catch (e) {}

    fsEl = null; fsPar = null; fsNext = null; fsStyle = null;
    // 视频上的内联尺寸必须撤掉：留着 width/height:100%!important，
    // 退回原位后它会撑满整块容器，看起来像「没退出全屏」。
    if (fsVideos) {
      for (var n = 0; n < fsVideos.length; n++) {
        try {
          var v = fsVideos[n][0], s = fsVideos[n][1];
          if (s === null) v.removeAttribute('style');
          else v.setAttribute('style', s);
        } catch (e) {}
      }
      fsVideos = null;
    }
    if (fsOverlay) {
      try { fsOverlay.parentNode.removeChild(fsOverlay); } catch (e) {}
      fsOverlay = null;
    }
    if (fsHidden) {
      for (var i = 0; i < fsHidden.length; i++) {
        try { if (fsHidden[i][0]) fsHidden[i][0].style.overflow = fsHidden[i][1]; } catch (e) {}
      }
      fsHidden = null;
    }
    notifyFs(false);
    return true;
  }

  // ---------- 默认关闭弹幕 ----------
  //
  // 站点播放器默认把弹幕铺在画面上。电视离人两三米、字号又小，弹幕基本只是噪点，
  // 所以默认关掉。只隐藏「铺在画面上」的那一层大覆盖层，控制条里那颗弹幕开关
  // 不动——否则用户想开回来就没入口了。
  function hideDanmaku() {
    var list;
    try {
      list = document.querySelectorAll(
        '[class*="danmaku"],[class*="danmu"],[id*="danmaku"],[id*="danmu"]'
      );
    } catch (e) { return; }
    var vw = window.innerWidth || 1280;
    var vh = window.innerHeight || 720;
    var vid = pickVideo();
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.__tvDanmakuOff) continue;
      // 绝对不能碰「播放器本体 / 视频容器」：不少站点把 danmu 类名挂在播放器
      // 外层容器上，一旦被 display:none，声音还在放，但画面和播放框整个消失。
      // 电视端「有声音、连播放框都没有」就是这条规则误伤的。
      if (el.tagName && el.tagName.toLowerCase() === 'video') continue;
      if (el.querySelector && el.querySelector('video')) continue;
      if (vid && (el === vid || el.contains(vid))) continue;
      var r = rect(el);
      // 面积阈值：只认画面上那一大块，按钮 / 标签之类的小元素放过
      if (r.width < vw * 0.35 || r.height < vh * 0.12) continue;
      el.style.setProperty('display', 'none', 'important');
      el.__tvDanmakuOff = true;
    }
  }

  // 弹幕层常常是播放器初始化之后才插进来的，分几个时间点再扫几遍
  function scheduleDanmakuOff() {
    var delays = [400, 1200, 3000];
    for (var i = 0; i < delays.length; i++) {
      setTimeout(hideDanmaku, delays[i]);
    }
  }

  // ---------- 暴露给 Android 的 API ----------

  // 视频一开始播放就自动铺满：电视上离屏幕两米远，站点播放器那几个小按钮
  // 根本按不到，这是「播放却没法全屏」最直接的解法。
  // 用户主动退出后不再自动弹回；换页时是新的文档、脚本重新注入，标志自然复位。
  document.addEventListener('play', function (e) {
    var t = e.target;
    if (!t || String(t.tagName || '').toLowerCase() !== 'video') return;
    hideDanmaku();
    scheduleDanmakuOff();

    // 网页里播不动就交给原生播放器。
    // 电视端的典型症状是「有声音没画面」：MSE 能解出音频、视频轨却渲染不出来，
    // 或者 Chromium 的 video SurfaceView 被窗口其它层盖住。这时 videoWidth 要么
    // 恒为 0，要么即使有值画面也是黑的，靠页面 CSS 修不了。
    // 粤漫之家（ymvid）这类站点直接走原生，保证一定有画面。
    var v = t;
    // 4K 电视上窄版站点的播放器经常落在首屏之外：声音先起来了，画面还在
    // 视口下方，看起来就是「有声音没画面」。播放一开始先把播放器滚进视口。
    try {
      var h0 = pickHost(v);
      var r0 = rect(h0 || v);
      var vh0 = window.innerHeight || 1080;
      if (r0.top < 0 || r0.bottom > vh0 || r0.height < 2) {
        (h0 || v).scrollIntoView({ block: 'center' });
      }
    } catch (err) {}

    setTimeout(function () {
      // 交给内置播放器：不再判断 videoWidth。电视上「有声音没画面」既可能是
      // 解码问题，也可能是 WebView 视频层的合成问题，页面这边无法可靠区分，
      // 所以统一走原生播放器（解码、渲染都由 ExoPlayer 负责）。
      tryHandoff();
    }, 1200);

    if (fsEl || fsAutoDone) return;
    fsAutoDone = true;
    setTimeout(function () { if (!fsEl) enterFullscreen(); }, 400);
  }, true);

  /**
   * 自动点一下播放器。
   *
   * 这类站（粤漫之家）是 Vue + xxtea：地址在运行时解密后交给 playM3u8，
   * 页面 HTML / 网络层都看不到。我们靠 hook playM3u8 拿地址，但重载页面后
   * 播放器停在封面上不会自己播，钩子就永远等不到调用——所以这里替用户点一下。
   */
  function pokePlay() {
    try {
      var v = document.querySelector('video');
      var target = v || document.querySelector('.art-video-player') || document.body;
      if (!target) return false;
      var r = rect(target);
      if (!r || r.width < 2 || r.height < 2) return false;
      var x = r.left + r.width / 2;
      var y = r.top + r.height / 2;
      var el = document.elementFromPoint(x, y) || target;
      var opts = { bubbles: true, cancelable: true, clientX: x, clientY: y, view: window };
      el.dispatchEvent(new MouseEvent('mousedown', opts));
      el.dispatchEvent(new MouseEvent('mouseup', opts));
      el.dispatchEvent(new MouseEvent('click', opts));
      if (v && v.paused) { try { v.play(); } catch (e) {} }
      return true;
    } catch (e) {
      return false;
    }
  }

  var api = {
    activate: function () {
      injectStyle();
      applyViewportScale();
      activeFrame = null; activeFrameEl = null;
      setFocus(firstVisible(), false);
      scheduleDanmakuOff();
      // 播放页（或页面里已经有 video）自动接管：遥控器点进来直接出画面，
      // 不用等用户去点网页里那个又小又难点的播放按钮。
      try {
        if (/\/(play|vod|video|detail)\//i.test(location.pathname) ||
            document.querySelector('video')) {
          startHandoffWatch();
        }
      } catch (e) {}
      return !!current;
    },
    move: function (dir) {
      // 焦点在 iframe 上时，方向键交给那个 frame 自己算
      if (current && current.tagName && current.tagName.toLowerCase() === 'iframe') {
        enterFrame(current);
        forwardToActive('move', dir);
        return true;
      }
      var moved = move(dir);
      if (!moved && activeFrame) forwardToActive('move', dir);
      return moved;
    },
    /** 自检用：当前页面能提取到的播放地址（空串 = 一条路径都没抓到） */
    mediaUrl: function () { return extractMediaUrl(); },
    /** 自检用：立刻尝试交给原生播放器 */
    handoff: function () { return tryHandoff(); },
    info: function () { return JSON.stringify(info()); },
    center: function () { return JSON.stringify(center()); },
    value: function () { return currentValue(); },
    typeText: function (t) {
      if (!editable()) return '0';
      try { setNativeValue(current, currentValue() + t); return currentValue(); }
      catch (e) { return '0'; }
    },
    backspace: function () {
      if (!editable()) return '0';
      var v = currentValue();
      if (!v.length) return v;
      try { setNativeValue(current, v.slice(0, -1)); return currentValue(); }
      catch (e) { return '0'; }
    },
    clearText: function () {
      if (!editable()) return '0';
      try { setNativeValue(current, ''); return ''; } catch (e) { return '0'; }
    },
    enter: function () {
      if (!current) return '0';
      try {
        // 1) Vue 2 的 .enter 修饰符读 keyCode，KeyboardEvent 构造器设不了，
        //    只能用 getter 补上；Vue 3 / 原生代码则读 key。两种都覆盖。
        ['keydown', 'keypress', 'keyup'].forEach(function (t) {
          var ev = new KeyboardEvent(t, {
            key: 'Enter', code: 'Enter', bubbles: true, cancelable: true
          });
          ['keyCode', 'which'].forEach(function (p) {
            try { Object.defineProperty(ev, p, { get: function () { return 13; } }); }
            catch (e) {}
          });
          current.dispatchEvent(ev);
        });

        // 2) 放大镜 / 提交按钮：很多站点是 <i @click>，根本不在 form 里
        var scope = current.parentElement, depth = 0;
        while (scope && depth < 4) {
          var btn = scope.querySelector(
            '.search-icon,[class*="search-icon"],[class*="search-btn"],' +
            'button[type="submit"],input[type="submit"],[aria-label*="搜索"]'
          );
          if (btn && btn !== current && !current.contains(btn) &&
              !/close|cancel|clear|delete|清空|取消/i.test(String(btn.className || ''))) {
            try { btn.click(); return '1'; } catch (e) {}
          }
          scope = scope.parentElement; depth++;
        }

        // 3) 最后才试 form。注意 onsubmit="return false" 的站点不能靠它，
        //    这也是粤漫之家的情况（Vue 接管了提交）。
        var form = current.closest ? current.closest('form') : null;
        if (form && !/return\s+false/.test(form.getAttribute('onsubmit') || '')) {
          if (form.requestSubmit) form.requestSubmit();
          else form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
        }
        return '1';
      } catch (e) { return '0'; }
    },
    dismiss: function () { return dismiss() ? '1' : '0'; },
    fullscreen: function () { return enterFullscreen() ? '1' : '0'; },
    exitFullscreen: function () { return exitFullscreen() ? '1' : '0'; },
    isFullscreen: function () { return fsEl ? '1' : '0'; },
    hasFocus: function () { return !!(current && current.isConnected); },
    clear: function () { setFocus(null, false); activeFrame = null; activeFrameEl = null; },
    isTop: function () { return IS_TOP; }
  };

  window.__tvFrame = api;
  if (IS_TOP) window.__tvNav = api;

  // 子 frame 接收主 frame 转发的按键
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || d.__tvnav !== 1) return;
    if (d.action === 'activate') { injectStyle(); setFocus(firstVisible(), false); }
    else if (d.action === 'move') { move(d.dir); }
    else if (d.action === 'fullscreen') { enterFullscreen(); }
    else if (d.action === 'exitfullscreen') { exitFullscreen(); }
    else if (d.action === 'click') {
      // 跨域 iframe 内无法从主 frame 派发精确坐标的真实触摸，退化为 DOM click。
      // 这类点击可能过不了站点的用户手势校验，属于已知限制，不伪装成成功。
      if (current) { try { current.click(); } catch (err) {} }
    }
  });

  // 焦点框跟随滚动 / 布局变化
  window.addEventListener('scroll', updateBox, true);
  window.addEventListener('resize', function () { injectStyle(); });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', injectStyle, { once: true });
  } else {
    injectStyle();
  }
})();
