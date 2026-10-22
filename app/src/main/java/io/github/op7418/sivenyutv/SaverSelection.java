package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 屏保播放列表（本地图片与视频的绝对路径，按选择顺序）。 */
final class SaverSelection {
    private static final String PREFS = "aptv-saver";
    private static final String KEY_ITEMS = "items";

    private SaverSelection() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 未配置返回空列表。 */
    static List<String> list(Context c) {
        String raw = prefs(c).getString(KEY_ITEMS, "");
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String line : raw.split("\n")) {
            String path = line.trim();
            if (!path.isEmpty() && !result.contains(path)) result.add(path);
        }
        return result;
    }

    static boolean has(Context c) {
        return !list(c).isEmpty();
    }

    static void save(Context c, Iterable<String> paths) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String path : paths) {
            if (path != null && !path.trim().isEmpty()) unique.add(path.trim());
        }
        StringBuilder builder = new StringBuilder();
        for (String path : unique) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(path);
        }
        prefs(c).edit().putString(KEY_ITEMS, builder.toString()).apply();
    }

    static void clear(Context c) {
        prefs(c).edit().remove(KEY_ITEMS).apply();
    }

    /**
     * 简单的类型判断，供播放器与选择器共用。
     *
     * <p>扩展名表统一放在 {@link MediaLibrary}，壁纸库和屏保库共用一份，
     * 免得两边的识别范围慢慢岔开。
     */
    static boolean isVideo(String path) {
        if (path == null) return false;
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return MediaLibrary.isVideo(name);
    }
}
