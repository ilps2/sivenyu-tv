package io.github.op7418.sivenyutv;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地媒体选择页。
 *
 * 电视上没有可靠的系统文件选择器，所以自己扫描机身存储与 U 盘的媒体目录，做成缩略图网格多选。
 * 导入时统一处理：图片按 EXIF 方向摆正、缩到最长边 1920、转存 JPEG；视频原样转存。
 * 文件都落到应用私有的统一媒体目录 {@code files/media/}，壁纸和屏保共用。
 */
public final class WallpaperSourceActivity extends TvActivity {
    private static final int MAX_FOUND = 120;
    private static final int MAX_PICK = 15;
    private static final int REQ_STORAGE = 91;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<File> images = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final java.util.Map<String, ImageView> thumbViews = new java.util.LinkedHashMap<>();

    private LinearLayout grid;
    private TextView status;
    private TextView action;
    private ScrollView scroller;
    private boolean importing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(dp(56), dp(38), dp(56), dp(34));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label("选择本地媒体", 28, Serif.light(this), 0xFFFFFFFF));
        status = label("正在扫描存储…", 13, Serif.regular(this), 0x8AFFFFFF);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.topMargin = dp(6);
        texts.addView(status, statusParams);
        header.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.bottomMargin = dp(16);
        root.addView(header, headerParams);

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(grid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(buildFooter());
        setContentView(root);

        askPermissionThenScan();
    }

    private View buildFooter() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(rounded(0x14FFFFFF, 30));
        bar.setPadding(dp(10), dp(7), dp(10), dp(7));

        TextView save = footerItem(bar, "保存并应用", this::importSelected);
        action = save;
        footerItem(bar, "取消", () -> finish());

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER);
        wrapper.addView(bar);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        wrapper.setLayoutParams(params);
        return wrapper;
    }

    private TextView footerItem(LinearLayout bar, String name, Runnable action) {
        TextView item = label(name, 16, Serif.regular(this), 0xE6FFFFFF);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(26), dp(10), dp(26), dp(10));
        item.setFocusable(true);
        item.setOnClickListener(v -> action.run());
        item.setOnFocusChangeListener((v, focused) -> {
            item.setBackground(focused ? rounded(0x3DFFFFFF, 24) : null);
            item.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(130).start();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        bar.addView(item, params);
        return item;
    }

    // ---------------------------------------------------------------- 扫描

    private void askPermissionThenScan() {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            scan();
            return;
        }
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            scan();
        } else {
            status.setText("没有存储权限，无法读取本地媒体。可在系统设置里授予后重进本页。");
        }
    }

    private void scan() {
        new Thread(() -> {
            final List<File> found = collectImages();
            handler.post(() -> render(found));
        }, "wallpaper-scan").start();
    }

    private List<File> collectImages() {
        List<File> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<File> roots = new ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) {
            roots.add(new File(primary, "Pictures"));
            roots.add(new File(primary, "DCIM"));
            roots.add(new File(primary, "Download"));
            roots.add(new File(primary, "Movies"));
            roots.add(primary);
        }
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File volume : volumes) {
                String name = volume.getName();
                if (name.equals("emulated") || name.equals("self")) continue;
                roots.add(volume);
            }
        }
        for (File root : roots) walk(root, 0, found, seen);
        found.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return found;
    }

    private void walk(File dir, int depth, List<File> out, Set<String> seen) {
        if (dir == null || depth > 3 || out.size() >= MAX_FOUND) return;
        File[] files;
        try {
            files = dir.listFiles();
        } catch (SecurityException denied) {
            return;
        }
        if (files == null) return;
        for (File file : files) {
            if (out.size() >= MAX_FOUND) return;
            String name = file.getName();
            if (name.startsWith(".")) continue;
            if (file.isDirectory()) {
                if (name.equals("Android") || name.equals("LOST.DIR") || name.equals("thumbnails")) continue;
                walk(file, depth + 1, out, seen);
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!MediaLibrary.isMedia(lower)) continue;
            if (seen.add(file.getAbsolutePath())) out.add(file);
            if (out.size() >= MAX_FOUND) return;
        }
    }

    // ---------------------------------------------------------------- 网格

    private void render(List<File> found) {
        images.clear();
        images.addAll(found);
        thumbViews.clear();
        grid.removeAllViews();

        if (images.isEmpty()) {
            status.setText("没找到图片或视频。把媒体放进 U 盘根目录或「图片」「Movies」文件夹后重进本页。");
            return;
        }
        status.setText("找到 " + images.size() + " 个文件　·　确认键选择，最多 " + MAX_PICK + " 张");

        LinearLayout row = null;
        int perRow = 4;
        View first = null;
        for (int i = 0; i < images.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.bottomMargin = dp(12);
                grid.addView(row, rowParams);
            }
            View tile = buildTile(images.get(i));
            if (first == null) first = tile;
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(202), dp(140));
            tileParams.rightMargin = dp(12);
            row.addView(tile, tileParams);
        }
        if (first != null) first.requestFocus();
        loadThumbs();
    }

    private View buildTile(File file) {
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setBackground(rounded(0x1FFFFFFF, 12));
        tile.setContentDescription(file.getName());

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF1B2228);
        tile.addView(thumb, new FrameLayout.LayoutParams(-1, -1));

        TextView check = label("✓", 20, Serif.bold(this), 0xFFFFFFFF);
        check.setGravity(Gravity.CENTER);
        check.setBackground(rounded(0xE61E88E5, 20));
        check.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.TOP | Gravity.END);
        checkParams.topMargin = dp(8);
        checkParams.rightMargin = dp(8);
        tile.addView(check, checkParams);

        TextView name = label(file.getName(), 11, Serif.regular(this), 0xFFFFFFFF);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setBackground(rounded(0xB3000000, 6));
        name.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nameParams.leftMargin = dp(6);
        nameParams.rightMargin = dp(6);
        nameParams.bottomMargin = dp(6);
        tile.addView(name, nameParams);

        tile.setTag(check);
        thumbViews.put(file.getAbsolutePath(), thumb);
        tile.setOnClickListener(v -> toggle(file, tile, check));
        tile.setOnFocusChangeListener((v, focused) -> {
            tile.setBackground(focused ? ring(0x4DFFFFFF, 12) : rounded(0x1FFFFFFF, 12));
            tile.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                    .setDuration(130).start();
            if (focused) {
                int top = tile.getTop() - (scroller.getHeight() - tile.getHeight()) / 2;
                scroller.smoothScrollTo(0, Math.max(0, top));
            }
        });
        return tile;
    }

    private void loadThumbs() {
        final List<File> targets = new ArrayList<>(images);
        new Thread(() -> {
            for (File file : targets) {
                final Bitmap bitmap = decodeThumb(file);
                if (bitmap == null) continue;
                handler.post(() -> attachThumb(file, bitmap));
            }
        }, "wallpaper-thumbs").start();
    }

    private void attachThumb(File file, Bitmap bitmap) {
        ImageView thumb = thumbViews.get(file.getAbsolutePath());
        if (thumb != null) thumb.setImageBitmap(bitmap);
    }

    private Bitmap decodeThumb(File file) {
        if (MediaLibrary.isVideo(file.getName())) {
            return MediaLibrary.frame(file, 400);
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 400) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    private void toggle(File file, View tile, TextView check) {
        String key = file.getAbsolutePath();
        if (selected.contains(key)) {
            selected.remove(key);
        } else {
            if (selected.size() >= MAX_PICK) {
                Serif.toast(this, "最多选 " + MAX_PICK + " 个");
                return;
            }
            selected.add(key);
        }
        check.setVisibility(selected.contains(key) ? View.VISIBLE : View.INVISIBLE);
        tile.setBackground(tile.isFocused() ? ring(0x4DFFFFFF, 12) : rounded(0x1FFFFFFF, 12));
        status.setText("已选 " + selected.size() + " 个　·　确认键选择，最多 " + MAX_PICK + " 张");
    }

    // ---------------------------------------------------------------- 导入

    private void importSelected() {
        if (importing) return;
        if (selected.isEmpty()) {
            Serif.toast(this, "还没选文件");
            return;
        }
        importing = true;
        status.setText("正在导入 0/" + selected.size());
        final List<File> sources = new ArrayList<>();
        for (String path : selected) {
            File file = new File(path);
            if (file.isFile()) sources.add(file);
        }
        new Thread(() -> {
            File dir = MediaLibrary.dir(this);
            if (!dir.isDirectory()) dir.mkdirs();
            int ok = 0;
            for (int i = 0; i < sources.size(); i++) {
                final int done = i + 1;
                handler.post(() -> status.setText("正在导入 " + done + "/" + sources.size()));
                if (store(sources.get(i), dir)) ok++;
            }
            final int saved = ok;
            handler.post(() -> {
                importing = false;
                if (saved == 0) {
                    status.setText("导入失败，可能文件已损坏或格式不支持");
                    return;
                }
                Serif.toast(this, "已导入 " + saved + " 个，回桌面可在壁纸设置里选用");
                setResult(RESULT_OK);
                finish();
            });
        }, "wallpaper-import").start();
    }

    /** 摆正 + 缩边 + 转存 JPEG；视频原样复制，不重编码。 */
    private boolean store(File source, File dir) {
        if (MediaLibrary.isVideo(source.getName())) return storeVideo(source, dir);
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0) return false;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 2400) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (bitmap == null) return false;

            int rotation = exifRotation(source.getAbsolutePath());
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated;
                }
            }
            if (bitmap.getWidth() > 1920) {
                int height = Math.max(1, Math.round(bitmap.getHeight() * 1920f / bitmap.getWidth()));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 1920, height, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            String name = "local-" + System.currentTimeMillis() + "-" + Math.abs(source.getName().hashCode()) + ".jpg";
            File target = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(target)) {
                return bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
        } catch (Exception | OutOfMemoryError failed) {
            android.util.Log.w("SivenyuWallpaper", "import failed " + source, failed);
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** 视频不做重编码，直接按字节复制到统一媒体目录。 */
    private boolean storeVideo(File source, File dir) {
        String name = "local-" + System.currentTimeMillis() + "-"
                + Math.abs(source.getName().hashCode()) + "-" + source.getName();
        File target = MediaLibrary.unique(dir, name);
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            android.util.Log.i("SivenyuWallpaper", "导入视频 " + target.getName()
                    + " " + target.length() + " 字节");
            return true;
        } catch (Exception failed) {
            android.util.Log.w("SivenyuWallpaper", "视频导入失败 " + source, failed);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        }
    }

    private int exifRotation(String path) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(path);
            int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception | OutOfMemoryError unavailable) {
            return 0;
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView label(String value, float size, Typeface face, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable ring(int color, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(2), 0xFFFFFFFF);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
