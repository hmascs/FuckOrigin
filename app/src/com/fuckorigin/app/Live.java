package com.fuckorigin.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 实时监控面板：1 秒刷新一次，纯只读。 */
public final class Live {

    public static View build(final Activity a) {
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 24));
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(a.getColor(R.color.bg));
        sv.addView(col, new LinearLayout.LayoutParams(-1, -2));

        final TextView body = new TextView(a);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(11);
        body.setTextColor(a.getColor(R.color.text));
        body.setBackgroundResource(R.drawable.bg_card);
        body.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        col.addView(body, new LinearLayout.LayoutParams(-1, -2));

        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] task = new Runnable[1];
        task[0] = new Runnable() { public void run() {
            new Thread(new Runnable() { public void run() {
                final String s = snapshot();
                h.post(new Runnable() { public void run() { body.setText(s); } });
            }}).start();
            h.postDelayed(task[0], 1000);
        }};
        h.post(task[0]);
        sv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { h.removeCallbacks(task[0]); }
        });
        return sv;
    }

    private static String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("== 场景温度（tshell）\n");
        sb.append("game : ").append(nz(ShellExec.catOrRoot("/sys/class/tshell/tshell_game"))).append('\n');
        sb.append("shell: ").append(nz(ShellExec.catOrRoot("/sys/class/tshell/tshell_shell"))).append('\n');
        sb.append("shelltemp_en: ").append(nz(ShellExec.catOrRoot("/sys/class/tshell/shelltemp_en"))).append("\n\n");
        sb.append("== PEM 节点\n");
        sb.append("thml_type    : ").append(nz(Pem.readNode(Pem.NODE))).append('\n');
        sb.append("thml_fan_type: ").append(nz(Pem.readNode(Pem.FAN))).append('\n');
        sb.append("heatlimit    : ").append(nz(Pem.readNode(Pem.HEAT))).append("\n\n");
        sb.append("== cpufreq\n");
        for (String p : new String[] { "policy0", "policy6" }) {
            String b = "/sys/devices/system/cpu/cpufreq/" + p;
            sb.append(p).append(" cur=").append(nz(ShellExec.cat(b + "/scaling_cur_freq")))
              .append(" max=").append(nz(ShellExec.cat(b + "/scaling_max_freq")))
              .append(" hw=").append(nz(ShellExec.cat(b + "/cpuinfo_max_freq")))
              .append(" gov=").append(nz(ShellExec.cat(b + "/scaling_governor"))).append('\n');
        }
        sb.append("\n== GPU\n");
        sb.append("clock=").append(nz(ShellExec.catOrRoot("/sys/class/kgsl/kgsl-3d0/clock_mhz")))
          .append(" MHz  min_pwr=").append(nz(ShellExec.catOrRoot("/sys/class/kgsl/kgsl-3d0/min_pwrlevel"))).append('\n');
        sb.append("\n== core_ctl\n");
        for (String c : new String[] { "cpu0", "cpu6" }) {
            String d = "/sys/devices/system/cpu/" + c + "/core_ctl";
            sb.append(c).append(" max=").append(nz(ShellExec.cat(d + "/max_cpus")))
              .append(" min=").append(nz(ShellExec.cat(d + "/min_cpus")))
              .append(" active=").append(nz(ShellExec.cat(d + "/active_cpus"))).append('\n');
        }
        sb.append("\n== 温区(按 type)\n");
        sb.append(MainActivityRoom.tzoneSummary());
        sb.append("\n== Hook 状态\n").append(HookStatus.text());
        return sb.toString();
    }

    private static String nz(String s) { return s == null || s.length() == 0 ? "-" : s.trim(); }
    private static int dp(Activity a, int v) { return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f); }
}
