package com.galaxy.downloader;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    // Serve the bundled assets over an https-like origin so that
    // localStorage / IndexedDB / fetch() all behave as on a real site.
    private static final String START_URL =
            "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private android.view.ViewGroup rootLayout;
    private int safeTopCss = -1;
    private int safeBottomCss = -1;
    private volatile String currentPageHost = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Long, String> activeDownloads = new HashMap<>();
    private final Map<String, ByteTask> activeByteTasks = new HashMap<>();
    /** Download rows that must complete with a toast instead of the
     * 「打开查看」 dialog — image notes download N files at once and stacked
     * dialogs bury the user (real-device feedback). Keyed by row id. */
    private final Set<Long> toastOnlyDownloads = new HashSet<>();
    /** Offscreen WebView used to let XHS's own page JS hydrate the note
     * state when a static fetch only gets the empty shell. Main thread. */
    private WebView noteRenderView;
    private Runnable noteRenderTimeout;
    private Timer downloadTimer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // WebView ignores its own padding when hardware-accelerated, so the
        // insets are applied to a plain container around it instead.
        // Pre-paint background only: once loaded, the page paints the whole
        // screen itself (edge-to-edge — its app bar runs under the status bar).
        // Kept on the theme's `paper` color, which has a values-night twin, so
        // a dark-mode launch doesn't flash light.
        int paper = androidx.core.content.ContextCompat.getColor(this, R.color.paper);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setBackgroundColor(paper);
        webView = new WebView(this);
        webView.setBackgroundColor(paper);
        container.addView(webView, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout = container;
        setContentView(container);

        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // Edge-to-edge: draw behind the status / navigation bars and let the
        // real insets pad the container, so the page needs no env() spacing.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // Only the keyboard lifts the whole view: a covered input is worse than
        // a shifted one. The status / navigation bar insets are deliberately
        // NOT padding here. Padding the container would leave a strip of
        // container background above the page, which reads as a seam against
        // the status bar, and no page content would ever draw under it. The
        // page draws edge-to-edge and spaces its own app bar / dock from the
        // values pushed below.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(0, 0, 0, ime.bottom);
            pushSafeInsets(bars.top, ime.bottom > 0 ? 0 : bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new WebAppBridge(), "AppBridge");

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Uri u = Uri.parse(url);
                currentPageHost = u.getHost() == null ? "" : u.getHost();
                injectSafeInsets();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectSafeInsets();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // window.open() fallback in the page: capture the target URL
                // and route it through the main WebView / downloader.
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        handleNewUrl(req.getUrl());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype,
                                        long contentLength) {
                long id = enqueueDownload(url, userAgent, contentDisposition, mimetype, null);
                if (id >= 0) {
                    trackDownload(id, url);
                    notifyPage("start", url, 0, 0);
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    private void handleNewUrl(Uri url) {
        String path = url.getPath() == null ? "" : url.getPath().toLowerCase();
        if (path.endsWith(".mp4") || path.endsWith(".mp3")
                || path.endsWith(".m4a") || path.endsWith(".webm")) {
            long id = enqueueDownload(url.toString(), null, null, null, null);
            if (id >= 0) {
                trackDownload(id, url.toString());
                notifyPage("start", url.toString(), 0, 0);
            }
        } else {
            webView.loadUrl(url.toString());
        }
    }

    private long enqueueDownload(String url, String userAgent,
                                 String contentDisposition, String mimetype,
                                 String destName) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("cookie", cookie);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            if (mimetype != null) req.setMimeType(mimetype);
            String name = destName != null && !destName.isEmpty()
                    ? destName
                    : URLUtil.guessFileName(url, contentDisposition, mimetype);
            // '#' truncates everything after it inside DownloadManager's
            // MediaStore destination pipeline (the path is re-parsed as a URI
            // fragment), leaving extension-less files. Douyin titles routinely
            // carry #hashtags — strip them whatever the page sent.
            name = name.replace("#", " ").trim();
            if (name.isEmpty()) {
                name = "video.mp4";
            }
            req.setTitle(name);
            req.setDescription("Video Downloader");
            // Videos go to Movies, audio to Music, images to Pictures: media
            // collections every gallery app scans, so the file shows up in
            // 系统相册 directly. Generic files stay in Downloads. MimeTypeMap's
            // extension parser returns "" for non-ASCII names (all Chinese
            // titles), so slice the extension off directly.
            String ext = extensionOf(name);
            String extMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            String dir = Environment.DIRECTORY_DOWNLOADS;
            if (extMime != null && extMime.startsWith("video/")) {
                dir = Environment.DIRECTORY_MOVIES;
            } else if (extMime != null && extMime.startsWith("audio/")) {
                dir = Environment.DIRECTORY_MUSIC;
            } else if (extMime != null && extMime.startsWith("image/")) {
                dir = Environment.DIRECTORY_PICTURES;
            }
            req.setDestinationInExternalPublicDir(dir, "Video Downloader/" + name);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(req);
            // 图片完成只弹 toast:图文批量下载会连环弹「打开查看」对话框
            // (真机反馈),静图落相册,toast 报个名字就够。
            if (extMime != null && extMime.startsWith("image/")) {
                synchronized (toastOnlyDownloads) { toastOnlyDownloads.add(id); }
            }
            android.util.Log.d("GalaxyDL", "enqueued id=" + id + " url=" + url);
            Toast.makeText(this, getString(R.string.download_started) + name,
                    Toast.LENGTH_SHORT).show();
            return id;
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return -1;
        }
    }

    /** Bridges clipboard access for the page: navigator.clipboard.readText is
     * denied in WebView on some ROMs (MIUI), so the page calls into here. */
    private class WebAppBridge {
        @JavascriptInterface
        public String readClipboard() {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return "";
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            CharSequence text = clip != null && clip.getItemCount() > 0
                    ? clip.getItemAt(0).getText() : null;
            return text == null ? "" : text.toString();
        }

        @JavascriptInterface
        public void writeClipboard(String text) {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && text != null) {
                cm.setPrimaryClip(ClipData.newPlainText("text", text));
            }
        }

        /** "versionName (versionCode)" — the shell is the only side that knows
         * which build is actually installed, and a stale install is otherwise
         * indistinguishable from inside the page (the versionName alone doesn't
         * change between builds). */
        @JavascriptInterface
        public String appVersion() {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return "";
            try {
                android.content.pm.PackageInfo pi = getPackageManager()
                        .getPackageInfo(getPackageName(), 0);
                long code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi);
                return pi.versionName + " (" + code + ")";
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void download(String url, String filename) {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            if (url == null || url.isEmpty()) return;
            final String fUrl = url, fName = filename;
            mainHandler.post(() -> {
                long id = enqueueDownload(fUrl, null, null, null, fName);
                if (id >= 0) {
                    trackDownload(id, fUrl);
                    notifyPage("start", fUrl, 0, 0);
                }
            });
        }

        @JavascriptInterface
        public void openDownloadDir() {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            mainHandler.post(() -> openDownloadDirInternal());
        }

        /** The page owns the theme, so it also owns the bar colours. It reports
         * its current background (and whether that background is light), and
         * the shell paints the status bar with it.
         *
         * Two reasons this exists rather than relying on edge-to-edge alone:
         * some ROMs draw an opaque status bar instead of letting the page run
         * underneath, and the theme's windowLightStatusBar is a fixed value
         * while the page theme is a user toggle — so without this the status
         * icons go black-on-black the moment the page is dark under the bar.
         * The nav bar is deliberately left transparent: it already works and
         * the dock surface is a different colour from the page background. */
        @JavascriptInterface
        public void syncSystemBars(String colorHex, boolean darkIcons) {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            if (colorHex == null) return;
            final int color;
            try {
                color = android.graphics.Color.parseColor(colorHex);
            } catch (IllegalArgumentException e) {
                return;
            }
            mainHandler.post(() -> {
                if (rootLayout != null) rootLayout.setBackgroundColor(color);
                if (webView != null) webView.setBackgroundColor(color);
                android.view.Window w = getWindow();
                if (w == null) return;
                // No-op on API 35+ (edge-to-edge is enforced there and the page
                // draws under the bar); on older versions this is what removes
                // the seam on ROMs that refuse a transparent status bar.
                w.setStatusBarColor(color);
                androidx.core.view.WindowInsetsControllerCompat c =
                        androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
                c.setAppearanceLightStatusBars(darkIcons);
                c.setAppearanceLightNavigationBars(darkIcons);
            });
        }

        /** Async fetch of a note page (小红书图文). The page API only serves
         * re-encoded webp stills with live photos flattened, so the page
         * fetches the real note HTML — which carries original JPEG urls and
         * the live-photo mp4 — through here. The callback lands as
         * window.__nativeNote({ok, html|error}); the browser preview has no
         * bridge and simply stays on the API path. The UA must be mobile
         * (desktop UA gets a stripped shell without __INITIAL_STATE__) and no
         * cookies are injected: anonymous fetch verified working. */
        @JavascriptInterface
        public void fetchNoteHtml(String url) {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            if (url == null || url.isEmpty()) return;
            final String fUrl = url;
            mainHandler.post(() -> {
                // WebView's own UA on a device is a mobile Chrome UA. Cache
                // it here on the UI thread; getSettings() isn't thread-safe.
                final String ua = webView != null
                        ? webView.getSettings().getUserAgentString() : null;
                new Thread(() -> {
                    String html = null;
                    try { html = httpGetString(fUrl, ua); } catch (Exception ignore) {}
                    // Full server-rendered pages carry the image list inline;
                    // XHS risk control increasingly serves an anonymous
                    // client-hydration shell (no imageList) instead — fall
                    // through to the offscreen render for those.
                    if (html != null && html.contains("__INITIAL_STATE__")
                            && html.contains("imageList")) {
                        deliverNotePayload(buildNotePayload(true, html, null));
                        return;
                    }
                    mainHandler.post(() -> startNoteRenderFetch(fUrl));
                }, "note-fetch").start();
            });
        }

        /** Live-photo download: byte-fetch the still + mp4 and compose a
         * Google Motion Photo (MicroVideo V1) saved as one .jpg in
         * Pictures/Video Downloader. Only on API 29+ — RELATIVE_PATH-based
         * MediaStore writes don't exist below, and raw public-storage writes
         * would need WRITE_EXTERNAL_STORAGE; there the two files are queued
         * as ordinary DownloadManager jobs instead. Progress events are
         * keyed by the still url (the page registers that row). backupVideoUrl
         * is the unsigned long-lived stream: tried automatically when the
         * signed masterUrl fetch fails. */
        @JavascriptInterface
        public void saveLivePhoto(String stillUrl, String videoUrl,
                                  String backupVideoUrl, String baseName) {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            if (stillUrl == null || stillUrl.isEmpty()
                    || videoUrl == null || videoUrl.isEmpty()) return;
            String name = baseName == null || baseName.trim().isEmpty()
                    ? "live" : baseName.trim();
            // '#' truncates DownloadManager destination paths (rule 14);
            // byte-saved names keep the same contract as a guard.
            name = name.replace("#", " ").trim();
            if (name.isEmpty()) name = "live";
            final String fStill = stillUrl, fVideo = videoUrl, fName = name;
            final String fBackup = backupVideoUrl == null ? "" : backupVideoUrl;
            mainHandler.post(() -> startLivePhotoTask(fStill, fVideo, fBackup, fName));
        }

        /** Task-bar cancel / pause: kill the DownloadManager row. Pause is
         * page-level state (DownloadManager has no public pause), resume
         * re-enqueues via download(). Byte-level tasks (live photos) are
         * cancelled by flag + connection disconnect. */
        @JavascriptInterface
        public void cancelDownload(String url) {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            if (url == null || url.isEmpty()) return;
            final String fUrl = url;
            mainHandler.post(() -> {
                final ByteTask bt;
                synchronized (activeByteTasks) { bt = activeByteTasks.get(fUrl); }
                if (bt != null) {
                    bt.cancelled = true;
                    HttpURLConnection c = bt.conn;
                    if (c != null) {
                        try { c.disconnect(); } catch (Exception ignore) {}
                    }
                }
                long found = -1;
                synchronized (activeDownloads) {
                    for (Map.Entry<Long, String> e : activeDownloads.entrySet()) {
                        if (fUrl.equals(e.getValue())) { found = e.getKey(); break; }
                    }
                }
                if (found >= 0) {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    try { dm.remove(found); } catch (Exception ignore) {}
                    synchronized (activeDownloads) { activeDownloads.remove(found); }
                }
                // Always settle the page's task row (paused rows stay visible).
                notifyPage("cancelled", fUrl, 0, 0);
            });
        }
    }

    /** Hand the system-bar insets to the page as CSS variables. The page owns
     * its own bar spacing (see the inset listener in onCreate); these land as
     * inline custom properties, which outrank the :root env() fallback, so
     * inside the shell this is the single inset source.
     *
     * getInsets() reports PHYSICAL pixels while the page consumes CSS pixels
     * (1 CSS px == 1 dp at this page's initial-scale=1), so the conversion
     * through display density is load-bearing: skipping it pads the app bar by
     * the screen density (≈2.7x) instead of the status bar height. */
    private void pushSafeInsets(int topPx, int bottomPx) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        int top = Math.round(topPx / density);
        int bottom = Math.round(bottomPx / density);
        if (top == safeTopCss && bottom == safeBottomCss) return;
        safeTopCss = top;
        safeBottomCss = bottom;
        injectSafeInsets();
    }

    /** Runs on the UI thread — inset dispatch and the page callbacks both are.
     * documentElement often doesn't exist yet when this fires from
     * onPageStarted, so the snippet retries instead of racing the parser and
     * letting the first paint land unpadded. */
    private void injectSafeInsets() {
        if (webView == null || safeTopCss < 0) return;
        final String js = "(function(){var t='" + safeTopCss + "px',b='" + safeBottomCss + "px',n=0;"
                + "function a(){var e=document.documentElement;"
                + "if(!e){if(++n<600)setTimeout(a,0);return;}"
                + "e.style.setProperty('--safe-t',t);e.style.setProperty('--safe-b',b);}"
                + "a();})()";
        webView.evaluateJavascript(js, null);
    }

    /** History page "打开下载目录": open the app's download folder in the
     * system file manager. Videos land in Movies, audio in Music, everything
     * else in Downloads — always under Video Downloader — so try those in order
     * and fall back to the system downloads list. */
    private void openDownloadDirInternal() {
        String[][] candidates = {
                {Environment.DIRECTORY_MOVIES, "Video Downloader"},
                {Environment.DIRECTORY_MUSIC, "Video Downloader"},
                {Environment.DIRECTORY_DOWNLOADS, "Video Downloader"},
        };
        for (String[] cand : candidates) {
            java.io.File dir = new java.io.File(
                    Environment.getExternalStoragePublicDirectory(cand[0]), cand[1]);
            if (dir.isDirectory() && openDocumentsDir("primary:" + cand[0] + "/" + cand[1])) {
                return;
            }
        }
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
            return;
        } catch (Exception e) {
            android.util.Log.d("GalaxyDL", "open downloads ui failed: " + e);
        }
        Toast.makeText(this, "没有找到可以打开该目录的文件管理器", Toast.LENGTH_SHORT).show();
    }

    /** ACTION_VIEW on a DocumentsUI directory URI — opens the device's file
     * manager at that folder. False when no app handles it. */
    private boolean openDocumentsDir(String docId) {
        try {
            Uri uri = android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", docId);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            android.util.Log.d("GalaxyDL", "open dir " + docId + " failed: " + e);
            return false;
        }
    }

    /** One in-flight byte-level download (live-photo composition). Cancel
     * works by flag checked between reads plus disconnecting the current
     * connection — HttpURLConnection reads don't respond to interrupt(). */
    private static final class ByteTask {
        volatile boolean cancelled;
        volatile HttpURLConnection conn;
    }

    /** Renders the note URL in an offscreen WebView and lets XHS's own page
     * JS hydrate __INITIAL_STATE__: anonymous static fetches increasingly
     * get a client-hydration shell with EMPTY note data (risk control), but
     * a real browser still loads the note. Polls the serialized state until
     * an image list appears, then wraps it as a synthetic html document and
     * delivers it through the same __nativeNote callback — the page-side
     * parser is unchanged. Must run on the UI thread. */
    private void startNoteRenderFetch(String url) {
        teardownNoteRender();
        final WebView wv = new WebView(this);
        noteRenderView = wv;
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBlockNetworkImage(true);   // only the state is needed, skip pixels
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                pollNoteState(view, 0);
            }
        });
        wv.loadUrl(url);
        noteRenderTimeout = () -> finishNoteRender("页面加载超时");
        mainHandler.postDelayed(noteRenderTimeout, 22000);
    }

    private void pollNoteState(WebView wv, int attempt) {
        if (noteRenderView != wv) return;   // superseded or torn down
        if (attempt >= 14) { finishNoteRender("页面加载超时"); return; }
        wv.evaluateJavascript(
                "(function(){try{var s=window.__INITIAL_STATE__||null;"
                + "var t=document.body?document.body.innerText:'';"
                + "var missing=t.indexOf('当前笔记暂时无法浏览')>=0"
                + "||t.indexOf('笔记不存在')>=0"
                + "||t.indexOf('仅作者可见')>=0"
                + "||t.indexOf('已被删除')>=0;"
                + "if(missing)return '__XHS_NOTE_MISSING__';"
                + "return s;}catch(e){return null;}})()",
                value -> {
                    if (noteRenderView != wv) return;
                    // Deleted / author-only notes never hydrate an image list
                    // but DO show the placeholder text — report that distinctly
                    // so the page can say 原文已失效 instead of a generic error.
                    if (value != null && value.contains("__XHS_NOTE_MISSING__")) {
                        finishNoteRender("原文已失效（笔记可能已被作者删除或仅自己可见）");
                        return;
                    }
                    // Ready once an image list actually has entries (the
                    // shell also carries empty containers), or a populated
                    // legacy noteDetailMap shows up.
                    boolean ready = value != null
                            && (value.contains("\"imageList\":[{")
                                || (value.contains("noteDetailMap")
                                    && !value.contains("noteDetailMap\":{}")));
                    if (ready) {
                        teardownNoteRender();
                        deliverNotePayload(buildNotePayload(true,
                                "<!doctype html><script>window.__INITIAL_STATE__="
                                        + value + ";</script>", null));
                        return;
                    }
                    mainHandler.postDelayed(() -> pollNoteState(wv, attempt + 1), 1000);
                });
    }

    private void finishNoteRender(String reason) {
        teardownNoteRender();
        deliverNotePayload(buildNotePayload(false, null, reason));
    }

    private void teardownNoteRender() {
        if (noteRenderTimeout != null) {
            mainHandler.removeCallbacks(noteRenderTimeout);
            noteRenderTimeout = null;
        }
        if (noteRenderView != null) {
            WebView wv = noteRenderView;
            noteRenderView = null;
            try { wv.loadUrl("about:blank"); wv.destroy(); } catch (Exception ignore) {}
        }
    }

    /** JSON-stringifies the __nativeNote callback payload and evaluates it
     * on the main thread. org.json handles all string escaping. */
    private void deliverNotePayload(String payload) {
        final String js = "window.__nativeNote && __nativeNote(" + payload + ")";
        mainHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private static String buildNotePayload(boolean ok, String html, String error) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ok", ok);
            if (html != null) o.put("html", html);
            if (error != null) o.put("error", error);
            return o.toString();
        } catch (Exception ignore) {
            return "{\"ok\":false,\"error\":\"payload build failed\"}";
        }
    }

    private void startLivePhotoTask(String stillUrl, String videoUrl,
                                    String backupUrl, String baseName) {
        if (Build.VERSION.SDK_INT < 29) {
            // MediaStore RELATIVE_PATH writes (the only public-storage write
            // path here) start at API 29; below that, save the two files as
            // ordinary DownloadManager jobs — the still row keyed by stillUrl
            // is what the page's task bar registered.
            long id1 = enqueueDownload(stillUrl, null, null, "image/jpeg", baseName + ".jpg");
            if (id1 >= 0) {
                synchronized (toastOnlyDownloads) { toastOnlyDownloads.add(id1); }
                trackDownload(id1, stillUrl);
                notifyPage("start", stillUrl, 0, 0);
            }
            long id2 = enqueueDownload(videoUrl, null, null, "video/mp4", baseName + ".mp4");
            if (id2 >= 0) {
                synchronized (toastOnlyDownloads) { toastOnlyDownloads.add(id2); }
                trackDownload(id2, videoUrl);
            }
            return;
        }
        final ByteTask task = new ByteTask();
        Thread worker = new Thread(
                () -> runLivePhotoTask(task, stillUrl, videoUrl, backupUrl, baseName),
                "live-photo");
        synchronized (activeByteTasks) { activeByteTasks.put(stillUrl, task); }
        notifyPage("start", stillUrl, 0, 0);
        worker.start();
    }

    /** Byte-fetch still + mp4, compose a MicroVideo motion photo, store it
     * via MediaStore. Any failure that keeps composition impossible but
     * leaves both payloads in hand falls back to saving the two files as-is
     * (the page's 兜底 contract); transport errors fail the task with the
     * reason in the toast. The video is fetched from the signed masterUrl
     * first and retried once from the unsigned backupUrl on failure. */
    private void runLivePhotoTask(ByteTask task, String stillUrl, String videoUrl,
                                  String backupUrl, String baseName) {
        try {
            byte[] still = httpGetBytes(task, stillUrl, stillUrl, 0, 32 * 1024 * 1024);
            if (task.cancelled) return;
            // Once the video's Content-Length is known the task bar switches
            // from the indeterminate sweep to a percent over the combined size.
            byte[] video;
            try {
                video = httpGetBytes(task, videoUrl, videoUrl,
                        still.length, 96 * 1024 * 1024);
            } catch (Exception e) {
                if (task.cancelled) return;
                android.util.Log.d("GalaxyDL", "master video failed, trying backup: " + e);
                if (backupUrl == null || backupUrl.isEmpty()) {
                    throw new IOException("取视频失败：" + failReason(e));
                }
                video = httpGetBytes(task, backupUrl, videoUrl,
                        still.length, 96 * 1024 * 1024);
            }
            if (task.cancelled) return;

            // 实况封面的水印问题(真机反馈):网页版静帧渲染(sns-webpic
            // 的 !h5_1080jpg)对实况图带小红书水印,相册缩略图可见、播放时
            // 消失。mp4 本身无水印且尺寸与静帧一致(实况静帧本就是视频
            // 尺寸),所以优先取视频第一帧做封面;取帧失败再退回原静帧。
            byte[] jpg = extractFirstFrame(video);
            if (jpg == null) {
                jpg = still;
            }
            if (!MotionPhoto.isJpeg(jpg)) {
                // Page-source stills are JPEG; API-proxy stills are webp.
                // Transcode through the framework decoder so composition
                // also works off the fallback source (no native libs).
                Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                if (bmp != null) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, b);
                    bmp.recycle();
                    jpg = b.toByteArray();
                }
            }
            if (!MotionPhoto.isJpeg(jpg)) {
                // Undecodable still: no motion photo possible. Payloads are
                // in hand — write both files straight to MediaStore.
                saveTwoFiles(task, still, video, baseName, stillUrl);
                return;
            }

            mediaStoreSave(
                    MediaStore.Images.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_PICTURES + "/Video Downloader",
                    "image/jpeg", baseName + ".jpg",
                    MotionPhoto.compose(jpg, video));
            if (task.cancelled) return;
            final String okName = baseName + ".jpg";
            mainHandler.post(() -> {
                notifyPage("done", stillUrl, 1, 1);
                // Plain toast, not the「打开查看」dialog: image notes download
                // several files back to back and stacked dialogs are unusable
                // (same contract as still-image DownloadManager rows).
                Toast.makeText(MainActivity.this,
                        getString(R.string.saved_toast, okName), Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            android.util.Log.d("GalaxyDL", "live photo failed: " + e);
            if (!task.cancelled) {
                final String reason = failReason(e);
                mainHandler.post(() -> {
                    notifyPage("done", stillUrl, 0, 1);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.live_save_failed, reason),
                            Toast.LENGTH_LONG).show();
                });
            }
        } finally {
            synchronized (activeByteTasks) { activeByteTasks.remove(stillUrl); }
        }
    }

    private static String failReason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Decodes the mp4's first frame as JPEG bytes (null on failure). The
     * motion part is watermark-free and — for XHS live photos — the same
     * size as the still rendition, so it makes a cleaner composed cover
     * than the watermarked web still. Framework-only (MediaMetadataRetriever
     * needs a seekable source, hence the temp file). */
    private byte[] extractFirstFrame(byte[] video) {
        java.io.File tmp = new java.io.File(getCacheDir(),
                "live_frame_" + System.currentTimeMillis() + ".mp4");
        try {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(video);
            }
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(tmp.getAbsolutePath());
                Bitmap bmp = r.getFrameAtTime(0,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp == null) return null;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, b);
                bmp.recycle();
                android.util.Log.d("GalaxyDL", "live cover from video frame, "
                        + b.size() + " bytes");
                return b.toByteArray();
            } finally {
                try { r.release(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            android.util.Log.d("GalaxyDL", "first-frame extract failed: " + e);
            return null;
        } finally {
            tmp.delete();
        }
    }

    /** Compose-fallback on 29+: still (sniffed type) + mp4 as two separate
     * MediaStore files — the always-available 兜底 for undecodable stills. */
    private void saveTwoFiles(ByteTask task, byte[] still, byte[] video,
                              String baseName, String keyUrl) {
        boolean savedAny = false;
        String stillMime = sniffImageMime(still);
        if (stillMime != null) {
            try {
                mediaStoreSave(MediaStore.Images.Media.getContentUri(
                                MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        Environment.DIRECTORY_PICTURES + "/Video Downloader",
                        stillMime, baseName + extensionForMime(stillMime), still);
                savedAny = true;
            } catch (Exception e) {
                android.util.Log.d("GalaxyDL", "still save failed: " + e);
            }
        }
        try {
            mediaStoreSave(MediaStore.Video.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_MOVIES + "/Video Downloader",
                    "video/mp4", baseName + ".mp4", video);
            savedAny = true;
        } catch (Exception e) {
            android.util.Log.d("GalaxyDL", "video save failed: " + e);
        }
        if (task.cancelled) return;
        final boolean ok = savedAny;
        mainHandler.post(() -> {
            notifyPage("done", keyUrl, ok ? 1 : 0, 1);
            Toast.makeText(MainActivity.this,
                    getString(ok ? R.string.live_fallback_saved
                            : R.string.live_save_failed, "无法写入相册"),
                    Toast.LENGTH_LONG).show();
        });
    }

    /** Inserts bytes into the media store as a pending entry, writes them,
     * then clears IS_PENDING. Caller must be on API 29+ (RELATIVE_PATH). */
    private Uri mediaStoreSave(Uri collection, String relativePath, String mime,
                               String displayName, byte[] bytes) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(collection, v);
        if (uri == null) throw new IOException("media store insert failed: " + relativePath);
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("stream open failed: " + uri);
            try { os.write(bytes); os.flush(); } finally { os.close(); }
        } catch (IOException e) {
            try { getContentResolver().delete(uri, null, null); } catch (Exception ignore) {}
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        return uri;
    }

    private static String sniffImageMime(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    private static String extensionForMime(String mime) {
        if ("image/jpeg".equals(mime)) return ".jpg";
        if ("image/webp".equals(mime)) return ".webp";
        if ("image/png".equals(mime)) return ".png";
        return "";
    }

    /** GET with a manual redirect loop — HttpURLConnection refuses
     * cross-protocol hops, and xhslink share links redirect https→https plus
     * the odd http entry point. Body capped at 4 MB (note pages ~200 KB). */
    private static String httpGetString(String url, String userAgent) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5 && current != null; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            try {
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setInstanceFollowRedirects(false);
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    current = loc == null ? null
                            : new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n, total = 0;
                try {
                    while ((n = in.read(chunk)) != -1) {
                        total += n;
                        if (total > 4 * 1024 * 1024) return null;
                        buf.write(chunk, 0, n);
                    }
                } finally {
                    in.close();
                }
                return buf.toString("UTF-8");
            } finally {
                conn.disconnect();
            }
        }
        return null;
    }

    /** Fetches a URL fully into memory for a byte task, posting progress
     * events keyed by {@code keyUrl}: cur = baseBytes + bytes read, total =
     * baseBytes + Content-Length (or -1 → page keeps the indeterminate sweep).
     * Returns null on cancellation, throws on transport / HTTP errors. */
    private byte[] httpGetBytes(ByteTask task, String url, String keyUrl,
                                long baseBytes, long maxBytes) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        task.conn = conn;
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            if (task.cancelled) return null;
            long cl = conn.getContentLengthLong();
            long total = cl > 0 ? baseBytes + cl : -1;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            long cur = baseBytes;
            int n;
            try {
                while ((n = in.read(chunk)) != -1) {
                    if (task.cancelled) return null;
                    cur += n;
                    if (buf.size() + n > maxBytes) throw new IOException("payload too large");
                    buf.write(chunk, 0, n);
                    final long fCur = cur, fTotal = total;
                    mainHandler.post(() -> notifyPage("progress", keyUrl, fCur, fTotal));
                }
            } finally {
                in.close();
            }
            return buf.toByteArray();
        } finally {
            conn.disconnect();
            if (task.conn == conn) task.conn = null;
        }
    }

    private void trackDownload(long id, String url) {
        synchronized (activeDownloads) {
            activeDownloads.put(id, url);
        }
        if (downloadTimer == null) {
            downloadTimer = new Timer("download-poll");
            downloadTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    pollDownloads();
                }
            }, 400, 600);
        }
    }

    private void pollDownloads() {
        long[] ids;
        synchronized (activeDownloads) {
            if (activeDownloads.isEmpty()) return;
            ids = new long[activeDownloads.size()];
            int i = 0;
            for (Long k : activeDownloads.keySet()) ids[i++] = k;
        }
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        for (long id : ids) {
            String url;
            synchronized (activeDownloads) { url = activeDownloads.get(id); }
            if (url == null) continue;
            Cursor c = null;
            try {
                c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c == null || !c.moveToFirst()) continue;
                int status = c.getInt(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long bytes = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    String mediaType = c.getString(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_MEDIA_TYPE));
                    boolean badContent = mediaType != null
                            && (mediaType.startsWith("text/")
                                || "application/json".equals(mediaType));
                    if (!badContent) {
                        // Some rate-limit proxies send the error body as 200 +
                        // application/octet-stream, which slips past the mime
                        // guard. Verify the payload actually starts with a
                        // known media container signature instead.
                        badContent = !hasMediaSignature(id);
                    }
                    android.util.Log.d("GalaxyDL", "SUCCESS id=" + id
                            + " mime=" + mediaType + (badContent ? " BAD-CONTENT" : ""));
                    if (badContent) {
                        // Upstream returned an error body with HTTP 200
                        // (expired link / rate limit): it would otherwise land
                        // on disk as a fake .mp4. Delete and report failure.
                        try { dm.remove(id); } catch (Exception ignore) {}
                        finishDownload(id, url, false, R.string.bad_content);
                    } else {
                        finishDownload(id, url, true, 0);
                    }
                } else if (status == DownloadManager.STATUS_FAILED) {
                    int reason = c.getInt(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_REASON));
                    android.util.Log.d("GalaxyDL", "FAILED id=" + id + " reason=" + reason);
                    finishDownload(id, url, false, R.string.download_failed);
                } else if (status == DownloadManager.STATUS_PENDING
                        || status == DownloadManager.STATUS_RUNNING) {
                    int reason = c.getInt(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_REASON));
                    android.util.Log.d("GalaxyDL", "tick id=" + id + " status=" + status
                            + " reason=" + reason + " bytes=" + bytes + " total=" + total);
                    // PENDING must surface too (rate-limited upstream keeps
                    // jobs here for minutes): total <= 0 drives the page's
                    // indeterminate sweep instead of a frozen/empty bar.
                    final String fUrl = url;
                    final long fBytes = bytes;
                    final long fTotal = status == DownloadManager.STATUS_RUNNING ? total : -1;
                    mainHandler.post(() -> notifyPage("progress", fUrl, fBytes, fTotal));
                } else {
                    android.util.Log.d("GalaxyDL", "other id=" + id + " status=" + status);
                }
            } catch (Exception e) {
                // transient query failures: retry on the next tick
            } finally {
                if (c != null) c.close();
            }
        }
        synchronized (activeDownloads) {
            if (activeDownloads.isEmpty() && downloadTimer != null) {
                downloadTimer.cancel();
                downloadTimer.purge();
                downloadTimer = null;
            }
        }
    }

    private void finishDownload(long id, String url, boolean ok, int failMsgRes) {
        synchronized (activeDownloads) { activeDownloads.remove(id); }
        final boolean toastOnly;
        synchronized (toastOnlyDownloads) { toastOnly = toastOnlyDownloads.remove(id); }
        mainHandler.post(() -> {
            notifyPage("done", url, ok ? 1 : 0, 1);
            if (ok && toastOnly) {
                // Images: plain toast with the file name. The「打开查看」
                // dialog per file is unusable when an image note downloads
                // N files back to back (real-device feedback).
                Toast.makeText(this, getString(R.string.saved_toast,
                        downloadTitle(id, url)), Toast.LENGTH_SHORT).show();
            } else if (ok) {
                showOpenDialog(id, url);
            } else {
                Toast.makeText(MainActivity.this,
                        failMsgRes != 0 ? failMsgRes : R.string.download_failed,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Display name of a completed row for the toast: COLUMN_TITLE (what we
     * enqueued), falling back to a guessed name when the row is gone. */
    private String downloadTitle(long id, String url) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = null;
        try {
            if (dm != null) {
                c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    String name = c.getString(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TITLE));
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return URLUtil.guessFileName(url, null, null);
    }

    private void notifyPage(String event, String url, long a, long b) {
        if (webView == null) return;
        String js = "window.__nativeDownload && __nativeDownload." + event + "("
                + jsString(url) + "," + a + "," + b + ")";
        webView.evaluateJavascript(js, null);
    }

    private static String jsString(String s) {
        String escaped = s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "'" + escaped + "'";
    }

    private void showOpenDialog(long id, String url) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        String name = null;
        Cursor c = null;
        try {
            if (dm != null) {
                c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    name = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.saved_title)
                .setMessage(getString(R.string.saved_message,
                        name == null || name.isEmpty()
                                ? URLUtil.guessFileName(url, null, null) : name))
                .setPositiveButton(R.string.open_action, (d, w) -> openDownloaded(id, dm))
                .setNegativeButton(R.string.close_action, null)
                .show();
    }

    /** ASCII extension of a filename ("" when none), immune to non-ASCII
     * titles that break MimeTypeMap.getFileExtensionFromUrl. */
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]+") ? ext : "";
    }

    /** Reads the first bytes of a completed download and checks them against
     * known media container signatures. Error bodies served with a lying
     * Content-Type (200 + octet-stream) are rejected here. */
    private boolean hasMediaSignature(long id) {
        java.io.InputStream in = null;
        try {
            Uri uri = Uri.parse("content://downloads/my_downloads").buildUpon()
                    .appendPath(String.valueOf(id)).build();
            in = getContentResolver().openInputStream(uri);
            if (in == null) return false;
            byte[] buf = new byte[12];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) != -1) n += r;
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < n; i++) hex.append(String.format("%02x", buf[i]));
            android.util.Log.d("GalaxyDL", "sniff id=" + id + " bytes=" + n
                    + " head=[" + hex + "]");
            return looksLikeMedia(buf, n);
        } catch (Exception e) {
            android.util.Log.d("GalaxyDL", "sniff failed id=" + id + ": " + e);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
        }
    }

    /** True when the head bytes match a media container (video / audio /
     * image families the app can download). */
    private static boolean looksLikeMedia(byte[] b, int n) {
        if (n < 4) return false;
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return true;   // mp4/m4a/mov/3gp
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45
                && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) return true;      // webm/mkv (EBML)
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;                  // mp3 (ID3)
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return true;             // mp3/aac frame sync
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return true;   // wav/webp/avi
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return true;   // ogg/opus
        if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return true;   // flac
        if (b[0] == 'F' && b[1] == 'L' && b[2] == 'V') return true;                  // flv
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF) return true;                               // jpeg
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N'
                && b[3] == 'G') return true;                                         // png
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;   // gif
        if (b[0] == 'B' && b[1] == 'M') return true;                                 // bmp
        // avif/heic need no extra case: ISOBMFF containers, matched above by ftyp
        return false;
    }

    /** Opens the completed download. Raw file:// URIs are banned in intents
     * (API 24+), and content://downloads/my_downloads turns out unreadable
     * for some players (AOSP MovieActivity plays the very same file fine
     * from its plain path but fails through the downloads provider), so the
     * actual file is shared via FileProvider; the downloads provider URI
     * stays as a fallback. */
    private void openDownloaded(long id, DownloadManager dm) {
        try {
            if (dm == null) throw new IllegalStateException("no download manager");
            String title = null;
            String localUri = null;
            Cursor c = null;
            try {
                c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    title = c.getString(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TITLE));
                    localUri = c.getString(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_LOCAL_URI));
                }
            } finally {
                if (c != null) c.close();
            }
            String ext = extensionOf(title == null ? "" : title);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null || mime.isEmpty()) mime = "*/*";
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (localUri != null && localUri.startsWith("file://")) {
                java.io.File f = new java.io.File(Uri.parse(localUri).getPath());
                if (f.exists()) {
                    // Portable handle: players read the file through our
                    // FileProvider with the read grant below.
                    intent.setDataAndType(androidx.core.content.FileProvider
                            .getUriForFile(this, "com.galaxy.downloader.files", f), mime);
                    startActivity(intent);
                    return;
                }
            }
            // Fallback: the row is owned by this app, so the downloads
            // provider content:// URI is cross-app-safe.
            Uri uri = Uri.parse("content://downloads/my_downloads").buildUpon()
                    .appendPath(String.valueOf(id)).build();
            intent.setDataAndType(uri, mime);
            startActivity(intent);
        } catch (Exception e) {
            android.util.Log.d("GalaxyDL", "open failed: " + e);
            Toast.makeText(this, "没有找到可以打开该视频的应用",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        teardownNoteRender();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
