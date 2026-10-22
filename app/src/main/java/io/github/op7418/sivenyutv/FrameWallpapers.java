package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bundled wallpapers: bounded decoding, saved choice, foreground-only rotation. */
final class FrameWallpapers {
    private static final class Entry {
        final String id, name, file, thumb;
        /** 视频壁纸：不能走 ImageView 矩阵裁剪，改由播放器铺满裁切。 */
        final boolean video;

        Entry(JSONObject json) throws org.json.JSONException {
            id = json.getString("id"); name = json.getString("name");
            file = json.getString("file"); thumb = json.getString("thumb");
            video = json.optBoolean("video", false);
        }

        Entry(String id, String name, String file, String thumb, boolean video) {
            this.id = id; this.name = name; this.file = file; this.thumb = thumb;
            this.video = video;
        }
    }
    private final Activity activity;
    private final ImageView photo, transition;
    private final WallpaperVideo video;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /**
     * 抽视频帧单独一条线程。
     *
     * <p>4K 视频抽一帧在电视上要十几秒，如果和壁纸解码共用 worker，
     * 面板里给视频格子抽缩略图就会把整条队列堵死——用户点图片时 decode 排不上队，
     * 表现正是「点了图片换不过去」。
     */
    private final ExecutorService frames = Executors.newSingleThreadExecutor();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> catalog = new ArrayList<>();
    private final List<TextView> captions = new ArrayList<>();
    private final List<ImageView> thumbs = new ArrayList<>();
    private boolean rotating, active, loading, destroyed, hasImage;
    private int current, minutes, generation;
    private Dialog dialog;
    private View returnFocusAnchor;
    private View cropAnchor;
    private UploadServer uploadServer;
    private int uploadSession;
    private Dialog uploadDialog;
    private TextView uploadStatus;
    private AlertDialog intervalDialog;
    private Runnable wallpaperChanged;
    private TextView autoButton, fixedButton, intervalButton, localButton, uploadButton, cropButton;

    private static final String KEY_HIDDEN = "hidden-entries";
    static final int REQUEST_LOCAL = 77;
    static final int REQUEST_CROP = 78;
    private final Runnable rotate = () -> load(WallpaperCycle.next(current, entries.size()));

