package io.github.koffelov.ebloclient;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The few things done on eblo.id's own pages inside the app: Twitch login (the OAuth redirect is
 * fixed to eblo.id, so it must finish in this cookie jar), creating a post (the site's uploader,
 * CSRF and Cloudflare check), and anything the native screens don't cover.
 */
public class WebActivity extends BaseActivity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_MODE = "mode";
    static final int MODE_PAGE = 0;
    static final int MODE_LOGIN = 1;
    static final int MODE_NEW_POST = 2;
    static final int REQUEST_LOGIN = 41;
    private static final int REQUEST_FILES = 1;

    /** Hides the site's own header, tab bar, footer and cookie banner: the app provides navigation. */
    private static final String EMBED_CSS = ".mobile-header,#mobile-tabbar,.mobile-tabbar,.footer,"
            + "#cookie-banner,.cookie-banner{display:none!important}"
            + "body{padding-top:0!important;padding-bottom:0!important;margin-top:0!important}";
    private static final String EMBED_JS = "(function(){if(document.getElementById('eblo-app-css'))return;"
            + "var s=document.createElement('style');s.id='eblo-app-css';s.textContent='" + EMBED_CSS + "';"
            + "(document.head||document.documentElement).appendChild(s);})()";

    private WebView web;
    private ProgressBar progress;
    private StateView errorView;
    private FrameLayout fullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;
    private int mode;
    private boolean inLoginFlow;
    private boolean loginSeen;
    private boolean mainFrameFailed;
    private boolean offlineError;
    private String failedUrl;
    private ConnectivityManager.NetworkCallback networkCallback;

    static void open(Context c, String url, String title) {
        c.startActivity(new Intent(c, WebActivity.class).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title));
    }

    static void login(Activity a) {
        Intent i = new Intent(a, WebActivity.class)
                .putExtra(EXTRA_URL, Http.BASE + "/login/twitch?next=/")
                .putExtra(EXTRA_TITLE, "Вход через Twitch")
                .putExtra(EXTRA_MODE, MODE_LOGIN);
        a.startActivityForResult(i, REQUEST_LOGIN);
    }

    static void newPost(Context c) {
        c.startActivity(new Intent(c, WebActivity.class)
                .putExtra(EXTRA_URL, Http.BASE + "/new")
                .putExtra(EXTRA_TITLE, "Новый пост")
                .putExtra(EXTRA_MODE, MODE_NEW_POST));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_PAGE);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        addTopBar(title == null ? "eblo.id" : title, true);
        if (mode == MODE_PAGE) {
            addAction(R.drawable.ic_open, "Открыть в браузере", v -> {
                if (web != null && web.getUrl() != null) Router.openExternal(this, Uri.parse(web.getUrl()));
            });
        }

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this);
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        errorView = new StateView(this);
        errorView.hide();
        content.addView(errorView, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0));
        progress.setVisibility(View.GONE);
        content.addView(progress, new FrameLayout.LayoutParams(-1, Ui.dp(3)));
        page.addView(content, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(0xFF000000);
        fullscreen.setVisibility(View.GONE);
        root.addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));

        setUpWebView();
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            String url = getIntent().getStringExtra(EXTRA_URL);
            loadInApp(url == null ? Http.BASE + "/" : url);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
                runOnUiThread(() -> {
                    if (offlineError && errorView.getVisibility() == View.VISIBLE) retry();
                });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && networkCallback != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
        }
        networkCallback = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---- WebView ----

    @SuppressLint("SetJavaScriptEnabled")
    private void setUpWebView() {
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web.setBackgroundColor(Ui.BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        // Cloudflare Turnstile and YouTube embeds run in third-party frames.
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(this::onDownload);
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            return route(request.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mainFrameFailed = false;
            Uri uri = Uri.parse(url);
            if (Router.isSite(uri) && !isAuthPath(uri)) inLoginFlow = false;
            progress.setProgress(0);
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            embed(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            embed(view, url);
            progress.setVisibility(View.GONE);
            if (!mainFrameFailed) errorView.hide();
            CookieManager.getInstance().flush();
            String title = view.getTitle();
            if (mode == MODE_PAGE && title != null && !title.isEmpty() && !title.startsWith("http")) {
                topTitle.setText(title.replace(" - eblo.id", ""));
            }
            updateBackCallback();
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            updateBackCallback();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            mainFrameFailed = true;
            failedUrl = request.getUrl().toString();
            offlineError = !Ui.online(WebActivity.this);
            progress.setVisibility(View.GONE);
            errorView.show(R.drawable.ic_wifi_off,
                    offlineError ? "Нет подключения к интернету" : "Не удалось открыть страницу",
                    offlineError ? "Страница загрузится сама, когда сеть появится." : String.valueOf(error.getDescription()),
                    "Повторить", WebActivity.this::retry, null, null);
        }

        @Override
        @TargetApi(Build.VERSION_CODES.O)
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page renderer crashed or was killed for memory: rebuild instead of crashing the app.
            if (view == web) {
                ((ViewGroup) web.getParent()).removeView(web);
                web.destroy();
                web = null;
                recreate();
            }
            return true;
        }
    }

    private void embed(WebView view, String url) {
        if (url != null && Router.isSite(Uri.parse(url))) view.evaluateJavascript(EMBED_JS, null);
    }

    /** Returns true when the navigation was handled outside this WebView. */
    private boolean route(Uri uri) {
        String scheme = lower(uri.getScheme());
        if ("https".equals(scheme) || "http".equals(scheme)) {
            if (Router.isSite(uri)) {
                if ("http".equals(scheme)) {
                    loadInApp(uri.buildUpon().scheme("https").build().toString());
                    return true;
                }
                if (isAuthPath(uri)) {
                    inLoginFlow = true;
                    loginSeen = true;
                    return false;
                }
                inLoginFlow = false;
                return leaveFor(uri);
            }
            if (inLoginFlow && isTwitch(uri)) return false;
            Router.openExternal(this, uri);
            return true;
        }
        if ("intent".equals(scheme)) {
            openIntentUri(uri.toString());
            return true;
        }
        if ("about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme) || "javascript".equals(scheme)) {
            return false;
        }
        Router.openExternal(this, uri);
        return true;
    }

    /**
     * Decides whether an eblo.id link should leave the web screen: a finished login or a published
     * post returns to the native app; links to posts and profiles open native screens.
     */
    private boolean leaveFor(Uri uri) {
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (mode == MODE_LOGIN && loginSeen) {
            finishLogin();
            return true;
        }
        if (mode == MODE_NEW_POST) {
            if ("/new".equals(path) || path.startsWith("/uploaders")) return false;
            String code = Router.postCode(uri);
            if (code != null) PostActivity.start(this, code);
            finish();
            return true;
        }
        if (Router.postCode(uri) != null || path.startsWith("/@") || "/".equals(path)) {
            Router.open(this, uri);
            return true;
        }
        return false;
    }

    private void finishLogin() {
        Http.run(() -> {
            Session.refreshBlocking();
            return Boolean.TRUE;
        }, (ok, error) -> {
            if (!alive()) return;
            setResult(Session.loggedIn() ? RESULT_OK : RESULT_CANCELED);
            if (Session.loggedIn()) Ui.toast(this, "Вы вошли как " + Session.login());
            finish();
        });
    }

    private void loadInApp(String url) {
        Uri uri = Uri.parse(url);
        if (Router.isSite(uri)) {
            inLoginFlow = isAuthPath(uri);
            if (inLoginFlow) loginSeen = true;
        }
        web.loadUrl(url);
    }

    private static boolean isAuthPath(Uri uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.startsWith("/login/") || path.startsWith("/auth/");
    }

    private static boolean isTwitch(Uri uri) {
        String host = lower(uri.getHost());
        return host.equals("twitch.tv") || host.endsWith(".twitch.tv");
    }

    private void openIntentUri(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Fall back to the page's own browser URL below.
            }
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (fallback != null) {
                Uri f = Uri.parse(fallback);
                String scheme = lower(f.getScheme());
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    if (!route(f)) loadInApp(fallback);
                    return;
                }
            }
        } catch (URISyntaxException ignored) {
            // Malformed intent: URL.
        }
        Ui.toast(this, "Нет приложения, чтобы открыть эту ссылку");
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            if (newProgress >= 100) progress.setVisibility(View.GONE);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            String[] mimeTypes = mimeTypesFor(params.getAcceptTypes());
            if (mimeTypes.length == 1) {
                intent.setType(mimeTypes[0]);
            } else {
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            }
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(intent, REQUEST_FILES);
                return true;
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                Ui.toast(WebActivity.this, "Не найдено приложение для выбора файлов");
                return false;
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            fullscreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fullscreen.setVisibility(View.VISIBLE);
            setSystemBarsHidden(true);
            updateBackCallback();
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILES || filePathCallback == null) return;
        List<Uri> picked = new ArrayList<>();
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) picked.add(uri);
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
        }
        filePathCallback.onReceiveValue(picked.isEmpty() ? null : picked.toArray(new Uri[0]));
        filePathCallback = null;
    }

    /**
     * Maps the input's accept list to MIME types for the system picker. Anything that is not
     * purely image/video/audio opens the picker for all files, because document providers often
     * report archives and documents with generic types; the site validates the files itself.
     */
    static String[] mimeTypesFor(String[] acceptTypes) {
        Set<String> result = new LinkedHashSet<>();
        if (acceptTypes != null) {
            for (String entry : acceptTypes) {
                if (entry == null) continue;
                for (String raw : entry.split(",")) {
                    String type = raw.trim().toLowerCase(Locale.ROOT);
                    if (type.isEmpty()) continue;
                    String mime = type.startsWith(".")
                            ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1))
                            : type;
                    if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/")
                            || mime.startsWith("audio/"))) {
                        return new String[] {"*/*"};
                    }
                    result.add(mime);
                }
            }
        }
        return result.isEmpty() ? new String[] {"*/*"} : result.toArray(new String[0]);
    }

    // ---- fullscreen video ----

    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hidden) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (hidden) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(hidden
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : 0);
        }
        setFullBleed(hidden);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        customView = null;
        WebChromeClient.CustomViewCallback callback = customViewCallback;
        customViewCallback = null;
        setSystemBarsHidden(false);
        if (callback != null) callback.onCustomViewHidden();
        updateBackCallback();
    }

    // ---- Back ----

    @Override
    protected boolean wantsBack() {
        return customView != null || (web != null && web.canGoBack());
    }

    @Override
    protected boolean handleBack() {
        if (customView != null) {
            exitFullscreen();
            return true;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return false;
    }

    // ---- errors ----

    private void retry() {
        if (web == null) return;
        errorView.loading();
        if (failedUrl != null) loadInApp(failedUrl);
        else web.reload();
    }

    // ---- downloads ----

    private void onDownload(String url, String userAgent, String contentDisposition, String mimeType, long length) {
        Uri uri = Uri.parse(url);
        String scheme = lower(uri.getScheme());
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            Ui.toast(this, "Этот файл нельзя скачать из приложения");
            return;
        }
        download(this, url, contentDisposition, mimeType, userAgent);
    }

    /** Saves a file to Downloads (Android 10+); older versions hand it to the browser. */
    static void download(Activity a, String url, String contentDisposition, String mimeType, String userAgent) {
        Uri uri = Uri.parse(url);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Router.openExternal(a, uri);
            return;
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            request.addRequestHeader("User-Agent", userAgent != null ? userAgent : Http.userAgent());
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            request.setTitle(fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            a.getSystemService(DownloadManager.class).enqueue(request);
            Ui.toast(a, "Скачивание: " + fileName);
        } catch (RuntimeException e) {
            Ui.toast(a, "Не удалось начать скачивание");
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
