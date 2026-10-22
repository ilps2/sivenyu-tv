package io.github.op7418.sivenyutv;

import android.content.Intent;
import android.graphics.Bitmap;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 屏保内容选择页：本地图片 + 本地视频，多选，按勾选顺序播放。
 *
 * 与壁纸不同，这里不复制文件（视频动辄几个 G），只记录绝对路径；
 * 播放时若文件不存在会跳过。所以 U 盘里的视频拔掉后要在本页重新选。
 */
public final class SaverPickerActivity extends TvActivity {
    private static final int MAX_FOUND = 100;
    private static final int MAX_PICK = 40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<File> items = new ArrayList<>();
    /** 每项 {路径, 显示名, 是否视频("1"/"0"), 是否 APK 内置("1"/"0")}。 */
    private final List<String[]> entries = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final java.util.Map<String, ImageView> thumbViews = new java.util.LinkedHashMap<>();
    private final List<TextView> footerItems = new ArrayList<>();

    private LinearLayout grid;
    private TextView status;
    private ScrollView scroller;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        selected.addAll(SaverSelection.list(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(dp(56), dp(38), dp(56), dp(34));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label("屏保内容", 28, "sans-serif-light", 0xFFFFFFFF));
        status = label("正在扫描存储…", 13, "sans-serif", 0x8AFFFFFF);
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

        scan();
    }

