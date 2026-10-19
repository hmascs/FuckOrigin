package com.fuckorigin.app;

import android.content.Context;

/** 只读诊断：一次 su 调用拿到全部读数。 */
public final class Diag {

    private static final String SCRIPT = String.join("\n",
        "echo '## device'",
        "getprop ro.product.model; getprop ro.vivo.product.model; getprop ro.vivo.os.version; getprop ro.build.display.id",
        "echo '## root'; id",
        "echo '## tshell-game'; cat /sys/class/tshell/tshell_game",
        "echo '## tshell-shell'; cat /sys/class/tshell/tshell_shell",
        "echo '## thermal-status'; dumpsys thermalservice | grep -m1 'Thermal Status'",
        "echo '## zones'",
        "i=0; while [ $i -lt 95 ]; do t=/sys/class/thermal/thermal_zone$i; ty=$(cat $t/type 2>/dev/null); " +
            "case \"$ty\" in gpuss-0|gpuss-3|cpu-0-0-0|cpuss-0-0|tz_shell|tz_game_shell|quiet_therm|ap_therm|battery) " +
            "echo \"$ty = $(cat $t/temp 2>/dev/null)\";; esac; i=$((i+1)); done",
        "echo '## cpufreq'",
        "for p in policy0 policy6; do b=/sys/devices/system/cpu/cpufreq/$p; " +
            "echo \"$p cur=$(cat $b/scaling_cur_freq) max=$(cat $b/scaling_max_freq) hw=$(cat $b/cpuinfo_max_freq) gov=$(cat $b/scaling_governor)\"; done",
        "echo '## gpu'",
        "echo \"clock=$(cat /sys/class/kgsl/kgsl-3d0/clock_mhz) min_pwr=$(cat /sys/class/kgsl/kgsl-3d0/min_pwrlevel)\"",
        "echo '## core_ctl'",
        "for d in /sys/devices/system/cpu/cpu*/core_ctl; do echo \"$d max=$(cat $d/max_cpus) min=$(cat $d/min_cpus) active=$(cat $d/active_cpus)\"; done",
        "echo '## refresh'",
        "dumpsys SurfaceFlinger | grep -m1 activeMode",
        "echo '## end'"
    );

    public static String snapshot(Context c) {
        ShellExec.R r = ShellExec.su(SCRIPT);
        if (!r.ok() && r.out.trim().length() == 0) {
            return "无法执行（root 未授权？）\n" + r.out;
        }
        return r.out.trim();
    }

    /** 极简：只取两个场景温度，用于首页实时显示 */
    public static String quickTemp() {
        String g = ShellExec.catOrRoot("/sys/class/tshell/tshell_game");
        String s = ShellExec.catOrRoot("/sys/class/tshell/tshell_shell");
        return fmt(g) + " / " + fmt(s);
    }

    private static String fmt(String raw) {
        if (raw == null) return "n/a";
        try {
            String first = raw.split(",")[0].trim();
            double v = Double.parseDouble(first) / 1000.0;
            return String.format("%.1f℃", v);
        } catch (Throwable t) {
            return raw.length() > 12 ? raw.substring(0, 12) : raw;
        }
    }
}
