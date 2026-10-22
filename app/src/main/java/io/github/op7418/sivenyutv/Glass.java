package io.github.op7418.sivenyutv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

/** Liquid Glass inspired material that also works on Android 8/9 TV renderers. */
final class Glass extends Drawable {
    private final View host, scrim;
    private final ImageView wallpaper;
    /** 视频壁纸的播放层；它在播时玻璃改取它的当前帧。 */
    private android.view.TextureView live;
    private final float density;
    private final RectF body = new RectF(), insetBox = new RectF();
    private final Path shape = new Path(), rim = new Path(), inner = new Path();
    private final Path refractionRing = new Path(), refractionInset = new Path();
    private final Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint refractedEdge = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint film = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix backdropMatrix = new Matrix();
    private final Matrix edgeMatrix = new Matrix();
    private final int[] sourceLocation = new int[2], hostLocation = new int[2];
    private Bitmap cachedBackdrop;
    private BitmapShader backdropShader;
    private BitmapShader edgeShader;
    private int alpha = 255;

    Glass(View host, ImageView wallpaper, View scrim) {
        this.host = host;
        this.wallpaper = wallpaper;
        this.scrim = scrim;
        density = host.getResources().getDisplayMetrics().density;
        lip.setStyle(Paint.Style.STROKE);
        lip.setStrokeWidth(dp(3));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp(0.9f));
        innerEdge.setStyle(Paint.Style.STROKE);
        innerEdge.setStrokeWidth(dp(0.65f));
    }

    void setFocusRect(RectF rect) {
        body.set(rect);
        if (body.isEmpty()) return;
        contour(shape, body);
        insetBox.set(body);
        insetBox.inset(dp(0.5f), dp(0.5f));
        contour(rim, insetBox);
        insetBox.set(body);
        insetBox.inset(dp(1.8f), dp(1.8f));
        contour(inner, insetBox);
        insetBox.set(body);
        insetBox.inset(dp(3.2f), dp(3.2f));
        contour(refractionInset, insetBox);
        refractionRing.reset();
        refractionRing.setFillType(Path.FillType.EVEN_ODD);
        refractionRing.addPath(shape);
        refractionRing.addPath(refractionInset);
        film.setShader(new LinearGradient(0, body.top, 0, body.bottom,
                new int[]{0x36FFFFFF, 0x15FFFFFF, 0x09FFFFFF, 0x27FFFFFF},
                new float[]{0f, 0.22f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, body.top, 0, body.centerY(),
                0x15FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // Opposing highlights read as a curved transparent edge, rather than a white frame.
        edge.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                new int[]{0xDBFFFFFF, 0x50FFFFFF, 0x18FFFFFF, 0xA6FFFFFF},
                new float[]{0f, 0.34f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        lip.setShader(new LinearGradient(0, body.top, 0, body.bottom,
                0x16FFFFFF, 0x06FFFFFF, Shader.TileMode.CLAMP));
        innerEdge.setShader(new LinearGradient(0, body.top, 0, body.bottom,
                new int[]{0x0AFFFFFF, 0x14000000, 0x2CFFFFFF}, null, Shader.TileMode.CLAMP));
        invalidateSelf();
    }

    /** Curvature starts at zero where each end joins the straight section. */
    private static void contour(Path path, RectF box) {
        path.reset();
        if (box.isEmpty()) return;
        float rx = Math.min(box.width() / 2f, box.height() * 0.62f);
        float ry = box.height() / 2f, tangent = rx * 0.52f;
        float l = box.left, r = box.right, t = box.top, b = box.bottom;
        path.moveTo(l + rx, t);
        path.lineTo(r - rx, t);
        path.cubicTo(r - rx + tangent, t, r, t, r, t + ry);
        path.cubicTo(r, b, r - rx + tangent, b, r - rx, b);
        path.lineTo(l + rx, b);
        path.cubicTo(l + rx - tangent, b, l, b, l, b - ry);
        path.cubicTo(l, t, l + rx - tangent, t, l + rx, t);
        path.close();
    }

    /** Capture only the wallpaper and its dimming layer, never the app controls. */
    void refreshBackdrop() {
        int width = wallpaper.getWidth(), height = wallpaper.getHeight();
        if (width <= 0 || height <= 0 || !host.isAttachedToWindow()) return;
        float scale = Math.min(0.1f, 256f / Math.max(width, height));
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        try {
            Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.scale(w / (float) width, h / (float) height);
            // 视频壁纸时取播放中的当前帧，玻璃才能跟着画面动
            Bitmap live = liveFrame();
            if (live != null) {
                canvas.drawBitmap(live, null,
                        new android.graphics.Rect(0, 0, width, height), null);
                live.recycle();
            } else {
                wallpaper.draw(canvas);
            }
            // The lens admits more wallpaper light than the surrounding darkened home.
            int shade = canvas.saveLayerAlpha(null, 176);
            scrim.draw(canvas);
            canvas.restoreToCount(shade);
            int[] pixels = new int[w * h];
            image.getPixels(pixels, 0, w, 0, 0, w, h);
            blur(pixels, w, h);
            image.setPixels(pixels, 0, w, 0, 0, w, h);
            cachedBackdrop = image;
            backdropShader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            edgeShader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            backdrop.setShader(backdropShader);
            backdrop.setAlpha(235);
            refractedEdge.setShader(edgeShader);
            refractedEdge.setAlpha(190);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            releaseBackdrop();
            Log.w("SivenyuGlass", "壁纸玻璃缓存不可用", unavailable);
        }
        invalidateSelf();
    }

    /** 视频壁纸的当前帧；没有视频在播时返回 null。 */
    private Bitmap liveFrame() {
        if (live == null || live.getVisibility() != View.VISIBLE || !live.isAvailable()) return null;
        try {
            return live.getBitmap();
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    /** 切换壁纸时告诉玻璃层视频层在哪，视频在播就优先取它的画面。 */
    void setLiveSource(android.view.TextureView source) {
        live = source;
    }

    /** Two separable box passes on a <=256px cache; no per-frame blur or full-size capture. */
    private static void blur(int[] pixels, int width, int height) {
        int[] temp = new int[pixels.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < height; y++) {
                int red = 0, green = 0, blue = 0;
                for (int k = -2; k <= 2; k++) {
                    int color = pixels[y * width + Math.max(0, Math.min(width - 1, k))];
                    red += (color >> 16) & 255; green += (color >> 8) & 255; blue += color & 255;
                }
                for (int x = 0; x < width; x++) {
                    temp[y * width + x] = 0xFF000000 | ((red / 5) << 16) | ((green / 5) << 8) | (blue / 5);
                    int add = pixels[y * width + Math.min(width - 1, x + 3)];
                    int remove = pixels[y * width + Math.max(0, x - 2)];
                    red += ((add >> 16) & 255) - ((remove >> 16) & 255);
                    green += ((add >> 8) & 255) - ((remove >> 8) & 255);
                    blue += (add & 255) - (remove & 255);
                }
            }
            for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int k = -2; k <= 2; k++) {
                    int color = temp[Math.max(0, Math.min(height - 1, k)) * width + x];
                    red += (color >> 16) & 255; green += (color >> 8) & 255; blue += color & 255;
                }
                for (int y = 0; y < height; y++) {
                    pixels[y * width + x] = 0xFF000000 | ((red / 5) << 16) | ((green / 5) << 8) | (blue / 5);
                    int add = temp[Math.min(height - 1, y + 3) * width + x];
                    int remove = temp[Math.max(0, y - 2) * width + x];
                    red += ((add >> 16) & 255) - ((remove >> 16) & 255);
                    green += ((add >> 8) & 255) - ((remove >> 8) & 255);
                    blue += (add & 255) - (remove & 255);
                }
            }
        }
    }

    void releaseBackdrop() {
        // Let render-thread references expire naturally; never recycle a displayed bitmap.
        cachedBackdrop = null;
        backdropShader = null;
        edgeShader = null;
        backdrop.setShader(null);
        refractedEdge.setShader(null);
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        if (body.isEmpty() || alpha == 0) return;
        int save = canvas.save();
        if (alpha < 255) canvas.saveLayerAlpha(null, alpha);
        canvas.save();
        canvas.translate(0, dp(2));
        shadow.setStyle(Paint.Style.STROKE);
        shadow.setStrokeWidth(dp(10)); shadow.setColor(0x06000000); canvas.drawPath(shape, shadow);
        shadow.setStrokeWidth(dp(5)); shadow.setColor(0x10000000); canvas.drawPath(shape, shadow);
        shadow.setStyle(Paint.Style.FILL);
        shadow.setColor(0x24000000); canvas.drawPath(shape, shadow);
        canvas.restore();
        if (backdropShader != null && cachedBackdrop != null) {
            wallpaper.getLocationInWindow(sourceLocation);
            host.getLocationInWindow(hostLocation);
            float magnify = 1.065f;
            backdropMatrix.setScale(wallpaper.getWidth() * magnify / cachedBackdrop.getWidth(),
                    wallpaper.getHeight() * magnify / cachedBackdrop.getHeight());
            backdropMatrix.postTranslate((sourceLocation[0] - hostLocation[0]) * magnify
                            + body.centerX() * (1f - magnify),
                    (sourceLocation[1] - hostLocation[1]) * magnify + body.centerY() * (1f - magnify));
            backdropShader.setLocalMatrix(backdropMatrix);
            canvas.drawPath(shape, backdrop);
            if (edgeShader != null) {
                float edgeMagnify = 1.14f;
                edgeMatrix.setScale(wallpaper.getWidth() * edgeMagnify / cachedBackdrop.getWidth(),
                        wallpaper.getHeight() * edgeMagnify / cachedBackdrop.getHeight());
                edgeMatrix.postTranslate((sourceLocation[0] - hostLocation[0]) * edgeMagnify
                                + body.centerX() * (1f - edgeMagnify),
                        (sourceLocation[1] - hostLocation[1]) * edgeMagnify
                                + body.centerY() * (1f - edgeMagnify));
                edgeShader.setLocalMatrix(edgeMatrix);
                canvas.drawPath(refractionRing, refractedEdge);
            }
        }
        canvas.drawPath(shape, film);
        canvas.drawPath(shape, sheen);
        canvas.drawPath(rim, lip);
        canvas.drawPath(rim, edge);
        canvas.drawPath(inner, innerEdge);
        canvas.restoreToCount(save);
    }

    private float dp(float value) { return value * density; }

    @Override public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        backdrop.setColorFilter(filter);
        refractedEdge.setColorFilter(filter);
        film.setColorFilter(filter);
        sheen.setColorFilter(filter);
        edge.setColorFilter(filter);
        lip.setColorFilter(filter);
        innerEdge.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
