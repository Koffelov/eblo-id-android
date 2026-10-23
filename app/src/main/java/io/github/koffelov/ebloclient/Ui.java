package io.github.koffelov.ebloclient;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The app's look: eblo.id's palette, its Handjet font, and view helpers. */
final class Ui {
    // From the site's CSS variables.
    static final int BG = 0xFF202020;
    static final int SURFACE = 0xFF262626;
    static final int SURFACE_RAISED = 0xFF151515;
    static final int INPUT = 0xFF181A1B;
    static final int INK = 0xFFEBEBEB;
    static final int INK2 = 0xFF989898;
    static final int INK3 = 0xFF828282;
    static final int BRAND = 0xFF789D2A;
    static final int BRAND_LIGHT = 0xFF9AC43E;
    static final int ACCENT = 0xFF86BE08;
    static final int LINE = 0x1AFFFFFF;
    static final int LINE_STRONG = 0x33FFFFFF;
    static final int DANGER = 0xFFEF9282;
    static final int TWITCH = 0xFF9147FF;

    private static final String[] MONTHS = {"янв", "фев", "мар", "апр", "мая", "июн", "июл",
            "авг", "сен", "окт", "ноя", "дек"};

    static Typeface font = Typeface.DEFAULT_BOLD;
    private static float density = 1f;

    private Ui() {
    }

    static void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        try {
            // Handjet by Rosetta, SIL Open Font License (assets/licenses/Handjet-OFL.txt).
            font = Typeface.createFromAsset(context.getAssets(), "fonts/handjet.ttf");
        } catch (RuntimeException ignored) {
            font = Typeface.DEFAULT_BOLD;
        }
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    // ---- views ----

    static TextView text(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.08f);
        return t;
    }

    static TextView text(Context c, CharSequence value, float sp, int color) {
        TextView t = text(c, sp, color);
        t.setText(value);
        return t;
    }

    static ImageView icon(Context c, int res, int sizeDp, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    static ImageView iconButton(Context c, int res, int tint, String description) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(description);
        v.setBackground(rippleOval());
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return v;
    }

    static GradientDrawable round(int color, float radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(Math.max(1, dp(1)), strokeColor);
        return d;
    }

    static Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content,
                content == null ? new GradientDrawable() {{ setColor(Color.WHITE); }} : content);
    }

    static Drawable rippleOval() {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask);
    }

    static void clipCircle(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setClipToOutline(true);
    }

    static void clipRound(View v, float radiusDp) {
        final int r = dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    static ImageView avatar(Context c, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackground(round(0xFF333333, sizeDp / 2f, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        clipCircle(v);
        return v;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 18, primary ? 0xFF111111 : INK);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setMinHeight(dp(44));
        b.setBackground(ripple(primary ? round(BRAND_LIGHT, 8, 0) : round(SURFACE, 8, LINE_STRONG)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static TextView twitchButton(Context c, String label) {
        TextView b = text(c, label, 18, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setMinHeight(dp(48));
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_twitch, 0);
        b.setCompoundDrawablePadding(dp(10));
        b.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        b.setBackground(ripple(round(TWITCH, 10, 0)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 16, selected ? 0xFF111111 : INK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        t.setBackground(ripple(selected ? round(BRAND_LIGHT, 18, 0) : round(SURFACE, 18, LINE_STRONG)));
        t.setClickable(true);
        return t;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpWeight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /** "icon + number" pair used for views, likes and comments. */
    static LinearLayout stat(Context c, int iconRes, String value) {
        LinearLayout l = row(c);
        l.addView(icon(c, iconRes, 18, INK3));
        TextView t = text(c, value, 17, INK2);
        t.setPadding(dp(5), 0, dp(14), 0);
        l.addView(t);
        return l;
    }

    static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
    }

    // ---- formatting (matches the site) ----

    static String count(long n) {
        if (n >= 1_000_000) return trim(n / 1_000_000.0) + "M";
        if (n >= 1_000) return trim(n / 1_000.0) + "k";
        return String.valueOf(n);
    }

    private static String trim(double v) {
        String s = v % 1 == 0 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s.replace('.', ',');
    }

    static String time(long epochSeconds) {
        if (epochSeconds <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(epochSeconds * 1000L);
        String hm = String.format(Locale.ROOT, "%02d:%02d", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE));
        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(t, now)) return "сегодня в " + hm;
        if (sameDay(t, yesterday)) return "вчера в " + hm;
        String date = t.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[t.get(Calendar.MONTH)];
        if (t.get(Calendar.YEAR) != now.get(Calendar.YEAR)) return date + " " + t.get(Calendar.YEAR);
        return date + " в " + hm;
    }

    static String duration(int seconds) {
        if (seconds <= 0) return "";
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return trim(bytes / 1024.0) + " КБ";
        return trim(bytes / (1024.0 * 1024.0)) + " МБ";
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    // ---- text with links, #tags and @users ----

    private static final Pattern LINKS = Pattern.compile(
            "(https?://[^\\s<>\"]+)|(?<![\\w#])#([\\p{L}\\p{N}_]{1,64})|(?<![\\w@])@([A-Za-z0-9_]{2,32})");

    static void setRichText(TextView view, String text, Activity activity) {
        if (text == null) text = "";
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        Matcher m = LINKS.matcher(text);
        boolean any = false;
        while (m.find()) {
            final Uri target;
            if (m.group(1) != null) {
                String url = m.group(1).replaceAll("[).,!?:;]+$", "");
                target = Uri.parse(url);
                sb.setSpan(link(activity, target), m.start(), m.start() + url.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                target = Uri.parse(Http.BASE + "/?tag=" + Uri.encode(m.group(2)));
                sb.setSpan(link(activity, target), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                target = Uri.parse(Http.BASE + "/@" + m.group(3));
                sb.setSpan(link(activity, target), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            any = true;
        }
        view.setText(sb);
        if (any) {
            view.setMovementMethod(LinkMovementMethod.getInstance());
            view.setHighlightColor(0x33FFFFFF);
        }
    }

    private static ClickableSpan link(Activity activity, Uri target) {
        return new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                Router.open(activity, target);
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setColor(BRAND_LIGHT);
                ds.setUnderlineText(false);
            }
        };
    }

    // ---- errors ----

    static boolean online(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    static String errorText(Context c, Exception e) {
        if (!online(c)) return "Нет подключения к интернету";
        if (e instanceof UnknownHostException) return "Нет подключения к интернету";
        if (e instanceof SocketTimeoutException) return "Сайт долго не отвечает";
        if (e instanceof Api.ApiException) {
            Api.ApiException a = (Api.ApiException) e;
            if (a.captcha) return "Сайт просит пройти проверку «я не робот»";
            if (a.code == 429) return "Слишком много запросов, попробуйте позже";
            if (a.code >= 500) return "Сайт временно недоступен";
            if (!TextUtils.isEmpty(a.getMessage())) return a.getMessage();
        }
        return "Не удалось загрузить данные";
    }
}
