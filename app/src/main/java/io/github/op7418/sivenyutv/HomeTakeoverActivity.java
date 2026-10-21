package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 开机自检 + 接管 Home 键向导。
 *
 * 开机不能自启只有两种可能，必须分清：
 * A. 广播没送到应用（系统/管家拦截了自启动）→ 自检显示「从未收到开机广播」
 * B. 广播到了但拉不起来（被系统拒绝或被原桌面顶掉）→ 显示收到时间 + 拉起结果
 *
 * 接管 Home 键后不依赖广播，所以 A 类问题的终极解法就是这里。
 */
public final class HomeTakeoverActivity extends Activity {
    private TextView homeValue;
    private TextView bootValue;
    private TextView attemptValue;
    private TextView stockValue;
    private TextView hint;
    private LinearLayout stepsBox;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(dp(64), dp(36), dp(64), dp(32));

        root.addView(label("开机启动自检", 28, "sans-serif-light", 0xFFFFFFFF));
        TextView sub = label("判断开机没进桌面卡在哪一环，并接管 Home 键", 13, "sans-serif", 0x8AFFFFFF);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = dp(6);
        root.addView(sub, subParams);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollerParams.topMargin = dp(16);
        root.addView(scroller, scrollerParams);

        body.addView(buildStatusCard(), new LinearLayout.LayoutParams(-1, -2));

        hint = label("", 13, "sans-serif", 0xFFFFD9A0);
        hint.setLineSpacing(dp(4), 1f);
        body.addView(hint, params(dp(14)));

        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(stepsBox, params(dp(6)));

        body.addView(buildCommands(), params(dp(16)));

        TextView risk = label("风险：接管后小米桌面不再自动出现，其开机儿童模式等入口会失效。"
                        + "随时可用上面的恢复命令退回。不确定就先别做。",
                12, "sans-serif", 0x8AFFFFFF);
        risk.setLineSpacing(dp(3), 1f);
        body.addView(risk, params(dp(14)));

