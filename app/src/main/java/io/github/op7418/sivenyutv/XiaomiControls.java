package io.github.op7418.sivenyutv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 小米电视专用入口。
 *
 * 原始实现用 Build.MODEL 硬编码只允许 MiTV-MTSU0。本改造改为「能力探测」：
 * 只要目标组件真实存在、已导出且当前应用有权调用就尝试，否则回落到标准 Android Intent。
 * 这样在 MIUI for TV 3.0（如 L65R8-X）上也能拿到系统设置、原桌面等入口，同时不会在别的机型上乱来。
 */
final class XiaomiControls {
    private static final String TAG = "SivenyuXiaomi";

    private static final String PKG_SETTINGS = "com.xiaomi.mitv.settings";
    private static final String PKG_PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String PKG_STOCK_HOME = "com.mitv.tvhome";
    private static final String STOCK_HOME_ACTIVITY = "com.mitv.tvhome.MainActivity";

    private XiaomiControls() { }

    private static boolean hasPackage(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException missing) {
            return false;
        }
    }

    /** 是否是小米 / 红米电视环境（能走小米私有入口）。 */
    static boolean enabled(Context context) {
        String model = Build.MODEL == null ? "" : Build.MODEL;
        if (model.startsWith("MiTV") || model.startsWith("XiaomiTV") || model.startsWith("MiBOX")) return true;
        return hasPackage(context, PKG_STOCK_HOME) || hasPackage(context, PKG_PLAYER);
    }

    private static boolean callable(Context context, ComponentName target) {
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(target, 0);
            if (info == null || !info.enabled || !info.exported) return false;
            return info.permission == null
                    || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    /** 已在实机验证的固件上 source 23 = HDMI 1，24 = HDMI 2；其他固件可能不接受该参数。 */
    static boolean openHdmi(Context context, int port) {
        if (port < 1 || port > 2) return false;
        ComponentName target = new ComponentName(PKG_PLAYER, PKG_PLAYER + ".ExternalSourceActivity");
        if (!callable(context, target)) return false;
        try {
            context.startActivity(new Intent(PKG_PLAYER + ".EXTSRC_PLAY")
                    .setComponent(target).putExtra("input", 22 + port)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "HDMI 入口不可用", unavailable);
            return false;
        }
    }

    /** 小米设置深链优先，其次标准 Intent。 */
    static Intent settings(Context context, String standardAction) {
        String action = null;
        String activity = null;
        if (Settings.ACTION_SETTINGS.equals(standardAction)) {
            action = PKG_SETTINGS + ".SETTINGS";
            activity = ".entry.MainActivity";
        } else if (Settings.ACTION_WIFI_SETTINGS.equals(standardAction)) {
            action = PKG_SETTINGS + ".WIFI_SETTINGS";
            activity = ".wifi.MitvWifiActivity";
        }
        if (action != null) {
            ComponentName target = ComponentName.unflattenFromString(PKG_SETTINGS + "/" + activity);
            if (target != null && callable(context, target)) {
                return new Intent(action).setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        }
        return new Intent(standardAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** 信号源弹出框（小米私有广播），失败返回 false 由调用方提示。 */
    static boolean showInputs(Context context) {
        ComponentName receiver = new ComponentName(PKG_SETTINGS, PKG_SETTINGS + ".SettingsBroadcastReceiver");
        try {
            context.sendBroadcast(new Intent(PKG_SETTINGS + ".INPUTSOURCE_POPUP").setComponent(receiver));
            return true;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "信号源广播不可用", unavailable);
            return false;
        }
    }

    /** 小米原桌面是否被禁用（接管 Home 键时会被 disable-user）。 */
    static boolean stockHomeDisabled(Context context) {
        try {
            int state = context.getPackageManager().getApplicationEnabledSetting(PKG_STOCK_HOME);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** 回到小米原桌面（公开别名，避免签名权限限制）。 */
    static boolean startStockHome(Context context) {
        if (stockHomeDisabled(context)) return false;
        ComponentName alias = new ComponentName(PKG_STOCK_HOME, STOCK_HOME_ACTIVITY);
        if (callable(context, alias)) {
            try {
                context.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(alias)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                return true;
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "小米原桌面入口不可用", unavailable);
            }
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "系统桌面入口不可用", unavailable);
            return false;
        }
    }
}
