package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 首页应用选择。
 *
 * 未设置过时返回 null，表示“全部显示”（首次安装的默认状态）。
 * 设置过之后只显示用户挑选的包名；首页调整的顺序单独保存。
 */
final class HomeSelection {
    private static final String PREFS = "aptv-home";
    private static final String KEY = "curated-apps";
    private static final String KEY_ORDER = "app-order";

    private HomeSelection() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean curated(Context c) {
        return prefs(c).contains(KEY);
    }

    /** 返回用户挑选的包名（有序）；从未设置过返回 null。 */
    static List<String> list(Context c) {
        String raw = prefs(c).getString(KEY, null);
        if (raw == null) return null;
        return order(c, parse(raw));
    }

    private static List<String> parse(String raw) {
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String pkg = part.trim();
            if (!pkg.isEmpty() && !result.contains(pkg)) result.add(pkg);
        }
        return result;
    }

    /** Keep new/unlisted apps after the remembered order, without changing visibility. */
    static List<String> order(Context c, List<String> packages) {
        LinkedHashSet<String> remaining = new LinkedHashSet<>(packages);
        List<String> ordered = new ArrayList<>();
        for (String pkg : parse(prefs(c).getString(KEY_ORDER, ""))) {
            if (remaining.remove(pkg)) ordered.add(pkg);
        }
        ordered.addAll(remaining);
        return ordered;
    }

    /** Sorting the default all-apps view must not turn it into an explicit selection. */
    static void saveOrder(Context c, Iterable<String> packages) {
        prefs(c).edit().putString(KEY_ORDER, join(packages)).apply();
    }

    static void save(Context c, Iterable<String> packages) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String pkg : packages) {
            if (pkg != null && !pkg.trim().isEmpty()) unique.add(pkg.trim());
        }
        prefs(c).edit().putString(KEY, join(unique)).apply();
    }

    private static String join(Iterable<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }
}