        root.addView(buildFooter(), params(dp(4)));
        setContentView(root);
        refresh();
    }

    private LinearLayout.LayoutParams params(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = topMarginDp;
        return params;
    }

    private View buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(0x14FFFFFF, 16));
        card.setPadding(dp(22), dp(14), dp(22), dp(14));
        homeValue = addRow(card, "当前默认桌面", "检测中…");
        stockValue = addRow(card, "小米原桌面", "检测中…");
        bootValue = addRow(card, "开机广播", "检测中…");
        attemptValue = addRow(card, "最近一次拉起", "检测中…");
        return card;
    }

    private TextView addRow(LinearLayout parent, String name, String initial) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView label = label(name, 13, "sans-serif", 0x8AFFFFFF);
        label.setMinWidth(dp(104));
        row.addView(label);

        TextView value = label(initial, 15, "sans-serif", 0xFFFFFFFF);
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    private View buildCommands() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(0x0DFFFFFF, 12));
        box.setPadding(dp(18), dp(14), dp(18), dp(14));

        String pkg = getPackageName();
        box.addView(label("接管 Home 键（电脑上执行，电视需开启网络调试）", 12, "sans-serif", 0x8AFFFFFF));
        addCommand(box, "adb connect 电视IP:5555");
        addCommand(box, "adb shell cmd package set-home-activity " + pkg + "/.FrameHomeActivity");
        box.addView(label("上面这条若提示不支持，改用（会禁用小米桌面，有风险）：", 12, "sans-serif", 0x99FFFFFF),
                params(dp(10)));
        addCommand(box, "adb shell pm disable-user --user 0 com.mitv.tvhome");
        box.addView(label("恢复小米桌面：", 12, "sans-serif", 0x8AFFFFFF), params(dp(10)));
        addCommand(box, "adb shell cmd package set-home-activity com.mitv.tvhome/com.mitv.tvhome.MainActivity");
        addCommand(box, "adb shell pm enable com.mitv.tvhome");
        return box;
    }

    private void addCommand(LinearLayout parent, String command) {
        TextView view = label(command, 12, "monospace", 0xFFFFD9A0);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(3), 0, dp(3));
        parent.addView(view);
    }

    private View buildFooter() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackground(rounded(0x14FFFFFF, 30));
        bar.setPadding(dp(10), dp(7), dp(10), dp(7));

        footerItem(bar, "重新检测", this::refresh);
        footerItem(bar, "系统设置", this::openSettings);
        footerItem(bar, "返回", () -> finish());

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER);
        wrapper.addView(bar);
        return wrapper;
    }

    private void footerItem(LinearLayout bar, String name, Runnable action) {
        TextView item = label(name, 16, "sans-serif", 0xE6FFFFFF);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(24), dp(10), dp(24), dp(10));
        item.setFocusable(true);
        item.setOnClickListener(v -> action.run());
        item.setOnFocusChangeListener((v, focused) -> {
            item.setBackground(focused ? rounded(0x3DFFFFFF, 24) : null);
            item.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(130).start();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        bar.addView(item, params);
    }

    // ---------------------------------------------------------------- 逻辑

    private String currentHomePackage() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved == null || resolved.activityInfo == null ? null : resolved.activityInfo.packageName;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private String describe(String pkg) {
        if (pkg == null) return "未识别";
        if (pkg.equals(getPackageName())) return getString(R.string.app_name) + "（已接管）";
        if (pkg.equals("com.mitv.tvhome")) return "小米原桌面";
        try {
            CharSequence label = getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
            return label == null ? pkg : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return pkg;
        }
    }

    private void refresh() {
        String pkg = currentHomePackage();
        boolean self = getPackageName().equals(pkg);
        homeValue.setText(describe(pkg));
        homeValue.setTextColor(self ? 0xFF7ED9A7 : 0xFFFFFFFF);

        boolean broadcastArrived = BootLog.lastBootAt(this) > 0L;
        bootValue.setText(BootLog.describeBoot(this));
        bootValue.setTextColor(broadcastArrived ? 0xFF7ED9A7 : 0xFFF09595);

        String attempt = BootLog.describeAttempt(this);
        attemptValue.setText(attempt);
        attemptValue.setTextColor(attempt.startsWith("被系统拒绝") ? 0xFFF09595 : 0xFFE6E6E6);

        boolean stockDisabled = XiaomiControls.stockHomeDisabled(this);
        stockValue.setText(stockDisabled ? "已禁用（Home 键交给了本桌面）" : "已启用");
        stockValue.setTextColor(stockDisabled ? 0xFF7ED9A7 : 0xFFE6E6E6);

        if (self) {
            hint.setText(stockDisabled
                    ? "已接管：小米原桌面被禁用，系统和 Home 键都只能落到本桌面。"
                            + "想恢复小米桌面，用下面的 pm enable 命令把它启用回来。"
                    : "已接管 Home 键：按 Home 键会回到这里，不再依赖开机广播。");
        } else if (!broadcastArrived) {
            hint.setText("问题定位：系统没有把开机广播送到应用（多数是小米电视的「自启动」被管家拦了）。\n"
                    + "两条路：① 电视管家 / 权限管理里把「" + getString(R.string.app_name) + "」设为允许自启动；"
                    + "② 直接接管 Home 键（下面 4 步），接管后不依赖广播，最可靠。");
        } else {
            hint.setText("问题定位：广播已经收到并尝试拉起，但被系统或原桌面挤掉了。\n"
                    + "v1.4.0 已加 20 秒 / 45 秒两次重试；如果仍不行，接管 Home 键是最稳的解法。");
        }
        renderSteps(!self);
    }

    private void renderSteps(boolean visible) {
        stepsBox.removeAllViews();
        if (!visible) return;
        String[][] steps = {
                {"1", "电视：设置 → 关于 → 连点「产品型号」约 7 次，开启开发者选项"},
                {"2", "电视：开发者选项 → 打开「网络调试 / ADB 调试」，记下 IP"},
                {"3", "电脑：双击运行 D:\\agent\\下载\\接管Home键.bat，输入电视 IP"},
                {"4", "回到本页按「重新检测」，显示「已接管」即完成"}
        };
        for (String[] step : steps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(5), 0, dp(5));
            TextView index = label(step[0], 13, "sans-serif-medium", 0xFF9BD1FF);
            index.setMinWidth(dp(22));
            row.addView(index);
            TextView text = label(step[1], 14, "sans-serif", 0xE6FFFFFF);
            text.setLineSpacing(dp(3), 1f);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            stepsBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException unavailable) {
            try {
                startActivity(XiaomiControls.settings(this, Settings.ACTION_SETTINGS));
            } catch (RuntimeException alsoUnavailable) {
                Serif.toast(this, "打不开系统设置");
            }
        }
    }

    private TextView label(String value, float size, String family, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(serifOf(family));
        view.setIncludeFontPadding(false);
        return view;
    }

    /** 全应用统一思源宋体：按原来的字体族语义取出对应的字重。 */
    private Typeface serifOf(String family) {
        String name = family == null ? "" : family;
        if (name.contains("light")) return Serif.light(this);
        if (name.contains("medium") || name.contains("bold")) return Serif.bold(this);
        return Serif.regular(this);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
