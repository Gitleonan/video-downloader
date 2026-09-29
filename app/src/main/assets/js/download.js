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
  const dlTasks = {};   // url -> { name, state: 'running'|'paused', cur, total }

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
    bar.innerHTML = urls.map(function (url) {
      const t = dlTasks[url];
      const pct = t.total > 0 ? Math.min(100, Math.round(t.cur * 100 / t.total)) : 0;
      const stateTxt = t.state === 'paused' ? '已暂停'
        : (t.total > 0 ? '下载中' : '排队中');
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
      t.state = 'running'; t.cur = 0; t.total = -1;
      renderTasks();
      try { if (window.AppBridge && AppBridge.download) AppBridge.download(url, t.name); } catch (_) {}
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

    if (data._demo) {
      App.showToast('演示模式：无法真实下载', 'err');
      return;
    }

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
  window.App.hasSrc = hasSrc;
  window.App.pickDownloadUrl = pickDownloadUrl;
  window.App.sanitizeFilename = sanitizeFilename;
})();
