package io.github.koffelov.ebloclient;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Insets;
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
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Unofficial eblo.id client: a thin shell around the site itself.
 *
 * eblo.id has no public API for the feed, profiles, posting or login, so the app shows the real
 * site and only adds what a browser tab would otherwise provide: Twitch login inside the same
 * cookie jar, the Android file picker, downloads, fullscreen video, back navigation and an
 * offline screen.
 */
public class MainActivity extends Activity {
    private static final String HOME_URL = "https://eblo.id/";
    private static final String SITE_HOST = "eblo.id";
    private static final int REQUEST_FILES = 1;
    private static final String PREFS = "app";
    private static final String PREF_NOTICE_SHOWN = "notice_shown_v1";

    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private View errorView;
    private TextView errorTitle;
    private TextView errorText;
    private TextView errorDetail;
    private FrameLayout fullscreen;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;

    /** True while the Twitch OAuth flow started from eblo.id is running inside the WebView. */
    private boolean inLoginFlow;
    private boolean mainFrameFailed;
    private boolean offlineError;
    private String failedUrl;

    private ConnectivityManager.NetworkCallback networkCallback;
    private Object backCallback;
    private boolean backCallbackRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.error);
        errorTitle = findViewById(R.id.error_title);
        errorText = findViewById(R.id.error_text);
        errorDetail = findViewById(R.id.error_detail);
        fullscreen = findViewById(R.id.fullscreen);
        findViewById(R.id.retry).setOnClickListener(v -> retry());

        setUpWindowInsets();
        setUpWebView();

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(startUrl(getIntent()));
        }
        showNoticeOnce();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (web != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            web.loadUrl(startUrl(intent));
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
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---- Window, keyboard and fullscreen ----

    private void setUpWindowInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // The window is not edge-to-edge here and adjustResize handles the keyboard.
            return;
        }
        // Edge-to-edge is enforced on Android 15+, so the bars and the keyboard are applied as
        // padding: the page never hides under the status bar, the navigation bar or the IME.
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (customView != null) {
                v.setPadding(0, 0, 0, 0);
            } else {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            }
            return WindowInsets.CONSUMED;
        });
    }

    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hidden) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (hidden) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(hidden
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : 0);
        }
        root.requestApplyInsets();
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

    // ---- WebView ----

    @SuppressLint("SetJavaScriptEnabled")
    private void setUpWebView() {
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web.setBackgroundColor(getColor(R.color.site_background));

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
            if (isSiteUri(uri) && !isAuthPath(uri)) inLoginFlow = false;
            progress.setProgress(0);
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            if (!mainFrameFailed) errorView.setVisibility(View.GONE);
            CookieManager.getInstance().flush();
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
            showError(request.getUrl().toString(), error.getErrorCode(),
                    String.valueOf(error.getDescription()));
        }

        @Override
        @TargetApi(Build.VERSION_CODES.O)
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page renderer crashed or was killed for memory: rebuild the screen instead of
            // letting the whole app crash.
            if (view == web) {
                root.removeView(web);
                web.destroy();
                web = null;
                recreate();
            }
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            if (newProgress >= 100) progress.setVisibility(View.GONE);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
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
                toast(getString(R.string.no_file_picker));
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
            fullscreen.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

    // ---- Navigation ----

    private String startUrl(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && "https".equals(lower(data.getScheme())) && isSiteUri(data)) {
            return data.toString();
        }
        return HOME_URL;
    }

    /** Returns true when the navigation was handled outside of the WebView. */
    private boolean route(Uri uri) {
        String scheme = lower(uri.getScheme());
        if ("https".equals(scheme) || "http".equals(scheme)) {
            if (isSiteUri(uri)) {
                if ("http".equals(scheme)) {
                    web.loadUrl(uri.buildUpon().scheme("https").build().toString());
                    return true;
                }
                // Login must finish in this WebView: Twitch redirects back to eblo.id, and the
                // session cookie has to land in this cookie jar, not in an external browser.
                inLoginFlow = isAuthPath(uri);
                return false;
            }
            if (inLoginFlow && isTwitchUri(uri)) return false;
            openExternally(uri);
            return true;
        }
        if ("intent".equals(scheme)) {
            openIntentUri(uri.toString());
            return true;
        }
        if ("about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)
                || "javascript".equals(scheme)) {
            return false;
        }
        openExternally(uri); // mailto:, tg:, tel:, market: and other apps
        return true;
    }

    private static boolean isSiteUri(Uri uri) {
        String host = lower(uri.getHost());
        return host.equals(SITE_HOST) || host.endsWith("." + SITE_HOST);
    }

    private static boolean isAuthPath(Uri uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.startsWith("/login/") || path.startsWith("/auth/");
    }

    private static boolean isTwitchUri(Uri uri) {
        String host = lower(uri.getHost());
        return host.equals("twitch.tv") || host.endsWith(".twitch.tv");
    }

    private void openExternally(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_app_for_link));
        }
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
                Uri fallbackUri = Uri.parse(fallback);
                String scheme = lower(fallbackUri.getScheme());
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    if (!route(fallbackUri)) web.loadUrl(fallback);
                    return;
                }
            }
        } catch (URISyntaxException ignored) {
            // Malformed intent: URL.
        }
        toast(getString(R.string.no_app_for_link));
    }

    // ---- Back ----

    private boolean handleBack() {
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

    /** Android 13+: intercept Back only while there is somewhere to go back to in the app. */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        boolean needed = customView != null || (web != null && web.canGoBack());
        if (needed == backCallbackRegistered) return;
        updateBackCallbackApi33(needed);
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void updateBackCallbackApi33(boolean needed) {
        if (backCallback == null) {
            backCallback = (OnBackInvokedCallback) () -> {
                if (!handleBack()) moveTaskToBack(true);
                updateBackCallback();
            };
        }
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (needed) {
            dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
        } else {
            dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
        }
        backCallbackRegistered = needed;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Android 12 and older; newer versions use the OnBackInvokedCallback above.
        if (!handleBack()) super.onBackPressed();
    }

    // ---- Errors and offline ----

    private void showError(String url, int code, String description) {
        failedUrl = url;
        offlineError = !isOnline();
        errorTitle.setText(offlineError ? R.string.error_offline_title : R.string.error_generic_title);
        errorText.setText(offlineError ? R.string.error_offline_text : R.string.error_generic_text);
        errorDetail.setText(description);
        errorView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
    }

    private void retry() {
        if (web == null) return;
        // The error screen stays up (with the progress bar over it) until the page loads.
        if (failedUrl != null) {
            web.loadUrl(failedUrl);
        } else {
            web.reload();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ---- Downloads ----

    private void onDownload(String url, String userAgent, String contentDisposition,
                            String mimeType, long contentLength) {
        Uri uri = Uri.parse(url);
        String scheme = lower(uri.getScheme());
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            toast(getString(R.string.download_unsupported));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Saving to the public Downloads folder needs a storage permission before Android 10.
            openExternally(uri);
            return;
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            request.setTitle(fileName);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = getSystemService(DownloadManager.class);
            dm.enqueue(request);
            toast(getString(R.string.download_started, fileName));
        } catch (RuntimeException e) {
            toast(getString(R.string.download_failed));
        }
    }

    // ---- Misc ----

    private void showNoticeOnce() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_NOTICE_SHOWN, false)) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.notice_title)
                .setMessage(R.string.notice_text)
                .setCancelable(false)
                .setPositiveButton(R.string.notice_ok, (dialog, which) ->
                        prefs.edit().putBoolean(PREF_NOTICE_SHOWN, true).apply())
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
