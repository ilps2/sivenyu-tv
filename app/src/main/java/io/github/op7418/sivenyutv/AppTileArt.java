package io.github.op7418.sivenyutv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * 生成 tvOS 风格的横长方形图标位图（5:3）。
 *
 * 苹果电视主屏图标是 5:3 横向矩形，不是正方形。安卓应用图标是 1:1，
 * 因此优先使用应用自带的 TV banner（本身即 320×180 横图）；
 * 没有 banner 时退化为「主色渐变底 + 图标居中」，避免硬裁切把主体切坏。
 */
final class AppTileArt {
    private static final String TAG = "SivenyuTile";

    private AppTileArt() { }

    /** 阴影留白：图标本体四周额外空间，供投影使用。 */
    static int padFor(int artHeight) {
        return Math.max(6, Math.round(artHeight * 0.09f));
    }

    /**
     * 只画卡片内容（banner / 兜底底图 / 图标 / 顶部高光），尺寸就是 artWidth × artHeight。
     *
     * <p>圆角不在这里裁 —— 交给调用方在 View 的 onDraw 里用 BitmapShader + drawRoundRect 画。
     * 在位图层面裁圆角试过三条路（clipPath / BitmapShader 叠圆角矩形 / 逐像素改 alpha），
     * 位图本身四角 alpha 确实变成 0 了，但经 ImageView 显示出来下缘两个角仍是直角，
     * 干脆把圆角交给绘制层，最稳。
     */
    static Bitmap render(AppCatalog.Entry entry, int artWidth, int artHeight, int radius) {
        if (artWidth <= 0 || artHeight <= 0) return null;
        try {
            Bitmap output = Bitmap.createBitmap(artWidth, artHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);

            boolean filled = entry.banner != null && centerCrop(canvas, entry.banner, artWidth, artHeight);
            if (!filled) {
                drawBackdrop(canvas, artWidth, artHeight, entry.icon);
                drawIcon(canvas, entry.icon, artWidth, artHeight);
            }
            drawGlassEdge(canvas, artWidth, artHeight, radius);
            return output;
        } catch (RuntimeException | OutOfMemoryError failed) {
            Log.w(TAG, "图标渲染失败 " + entry.pkg, failed);
            return null;
        }
    }

