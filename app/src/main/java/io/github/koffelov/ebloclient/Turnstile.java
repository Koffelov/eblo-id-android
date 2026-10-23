package io.github.koffelov.ebloclient;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * eblo.id sometimes asks for Cloudflare Turnstile ("I'm not a robot") before uploads or votes.
 * The check itself is Cloudflare's widget, so it is shown in a small dialog; the resulting token
 * is sent to the site's own /api/security/turnstile/verify, after which the action is retried.
 */
final class Turnstile {
    interface Result {
        void done(boolean passed);
    }

    private Turnstile() {
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void show(Activity a, Result result) {
        Dialog dialog = new Dialog(a);
        LinearLayout box = Ui.column(a);
        box.setBackground(Ui.round(Ui.SURFACE, 16, Ui.LINE_STRONG));
        box.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(12));
        box.addView(Ui.text(a, "Проверка безопасности", 22, Ui.INK));
        TextView hint = Ui.text(a, "Сайт просит подтвердить, что вы не бот.", 16, Ui.INK2);
        box.addView(hint, Ui.margins(Ui.lp(-1, -2), 0, 6, 0, 10));
        WebView web = new WebView(a);
        web.setBackgroundColor(Ui.SURFACE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        final boolean[] finished = {false};
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false; // Cloudflare's own frame
                Uri u = request.getUrl();
                if (!"/__app_turnstile".equals(u.getPath())) return true;
                String token = u.getQueryParameter("token");
                if (token == null) {
                    hint.setText("Проверка не загрузилась. Закройте окно и попробуйте ещё раз.");
                    return true;
                }
                hint.setText("Проверяем…");
                Http.run(() -> Api.verifyTurnstile(token), (ok, error) -> {
                    if (a.isDestroyed()) return;
                    if (error == null && Boolean.TRUE.equals(ok)) {
                        finished[0] = true;
                        dialog.dismiss();
                        result.done(true);
                    } else {
                        hint.setText("Проверка не пройдена, попробуйте ещё раз.");
                        view.reload();
                    }
                });
                return true;
            }
        });
        box.addView(web, new LinearLayout.LayoutParams(-1, Ui.dp(90)));
        TextView cancel = Ui.text(a, "Отмена", 17, Ui.INK2);
        cancel.setGravity(Gravity.END);
        cancel.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(4), Ui.dp(4));
        cancel.setOnClickListener(v -> dialog.dismiss());
        box.addView(cancel, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            dialog.getWindow().setLayout(Math.min(Ui.dp(360), a.getResources().getDisplayMetrics().widthPixels - Ui.dp(32)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(d -> {
            web.destroy();
            if (!finished[0]) result.done(false);
        });
        // Served as an eblo.id page so the widget is valid for the site's key.
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;background:#262626;display:flex;justify-content:center;align-items:center;height:100%}</style>"
                + "<script>function go(){turnstile.render('#w',{sitekey:'" + Session.turnstileKey + "',theme:'dark',"
                + "callback:function(t){location.href='https://eblo.id/__app_turnstile?token='+encodeURIComponent(t)},"
                + "'error-callback':function(){location.href='https://eblo.id/__app_turnstile?error=1'}})}</script>"
                + "<script src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=go' async defer></script>"
                + "</head><body><div id='w'></div></body></html>";
        web.loadDataWithBaseURL("https://eblo.id/", html, "text/html", "utf-8", null);
        dialog.show();
    }
}
