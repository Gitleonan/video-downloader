/* Download half: native bridge events, task bar, progress fills.
   App state/showToast/markDownloaded/saveFileToIDB are provided by app.js
   through the shared App namespace (assigned before any of these run). */
(function () {
  'use strict';

  function sanitizeFilename(name) {
    return (name || 'video').replace(/[<>:"/\\|?*\x00-\x1f]/g, '-').slice(0, 80);
  }

  function hasSrc(u) {
    return typeof u === 'string' && u.trim().length > 0 && u.trim() !== '#';
  }

  function pickDownloadUrl(data, kind) {
    if (kind === 'video') return data.downloadVideoUrl || data.originDownloadVideoUrl || null;
    return data.downloadAudioUrl || data.originDownloadAudioUrl || null;
  }

  /* Download UI driven by the Android shell's real DownloadManager events */
  const nativeDownloads = {};   // url -> { prog, btn, cancelFake }

  /* ========== Background task bar ==========
     Session-scoped map of active downloads, driven purely by the shell's
     real events. Pause = native cancel (DownloadManager has no public
     pause); resume = re-enqueue with the same filename. */
  const dlTasks = {};   // url -> { name, state: 'running'|'queued'|'paused', cur, total }
  /* dlTasks.live 行 = 实况合成任务:壳内单线程串行执行(避免视频 CDN 同 IP
     限速触发超时),入队即显示「排队等待(避免限速)」,出队时原生才发 start。 */

  /* 实时速率采样(EMA):只采样总大小已知的字节任务,给排队行的
     「预计 N 秒后开始」用。锚点法:每次开满 1 秒窗口算一次平均,
     再做 EMA 平滑 —— 进度事件是突发的,按单事件间隔取样会把突发
     字节全算进短窗口,速率被系统性高估(ETA 恒等于「即将开始」)。 */
  let dlRateEma = 0;
  let rateRef = null;

  function fmtWait(sec) {
    if (sec <= 3) return '即将开始';
    if (sec < 60) return '预计约 ' + sec + ' 秒后开始';
    return '预计约 ' + Math.ceil(sec / 60) + ' 分钟后开始';
  }

  function fmtSize(n) {
    if (!n || n <= 0) return '…';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }

  function renderTasks() {
    const bar = $('taskbar');
    if (!bar) return;
    const urls = Object.keys(dlTasks);
    bar.classList.toggle('show', urls.length > 0);
    const liveUrls = urls.filter(function (u) { return dlTasks[u].live; });
    let runningLive = null;
    for (let i = 0; i < liveUrls.length; i++) {
      const t = dlTasks[liveUrls[i]];
      if (t.state === 'running' && t.total > 0) { runningLive = t; break; }
    }
    const etaSec = runningLive && dlRateEma > 0
      ? Math.max(0, Math.round((runningLive.total - runningLive.cur) / dlRateEma))
      : null;
    bar.innerHTML = urls.map(function (url) {
      const t = dlTasks[url];
      const pct = t.total > 0 ? Math.min(100, Math.round(t.cur * 100 / t.total)) : 0;
      let stateTxt;
      if (t.state === 'paused') {
        stateTxt = '已暂停';
      } else if (t.state === 'queued') {
        const idx = liveUrls.indexOf(url);
        const ahead = liveUrls.slice(0, idx).filter(function (u) {
          const s = dlTasks[u].state;
          return s === 'running' || s === 'queued';
        }).length;
        if (ahead <= 1) {
          stateTxt = '排队等待（避免限速）'
            + (etaSec == null ? '，前一张完成后自动开始' : '，' + fmtWait(etaSec));
        } else {
          stateTxt = '排队等待（避免限速），前面还有 ' + (ahead - 1) + ' 张';
        }
      } else {
        // 非实况行无大小时多半是系统下载器 PENDING/限流,维持「排队中」;
        // 实况行入队即跑,不该被误标
        stateTxt = (t.total > 0 || t.live) ? '下载中' : '排队中';
      }
      const sizeTxt = t.total > 0
        ? fmtSize(t.cur) + ' / ' + fmtSize(t.total)
        : (t.cur > 0 ? fmtSize(t.cur) : '');
      const acts = t.state === 'paused'
        ? '<button class="t-btn wide" data-act="resume">继续</button>' +
          '<button class="t-btn danger" data-act="cancel">取消</button>'
        : '<button class="t-btn" data-act="pause">暂停</button>' +
          '<button class="t-btn danger" data-act="cancel">取消</button>';
      return '<div class="task' + (t.state === 'paused' ? ' paused' : '') + '" data-url="' + escapeAttr(url) + '">' +
        '<div class="t-main">' +
          '<div class="t-name">' + escapeHtml(t.name || '下载任务') + '</div>' +
          '<div class="t-sub"><span class="t-state' + (t.state === 'paused' ? ' paused' : '') + '">' + stateTxt + '</span>' +
            (sizeTxt ? '<span>' + sizeTxt + '</span>' : '') +
            (t.total > 0 ? '<span class="t-pct">' + pct + '%</span>' : '') + '</div>' +
          '<div class="t-bar"><i style="width:' + pct + '%"></i></div>' +
        '</div>' +
        '<div class="t-actions">' + acts + '</div>' +
      '</div>';
    }).join('');
  }

  $('taskbar').addEventListener('click', function (e) {
    const btn = e.target.closest('.t-btn');
    if (!btn) return;
    const row = btn.closest('.task');
    if (!row) return;
    const url = row.getAttribute('data-url');
    const t = dlTasks[url];
    if (!t) return;
    const act = btn.getAttribute('data-act');
    if (act === 'pause') {
      t.state = 'paused';
      try { if (window.AppBridge && AppBridge.cancelDownload) AppBridge.cancelDownload(url); } catch (_) {}
      renderTasks();
      App.showToast('已暂停，继续将重新下载');
    } else if (act === 'resume') {
      // 实况行「继续」必须重新走合成通道:普通 download 只会下载静帧本体
      t.state = t.live ? 'queued' : 'running'; t.cur = 0; t.total = -1;
      renderTasks();
      try {
        if (t.live && window.AppBridge && typeof AppBridge.saveLivePhoto === 'function') {
          AppBridge.saveLivePhoto(url, t.videoUrl, t.backupUrl || '',
            t.base || (t.name || 'live').replace(/\.jpg$/i, ''));
        } else if (window.AppBridge && AppBridge.download) {
          AppBridge.download(url, t.name);
        }
      } catch (_) {}
    } else if (act === 'cancel') {
      if (t.state !== 'paused') {
        try { if (window.AppBridge && AppBridge.cancelDownload) AppBridge.cancelDownload(url); } catch (_) {}
      }
      delete dlTasks[url];
      renderTasks();
    }
  });

  window.__nativeDownload = {
    start: function (url) {
      const d = nativeDownloads[url];
      if (d) d.cancelFake();
      if (dlTasks[url]) { dlTasks[url].state = 'running'; renderTasks(); }
    },
    progress: function (url, cur, total) {
      if (total > 0 && cur > 0) {
        const now = Date.now();
        if (!rateRef || rateRef.url !== url || cur < rateRef.cur) {
          rateRef = { url: url, cur: cur, t: now };
        } else if (now - rateRef.t >= 1000) {
          const r = (cur - rateRef.cur) * 1000 / (now - rateRef.t);
          dlRateEma = dlRateEma > 0 ? dlRateEma * 0.6 + r * 0.4 : r;
          rateRef = { url: url, cur: cur, t: now };
        }
      }
      if (dlTasks[url]) {
        dlTasks[url].cur = cur;
        dlTasks[url].total = total;
        renderTasks();
      }
      const d = nativeDownloads[url];
      if (!d || !d.prog) return;
      if (total > 0) {
        d.prog.classList.remove('indeterminate');
        d.prog.classList.add('advancing');
        d.prog.style.width = Math.min(100, Math.round(cur * 100 / total)) + '%';
      } else if (!d.prog.classList.contains('indeterminate')) {
        // queued / size unknown: ripple sweep instead of a frozen or empty bar
        d.prog.classList.remove('advancing');
        d.prog.classList.add('indeterminate');
        d.prog.style.width = '';   // inline width would shrink the sweep
      }
    },
    done: function (url, ok) {
      if (dlTasks[url]) { delete dlTasks[url]; renderTasks(); }
      const d = nativeDownloads[url];
      if (!d) return;
      if (d.prog) {
        d.prog.classList.remove('indeterminate', 'advancing');
        d.prog.style.width = ok ? '100%' : '0%';
      }
      setTimeout(function () {
        if (d.prog) d.prog.style.width = '0%';
        if (d.btn) d.btn.disabled = false;
      }, 450);
      delete nativeDownloads[url];
    },
    cancelled: function (url) {
      // pause keeps the row visible in the paused state; a plain cancel
      // (no paused flag) drops the task
      const t = dlTasks[url];
      if (!(t && t.state === 'paused')) { delete dlTasks[url]; renderTasks(); }
    }
  };

  function doDownload(kind) {
    const data = App.currentResult;
    if (!data) return;

    const url = pickDownloadUrl(data, kind);
    if (!url) {
      App.showToast(kind === 'video' ? '没有可用的视频地址' : '没有可用的音频地址', 'err');
      return;
    }

    const btn = document.getElementById(kind === 'video' ? 'btn-dl-video' : 'btn-dl-audio');
    const prog = document.getElementById(kind === 'video' ? 'prog-video' : 'prog-audio');
    if (btn) {
      btn.disabled = true;
      if (prog) {
        // No fake percent countdown on the native path (rule: real events
        // only): indeterminate sweep from the click; the shell keeps sweeping
        // on PENDING / unknown-size ticks and takes over with real percent.
        prog.classList.remove('indeterminate', 'advancing');
        prog.style.width = '0%';
        void prog.offsetWidth;   // restart the sweep animation cleanly
        prog.classList.add('indeterminate');
        prog.style.width = '';
      }
      nativeDownloads[url] = {
        prog: prog,
        btn: btn,
        cancelFake: function () {
          if (prog) { prog.classList.remove('indeterminate', 'advancing'); prog.style.width = '0%'; }
        }
      };
    }

    try {
      // Douyin titles carry #hashtag tokens, and '#' truncates everything
      // after it inside DownloadManager's destination pipeline (parsed as a
      // URI fragment), leaving extension-less files. Strip hashtags BEFORE
      // appending the extension.
      const rawTitle = (data.title || '').replace(/#[^\s#]+/g, ' ').trim() || 'video';
      const filename = sanitizeFilename(rawTitle + (kind === 'video' ? '.mp4' : '.mp3'));
      if (window.AppBridge && typeof AppBridge.download === 'function') {
        // Native path: DownloadManager + progress events + completion dialog.
        // Anchors with target=_blank are unreliable inside WebView for CDN
        // URLs without a media file extension.
        // Register in the background task bar (shell keeps it fed with
        // progress events; survives closing this sheet).
        dlTasks[url] = { name: filename, state: 'running', cur: 0, total: -1 };
        renderTasks();
        AppBridge.download(url, filename);
        App.showToast(kind === 'video' ? '已开始下载视频' : '已开始下载音频');
        // The sheet is now dismissible at no cost: the task bar keeps
        // reporting progress after it slides away, so tell the user that.
        App.showDlHint();
        App.markDownloaded(App.currentHistoryId, kind);
        return;
      }

      // Browser fallback: no native events here, so a placeholder animation
      // is allowed until the tab's own download UI takes over.
      if (btn && prog) {
        prog.classList.remove('indeterminate', 'advancing');
        prog.style.width = '0%';
        let p = 0;
        const fakeTimer = setInterval(function () {
          p = Math.min(92, p + 7);
          prog.style.width = p + '%';
        }, 120);
        setTimeout(function () {
          clearInterval(fakeTimer);
          prog.style.width = '100%';
          setTimeout(function () {
            prog.style.width = '0%';
            btn.disabled = false;
          }, 450);
        }, 1400);
      }

      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.rel = 'noopener';
      a.target = '_blank';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      App.showToast(kind === 'video' ? '已开始下载视频' : '已开始下载音频');

      // 标记历史项为已下载
      App.markDownloaded(App.currentHistoryId, kind);

      // 后台尝试 fetch blob 存入 IndexedDB
      fetch(url)
        .then(function (r) { return r.blob(); })
        .then(function (blob) {
          App.saveFileToIDB(App.currentHistoryId, blob, filename);
        })
        .catch(function () {
          // fetch 失败（CORS 等），仅标记已下载
        });
    } catch (e) {
      window.open(url, '_blank');
      App.showToast('已打开下载链接');
    }
  }

  window.App.doDownload = doDownload;

  /* ========== 图文笔记下载(逐张 / 全部) ========== */
  /* 任务键 = 下载地址(静图)或静帧地址(实况:原生进度事件按静帧地址
     回报)。dlTasks 里已存在同键任务时直接忽略本次点击,防止重复入队。 */
  function noteTitle(data) {
    return (data.title || '').replace(/#[^\s#]+/g, ' ').trim() || 'image';
  }

  /* 笔记页来源是真 JPEG(实测 3024×4032 JFIF);API 来源是代理重写的
     webp。扩展名跟着来源走,原生魔数校验两条路都放行。 */
  function imageItemExt(it) {
    return it.fromNote ? '.jpg' : '.webp';
  }

  function triggerAnchorDownload(url, filename) {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.rel = 'noopener';
    a.target = '_blank';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  }

  function doDownloadImage(index) {
    const data = App.currentResult;
    const items = data && data._imageItems;
    const it = items && items[index];
    if (!data || !it) return;

    const base = App.sanitizeFilename(noteTitle(data) + '-' + (index + 1));

    if (it.live && it.liveVideo) {
      if (dlTasks[it.still]) { App.showToast('该图已在下载列表'); return; }
      if (window.AppBridge && typeof AppBridge.saveLivePhoto === 'function') {
        // 原生合成 MicroVideo 动态照片(单文件 .jpg);进度事件按静帧
        // 地址回报,任务行先在这里注册。第 3 参是备用视频地址(masterUrl
        // 带签名会过期,原生失败时自动换 backup 重试)。行初始为排队态:
        // 原生任务真正出队执行时才发 start,页面据此区分「下载中/排队」。
        const queueBusy = Object.keys(dlTasks).some(function (u) {
          const t = dlTasks[u];
          return t.live && (t.state === 'running' || t.state === 'queued');
        });
        dlTasks[it.still] = {
          name: base + '.jpg', state: 'queued', cur: 0, total: -1,
          live: true, videoUrl: it.liveVideo,
          backupUrl: it.liveVideoBackup || '', base: base
        };
        renderTasks();
        AppBridge.saveLivePhoto(it.still, it.liveVideo, it.liveVideoBackup || '', base);
        App.showToast(queueBusy
          ? '已加入队列，待当前实况完成后自动开始（逐张下载避免限速）'
          : '已开始下载实况图 ' + (index + 1));
        App.showDlHint();
        App.markDownloaded(App.currentHistoryId, 'image');
        return;
      }
      // 浏览器预览:无合成能力,分存两个文件
      triggerAnchorDownload(it.still, base + '.jpg');
      triggerAnchorDownload(it.liveVideo, base + '.mp4');
      App.showToast('浏览器环境无合成能力，已分开下载图片与视频');
      return;
    }

    const url = it.still;
    if (dlTasks[url]) { App.showToast('该图已在下载列表'); return; }
    const filename = base + imageItemExt(it);
    if (window.AppBridge && typeof AppBridge.download === 'function') {
      dlTasks[url] = { name: filename, state: 'running', cur: 0, total: -1 };
      renderTasks();
      AppBridge.download(url, filename);
      App.showToast('已开始下载第 ' + (index + 1) + ' 张');
      App.showDlHint();
      App.markDownloaded(App.currentHistoryId, 'image');
      return;
    }
    triggerAnchorDownload(url, filename);
    App.showToast('已开始下载第 ' + (index + 1) + ' 张');
  }

  function doDownloadAllImages() {
    const data = App.currentResult;
    const items = data && data._imageItems;
    if (!data || !items || !items.length) return;
    // 逐个入队,300ms 间隔,避免瞬间打满下载队列(壳内无 zip 打包能力)
    items.forEach(function (_, i) {
      setTimeout(function () { doDownloadImage(i); }, i * 300);
    });
    const liveCount = items.filter(function (x) { return x.live && x.liveVideo; }).length;
    App.showToast('已开始下载全部 ' + items.length + ' 张'
      + (liveCount > 1 ? '，实况图将逐张下载以避免 CDN 限速' : ''));
  }

  window.App.doDownloadImage = doDownloadImage;
  window.App.doDownloadAllImages = doDownloadAllImages;
  window.App.hasSrc = hasSrc;
  window.App.pickDownloadUrl = pickDownloadUrl;
  window.App.sanitizeFilename = sanitizeFilename;
})();