    /** 玻璃质感：顶部受光高光 + 一圈上亮下暗的边缘光。 */
    private static void drawGlassEdge(Canvas canvas, int width, int height, float radius) {
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        float sheenHeight = height * 0.42f;
        sheen.setShader(new LinearGradient(0, 0, 0, sheenHeight,
                0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, sheenHeight, sheen);

        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, height * 0.009f));
        edge.setShader(new LinearGradient(0, 0, 0, height,
                0x59FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        float inset = edge.getStrokeWidth() / 2f;
        canvas.drawRoundRect(new RectF(inset, inset, width - inset, height - inset),
                Math.max(0f, radius - inset), Math.max(0f, radius - inset), edge);
    }

    /** 等比铺满并居中裁切，返回是否成功绘制。 */
    private static boolean centerCrop(Canvas canvas, Drawable drawable, int width, int height) {
        try {
            drawable.mutate();
            int sourceWidth = drawable.getIntrinsicWidth();
            int sourceHeight = drawable.getIntrinsicHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                drawable.setBounds(0, 0, width, height);
            } else {
                float scale = Math.max(width / (float) sourceWidth, height / (float) sourceHeight);
                int drawnWidth = Math.round(sourceWidth * scale);
                int drawnHeight = Math.round(sourceHeight * scale);
                int left = (width - drawnWidth) / 2;
                int top = (height - drawnHeight) / 2;
                drawable.setBounds(left, top, left + drawnWidth, top + drawnHeight);
            }
            drawable.draw(canvas);
            return true;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "banner 绘制失败", unavailable);
            return false;
        }
    }

    /** 背景：统一压暗的图标主色调，让居中图标始终与底色有足够反差。 */
    private static void drawBackdrop(Canvas canvas, int width, int height, Drawable icon) {
        drawToneBackdrop(canvas, width, height, icon);
    }

    /** 退路：竖向渐变底，上浅下深，色调取自图标主色。 */
    private static void drawToneBackdrop(Canvas canvas, int width, int height, Drawable icon) {
        int base = Color.HSVToColor(new float[]{215f, 0.10f, 0.21f});
        Sample sample = sample(icon);
        if (sample != null) {
            float saturation = Math.max(0.10f, Math.min(0.34f, sample.saturation * 0.55f));
            base = Color.HSVToColor(new float[]{sample.hue, saturation, 0.20f});
        }
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        int top = Color.HSVToColor(new float[]{hsv[0], hsv[1], Math.min(1f, hsv[2] + 0.07f)});
        int bottom = Color.HSVToColor(new float[]{
                hsv[0], Math.min(1f, hsv[1] + 0.04f), Math.max(0f, hsv[2] - 0.06f)});
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, height, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
    }

    /** 原图标按高度的 60% 居中放置，自带圆角。 */
    private static void drawIcon(Canvas canvas, Drawable icon, int width, int height) {
        if (icon == null) return;
        int size = Math.round(height * 0.60f);
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        try {
            Bitmap art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(art);
            icon.mutate();
            int sourceWidth = icon.getIntrinsicWidth();
            int sourceHeight = icon.getIntrinsicHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                icon.setBounds(0, 0, size, size);
            } else {
                float scale = Math.max(size / (float) sourceWidth, size / (float) sourceHeight);
                int drawnWidth = Math.round(sourceWidth * scale);
                int drawnHeight = Math.round(sourceHeight * scale);
                int offsetX = (size - drawnWidth) / 2;
                int offsetY = (size - drawnHeight) / 2;
                icon.setBounds(offsetX, offsetY, offsetX + drawnWidth, offsetY + drawnHeight);
            }
            icon.draw(c);
            canvas.drawBitmap(art, left, top, null);
            art.recycle();
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            Log.w(TAG, "图标绘制失败", unavailable);
        }
    }

    private static final class Sample {
        float hue;
        float saturation;
        float lightness;
    }

    /** 取图标主色：色相按单位圆加权平均（避免 0/360 断裂），并统计平均亮度。 */
    private static Sample sample(Drawable icon) {
        if (icon == null) return null;
        try {
            int size = 32;
            Bitmap small = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            icon.mutate();
            int sourceWidth = icon.getIntrinsicWidth();
            int sourceHeight = icon.getIntrinsicHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                icon.setBounds(0, 0, size, size);
            } else {
                float scale = Math.max(size / (float) sourceWidth, size / (float) sourceHeight);
                int drawnWidth = Math.round(sourceWidth * scale);
                int drawnHeight = Math.round(sourceHeight * scale);
                int left = (size - drawnWidth) / 2;
                int top = (size - drawnHeight) / 2;
                icon.setBounds(left, top, left + drawnWidth, top + drawnHeight);
            }
            icon.draw(canvas);

            double cosSum = 0;
            double sinSum = 0;
            double weightSum = 0;
            double saturationSum = 0;
            double lightnessSum = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = small.getPixel(x, y);
                    int alpha = Color.alpha(pixel);
                    if (alpha < 48) continue;
                    Color.colorToHSV(pixel, hsv);
                    double alphaRatio = alpha / 255.0;
                    double weight = (0.12 + hsv[1] * 1.6) * alphaRatio;
                    double radians = Math.toRadians(hsv[0]);
                    cosSum += Math.cos(radians) * weight;
                    sinSum += Math.sin(radians) * weight;
                    weightSum += weight;
                    saturationSum += hsv[1] * weight;
                    lightnessSum += ((0.299 * Color.red(pixel) + 0.587 * Color.green(pixel)
                            + 0.114 * Color.blue(pixel)) / 255.0) * alphaRatio;
                }
            }
            if (weightSum <= 0.001) return null;
            Sample sample = new Sample();
            float hue = (float) Math.toDegrees(Math.atan2(sinSum, cosSum));
            if (hue < 0) hue += 360f;
            sample.hue = hue;
            sample.saturation = (float) (saturationSum / weightSum);
            sample.lightness = (float) (lightnessSum / weightSum);
            return sample;
        } catch (RuntimeException | OutOfMemoryError failed) {
            Log.w(TAG, "取色失败", failed);
            return null;
        }
    }
}
