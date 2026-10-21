package io.github.op7418.sivenyutv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 轻量「液体玻璃」面板。
 *
 * 本机是 Android 9，没有 RenderEffect 实时背景模糊，所以用三层叠加模拟：
 *   1) 白色低透明度竖向渐变（上亮下暗，像玻璃厚度）
 *   2) 顶部 45% 再叠一层高光（受光面）
 *   3) 一圈上亮下暗的描边（玻璃边缘的转折光）
 */
final class Glass extends Drawable {
    private final float radiusPx;
    private final int topAlpha;
    private final int bottomAlpha;
    private final boolean rim;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    /** A capsule always uses half of its actual height, including larger font sizes. */
    static Glass capsule(int topAlpha, int bottomAlpha) {
        return new Glass(Float.POSITIVE_INFINITY, topAlpha, bottomAlpha);
    }

    Glass(float radiusPx, int topAlpha, int bottomAlpha) {
        this(radiusPx, topAlpha, bottomAlpha, true);
    }

    Glass(float radiusPx, int topAlpha, int bottomAlpha, boolean rim) {
        this.radiusPx = radiusPx;
        this.topAlpha = topAlpha;
        this.bottomAlpha = bottomAlpha;
        this.rim = rim;
        edge.setStyle(Paint.Style.STROKE);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        box.set(bounds.left + 0.5f, bounds.top + 0.5f, bounds.right - 0.5f, bounds.bottom - 0.5f);
        float radius = Math.min(radiusPx, Math.min(box.width(), box.height()) / 2f);

        fill.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                white(topAlpha), white(bottomAlpha), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, radius, radius, fill);

        float sheenHeight = box.height() * 0.45f;
        sheen.setShader(new LinearGradient(0, box.top, 0, box.top + sheenHeight,
                0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // Draw the same antialiased contour for every layer. A clipped rectangle can
        // leave a hard, uneven highlight around the curved ends on older TV GPUs.
        canvas.drawRoundRect(box, radius, radius, sheen);

        if (!rim) return;
        edge.setStrokeWidth(Math.max(1f, box.height() * 0.008f));
        edge.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                0x5CFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        float inset = edge.getStrokeWidth() / 2f;
        RectF strokeBox = new RectF(box.left + inset, box.top + inset,
                box.right - inset, box.bottom - inset);
        canvas.drawRoundRect(strokeBox, Math.max(0f, radius - inset), Math.max(0f, radius - inset), edge);
    }

    private static int white(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | 0x00FFFFFF;
    }

    @Override public void setAlpha(int alpha) {
        // 由构造参数控制透明度，这里不额外处理
    }

    @Override public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
