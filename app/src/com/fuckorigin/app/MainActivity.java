package com.fuckorigin.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 界面结构对齐 HyperCeiler（源码依据）：
 *   activity_home.xml → HomeViewPage2(ViewPager2) + 底部 NavigationStyle.BOTTOM_LABEL 三标签
 *   fragment_page.xml → NestedHeaderLayout(搜索栏) + scrollable 内容
 *   item_app_list.xml → 行：图标 40dp + 左右 15dp + 标题 18sp + chevron
 *   home_settings.xml → 设置页 = 分组偏好列表
 * 差异：列表按「功能分类」而不是按应用作用域。
 */
public class MainActivity extends Activity {

    private FrameLayout content;
    private LinearLayout bottomBar;
    private final Deque<View> stack = new ArrayDeque<>();
    private int tab = 0;
    private String query = "";
    private boolean root = false;
    private String devLine;
    private EditText searchEdit;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LsposedFix.autoSync(getApplicationContext());
        handleFaithIntent(getIntent());
        Bridge.sync(getApplicationContext());

        // 启动自检：模块状态 + 一键安装
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!ShellExec.hasRoot()) return;
                    final boolean ready = ModuleStatus.allReady();
                    final String sum = ModuleStatus.summary();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showModuleDialog(ready, sum); }
                    });
                } catch (Throwable ignored) { }
            }
        }, "module-check").start();

        if (getIntent() != null && getIntent().getBooleanExtra("unlock", false)) {
            String[] ids = { "q2.unlock.all", "thermal.bypass.global", "perf.monsterplus.force",
                    "q2.mask.memc", "q2.mask.sr", "q2.mask.ve", "perf.fps.lock" };
            for (String id : ids) {
                Feature f = Catalog.feature(id);
                if (f != null) Actions.apply(this, f, true);   // 走真实动作，不只是写 prefs
            }
            Bridge.sync(this);
            android.util.Log.i("FuckOrigin", "PREFS|已应用 " + ids.length + " 项解锁开关");
            finish();
            return;
        }
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(getColor(R.color.bg));
        rootLayout.setFitsSystemWindows(false);
        applyInsets(rootLayout);

        content = new FrameLayout(this);
        rootLayout.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        View bottomDivider = new View(this);
        bottomDivider.setBackgroundColor(getColor(R.color.divider));
        rootLayout.addView(bottomDivider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(getColor(R.color.card));
        bottomBar.setPadding(0, 0, 0, 0);
        rootLayout.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(52)));

        setContentView(rootLayout);
        rootLayout.post(new Runnable() { public void run() {
            int navPx = measureNavBarPx();
            android.util.Log.i("FO_INSETS", "navPx=" + navPx + " rootH=" + rootLayout.getHeight()
                    + " barH=" + bottomBar.getHeight());
            // 底栏白底铺到屏幕底部，内容上移；根布局不再留白（否则底部会出现灰条）
            if (navPx > 0) {
                bottomBar.setPadding(0, 0, 0, navPx);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bottomBar.getLayoutParams();
                if (lp != null) { lp.height = dp(52) + navPx; bottomBar.setLayoutParams(lp); }
            }
            rootLayout.setPadding(0, 0, 0, 0);
        }});
        buildBottomBar();
        // 调试入口：--ez settings true → 直接打开设置页（用于自动化截图与验证）
        showTab(getIntent() != null && getIntent().getBooleanExtra("settings", false) ? 1 : 0);
        // 调试入口二：--es feature thermal.bypass.global → 直达功能详情页
        if (getIntent() != null && getIntent().getStringExtra("feature") != null) {
            Feature df = Catalog.feature(getIntent().getStringExtra("feature"));
            if (df != null) content.post(new Runnable() { public void run() { push(featurePage(df)); } });
        }

        ui.postDelayed(new Runnable() { public void run() {
            if (Pem.isOn(MainActivity.this) && System.currentTimeMillis() - Pem.onAt(MainActivity.this) > 60_000L) {
                final String r = Pem.disable(MainActivity.this);
                Toast.makeText(MainActivity.this, "PEM 实验到期，已自动恢复：" + r, Toast.LENGTH_LONG).show();
                showTab(tab);
            }
            ui.postDelayed(this, 15_000L);
        }}, 15_000L);
        new Thread(new Runnable() { public void run() {
            Env.probe();
            final String dev = DeviceInfo.line();
            ui.post(new Runnable() { public void run() {
                root = Env.root; devLine = dev;
                showTab(tab);
                warnIfNeeded();
            }});
        }}).start();
    }

    // ── 底部三标签（NavigationStyle.BOTTOM_LABEL）──────────────────────
    private void buildBottomBar() {
        bottomBar.removeAllViews();
        String[] labels = { "主页", "设置", "关于" };
        int[] icons = { R.drawable.ic_tab_home, R.drawable.ic_tab_settings, R.drawable.ic_tab_info };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);   // 图标与文字都水平居中，保证对齐
            item.setBaselineAligned(false);
            item.setPadding(0, dp(8), 0, dp(2));
            item.setBackgroundResource(R.drawable.bg_tab_ripple);
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(getColor(idx == tab ? R.color.tab_on : R.color.tab_off));
            ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(dp(22), dp(22));
            icp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(ic, icp);
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextSize(11);
            tv.setGravity(Gravity.CENTER);                 // 文字自身居中
            tv.setIncludeFontPadding(false);               // 去掉字体额外留白，避免上下不齐
            tv.setTextColor(getColor(idx == tab ? R.color.tab_on : R.color.tab_off));
            LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(-2, -2);
            tcp.gravity = Gravity.CENTER_HORIZONTAL;
            tcp.topMargin = dp(3);
            item.addView(tv, tcp);
            item.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(idx); } });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
            bottomBar.addView(item, p);
        }
    }

    private void showTab(int t) {
        tab = t;
        stack.clear();
        buildBottomBar();
        if (t == 0) setContent(homePage());
        else if (t == 1) setContent(settingsPage());
        else setContent(aboutPage());
    }

    private void setContent(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0f);
        v.setTranslationY(dp(10));
        v.animate().alpha(1f).translationY(0f).setDuration(160).start();   // 轻量转场
    }

    private void push(View v) { stack.push(v); setContent(v); }

    @Override public void onBackPressed() {
        if (!stack.isEmpty()) {
            stack.pop();
            if (stack.isEmpty()) showTab(tab); else setContent(stack.peek());
            return;
        }
        if (tab != 0) { showTab(0); return; }
        super.onBackPressed();
    }

    // ── 主页：大标题 + 搜索 + 提示卡 + 功能分类卡片 ─────────────────────
    private View homePage() {
        ScrollView sv = scroll();
        LinearLayout col = column(sv);

        TextView title = tv("FuckOrigin", 34, R.color.text, Typeface.NORMAL);
        title.setPadding(dp(6), dp(18), 0, dp(4));
        col.addView(title);
        TextView sub = tv(getString(R.string.subtitle), 13, R.color.dim, Typeface.NORMAL);
        sub.setPadding(dp(6), 0, 0, dp(16));
        col.addView(sub);

        col.addView(searchBar());

        if (Env.probed) {
            String issues = Env.issues();
            if (issues.length() > 0) col.addView(banner(issues));
        }

        if (!query.isEmpty()) {
            // 搜索态：平铺结果，一张卡装完
            List<Feature> hit = new ArrayList<>();
            for (Catalog.Domain d : Catalog.domains()) hit.addAll(filter(Catalog.of(d.id)));
            col.addView(sectionHeader("搜索结果 " + hit.size() + " 项"));
            LinearLayout g = cardGroup();
            for (int i = 0; i < hit.size(); i++) {
                final Feature f = hit.get(i);
                g.addView(searchRow(f));
                if (i < hit.size() - 1) g.addView(divider());
            }
            col.addView(g);
            if (hit.isEmpty()) col.addView(tipCard("没有匹配的功能"));
            return sv;
        }

        col.addView(tipCard("Tip · 所有功能都在本页按域展开，常用 4 项在「快捷开关」卡；点任意条目可看细节与诊断"));

        // 快捷开关：一张卡装 4 个开关
        col.addView(sectionHeader("快捷开关"));
        LinearLayout quick = cardGroup();
        String[] quickIds = { "thermal.bypass.global", "perf.cpufreq.max", "perf.gpu.floor", "update.freeze" };
        int qn = 0;
        for (String qid : quickIds) {
            Feature f = Catalog.feature(qid);
            if (f == null) continue;
            if (qn++ > 0) quick.addView(divider());
            quick.addView(featureRow(f));
        }
        col.addView(quick);

        // 各域：每域一张卡，卡内最多 3 条 + 查看全部
        for (Catalog.Domain d : Catalog.domains()) {
            List<Feature> list = new ArrayList<>();
            for (Feature f : filter(Catalog.of(d.id))) {
                if (isQuick(f.id)) continue;   // 快捷开关卡里已出现，不重复
                list.add(f);
            }
            if (list.isEmpty()) continue;
            int on = Catalog.enabledCount(this, d.id);
            col.addView(sectionHeader(d.name + "  " + on + "/" + Catalog.of(d.id).size()));
            LinearLayout g = cardGroup();
            // 同页展开：该域全部条目直接铺在这张卡里，不再跳二级页
            for (int i = 0; i < list.size(); i++) {
                g.addView(featureRow(list.get(i)));
                if (i < list.size() - 1) g.addView(divider());
            }
            col.addView(g);
        }
        return sv;
    }

    private static final String[] QUICK_IDS = { "thermal.bypass.global", "perf.cpufreq.max", "perf.gpu.floor", "update.freeze" };

    private boolean isQuick(String id) {
        for (String q : QUICK_IDS) if (q.equals(id)) return true;
        return false;
    }

    /** "查看全部" 行 */
    private View moreRow(String text, final String domainId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        row.addView(tv(text, 15, R.color.accent, Typeface.NORMAL), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(chevron());
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { push(categoryPage(domainId)); }
        });
        return row;
    }

    /** 搜索结果行：标题 + 域标签 + 目标 */
    private View searchRow(final Feature f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout t1 = new LinearLayout(this);
        t1.setOrientation(LinearLayout.HORIZONTAL);
        t1.setGravity(Gravity.CENTER_VERTICAL);
        t1.addView(statusDot(f.status));
        t1.addView(tv(f.name, 15, R.color.text, Typeface.NORMAL));
        texts.addView(t1);
        Catalog.Domain d = Catalog.domain(f.domain);
        TextView meta = tv((d == null ? "" : d.name + " · ") + f.kind + " · " + f.risk, 11, R.color.dim, Typeface.NORMAL);
        meta.setPadding(dp(16), dp(2), 0, 0);
        texts.addView(meta);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(chevron());
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { push(featurePage(f)); }
        });
        return row;
    }

    // ── 域详情：该域下的功能条目（卡片组 + 开关）──────────────────────
    private View categoryPage(String domainId) {
        Catalog.Domain d = Catalog.domain(domainId);
        ScrollView sv = scroll();
        LinearLayout col = column(sv);
        col.addView(backRow(d == null ? "" : d.name));
        TextView title = tv(d == null ? "" : d.name, 28, R.color.text, Typeface.NORMAL);
        title.setPadding(dp(4), dp(2), 0, dp(2));
        col.addView(title);
        TextView sub = tv(d == null ? "" : (d.pkg + " · " + d.desc), 12, R.color.dim, Typeface.NORMAL);
        sub.setPadding(dp(6), 0, 0, dp(16));
        col.addView(sub);

        LinearLayout group = cardGroup();
        List<Feature> list = filter(Catalog.of(d == null ? "" : d.id));
        for (int i = 0; i < list.size(); i++) {
            final Feature f = list.get(i);
            group.addView(featureRow(f));
            if (i < list.size() - 1) group.addView(divider());
        }
        col.addView(group);
        return sv;
    }

    private int groupOn(Feature f) {
        int n = 0;
        for (String cid : f.children) {
            Feature c = Catalog.feature(cid);
            if (c != null && c.enabled(this)) n++;
        }
        return n;
    }

    private View featureRow(final Feature f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(statusDot(f.status));
        titleRow.addView(tv(f.name, 16, R.color.text, Typeface.NORMAL));
        texts.addView(titleRow);
        String metaText = f.isGroup()
                ? ("合并 " + f.children.length + " 项 · 已开 " + groupOn(f))
                : (f.status + " · " + f.kind + " · 风险 " + f.risk);
        TextView meta = tv(metaText, 13, R.color.dim, Typeface.NORMAL);
        meta.setPadding(0, dp(3), 0, 0);
        texts.addView(meta);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        if (f.toggleable()) {
            MiuixSwitch sw = new MiuixSwitch(this);
            sw.setChecked(f.enabled(this));
            sw.setOnCheckedChangeListener(new MiuixSwitch.OnCheckedChangeListener() {
                public void onCheckedChanged(MiuixSwitch v, boolean on) { runAction(f, on); }
            });
            row.addView(sw);
        } else {
            row.addView(chevron());
        }
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { push(featurePage(f)); } });
        return row;
    }

    // ── 单功能详情 ───────────────────────────────────────────────────
    private View featurePage(final Feature f) {
        if ("diag.live".equals(f.id)) return panePage(f, Live.build(this));
        if ("q2.thresholds".equals(f.id)) return panePage(f, textPane(Thresholds.dump()));
        if ("hook.status".equals(f.id)) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            final android.widget.ScrollView hookScroll = new android.widget.ScrollView(this);
            final android.widget.TextView hookPane = new android.widget.TextView(this);
            hookPane.setText("检测中…");
            hookPane.setTextSize(13);
            hookPane.setTextColor(getColor(R.color.text));
            int pad = dp(16);
            hookPane.setPadding(pad, pad, pad, pad);
            hookScroll.addView(hookPane);
            box.addView(hookScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
            new Thread(new Runnable() { public void run() {
                final String txt = HookStatus.text() + "\n\n模块路径：" + LsposedFix.lastResult()
                        + "\n标记来源：logcat -s " + HookStatus.TAG;
                ui.post(new Runnable() { public void run() { hookPane.setText(txt); }});
            }}).start();
            android.widget.Button restart = new android.widget.Button(this);
            restart.setText("重启游戏魔盒（让 Hook 生效）");
            restart.setTextColor(getColor(R.color.on_accent));
            restart.setBackgroundResource(R.drawable.bg_button);
            restart.setAllCaps(false);
            restart.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ShellExec.R r = ShellExec.su("am force-stop com.vivo.gamecube");
                    info("已执行", r.ok() ? "游戏魔盒已停止，重新打开任意游戏即可触发加载。"
                            : ("失败：" + r.out.trim()));
                }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(46));
            bp.topMargin = dp(12);
            box.addView(restart, bp);
            return panePage(f, box);
        }

        ScrollView sv = scroll();
        LinearLayout col = column(sv);
        Catalog.Domain d = Catalog.domain(f.domain);
        col.addView(backRow(d == null ? "" : d.name));

        // 标题 + 状态徽章
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv(f.name, 26, R.color.text, Typeface.NORMAL);
        titleRow.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        titleRow.addView(badge(f.status));
        col.addView(titleRow);

        TextView sub = tv(f.kind + " · 风险 " + f.risk + (d == null ? "" : " · " + d.name),
                13, R.color.dim, Typeface.NORMAL);
        sub.setPadding(0, dp(4), 0, dp(14));
        col.addView(sub);

        // 实时状态卡
        col.addView(sectionHeader("实时状态"));
        LinearLayout live = cardGroup();
        final TextView hookTv = tv("检测中…", 14, R.color.dim, Typeface.NORMAL);
        LinearLayout hookRow = new LinearLayout(this);
        hookRow.setOrientation(LinearLayout.VERTICAL);
        hookRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        hookRow.addView(tv("Hook 注入", 12, R.color.dim, Typeface.NORMAL));
        hookTv.setPadding(0, dp(3), 0, 0);
        hookRow.addView(hookTv);
        live.addView(hookRow);
        new Thread(new Runnable() { public void run() {
            final String st = HookStatus.text();
            ui.post(new Runnable() { public void run() { hookTv.setText(st); }});
        }}).start();
        live.addView(divider());
        final Feature ff = f;
        final TextView anchorTv = tv("检测中…", 13, R.color.dim, Typeface.NORMAL);
        LinearLayout anchorRow = new LinearLayout(this);
        anchorRow.setOrientation(LinearLayout.VERTICAL);
        anchorRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        anchorRow.addView(tv("目标锚点", 12, R.color.dim, Typeface.NORMAL));
        anchorTv.setTextSize(13);
        anchorRow.addView(anchorTv);
        live.addView(anchorRow);
        col.addView(live);
        new Thread(new Runnable() { public void run() {
            final String a = Anchor.check(MainActivity.this, ff);
            ui.post(new Runnable() { public void run() {
                if (a.length() == 0) anchorTv.setText("—（非 Hook 类功能）");
                else anchorTv.setText(a);
            }});
        }}).start();

        // 开关卡
        if (f.toggleable()) {
            col.addView(sectionHeader("开关"));
            LinearLayout g2 = cardGroup();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(14), dp(12));
            row.addView(tv("启用", 17, R.color.text, Typeface.NORMAL), new LinearLayout.LayoutParams(0, -2, 1f));
            MiuixSwitch sw = new MiuixSwitch(this);
            sw.setChecked(f.enabled(this));
            sw.setOnCheckedChangeListener(new MiuixSwitch.OnCheckedChangeListener() {
                public void onCheckedChanged(MiuixSwitch v, boolean on) { runAction(f, on); }
            });
            row.addView(sw);
            g2.addView(row);
            col.addView(g2);
        }

        // 操作卡
        col.addView(sectionHeader("操作"));
        LinearLayout acts = cardGroup();
        acts.addView(actionRow("重启游戏魔盒", "让 Hook 立刻重新注入", new Runnable() {
            public void run() {
                ShellExec.R r = ShellExec.su("am force-stop com.vivo.gamecube");
                info("已执行", r.ok() ? "游戏魔盒已停止，重新打开任意游戏即可。" : ("失败：" + r.out.trim()));
            }
        }));
        acts.addView(divider());
        acts.addView(actionRow("重新检测锚点", "清除缓存后重新在 APK 里查类", new Runnable() {
            public void run() {
                Prefs.setS(MainActivity.this, "anchor." + ff.id, null);
                Toast.makeText(MainActivity.this, "缓存已清除，返回再进本页即可重测", Toast.LENGTH_SHORT).show();
            }
        }));
        acts.addView(divider());
        acts.addView(actionRow("设备诊断", "温度 / 频率 / 热状态", new Runnable() {
            public void run() { showDiag(); }
        }));
        col.addView(acts);

        // 说明
        col.addView(sectionHeader("说明"));
        col.addView(tipCard(f.note + "\n\n目标：" + f.target));
        return sv;
    }

    /** 状态徽章：已实现=绿、已定位=蓝、待实验=黄、其他=灰 */
    private View badge(String status) {
        int color;
        if (status != null && status.contains("已实现")) color = R.color.ok;
        else if (status != null && status.contains("已定位")) color = R.color.accent;
        else if (status != null && status.contains("待实验")) color = R.color.warn;
        else color = R.color.dim;
        TextView t = tv(status == null ? "-" : status, 11, color, Typeface.BOLD);
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    /** 带标题的自定义内容页 */
    private View panePage(Feature f, View content) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(getColor(R.color.bg));
        col.setPadding(dp(16), dp(8), dp(16), dp(78));
        col.addView(backRow(Catalog.domain(f.domain) == null ? "" : Catalog.domain(f.domain).name));
        col.addView(tv(f.name, 28, R.color.text, Typeface.NORMAL));
        TextView sub = tv(f.target, 11, R.color.dim, Typeface.NORMAL);
        sub.setPadding(0, dp(2), 0, dp(12));
        col.addView(sub);
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        return col;
    }

    /** 等宽文本面板（用于阈值表 / Hook 状态） */
    private View textPane(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(getColor(R.color.text));
        t.setBackgroundResource(R.drawable.bg_card);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        ScrollView sc = new ScrollView(this);
        sc.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return sc;
    }

    // ── 设置页（对应 home_settings.xml 的分组偏好列表）─────────────────
    private View settingsPage() {
        ScrollView sv = scroll();
        LinearLayout col = column(sv);
        TextView title = tv("设置", 34, R.color.text, Typeface.NORMAL);
        title.setPadding(dp(6), dp(18), 0, dp(16));
        col.addView(title);

        col.addView(sectionHeader("显示"));
        LinearLayout g1 = cardGroup();
        g1.addView(desktopIconRow());
        g1.addView(divider());
        g1.addView(choiceRow("设置图标", "设置模块入口在系统设置中的位置", Prefs.SET_ICON,
                new String[] { "禁用", "默认" }));
        g1.addView(divider());
        g1.addView(choiceRow("语言", "界面语言（当前仅文案切换）", Prefs.SET_LANG,
                new String[] { "简体中文", "English" }));
        g1.addView(divider());
        g1.addView(choiceRow("主页入口设置", "启动时停在哪个页面", Prefs.SET_ENTRY,
                new String[] { "首页", "上次页面" }));
        col.addView(g1);

        col.addView(sectionHeader("备份与恢复"));
        LinearLayout g2 = cardGroup();
        g2.addView(actionRow("备份模块配置", "导出当前开关到应用目录", new Runnable() {
            public void run() { info("备份", Config.export(MainActivity.this)); }
        }));
        g2.addView(divider());
        g2.addView(actionRow("恢复模块配置", "从备份文件恢复开关", new Runnable() {
            public void run() { pickBackup(); }
        }));
        g2.addView(divider());
        g2.addView(actionRow("重置模块配置", "清除全部本地设置", new Runnable() {
            public void run() { confirmReset(); }
        }));
        col.addView(g2);

        col.addView(sectionHeader("故障排查"));
        LinearLayout g3 = cardGroup();
        g3.addView(actionRow("一键恢复默认", "回滚所有已开启的 sysfs / 设置项", new Runnable() {
            public void run() { doRestore(); }
        }));
        g3.addView(divider());
        g3.addView(actionRow("设备诊断", "温度 / 频率 / 热状态 / core_ctl", new Runnable() {
            public void run() { showDiag(); }
        }));
        g3.addView(divider());
        g3.addView(actionRow("清理临时文件", "删除 /data/local/tmp 下的标记文件", new Runnable() {
            public void run() { info("清理", Config.cleanTmp()); }
        }));
        col.addView(g3);
        return sv;
    }

    /** 桌面图标：启用/禁用 launcher alias */
    private View desktopIconRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv("桌面图标", 17, R.color.text, Typeface.NORMAL));
        texts.addView(tv("在桌面显示 FuckOrigin 图标", 13, R.color.dim, Typeface.NORMAL));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final MiuixSwitch sw = new MiuixSwitch(this);
        sw.setChecked(isLauncherEnabled());
        sw.setOnCheckedChangeListener(new MiuixSwitch.OnCheckedChangeListener() {
            public void onCheckedChanged(MiuixSwitch v, boolean on) {
                setLauncherEnabled(on);
                Toast.makeText(MainActivity.this, on ? "已显示桌面图标" : "已隐藏桌面图标", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(sw);
        return row;
    }

    private boolean isLauncherEnabled() {
        try {
            int st = getPackageManager().getComponentEnabledSetting(
                    new android.content.ComponentName(this, getPackageName() + ".LauncherAlias"));
            return st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) { return true; }
    }

    private void setLauncherEnabled(boolean on) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new android.content.ComponentName(this, getPackageName() + ".LauncherAlias"),
                    on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                       : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) { }
    }

    /** 单选设置项：右侧显示当前值，点击弹 miuix 选择框 */
    private View choiceRow(final String title, String summary, final String key, final String[] options) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv(title, 17, R.color.text, Typeface.NORMAL));
        if (summary != null && summary.length() > 0) {
            TextView s = tv(summary, 13, R.color.dim, Typeface.NORMAL);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = tv(Prefs.getS(this, key, options[0]), 14, R.color.dim, Typeface.NORMAL);
        row.addView(value);
        View gap = new View(this); row.addView(gap, new LinearLayout.LayoutParams(dp(6), 1));
        row.addView(chevron());
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LinearLayout list = new LinearLayout(MainActivity.this);
                list.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < options.length; i++) {
                    final String opt = options[i];
                    TextView o = tv(opt, 16,
                            opt.equals(Prefs.getS(MainActivity.this, key, options[0])) ? R.color.accent : R.color.text,
                            Typeface.NORMAL);
                    o.setPadding(dp(6), dp(14), dp(6), dp(14));
                    o.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View vv) {
                            Prefs.setS(MainActivity.this, key, opt);
                            value.setText(opt);
                            if (Prefs.SET_ICON.equals(key)) { }
                        }
                    });
                    list.addView(o);
                    if (i < options.length - 1) list.addView(divider());
                }
                new MDialog(MainActivity.this).title(title).custom(list, 0).positive("关闭", null).show();
            }
        });
        return row;
    }

    /** 普通动作行 */
    private View actionRow(String title, String summary, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv(title, 17, R.color.text, Typeface.NORMAL));
        if (summary != null && summary.length() > 0) {
            TextView s = tv(summary, 13, R.color.dim, Typeface.NORMAL);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(chevron());
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return row;
    }

    private void pickBackup() {
        final String[] files = Config.list(this);
        if (files.length == 0) { info("恢复模块配置", "没有找到备份文件\n\n先点「备份模块配置」导出一份。"); return; }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < files.length && i < 8; i++) {
            final String name = files[i];
            TextView o = tv(name, 14, R.color.text, Typeface.NORMAL);
            o.setPadding(dp(6), dp(12), dp(6), dp(12));
            o.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { info("恢复结果", Config.restore(MainActivity.this, name)); }
            });
            list.addView(o);
            if (i < Math.min(files.length, 8) - 1) list.addView(divider());
        }
        new MDialog(this).title("选择备份").custom(list, 0).positive("取消", null).show();
    }

    private void confirmReset() {
        new MDialog(this).title("重置模块配置")
            .message("只清除本应用内的开关记录，不会回滚已经写入的系统状态。\n如需回滚系统状态请用「一键恢复默认」。")
            .negative("取消", null)
            .positive("重置", new MDialog.OnClick() {
                public void run(android.app.Dialog d) { info("重置结果", Config.reset(MainActivity.this)); showTab(tab); }
            }).show();
    }

    private void info(String title, String msg) {
        new MDialog(this).title(title).message(msg).positive("好", null).show();
    }

    private View aboutPage() {
        ScrollView sv = scroll();
        LinearLayout col = column(sv);
        TextView title = tv("关于", 34, R.color.text, Typeface.NORMAL);
        title.setPadding(dp(6), dp(18), 0, dp(16));
        col.addView(title);

        LinearLayout g = cardGroup();
        g.addView(kvRow("应用", "FuckOrigin 0.1.1 beta（自编译，非商店分发）"));
        g.addView(kvRow("作者", "hmascs"));
        g.addView(kvRow("初期资金", "Nick Ji"));
        g.addView(divider());
        g.addView(kvRow("设备", devLine == null ? "读取中…" : devLine));
        g.addView(divider());
        g.addView(kvRow("界面", "结构参照 HyperCeiler（miuix 设计语言）；列表按功能分类"));
        g.addView(divider());
        g.addView(kvRow("证据", "文档/FuckOrigin-OriginOS-技术方案-v1.0.md"));
        col.addView(g);
        return sv;
    }

    // ── 通用组件 ─────────────────────────────────────────────────────
    private ScrollView scroll() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.bg));
        sv.setFillViewport(true);
        return sv;
    }

    private LinearLayout column(ScrollView sv) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(10), dp(16), dp(78));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return col;
    }

    private View searchBar() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.bg_search);
        box.setPadding(dp(16), 0, dp(16), 0);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_search);
        box.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
        EditText et = new EditText(this);
        et.setHint("搜索");
        et.setTextSize(15);
        et.setTextColor(getColor(R.color.text));
        et.setHintTextColor(getColor(R.color.dim));
        et.setBackground(null);
        et.setSingleLine(true);
        et.setPadding(dp(12), dp(14), dp(4), dp(14));
        searchEdit = et;
        if (!query.isEmpty()) { et.setText(query); et.setSelection(query.length()); }
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim();
                if (!q.equals(query)) { query = q; rerenderHome(); }
            }
        });
        box.addView(et, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.topMargin = dp(2);
        p.bottomMargin = dp(10);
        box.setLayoutParams(p);
        return box;
    }

    private void rerenderHome() {
        setContent(homePage());
        if (searchEdit != null) {
            searchEdit.requestFocus();
            try { searchEdit.setSelection(searchEdit.getText().length()); } catch (Throwable ignored) { }
        }
    }

    private View banner(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(getColor(R.color.banner_text));
        tv.setBackgroundResource(R.drawable.bg_banner);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        tv.setLayoutParams(p);
        return tv;
    }

    private View tipCard(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(getColor(R.color.dim));
        tv.setBackgroundResource(R.drawable.bg_card);
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(4);
        tv.setLayoutParams(p);
        return tv;
    }

    private View sectionHeader(String text) {
        TextView tv = tv(text, 13, R.color.category, Typeface.NORMAL);
        tv.setPadding(dp(6), dp(20), dp(6), dp(8));
        return tv;
    }

    private LinearLayout cardGroup() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackgroundResource(R.drawable.bg_card);
        g.setClipToOutline(true);
        return g;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
        p.leftMargin = dp(16);
        p.rightMargin = dp(16);
        v.setLayoutParams(p);
        return v;
    }

    /** 对应 item_app_list.xml：图标 40dp + 左右 15dp + 标题 18sp + chevron */
    private View scopeRow(int iconRes, String title, String summary, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setBackgroundResource(R.drawable.bg_icon_round);
        ic.setPadding(dp(9), dp(9), dp(9), dp(9));
        row.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(15), 0, dp(15), 0);
        texts.addView(tv(title, 18, R.color.text, Typeface.NORMAL));
        if (summary != null && summary.length() > 0) {
            TextView s = tv(summary, 12, R.color.dim, Typeface.NORMAL);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(chevron());
        row.setOnClickListener(click);
        return row;
    }

    private View switchRow(String title, String summary, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv(title, 16, R.color.text, Typeface.NORMAL));
        if (summary != null && summary.length() > 0) {
            TextView s = tv(summary, 12, R.color.dim, Typeface.NORMAL);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final MiuixSwitch sw = new MiuixSwitch(this);
        sw.setChecked(Prefs.get(this, key, def));
        sw.setOnCheckedChangeListener(new MiuixSwitch.OnCheckedChangeListener() {
            public void onCheckedChanged(MiuixSwitch v, boolean on) {
                Prefs.set(MainActivity.this, key, on);
                Toast.makeText(MainActivity.this, (on ? "已开启：" : "已关闭：") + key, Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(sw);
        return row;
    }

    private View navRow(String title, String summary, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv(title, 16, R.color.text, Typeface.NORMAL));
        if (summary != null && summary.length() > 0) {
            TextView s = tv(summary, 12, R.color.dim, Typeface.NORMAL);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && value.length() > 0) {
            row.addView(tv(value, 14, R.color.dim, Typeface.NORMAL));
            View gap = new View(this);
            row.addView(gap, new LinearLayout.LayoutParams(dp(6), 1));
        }
        row.addView(chevron());
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { Toast.makeText(MainActivity.this, title + "：待接入", Toast.LENGTH_SHORT).show(); }
        });
        return row;
    }

    private View kvRow(String k, String v) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.addView(tv(k, 12, R.color.dim, Typeface.NORMAL));
        TextView val = tv(v == null ? "-" : v, 14, R.color.text, Typeface.NORMAL);
        val.setPadding(0, dp(3), 0, 0);
        box.addView(val);
        return box;
    }

    private View backRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_back);
        ic.setColorFilter(getColor(R.color.text));
        row.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView tv = tv(label, 14, R.color.dim, Typeface.NORMAL);
        tv.setPadding(dp(6), 0, 0, 0);
        row.addView(tv);
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onBackPressed(); } });
        return row;
    }

    /** 状态圆点：已实现=绿，已定位=蓝，待实验=黄，待逆向=灰 */
    private View statusDot(String status) {
        View v = new View(this);
        int color;
        if (status != null && status.contains("已实现")) color = getColor(R.color.ok);
        else if (status != null && status.contains("已定位")) color = getColor(R.color.accent);
        else if (status != null && status.contains("待实验")) color = getColor(R.color.warn);
        else color = getColor(R.color.dim);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(7), dp(7));
        p.rightMargin = dp(9);
        v.setLayoutParams(p);
        return v;
    }

    private View chevron() {
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_chevron);
        return ic;
    }

    private String shortTarget(Feature f) {
        String t = f.target;
        if (t.length() > 46) t = t.substring(0, 46) + "…";
        return t;
    }

    private List<Feature> filter(List<Feature> in) {
        if (query.isEmpty()) return in;
        String q = query.toLowerCase();
        List<Feature> out = new ArrayList<>();
        for (Feature f : in) {
            if (f.name.toLowerCase().contains(q) || f.id.toLowerCase().contains(q)
                    || f.target.toLowerCase().contains(q) || f.note.toLowerCase().contains(q)) out.add(f);
        }
        return out;
    }

    private void runAction(final Feature f, final boolean on) {
        new Thread(new Runnable() { public void run() {
            final String msg = Actions.apply(MainActivity.this, f, on);
            // 影响游戏魔盒的 hook 开关：立即重启游戏魔盒，让新状态生效
            if (Feature.KIND_HOOK.equals(f.kind) && affectsGamecube(f)) {
                ShellExec.su("am force-stop com.vivo.gamecube");
                ui.post(new Runnable() { public void run() {
                    Toast.makeText(MainActivity.this, "已重启游戏魔盒，重开游戏后生效",
                            Toast.LENGTH_SHORT).show();
                }});
            }
            ui.post(new Runnable() { public void run() {
                Toast.makeText(MainActivity.this, f.name + "：" + msg, Toast.LENGTH_SHORT).show();
                setContent(stack.isEmpty() ? homePage() : stack.peek());
            }});
        }}).start();
    }

    /** 该功能是否会改动游戏魔盒内的行为 */
    private static boolean affectsGamecube(Feature f) {
        String t = (f.target == null ? "" : f.target) + (f.note == null ? "" : f.note);
        return t.contains("gameassistant") || t.contains("vivo.gamecube")
                || t.contains("modeshift") || t.contains("superresolution")
                || t.contains("frameinterpolation") || t.contains("gamefilter")
                || f.id.startsWith("q2.") || f.id.startsWith("perf.monsterplus")
                || f.id.startsWith("game.") || f.id.equals("thermal.bypass.global");
    }

    /** LSPosed 未生效 / 无 root 时给出明确提示（同一环境只弹一次） */
    private void warnIfNeeded() {
        String issues = Env.issues();
        if (issues.length() == 0) return;
        String key = "env.prompt." + Env.root + "." + Env.lsposedInstalled + "." + Env.hookAlive;
        if (Prefs.get(this, key, false)) return;
        Prefs.set(this, key, true);
        new MDialog(this).title("环境不完整").message(issues)
            .neutral("去诊断", new MDialog.OnClick() {
                public void run(android.app.Dialog d) { showDiag(); }
            })
            .positive("知道了", null).show();
    }

    private void doRestore() {
        new MDialog(this).title("恢复默认")
            .message("将关闭所有已开启的功能并回滚频率 / 调度 / 设置项。继续？")
            .negative("取消", null)
            .positive("继续", new MDialog.OnClick() {
                public void run(android.app.Dialog d) {
                    new Thread(new Runnable() { public void run() {
                        final String r = Actions.restoreAll(MainActivity.this);
                        ui.post(new Runnable() { public void run() {
                            info("已恢复", r);
                            showTab(tab);
                        }});
                    }}).start();
                }
            }).show();
    }

    private void showDiag() {
        new Thread(new Runnable() { public void run() {
            final String s = Diag.snapshot(MainActivity.this);
            ui.post(new Runnable() { public void run() {
                TextView tv = new TextView(MainActivity.this);
                tv.setText(s);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(getColor(R.color.text));
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                ScrollView sc = new ScrollView(MainActivity.this);
                sc.addView(tv);
                new MDialog(MainActivity.this).title("设备诊断").custom(sc, 0)
                    .positive("关闭", null).show();
            }});
        }}).start();
    }

    private int icon(String name) { return getResources().getIdentifier(name, "drawable", getPackageName()); }

    private TextView tv(String s, int sp, int colorRes, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(colorRes));
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    /** 默认窗口适配：内容区已排除状态栏/导航栏，无需再自行加 padding */
    private void applyInsets(final View root) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        root.setFitsSystemWindows(true);
    }

    /** 底部导航自己吃 inset：背景铺到屏幕底部，图标与文字上移，避免被手势条切掉 */
    /** 真实导航栏高度：realMetrics - metrics（比 insets 更可靠，实测该窗口 insets 恒 0） */
    private int navBarHeight() {
        try {
            android.util.DisplayMetrics real = new android.util.DisplayMetrics();
            android.util.DisplayMetrics cur = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(real);
            getWindowManager().getDefaultDisplay().getMetrics(cur);
            int diff = real.heightPixels - cur.heightPixels;
            if (diff > 0 && diff < dp(120)) return diff;
            int res = dim("navigation_bar_height");
            return res > 0 ? res : dp(40);
        } catch (Throwable t) { return dp(40); }
    }

    /** 导航栏像素高度：realMetrics - metrics（本机实测 192px = 48dp） */
    private int measureNavBarPx() {
        try {
            android.util.DisplayMetrics cur = new android.util.DisplayMetrics();
            android.util.DisplayMetrics real = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(cur);
            getWindowManager().getDefaultDisplay().getRealMetrics(real);
            int diff = real.heightPixels - cur.heightPixels;
            return (diff > 0 && diff < dp(200)) ? diff : dp(48);
        } catch (Throwable t) { return dp(48); }
    }

    private int dim(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void showModuleDialog(boolean ready, String summary) {
        try {
            String msg = ready
                    ? "去温控模块已安装并运行，频率滑块与信仰模式可用。\n\n" + summary
                    : summary + "\n\n点击下方按钮会自动写入模块文件并启动执行器。";
            MDialog d = new MDialog(this)
                    .title(ready ? "全部功能已启用" : "需要启用去温控模块")
                    .message(msg);
            if (!ready) {
                d.positive("一键安装并启用", new MDialog.OnClick() {
                    @Override public void run(android.app.Dialog dd) {
                        String r = ModuleStatus.install(MainActivity.this);
                        android.widget.Toast.makeText(MainActivity.this, r,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
                d.negative("稍后", null);
            } else {
                d.positive("知道了", null);
            }
            d.show();
        } catch (Throwable t) {
            android.util.Log.i("FuckOrigin", "模块提示失败 " + t);
        }
    }

    /** 模块控制台的「信仰模式」按钮通过 fuckorigin://faith 调到这里 */
    private void handleFaithIntent(android.content.Intent it) {
        try {
            if (it == null || it.getData() == null) return;
            if (!"fuckorigin".equals(it.getData().getScheme())) return;
            if (!"faith".equals(it.getData().getHost())) return;
            String cur = ShellExec.su("settings get secure sys_perf_faith").out;
            boolean on = cur != null && cur.trim().equals("1");
            boolean next = !on;
            ShellExec.su("settings put secure sys_perf_faith " + (next ? 1 : 0));
            android.widget.Toast.makeText(this,
                    next ? "燃烧你的芯片吧，赛博坦怪兽" : "信仰模式已关闭",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            android.util.Log.i("FuckOrigin", "faith intent 失败 " + t);
        }
    }

}
