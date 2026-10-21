package io.github.op7418.sivenyutv;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 壁纸构图页：竖图按宽度铺满后，用上下键选择电视上显示哪一段。
 *
 * <p>全屏预览（不带图标和暗角），所见即首页效果；确认键保存，返回键放弃。
 */
public final class WallpaperCropActivity extends TvActivity {
    static final String EXTRA_FILE = "file";
    static final String EXTRA_ID = "id";
    static final String EXTRA_LABEL = "label";

    private static final float STEP_Y = 0.02f;
    private static final float STEP_ZOOM = 0.1f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageView preview;
    private TextView status;
    private TextView hint;
    private String id = "";
    private String file = "";
    private String label = "";
    private float[] crop = WallpaperCrop.defaultCrop();
    private int imageWidth, imageHeight;
    private boolean ready, saving;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        id = getIntent().getStringExtra(EXTRA_ID);
        file = getIntent().getStringExtra(EXTRA_FILE);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (id == null) id = "";
        if (file == null) file = "";
        if (label == null) label = "";
        crop = WallpaperCrop.read(this, id);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new ImageView(this);
        preview.setBackgroundColor(0xFF101820);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(40), dp(14), dp(40), dp(16));
        bar.setBackgroundColor(0xC9080B0E);

        status = label("", 15);
        status.setTextColor(0xFFFFD9A0);
        status.setLineSpacing(dp(3), 1f);
        bar.addView(status, new LinearLayout.LayoutParams(-1, dp(46)));

        hint = label("", 13);
        hint.setTextColor(0x99FFFFFF);
        bar.addView(hint, new LinearLayout.LayoutParams(-1, dp(24)));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bar, barParams);
        setContentView(root);

        updateStatus();
        loadImage();
    }

    private void loadImage() {
        final String target = file;
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = FrameWallpapers.decode(this, target, false);
            } catch (Exception error) {
                android.util.Log.e("TvHome.Wallpaper", "Crop preview decode failed", error);
            }
            final Bitmap image = bitmap;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (image != null) image.recycle();
                    return;
                }
                if (image == null) {
                    Serif.toast(this, "图片读取失败");
                    finish();
                    return;
                }
                imageWidth = image.getWidth();
                imageHeight = image.getHeight();
                preview.setImageBitmap(image);
                preview.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    applyCrop();
                    updateStatus();
                });
                ready = true;
                applyCrop();
                updateStatus();
            });
        }, "crop-preview").start();
    }

    private void applyCrop() {
        if (!ready) return;
        if (!WallpaperCrop.apply(preview, crop)) preview.post(this::applyCrop);
    }

    private int[] overflow() {
        return WallpaperCrop.overflow(preview.getWidth(), preview.getHeight(),
                imageWidth, imageHeight, crop[2]);
    }

    private String describe(String text) {
        int[] space = overflow();
        boolean vertical = space[1] > 0, horizontal = space[0] > 0;
        String shape = imageHeight > imageWidth ? "竖图" : "横图";
        String fill;
        if (vertical && !horizontal) fill = "已按宽度铺满";
        else if (!vertical && horizontal) fill = "已按高度铺满";
        else if (vertical) fill = "已铺满，可上下左右移动";
        else fill = "与屏幕同比例，按 → 放大取局部";
        String state = imageWidth + "×" + imageHeight + " " + shape + " · " + fill
                + " · 上下 " + Math.round(crop[1] * 100) + "%"
                + " · " + String.format(java.util.Locale.CHINA, "%.2f", crop[2]) + "×";
        return text == null || text.isEmpty() ? state : text + "\n" + state;
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText(describe(shortLabel()));
        if (hint != null) hint.setText("↑↓ 选显示哪一段　←→ 放大（最多 4.00×）/ 缩小　确认键保存　返回键取消");
    }

    private String shortLabel() {
        if (label.length() <= 26) return label;
        return label.substring(0, 25) + "…";
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean handled = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (!handled) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (!ready) return true;

        // 按住不放时步进逐次变大：从 1.0 拉到 3.8 不用按三十下
        float boost = 1f + Math.min(event.getRepeatCount(), 8) * 0.35f;
        float stepY = STEP_Y * boost;
        float stepZoom = STEP_ZOOM * boost;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                crop[1] = WallpaperCrop.clamp(crop[1] - stepY, 0f, 1f);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                crop[1] = WallpaperCrop.clamp(crop[1] + stepY, 0f, 1f);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                crop[2] = WallpaperCrop.clamp(crop[2] - stepZoom, WallpaperCrop.MIN_ZOOM,
                        WallpaperCrop.MAX_ZOOM);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                crop[2] = WallpaperCrop.clamp(crop[2] + stepZoom, WallpaperCrop.MIN_ZOOM,
                        WallpaperCrop.MAX_ZOOM);
                break;
            default:
                save();
                return true;
        }
        applyCrop();
        updateStatus();
        return true;
    }

    private void save() {
        if (saving) return;
        saving = true;
        WallpaperCrop.write(this, id, crop[0], crop[1], crop[2]);
        setResult(RESULT_OK);
        finish();
    }

    @Override public void onBackPressed() {
        // 返回键放弃本次调整：crop 只是内存副本，不写回 prefs 即可
        setResult(RESULT_CANCELED);
        finish();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setTypeface(Serif.regular(this));
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
