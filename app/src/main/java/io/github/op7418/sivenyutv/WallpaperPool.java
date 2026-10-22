package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 壁纸轮播池。
 *
 * <p>元素用「相对标识」而不是绝对路径：本地媒体是 {@code file:<文件名>}，
 * APK 内置壁纸是 {@code asset:<相对 assets 的路径>}。
 * 旧版屏保选择存的是绝对路径，一旦 {@code files/media} 目录或应用数据位置变化，
 * 整批选择就会被判为「已失效」清空——用户看到的是勾选突然全没了。
 */
final class WallpaperPool {
    private static final String PREFS = "aptv-wallpaper-pool";
    private static final String KEY_ITEMS = "items";

    private WallpaperPool() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> list(Context c) {
        String raw = prefs(c).getString(KEY_ITEMS, "");
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String line : raw.split("\n")) {
            String key = line.trim();
            if (!key.isEmpty() && !result.contains(key)) result.add(key);
        }
        return result;
    }

    static void save(Context c, Iterable<String> keys) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.trim().isEmpty()) unique.add(key.trim());
        }
        StringBuilder builder = new StringBuilder();
        for (String key : unique) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(key);
        }
        prefs(c).edit().putString(KEY_ITEMS, builder.toString()).apply();
    }

    static boolean has(Context c) {
        return !list(c).isEmpty();
    }

    static void clear(Context c) {
        prefs(c).edit().remove(KEY_ITEMS).apply();
    }
}
