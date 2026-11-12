/*
 * 通用网页内容提取器 v2
 *
 * 设计目标：不针对任何具体站点，靠结构启发式识别「内容区块」。
 *
 * 输出：
 * {
 *   title, poster, intro, hasVideo,
 *   sections: [ { title, kind: "poster"|"chip", items: [ {t,i,h} ] } ]
 * }
 *
 * v2 相对 v1 的修正（均来自真实站点回归）：
 *  1. 卡片标题取「最长且非集数」的文本，避免取到"全23集"这类角标
 *  2. 过滤形如 "aside.title" 的选择器式属性值
 *  3. 选集判定收紧：排除 19xx/20xx 年份，要求链接同一前缀（真选集同属一部剧）
 *  4. 丢弃筛选器/分页器区块（链接前缀分散、文本全是数字/年份）
 *  5. 区块标题清洗（去掉尾部"更多"）+ 噪音标题黑名单
 *  6. 高度重合的区块只保留一个
 *  7. 海报区块优先，文字块限量，避免导航条淹没内容
 */
(function () {
  var MAX_ITEMS = 60;
  var MAX_CHIP_ROWS = 2;

  var out = { title: '', poster: null, intro: '', hasVideo: false, next: null, sections: [] };

  // ---------- 基础工具 ----------

  function cleanText(s) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  function abs(u) {
    if (!u) return null;
    u = String(u).trim();
    if (!u || u.indexOf('javascript:') === 0 || u.indexOf('#') === 0) return null;
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }

  function imgOf(el) {
    var img = el.querySelector('img');
    var src = null;
    if (img) {
      src = img.getAttribute('data-original') ||
            img.getAttribute('data-src') ||
            img.getAttribute('data-lazy-src') ||
            img.getAttribute('data-echo') ||
            img.currentSrc || img.getAttribute('src');
      if ((!src || src.indexOf('data:') === 0) && img.getAttribute('srcset')) {
        src = img.getAttribute('srcset').split(',')[0].trim().split(/\s+/)[0];
      }
    }
    if (!src || src.indexOf('data:') === 0) {
      var bg = '';
      try { bg = getComputedStyle(el).backgroundImage || ''; } catch (e) {}
      var m = bg.match(/url\(["']?(.*?)["']?\)/);
      if (m) src = m[1];
    }
    return abs(src);
  }

  // 形如 "aside.title"、"div.card .name" 的 CSS 选择器，不是有效标题
  function looksLikeSelector(s) {
    s = cleanText(s);
    if (!s || s.length > 40) return false;
    if (/[\u4e00-\u9fa5]/.test(s)) return false;   // 含中文，不是选择器
    if (/^[a-zA-Z][\w-]*(\.[\w-]+)+$/.test(s.replace(/\s+/g, ''))) return true;
    if (/^[#.]/.test(s)) return true;
    return false;
  }

  var BADGE_RE = /^(全\s*\d+(\.\d+)?\s*[集話话]|更新到\s*\d+(\.\d+)?\s*[集話话]|完结|連載|连载|完结全\s*\d+\s*集|\d+\s*[集話话])$/;

  // 从一格里挑出最像"标题"的文本。
  // 优先级：title 属性 > 不含图片的链接文本 > 最长文本。
  // 原因：卡片常是「图片链接 + 标题链接」两段，图片链接的文本往往是
  // "粤语 更新到02集" 这类标签，按长度取会取错。
  function titleOfCell(cell) {
    var as = cell.querySelectorAll('a');
    var attrTitles = [];
    var plainTexts = [];
    var allTexts = [];

    for (var i = 0; i < as.length; i++) {
      var a = as[i];
      var t = cleanText(a.textContent);
      var at = cleanText(a.getAttribute('title'));
      var hasImg = !!a.querySelector('img');

      if (at && !looksLikeSelector(at) && !BADGE_RE.test(at)) attrTitles.push(at);
      if (t) {
        allTexts.push(t);
        if (!hasImg) plainTexts.push(t);
      }
    }
    if (!allTexts.length) {
      var whole = cleanText(cell.textContent);
      if (whole) allTexts.push(whole);
    }

    function pick(list) {
      var seen = {};
      var useful = [];
      for (var j = 0; j < list.length; j++) {
        var c = list[j];
        if (!c || BADGE_RE.test(c) || seen[c]) continue;
        seen[c] = 1;
        useful.push(c);
      }
      if (!useful.length) return '';
      useful.sort(function (x, y) { return y.length - x.length; });
      return useful[0];
    }

    var r = pick(attrTitles);
    if (r) return r;
    r = pick(plainTexts);
    if (r) return r;
    r = pick(allTexts);
    if (r) return r;

    for (var k = 0; k < allTexts.length; k++) {
      if (allTexts[k]) return allTexts[k];
    }
    return '';
  }

  function shown(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 24 || r.height < 24) return false;
    var s = getComputedStyle(el);
    return s.display !== 'none' && s.visibility !== 'hidden' && parseFloat(s.opacity || '1') > 0.05;
  }

  function sig(el) {
    var cls = '';
    if (el.className && typeof el.className === 'string') {
      cls = el.className.split(/\s+/).filter(Boolean).slice(0, 3).join('.');
    }
    return el.tagName + '|' + cls;
  }

  // 链接的"父路径"，用于判断一批链接是否属于同一部剧
  function pathPrefix(u) {
    try {
      var seg = new URL(u).pathname.split('/').filter(Boolean);
      return '/' + seg.slice(0, Math.max(1, seg.length - 1)).join('/');
    } catch (e) {
      return u;
    }
  }

  // ---------- 元信息 ----------

  out.title = cleanText(document.title);
  var ogt = document.querySelector('meta[property="og:title"]');
  if (ogt && ogt.content) out.title = cleanText(ogt.content);
  out.title = out.title.split(/\s*[-_|｜]\s*/)[0].trim() || '未命名页面';

  var ogi = document.querySelector('meta[property="og:image"]');
  if (ogi && ogi.content) out.poster = abs(ogi.content);

  var ogd = document.querySelector('meta[property="og:description"]') ||
            document.querySelector('meta[name="description"]');
  if (ogd && ogd.content) out.intro = cleanText(ogd.content).slice(0, 300);

  out.hasVideo = !!document.querySelector(
    'video, .art-video-player, #player, .dplayer, .jw-wrapper, iframe[src*="player"]'
  );

  // ---------- 翻页 ----------

  out.next = null;
  var NEXT_RE = /^(下一页|下页|下頁|next|›|»|>>|>|Next)$/i;

  var relNext = document.querySelector('link[rel="next"]');
  if (relNext && relNext.getAttribute('href')) {
    var rh = abs(relNext.getAttribute('href'));
    if (rh) out.next = { t: '下一页', h: rh };
  }
  if (!out.next) {
    var candidates = document.querySelectorAll('a[href],button');
    for (var ni = 0; ni < candidates.length; ni++) {
      var node = candidates[ni];
      var nt = cleanText(node.textContent);
      if (!NEXT_RE.test(nt)) continue;
      var nh = abs(node.getAttribute('href'));
      if (!nh) continue;
      var ncls = String(node.className || '') + ' ' + String(node.getAttribute('class') || '');
      if (/disabled|is-disabled|active/i.test(ncls)) continue;
      if (node.getAttribute('aria-disabled') === 'true') continue;
      out.next = { t: '下一页', h: nh };
      break;
    }
  }

  // ---------- 分组识别 ----------

  var groups = [];
  var all = document.body ? document.body.querySelectorAll('*') : [];
  var limit = Math.min(all.length, 6000);

  for (var i = 0; i < limit; i++) {
    var p = all[i];
    if (p.children.length < 3) continue;

    var bucket = {};
    for (var j = 0; j < p.children.length; j++) {
      var c = p.children[j];
      var k = sig(c);
      (bucket[k] = bucket[k] || []).push(c);
    }

    for (var k2 in bucket) {
      var arr = bucket[k2];
      if (arr.length < 3) continue;

      var items = [];
      var seenHref = {};
      for (var n = 0; n < arr.length && items.length < MAX_ITEMS; n++) {
        var cell = arr[n];
        if (!shown(cell)) continue;
        var a = cell.querySelector('a[href]') || (cell.tagName === 'A' ? cell : null);
        if (!a) continue;
        var h = abs(a.getAttribute('href'));
        if (!h || seenHref[h]) continue;
        var t = titleOfCell(cell);
        var img = imgOf(cell);
        if (!t && !img) continue;
        seenHref[h] = 1;
        items.push({ t: t, i: img, h: h });
      }
      if (items.length < 3) continue;

      var withImg = 0;
      for (var q = 0; q < items.length; q++) if (items[q].i) withImg++;
      var ratio = withImg / items.length;
      groups.push({
        el: p,
        items: items,
        score: items.length * (0.35 + ratio),
        ratio: ratio
      });
    }
  }

  groups.sort(function (a, b) { return b.score - a.score; });

  // ---------- 区块标题 ----------

  var NOISE_TITLES = ['搜索历史', '搜索记录', '热词', '热搜', '热门搜索', '筛选结果'];

  function cleanTitle(s) {
    s = cleanText(s);
    s = s.replace(/\s*(更多|查看更多|更多 »|更多>|MORE|More)\s*$/, '').trim();
    s = s.replace(/^[·•\-|>]+\s*/, '').trim();
    return s;
  }

  function headingFor(el) {
    var cur = el;
    for (var d = 0; d < 5 && cur; d++) {
      var prev = cur.previousElementSibling;
      var guard = 0;
      while (prev && guard++ < 3) {
        if (prev.querySelector && prev.querySelector('a[href]')) break;
        var t = cleanTitle(prev.textContent);
        if (t && t.length <= 24) return t;
        prev = prev.previousElementSibling;
      }
      var par = cur.parentElement;
      if (par) {
        var heads = par.querySelectorAll('h1,h2,h3,h4,h5,.title,.section-title,.head,.more-title');
        for (var x = 0; x < heads.length; x++) {
          var hh = heads[x];
          if (hh === el || (el.contains && el.contains(hh))) continue;
          var ht = cleanTitle(hh.textContent);
          if (ht && ht.length <= 24) return ht;
        }
      }
      cur = cur.parentElement;
      if (!cur || cur === document.body) break;
    }
    return '';
  }

  // ---------- 判据：是不是筛选器 / 分页器 ----------

  function isFilterOrPager(items) {
    var uniqPrefix = {};
    var numeric = 0;
    for (var i = 0; i < items.length; i++) {
      uniqPrefix[pathPrefix(items[i].h)] = 1;
      var t = cleanText(items[i].t);
      if (/^\d{1,5}$/.test(t) || /^(19|20)\d{2}$/.test(t)) numeric++;
    }
    var prefixes = Object.keys(uniqPrefix).length;
    var numericRatio = numeric / items.length;
    // 链接分散在不同父路径，且文本基本是数字/年份 → 筛选器或翻页器
    if (numericRatio >= 0.8 && prefixes >= Math.max(2, items.length * 0.5)) return true;
    // 全是 19xx/20xx 年份
    var years = 0;
    for (var j = 0; j < items.length; j++) {
      if (/^(19|20)\d{2}$/.test(cleanText(items[j].t))) years++;
    }
    if (years === items.length) return true;
    return false;
  }

  // ---------- 选集判定 ----------

  var EP_RE = /^(第\s*)?\d{1,4}\s*[集話话回期]|^EP?\s*\d{1,4}$|^\d{1,4}$/i;

  function isYear(t) { return /^(19|20)\d{2}$/.test(cleanText(t)); }

  function collectEpisodes() {
    var anchors = document.querySelectorAll('a[href]');
    var items = [];
    var seen = {};
    for (var i = 0; i < anchors.length && items.length < MAX_ITEMS; i++) {
      var a = anchors[i];
      var t = cleanText(a.textContent);
      if (!t || t.length > 14) continue;
      if (!EP_RE.test(t) || isYear(t)) continue;
      var h = abs(a.getAttribute('href'));
      if (!h || seen[h]) continue;
      seen[h] = 1;
      items.push({ t: t, i: null, h: h });
    }
    if (items.length < 2) return [];

    // 真选集：同属一部剧（父路径集中）
    var pre = {};
    for (var j = 0; j < items.length; j++) pre[pathPrefix(items[j].h)] = 1;
    var uniq = Object.keys(pre).length;
    if (uniq > Math.max(1, Math.floor(items.length * 0.3))) return [];
    return items;
  }

  // ---------- 组装 ----------

  // 与已选区块高度重合的，跳过
  function overlapRatio(items, pickedHrefs) {
    var hit = 0;
    for (var i = 0; i < items.length; i++) if (pickedHrefs[items[i].h]) hit++;
    return items.length ? hit / items.length : 0;
  }

  var pickedHrefs = {};
  var posterRows = [];
  var chipRows = [];

  for (var g = 0; g < groups.length; g++) {
    var grp = groups[g];
    if (isFilterOrPager(grp.items)) continue;
    if (overlapRatio(grp.items, pickedHrefs) > 0.6) continue;

    var title = headingFor(grp.el);
    if (NOISE_TITLES.indexOf(title) >= 0) continue;

    var kind = grp.ratio >= 0.5 ? 'poster' : 'chip';
    var row = { title: title, kind: kind, items: grp.items };

    if (kind === 'poster') {
      posterRows.push(row);
      for (var m = 0; m < grp.items.length; m++) pickedHrefs[grp.items[m].h] = 1;
    } else {
      chipRows.push(row);
      for (var m2 = 0; m2 < grp.items.length; m2++) pickedHrefs[grp.items[m2].h] = 1;
    }
    if (posterRows.length >= 5 && chipRows.length >= MAX_CHIP_ROWS) break;
  }

  // 选集单独处理：优先放在最前面（用户最常点）
  var eps = collectEpisodes();
  var epRow = null;
  if (eps.length >= 2) {
    // 若已有区块几乎就是这批选集，用它替代
    for (var ci = 0; ci < chipRows.length; ci++) {
      if (overlapRatio(eps, (function () {
        var d = {}; chipRows[ci].items.forEach(function (x) { d[x.h] = 1; }); return d;
      })()) > 0.8) { chipRows.splice(ci, 1); break; }
    }
    epRow = { title: '选集', kind: 'chip', items: eps };
  }

  if (epRow) out.sections.push(epRow);
  out.sections = out.sections.concat(posterRows);
  out.sections = out.sections.concat(chipRows.slice(0, MAX_CHIP_ROWS));

  // ---------- 兜底 ----------

  if (out.sections.length === 0) {
    var anchorsAll = document.querySelectorAll('a[href]');
    var fb = [];
    var fbSeen = {};
    for (var f = 0; f < anchorsAll.length && fb.length < 40; f++) {
      var af = anchorsAll[f];
      var hf = abs(af.getAttribute('href'));
      if (!hf || fbSeen[hf] || hf === location.href) continue;
      var tf = cleanText(af.getAttribute('title')) || cleanText(af.textContent);
      if (looksLikeSelector(tf)) tf = cleanText(af.textContent);
      if (!tf || tf.length < 2) continue;
      var imf = imgOf(af);
      if (!imf && tf.length < 6) continue;
      fbSeen[hf] = 1;
      fb.push({ t: tf, i: imf, h: hf });
    }
    if (fb.length) {
      var withImg2 = 0;
      for (var z = 0; z < fb.length; z++) if (fb[z].i) withImg2++;
      out.sections.push({
        title: '页面链接',
        kind: (withImg2 / fb.length) >= 0.5 ? 'poster' : 'chip',
        items: fb
      });
    }
  }

  return JSON.stringify(out);
})();
