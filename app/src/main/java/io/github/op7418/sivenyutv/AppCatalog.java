package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 扫描电视上可启动的应用，供 tvOS 风格的应用行使用。 */
final class AppCatalog {
    private static final String TAG = "SivenyuApps";

    static final class Entry {
        final String pkg;
        final String label;
        final Drawable icon;
        /** 应用自带的电视横幅（320×180 一类），没有则为 null。 */
        final Drawable banner;

        Entry(String pkg, String label, Drawable icon, Drawable banner) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.banner = banner;
        }
    }

    private AppCatalog() { }

    static List<Entry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> found;
            try {
                found = pm.queryIntentActivities(probe, 0);
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "查询应用失败 category=" + category, unavailable);
                continue;
            }
            for (ResolveInfo info : found) {
                if (info == null || info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.equals(context.getPackageName())) continue;
                if (!info.activityInfo.exported || !info.activityInfo.enabled) continue;
                if (unique.containsKey(pkg)) continue;
                String label;
                try {
                    CharSequence text = info.loadLabel(pm);
                    label = text == null ? pkg : text.toString().trim();
                } catch (RuntimeException broken) {
                    label = pkg;
                }
                if (label.isEmpty()) label = pkg;
                Drawable icon = null;
                try {
                    icon = info.activityInfo.loadIcon(pm);
                } catch (RuntimeException broken) {
                    Log.w(TAG, "读取图标失败 " + pkg, broken);
                }
                Drawable banner = BannerStore.load(context, pkg, label);
                if (banner == null) banner = loadBanner(pm, info);
                unique.put(pkg, new Entry(pkg, label, icon, banner));
            }
        }
        List<Entry> apps = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.CHINA);
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    /** 优先取 Activity 级 banner，其次应用级；两者都可能是 320×180 的电视横幅。 */
    private static Drawable loadBanner(PackageManager pm, ResolveInfo info) {
        try {
            Drawable banner = info.activityInfo.loadBanner(pm);
            if (banner != null) return banner;
        } catch (RuntimeException ignored) {
            // 部分应用的 banner 资源损坏，交给退路处理
        }
        try {
            if (info.activityInfo.applicationInfo != null) {
                return info.activityInfo.applicationInfo.loadBanner(pm);
            }
        } catch (RuntimeException ignored) {
            // 同上
        }
        return null;
    }

    /** 启动目标应用；leanback 入口优先，其次普通入口。 */
    static boolean launch(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        Intent intent = null;
        try {
            intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "启动失败 " + pkg, unavailable);
            return false;
        }
    }
}
