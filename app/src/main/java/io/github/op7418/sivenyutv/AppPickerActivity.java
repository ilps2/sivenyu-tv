package io.github.op7418.sivenyutv;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 首页应用管理页。
 *
 * <p>网格布局（每行 5 个），方向键四向移动，比单列一路往下快得多。
 * 已选的应用按首页顺序排在最前面，名称前的蓝色数字就是首页上的位置。
 *
 * <p>此页只管理展示选择和卸载；调整位置请回到首页长按应用。
 */
public final class AppPickerActivity extends TvActivity {
    static final String EXTRA_CHANGED = "changed";

    private static final int COLUMNS = 5;
    private static final int TILE_HEIGHT = 130;
    private static final int REQ_UNINSTALL = 91;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final List<AppCatalog.Entry> apps = new ArrayList<>();
    private final Map<String, LinearLayout> tiles = new LinkedHashMap<>();
    private final Map<String, TextView> names = new LinkedHashMap<>();
    private final Map<String, ImageView> icons = new LinkedHashMap<>();

    private GridLayout grid;
    private ScrollView scroller;
    private TextView counter;
    private TextView subtitle;
    private TextView title;
    private LinearLayout footerBar;
    private TextView uninstallButton;
    private boolean changed;

    /** 卸载模式：和「首页展示」是两套互不相干的选择，标记只存在这里。 */
    private boolean uninstallMode;
    private final LinkedHashSet<String> toRemove = new LinkedHashSet<>();
    private final java.util.ArrayDeque<String> pendingUninstall = new java.util.ArrayDeque<>();
    private String lastAttempted;
    private int removedCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        List<String> saved = HomeSelection.list(this);
        if (saved != null) selected.addAll(saved);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(dp(56), dp(34), dp(56), dp(30));

        root.addView(buildHeader());

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        scroller.addView(grid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(buildFooter());
        setContentView(root);

        loadApps(saved == null);
    }

