package com.galaxy.downloader;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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

import java.util.HashMap;
import java.util.Map;
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
            // Videos go to Movies, audio to Music: media collections every
            // gallery app scans, so the file shows up in 系统相册 directly.
            // Generic files stay in Downloads. MimeTypeMap's extension parser
            // returns "" for non-ASCII names (all Chinese titles), so slice
            // the extension off directly.
            String ext = extensionOf(name);
            String extMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            String dir = Environment.DIRECTORY_DOWNLOADS;
            if (extMime != null && extMime.startsWith("video/")) {
                dir = Environment.DIRECTORY_MOVIES;
            } else if (extMime != null && extMime.startsWith("audio/")) {
                dir = Environment.DIRECTORY_MUSIC;
            }
            req.setDestinationInExternalPublicDir(dir, "Video Downloader/" + name);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(req);
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

        /** Task-bar cancel / pause: kill the DownloadManager row. Pause is
         * page-level state (DownloadManager has no public pause), resume
         * re-enqueues via download(). */
        @JavascriptInterface
        public void cancelDownload(String url) {
            if (!"appassets.androidplatform.net".equals(currentPageHost)) return;
            if (url == null || url.isEmpty()) return;
            final String fUrl = url;
            mainHandler.post(() -> {
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
        mainHandler.post(() -> {
            notifyPage("done", url, ok ? 1 : 0, 1);
            if (ok) {
                showOpenDialog(id, url);
            } else {
                Toast.makeText(MainActivity.this,
                        failMsgRes != 0 ? failMsgRes : R.string.download_failed,
                        Toast.LENGTH_LONG).show();
            }
        });
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
