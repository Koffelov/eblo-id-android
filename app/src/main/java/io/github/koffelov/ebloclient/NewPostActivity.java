package io.github.koffelov.ebloclient;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;

/**
 * Native "new post": files from the gallery or file manager (or shared from other apps), title,
 * description, visibility and "not for stream". Uploads through the site's own chunked uploader.
 */
public class NewPostActivity extends BaseActivity {
    private static final int REQUEST_FILES = 61;
    private static final int REQUEST_GALLERY = 62;
    private static final int MAX_FILES = 10;
    private static final int TITLE_MAX = 100;
    private static final int DESCRIPTION_MAX = 1000;

    private static final class Attachment {
        Uri uri;
        String name;
        long size;
        String mime;
    }

    private final List<Attachment> attachments = new ArrayList<>();
    private final List<String> sessions = Collections.synchronizedList(new ArrayList<>());
    private Models.UploadLimits limits = new Models.UploadLimits();
    private TextView loginBanner;
    private LinearLayout attachmentList;
    private TextView attachInfo;
    private EditText title;
    private EditText description;
    private TextView accessPublic;
    private TextView accessLink;
    private Switch blur;
    private TextView publishButton;
    private LinearLayout progressBox;
    private ProgressBar progressBar;
    private TextView progressText;
    private boolean linkOnly;
    private boolean uploading;
    private volatile boolean cancelled;