    private View buildHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.BOTTOM);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = label("选择首页应用", 28, Serif.light(this), 0xFFFFFFFF);
        texts.addView(title);
        subtitle = label("确认键 添加 / 移除　·　首页长按可排序　·　返回键 完成", 13,
                Serif.regular(this), 0x8AFFFFFF);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = dp(6);
        texts.addView(subtitle, subParams);
        box.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        counter = label("已选 0 个", 16, Serif.regular(this), 0xCCFFFFFF);
        counter.setGravity(Gravity.END);
        box.addView(counter);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        box.setLayoutParams(params);
        return box;
    }

    private View buildFooter() {
        footerBar = new LinearLayout(this);
        footerBar.setOrientation(LinearLayout.HORIZONTAL);
        footerBar.setGravity(Gravity.CENTER_VERTICAL);
        footerBar.setBackground(rounded(0x14FFFFFF, 30, 0));
        footerBar.setPadding(dp(10), dp(7), dp(10), dp(7));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER);
        wrapper.addView(footerBar);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        wrapper.setLayoutParams(params);

        fillFooter();
        return wrapper;
    }

    /** 两套按钮各管一件事：平时是「首页展示」，卸载模式换成「标记 / 卸载 / 退出」。 */
    private void fillFooter() {
        footerBar.removeAllViews();
        uninstallButton = null;
        if (uninstallMode) {
            uninstallButton = addFooterItem(footerBar, uninstallLabel(), this::startUninstall);
            addFooterItem(footerBar, "全不选", () -> {
                toRemove.clear();
                updateUninstallButton();
                refreshTiles();
            });
            addFooterItem(footerBar, "退出", this::exitUninstallMode);
            return;
        }
        addFooterItem(footerBar, "全选", () -> {
            selected.clear();
            for (AppCatalog.Entry entry : apps) selected.add(entry.pkg);
            markChanged();
            rebuild();
        });
        addFooterItem(footerBar, "清空", () -> {
            selected.clear();
            markChanged();
            rebuild();
        });
        addFooterItem(footerBar, "卸载应用", this::enterUninstallMode);
        addFooterItem(footerBar, "完成", this::finishPicker);
    }

    private String uninstallLabel() {
        return toRemove.isEmpty() ? "卸载应用" : "卸载 " + toRemove.size() + " 个";
    }

    private void updateUninstallButton() {
        if (uninstallButton != null) uninstallButton.setText(uninstallLabel());
    }

    private TextView addFooterItem(LinearLayout bar, String name, Runnable action) {
        TextView item = label(name, 16, Serif.regular(this), 0xE6FFFFFF);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(28), dp(10), dp(28), dp(10));
        item.setFocusable(true);
        item.setOnClickListener(v -> action.run());
        item.setOnFocusChangeListener((v, focused) -> {
            item.setBackground(focused ? rounded(0x3DFFFFFF, 24, 0) : null);
            item.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(130).start();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        bar.addView(item, params);
        return item;
    }

    // ---------------------------------------------------------------- 卸载

    private void enterUninstallMode() {
        uninstallMode = true;
        toRemove.clear();
        title.setText("卸载应用");
        subtitle.setText("确认键 标记要卸载的应用　·　标记完点「卸载」　·　返回键 退出（不影响首页选择）");
        fillFooter();
        refreshTiles();
        focusFirstTile();
    }

    private void exitUninstallMode() {
        uninstallMode = false;
        toRemove.clear();
        title.setText("选择首页应用");
        subtitle.setText("确认键 添加 / 移除　·　首页长按可排序　·　返回键 完成");
        fillFooter();
        refreshTiles();
        focusFirstTile();
        android.util.Log.i("TvHome.Picker", "退出卸载模式");
    }

    /** 页脚按钮被整组换掉后焦点会丢，拉回网格第一个。 */
    private void focusFirstTile() {
        View first = tiles.isEmpty() ? null : tiles.values().iterator().next();
        if (first != null) first.postDelayed(first::requestFocus, 60);
    }

    private void toggleRemove(String pkg) {
        if (pkg.equals(getPackageName())) {
            subtitle.setText("这个就是本应用自己，不能卸载");
            return;
        }
        if (toRemove.contains(pkg)) toRemove.remove(pkg);
        else toRemove.add(pkg);
        updateUninstallButton();
        refreshTiles();
    }

    /** 按标记顺序逐个弹系统卸载框；中途取消就停下，剩下的不卸。 */
    private void startUninstall() {
        if (toRemove.isEmpty()) {
            subtitle.setText("还没标记任何应用，先在网格里按确认键选几个");
            return;
        }
        pendingUninstall.clear();
        pendingUninstall.addAll(toRemove);
        removedCount = 0;
        android.util.Log.i("TvHome.Picker", "开始批量卸载 " + pendingUninstall.size() + " 个");
        nextUninstall();
    }

    private void nextUninstall() {
        String pkg = pendingUninstall.poll();
        if (pkg == null) {
            finishUninstall(false);
            return;
        }
        lastAttempted = pkg;
        subtitle.setText("正在卸载 " + labelOf(pkg) + "　·　还剩 " + pendingUninstall.size() + " 个");
        try {
            startActivityForResult(new Intent(Intent.ACTION_DELETE,
                    android.net.Uri.parse("package:" + pkg)), REQ_UNINSTALL);
        } catch (RuntimeException unavailable) {
            lastAttempted = null;
            nextUninstall();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_UNINSTALL) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        // 不靠 resultCode：有的系统卸载成功也回 CANCELED。直接看包还在不在，最准。
        // 系统删包要几百毫秒，轮询几次再判「用户取消了」。
        final String pkg = lastAttempted;
        lastAttempted = null;
        handler.postDelayed(() -> verifyRemoval(pkg, 0), 500);
    }

    private void verifyRemoval(String pkg, int attempt) {
        if (pkg != null && !installed(pkg)) {
            removedCount++;
            nextUninstall();
            return;
        }
        if (attempt < 2) {
            handler.postDelayed(() -> verifyRemoval(pkg, attempt + 1), 400);
            return;
        }
        pendingUninstall.clear();
        finishUninstall(true);
    }

    private void finishUninstall(boolean stopped) {
        final int done = removedCount;
        pendingUninstall.clear();
        uninstallMode = false;
        toRemove.clear();
        title.setText("选择首页应用");
        fillFooter();
        if (done > 0) {
            // 卸载掉的从首页选择里一起摘掉，不然「已选 N 个」会虚高
            for (String pkg : new ArrayList<>(selected)) {
                if (!installed(pkg)) {
                    selected.remove(pkg);
                    markChanged();
                }
            }
        }
        loadApps(false);
        subtitle.setText(stopped
                ? "已卸载 " + done + " 个，剩下的没动"
                : (done > 0 ? "已卸载 " + done + " 个" : "没有卸载任何应用"));
        android.util.Log.i("TvHome.Picker", "批量卸载结束 成功 " + done + " 个，中断=" + stopped);
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException missing) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 数据

    private void loadApps(boolean firstTime) {
        if (firstTime) subtitle.setText("当前首页显示全部应用　·　勾选后只显示你选的这几个");
        new Thread(() -> {
            final List<AppCatalog.Entry> loaded = AppCatalog.load(getApplicationContext());
            handler.post(() -> {
                apps.clear();
                apps.addAll(loaded);
                rebuild();
            });
        }, "picker-scan").start();
    }

    /** 已选的按首页顺序排在前面，未选的按扫描顺序跟在后面。 */
    private List<AppCatalog.Entry> orderForGrid() {
        Map<String, AppCatalog.Entry> byPkg = new LinkedHashMap<>();
        for (AppCatalog.Entry entry : apps) byPkg.put(entry.pkg, entry);
        List<AppCatalog.Entry> ordered = new ArrayList<>();
        for (String pkg : HomeSelection.order(this, new ArrayList<>(selected))) {
            AppCatalog.Entry entry = byPkg.get(pkg);
            if (entry != null) ordered.add(entry);
        }
        for (AppCatalog.Entry entry : apps) {
            if (!selected.contains(entry.pkg)) ordered.add(entry);
        }
        return ordered;
    }

    private void rebuild() {
        if (grid == null) return;
        String keep = null;
        View focused = getCurrentFocus();
        for (Map.Entry<String, LinearLayout> entry : tiles.entrySet()) {
            if (entry.getValue() == focused) { keep = entry.getKey(); break; }
        }
        grid.removeAllViews();
        tiles.clear();
        names.clear();
        icons.clear();

        List<AppCatalog.Entry> ordered = orderForGrid();
        if (ordered.isEmpty()) {
            TextView empty = label("没有找到可启动的应用", 16, Serif.regular(this), 0x99FFFFFF);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(80), 0, dp(80));
            grid.addView(empty, new GridLayout.LayoutParams());
            updateCounter();
            return;
        }

        for (AppCatalog.Entry entry : ordered) {
            LinearLayout tile = buildTile(entry);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(TILE_HEIGHT);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(tile, params);
            tiles.put(entry.pkg, tile);
        }
        refreshTiles();
        View focusTarget = keep != null ? tiles.get(keep) : null;
        if (focusTarget == null && !tiles.isEmpty()) focusTarget = tiles.values().iterator().next();
        final View target = focusTarget;
        if (target != null) target.postDelayed(target::requestFocus, 60);
    }

    private LinearLayout buildTile(AppCatalog.Entry entry) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setPadding(dp(4), dp(8), dp(4), dp(6));
        tile.setContentDescription(entry.label);

        ImageView icon = new ImageView(this);
        if (entry.icon != null) icon.setImageDrawable(entry.icon);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(84), dp(84)));

        TextView name = label(entry.label, 14, Serif.regular(this), 0xFFFFFFFF);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.topMargin = dp(8);
        tile.addView(name, nameParams);

        tiles.put(entry.pkg, tile);
        names.put(entry.pkg, name);
        icons.put(entry.pkg, icon);

        tile.setOnClickListener(v -> {
            if (uninstallMode) toggleRemove(entry.pkg);
            else toggle(entry.pkg);
        });
        tile.setOnFocusChangeListener((v, focused) -> {
            applyTileStyle(tile, entry.pkg, focused);
            if (focused) scrollIntoView(tile);
        });
        return tile;
    }

    private void toggle(String pkg) {
        if (selected.contains(pkg)) selected.remove(pkg);
        else selected.add(pkg);
        markChanged();
        rebuild();
        View tile = tiles.get(pkg);
        if (tile != null) tile.post(tile::requestFocus);
    }

    private void markChanged() {
        changed = true;
    }

    private void refreshTiles() {
        int position = 0;
        for (AppCatalog.Entry entry : orderForGrid()) {
            TextView name = names.get(entry.pkg);
            View tile = tiles.get(entry.pkg);
            if (name == null || tile == null) continue;

            if (uninstallMode) {
                // 卸载模式只加红框，不显示首页序号——两套选择互不干扰
                boolean marked = toRemove.contains(entry.pkg);
                name.setText(entry.label);
                name.setTypeface(marked ? Serif.bold(this) : Serif.regular(this));
                name.setTextColor(marked ? 0xFFFF9C9C : 0x99FFFFFF);
                applyTileStyle(tile, entry.pkg, tile.isFocused());
                continue;
            }

            boolean chosen = selected.contains(entry.pkg);
            if (chosen) position++;
            if (chosen) {
                String prefix = position + " ";
                SpannableString text = new SpannableString(prefix + entry.label);
                text.setSpan(new ForegroundColorSpan(0xFF9BD1FF), 0, prefix.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                name.setText(text);
            } else {
                name.setText(entry.label);
            }
            name.setTypeface(chosen ? Serif.bold(this) : Serif.regular(this));
            name.setTextColor(chosen ? 0xFFFFFFFF : 0x99FFFFFF);
            applyTileStyle(tile, entry.pkg, tile.isFocused());
        }
        updateCounter();
    }

    private void applyTileStyle(View tile, String pkg, boolean focused) {
        if (tile == null) return;
        if (uninstallMode) {
            boolean marked = toRemove.contains(pkg);
            if (marked) {
                tile.setBackground(rounded(0x33FF6B6B, 14, 0xFFFF7B7B));
            } else {
                tile.setBackground(focused ? rounded(0x33FFFFFF, 14, 0) : null);
            }
            tile.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(120).start();
            ImageView mark = icons.get(pkg);
            // 未标记的跟平时一样，别压暗——暗色反而容易被理解成「这些要被卸」
            if (mark != null) mark.setAlpha(marked || focused ? 1f : 0.72f);
            return;
        }
        boolean chosen = selected.contains(pkg);
        if (focused) {
            tile.setBackground(rounded(0x33FFFFFF, 14, 0));
        } else if (chosen) {
            tile.setBackground(rounded(0x14FFFFFF, 14, 0));
        } else {
            tile.setBackground(null);
        }
        tile.animate().scaleX(focused ? 1.06f : 1f)
                .scaleY(focused ? 1.06f : 1f).setDuration(120).start();
        ImageView icon = icons.get(pkg);
        if (icon != null) icon.setAlpha(focused || chosen ? 1f : 0.7f);
    }

    private void updateCounter() {
        if (uninstallMode) {
            counter.setText(toRemove.isEmpty() ? "未标记" : "已标记 " + toRemove.size() + " 个");
            return;
        }
        int hidden = apps.size() - selected.size();
        boolean showAll = !changed && !HomeSelection.curated(this);
        counter.setText(selected.isEmpty() ? (showAll ? "已选 0 个（显示全部）" : "已选 0 个（首页不展示应用）")
                : "已选 " + selected.size() + " 个　未选 " + Math.max(0, hidden));
    }
    private String labelOf(String pkg) {
        for (AppCatalog.Entry entry : apps) if (entry.pkg.equals(pkg)) return entry.label;
        return pkg;
    }

    private void scrollIntoView(View view) {
        if (scroller == null) return;
        int top = view.getTop() - (scroller.getHeight() - view.getHeight()) / 2;
        scroller.smoothScrollTo(0, Math.max(0, top));
    }

    // ---------------------------------------------------------------- 收尾

    private void finishPicker() {
        if (changed) HomeSelection.save(this, selected);
        Intent result = new Intent();
        result.putExtra(EXTRA_CHANGED, changed);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override public void onBackPressed() {
        if (uninstallMode) {
            exitUninstallMode();
            return;
        }
        finishPicker();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView label(String value, float size, Typeface face, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(2), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