    FrameWallpapers(Activity activity, ImageView photo, ImageView transition, WallpaperVideo video) {
        this.activity = activity; this.photo = photo; this.transition = transition;
        this.video = video;
        MediaLibrary.migrate(activity);
        prefs = activity.getSharedPreferences("frame-wallpapers", Activity.MODE_PRIVATE);
        rotating = prefs.getBoolean("rotating", true);
        minutes = WallpaperCycle.minutes(prefs.getInt("minutes", WallpaperCycle.DEFAULT_MINUTES));
        try (InputStream stream = openCatalog(activity)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
            JSONArray json = new JSONArray(bytes.toString("UTF-8"));
            if (json.length() > 40) throw new IllegalArgumentException("At most 40 wallpapers");
            for (int i = 0; i < json.length(); i++) catalog.add(new Entry(json.getJSONObject(i)));
        } catch (Exception error) {
            android.util.Log.e("TvHome.Wallpaper", "Catalog unavailable", error);
        }
        rebuildEntries();
        String id = prefs.getString("selected", "");
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(id)) current = i;
        if (entries.isEmpty()) {
            String[] names = {"暮色", "松林", "暖砂", "深海", "紫夜"};
            for (int i = 0; i < names.length; i++) {
                try {
                    entries.add(new Entry(new JSONObject().put("id", "builtin-" + i)
                            .put("name", names[i]).put("file", "builtin:" + i)
                            .put("thumb", "builtin:" + i)));
                } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(id)) current = i;
        }
        load(current);
    }

    static InputStream openCatalog(Context context) throws java.io.IOException {
        try { return context.getAssets().open("wallpapers/local/catalog.json"); }
        catch (java.io.FileNotFoundException absent) {
            return context.getAssets().open("wallpapers/catalog.json");
        }
    }

    /** 解码壁纸；静态是为了让「画面位置」页复用同一套采样规则。 */
    static Bitmap decode(Activity activity, String file, boolean thumbnail) throws Exception {
        if (file.startsWith("builtin:")) {
            int[][] palettes = {{0xFF172B3B, 0xFFB78060}, {0xFF152C28, 0xFF6D8B68},
                    {0xFF3C302B, 0xFFC6A376}, {0xFF0B2238, 0xFF397E92}, {0xFF24203A, 0xFF866B8D}};
            int index = Integer.parseInt(file.substring(8));
            Bitmap image = Bitmap.createBitmap(thumbnail ? 320 : 1920, thumbnail ? 180 : 1080,
                    Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(image);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setShader(new android.graphics.LinearGradient(0, image.getHeight(), image.getWidth(), 0,
                    palettes[index], null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, image.getWidth(), image.getHeight(), paint);
            return image;
        }
        if (file.startsWith("user:")) {
            java.io.File target = new java.io.File(userDir(activity), file.substring(5));
            if (!target.isFile()) throw new java.io.IOException("Missing local wallpaper");
            // 视频没有位图，用抽出来的首帧代替（面板缩略图、屏保预览、裁剪页都用它）
            if (MediaLibrary.isVideo(target.getName())) {
                android.graphics.Bitmap frame = MediaLibrary.frame(target, thumbnail ? 360 : 1920);
                if (frame == null) throw new java.io.IOException("Video frame unavailable");
                return frame;
            }
            final String path = target.getAbsolutePath();
            // 手机竖拍照片通常是「横着存 + EXIF 写 90°」，不摆正就会被当成横图，上下也就没得调
            int rotation = exifRotation(path);
            boolean turned = rotation == 90 || rotation == 270;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int pictureWidth = turned ? bounds.outHeight : bounds.outWidth;
            int pictureHeight = turned ? bounds.outWidth : bounds.outHeight;
            // 竖图要保证长边够长（铺满 1920 宽后还要上下平移），横图保证宽度够 1920 即可
            int limit = thumbnail ? 360 : (pictureHeight > pictureWidth ? 2560 : 1920);
            int longest = Math.max(pictureWidth, pictureHeight);
            int sample = 1;
            while (longest > 0 && longest / sample > limit && sample < 8) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap image = BitmapFactory.decodeFile(path, options);
            if (image == null) throw new java.io.IOException("Image unavailable");
            if (rotation != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotation);
                Bitmap upright = Bitmap.createBitmap(image, 0, 0,
                        image.getWidth(), image.getHeight(), matrix, true);
                if (upright != image) {
                    image.recycle();
                    image = upright;
                }
                android.util.Log.i("TvHome.Wallpaper", "EXIF 摆正 " + rotation + "° "
                        + image.getWidth() + "x" + image.getHeight() + " " + target.getName());
            }
            return image;
        }
        return decodeAsset(activity, "wallpapers/" + file, thumbnail);
    }

    /** 壁纸与屏保共用内置图片采样，防止 4K 源文件占用过多位图内存。 */
    static Bitmap decodeAsset(Context context, String assetPath, boolean thumbnail) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = context.getAssets().open(assetPath)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int limit = thumbnail ? 360 : 1920;
        int sample = 1;
        while (longest > 0 && longest / sample > limit && sample < 8) sample *= 2;
        try (InputStream stream = context.getAssets().open(assetPath)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap image = BitmapFactory.decodeStream(stream, null, options);
            if (image == null) throw new java.io.IOException("Image unavailable");
            return image;
        }
    }

    /** 读 EXIF 的旋转角度；PNG/WebP 没有 EXIF 段，返回 0。 */
    static int exifRotation(String path) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(path);
            switch (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception | OutOfMemoryError unusable) {
            return 0;
        }
    }

    /** 统一媒体目录：图片壁纸和屏保共用 files/media/。 */
    java.io.File userDir() { return MediaLibrary.dir(activity); }

    static java.io.File userDir(Activity activity) { return MediaLibrary.dir(activity); }

    /** 内置壁纸的隐藏名单：内置图打包在 APK 里删不掉文件，用名单把它们从列表移除。 */
    private java.util.Set<String> hiddenIds() {
        return new java.util.HashSet<>(prefs.getStringSet(KEY_HIDDEN,
                java.util.Collections.<String>emptySet()));
    }

    /**
     * 同一个隐藏名单给屏保内容页读：在壁纸面板里移除过的内置壁纸，
     * 屏保候选里也一起消失（否则两边不一致）。
     */
    static java.util.Set<String> hiddenBuiltinIds(Context context) {
        return new java.util.HashSet<>(context
                .getSharedPreferences("frame-wallpapers", Context.MODE_PRIVATE)
                .getStringSet(KEY_HIDDEN, java.util.Collections.<String>emptySet()));
    }

    private void hideEntry(Entry entry) {
        java.util.Set<String> hidden = hiddenIds();
        hidden.add(entry.id);
        //noinspection ResultOfMethodCallIgnored
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        android.util.Log.i("TvHome.Wallpaper", "隐藏内置壁纸 " + entry.id + " 隐藏数=" + hidden.size());
    }

    /** 按 catalog + 隐藏名单 + 本地图片重建列表。 */
    private void rebuildEntries() {
        entries.clear();
        final java.util.Set<String> hidden = hiddenIds();
        for (Entry entry : catalog) if (!hidden.contains(entry.id)) entries.add(entry);
        loadUserEntries();
    }

    /**
     * 把统一媒体目录里的内容挂到 entries 末尾。
     *
     * <p>壁纸库和屏保库看的是同一个 {@code files/media/}，所以图片和视频都收进来：
     * 传一次，壁纸和屏保都能选。视频的缩略图走抽帧。
     */
    private void loadUserEntries() {
        entries.removeIf(entry -> entry.id.startsWith("user-"));
        List<MediaLibrary.Item> media = MediaLibrary.listMedia(userDir());
        int added = 0;
        for (MediaLibrary.Item item : media) {
            if (added >= 15) break;
            String name = item.name;
            entries.add(new Entry("user-" + name, name.replaceAll("\\.[^.]+$", ""),
                    "user:" + name, "user:" + name, item.video));
            added++;
        }
    }

    /** 本地图片增删后刷新列表，保持当前选中项。 */
    void refreshUserWallpapers() {
        String selectedId = entries.isEmpty() ? "" : entries.get(current).id;
        rebuildEntries();
        current = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(selectedId)) current = i;
        refreshLabels();
        schedule();
    }

    int size() { return entries.size(); }

    void setOnWallpaperChanged(Runnable listener) { wallpaperChanged = listener; }

    private void notifyWallpaperChanged() {
        if (!destroyed && wallpaperChanged != null) wallpaperChanged.run();
    }

    String entryFile(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index).file : null;
    }

    Bitmap decodeEntry(String file) throws Exception {
        return decode(activity, file, false);
    }

    boolean isPanelOpen() {
        return dialog != null && dialog.isShowing();
    }

    private void load(int index) {
        if (destroyed || entries.isEmpty()) return;
        handler.removeCallbacks(rotate);
        final int request = ++generation;
        final Entry entry = entries.get(index);
        final String entryId = entry.id;
        final float[] crop = WallpaperCrop.read(activity, entryId);
        loading = true;
        if (entry.video) {
            loadVideo(index, entry, request);
            return;
        }
        // 切到图片时立刻停掉视频层：解码要几十到几百毫秒，
        // 这期间如果视频还盖在屏幕上，用户看到的就是「点了图片没反应」。
        video.stop();
        worker.execute(() -> {
            Bitmap bitmap = null;
            try { bitmap = decode(activity, entries.get(index).file, false); }
            catch (Exception error) { android.util.Log.e("TvHome.Wallpaper", "Decode failed", error); }
            final Bitmap image = bitmap;
            handler.post(() -> {
                if (destroyed || request != generation) {
                    if (image != null) image.recycle();
                    return;
                }
                loading = false;
                if (image == null) {
                    Serif.toast(activity, "壁纸加载失败，保留当前图片");
                    schedule();
                    return;
                }
                video.stop();
                transition.animate().cancel();
                // 切图前先把过渡层彻底摘干净：它曾经因为动画被中断而残留旧图，
                // 盖在 photo 上表现为「点了图片换不过去」。
                transition.setImageDrawable(null);
                transition.setAlpha(0f);
                transition.setVisibility(View.GONE);
                if (!hasImage || !active) {
                    photo.setImageBitmap(image);
                    WallpaperCrop.apply(photo, crop);
                    notifyWallpaperChanged();
                } else {
                    // 新图先落到 photo 上再淡入：即使属性动画被系统掐断，图也已经换过了
                    photo.setImageBitmap(image);
                    WallpaperCrop.apply(photo, crop);
                    photo.setAlpha(0f);
                    photo.animate().alpha(1f).setDuration(400).start();
                    handler.postDelayed(() -> {
                        if (destroyed) return;
                        photo.setAlpha(1f);
                        notifyWallpaperChanged();
                    }, 420);
                }
                hasImage = true;
                current = index;
                prefs.edit().putString("selected", entries.get(current).id).apply();
                refreshLabels();
                android.util.Log.i("TvHome.Wallpaper", "Applied " + entries.get(current).name
                        + " rotating=" + rotating + " minutes=" + minutes);
                schedule();
            });
        });
    }

    /**
     * 视频壁纸：播放器铺满裁切，图片层留一张首帧做背景与玻璃取样。
     */
    private void loadVideo(int index, Entry entry, int request) {
        final java.io.File file = new java.io.File(userDir(), entry.file.substring(5));
        if (!file.isFile()) {
            loading = false;
            android.util.Log.w("TvHome.Wallpaper", "视频缺失 " + file.getAbsolutePath());
            Serif.toast(activity, "视频文件不存在，跳过");
            schedule();
            return;
        }
        // 先起播再抽帧：4K 视频抽一帧在真机上要十几秒，等它会让「点了没反应」。
        // 首帧只用来给玻璃 dock 做静态取样，晚一点刷上去无所谓。
        loading = false;
        transition.animate().cancel();
        transition.setImageDrawable(null);
        transition.setAlpha(0f);
        transition.setVisibility(View.GONE);
        photo.setAlpha(1f);
        hasImage = true;
        current = index;
        prefs.edit().putString("selected", entry.id).apply();
        video.play(file.getAbsolutePath());
        notifyWallpaperChanged();
        refreshLabels();
        android.util.Log.i("TvHome.Wallpaper", "Applied video " + entry.name
                + " rotating=" + rotating + " minutes=" + minutes);
        schedule();
        // 这里不再抽首帧：播放层铺满整个屏幕把 photo 完全盖住，首帧看不见；
        // 玻璃 dock 取样优先取播放中的实时帧。抽帧还会长时间独占单线程 worker，
        // 导致「视频 → 图片」时图片解码排不上队，表现就是点了图片换不过去。
    }

    /**
     * 把图片层恢复成普通居中裁切。
     *
     * <p>{@code setImageMatrix} 只在 MATRIX 模式下合法，直接调用会抛
     * {@code IllegalStateException}，所以必须先切回 MATRIX 清矩阵再换模式。
     */
    private void resetPhotoScale() {
        photo.setScaleType(ImageView.ScaleType.MATRIX);
        photo.setImageMatrix(null);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    /** 构图参数变了，或者 view 刚布局完，重新套一次矩阵。 */
    void reapplyCrop() {        if (entries.isEmpty()) return;
        if (current < entries.size() && entries.get(current).video) {
            // 视频由播放器自己铺满裁切，图片层只是首帧占位，不用套矩阵
            resetPhotoScale();
            return;
        }
        float[] crop = WallpaperCrop.read(activity, entries.get(current).id);
        WallpaperCrop.apply(photo, crop);
        WallpaperCrop.apply(transition, crop);
        notifyWallpaperChanged();
    }

    /** 当前选中的是视频壁纸就继续播（面板关闭、从后台回来时用）。 */
    private void resumeWallpaperVideo() {
        if (destroyed || entries.isEmpty() || current < 0 || current >= entries.size()) return;
        Entry entry = entries.get(current);
        if (!entry.video) return;
        java.io.File file = new java.io.File(userDir(), entry.file.substring(5));
        if (file.isFile()) video.play(file.getAbsolutePath());
    }

    private void schedule() {
        handler.removeCallbacks(rotate);
        if (active && !destroyed && !loading && rotating && entries.size() > 1
                && (dialog == null || !dialog.isShowing()))
            handler.postDelayed(rotate, minutes * 60_000L);
    }

    void resume() {
        active = true;
        // 面板期间可能被 stop 掉了，这里重新起播（不同路径时 play 会重开）
        resumeWallpaperVideo();
        video.resume();
        schedule();
    }
    void pause() {
        active = false;
        handler.removeCallbacks(rotate);
        video.pause();
        if (intervalDialog != null) intervalDialog.dismiss();
        if (dialog != null) dialog.dismiss();
    }
    void destroy() {
        destroyed = true;
        wallpaperChanged = null;
        stopUploadServer();
        generation++;
        pause();
        video.release();
        transition.animate().cancel();
        worker.shutdownNow();
        frames.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    private void setMode(boolean enabled) {
        rotating = enabled;
        prefs.edit().putBoolean("rotating", enabled).apply();
        refreshLabels();
        schedule();
    }

    void show(View returnFocus) {
        if (entries.isEmpty()) return;
        returnFocusAnchor = returnFocus;
        if (dialog != null && dialog.isShowing()) {
            // 曾经出现「isShowing=true 但窗口已经不可见」的残留状态：后续每次点壁纸
            // 都在这里直接 return，面板再也打不开。这种情况先清掉重来。
            View decor = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
            if (decor != null && decor.isShown()) return;
            try { dialog.dismiss(); } catch (RuntimeException ignored) { }
            dialog = null;
        }
        // 面板打开期间一定要停掉视频层：TextureView 在播时窗口焦点会被拽回 Activity 主窗口，
        // Dialog 收不到按键也收不到触摸，整个面板就「点不动」了。
        video.stop();
        handler.removeCallbacks(rotate);
        captions.clear();
        thumbs.clear();
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(20), dp(28), dp(18));
        panel.setBackground(surface(0xF51C2025, false));
        TextView title = label("壁纸设置", 24);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        // 第一行：轮播模式
        autoButton = button("", () -> setMode(true));
        fixedButton = button("", () -> setMode(false));
        intervalButton = button("", this::chooseInterval);
        panel.addView(buttonRow(autoButton, fixedButton, intervalButton),
                new LinearLayout.LayoutParams(-1, -2));
        // 第二行：操作按钮，和模式按钮同一套尺寸，不再单独挂在标题/说明行右边
        cropButton = button("⇅ 画面位置", this::openCrop);
        localButton = button("＋ 本地媒体", this::pickLocalImages);
        uploadButton = button("手机上传", this::showUploadDialog);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(8);
        panel.addView(buttonRow(cropButton, localButton, uploadButton), actionsParams);
        TextView note = label("点一下立即使用；视频会静音循环播放，长按格子可移除", 12);
        note.setTextColor(0xB3FFFFFF);
        note.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, dp(30));
        noteParams.topMargin = dp(6);
        panel.addView(note, noteParams);
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(5);
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(4), dp(4), dp(4), dp(3));
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setId(View.generateViewId());
            tile.setBackground(surface(0x18000000, false));
            tile.setContentDescription(entries.get(i).name);
            tile.setOnFocusChangeListener((v, focus) -> tile.setBackground(surface(0x18FFFFFF, focus)));
            // 长按（遥控器按住确认键约 0.5 秒）删除本地壁纸
            final boolean[] longPressed = {false};
            tile.setOnKeyListener((v, keyCode, event) -> {
                boolean enter = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
                if (enter && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 1) {
                    longPressed[0] = true;
                    confirmDelete(index);
                    return true;
                }
                return false;
            });
            tile.setOnLongClickListener(v -> { confirmDelete(index); return true; });
            tile.setOnClickListener(v -> {
                if (longPressed[0]) { longPressed[0] = false; return; }
                setMode(false); load(index);
            });
            ImageView thumb = new ImageView(activity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (entries.get(i).video) {
                // 抽帧要走 MediaMetadataRetriever，别在主线程上卡住面板；
                // 也不能占壁纸解码的那条队列，否则点了图片要等抽帧跑完才换
                thumb.setBackgroundColor(0xFF30343A);
                final int slot = i;
                frames.execute(() -> {
                    android.graphics.Bitmap frame = null;
                    try { frame = MediaLibrary.frame(new java.io.File(userDir(),
                            entries.get(slot).file.substring(5)), 360); }
                    catch (Exception | OutOfMemoryError unavailable) {
                        android.util.Log.w("TvHome.Wallpaper", "缩略图抽帧失败", unavailable);
                    }
                    final android.graphics.Bitmap result = frame;
                    handler.post(() -> {
                        if (destroyed || result == null) return;
                        if (slot < thumbs.size() && thumbs.get(slot) != null) {
                            thumbs.get(slot).setImageBitmap(result);
                        }
                    });
                });
            } else {
                try { thumb.setImageBitmap(decode(activity, entries.get(i).thumb, true)); }
                catch (Exception unavailable) { thumb.setBackgroundColor(0xFF30343A); }
            }
            thumbs.add(thumb);
            tile.addView(thumb, new LinearLayout.LayoutParams(-1, dp(64)));
            TextView caption = label("", 12);
            caption.setGravity(Gravity.CENTER);
            tile.addView(caption, new LinearLayout.LayoutParams(-1, dp(20)));
            captions.add(caption);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            // 5 列必须留出余量：面板内容宽 784dp，5×(142+6)=740dp。
            // 之前写 148+8=156dp×5=780dp，刚好卡在 784dp 边界上，
            // GridLayout 会判「列约束不一致」并永久丢弃一列约束，格子重叠错位，
            // 点击落到重叠的格子上——表现就是「点图片换不过去」。
            p.width = dp(142); p.height = dp(96);
            p.rightMargin = dp(6); p.bottomMargin = dp(6);
            grid.addView(tile, p);
            tiles.add(tile);
        }
        for (int i = 0; i < tiles.size(); i++) {
            View tile = tiles.get(i);
            tile.setNextFocusLeftId(tiles.get(i % 5 == 0 ? i : i - 1).getId());
            tile.setNextFocusRightId(tiles.get(i % 5 == 4 || i == tiles.size() - 1 ? i : i + 1).getId());
            tile.setNextFocusUpId(i < 5 ? actionFor(i).getId() : tiles.get(i - 5).getId());
            tile.setNextFocusDownId(tiles.get(Math.min(i + 5, tiles.size() - 1)).getId());
        }
        // 第一行：模式三连
        autoButton.setNextFocusUpId(autoButton.getId());
        autoButton.setNextFocusLeftId(autoButton.getId());
        autoButton.setNextFocusRightId(fixedButton.getId());
        autoButton.setNextFocusDownId(cropButton.getId());
        fixedButton.setNextFocusUpId(fixedButton.getId());
        fixedButton.setNextFocusLeftId(autoButton.getId());
        fixedButton.setNextFocusRightId(intervalButton.getId());
        fixedButton.setNextFocusDownId(localButton.getId());
        intervalButton.setNextFocusUpId(intervalButton.getId());
        intervalButton.setNextFocusLeftId(fixedButton.getId());
        intervalButton.setNextFocusRightId(intervalButton.getId());
        intervalButton.setNextFocusDownId(uploadButton.getId());
        // 第二行：三等分，与上方模式按钮逐列对齐。
        cropButton.setNextFocusUpId(autoButton.getId());
        cropButton.setNextFocusLeftId(cropButton.getId());
        cropButton.setNextFocusRightId(localButton.getId());
        cropButton.setNextFocusDownId(tiles.get(0).getId());
        localButton.setNextFocusUpId(fixedButton.getId());
        localButton.setNextFocusLeftId(cropButton.getId());
        localButton.setNextFocusRightId(uploadButton.getId());
        localButton.setNextFocusDownId(tiles.get(Math.min(2, tiles.size() - 1)).getId());
        uploadButton.setNextFocusUpId(intervalButton.getId());
        uploadButton.setNextFocusLeftId(localButton.getId());
        uploadButton.setNextFocusRightId(uploadButton.getId());
        uploadButton.setNextFocusDownId(tiles.get(Math.min(4, tiles.size() - 1)).getId());
        panel.addView(grid);
        // 内置 5 张 + 本地最多 15 张，固定高度会溢出 1080p 屏，这里改成可滚动
        android.widget.ScrollView gridScroll = new android.widget.ScrollView(activity);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setClipToPadding(false);
        panel.removeView(grid);
        gridScroll.addView(grid, new android.widget.ScrollView.LayoutParams(-1, -2));
        // 高度按「两行刚好放下」给（2×(96+6)=204dp），再多靠滚动。
        // 面板整体必须留在 1080 屏内，否则底部会超出屏幕、那一片收不到点击。
        panel.addView(gridScroll, new LinearLayout.LayoutParams(-1, dp(215)));
        dialog.setContentView(panel);
        dialog.setOnDismissListener(d -> {
            captions.clear();
            thumbs.clear();
            if (returnFocus != null) returnFocus.requestFocus();
            // 面板关掉后把视频壁纸接上（选中的是视频的话）
            resumeWallpaperVideo();
            schedule();
        });
        refreshLabels();
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(dp(840), -2);
        final TextView initial = rotating ? autoButton : fixedButton;
        initial.requestFocus();
        // 面板重建（删壁纸后 dismiss 再 show）时首次聚焦常常落空，补一次并留日志
        dialog.getWindow().getDecorView().postDelayed(() -> {
            if (dialog == null || !dialog.isShowing()) return;
            Window window = dialog.getWindow();
            View decor = window.getDecorView();
            // 视频壁纸刚停掉时 Activity 窗口会 relayout 把窗口焦点抢回去，
            // 结果面板画出来了却收不到按键/触摸。这里重新把 FLAG_NOT_FOCUSABLE 清一遍，
            // 强制 WindowManager 把焦点还给 Dialog。
            window.setFlags(0, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            decor.setFocusableInTouchMode(true);
            decor.requestFocus();
            boolean ok = decor.findFocus() == null && initial.requestFocus();
            android.util.Log.i("TvHome.Wallpaper", "补焦点 shown=" + initial.isShown()
                    + " windowFocus=" + decor.hasWindowFocus()
                    + " retried=" + ok
                    + " now=" + decor.findFocus());
        }, 250);
    }

    /** 手机扫码上传：临时起一个局域网 HTTP 服务，二维码里就是地址。 */
    private void showUploadDialog() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();

        final String ip = UploadServer.localIp();
        if (ip == null) {
            Serif.toast(activity, "没找到局域网地址，先确认电视连上了 Wi-Fi");
            return;
        }
        final int port = 8080;
        final java.io.File mediaDir = MediaLibrary.dir(activity);

        stopUploadServer();
        final int session = uploadSession;
        uploadServer = new UploadServer(port, mediaDir, mediaDir, (file, video) -> handler.post(() -> {
            if (destroyed || session != uploadSession || uploadStatus == null) return;
            loadUserEntries();
            if (video) {
                uploadStatus.setText("已收到视频 " + file.getName() + "\n壁纸和屏保都能用了");
            } else {
                uploadStatus.setText("已收到图片 " + file.getName() + "\n壁纸和屏保都能直接用了");
            }
        }));
        if (!uploadServer.start()) {
            Serif.toast(activity, "端口 " + port + " 被占用，稍后再试");
            uploadServer = null;
            return;
        }
        // 地址带上本次会话的令牌：端口只监听一次，令牌随窗口关闭失效，
        // 同一局域网的其他人只输 IP 进不来。
        final String url = "http://" + ip + ":" + port + "/?t=" + uploadServer.token();

        Dialog sheet = new Dialog(activity);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(36), dp(26), dp(36), dp(22));
        panel.setBackground(surface(0xF51C2025, false));

        panel.addView(label("手机扫码上传", 22), new LinearLayout.LayoutParams(-1, dp(38)));
        TextView note = label("手机连同一个 Wi-Fi，扫下面的码；图片和视频都进同一个媒体库", 12);
        note.setTextColor(0xB3FFFFFF);
        panel.addView(note, new LinearLayout.LayoutParams(-1, dp(30)));

        Bitmap code = QrCode.encode(url, dp(280));
        if (code != null) {
            ImageView qr = new ImageView(activity);
            qr.setImageBitmap(code);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(280), dp(280));
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            qrParams.topMargin = dp(4);
            panel.addView(qr, qrParams);
        } else {
            TextView fallback = label("二维码生成失败，手动在手机浏览器输入下面的地址", 13);
            fallback.setTextColor(0xFFFFD9A0);
            panel.addView(fallback);
        }

        TextView address = label(url, 16);
        address.setTextColor(0xFFFFD9A0);
        address.setTextIsSelectable(true);
        address.setGravity(Gravity.CENTER);
        panel.addView(address, new LinearLayout.LayoutParams(-1, dp(34)));

        uploadStatus = label("等待上传…　·　手机端可长按取消单个文件", 12);
        uploadStatus.setTextColor(0x99FFFFFF);
        uploadStatus.setGravity(Gravity.CENTER);
        panel.addView(uploadStatus, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView cancel = button("长按取消本次上传", () ->
                Serif.toast(activity, "长按确认键可取消本次上传，已完成的文件保留"));
        attachUploadCancelHold(cancel);
        TextView done = button("完成", sheet::dismiss);
        LinearLayout footer = new LinearLayout(activity);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        cancelParams.rightMargin = dp(10);
        footer.addView(cancel, cancelParams);
        footer.addView(done, new LinearLayout.LayoutParams(0, dp(40), 1f));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, dp(40)));

        sheet.setContentView(panel);
        sheet.setOnDismissListener(d -> {
            uploadDialog = null;
            uploadStatus = null;
            stopUploadServer();
            schedule();
        });
        sheet.show();
        sheet.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        sheet.getWindow().setLayout(dp(460), -2);
        cancel.setNextFocusRightId(done.getId());
        done.setNextFocusLeftId(cancel.getId());
        done.requestFocus();
        uploadDialog = sheet;
    }

    /** 保留正式版已有的电视端整场取消，同时使用完整暂存与清理逻辑。 */
    private void cancelUpload() {
        if (uploadServer == null) {
            Serif.toast(activity, "上传已经关闭");
            return;
        }
        stopUploadServer();
        if (uploadStatus != null) {
            uploadStatus.setText("本次上传已取消，已完成的文件保留\n关闭窗口后可重新扫码上传");
            uploadStatus.setTextColor(0xFFFFD9A0);
        }
        Serif.toast(activity, "已取消本次上传，未完成文件会自动清理");
    }

    private void attachUploadCancelHold(TextView button) {
        final boolean[] held = {false};
        final Runnable action = () -> {
            if (button.isFocused() && button.isAttachedToWindow()) {
                held[0] = true;
                cancelUpload();
            }
        };
        button.setOnLongClickListener(v -> {
            cancelUpload();
            return true;
        });
        button.setOnKeyListener((v, key, event) -> {
            boolean confirm = key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || key == android.view.KeyEvent.KEYCODE_ENTER
                    || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (!confirm) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    handler.removeCallbacks(action);
                    held[0] = false;
                    handler.postDelayed(action, 700);
                }
                return true;
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                handler.removeCallbacks(action);
                if (!held[0] && !event.isCanceled()) button.performClick();
                held[0] = false;
                return true;
            }
            return true;
        });
        View.OnFocusChangeListener appearance = button.getOnFocusChangeListener();
        button.setOnFocusChangeListener((v, focused) -> {
            if (appearance != null) appearance.onFocusChange(v, focused);
            if (!focused) {
                handler.removeCallbacks(action);
                held[0] = false;
            }
        });
    }

    private void stopUploadServer() {
        uploadSession++;
        if (uploadServer != null) {
            uploadServer.stop();
            uploadServer = null;
        }
    }

    /** 长按壁纸：确认后删除。内置壁纸在 APK 里，不能删。 */
    private void confirmDelete(int index) {
        if (index < 0 || index >= entries.size()) return;
        final Entry entry = entries.get(index);
        if (entries.size() <= 1) {
            Serif.toast(activity, "至少保留一张壁纸");
            return;
        }
        final boolean bundled = !entry.file.startsWith("user:");
        AlertDialog confirm = new AlertDialog.Builder(activity)
                .setTitle(bundled ? "移除内置壁纸" : "删除壁纸")
                .setMessage(bundled
                        ? "「" + entry.name + "」会从壁纸和屏保列表移除，移除后无法在应用内恢复。"
                        : "「" + entry.name + "」会从应用内彻底删除，不可恢复。")
                .setPositiveButton(bundled ? "移除" : "删除", (d, w) -> deleteEntry(entry))
                .setNegativeButton("取消", null)
                .create();
        confirm.show();
        Serif.applyTo(confirm);
        if (confirm.getWindow() != null) {
            confirm.getWindow().getDecorView().post(() -> Serif.applyTo(confirm));
        }
    }

    /** 删文件（本地）/ 记入隐藏名单（内置）+ 从列表移除 + 必要时换一张当前壁纸 + 重开面板。 */
    private void deleteEntry(Entry entry) {
        final boolean bundled = !entry.file.startsWith("user:");
        boolean deleted = true;
        if (bundled) {
            hideEntry(entry);
            WallpaperCrop.clear(activity, entry.id);
        } else {
            java.io.File file = new java.io.File(userDir(), entry.file.substring(5));
            deleted = file.delete();
            WallpaperCrop.clear(activity, entry.id);
            android.util.Log.i("TvHome.Wallpaper",
                    "删除壁纸 " + entry.name + " -> " + deleted + " (" + file.getAbsolutePath() + ")");
        }

        int index = entries.indexOf(entry);
        if (index < 0) return;
        entries.remove(index);
        if (entries.isEmpty()) {
            Serif.toast(activity, "已删除，但没有可用壁纸了");
            return;
        }
        if (index == current) {
            current = 0;
            load(0);
        } else if (index < current) {
            current--;
        }
        Serif.toast(activity, bundled
                ? "已移除「" + entry.name + "」"
                : (deleted ? "已删除「" + entry.name + "」" : "删除失败，文件可能已被移除"));

        // 面板里的格子是打开时一次性铺好的，重开一次最省事
        final View anchor = returnFocusAnchor;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        handler.postDelayed(() -> {
            if (!destroyed) show(anchor);
        }, 150);
    }

    /** Both control rows share the same widths and only have gaps between buttons. */
    private LinearLayout buttonRow(TextView... buttons) {
        LinearLayout row = new LinearLayout(activity);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i < buttons.length - 1) p.rightMargin = dp(10);
            row.addView(buttons[i], p);
        }
        return row;
    }

    /** Map the five thumbnail centers to the three action buttons above them. */
    private View actionFor(int column) {
        View[] actions = {cropButton, localButton, uploadButton};
        int index = Math.min(actions.length - 1, Math.max(0, (2 * column + 1) * actions.length / 10));
        return actions[index];
    }

    /** 打开「画面位置」：给当前选中的壁纸指定电视上显示哪一段（竖图最有用）。 */
    private void openCrop() {
        if (entries.isEmpty()) return;
        final Entry entry = entries.get(current);
        if (entry.file.startsWith("builtin:")) {
            Serif.toast(activity, "纯色渐变壁纸不需要调整");
            return;
        }
        if (entry.video) {
            Serif.toast(activity, "视频壁纸由画面自动铺满，不支持手动调整");
            return;
        }
        cropAnchor = returnFocusAnchor;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        handler.postDelayed(() -> {
            if (destroyed) return;
            try {
                android.content.Intent intent = new android.content.Intent(activity, WallpaperCropActivity.class);
                intent.putExtra(WallpaperCropActivity.EXTRA_ID, entry.id);
                intent.putExtra(WallpaperCropActivity.EXTRA_FILE, entry.file);
                intent.putExtra(WallpaperCropActivity.EXTRA_LABEL, entry.name);
                activity.startActivityForResult(intent, REQUEST_CROP);
            } catch (RuntimeException unavailable) {
                Serif.toast(activity, "打不开画面位置");
                show(cropAnchor);
            }
        }, 160);
    }

    /** 从「画面位置」返回：保存了就重新套一遍矩阵，然后重开面板。 */
    void onCropResult(int resultCode) {
        cropAnchor = null;
        if (resultCode == Activity.RESULT_OK) {
            reapplyCrop();
            Serif.toast(activity, "画面位置已保存");
        }
        final View anchor = returnFocusAnchor;
        handler.postDelayed(() -> {
            if (!destroyed) show(anchor);
        }, 160);
    }

    private void pickLocalImages() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        try {
            activity.startActivityForResult(
                    new android.content.Intent(activity, WallpaperSourceActivity.class), REQUEST_LOCAL);
        } catch (RuntimeException unavailable) {
            Serif.toast(activity, "打不开本地媒体选择");
        }
    }

    private void chooseInterval() {
        String[] labels = {"1 分钟", "5 分钟", "15 分钟", "30 分钟"};
        int selected = 1;
        for (int i = 0; i < WallpaperCycle.MINUTES.length; i++) if (WallpaperCycle.MINUTES[i] == minutes) selected = i;
        intervalDialog = new AlertDialog.Builder(activity).setTitle("轮播间隔")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    minutes = WallpaperCycle.MINUTES[which];
                    prefs.edit().putInt("minutes", minutes).apply();
                    refreshLabels();
                    d.dismiss();
                    schedule();
                }).setNegativeButton("返回", null).create();
        intervalDialog.show();
        Serif.applyTo(intervalDialog);
        if (intervalDialog.getWindow() != null) {
            intervalDialog.getWindow().getDecorView().post(() -> Serif.applyTo(intervalDialog));
        }
    }

    private void refreshLabels() {
        if (autoButton == null) return;
        autoButton.setText((rotating ? "✓  " : "") + "自动轮播");
        fixedButton.setText((!rotating ? "✓  " : "") + "固定壁纸");
        intervalButton.setText("间隔：" + minutes + " 分钟");
        for (int i = 0; i < captions.size(); i++)
            captions.get(i).setText((i == current ? "✓ " : "") + entries.get(i).name);
    }

    void dump(PrintWriter out) {
        out.println("FrameWallpaper: count=" + entries.size() + " index=" + current
                + " rotating=" + rotating + " minutes=" + minutes + " active=" + active
                + " loading=" + loading + " name=" + (entries.isEmpty() ? "" : entries.get(current).name));
    }

    private TextView button(String text, Runnable action) {
        TextView view = label(text, 15);
        view.setGravity(Gravity.CENTER);
        view.setId(View.generateViewId());
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(surface(0x18FFFFFF, false));
        view.setOnFocusChangeListener((v, focus) -> view.setBackground(surface(0x28FFFFFF, focus)));
        view.setOnClickListener(v -> action.run());
        return view;
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(activity);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setTypeface(Serif.regular(activity));
        view.setIncludeFontPadding(false);
        return view;
    }
    private GradientDrawable surface(int color, boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        if (focused) drawable.setStroke(dp(2), Color.WHITE);
        return drawable;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
