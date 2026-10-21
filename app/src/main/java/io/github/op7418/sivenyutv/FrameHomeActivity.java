package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * tvOS 风格电视桌面。
 *
 * 视觉：全屏壁纸（暗化）＋ 右上角时钟 ＋ 居中的横向应用行（焦点放大、白色描边、名称上浮）＋ 底部胶囊工具条。
 * 应用行来自 PackageManager 实时扫描，不写死任何包名。
 */
public final class FrameHomeActivity extends Activity {
    private static final String TAG = "SivenyuHome";
    private static final String PREFS = "aptv-home";
    private static final String KEY_FOCUS_PKG = "focus-pkg";
    private static final String KEY_LAST_TILE = "last-tile";
    private static final String KEY_HINT_SHOWN = "hint-shown";
    private static final int REQUEST_PICK = 41;

    // 聚焦才展开：常态只有一行居中的名字，焦点落上去才亮出 5:3 的 banner 卡片。
    // 格子尺寸 = 卡片尺寸，所以展开时邻居不位移，壁纸也只在聚焦处被遮住一块。
    // 注意：卡片不做缩放动画 —— 缩放会让卡片溢出格子被裁，下缘两个圆角会变成直角。
    private static final int TILE_RADIUS = 8;
    private static final int TILE_NAME_SIZE = 22;

    private int tileW = 210;
    private int tileH = 126;
    private int tileGap = 20;
    private int perRow = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> tiles = new ArrayList<>();
    private final List<List<View>> tileRows = new ArrayList<>();
    private final List<TextView> dockItems = new ArrayList<>();

    private ImageView photo;
    private ImageView transition;
    private FrameWallpapers wallpapers;
    private LinearLayout row;
    private TextView clock;
    private TextView hint;
    private View lastTile;
    private boolean reloading;

