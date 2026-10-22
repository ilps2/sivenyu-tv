package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * 壁纸构图参数：铺满屏幕 + 归一化焦点 + 额外放大。
 *
 * <p>屏幕 1920×1080，竖图（1080×1920）按 max(宽比, 高比) 铺满后宽度刚好等于屏宽、
 * 高度溢出到 3413，因此只能上下平移——这正是「放大到宽度适应、上下滑动选显示哪段」的效果。
 * 横图铺满后高度刚好、宽度溢出，则只能左右平移。
 *
 * <p>crop = {focusX, focusY, zoom}：focus 为 0 时贴左上、1 时贴右下、0.5 居中。
 */
final class WallpaperCrop {
    private static final String PREFS = "aptv-wallpaper-crop";
    static final float MIN_ZOOM = 1f;
    /** 上限 4 倍：像「竖图 + 两侧模糊」这种 16:9 合成图，中间那块要放大 3.8 倍才能铺满屏幕。 */
    static final float MAX_ZOOM = 4f;
    private static final float[] DEFAULT = {0.5f, 0.5f, 1f};

    private WallpaperCrop() {}

    static float[] defaultCrop() {
        return DEFAULT.clone();
    }

    static float[] read(Context context, String id) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null);
        if (raw == null) return DEFAULT.clone();
        try {
            String[] parts = raw.split(",");
            float focusX = clamp(Float.parseFloat(parts[0]), 0f, 1f);
            float focusY = clamp(Float.parseFloat(parts[1]), 0f, 1f);
            float zoom = parts.length > 2 ? clamp(Float.parseFloat(parts[2]), MIN_ZOOM, MAX_ZOOM) : 1f;
            return new float[]{focusX, focusY, zoom};
        } catch (RuntimeException broken) {
            android.util.Log.w("TvHome.Wallpaper", "Bad crop for " + id, broken);
            return DEFAULT.clone();
        }
    }

    static void write(Context context, String id, float focusX, float focusY, float zoom) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(id, clamp(focusX, 0f, 1f) + "," + clamp(focusY, 0f, 1f) + ","
                        + clamp(zoom, MIN_ZOOM, MAX_ZOOM))
                .apply();
        android.util.Log.i("TvHome.Wallpaper", "Crop " + id + " x=" + focusX + " y=" + focusY
                + " zoom=" + zoom);
    }

    static void clear(Context context, String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply();
    }

    static boolean isCustom(Context context, String id) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(id);
    }

    /**
     * 跨界面统一的构图键。
     *
     * <p>壁纸侧用 {@code Entry.id}、屏保侧只有路径，两边必须落到同一个键上，
     * 否则「改了壁纸的画面位置，屏保还是原来那样」。规则与
     * {@code FrameWallpapers.poolKey} 一致：本地 {@code file:<文件名>}、
     * 内置 {@code asset:<相对 assets 的路径>}。
     */
    static String keyFor(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("asset:")) {
            String rest = path.substring(6);
            if (rest.startsWith("wallpapers/")) rest = rest.substring(11);
            return "asset:" + rest;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return "file:" + (slash >= 0 ? path.substring(slash + 1) : path);
    }

    /**
     * 旧版按 {@code Entry.id}（{@code user-xxx.jpg} / catalog id）存，
     * v2 起按相对标识存。启动时把旧键搬到新键，搬过的不会重复搬。
     */
    static void migrateKey(Context context, String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(from, null);
        if (raw == null || prefs.contains(to)) return;
        prefs.edit().putString(to, raw).apply();
    }

    /**
     * 把构图参数套到 ImageView 上。view 还没测量（宽高为 0）时返回 false，
     * 调用方应在 layout 变化后重试。
     */
    static boolean apply(ImageView view, float[] crop) {
        if (view == null || crop == null) return false;
        Drawable drawable = view.getDrawable();
        if (drawable == null) return false;
        int viewWidth = view.getWidth(), viewHeight = view.getHeight();
        int imageWidth = drawable.getIntrinsicWidth(), imageHeight = drawable.getIntrinsicHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            // 还没测量就退回居中裁切，绝不让图以 1:1 乘在左上角
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return false;
        }

        float zoom = clamp(crop[2], MIN_ZOOM, MAX_ZOOM);
        float scale = Math.max((float) viewWidth / imageWidth, (float) viewHeight / imageHeight) * zoom;
        float drawnWidth = imageWidth * scale, drawnHeight = imageHeight * scale;
        // focus 0 → 图片左边/上边贴住屏幕左边/上边；focus 1 → 右边/下边贴住
        float dx = (viewWidth - drawnWidth) * clamp(crop[0], 0f, 1f);
        float dy = (viewHeight - drawnHeight) * clamp(crop[1], 0f, 1f);

        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        view.setScaleType(ImageView.ScaleType.MATRIX);
        view.setImageMatrix(matrix);
        return true;
    }

    /** 当前参数下可平移的余量（像素），0 表示该方向已经贴边、无需调整。 */
    static int[] overflow(int viewWidth, int viewHeight, int imageWidth, int imageHeight, float zoom) {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return new int[]{0, 0};
        float scale = Math.max((float) viewWidth / imageWidth, (float) viewHeight / imageHeight) * zoom;
        return new int[]{
                Math.max(0, Math.round(imageWidth * scale) - viewWidth),
                Math.max(0, Math.round(imageHeight * scale) - viewHeight)};
    }

    static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