    static void start(Context c) {
        c.startActivity(new Intent(c, NewPostActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addTopBar("Новый пост", true);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(32));
        scroll.addView(form);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        loginBanner = Ui.text(this, "Войдите через Twitch, чтобы опубликовать пост", 17, Ui.INK);
        loginBanner.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_twitch, 0, 0, 0);
        loginBanner.setCompoundDrawablePadding(Ui.dp(10));
        loginBanner.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.TWITCH));
        loginBanner.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        loginBanner.setBackground(Ui.ripple(Ui.round(0x339147FF, 12, 0x669147FF)));
        loginBanner.setOnClickListener(v -> WebActivity.login(this));
        form.addView(loginBanner, Ui.margins(Ui.lp(-1, -2), 0, 4, 0, 8));

        form.addView(label("Вложения"));
        attachInfo = Ui.text(this, 15, Ui.INK3);
        form.addView(attachInfo);
        attachmentList = Ui.column(this);
        form.addView(attachmentList, Ui.margins(Ui.lp(-1, -2), 0, 6, 0, 0));
        LinearLayout pickers = Ui.row(this);
        TextView gallery = pickerButton("Галерея", R.drawable.ic_album);
        gallery.setOnClickListener(v -> pickGallery());
        TextView files = pickerButton("Файлы", R.drawable.ic_file);
        files.setOnClickListener(v -> pickFiles());
        pickers.addView(gallery, Ui.lpWeight());
        pickers.addView(files, Ui.margins(new LinearLayout.LayoutParams(0, -2, 1f), 10, 0, 0, 0));
        form.addView(pickers, Ui.margins(Ui.lp(-1, -2), 0, 8, 0, 0));

        form.addView(label("Название"));
        TextView titleCounter = Ui.text(this, 14, Ui.INK3);
        title = input("Безымянный", false, TITLE_MAX, titleCounter);
        form.addView(title);
        form.addView(titleCounter);

        form.addView(label("Описание"));
        TextView descCounter = Ui.text(this, 14, Ui.INK3);
        description = input("Расскажите, что это. #теги работают", true, DESCRIPTION_MAX, descCounter);
        form.addView(description);
        form.addView(descCounter);

        form.addView(label("Кто видит пост"));
        LinearLayout access = Ui.row(this);
        accessPublic = Ui.chip(this, "Все — в ленте", true);
        accessLink = Ui.chip(this, "Только по ссылке", false);
        accessPublic.setOnClickListener(v -> setLinkOnly(false));
        accessLink.setOnClickListener(v -> setLinkOnly(true));
        access.addView(accessPublic, Ui.margins(Ui.lp(-2, -2), 0, 0, 8, 0));
        access.addView(accessLink);
        form.addView(access);

        LinearLayout blurRow = Ui.row(this);
        blurRow.setPadding(0, Ui.dp(18), 0, 0);
        LinearLayout blurText = Ui.column(this);
        blurText.addView(Ui.text(this, "Не для стрима", 19, Ui.INK));
        blurText.addView(Ui.text(this, "Превью будет скрыто, пока его не откроют", 15, Ui.INK3));
        blurRow.addView(blurText, Ui.lpWeight());
        blur = new Switch(this);
        blur.setThumbTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        blur.setTrackTintList(ColorStateList.valueOf(0x669AC43E));
        blurRow.addView(blur);
        form.addView(blurRow);

        publishButton = Ui.button(this, "Опубликовать", true);
        publishButton.setOnClickListener(v -> publish());
        form.addView(publishButton, Ui.margins(Ui.lp(-1, -2), 0, 26, 0, 0));

        progressBox = Ui.column(this);
        progressBox.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(10));
        progressBox.setBackground(Ui.round(Ui.SURFACE, 12, Ui.LINE_STRONG));
        progressText = Ui.text(this, 18, Ui.INK);
        progressBox.addView(progressText);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgressTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        progressBox.addView(progressBar, Ui.margins(Ui.lp(-1, Ui.dp(10)), 0, 10, 0, 6));
        TextView cancel = Ui.text(this, "Отменить загрузку", 17, Ui.DANGER);
        cancel.setPadding(0, Ui.dp(8), 0, Ui.dp(4));
        cancel.setOnClickListener(v -> confirmCancel());
        progressBox.addView(cancel);
        progressBox.setVisibility(View.GONE);
        form.addView(progressBox, Ui.margins(Ui.lp(-1, -2), 0, 26, 0, 0));

        updateLogin();
        renderAttachments();
        Http.run(Api::uploadLimits, (l, error) -> {
            if (l != null) {
                limits = l;
                renderAttachments();
            }
        });
        if (!Session.known()) Session.refresh(() -> {
            if (alive()) updateLogin();
        });
        acceptShared(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        acceptShared(intent);
    }

    // ---- form pieces ----

    private TextView label(String text) {
        TextView t = Ui.text(this, text, 18, Ui.INK2);
        t.setPadding(0, Ui.dp(18), 0, Ui.dp(6));
        return t;
    }

    private TextView pickerButton(String text, int icon) {
        TextView b = Ui.button(this, text, false);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        b.setCompoundDrawablePadding(Ui.dp(8));
        b.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        return b;
    }

    private EditText input(String hint, boolean multiline, int max, TextView counter) {
        EditText e = new EditText(this);
        e.setTypeface(Ui.font);
        e.setTextSize(19);
        e.setTextColor(Ui.INK);
        e.setHintTextColor(Ui.INK3);
        e.setHint(hint);
        e.setBackground(Ui.round(Ui.INPUT, 10, Ui.LINE_STRONG));
        e.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(max)});
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        counter.setGravity(Gravity.END);
        counter.setPadding(0, Ui.dp(4), Ui.dp(4), 0);
        counter.setText("0/" + max);
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                counter.setText(s.length() + "/" + max);
            }
        });
        return e;
    }

    private void setLinkOnly(boolean value) {
        linkOnly = value;
        style(accessPublic, !value);
        style(accessLink, value);
    }

    private static void style(TextView chip, boolean on) {
        chip.setTextColor(on ? 0xFF111111 : Ui.INK);
        chip.setBackground(Ui.ripple(on ? Ui.round(Ui.BRAND_LIGHT, 18, 0) : Ui.round(Ui.SURFACE, 18, Ui.LINE_STRONG)));
    }

    private void updateLogin() {
        loginBanner.setVisibility(Session.known() && !Session.loggedIn() ? View.VISIBLE : View.GONE);
    }

    // ---- picking files ----

    private void pickGallery() {
        if (!canAddMore()) return;
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            i = photoPicker(MAX_FILES - attachments.size());
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startPicker(i, REQUEST_GALLERY);
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private static Intent photoPicker(int remaining) {
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        int max = Math.min(remaining, MediaStore.getPickImagesMaxLimit());
        if (max > 1) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
        return i;
    }

    private void pickFiles() {
        if (!canAddMore()) return;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startPicker(i, REQUEST_FILES);
    }

    private void startPicker(Intent i, int request) {
        try {
            startActivityForResult(i, request);
        } catch (android.content.ActivityNotFoundException e) {
            Ui.toast(this, "Не найдено приложение для выбора файлов");
        }
    }

    private boolean canAddMore() {
        if (uploading) return false;
        if (attachments.size() >= MAX_FILES) {
            Ui.toast(this, "В пост можно добавить до " + MAX_FILES + " файлов");
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebActivity.REQUEST_LOGIN) {
            updateLogin();
            return;
        }
        if ((requestCode != REQUEST_FILES && requestCode != REQUEST_GALLERY) || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        addUris(uris);
    }

    @SuppressWarnings("deprecation")
    private void acceptShared(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && description.getText().length() == 0) description.setText(text);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (!uris.isEmpty()) addUris(uris);
    }

    private void addUris(List<Uri> uris) {
        Http.run(() -> {
            List<Attachment> found = new ArrayList<>();
            for (Uri u : uris) found.add(describe(u));
            return found;
        }, (found, error) -> {
            if (!alive() || found == null) return;
            long total = 0;
            for (Attachment a : attachments) total += a.size;
            boolean hasZip = false;
            for (Attachment a : attachments) hasZip |= a.name.toLowerCase(Locale.ROOT).endsWith(".zip");
            for (Attachment a : found) {
                String ext = extension(a.name);
                if (attachments.size() >= MAX_FILES) {
                    Ui.toast(this, "Добавлены первые " + MAX_FILES + " файлов");
                    break;
                }
                if (!limits.extensions.isEmpty() && !limits.extensions.contains(ext)) {
                    Ui.toast(this, "Сайт не принимает " + (ext.isEmpty() ? "этот формат" : "." + ext) + ": " + a.name);
                    continue;
                }
                if ("zip".equals(ext) && hasZip) {
                    Ui.toast(this, "В пост можно добавить только один ZIP");
                    continue;
                }
                if (a.size > 0 && total + a.size > limits.maxBytes) {
                    Ui.toast(this, "Слишком много: максимум " + Ui.size(limits.maxBytes) + " на пост");
                    continue;
                }
                hasZip |= "zip".equals(ext);
                total += Math.max(0, a.size);
                attachments.add(a);
            }
            renderAttachments();
        });
    }

    private Attachment describe(Uri uri) {
        Attachment a = new Attachment();
        a.uri = uri;
        a.size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) a.name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) a.size = c.getLong(s);
            }
        } catch (RuntimeException ignored) {
            // Some providers don't support queries; fall back below.
        }
        String type = getContentResolver().getType(uri);
        a.mime = type == null ? "application/octet-stream" : type;
        if (a.name == null || a.name.isEmpty()) {
            String last = uri.getLastPathSegment();
            a.name = last == null ? "file" : last;
        }
        if (extension(a.name).isEmpty()) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(a.mime);
            if (ext != null) a.name += "." + ext;
        }
        return a;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void renderAttachments() {
        attachmentList.removeAllViews();
        long total = 0;
        for (Attachment a : attachments) total += Math.max(0, a.size);
        attachInfo.setText(attachments.isEmpty()
                ? "Картинки, видео, аудио, архивы и документы. До " + MAX_FILES + " файлов и " + Ui.size(limits.maxBytes) + " на пост."
                : attachments.size() + " из " + MAX_FILES + " · " + Ui.size(total) + " из " + Ui.size(limits.maxBytes));
        for (Attachment a : new ArrayList<>(attachments)) {
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(4), Ui.dp(8));
            row.setBackground(Ui.round(Ui.SURFACE, 12, Ui.LINE_STRONG));
            FrameLayout thumbBox = new FrameLayout(this);
            thumbBox.setBackground(Ui.round(Ui.SURFACE_RAISED, 8, 0));
            Ui.clipRound(thumbBox, 8);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            ImageView kind = Ui.icon(this, a.mime.startsWith("video/") ? R.drawable.ic_play
                    : a.mime.startsWith("audio/") ? R.drawable.ic_music
                    : a.mime.startsWith("image/") ? 0 : R.drawable.ic_file, 24, Ui.INK2);
            if (a.mime.startsWith("image/")) kind.setVisibility(View.GONE);
            thumbBox.addView(kind, new FrameLayout.LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
            row.addView(thumbBox, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
            loadThumb(a, thumb, kind);
            LinearLayout text = Ui.column(this);
            text.setPadding(Ui.dp(12), 0, Ui.dp(4), 0);
            TextView name = Ui.text(this, a.name, 17, Ui.INK);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.addView(name);
            text.addView(Ui.text(this, a.size >= 0 ? Ui.size(a.size) : "размер неизвестен", 14, Ui.INK3));
            row.addView(text, Ui.lpWeight());
            ImageView remove = Ui.iconButton(this, R.drawable.ic_close, Ui.INK2, "Убрать " + a.name);
            remove.setOnClickListener(v -> {
                if (uploading) return;
                attachments.remove(a);
                renderAttachments();
            });
            row.addView(remove);
            attachmentList.addView(row, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 8));
        }
    }

    private void loadThumb(Attachment a, ImageView view, ImageView kind) {
        if (!a.mime.startsWith("image/") && !a.mime.startsWith("video/")) return;
        Http.run(() -> thumbnail(a), (bitmap, error) -> {
            if (bitmap == null || !alive()) return;
            view.setImageBitmap(bitmap);
            if (a.mime.startsWith("video/")) kind.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        });
    }

    private Bitmap thumbnail(Attachment a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return getContentResolver().loadThumbnail(a.uri, new Size(256, 256), null);
            } catch (IOException | RuntimeException ignored) {
                // Fall back to decoding below.
            }
        }
        try {
            if (a.mime.startsWith("video/")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(this, a.uri);
                    return r.getFrameAtTime();
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                        // Nothing to release.
                    }
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(a.uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Math.min(bounds.outWidth, bounds.outHeight) / 256);
            try (InputStream in = getContentResolver().openInputStream(a.uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- publishing ----

    private void publish() {
        if (uploading) return;
        if (!Session.loggedIn()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нужен вход")
                    .setMessage("Войдите через Twitch, чтобы публиковать посты.")
                    .setPositiveButton("Войти", (d, w) -> WebActivity.login(this))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        if (attachments.isEmpty()) {
            Ui.toast(this, "Добавьте хотя бы один файл");
            return;
        }
        String t = title.getText().toString().trim();
        if (t.isEmpty()) t = "Безымянный";
        final String postTitle = t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) : t;
        String d = description.getText().toString().trim();
        final String postDescription = d.length() > DESCRIPTION_MAX ? d.substring(0, DESCRIPTION_MAX) : d;
        final boolean link = linkOnly;
        final boolean hidden = blur.isChecked();
        final List<Attachment> files = new ArrayList<>(attachments);
        final String uploadId = UUID.randomUUID().toString();
        setUploading(true);
        Http.IO.execute(() -> upload(files, postTitle, postDescription, link, hidden, uploadId));
    }

    private void setUploading(boolean value) {
        uploading = value;
        cancelled = false;
        publishButton.setVisibility(value ? View.GONE : View.VISIBLE);
        progressBox.setVisibility(value ? View.VISIBLE : View.GONE);
        title.setEnabled(!value);
        description.setEnabled(!value);
        blur.setEnabled(!value);
        if (value) {
            progressText.setText("Подготовка…");
            progressBar.setProgress(0);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        updateBackCallback();
    }

    /** Runs on a background thread. */
    private void upload(List<Attachment> files, String postTitle, String postDescription, boolean link,
                        boolean hidden, String uploadId) {
        sessions.clear();
        boolean completed = false;
        try {
            for (Attachment f : files) {
                if (f.size < 0) copyToCache(f);
            }
            long total = 0;
            for (Attachment f : files) total += f.size;
            long sent = 0;
            List<String> ids = new ArrayList<>();
            for (int n = 0; n < files.size(); n++) {
                Attachment f = files.get(n);
                final String label = files.size() > 1 ? "Загрузка " + (n + 1) + " из " + files.size() : "Загрузка";
                Api.UploadSession s = call(() -> Api.createUpload(f.name, f.size, f.mime));
                sessions.add(s.id);
                try (InputStream in = getContentResolver().openInputStream(f.uri)) {
                    if (in == null) throw new IOException("Не удалось прочитать " + f.name);
                    for (int i = 0; i < s.chunks; i++) {
                        long start = i * s.chunkSize;
                        int len = (int) Math.max(0, Math.min(s.chunkSize, f.size - start));
                        final byte[] chunk = new byte[len];
                        readFully(in, chunk);
                        final int index = i;
                        call(() -> {
                            Api.putChunk(s.id, index, chunk);
                            return Boolean.TRUE;
                        });
                        sent += len;
                        progress(label, sent, total);
                    }
                }
                ids.add(s.id);
            }
            progress("Публикуем…", total, total);
            JSONObject result = call(() -> Api.completeUpload(ids, postTitle, postDescription, link, hidden, uploadId));
            completed = true;
            runOnUiThread(() -> published(result));
        } catch (CancellationException e) {
            runOnUiThread(() -> {
                if (alive()) setUploading(false);
            });
        } catch (Exception e) {
            runOnUiThread(() -> failed(e));
        } finally {
            if (!completed) {
                for (String id : new ArrayList<>(sessions)) Api.cancelUpload(id);
            }
        }
    }

    private void copyToCache(Attachment f) throws IOException {
        File tmp = new File(getCacheDir(), "upload-" + UUID.randomUUID());
        try (InputStream in = getContentResolver().openInputStream(f.uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Не удалось прочитать " + f.name);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        tmp.deleteOnExit();
        f.uri = Uri.fromFile(tmp);
        f.size = tmp.length();
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new IOException("Файл оказался короче, чем ожидалось");
            off += r;
        }
    }

    private void progress(String label, long sent, long total) {
        int permille = total <= 0 ? 1000 : (int) Math.min(1000, sent * 1000 / total);
        runOnUiThread(() -> {
            progressText.setText(label + " · " + permille / 10 + "%");
            progressBar.setProgress(permille);
        });
    }

    /** Retries network hiccups like the site's uploader and asks for the Cloudflare check when needed. */
    private <T> T call(Callable<T> work) throws Exception {
        int attempt = 0;
        int captchas = 0;
        while (true) {
            if (cancelled) throw new CancellationException();
            try {
                return work.call();
            } catch (Api.ApiException e) {
                if (e.captcha && captchas++ < 2 && askCaptcha()) continue;
                if (!e.transientError() || ++attempt >= 6) throw e;
            } catch (IOException e) {
                if (++attempt >= 6) throw e;
            }
            Thread.sleep(Math.min(30_000L, 1000L << attempt));
        }
    }

    private boolean askCaptcha() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] passed = {false};
        runOnUiThread(() -> {
            if (!alive()) {
                latch.countDown();
                return;
            }
            Turnstile.show(this, ok -> {
                passed[0] = ok;
                latch.countDown();
            });
        });
        latch.await();
        return passed[0];
    }

    private void published(JSONObject result) {
        Ui.toast(this, "Пост опубликован");
        String url = result.optString("short_url", result.optString("redirect_url", ""));
        String code = url.isEmpty() ? null : Router.postCode(Uri.parse(Http.absolute(url)));
        attachments.clear();
        if (code != null) PostActivity.start(this, code);
        else if (!url.isEmpty()) Router.open(this, url);
        finish();
    }

    private void failed(Exception e) {
        if (!alive()) return;
        setUploading(false);
        String message = Ui.errorText(this, e);
        String duplicate = null;
        if (e instanceof Api.ApiException && ((Api.ApiException) e).body != null) {
            JSONObject body = ((Api.ApiException) e).body;
            StringBuilder sb = new StringBuilder();
            JSONArray rejected = body.optJSONArray("rejected");
            if (rejected != null) {
                for (int i = 0; i < rejected.length(); i++) {
                    JSONObject r = rejected.optJSONObject(i);
                    if (r == null) continue;
                    sb.append(r.optString("filename", "файл")).append(": ").append(r.optString("reason", "отклонён")).append('\n');
                }
            }
            if ("forbidden_extension".equals(body.optString("error"))) {
                sb.append(body.optString("message", "Формат не поддерживается"));
                if (!body.optString("forbidden_ext").isEmpty()) sb.append(" (").append(body.optString("forbidden_ext")).append(")");
            }
            if (sb.length() > 0) message = sb.toString().trim();
            duplicate = body.optString("duplicate_url", null);
            if (duplicate != null && duplicate.isEmpty()) duplicate = null;
            if (duplicate != null) message = "Такой файл уже есть на сайте.";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Не удалось опубликовать")
                .setMessage(message)
                .setPositiveButton("Понятно", null);
        final String dup = duplicate;
        if (dup != null) b.setNeutralButton("Открыть", (d, w) -> Router.open(this, dup));
        b.show();
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle("Отменить загрузку?")
                .setPositiveButton("Отменить", (d, w) -> cancelled = true)
                .setNegativeButton("Продолжить", null)
                .show();
    }

    // ---- Back ----

    @Override
    protected boolean wantsBack() {
        return true;
    }

    @Override
    protected boolean handleBack() {
        if (uploading) {
            confirmCancel();
            return true;
        }
        boolean dirty = !attachments.isEmpty() || title.length() > 0 || description.length() > 0;
        if (!dirty) return false;
        new AlertDialog.Builder(this)
                .setTitle("Выйти без публикации?")
                .setMessage("Выбранные файлы и текст не сохранятся.")
                .setPositiveButton("Выйти", (d, w) -> finish())
                .setNegativeButton("Остаться", null)
                .show();
        return true;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updateBackCallback();
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }
}