    private FrameLayout screensaverLayer;
    private ImageView ssA;
    private ImageView ssB;
    private TextView ssClock;
    private boolean screensaverVisible;
    private boolean saverLaunching;
    private int ssIndex;
    private final java.util.concurrent.ExecutorService ssWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static boolean reasserted;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            String text = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(now));
            if (clock != null) clock.setText(text);
            if (ssClock != null) ssClock.setText(text);
            handler.postDelayed(this, 60000 - now % 60000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        applyFullscreen();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        photo = new ImageView(this);
        photo.setBackgroundColor(0xFF101820);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 图片与 view 尺寸都就绪后才能算矩阵，尺寸变化时补一次
        photo.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (wallpapers != null && (r - l != or - ol || b - t != ob - ot)) wallpapers.reapplyCrop();
        });
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));

        transition = new ImageView(this);
        transition.setScaleType(ImageView.ScaleType.CENTER_CROP);
        transition.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (wallpapers != null && (r - l != or - ol || b - t != ob - ot)) wallpapers.reapplyCrop();
        });
        transition.setAlpha(0f);
        root.addView(transition, new FrameLayout.LayoutParams(-1, -1));

        wallpapers = new FrameWallpapers(this, photo, transition);

        View scrim = new View(this);
        GradientDrawable scrimDrawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xC40A0A0A, 0x3D0A0A0A, 0x4D0A0A0A, 0xD9000000});
        scrim.setBackground(scrimDrawable);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // 图标区整体上移一点，把屏幕下半段留给 dock，两行大图标才不会顶到胶囊条
        FrameLayout.LayoutParams stageParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        stageParams.bottomMargin = dp(40);
        root.addView(buildStage(), stageParams);
        root.addView(buildDock(), dockParams());
        root.addView(buildScreensaver(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        reloadApps(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FOCUS_PKG, null), true);
        maybeReassertAfterBoot();
    }

    // ---------------------------------------------------------------- 布局

    /** 全屏：隐藏系统状态栏（右上角信号、天气、温度那一排）。 */
    private void applyFullscreen() {
        try {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } catch (RuntimeException ignored) {
            // 个别固件不允许改窗口 flag，交给下面的 setSystemUiVisibility
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private FrameLayout.LayoutParams dockParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(30);
        return params;
    }

    /** 居中横向应用行（tvOS 式大图标，无卡片底）。 */
    private View buildStage() {
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER_HORIZONTAL);
        stage.setClipChildren(false);

        // 多行网格：每行列数由 chooseGridMetrics() 按应用数决定，超出自动换行，不再横向滚动
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setClipChildren(false);
        stage.addView(row, new LinearLayout.LayoutParams(-2, -2));

        hint = label("正在读取已安装应用…", 16, Serif.regular(this), 0x99FFFFFF);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-2, -2);
        hintParams.leftMargin = dp(72);
        hintParams.topMargin = dp(18);
        hint.setVisibility(View.GONE);
        stage.addView(hint, hintParams);
        return stage;
    }

    private View buildDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        // 和图标区同一套逻辑：常态只有文字，胶囊只在获得焦点时出现
        dock.setPadding(dp(10), dp(7), dp(10), dp(7));

        addDockItem(dock, "壁纸", () -> wallpapers.show(dockItems.isEmpty() ? dock : dockItems.get(0)));
        addDockItem(dock, "应用", this::openPicker);
        addDockItem(dock, "信号源", () -> {
            if (!XiaomiControls.showInputs(this)) message("暂时无法打开信号源");
        });
        addDockItem(dock, "设置", () -> BootStart.menu(this, () -> {
            try {
                startActivity(XiaomiControls.settings(this, Settings.ACTION_SETTINGS));
            } catch (RuntimeException unavailable) {
                message("暂时无法打开设置");
            }
        }));
        return dock;
    }

    private void addDockItem(LinearLayout dock, String name, Runnable action) {
        TextView item = label(name, 17, Serif.regular(this), 0xCCFFFFFF);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(26), dp(10), dp(26), dp(10));
        item.setShadowLayer(dp(6), 0, dp(2), 0xC0000000);
        item.setFocusable(true);
        item.setId(View.generateViewId());
        item.setOnClickListener(v -> action.run());
        final android.graphics.drawable.Drawable focusedGlass = new Glass(dp(24), 0x52, 0x22);
        item.setOnFocusChangeListener((v, focused) -> {
            item.setBackground(focused ? focusedGlass : null);
            item.setTextColor(focused ? 0xFFFFFFFF : 0xCCFFFFFF);
            item.setTypeface(focused ? Serif.bold(this) : Serif.regular(this));
            item.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(150).start();
        });
        dockItems.add(item);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        dock.addView(item, params);
    }

    // ---------------------------------------------------------------- 屏保

    private View buildScreensaver() {
        screensaverLayer = new FrameLayout(this);
        screensaverLayer.setBackgroundColor(Color.BLACK);
        screensaverLayer.setVisibility(View.GONE);

        ssA = new ImageView(this);
        ssA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ssB = new ImageView(this);
        ssB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ssB.setAlpha(0f);
        screensaverLayer.addView(ssA, new FrameLayout.LayoutParams(-1, -1));
        screensaverLayer.addView(ssB, new FrameLayout.LayoutParams(-1, -1));

        ssClock = label("", 26, Serif.light(this), 0xCCFFFFFF);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        clockParams.bottomMargin = dp(54);
        clockParams.rightMargin = dp(72);
        screensaverLayer.addView(ssClock, clockParams);

        TextView hint = label("按任意键返回", 12, Serif.regular(this), 0x59FFFFFF);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        hintParams.bottomMargin = dp(58);
        hintParams.leftMargin = dp(72);
        screensaverLayer.addView(hint, hintParams);
        return screensaverLayer;
    }

    private final Runnable idleTick = () -> {
        if (!isFinishing()) showScreensaver();
    };

    private final Runnable ssTick = () -> advanceScreensaver(false);

    private void resetIdle() {
        handler.removeCallbacks(idleTick);
        int minutes = ScreenSaver.minutes(this);
        if (minutes > 0 && !screensaverVisible) handler.postDelayed(idleTick, minutes * 60_000L);
    }

    private void showScreensaver() {
        if (screensaverVisible || ScreenSaver.minutes(this) <= 0) return;
        if (SaverSelection.has(this)) {
            // 配置了本地图片/视频，交给独立屏保播放器（支持视频）
            if (saverLaunching) return;
            saverLaunching = true;
            handler.postDelayed(() -> saverLaunching = false, 3000);
            try {
                startActivity(new Intent(this, SaverActivity.class));
                return;
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "启动屏保失败，回落到壁纸轮播", unavailable);
            }
        }
        if (wallpapers == null || wallpapers.size() == 0) return;
        if (wallpapers.isPanelOpen()) return;
        screensaverVisible = true;
        ssIndex = 0;
        ssClock.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
        advanceScreensaver(true);
        screensaverLayer.setAlpha(0f);
        screensaverLayer.setVisibility(View.VISIBLE);
        screensaverLayer.animate().alpha(1f).setDuration(900).start();
    }

    private void hideScreensaver() {
        if (!screensaverVisible) return;
        screensaverVisible = false;
        handler.removeCallbacks(ssTick);
        screensaverLayer.animate().alpha(0f).setDuration(420)
                .withEndAction(() -> {
                    if (screensaverLayer != null) screensaverLayer.setVisibility(View.GONE);
                }).start();
        resetIdle();
    }

    /** 解码下一张并交叉淡入。first 为真时不重置计时器。 */
    private void advanceScreensaver(boolean first) {
        int count = wallpapers.size();
        if (count <= 0) return;
        String file = wallpapers.entryFile(ssIndex % count);
        if (file == null) return;
        ssWorker.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = wallpapers.decodeEntry(file);
            } catch (Exception | OutOfMemoryError unavailable) {
                Log.w(TAG, "屏保图片解码失败 " + file);
            }
            final Bitmap image = bitmap;
            handler.post(() -> {
                if (!screensaverVisible || image == null) {
                    if (image != null) image.recycle();
                    return;
                }
                ImageView target = ssA.getAlpha() >= 0.99f ? ssB : ssA;
                ImageView other = target == ssA ? ssB : ssA;
                target.setImageBitmap(image);
                target.setAlpha(0f);
                target.animate().alpha(1f).setDuration(1200).start();
                other.animate().alpha(0f).setDuration(1200).start();
                ssIndex++;
                handler.removeCallbacks(ssTick);
                handler.postDelayed(ssTick, first ? 20_000L : 20_000L);
            });
        });
    }

    /**
     * 注意：音量键在到达 Activity 之前就被系统消费（logcat 里是
     * vol.VolumeDialogControl 在处理），所以应用层拦不到，也没法自绘音量条。
     */
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (screensaverVisible) {
                hideScreensaver();
                return true;
            }
            resetIdle();
        }
        return super.dispatchKeyEvent(event);
    }




    // ---------------------------------------------------------------- 应用列表

    private void openPicker() {
        try {
            startActivityForResult(new Intent(this, AppPickerActivity.class), REQUEST_PICK);
        } catch (RuntimeException unavailable) {
            message("打不开应用管理页");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK) {
            reloadApps(currentPackage(), false);
        } else if (requestCode == FrameWallpapers.REQUEST_LOCAL) {
            if (wallpapers != null) wallpapers.refreshUserWallpapers();
            if (resultCode == RESULT_OK) message("已导入本地壁纸，可点「壁纸」选用");
            resetIdle();
        } else if (requestCode == FrameWallpapers.REQUEST_CROP) {
            if (wallpapers != null) wallpapers.onCropResult(resultCode);
            resetIdle();
        }
    }

    private void reloadApps(String keepPackage, boolean firstLoad) {
        if (reloading) return;
        reloading = true;
        if (firstLoad && hint != null) hint.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final List<AppCatalog.Entry> apps = AppCatalog.load(getApplicationContext());
            handler.post(() -> {
                reloading = false;
                renderRow(apps, keepPackage);
            });
        }, "app-scan").start();
    }

    private void renderRow(List<AppCatalog.Entry> all, String keepPackage) {
        row.removeAllViews();
        tiles.clear();
        tileRows.clear();

        List<String> curated = HomeSelection.list(this);
        List<AppCatalog.Entry> apps = new ArrayList<>();
        if (curated == null) {
            apps.addAll(all);
        } else {
            Map<String, AppCatalog.Entry> byPkg = new HashMap<>();
            for (AppCatalog.Entry entry : all) byPkg.put(entry.pkg, entry);
            for (String pkg : curated) {
                AppCatalog.Entry entry = byPkg.get(pkg);
                if (entry != null) apps.add(entry);
            }
        }

        View focusTarget = null;
        chooseGridMetrics(apps.size());
        int rowsTotal = Math.max(1, (apps.size() + perRow - 1) / perRow);
        // 行距给「聚焦放大 1.10×」留溢出量：卡片 126dp 高，放大后上下各溢出 6dp
        int rowGap = rowsTotal >= 3 ? 16 : 26;
        LinearLayout currentRow = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % perRow == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setGravity(Gravity.CENTER_VERTICAL);
                currentRow.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) rowParams.topMargin = dp(rowGap);
                row.addView(currentRow, rowParams);
                tileRows.add(new ArrayList<>());
            }
            AppCatalog.Entry entry = apps.get(i);
            View tile = buildTile(entry);
            currentRow.addView(tile, tileParams());
            tiles.add(tile);
            tileRows.get(tileRows.size() - 1).add(tile);
            if (keepPackage != null && keepPackage.equals(entry.pkg)) focusTarget = tile;
        }
        if (focusTarget == null && !tiles.isEmpty()) focusTarget = tiles.get(0);
        lastTile = focusTarget;
        wireFocus();
        // 嵌套布局里首次布局完成前 requestFocus 会失败，延后到布局稳定后再试一次
        final View target = focusTarget;
        row.postDelayed(() -> {
            if (target == null || isFinishing()) return;
            if (!target.isFocused()) target.requestFocus();
            updateTileDimming();
        }, 160);

        updateHint(all.isEmpty(), apps.isEmpty());
    }

    /**
     * 按应用数量选网格：≤6 个正好两行三列的大图标（常用 6 个的场景），
     * 再多就退到四列、五列并缩小，避免超过两行把屏幕撑爆。
     */
    private void chooseGridMetrics(int count) {
        if (count <= 6) {
            perRow = 3; tileW = 210; tileH = 126; tileGap = 20;
        } else if (count <= 12) {
            perRow = 4; tileW = 160; tileH = 96; tileGap = 14;
        } else {
            perRow = 5; tileW = 130; tileH = 78; tileGap = 12;
        }
    }

    private void updateHint(boolean catalogEmpty, boolean visibleEmpty) {
        if (catalogEmpty) {
            showHint("没有找到可启动的应用", false);
            return;
        }
        if (visibleEmpty) {
            showHint("首页还没有应用，点底部「应用」挑选要显示的", false);
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_HINT_SHOWN, false)) {
            hint.setVisibility(View.GONE);
            return;
        }
        prefs.edit().putBoolean(KEY_HINT_SHOWN, true).apply();
        showHint("点底部「应用」可以挑选首页显示哪些应用", true);
    }

    private void showHint(String text, boolean autoHide) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        if (autoHide) handler.postDelayed(hideHint, 8000L);
    }

    private final Runnable hideHint = () -> {
        if (hint != null) hint.setVisibility(View.GONE);
    };

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(tileW), dp(tileH));
        params.rightMargin = dp(tileGap);
        return params;
    }

    /**
     * 聚焦才展开：常态格子只有一个居中的名字，焦点落上去才把 banner 卡片亮出来。
     * 卡片固定 210×126dp（5:3，和原来的 banner 一模一样），所以展开/收起时布局完全不跳动。
     * 没有 banner 的 App 走 AppTileArt 的兜底（深色主色底 + 图标居中）。
     */
    private View buildTile(AppCatalog.Entry entry) {
        final int artWidth = dp(tileW);
        final int artHeight = dp(tileH);
        final int radius = dp(TILE_RADIUS);
        final Bitmap cardBitmap = AppTileArt.render(entry, artWidth, artHeight, radius);

        FrameLayout item = new FrameLayout(this);
        item.setFocusable(true);
        item.setClipChildren(false);
        item.setId(View.generateViewId());
        item.setContentDescription(entry.label + " · " + entry.pkg);

        // 卡片用自绘 View 而不是 ImageView：圆角由 onDraw 里的 drawRoundRect 直接画，
        // 走 ImageView 时位图的下缘两个圆角会被吃掉（上圆下直），换成绘制层就没这问题。
        final View art = new View(this) {
            private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path cardPath = new Path();

            {
                cardPaint.setFilterBitmap(true);
                if (cardBitmap != null) {
                    cardPaint.setShader(new BitmapShader(cardBitmap,
                            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                } else {
                    cardPaint.setColor(0xFF1B2228);
                }
            }

            @Override protected void onDraw(Canvas canvas) {
                // 用 Path 而不是 drawRoundRect：这台机器上 drawRoundRect 的下半圆角渲染不出来
                cardPath.reset();
                cardPath.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius,
                        Path.Direction.CW);
                canvas.drawPath(cardPath, cardPaint);
            }
        };
        // 卡片只在获得焦点时出现
        art.setAlpha(0f);
        item.addView(art, new FrameLayout.LayoutParams(artWidth, artHeight, Gravity.CENTER));

        final TextView name = label(entry.label, TILE_NAME_SIZE, Serif.regular(this), 0xFFFFFFFF);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(6), 0, dp(2), 0xC0000000);
        item.addView(name, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        item.setOnFocusChangeListener((v, focused) -> {
            art.animate().alpha(focused ? 1f : 0f).setDuration(170).start();
            name.animate().alpha(focused ? 0f : 1f).setDuration(150).start();
            // 这里不再缩放 item：scale 和下面的 alpha 共用同一个 ViewPropertyAnimator，
            // alpha 一来就把 scale 打断在中间值，卡片下缘溢出格子被裁成直边（上圆下直）。
            // 焦点反馈靠「亮色卡片整体浮现 + 其余压暗」，反而更干净。
            if (focused) {
                lastTile = v;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_FOCUS_PKG, entry.pkg).apply();
            }
            v.post(this::updateTileDimming);
        });
        item.setOnClickListener(v -> {
            if (!AppCatalog.launch(this, entry.pkg)) message(entry.label + " 打不开");
        });
        item.setOnLongClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + entry.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException unavailable) {
                message("没有应用信息页面");
            }
            return true;
        });
        return item;
    }

    private void wireFocus() {
        if (dockItems.isEmpty() || tileRows.isEmpty()) return;
        int dockFirst = dockItems.get(0).getId();
        for (int r = 0; r < tileRows.size(); r++) {
            List<View> current = tileRows.get(r);
            List<View> above = r > 0 ? tileRows.get(r - 1) : null;
            List<View> below = r + 1 < tileRows.size() ? tileRows.get(r + 1) : null;
            for (int c = 0; c < current.size(); c++) {
                View tile = current.get(c);
                tile.setNextFocusUpId(above == null ? dockFirst : idAt(above, c));
                tile.setNextFocusDownId(below == null ? dockFirst : idAt(below, c));
            }
        }
        View backTo = lastTile != null ? lastTile : tiles.get(0);
        for (TextView dock : dockItems) {
            dock.setNextFocusUpId(backTo.getId());
            dock.setNextFocusDownId(backTo.getId());
        }
        // 左右到边界就停住，否则系统会自动找「最近的」可控件，从最左边按左会跳回上面的图标
        if (!dockItems.isEmpty()) {
            TextView first = dockItems.get(0);
            TextView last = dockItems.get(dockItems.size() - 1);
            first.setNextFocusLeftId(first.getId());
            last.setNextFocusRightId(last.getId());
        }
    }

    /** 取某一行第 index 个图标的 id，越界就落到该行最后一个。 */
    private int idAt(List<View> rowViews, int index) {
        int clamped = Math.max(0, Math.min(index, rowViews.size() - 1));
        return rowViews.get(clamped).getId();
    }

    /** 有焦点时压暗其余格子，强化「唯一焦点」的视觉。只压到 0.88，别把文字压没了。 */
    private void updateTileDimming() {
        boolean anyFocused = false;
        for (View tile : tiles) {
            if (tile.isFocused()) {
                anyFocused = true;
                break;
            }
        }
        for (View tile : tiles) {
            float target = (!anyFocused || tile.isFocused()) ? 1f : 0.88f;
            tile.animate().alpha(target).setDuration(170).start();
        }
    }

    private String currentPackage() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FOCUS_PKG, null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 生命周期

    private void maybeReassertAfterBoot() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(BootStart.EXTRA_BOOT_LAUNCH, false)) return;
        intent.removeExtra(BootStart.EXTRA_BOOT_LAUNCH);
        if (reasserted) return;
        reasserted = true;
        // 部分固件会在我们之后把系统桌面顶到前台，这里再用一次 CLEAR_TOP 把自己提到最前（singleTask，无闪烁）。
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            try {
                startActivity(new Intent(this, FrameHomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                Log.i(TAG, "开机后重申前台");
            } catch (RuntimeException blocked) {
                Log.w(TAG, "重申前台失败", blocked);
            }
        }, 9000L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) intent.removeExtra(BootStart.EXTRA_BOOT_LAUNCH);
        // 作为默认桌面时按 Home 键会走到这里：把焦点放回应用行，而不是停在工具条上
        View target = lastTile != null ? lastTile : (tiles.isEmpty() ? null : tiles.get(0));
        if (target != null) target.requestFocus();
        resetIdle();
    }

    @Override protected void onResume() {
        super.onResume();
        BootStart.confirm(this);
        applyFullscreen();
        if (clock != null) {
            handler.removeCallbacks(tick);
            tick.run();
        }
        if (wallpapers != null) wallpapers.resume();
        resetIdle();
    }

    /**
     * 窗口真正拿到焦点后再指定焦点视图。放在 onResume / post 里都会
     * 被系统默认的「第一个可聚焦控件」（也就是底部工具条的壁纸）抢走。
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        View target = lastTile != null ? lastTile : (tiles.isEmpty() ? null : tiles.get(0));
        if (target != null && !target.isFocused()) target.requestFocus();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(idleTick);
        if (wallpapers != null) wallpapers.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        ssWorker.shutdownNow();
        if (wallpapers != null) wallpapers.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (isDefaultHome()) {
            // 已是默认桌面，返回键不退出，避免黑屏。静默处理，不弹提示打扰。
            return;
        }
        super.onBackPressed();
    }

    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && resolved.activityInfo != null
                    && getPackageName().equals(resolved.activityInfo.packageName);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        if (wallpapers != null) wallpapers.dump(writer);
        BootStart.dump(this, writer);
        writer.println("Home: tiles=" + tiles.size() + " defaultHome=" + isDefaultHome());
    }

    // ---------------------------------------------------------------- 小工具

    private void message(String value) {
        Serif.toast(this, value);
    }

    private TextView label(String value, float size, Typeface face, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        view.setShadowLayer(dp(1), 0, dp(1), 0x59000000);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
