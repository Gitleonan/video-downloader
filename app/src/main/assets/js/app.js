
(function () {
  'use strict';

  const API_BASE = 'https://downloader-api.bhwa233.com';
  const STORAGE_KEY = 'galaxy_download_history_v1';
  const PREFS_KEY = 'galaxy_prefs_v1';
  const STATS_KEY = 'galaxy_parse_count_v1';
  const THEME_KEY = 'galaxy_theme_v1';

  const PLATFORM_META = {
    douyin: { name: '抖音', color: '#FF3D67', short: '抖' },
    xiaohongshu: { name: '小红书', color: '#FF5A4D', short: '书' },
    bilibili: { name: '哔哩哔哩', color: '#38A9F0', short: 'B' },
    other: { name: '其他', color: '#868D9B', short: '·' }
  };

  const linkInput = $('link-input');
  const pasteCard = $('paste-card');
  const detected = $('detected');
  const detectedTag = $('detected-tag');
  const detectedUrl = $('detected-url');
  const overlay = $('overlay');
  const sheet = $('sheet');
  const parseLoading = $('parse-loading');
  const parseLoadingTxt = $('parse-loading-txt');
  const resultView = $('result-view');
  const toastEl = $('toast');
  const toastMsg = $('toast-msg');

  let currentResult = null;
  let toastTimer = null;

  /* ========== Theme ========== */
  /* The shell paints its status bar from the page's own palette, so the bar
     can never sit at a different colour than the app bar and the status icons
     stay legible in both themes. No-ops in a plain browser. */
  function syncSystemBars(isDark) {
    if (!(window.AppBridge && typeof AppBridge.syncSystemBars === 'function')) return;
    const bg = getComputedStyle(document.documentElement).getPropertyValue('--bg').trim();
    if (!bg) return;
    try { AppBridge.syncSystemBars(bg, !isDark); } catch (_) {}
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#101214' : '#F6F7F9');
    syncSystemBars(theme === 'dark');
    const sun = $('icon-sun');
    const moon = $('icon-moon');
    if (sun && moon) {
      sun.style.display = theme === 'dark' ? 'none' : 'block';
      moon.style.display = theme === 'dark' ? 'block' : 'none';
    }
    const sw = $('sw-theme');
    const label = $('theme-label');
    if (sw) sw.classList.toggle('on', theme !== 'dark');
    if (label) label.textContent = theme === 'dark' ? '当前：夜间' : '当前：日间';
  }
  function toggleTheme() {
    const cur = document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
    const next = cur === 'dark' ? 'light' : 'dark';
    localStorage.setItem(THEME_KEY, next);
    applyTheme(next);
    showToast(next === 'dark' ? '已切换到夜间模式' : '已切换到日间模式');
  }
  const savedTheme = localStorage.getItem(THEME_KEY) ||
    (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  applyTheme(savedTheme === 'dark' ? 'dark' : 'light');
  // The stylesheet isn't guaranteed to be applied on that first synchronous
  // pass, in which case --bg reads empty and nothing is reported; say it again
  // once everything has loaded.
  window.addEventListener('load', function () {
    syncSystemBars(document.documentElement.getAttribute('data-theme') === 'dark');
  });

  // The shell is the only side that knows which build is installed, and a stale
  // install looks exactly like "my change did nothing" from in here. Version
  // name alone doesn't distinguish builds, so show its version code too.
  (function () {
    if (!(window.AppBridge && typeof AppBridge.appVersion === 'function')) return;
    let v = '';
    try { v = AppBridge.appVersion(); } catch (_) {}
    if (!v) return;
    const el = document.querySelector('.about-card .ver');
    if (el) el.textContent = 'v' + v;
  })();
  $('btn-theme').addEventListener('click', toggleTheme);
  $('row-theme').addEventListener('click', toggleTheme);

  /* ========== Utils ========== */
  function showToast(msg, type) {
    toastMsg.textContent = msg;
    toastEl.classList.toggle('err', type === 'err');
    toastEl.querySelector('.t-ico').textContent = type === 'err' ? '!' : '✓';
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 2600);
  }

  function formatDuration(seconds) {
    if (seconds == null || isNaN(seconds)) return '';
    const s = Math.max(0, Math.floor(seconds));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (h > 0) return h + ':' + String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
    return m + ':' + String(sec).padStart(2, '0');
  }


  /* ========== Link extraction ========== */
  function extractUrl(text) {
    if (!text) return null;
    const urlRe = /(https?:\/\/[^\s<>"'）)】」』]+)/i;
    const m = text.match(urlRe);
    if (m) return m[1].replace(/[.,;:!?，。；：！？]+$/, '');
    const bareRe = /\b((?:v\.)?douyin\.com\/[^\s]+|www\.douyin\.com\/[^\s]+|xiaohongshu\.com\/[^\s]+|xhslink\.com\/[^\s]+|xhslink\.cn\/[^\s]+|b23\.tv\/[^\s]+|www\.bilibili\.com\/[^\s]+|bilibili\.com\/[^\s]+)/i;
    const b = text.match(bareRe);
    if (b) {
      const bare = b[1].replace(/[.,;:!?，。；：！？]+$/, '');
      return /^https?:\/\//i.test(bare) ? bare : 'https://' + bare;
    }
    return null;
  }

  function detectPlatform(url) {
    if (!url) return null;
    const u = url.toLowerCase();
    if (u.includes('douyin.com') || u.includes('iesdouyin.com')) return 'douyin';
    if (u.includes('xiaohongshu.com') || u.includes('xhslink.com') || u.includes('xhslink.cn')
      || u.includes('xhscdn.com')) return 'xiaohongshu';
    if (u.includes('bilibili.com') || u.includes('b23.tv') || u.includes('bili')) return 'bilibili';
    return 'other';
  }

  function updateDetectUI() {
    const raw = linkInput.value.trim();
    const url = extractUrl(raw);
    const plat = detectPlatform(url);

    pasteCard.classList.remove('detect-douyin', 'detect-xiaohongshu', 'detect-bilibili');
    if (url && plat && plat !== 'other') {
      pasteCard.classList.add('detect-' + plat);
      detected.classList.add('show');
      detected.setAttribute('data-p', plat);
      detectedTag.textContent = '已识别 · ' + PLATFORM_META[plat].name;
      detectedUrl.textContent = url;
    } else if (url) {
      detected.classList.add('show');
      detected.setAttribute('data-p', 'other');
      detectedTag.textContent = '已识别链接';
      detectedUrl.textContent = url;
    } else {
      detected.classList.remove('show');
    }
    return { url, plat };
  }

  linkInput.addEventListener('input', updateDetectUI);

  /* ========== Clipboard ========== */
  function nativeClipboardText() {
    // navigator.clipboard.readText is permission-denied inside Android
    // WebView on some ROMs (MIUI included); the native bridge is reliable.
    try {
      if (window.AppBridge && typeof AppBridge.readClipboard === 'function') {
        const t = AppBridge.readClipboard();
        if (t && t.trim()) return t.trim();
      }
    } catch (_) {}
    return null;
  }
  async function readClipboard() {
    let text = nativeClipboardText();
    if (!text) {
      try {
        if (navigator.clipboard && navigator.clipboard.readText) {
          text = await navigator.clipboard.readText();
        }
      } catch (e) { text = null; }
    }
    try {
      if (!text || !text.trim()) {
        showToast('剪贴板是空的，先去复制链接吧', 'err');
        return;
      }
      linkInput.value = text.trim();
      const { url } = updateDetectUI();
      if (url) {
        showToast('已读取剪贴板链接');
        setTimeout(() => startParse(), 220);
      } else {
        showToast('未在剪贴板找到有效链接', 'err');
      }
    } catch (e) {
      linkInput.focus();
      showToast('无法读取剪贴板，请手动粘贴', 'err');
    }
  }

  $('btn-paste').addEventListener('click', readClipboard);
  $('btn-read-clip').addEventListener('click', readClipboard);
  $('btn-clear-input').addEventListener('click', function () {
    linkInput.value = '';
    updateDetectUI();
    linkInput.focus();
  });

  /* ========== Sheet ========== */
  function openSheet() {
    overlay.classList.add('show');
    sheet.classList.add('show');
    document.body.style.overflow = 'hidden';
  }
  function closeSheet() {
    stopPreview();
    overlay.classList.remove('show');
    sheet.classList.remove('show');
    document.body.style.overflow = '';
  }
  overlay.addEventListener('click', closeSheet);
  $('btn-close-sheet').addEventListener('click', closeSheet);

  /* The sheet stays dismissible while a download runs: the shell's task bar
     keeps reporting after it slides away. Once a download has started, the
     notice stops warning about link expiry (moot by then) and instead says
     where the progress went. */
  function showDlHint() {
    const n = $('result-notice');
    if (!n) return;
    n.textContent = '已开始下载。可以关闭本窗口，进度见底部任务条。';
    n.classList.add('is-active');
  }

  /* ========== Video preview ========== */
  function stopPreview() {
    const wrap = $('preview-wrap');
    const vid = document.getElementById('result-video');
    if (vid) {
      try { vid.pause(); } catch (_) {}
      vid.remove();
    }
    if (wrap) wrap.classList.remove('playing');
    const hint = $('preview-hint');
    if (hint) hint.classList.add('show');
    const cover = $('result-cover');
    if (cover) cover.style.display = '';
    const overlayEl = $('play-overlay');
    if (overlayEl) overlayEl.style.display = '';
  }

  function getPreviewVideoUrl(data) {
    if (!data) return null;
    const candidates = [
      data.originDownloadVideoUrl,
      data.downloadVideoUrl,
      data.originDownloadAudioUrl,
      data.downloadAudioUrl
    ];
    for (let i = 0; i < candidates.length; i++) {
      const u = candidates[i];
      if (typeof u === 'string' && u.trim() && u.trim() !== '#') return u.trim();
    }
    return null;
  }

  function startPreview() {
    const data = currentResult;
    if (!data) return;

    const wrap = $('preview-wrap');
    if (wrap.classList.contains('playing')) return;

    // 图文笔记：没有视频流可播
    if (data.noteType === 'image') {
      showToast('这是图文笔记，长按封面可保存图片');
      return;
    }

    const src = getPreviewVideoUrl(data);
    if (!src) {
      showToast('没有可预览的视频地址', 'err');
      return;
    }

    const cover = $('result-cover');
    const overlayEl = $('play-overlay');
    const hint = $('preview-hint');
    if (cover) cover.style.display = 'none';
    if (overlayEl) overlayEl.style.display = 'none';
    if (hint) hint.classList.remove('show');
    wrap.classList.add('playing');

    const video = document.createElement('video');
    video.id = 'result-video';
    video.controls = true;
    video.playsInline = true;
    video.setAttribute('playsinline', '');
    video.setAttribute('webkit-playsinline', '');
    video.preload = 'metadata';
    video.poster = data.cover || '';
    video.src = src;

    video.addEventListener('error', function () {
      stopPreview();
      showToast('预览失败，可直接下载后观看', 'err');
    });
    video.addEventListener('ended', function () {
      // 播完保留画面，点击可重播
    });

    wrap.appendChild(video);
    const playPromise = video.play();
    if (playPromise && typeof playPromise.catch === 'function') {
      playPromise.catch(function () {
        // 自动播放被拦截时保留 controls，由用户手动点播放
      });
    }
  }

  (function bindPreview() {
    const wrap = $('preview-wrap');
    if (!wrap) return;
    wrap.addEventListener('click', function (e) {
      // 视频播放中时，点击交给原生控件
      if (wrap.classList.contains('playing') && e.target.tagName === 'VIDEO') return;
      if (wrap.classList.contains('playing')) {
        const vid = document.getElementById('result-video');
        if (vid && vid.paused) {
          vid.play().catch(function () {});
        }
        return;
      }
      startPreview();
    });
    wrap.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        if (!wrap.classList.contains('playing')) startPreview();
      }
    });
  })();

  /* ========== Parse ========== */
  async function startParse() {
    const { url, plat } = updateDetectUI();
    if (!url) {
      showToast('请先粘贴或读取视频链接', 'err');
      linkInput.focus();
      return;
    }

    parseLoading.classList.add('show');
    parseLoadingTxt.textContent = '正在解析 ' + (PLATFORM_META[plat] ? PLATFORM_META[plat].name : '媒体') + ' 链接…';
    resultView.style.display = 'none';
    openSheet();

    try {
      const res = await fetch(API_BASE + '/api/parse?url=' + encodeURIComponent(url), {
        method: 'GET',
        cache: 'no-store'
      });
      const payload = await res.json().catch(() => null);

      if (!res.ok || !payload || !payload.success || !payload.data) {
        const errMsg = (payload && (payload.error || payload.message))
          || (payload && payload.code ? '解析失败（' + payload.code + '）' : null)
          || '解析失败，请检查链接';
        throw new Error(errMsg);
      }

      const data = payload.data;
      let noteImgs = null;
      if (isImageNote(data)) {
        // 已拍板的数据源策略:默认抓笔记页拿无水印原图 + 实况数据,
        // 抓取失败/超时/无桥 → 静默降级为 API 的 webp(仍可下载,无实况)。
        parseLoadingTxt.textContent = '正在获取无水印原图…';
        try { noteImgs = await enrichImageNote(url); } catch (_) { noteImgs = null; }
      }
      renderResult(data, noteImgs);
      bumpStats();
      addHistory(data, url, linkInput.value.trim());
    } catch (err) {
      console.error(err);
      // 解析 API 故障时,小红书图文还有一条不依赖 API 的路:原生抓笔记页
      // 直出(标题/图片/实况全在页面状态里)。视频笔记接不了这条路,只能
      // 等 API 恢复。解析失败绝不展示演示假数据 —— 上游故障被包装成
      // 「示例视频」会让用户以为应用坏了(真机踩过的坑)。
      if (plat === 'xiaohongshu'
          && window.AppBridge && typeof AppBridge.fetchNoteHtml === 'function') {
        parseLoadingTxt.textContent = '解析服务异常，尝试直接读取笔记页…';
        const direct = await fetchNoteHtmlText(url)
          .then(function (html) {
            return xhsDirectFromHtml(html, url) || { _noData: true };
          })
          .catch(function (e) { return { _fetchError: e }; });
        if (direct && direct.data) {
          renderResult(direct.data, direct.noteImgs);
          bumpStats();
          addHistory(direct.data, url, linkInput.value.trim());
          showToast('解析服务暂不可用，已直接从笔记页获取');
          return;
        }
        // 直取失败的原因比 API 错误码更有行动价值,优先展示
        if (direct && direct._fetchError) {
          parseLoading.classList.remove('show');
          closeSheet();
          showToast('抓取笔记页失败：'
            + ((direct._fetchError && direct._fetchError.message) || '未知原因'), 'err');
          return;
        }
        if (direct && direct._noData) {
          parseLoading.classList.remove('show');
          closeSheet();
          showToast('笔记页未返回图文数据（可能触发风控），请稍后重试', 'err');
          return;
        }
      }
      parseLoading.classList.remove('show');
      closeSheet();
      const msg = (err && err.message) || '';
      showToast(/failed to fetch/i.test(msg)
        ? '无法连接解析服务，请稍后重试'
        : (msg || '解析失败，请稍后重试'), 'err');
    }
  }

  function gradientCover(color) {
    // Placeholder cover for items with no thumbnail. The end stop follows the
    // active theme so the fade lands on the surface behind it in both modes.
    const end = document.documentElement.getAttribute('data-theme') === 'dark'
      ? '#17191D'
      : '#EAEDF1';
    const svg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360">' +
      '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
      '<stop offset="0%" stop-color="' + color + '"/><stop offset="100%" stop-color="' + end + '"/>' +
      '</linearGradient></defs>' +
      '<rect width="640" height="360" fill="url(#g)"/>' +
      '<circle cx="320" cy="180" r="56" fill="rgba(255,255,255,0.22)"/>' +
      '<path d="M304 152 L368 180 L304 208 Z" fill="white"/></svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }

  /* ========== 小红书图文笔记 ========== */
  /* 图文判定只能用 kind / noteType:API 在图文笔记上 data.type === 'video'
     也成立(实测 16 张全实况的笔记 type 仍是 video),不可信。 */
  function isImageNote(data) {
    if (!data) return false;
    const kindOk = data.kind === 'image' || data.noteType === 'image';
    return kindOk && Array.isArray(data.images) && data.images.length > 0;
  }

  /* images 元素两种形态都要吃:OpenAPI 声明 string[],实测是
     {index,url,downloadUrl} 对象数组(参考前端 types.ts 印证)。归一化成
     {src: 显示用, dl: 下载用}。 */
  function normalizeImages(images) {
    return (images || []).map(function (v) {
      if (typeof v === 'string') return { src: v, dl: v }
      if (v && typeof v === 'object') {
        const src = (typeof v.url === 'string' && v.url) ? v.url : '';
        const dl = (typeof v.downloadUrl === 'string' && v.downloadUrl) ? v.downloadUrl : src;
        return { src: src, dl: dl };
      }
      return null;
    }).filter(Boolean).filter(function (x) { return !!x.src; });
  }

  /* __INITIAL_STATE__ 是 JS 对象字面量而非纯 JSON:实测含裸 undefined
     (如 "jsAssetsList":undefined),JSON.parse 会直接抛异常 —— 真机实测
     这是实况数据整份丢失的根因(表现:图文正常、实况永远拿不到)。先做
     字符串感知消毒:只把字符串字面量之外的 undefined / NaN / ±Infinity
     换成 null,再 JSON.parse。字符串内的这些词不动。 */
  function jsonishParse(literal) {
    let out = '';
    let inStr = false, esc = false, quote = '';
    const idents = /[A-Za-z0-9_$]/;
    for (let i = 0; i < literal.length; i++) {
      const ch = literal[i];
      if (inStr) {
        out += ch;
        if (esc) esc = false;
        else if (ch === '\\') esc = true;
        else if (ch === quote) inStr = false;
        continue;
      }
      if (ch === '"' || ch === "'") { inStr = true; quote = ch; out += ch; continue; }
      let hit = null;
      if (literal.startsWith('undefined', i)) hit = 'undefined';
      else if (literal.startsWith('-Infinity', i)) hit = '-Infinity';
      else if (literal.startsWith('Infinity', i)) hit = 'Infinity';
      else if (literal.startsWith('NaN', i)) hit = 'NaN';
      if (hit) {
        const nxt = i + hit.length;
        // 长标识符的一部分(如 undefinedFoo)不是裸字面量,原样放行
        if (nxt < literal.length && idents.test(literal[nxt])) { out += ch; continue; }
        out += 'null';
        i += hit.length - 1;
        continue;
      }
      out += ch;
    }
    try { return JSON.parse(out); } catch (_) { return null; }
  }

  /* 从笔记页 HTML 提取 __INITIAL_STATE__。它是 `window.__INITIAL_STATE__ =
     {…}` 形态的对象字面量,字符串里可能含花括号,所以从第一个 { 起做
     字符串感知的配平扫描,取到平衡为止再 JSON.parse。 */
  function extractInitialState(html) {
    const keyAt = html.indexOf('__INITIAL_STATE__');
    if (keyAt < 0) return null;
    const openAt = html.indexOf('{', keyAt);
    if (openAt < 0) return null;
    let depth = 0, inStr = false, esc = false, quote = '';
    for (let i = openAt; i < html.length; i++) {
      const ch = html[i];
      if (inStr) {
        if (esc) { esc = false; continue; }
        if (ch === '\\') { esc = true; continue; }
        if (ch === quote) inStr = false;
        continue;
      }
      if (ch === '"' || ch === "'") { inStr = true; quote = ch; continue; }
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) {
          return jsonishParse(html.slice(openAt, i + 1));
        }
      }
    }
    return null;
  }

  /* 归一化 __INITIAL_STATE__ 的 imageList → [{still, liveVideo, isLive}]。
     现行移动端形态:noteData.data.noteData.imageList;旧版:
     note.noteDetailMap[<noteId>].note.imageList,两路都试。实况 mp4 取
     stream.h264[0](h265/h266/av1 实测恒为空),masterUrl 带签名会过期,
     backupUrls[0] 无签名长期有效,master 优先、backup 兜底。 */
  function parseNoteState(html, noteUrl) {
    const state = extractInitialState(html);
    if (!state) return null;
    let imageList = null;
    try {
      imageList = state.noteData && state.noteData.data
        && state.noteData.data.noteData
        && state.noteData.data.noteData.imageList;
    } catch (_) { imageList = null; }
    if (!Array.isArray(imageList) || !imageList.length) {
      // 旧版形态:note.noteDetailMap[<noteId>].note.imageList
      try {
        const m = String(noteUrl || '').match(/\/(?:explore|discovery\/item)\/([0-9a-f]+)/i);
        const map = state.note && state.note.noteDetailMap;
        const entry = map && (map[m ? m[1] : '']
          || (map[Object.keys(map)[0]] || null));
        imageList = entry && entry.note && entry.note.imageList;
      } catch (_) { imageList = null; }
    }
    if (!Array.isArray(imageList) || !imageList.length) return null;
    return imageList.map(function (it) {
      it = it || {};
      const stream = it.stream || {};
      const h264 = Array.isArray(stream.h264) ? stream.h264 : [];
      const v0 = h264[0] || {};
      const master = (typeof v0.masterUrl === 'string' && v0.masterUrl) || '';
      const backup = (Array.isArray(v0.backupUrls) && typeof v0.backupUrls[0] === 'string'
        && v0.backupUrls[0]) || '';
      return {
        still: typeof it.url === 'string' ? it.url : '',
        liveVideo: master || backup,
        liveVideoBackup: master ? backup : '',
        isLive: !!it.livePhoto
      };
    });
  }

  /* 原生抓笔记页(有桥时)。回调 __nativeNote({ok, html}) 只到一次;
     无桥/失败/超时 reject,由调用方决定怎么降级。 */
  function fetchNoteHtmlText(noteUrl) {
    return new Promise(function (resolve, reject) {
      if (!(window.AppBridge && typeof AppBridge.fetchNoteHtml === 'function')) {
        reject(new Error('no bridge'));
        return;
      }
      let settled = false;
      const finish = function (err, html) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        try { delete window.__nativeNote; } catch (_) { window.__nativeNote = null; }
        if (err) reject(err); else resolve(html);
      };
      window.__nativeNote = function (payload) {
        if (!payload || !payload.ok || typeof payload.html !== 'string') {
          finish(new Error((payload && payload.error) || 'fetch failed'));
          return;
        }
        finish(null, payload.html);
      };
      const timer = setTimeout(function () { finish(new Error('timeout')); }, 12000);
      try { AppBridge.fetchNoteHtml(noteUrl); }
      catch (e) { finish(e); }
    });
  }

  /* API 正常返回时的图文增强:拿不到就 resolve(null),静默走 API 降级。 */
  function enrichImageNote(noteUrl) {
    return fetchNoteHtmlText(noteUrl).then(function (html) {
      try { return parseNoteState(html, noteUrl); } catch (_) { return null; }
    }).catch(function () { return null; });
  }

  /* API 故障时的小红书图文直取:标题/图片/实况全部来自笔记页状态本身,
     不依赖解析 API。视频笔记的状态结构未接,返回 null 走报错路径。 */
  function xhsDirectFromHtml(html, sourceUrl) {
    const noteImgs = parseNoteState(html, sourceUrl);
    if (!noteImgs || !noteImgs.length) return null;
    const state = extractInitialState(html);
    let title = '', desc = '';
    try {
      const nd = state && state.noteData && state.noteData.data
        && state.noteData.data.noteData;
      if (nd) { title = nd.title || ''; desc = nd.desc || ''; }
    } catch (_) {}
    if (!title) {
      // 旧版形态:note.noteDetailMap[<noteId>].note
      try {
        const m = String(sourceUrl || '').match(/\/(?:explore|discovery\/item)\/([0-9a-f]+)/i);
        const map = state && state.note && state.note.noteDetailMap;
        const note = map && (map[m ? m[1] : ''] || map[Object.keys(map)[0]] || null);
        if (note && note.note) { title = note.note.title || ''; desc = note.note.desc || ''; }
      } catch (_) {}
    }
    const data = {
      title: title || desc || '小红书图文',
      desc: desc,
      cover: noteImgs[0].still,
      platform: 'xiaohongshu',
      url: sourceUrl,
      noteType: 'image',
      kind: 'image',
      images: noteImgs.map(function (n, i) {
        return { index: i, url: n.still, downloadUrl: n.still };
      }),
      _direct: true
    };
    return { data: data, noteImgs: noteImgs };
  }

  /* 合成最终 items:笔记页数据优先(无水印原图 + 实况),API images 兜底
     (webp、无实况)。有笔记页数据时以它为准,API 只补显示缩略图。 */
  function buildImageItems(apiImgs, noteImgs) {
    if (noteImgs && noteImgs.length) {
      return noteImgs.map(function (n, i) {
        const api = apiImgs[i];
        return {
          display: (api && api.src) || n.still,
          still: n.still,
          live: n.isLive,
          liveVideo: n.liveVideo,
          liveVideoBackup: n.liveVideoBackup || '',
          fromNote: true
        };
      });
    }
    return (apiImgs || []).map(function (a) {
      return { display: a.src, still: a.dl || a.src, live: false, liveVideo: '', liveVideoBackup: '', fromNote: false };
    });
  }

  /* 渲染图文网格:序号角标 + 实况徽标 + 逐张下载按钮。 */
  function renderImageGrid(data) {
    const items = data._imageItems || [];
    const liveCount = items.filter(function (x) { return x.live && x.liveVideo; }).length;
    $('ig-count').textContent = '共 ' + items.length + ' 张'
      + (liveCount ? ' · ' + liveCount + ' 张实况' : '');
    const cells = $('ig-cells');
    cells.innerHTML = items.map(function (it, i) {
      return '<div class="ig-cell">'
        + '<img src="' + escapeAttr(it.display) + '" alt="" loading="lazy" referrerpolicy="no-referrer"/>'
        + '<span class="ig-idx">' + (i + 1) + '</span>'
        + (it.live && it.liveVideo ? '<span class="ig-live">实况</span>' : '')
        + '<button class="ig-dl" data-ig="' + i + '" aria-label="下载第' + (i + 1) + '张">'
        + '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M5 20h14"/></svg>'
        + '</button>'
        + '</div>';
    }).join('');
    cells.querySelectorAll('.ig-dl').forEach(function (btn) {
      btn.addEventListener('click', function () {
        App.doDownloadImage(parseInt(btn.getAttribute('data-ig'), 10));
      });
    });
    $('btn-dl-all').onclick = function () { App.doDownloadAllImages(); };
    const fromNote = items.length > 0 && items[0].fromNote;
    $('ig-note').textContent = (fromNote
      ? '已通过笔记页获取无水印原图。'
      : '笔记页不可用,已降级为预览图质量。')
      + (liveCount ? '实况图将合成动态照片(.jpg),在支持的相册里按住即可播放。' : '');
  }

  /* ========== Render result ========== */
  function renderResult(data, noteImgs) {
    currentResult = data;
    stopPreview();
    parseLoading.classList.remove('show');
    resultView.style.display = 'block';

    const cover = data.cover || gradientCover((PLATFORM_META[detectPlatform(data.url)] || PLATFORM_META.other).color);
    const coverImg = $('result-cover');
    coverImg.src = cover;
    coverImg.alt = data.title || '封面';

    $('result-title').textContent = data.title || data.desc || '未命名视频';

    const platKey = detectPlatform(data.url || '') ||
      (data.platform === 'douyin' ? 'douyin' :
       data.platform === 'xiaohongshu' || data.platform === 'xhs' ? 'xiaohongshu' :
       (data.platform === 'bili' || data.platform === 'bilibili') ? 'bilibili' : 'other');
    const meta = PLATFORM_META[platKey] || PLATFORM_META.other;

    const metaEl = $('result-meta');
    metaEl.innerHTML = '';

    function pill(html) {
      const s = document.createElement('span');
      s.className = 'meta-pill';
      s.innerHTML = html;
      metaEl.appendChild(s);
    }

    pill('<span style="width:7px;height:7px;border-radius:50%;background:' + meta.color + ';display:inline-block"></span>' + meta.name);
    if (data.duration != null) {
      pill('<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8"/><path d="M12 8v4.5l2.5 1.5"/></svg>' + formatDuration(data.duration));
    }
    if (data.isMultiPart && data.pages && data.pages.length > 1) {
      pill('<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M8 12h8"/></svg>' + data.pages.length + ' 个分P');
    }
    if (data.noteType === 'image' && Array.isArray(data.images)) {
      pill('<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="9" cy="11" r="1.5"/><path d="M4 17l5-5 4 4 3-2 4 3"/></svg>' + data.images.length + ' 张图');
    }
    if (data.videoAudioMode === 'separate') {
      pill('音视频分离');
    }

    const hasVideo = App.hasSrc(data.downloadVideoUrl) || App.hasSrc(data.originDownloadVideoUrl);
    const hasAudio = App.hasSrc(data.downloadAudioUrl) || App.hasSrc(data.originDownloadAudioUrl);
    const row = $('dl-row');
    const grid = $('image-grid');
    const previewWrap = $('preview-wrap');

    if (isImageNote(data)) {
      // 图文笔记:视频预览与视频/音频按钮整块隐藏,网格负责一切动作
      data._imageItems = buildImageItems(normalizeImages(data.images), noteImgs);
      renderImageGrid(data);
      grid.style.display = 'block';
      previewWrap.style.display = 'none';
      row.style.display = 'none';
      const notice = $('result-notice');
      notice.classList.remove('is-active');
      notice.textContent = '点格子右下角的按钮逐张保存,或点「全部下载」。'
        + (data._imageItems.some(function (x) { return x.live && x.liveVideo; })
          ? '实况图在支持的相册里按住可播放。' : '');
      return;
    }

    // 非图文:恢复视频分支 UI(上一次结果可能是图文)
    grid.style.display = 'none';
    previewWrap.style.display = '';
    row.style.display = '';

    if (!hasVideo && !hasAudio) {
      row.innerHTML = '<button class="dl-btn video full" id="btn-dl-video"><span class="label">复制原始链接</span></button>';
      document.getElementById('btn-dl-video').addEventListener('click', function () {
        copyText(data.url || linkInput.value);
        showToast('已复制原始链接');
      });
    } else {
      if (!document.getElementById('btn-dl-video')) {
        row.innerHTML =
          '<button class="dl-btn video" id="btn-dl-video"><div class="prog" id="prog-video"></div>' +
          '<span class="label"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M5 20h14"/></svg>下载视频</span></button>' +
          '<button class="dl-btn audio" id="btn-dl-audio"><div class="prog" id="prog-audio"></div>' +
          '<span class="label"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V7l10-2v11"/><circle cx="7" cy="18" r="2.2"/><circle cx="17" cy="16" r="2.2"/></svg>提取音频</span></button>';
        bindDownloadButtons();
      }
      const btnV = $('btn-dl-video');
      const btnA = $('btn-dl-audio');
      if (btnV) btnV.style.display = hasVideo ? '' : 'none';
      if (btnA) btnA.style.display = hasAudio ? '' : 'none';
    }

    const notice = $('result-notice');
    notice.classList.remove('is-active');
    notice.textContent = '点击下载后将调用系统下载器保存到手机。部分平台链接有时效，请尽快下载。';
  }


  function markDownloaded(id, kind) {
    if (!id) return;
    const list = loadHistory();
    const item = list.find(function (h) { return h.id === id; });
    if (item) {
      item.downloaded = true;
      item.downloadedKind = kind;
      saveHistory(list);
      renderHistory();
    }
  }

  /* ========== IndexedDB ========== */
  function openIDB() {
    return new Promise(function (resolve, reject) {
      const req = indexedDB.open('galaxy-downloader', 1);
      req.onupgradeneeded = function () {
        if (!req.result.objectStoreNames.contains('files')) {
          req.result.createObjectStore('files', { keyPath: 'id' });
        }
      };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error); };
    });
  }

  function saveFileToIDB(id, blob, filename) {
    openIDB().then(function (db) {
      const tx = db.transaction('files', 'readwrite');
      tx.objectStore('files').put({ id: id, blob: blob, filename: filename, ts: Date.now() });
      tx.oncomplete = function () {
        // 更新历史项的 localFile 字段
        const list = loadHistory();
        const item = list.find(function (h) { return h.id === id; });
        if (item) {
          const url = URL.createObjectURL(blob);
          item.localFile = url;
          saveHistory(list);
          renderHistory();
        }
      };
    }).catch(function () {});
  }

  function getFileFromIDB(id) {
    return openIDB().then(function (db) {
      return new Promise(function (resolve, reject) {
        const tx = db.transaction('files', 'readonly');
        const req = tx.objectStore('files').get(id);
        req.onsuccess = function () { resolve(req.result || null); };
        req.onerror = function () { reject(req.error); };
      });
    });
  }

  function deleteFileFromIDB(id) {
    openIDB().then(function (db) {
      const tx = db.transaction('files', 'readwrite');
      tx.objectStore('files').delete(id);
    }).catch(function () {});
  }

  function bindDownloadButtons() {
    const bv = document.getElementById('btn-dl-video');
    const ba = document.getElementById('btn-dl-audio');
    if (bv) bv.addEventListener('click', function () { App.doDownload('video'); });
    if (ba) ba.addEventListener('click', function () { App.doDownload('audio'); });
  }
  bindDownloadButtons();

  /* ========== Ripple Effect ========== */
  function addRipple(el, e) {
    const rect = el.getBoundingClientRect();
    const ripple = document.createElement('span');
    ripple.className = 'ripple';
    const size = Math.max(rect.width, rect.height);
    ripple.style.width = ripple.style.height = size + 'px';
    const x = (e.clientX || rect.left + rect.width / 2) - rect.left - size / 2;
    const y = (e.clientY || rect.top + rect.height / 2) - rect.top - size / 2;
    ripple.style.left = x + 'px';
    ripple.style.top = y + 'px';
    el.appendChild(ripple);
    setTimeout(function () { ripple.remove(); }, 600);
  }

  document.addEventListener('click', function (e) {
    const el = e.target.closest('.send-btn, .tool-btn, .hist-act, .chip');
    if (!el) return;
    if (getComputedStyle(el).position === 'static') {
      el.style.position = 'relative';
      el.style.overflow = 'hidden';
    }
    addRipple(el, e);
  });

  /* ========== Sheet Drag to Close ========== */
  (function () {
    const sheet = document.getElementById('sheet');
    if (!sheet) return;
    let startY = 0, currentY = 0, dragging = false;

    sheet.addEventListener('touchstart', function (e) {
      if (e.target.closest('.sheet-body') && sheet.querySelector('.sheet-body').scrollTop > 0) return;
      startY = e.touches[0].clientY;
      dragging = true;
      sheet.style.transition = 'none';
    }, { passive: true });

    sheet.addEventListener('touchmove', function (e) {
      if (!dragging) return;
      currentY = e.touches[0].clientY - startY;
      if (currentY > 0) {
        sheet.style.transform = 'translate(-50%, ' + currentY + 'px)';
      }
    }, { passive: true });

    sheet.addEventListener('touchend', function () {
      if (!dragging) return;
      dragging = false;
      sheet.style.transition = '';
      if (currentY > 100) {
        closeSheet();
      } else {
        sheet.style.transform = '';
      }
      currentY = 0;
    });
  })();

  /* ========== Staggered List Animation ========== */
  function applyStaggeredAnimation(container, selector) {
    if (!container) return;
    container.querySelectorAll(selector).forEach(function (el, i) {
      el.style.animationDelay = (i * 40) + 'ms';
      el.style.animationFillMode = 'both';
    });
  }

  $('btn-parse').addEventListener('click', startParse);

  linkInput.addEventListener('keydown', function (e) {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      startParse();
    }
  });

  /* ========== History ========== */
  function loadHistory() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'); } catch { return []; }
  }
  function saveHistory(list) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(0, 50)));
  }
  let currentHistoryId = null;
  function addHistory(data, sourceUrl, rawText) {
    const list = loadHistory();
    const id = Date.now();
    currentHistoryId = id;
    // 图文笔记不往 videoUrl/audioUrl 塞空值,原图/实况地址单独存
    const imgItems = isImageNote(data) ? (data._imageItems || []) : null;
    list.unshift({
      id: id,
      title: data.title || data.desc || '未命名',
      cover: data.cover || null,
      platform: data.platform || detectPlatform(sourceUrl) || 'other',
      url: sourceUrl,
      rawText: rawText || sourceUrl || '',
      duration: data.duration != null ? data.duration : null,
      ts: Date.now(),
      videoUrl: imgItems ? null : App.pickDownloadUrl(data, 'video'),
      audioUrl: imgItems ? null : App.pickDownloadUrl(data, 'audio'),
      imageUrls: imgItems ? imgItems.map(function (x) { return x.still; }) : null,
      liveUrls: imgItems ? imgItems.map(function (x) { return x.liveVideo || null; }) : null,
      imageCount: imgItems ? imgItems.length : null,
      downloaded: false,
      downloadedKind: null,
      localFile: null
    });
    saveHistory(list);
    renderHistory();
  }
  function renderHistory() {
    const list = loadHistory();
    const box = $('hist-list');
    const badge = $('tab-badge');
    if (list.length) {
      badge.textContent = list.length > 99 ? '99+' : String(list.length);
      badge.classList.add('show');
    } else {
      badge.classList.remove('show');
    }

    if (!list.length) {
      box.innerHTML =
        '<div class="hist-empty">' +
        '<div class="ico"><svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#A39E94" stroke-width="1.5"><circle cx="12" cy="12" r="8"/><path d="M12 8v4.5l3 1.8"/></svg></div>' +
        '<p>还没有下载记录<br/>去首页解析一个链接试试吧</p></div>';
      return;
    }

    box.innerHTML = list.map(function (item) {
      const platKey = normalizePlatKey(item.platform);
      const meta = PLATFORM_META[platKey] || PLATFORM_META.other;
      const cover = item.cover || gradientCover(meta.color);
      const time = formatTime(item.ts);
      const dlBadge = item.downloaded ? '<span class="hist-dl-badge">已下载</span>' : '';
      return (
        '<div class="hist-item" data-id="' + item.id + '" data-downloaded="' + (item.downloaded ? '1' : '0') + '">' +
          '<img class="hist-cover" src="' + escapeAttr(cover) + '" alt="" loading="lazy" referrerpolicy="no-referrer"/>' +
          '<div class="hist-meta">' +
            '<div class="tt">' + escapeHtml(item.title) + '</div>' +
            '<div class="sub">' +
              '<span class="p-tag">' + meta.name + '</span>' +
              (item.imageCount ? '<span>图文 · ' + item.imageCount + ' 张</span>' : '') +
              (item.duration != null ? '<span>' + formatDuration(item.duration) + '</span>' : '') +
              '<span>' + time + '</span>' +
              dlBadge +
            '</div>' +
            (function () {
              const shareText = item.rawText || item.url || '';
              if (!shareText) return '';
              return '<div class="hist-link" data-copy="' + escapeAttr(shareText) + '" title="点击复制解析前的分享文本，可粘回 App 回到原视频">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>' +
                '<span>' + escapeHtml(shareText) + '</span>' +
                '</div>';
            })() +
          '</div>' +
          '<div class="hist-actions">' +
            '<button class="hist-act" data-redo="' + escapeAttr(item.url) + '" aria-label="重新解析">' +
              '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 12a8 8 0 1 0 3-6.2"/><path d="M4 4v5h5"/></svg>' +
            '</button>' +
            '<button class="hist-act del" data-del="' + item.id + '" aria-label="删除记录">' +
              '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/></svg>' +
            '</button>' +
          '</div>' +
        '</div>'
      );
    }).join('');

    applyStaggeredAnimation(box, '.hist-item');

    box.querySelectorAll('.hist-link').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.stopPropagation();
        const text = el.getAttribute('data-copy');
        if (!text) return;
        copyText(text);
        showToast('已复制解析前分享文本，粘回对应 App 可回到原视频');
      });
    });

    box.querySelectorAll('.hist-item').forEach(function (el) {
      el.addEventListener('click', function (e) {
        if (e.target.closest('.hist-act') || e.target.closest('.hist-link')) return;
        const id = parseInt(el.getAttribute('data-id'), 10);
        const downloaded = el.getAttribute('data-downloaded') === '1';
        if (downloaded) {
          openLocalFile(id);
        }
      });
    });

    box.querySelectorAll('[data-redo]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        const url = btn.getAttribute('data-redo');
        linkInput.value = url;
        updateDetectUI();
        switchPage('home');
        startParse();
      });
    });

    box.querySelectorAll('[data-del]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        deleteHistoryItem(parseInt(btn.getAttribute('data-del'), 10));
      });
    });
  }

  function deleteHistoryItem(id) {
    saveHistory(loadHistory().filter(function (h) { return h.id !== id; }));
    deleteFileFromIDB(id);
    renderHistory();
    showToast('已删除该记录');
  }

  function openLocalFile(id) {
    const list = loadHistory();
    const item = list.find(function (h) { return h.id === id; });
    if (!item) return;
    if (item.localFile) {
      window.open(item.localFile, '_blank');
      showToast('正在打开本地文件');
    } else {
      showToast('文件已保存到本地下载目录');
    }
  }

  function normalizePlatKey(p) {
    if (!p) return 'other';
    const v = String(p).toLowerCase();
    if (v.includes('douyin')) return 'douyin';
    if (v.includes('xiaohongshu') || v === 'xhs' || v.includes('xhs')) return 'xiaohongshu';
    if (v.includes('bili') || v === 'bili') return 'bilibili';
    return 'other';
  }
  function formatTime(ts) {
    const d = new Date(ts);
    const now = new Date();
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    if (d.toDateString() === now.toDateString()) return '今天 ' + hh + ':' + mm;
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hh + ':' + mm;
  }

  $('btn-open-dir').addEventListener('click', function () {
    // Native bridge opens the shell's download folder (DocumentsUI); in a
    // plain browser there is no device folder to open, so just say so.
    try {
      if (window.AppBridge && typeof AppBridge.openDownloadDir === 'function') {
        AppBridge.openDownloadDir();
        return;
      }
    } catch (_) {}
    showToast('该功能需在 App 内使用');
  });

  $('btn-clear-hist').addEventListener('click', function () {
    if (!loadHistory().length) {
      showToast('暂无历史记录');
      return;
    }
    localStorage.removeItem(STORAGE_KEY);
    renderHistory();
    showToast('历史记录已清空');
  });

  /* ========== Stats / prefs ========== */
  function bumpStats() {
    const n = (parseInt(localStorage.getItem(STATS_KEY) || '0', 10) || 0) + 1;
    localStorage.setItem(STATS_KEY, String(n));
    renderStats();
  }
  function renderStats() {
    const n = parseInt(localStorage.getItem(STATS_KEY) || '0', 10) || 0;
    $('stat-count').innerHTML = n + '<em>次</em>';
  }

  $('row-clear').addEventListener('click', function () {
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(STATS_KEY);
    localStorage.removeItem(PREFS_KEY);
    renderHistory();
    renderStats();
    showToast('本地数据已清除');
  });

  function copyText(text) {
    // Native bridge write is synchronous and works regardless of WebView
    // clipboard-permission quirks on various ROMs.
    try {
      if (window.AppBridge && typeof AppBridge.writeClipboard === 'function') {
        AppBridge.writeClipboard(text);
        return;
      }
    } catch (_) {}
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).catch(function () { fallbackCopy(text); });
    } else {
      fallbackCopy(text);
    }
  }
  function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (_) {}
    document.body.removeChild(ta);
  }

  /* ========== Tabs ========== */
  function switchPage(name) {
    document.querySelectorAll('.page').forEach(function (p) {
      p.classList.toggle('active', p.id === 'page-' + name);
    });
    document.querySelectorAll('.tab').forEach(function (t) {
      t.classList.toggle('active', t.getAttribute('data-page') === name);
    });
    $('pages').scrollTop = 0;
    if (name === 'history') renderHistory();
  }
  document.querySelectorAll('.tab').forEach(function (t) {
    t.addEventListener('click', function () {
      // The tabbar sits above the sheet overlay: picking a tab while the
      // result sheet is open closes it and switches (was previously blocked
      // by the overlay, which read as "tabs stopped working").
      if (sheet.classList.contains('show')) closeSheet();
      switchPage(t.getAttribute('data-page'));
    });
  });

  /* ========== Composer dock-on-scroll ========== */
  const pagesEl = $('pages');
  const pageHome = $('page-home');
  const pasteCardEl = $('paste-card');
  const dockSentinel = $('dock-sentinel');
  let docked = false;
  let dockScroll = 0;     // scroll position where docking happened

  function updateDock() {
    const scrollTop = pagesEl.scrollTop;
    // The sentinel is not sticky, so its position always reflects the true
    // scroll geometry — even while the card itself is pinned.
    const contentY = dockSentinel.getBoundingClientRect().top -
                     pagesEl.getBoundingClientRect().top + scrollTop;
    const maxScroll = pagesEl.scrollHeight - pagesEl.clientHeight;
    // Sticky top small enough that the card can actually reach its stick
    // point within the available scroll range on any viewport.
    const pinTop = Math.max(8, Math.min(90, contentY - maxScroll + 12));
    pasteCardEl.style.setProperty('--pin-top', pinTop + 'px');
    if (!docked) {
      if (scrollTop > contentY - pinTop - 60) {
        docked = true;
        dockScroll = scrollTop;
        pageHome.classList.add('docked');
      }
    } else if (scrollTop < dockScroll - 55) {
      docked = false;
      pageHome.classList.remove('docked');
    }
  }
  // No rAF gating: rAF is starved in background tabs and the dock state
  // would freeze. The handler itself is cheap.
  pagesEl.addEventListener('scroll', updateDock, { passive: true });
  updateDock();

  /* ========== Platform chips ========== */
  document.querySelectorAll('.chip').forEach(function (chip) {
    chip.addEventListener('click', function () {
      document.querySelectorAll('.chip').forEach(function (c) { c.classList.remove('on'); });
      chip.classList.add('on');
      const p = chip.getAttribute('data-p');
      const placeholders = {
        all: '打开 App 复制分享链接，粘贴到这里…\n例如 https://v.douyin.com/xxxx',
        douyin: '粘贴抖音分享链接或口令…\n例如 https://v.douyin.com/xxxx',
        xiaohongshu: '粘贴小红书笔记链接或口令…\n例如 https://www.xiaohongshu.com/explore/…',
        bilibili: '粘贴 B 站视频链接或 b23.tv 短链…\n例如 https://www.bilibili.com/video/BV…'
      };
      linkInput.placeholder = placeholders[p] || placeholders.all;
    });
  });

  /* ========== Boot ========== */
  renderStats();
  renderHistory();
  updateDetectUI();

  try {
    const params = new URLSearchParams(location.search);
    const shared = params.get('url') || params.get('text') || params.get('share');
    if (shared) {
      linkInput.value = shared;
      updateDetectUI();
      setTimeout(startParse, 400);
    }
  } catch (_) {}

  window.addEventListener('paste', function (e) {
    const text = (e.clipboardData && e.clipboardData.getData('text')) || '';
    if (text && document.activeElement !== linkInput) {
      const url = extractUrl(text);
      if (url) {
        linkInput.value = text;
        updateDetectUI();
        showToast('已识别粘贴内容');
        setTimeout(startParse, 200);
      }
    }
  });

  /* Cross-file wiring consumed by download.js */
  App.showToast = showToast;
  App.markDownloaded = markDownloaded;
  App.saveFileToIDB = saveFileToIDB;
  App.showDlHint = showDlHint;
  Object.defineProperty(App, 'currentResult', {
    get: function () { return currentResult; }
  });
  Object.defineProperty(App, 'currentHistoryId', {
    get: function () { return currentHistoryId; }
  });

})();