    private View buildFooter() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(rounded(0x14FFFFFF, 30));
        bar.setPadding(dp(10), dp(7), dp(10), dp(7));
        footerItem(bar, "保存", this::save);
        footerItem(bar, "全选", () -> {
            for (String[] entry : entries) {
                if (selected.size() >= MAX_PICK) break;
                selected.add(entry[0]);
            }
            refreshChecks();
        });
        footerItem(bar, "清空", () -> {
            selected.clear();
            refreshChecks();
        });
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
        TextView item = label(name, 16, "sans-serif", 0xE6FFFFFF);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(26), dp(10), dp(26), dp(10));
        item.setFocusable(true);
        item.setId(View.generateViewId());
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
        footerItems.add(item);
        return item;
    }

    // ---------------------------------------------------------------- 扫描

    private void scan() {
        new Thread(() -> {
            final List<File> found = collect();
            final Set<String> availableVideos = new HashSet<>();
            for (File file : found) {
                if (SaverSelection.isVideo(file.getName())) {
                    availableVideos.add(file.getAbsolutePath());
                }
            }
            List<String> saved = SaverSelection.list(this);
            if (saved.removeIf(path -> isObsoleteDemoPath(path)
                    || (SaverSelection.isVideo(path) && !availableVideos.contains(path)))) {
                SaverSelection.save(this, saved);
            }
            handler.post(() -> {
                selected.removeIf(path -> isObsoleteDemoPath(path)
                        || (SaverSelection.isVideo(path) && !availableVideos.contains(path)));
                render(found);
            });
        }, "saver-scan").start();
    }

    private static boolean isObsoleteDemoPath(String path) {
        return path != null && path.replace('\\', '/').toLowerCase(Locale.ROOT)
                .endsWith("/kid_splash.mp4");
    }

    private List<File> collect() {
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
        // 统一媒体目录：壁纸和屏保共用，上传一次两边都能选
        File uploaded = MediaLibrary.dir(this);
        if (uploaded.isDirectory()) {
            roots.add(uploaded);
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
            if (name.equalsIgnoreCase("kid_splash.mp4")) {
                // 这个旧演示视频不属于壁纸库，也不再作为屏保媒体提供。
                // 删除仅命中这个确切文件名，不影响其他本地视频。
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!MediaLibrary.isMedia(lower)) continue;
            if (seen.add(file.getAbsolutePath())) out.add(file);
        }
    }

    // ---------------------------------------------------------------- 网格

    private void render(List<File> found) {
        items.clear();
        items.addAll(found);
        thumbViews.clear();
        grid.removeAllViews();
        entries.clear();

        // 先把「壁纸库」里的内置壁纸排进来，再排存储卡上的文件
        for (String[] builtinEntry : loadBuiltinWallpapers()) {
            entries.add(new String[]{builtinEntry[0], builtinEntry[1], "0", "1"});
        }
        for (File file : items) {
            String name = file.getName();
            entries.add(new String[]{file.getAbsolutePath(), name,
                    SaverSelection.isVideo(name) ? "1" : "0", "0"});
        }

        if (entries.isEmpty()) {
            status.setText("没找到图片或视频。放进 U 盘或「图片」「Download」文件夹后重进本页。");
            return;
        }
        Set<String> availablePaths = new HashSet<>();
        for (String[] entry : entries) availablePaths.add(entry[0]);
        if (selected.retainAll(availablePaths)) {
            // 清理已从壁纸库移除或已不存在的旧媒体路径，避免计数和轮播配置残留。
            SaverSelection.save(this, selected);
        }
        refreshChecks();

        LinearLayout row = null;
        List<List<View>> rows = new ArrayList<>();
        View first = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.bottomMargin = dp(10);
                grid.addView(row, rowParams);
                rows.add(new ArrayList<>());
            }
            String[] entry = entries.get(i);
            View tile = buildTile(entry[0], entry[1], "1".equals(entry[2]));
            if (first == null) first = tile;
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(200), dp(112));
            tileParams.rightMargin = dp(10);
            row.addView(tile, tileParams);
            rows.get(rows.size() - 1).add(tile);
        }

        // 焦点链：最后一行按下直接进页脚，页脚按上回到最后一行，
        // 否则 ScrollView 会把方向键吞掉，遥控器永远走不到「保存」。
        if (!rows.isEmpty() && !footerItems.isEmpty()) {
            List<View> lastRow = rows.get(rows.size() - 1);
            for (View tile : lastRow) tile.setNextFocusDownId(footerItems.get(0).getId());
            for (int i = 0; i < footerItems.size(); i++) {
                TextView button = footerItems.get(i);
                button.setNextFocusUpId(lastRow.get(Math.min(i, lastRow.size() - 1)).getId());
            }
        }
        if (!rows.isEmpty()) {
            for (View tile : rows.get(0)) tile.setNextFocusUpId(tile.getId());
        }

        if (first != null) first.requestFocus();
        loadThumbs();
    }

    /**
     * 把获得焦点的格子滚到可视区中间。
     *
     * <p>坐标必须先用 offsetDescendantRectToMyCoords 换算到 grid 坐标系：直接取
     * tile.getTop() 拿到的是「行内」的 y（每行都是 0 左右），第二行以后算出的滚动量全错，
     * 表现就是「移到第三行就看不全」。
     */
    private void scrollIntoView(View view) {
        if (scroller == null || grid == null || view == null) return;
        android.graphics.Rect rect = new android.graphics.Rect();
        view.getDrawingRect(rect);
        grid.offsetDescendantRectToMyCoords(view, rect);
        int target = rect.top - (scroller.getHeight() - rect.height()) / 2;
        scroller.smoothScrollTo(0, Math.max(0, target));
    }

    /**
     * 读 APK 内置壁纸清单（和壁纸面板同一份 catalog），返回 {asset 路径, 名字}。
     * 同时套用壁纸面板的隐藏名单：那边移除过的内置壁纸这里也不出现，
     * 顺手把它的勾选记录从屏保列表里剔掉，避免播一张已移除的壁纸。
     */
    private List<String[]> loadBuiltinWallpapers() {
        List<String[]> out = new ArrayList<>();
        final java.util.Set<String> hidden = FrameWallpapers.hiddenBuiltinIds(this);
        final List<String> gone = new ArrayList<>();
        java.io.InputStream stream = null;
        try {
            stream = FrameWallpapers.openCatalog(this);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
            org.json.JSONArray json = new org.json.JSONArray(bytes.toString("UTF-8"));
            for (int i = 0; i < json.length(); i++) {
                org.json.JSONObject item = json.getJSONObject(i);
                String id = item.optString("id", "");
                String path = "asset:wallpapers/" + item.getString("file");
                if (!id.isEmpty() && hidden.contains(id)) {
                    gone.add(path);
                    continue;
                }
                out.add(new String[]{path, item.optString("name", item.getString("file"))});
            }
        } catch (Exception unavailable) {
            android.util.Log.w("TvHome.Saver", "内置壁纸清单读取失败", unavailable);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (java.io.IOException ignored) { }
            }
        }

        if (!gone.isEmpty()) {
            List<String> kept = SaverSelection.list(this);
            if (kept.removeAll(gone)) {
                SaverSelection.save(this, kept);
                // 只剔掉被移除的那几张，本次会话里新勾选（还没保存）的保留着
                selected.removeAll(gone);
                android.util.Log.i("TvHome.Saver",
                        "内置壁纸已移除 " + gone.size() + " 张，同步清掉勾选，剩 " + kept.size() + " 项");
            }
        }
        android.util.Log.i("TvHome.Saver",
                "内置壁纸候选 " + out.size() + " 张，隐藏名单 " + hidden.size() + " 项");
        return out;
    }

    private View buildTile(String path, String label, boolean video) {
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(label);
        tile.setTag(path);

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF1B2228);
        tile.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        thumbViews.put(path, thumb);

        TextView kind = label(video ? "视频" : "图片", 11, "sans-serif-medium", 0xFFFFFFFF);
        kind.setPadding(dp(8), dp(3), dp(8), dp(3));
        kind.setBackground(rounded(video ? 0xD9C0392B : 0xD91E88E5, 8));
        FrameLayout.LayoutParams kindParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        kindParams.topMargin = dp(8);
        kindParams.leftMargin = dp(8);
        tile.addView(kind, kindParams);

        TextView check = label("✓", 20, "sans-serif-medium", 0xFFFFFFFF);
        check.setGravity(Gravity.CENTER);
        check.setBackground(rounded(0xE61E88E5, 20));
        check.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.TOP | Gravity.END);
        checkParams.topMargin = dp(8);
        checkParams.rightMargin = dp(8);
        tile.addView(check, checkParams);
        tile.setTag(R.id.tag_check, check);

        TextView name = label(label, 11, "sans-serif", 0xFFFFFFFF);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setBackground(rounded(0xB3000000, 6));
        name.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nameParams.leftMargin = dp(6);
        nameParams.rightMargin = dp(6);
        nameParams.bottomMargin = dp(6);
        tile.addView(name, nameParams);

        tile.setOnClickListener(v -> {
            if (selected.contains(path)) {
                selected.remove(path);
            } else {
                if (selected.size() >= MAX_PICK) {
                    Serif.toast(this, "最多选 " + MAX_PICK + " 项");
                    return;
                }
                selected.add(path);
            }
            refreshChecks();
        });
        tile.setOnFocusChangeListener((v, focused) -> {
            tile.setBackground(focused ? ring(0x4DFFFFFF, 12) : rounded(0x1FFFFFFF, 12));
            tile.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                    .setDuration(130).start();
            if (focused) scrollIntoView(tile);
        });
        tile.setBackground(rounded(0x1FFFFFFF, 12));
        return tile;
    }

    private void refreshChecks() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View row = grid.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout line = (LinearLayout) row;
            for (int j = 0; j < line.getChildCount(); j++) {
                View tile = line.getChildAt(j);
                Object tag = tile.getTag();
                Object check = tile.getTag(R.id.tag_check);
                if (!(tag instanceof String) || !(check instanceof View)) continue;
                ((View) check).setVisibility(selected.contains(tag) ? View.VISIBLE : View.INVISIBLE);
            }
        }
        status.setText("已选 " + selected.size() + " 项　·　共 " + entries.size() + " 项可用　·　确认键选择");
    }

    private void loadThumbs() {
        final List<String[]> targets = new ArrayList<>(entries);
        new Thread(() -> {
            for (String[] entry : targets) {
                String path = entry[0];
                boolean builtin = "1".equals(entry[3]);
                Bitmap bitmap;
                if (builtin) {
                    bitmap = decodeAsset(path.substring(6));
                } else if ("1".equals(entry[2])) {
                    bitmap = videoFrame(new File(path));
                } else {
                    bitmap = decodeImage(new File(path));
                }
                if (bitmap == null) continue;
                handler.post(() -> {
                    ImageView view = thumbViews.get(path);
                    if (view != null) view.setImageBitmap(bitmap);
                });
            }
        }, "saver-thumbs").start();
    }

    private Bitmap decodeAsset(String assetPath) {
        try (java.io.InputStream first = getAssets().open(assetPath)) {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(first, null, bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 400) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (java.io.InputStream second = getAssets().open(assetPath)) {
                return android.graphics.BitmapFactory.decodeStream(second, null, options);
            }
        } catch (Exception | OutOfMemoryError unavailable) {
            return null;
        }
    }

    private Bitmap decodeImage(File file) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 400) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    private Bitmap videoFrame(File file) {
        // 走统一实现：2 秒处没有关键帧时会退回第一帧，避免抽出来是空白
        return MediaLibrary.frame(file, 400);
    }

    private void save() {
        SaverSelection.save(this, selected);
        Serif.toast(this, selected.isEmpty()
                ? "已清空，屏保将播放内置图" : "已保存 " + selected.size() + " 项");
        setResult(RESULT_OK, new Intent());
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView label(String value, float size, String family, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(serifOf(family));
        view.setIncludeFontPadding(false);
        return view;
    }

    /** 全应用统一思源宋体：按原来的字体族语义取出对应的字重。 */
    private Typeface serifOf(String family) {
        String name = family == null ? "" : family;
        if (name.contains("light")) return Serif.light(this);
        if (name.contains("medium") || name.contains("bold")) return Serif.bold(this);
        return Serif.regular(this);
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
