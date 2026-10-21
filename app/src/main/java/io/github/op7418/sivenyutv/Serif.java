package io.github.op7418.sivenyutv;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 全应用统一字体：思源宋体（Noto Serif SC）。
 *
 * 字体随 APK 打包在 assets/fonts —— 这台电视的固件只带 NotoSansCJK（黑体）和
 * 仅含拉丁的 NotoSerif，没有中文衬线体，所以不能依赖系统字体。
 * 三个字重：Light 300 / Regular 400 / Bold 700，都按 GB2312 全集子集化过。
 */
final class Serif {
    private static final String TAG = "SivenyuFont";

    private static Typeface light;
    private static Typeface regular;
    private static Typeface bold;

    private Serif() { }

    static Typeface light(Context context) {
        load(context);
        return light;
    }

    static Typeface regular(Context context) {
        load(context);
        return regular;
    }

    static Typeface bold(Context context) {
        load(context);
        return bold;
    }

    private static void load(Context context) {
        if (regular != null) return;
        Context app = context.getApplicationContext();
        try {
            light = Typeface.createFromAsset(app.getAssets(), "fonts/NotoSerifSC-Light.otf");
            regular = Typeface.createFromAsset(app.getAssets(), "fonts/NotoSerifSC-Regular.otf");
            bold = Typeface.createFromAsset(app.getAssets(), "fonts/NotoSerifSC-Bold.otf");
        } catch (RuntimeException | OutOfMemoryError failed) {
            Log.w(TAG, "思源宋体加载失败，退回系统衬线体", failed);
            light = Typeface.create("serif", Typeface.NORMAL);
            regular = light;
            bold = Typeface.create("serif", Typeface.BOLD);
        }
    }

    /** 把整棵视图树里的文字统一成宋体，用于系统对话框这类不由我们创建的内容。 */
    static void applyTo(View root) {
        if (root == null) return;
        if (root instanceof TextView) {
            style((TextView) root, root.getContext());
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i));
            }
        }
    }

    /**
     * 自定义 Toast：系统 Toast 的文案由系统渲染，套不上应用字体，
     * 这里自己搭一个同样圆角半透明的提示，保证也是宋体。
     */
    static void toast(Context context, CharSequence text) {
        if (text == null) return;
        Context app = context.getApplicationContext();
        try {
            float density = app.getResources().getDisplayMetrics().density;
            TextView view = new TextView(app);
            view.setText(text);
            view.setTextSize(15);
            view.setTextColor(0xFFFFFFFF);
            view.setTypeface(regular(app));
            view.setIncludeFontPadding(false);
            view.setGravity(Gravity.CENTER);
            view.setMaxWidth(Math.round(680 * density));
            int h = Math.round(26 * density);
            int v = Math.round(12 * density);
            view.setPadding(h, v, h, v);

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE6161B22);
            background.setCornerRadius(18 * density);
            background.setStroke(Math.max(1, Math.round(density)), 0x33FFFFFF);
            view.setBackground(background);

            Toast toast = new Toast(app);
            toast.setView(view);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.BOTTOM, 0, Math.round(132 * density));
            toast.show();
        } catch (RuntimeException | OutOfMemoryError failed) {
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
        }
    }

    /** 系统对话框：它的标题与按钮由系统创建，show 之后单独走一遍。 */
    static void applyTo(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        applyTo(dialog.getWindow().getDecorView());
    }

    private static void style(TextView view, Context context) {
        Typeface current = view.getTypeface();
        boolean heavy = current != null && current.isBold();
        view.setTypeface(heavy ? bold(context) : regular(context));
    }
}
