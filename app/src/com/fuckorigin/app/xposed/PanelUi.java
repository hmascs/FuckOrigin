package com.fuckorigin.app.xposed;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏侧边栏「性能面板」底部注入。
 * 默认只占一行（约 40dp），点击展开；展开区限高，避免遮挡原面板。
 */
final class PanelUi {

    private static final String TAG_KEY = "fuckorigin_panel_ui";
    private static final String K_CPU_MIN = "sys_perf_c0_min";
    private static final String K_CPU_MAX = "sys_perf_c0_max";
    private static final String K_GPU_MIN = "sys_perf_g_min";
    private static final String K_GPU_MAX = "sys_perf_g_max";
    private static final String K_FAITH = "sys_perf_faith";
    private static final String K_FAITH_PKG = "sys_perf_faith_pkg";

    private static final int[] PENDING = new int[] { -1, -1, -1, -1 };
    private static final int GOLD_A = 0xFFFFD24A;
    private static final int GOLD_B = 0xFFFFA51F;

    static void attach(ViewGroup root) {
        if (root.findViewWithTag(TAG_KEY) != null) {
            android.util.Log.i("FuckOrigin", "PANEL|已注入过，跳过");
            return;
        }
        final Context ctx = root.getContext();
        android.util.Log.i("FuckOrigin", "PANEL|attach 开始 ctx=" + ctx
                + " children=" + root.getChildCount());
        if (ctx == null) return;

        // 找到「调整屏幕刷新率 / 触控采样率」所在的列表容器，把控件插成同级
        ViewGroup container = findContentById(root, ctx);
        if (container == null) container = findVerticalList(root);
        if (container == null) {
            android.util.Log.i("FuckOrigin", "PANEL|未找到可插入的列表容器，放弃注入");
            return;
        }

        final List<Long> cpu = readFreqs("/sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies",
                "/sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq");
        final List<Long> gpu = readFreqs("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
                "/sys/class/kgsl/kgsl-3d0/gpu_maxfreq");

        LinearLayout holder = new LinearLayout(ctx);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setTag(TAG_KEY);

        if (!cpu.isEmpty()) {
            holder.addView(sliderRow(ctx, "CPU 最低频率", cpu, K_CPU_MIN, false, 0));
            holder.addView(sliderRow(ctx, "CPU 最高频率", cpu, K_CPU_MAX, true, 1));
        }
        if (!gpu.isEmpty()) {
            holder.addView(sliderRow(ctx, "GPU 最低频率", gpu, K_GPU_MIN, false, 2));
            holder.addView(sliderRow(ctx, "GPU 最高频率", gpu, K_GPU_MAX, true, 3));
        }

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(ctx);
        save.setText("保存配置");
        save.setAllCaps(false);
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        actions.addView(save, lp1);

        final Button faith = new Button(ctx);
        faith.setText("信仰模式");
        faith.setAllCaps(false);
        faith.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        styleGold(faith, isOn(ctx, K_FAITH), 10);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp2.leftMargin = (int) dp(ctx, 8);
        actions.addView(faith, lp2);

        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = (int) dp(ctx, 8);
        holder.addView(actions, alp);

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                applyPending(v.getContext());
                Toast.makeText(v.getContext(), "配置已保存", Toast.LENGTH_SHORT).show();
            }
        });

        View.OnClickListener toggle = new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !isOn(v.getContext(), K_FAITH);
                if (next) {
                    String gp = currentGame();
                    if (gp == null || gp.length() == 0) {
                        // 没有识别到游戏就不开，否则退出时无法自动关闭
                        Toast.makeText(v.getContext(), "未识别到游戏，请进入游戏后再开启",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyPending(v.getContext());
                    putS(v.getContext(), K_FAITH_PKG, gp);
                }
                put(v.getContext(), K_FAITH, next ? 1 : 0);
                if (!next) put(v.getContext(), "sys_perf_thaw", 1);   // 关闭时立即交还热控
                styleGold(faith, next, 10);
                faith.setText(next ? "信仰模式 · 已开启" : "信仰模式");
                Toast.makeText(v.getContext(),
                        next ? "燃烧你的芯片吧，赛博坦怪兽" : "信仰模式已关闭",
                        Toast.LENGTH_SHORT).show();
            }
        };
        faith.setOnClickListener(toggle);

        // 把滚动容器的唯一子视图包进竖向 LinearLayout，控件作为第二项追加其后：
        // 滚动范围自然包含它，也不会有"底部锚定行被 padding 推下来"的重叠问题。
        ViewGroup scroll = findScroll(root, ctx);
        if (scroll != null && scroll.getChildCount() >= 1) {
            View content = scroll.getChildAt(0);
            scroll.removeAllViews();
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.topMargin = (int) dp(ctx, 8);
            wrap.addView(holder, hp);
            scroll.addView(wrap, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.util.Log.i("FuckOrigin", "PANEL|已包一层追加到内容之后 scroll="
                    + scroll.getClass().getSimpleName()
                    + " 原内容=" + content.getClass().getSimpleName());
            return;
        }
        android.util.Log.i("FuckOrigin", "PANEL|未找到滚动容器，放弃注入");
    }

    /** 取滚动容器 performance_scroll_layout（CustomVFastScrollView），失败则按类名找 */
    private static ViewGroup findScroll(ViewGroup root, Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier(
                    "performance_scroll_layout", "id", "com.vivo.gamecube");
            if (id != 0) {
                View v = root.findViewById(id);
                if (v instanceof ViewGroup) return (ViewGroup) v;
            }
        } catch (Throwable ignored) { }
        java.util.ArrayList<View> stack = new java.util.ArrayList<View>();
        stack.add(root);
        while (!stack.isEmpty()) {
            View v = stack.remove(stack.size() - 1);
            if (v.getClass().getName().contains("ScrollView")) return (ViewGroup) v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        return null;
    }

    /** 按 gamecube 自己的布局 id 取内容容器（performance_content_cl） */
    private static ViewGroup findContentById(ViewGroup root, Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("performance_content_cl", "id", "com.vivo.gamecube");
            if (id == 0) return null;
            View v = root.findViewById(id);
            if (v instanceof ViewGroup) return (ViewGroup) v;
        } catch (Throwable ignored) { }
        return null;
    }

    /** 找到承载各行的竖向 LinearLayout（即「刷新率 / 亮度 / 触控采样率」所在的那个列表） */
    private static ViewGroup findVerticalList(ViewGroup root) {
        View target = null;
        java.util.ArrayList<View> stack = new java.util.ArrayList<View>();
        stack.add(root);
        while (!stack.isEmpty() && target == null) {
            View v = stack.remove(stack.size() - 1);
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && t.toString().contains("刷新率")) { target = v; break; }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        if (target == null) return null;
        View cur = target;
        for (int depth = 0; depth < 5 && cur != null; depth++) {
            View parent = (View) cur.getParent();
            if (parent instanceof LinearLayout) {
                LinearLayout ll = (LinearLayout) parent;
                if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() >= 2) {
                    return ll;
                }
            }
            cur = parent;
        }
        return null;
    }

    /** 取兄弟行的 LayoutParams，保证左右边距与宽度一致 */
    private static ViewGroup.LayoutParams siblingParams(ViewGroup list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams src = (LinearLayout.LayoutParams) lp;
                LinearLayout.LayoutParams out = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                out.leftMargin = src.leftMargin;
                out.rightMargin = src.rightMargin;
                out.topMargin = src.topMargin;
                out.bottomMargin = src.bottomMargin;
                return out;
            }
        }
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** ConstraintLayout 兜底：锚到"最靠下的那个子项"下方，左右对齐父容器 */
    private static ViewGroup.LayoutParams constraintFallback(ViewGroup list) {
        int anchorId = View.NO_ID;
        int bestBottom = -1;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c.getBottom() >= bestBottom) {
                bestBottom = c.getBottom();
                anchorId = c.getId();
                if (anchorId == View.NO_ID) {
                    anchorId = View.generateViewId();
                    c.setId(anchorId);
                }
            }
        }
        try {
            Class<?> cls = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams");
            Object lp = cls.getConstructor(int.class, int.class).newInstance(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (anchorId != View.NO_ID) {
                cls.getField("topToBottom").setInt(lp, anchorId);
            }
            cls.getField("startToStart").setInt(lp, 0);
            cls.getField("endToEnd").setInt(lp, 0);
            android.util.Log.i("FuckOrigin", "PANEL|约束锚点 id=" + anchorId
                    + " bottom=" + bestBottom + " children=" + list.getChildCount());
            return (ViewGroup.LayoutParams) lp;
        } catch (Throwable t) {
            android.util.Log.i("FuckOrigin", "PANEL|约束参数失败 " + t);
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 与原有行同构：上面标题，下面 SeekBar */
    private static View sliderRow(Context ctx, String name, List<Long> freqs,
                                  final String key, boolean isMax, final int slot) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = (int) dp(ctx, 6);
        row.setLayoutParams(rp);

        final TextView label = new TextView(ctx);
        label.setTextColor(0xFFBFC4D6);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(label);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(freqs.size() - 1);
        int cur = currentIndex(ctx, key, freqs, isMax);
        bar.setProgress(cur);
        label.setText(name + "：" + mhz(freqs.get(cur)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                label.setText(name + "：" + mhz(freqs.get(p)));
                if (fromUser) PENDING[slot] = freqs.get(p).intValue();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                PENDING[slot] = freqs.get(s.getProgress()).intValue();
            }
        });
        row.addView(bar);
        return row;
    }

    /** 把待保存的四个滑块值写入 Settings.Secure */
    static void applyPending(Context c) {
        int[] v = new int[] { -1, -1, -1, -1 };
        for (int i = 0; i < 4; i++) v[i] = PENDING[i];
        if (v[0] > 0) put(c, K_CPU_MIN, v[0]);
        if (v[1] > 0) put(c, K_CPU_MAX, v[1]);
        if (v[2] > 0) put(c, K_GPU_MIN, v[2]);
        if (v[3] > 0) put(c, K_GPU_MAX, v[3]);
    }

    /** 当前前台游戏包名，供 root 侧在游戏退出后自动关闭温控解除 */
    static String currentGame() {
        // 1) gamecube 自己的判定
        try {
            Class<?> pc = Class.forName("com.vivo.gameassistant.p");
            Object inst = pc.getMethod("V").invoke(null);
            Object pkg = pc.getMethod("O0").invoke(inst);
            if (pkg instanceof String && ((String) pkg).length() > 0) return (String) pkg;
        } catch (Throwable ignored) { }
        // 2) 根执行器写入的当前游戏
        try {
            Context c = sCtxForGame();
            if (c != null) {
                String cur = Settings.System.getString(c.getContentResolver(), "current_game_package");
                if (cur != null && cur.length() > 0 && !"null".equals(cur)) return cur;
            }
        } catch (Throwable ignored) { }
        // 3) 最近任务里的前台包名
        try {
            Context c = sCtxForGame();
            if (c != null) {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        c.getSystemService(Context.ACTIVITY_SERVICE);
                java.util.List<android.app.ActivityManager.RunningTaskInfo> t =
                        am.getRunningTasks(1);
                if (t != null && !t.isEmpty() && t.get(0).topActivity != null) {
                    return t.get(0).topActivity.getPackageName();
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** 供 currentGame() 取 Context（HookEntry 在 Application#onCreate 里回填） */
    static volatile Context UI_CTX;
    private static Context sCtxForGame() { return UI_CTX; }

    private static void styleGold(Button b, boolean on, float radiusDp) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                on ? new int[] { GOLD_A, GOLD_B } : new int[] { 0xFFE8C470, 0xFFC99A34 });
        g.setCornerRadius(dp(b.getContext(), radiusDp));
        b.setBackground(g);
        b.setTextColor(on ? 0xFF1A1206 : 0xFF3A2A08);
    }

    private static void addSlider(Context ctx, LinearLayout parent, String name,
                                  List<Long> freqs, final String key, boolean isMax, final int slot) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = (int) dp(ctx, 6);
        parent.addView(row, rp);

        final TextView label = new TextView(ctx);
        label.setTextColor(0xFFBFC4D6);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        row.addView(label);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(freqs.size() - 1);
        int cur = currentIndex(ctx, key, freqs, isMax);
        bar.setProgress(cur);
        label.setText(name + "：" + mhz(freqs.get(cur)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                label.setText(name + "：" + mhz(freqs.get(p)));
                if (fromUser) PENDING[slot] = freqs.get(p).intValue();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                PENDING[slot] = freqs.get(s.getProgress()).intValue();
            }
        });
        row.addView(bar);
    }

    private static int currentIndex(Context ctx, String key, List<Long> freqs, boolean isMax) {
        int v = get(ctx, key, -1);
        if (v <= 0) return isMax ? freqs.size() - 1 : 0;
        int best = isMax ? freqs.size() - 1 : 0;
        for (int i = 0; i < freqs.size(); i++) {
            if (freqs.get(i) <= v) best = i;
        }
        return best;
    }

    private static String mhz(long v) {
        if (v > 10000000L) return (v / 1000000L) + " MHz";
        return (v / 1000L) + " MHz";
    }

    private static List<Long> readFreqs(String listPath, String fallback) {
        List<Long> out = new ArrayList<Long>();
        try {
            String s = readAll(listPath);
            if (s != null && s.trim().length() > 0) {
                for (String t : s.trim().split("\\s+")) {
                    try { out.add(Long.parseLong(t.trim())); } catch (Throwable ignored) { }
                }
            }
            if (out.isEmpty()) {
                String f = readAll(fallback);
                if (f != null) out.add(Long.parseLong(f.trim()));
            }
        } catch (Throwable ignored) { }
        java.util.Collections.sort(out);
        return out;
    }

    private static String readAll(String path) {
        FileInputStream in = null;
        try {
            File f = new File(path);
            if (!f.canRead()) return null;
            in = new FileInputStream(f);
            byte[] b = new byte[512];
            int n = in.read(b);
            return n <= 0 ? null : new String(b, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static int get(Context c, String k, int def) {
        try { return Settings.Secure.getInt(c.getContentResolver(), k, def); }
        catch (Throwable t) { return def; }
    }

    private static boolean isOn(Context c, String k) { return get(c, k, 0) == 1; }

    private static void putS(Context c, String k, String v) {
        try { Settings.Secure.putString(c.getContentResolver(), k, v); }
        catch (Throwable t) { android.util.Log.i("FuckOrigin", "PANEL|写设置失败 " + t); }
    }

    private static void put(Context c, String k, int v) {
        try { Settings.Secure.putInt(c.getContentResolver(), k, v); }
        catch (Throwable t) { android.util.Log.i("FuckOrigin", "PANEL|写设置失败 " + t); }
    }

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    private PanelUi() { }
}
