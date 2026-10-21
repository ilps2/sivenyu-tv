package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * 开机自动进入。
 *
 * 两条来源：
 * 1. BootReceiver（BOOT_COMPLETED 广播）—— 通用做法，Android 9 上允许后台拉起 Activity。
 * 2. BootGateActivity（小米系统 start_3rd_app 包名钩子）—— 需 ADB 写系统设置，作为备用。
 *
 * 两者共用同一个开关，且同一次开机只尝试一次。
 */
public final class BootStart {
    private static final String PREFS = "boot-start";
    private static final String EXTRA = "sivenyu.boot-count";
    /** BootReceiver 拉起时带上，用于界面侧做一次前台重申。 */
    static final String EXTRA_BOOT_LAUNCH = "sivenyu.boot-launch";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 开机自动进入，默认开启（本改造的主诉求）。 */
    static boolean autoBoot(Context c) {
        return prefs(c).getBoolean("auto-boot", true);
    }

    static void setAutoBoot(Context c, boolean value) {
        prefs(c).edit().putBoolean("auto-boot", value).apply();
    }

    /** 统一的拉起动作，供 BOOT_COMPLETED 与重试闹钟共用，并把结果记进自检日志。 */
    static void attempt(Context c, String reason) {
        if (!autoBoot(c)) {
            BootLog.attempt(c, "已关闭自动进入，跳过");
            return;
        }
        try {
            c.startActivity(new Intent(c, FrameHomeActivity.class)
                    .putExtra(EXTRA_BOOT_LAUNCH, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            BootLog.attempt(c, "已发起（" + reason + "）");
            Log.i("SivenyuBoot", "拉起桌面：" + reason);
        } catch (RuntimeException rejected) {
            BootLog.attempt(c, "被系统拒绝：" + rejected.getClass().getSimpleName());
            Log.w("SivenyuBoot", "拉起被拒绝：" + reason, rejected);
        }
    }

    private static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    /** 由小米 start_3rd_app 包名钩子触发的入口。 */
    static void enter(Activity a) {
        int boot = bootCount(a);
        String reason = BootStartPolicy.skipReason(XiaomiControls.enabled(a), autoBoot(a), boot,
                prefs(a).getInt("attempted", -1), SystemClock.elapsedRealtime());
        if (reason != null) {
            Log.i("SivenyuBoot", reason);
            return;
        }
        if (!prefs(a).edit().putInt("attempted", boot)
                .putString("status", "系统已调用启动入口，等待界面确认").commit()) return;
        try {
            a.startActivity(new Intent(a, FrameHomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA, boot));
        } catch (RuntimeException error) {
            status(a, "启动失败：" + error.getClass().getSimpleName());
            Log.w("SivenyuBoot", "Launch rejected", error);
        }
    }

    private static boolean configured(Context c) {
        return c.getPackageName().equals(Settings.System.getString(c.getContentResolver(), "start_3rd_app"));
    }

    private static void status(Context c, String value) {
        prefs(c).edit().putString("status", value).apply();
        Log.i("SivenyuBoot", value);
    }

    static void confirm(Activity activity) {
        Intent intent = activity.getIntent();
        int boot = intent.getIntExtra(EXTRA, -1);
        if (boot < 0) return;
        intent.removeExtra(EXTRA);
        if (boot == bootCount(activity) && prefs(activity).getInt("attempted", -1) == boot && autoBoot(activity)) {
            status(activity, "已在本次开机后进入桌面");
        }
    }

    /** 系统对话框的标题与按钮由系统创建，show 之后统一换成宋体。 */
    private static void show(AlertDialog dialog) {
        dialog.show();
        Serif.applyTo(dialog);
        // 列表项由系统 ListView 延迟创建，补一帧再套一次
        if (dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().post(() -> Serif.applyTo(dialog));
        }
    }

    /** 桌面上「设置」入口弹出的菜单。 */
    static void menu(Activity a, Runnable systemSettings) {
        boolean on = autoBoot(a);
        final int saverCount = SaverSelection.list(a).size();
        AlertDialog menu = new AlertDialog.Builder(a).setTitle("设置")
                .setItems(new String[]{
                        "电视系统设置",
                        "开机自动进入：" + (on ? "已开启" : "已关闭"),
                        "屏幕保护：" + ScreenSaver.label(a),
                        "屏保内容：" + (saverCount > 0 ? saverCount + " 项本地文件" : "内置图（未选择）"),
                        "立即播放屏保",
                        "更多（图标 · 屏保归属 · 接管 Home）"
                }, (dialog, index) -> {
                    if (index == 0) {
                        systemSettings.run();
                    } else if (index == 1) {
                        setAutoBoot(a, !on);
                        Serif.toast(a, !on ? "已开启：下次开机自动进入本桌面" : "已关闭：开机后停留在小米原桌面");
                    } else if (index == 2) {
                        chooseScreenSaver(a);
                    } else if (index == 3) {
                        try {
                            a.startActivity(new Intent(a, SaverPickerActivity.class));
                        } catch (RuntimeException unavailable) {
                            Serif.toast(a, "打不开屏保内容选择");
                        }
                    } else if (index == 4) {
                        try {
                            a.startActivity(new Intent(a, SaverActivity.class));
                        } catch (RuntimeException unavailable) {
                            Serif.toast(a, "打不开屏保");
                        }
                    } else {
                        showMore(a);
                    }
                })
                .setNegativeButton("返回", null)
                .create();
        show(menu);
    }

    /** 设置的第二层：说明类与接管入口，避免主菜单超出对话框高度被截断。 */
    private static void showMore(Activity a) {
        AlertDialog menu = new AlertDialog.Builder(a).setTitle("更多")
                .setItems(new String[]{
                        "小米屏保：" + (stockSaverDisabled(a) ? "已禁用" : "未禁用（会抢屏）"),
                        "自定义图标（banner）",
                        "接管 Home 键"
                }, (dialog, index) -> {
                    if (index == 0) {
                        showStockSaverHelp(a);
                    } else if (index == 1) {
                        showBannerHelp(a);
                    } else {
                        try {
                            a.startActivity(new Intent(a, HomeTakeoverActivity.class));
                        } catch (RuntimeException unavailable) {
                            showDefaultHomeHelp(a);
                        }
                    }
                })
                .setNegativeButton("返回", null)
                .create();
        show(menu);
    }

    /** 小米自带屏保是否已被停用（停用后本桌面的屏保才能留在屏幕上）。 */
    static boolean stockSaverDisabled(Context context) {
        try {
            int state = context.getPackageManager()
                    .getApplicationEnabledSetting("com.mitv.screensaver");
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (RuntimeException missing) {
            return false;
        }
    }

    private static void showStockSaverHelp(Activity a) {
        boolean disabled = stockSaverDisabled(a);
        String body = disabled
                ? "小米自带屏保已停用，本桌面的屏保不会再被它盖掉。\n\n"
                + "如需恢复：\nadb shell pm enable com.mitv.screensaver"
                : "电视闲置到设定时间后，小米自带屏保（com.mitv.screensaver）会把本桌面的屏保盖掉，"
                + "原因是它由系统层直接拉起，第三方应用无法阻止。\n\n"
                + "关闭它（电脑执行一次，重启不丢）：\n"
                + "adb shell pm disable-user --user 0 com.mitv.screensaver\n\n"
                + "恢复：\nadb shell pm enable com.mitv.screensaver";
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle("小米自带屏保")
                .setMessage(body)
                .setNegativeButton("知道了", null)
                .create();
        show(dialog);
    }

    private static void showBannerHelp(Activity a) {
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle("自定义图标（banner）")
                .setMessage("部分应用没有电视横幅，图标只能缩在横框中间。可以自己放一张 5:3 的横图替换。\n\n"
                        + "尺寸：1280×768 PNG（或任意 5:3，例如 960×576）\n\n"
                        + "放这里（U 盘或机身存储均可）：\n"
                        + "U盘根目录/aptv-banners/\n"
                        + "或 Download/aptv-banners/\n\n"
                        + "文件名用包名或应用中文名，例如：\n"
                        + "com.quark.yun.tv.png\n"
                        + "夸克网盘.png\n\n"
                        + "放好后回到桌面按「应用 → 刷新」即可生效，不用重装。")
                .setNegativeButton("知道了", null)
                .create();
        show(dialog);
    }

    private static void chooseScreenSaver(Activity a) {
        // 不再用 setMessage + setSingleChoiceItems：两者同时用时，列表会被挤成 0 高度，
        // 对话框里只剩标题、说明和「返回」，看起来像点了没反应。
        AlertDialog choose = new AlertDialog.Builder(a)
                .setTitle("屏幕保护（当前 " + ScreenSaver.label(a) + "）")
                .setItems(ScreenSaver.LABELS, (dialog, which) -> {
                    ScreenSaver.setMinutes(a, ScreenSaver.MINUTES[which]);
                    dialog.dismiss();
                    Serif.toast(a, "屏幕保护：" + ScreenSaver.label(a));
                })
                .setNegativeButton("返回", null)
                .create();
        show(choose);
    }

    private static void showDefaultHomeHelp(Activity a) {
        String pkg = a.getPackageName();
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle("接管 Home 键（可选）")
                .setMessage("本桌面不会强行修改系统。若想让 Home 键也回到这里，需要电脑上执行一次 ADB 命令：\n\n"
                        + "1) 电视开启「开发者选项 → USB 调试 / 网络调试」\n"
                        + "2) 电脑执行：\n"
                        + "adb connect 电视IP:5555\n"
                        + "adb shell cmd package set-home-activity " + pkg + "/.FrameHomeActivity\n\n"
                        + "若上面这条提示不支持，可改用：\n"
                        + "adb shell pm set-home-activity " + pkg + "/.FrameHomeActivity\n\n"
                        + "想恢复小米原桌面：\n"
                        + "adb shell cmd package set-home-activity com.mitv.tvhome/com.mitv.tvhome.MainActivity\n\n"
                        + "开机自动进入不依赖这一步。")
                .setPositiveButton("知道了", null)
                .create();
        show(dialog);
    }

    static void dump(Context c, java.io.PrintWriter out) {
        out.println("BootStart: autoBoot=" + autoBoot(c) + " configured=" + configured(c)
                + " boot=" + bootCount(c) + " attempted=" + prefs(c).getInt("attempted", -1)
                + " status=" + prefs(c).getString("status", "none"));
    }
}
