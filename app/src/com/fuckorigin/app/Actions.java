package com.fuckorigin.app;

import android.content.Context;

/** 所有"真正会改变设备状态"的动作都在这里，且每条都带基线保存与回滚。 */
public final class Actions {

    /** 锁帧：抬高设备帧率上限并钉住刷新率，关闭时回滚到 120/null */
    private static String fpsLock(Context c, Feature f, boolean on) {
        if (on) {
            String cap = Prefs.getS(c, "fps.cap", "144");
            ShellExec.R r = ShellExec.su(
                    "setprop persist.vivo.phone.fps_max " + cap
                    + "; settings put system peak_refresh_rate " + cap
                    + "; settings put system min_refresh_rate " + cap);
            if (!r.ok()) return "写入失败：" + r.out.trim();
            f.setEnabled(c, true);
            return "已锁 " + cap + " 帧（最高帧率 + 刷新率已钉住，重启后仍生效）";
        }
        ShellExec.R r = ShellExec.su(
                "setprop persist.vivo.phone.fps_max 120"
                + "; settings put system peak_refresh_rate 144"
                + "; settings delete system min_refresh_rate");
        f.setEnabled(c, false);
        return r.ok() ? "已恢复默认帧率策略（上限 120）" : "回滚失败：" + r.out.trim();
    }

    private static String base(Context c, String id, String def) {
        return Prefs.getS(c, "base." + id, def);
    }

    public static String apply(Context c, Feature f, boolean on) {
        String r = apply0(c, f, on);
        try { Bridge.sync(c); } catch (Throwable ignored) { }
        return r;
    }

    private static String apply0(Context c, Feature f, boolean on) {
        try {
            if (f.isGroup()) {
                StringBuilder sb = new StringBuilder();
                for (String cid : f.children) {
                    Feature child = Catalog.feature(cid);
                    if (child == null) continue;
                    apply(c, child, on);
                    sb.append(child.name).append(on ? " 开" : " 关").append(" / ");
                }
                f.setEnabled(c, on);
                return sb.length() == 0 ? (on ? "已开启" : "已关闭") : sb.toString().trim();
            }
            if ((f.id.startsWith("perf.cpufreq") || f.id.startsWith("perf.gpu")
                    || f.id.equals("perf.governor") || f.id.equals("perf.core_ctl.big"))
                    && on && !ModuleStatus.allReady()) {
                return "模块未安装或未运行，此功能不生效。请在启动提示或设置里点「一键安装并启用」。";
            }
            switch (f.id) {
                case "perf.cpufreq.max":   return cpuMax(c, f, on);
                case "perf.governor":      return governor(c, f, on);
                case "perf.gpu.floor":     return gpuFloor(c, f, on);
                case "perf.core_ctl.big":  return coreCtl(c, f, on);
                case "ui.statusbar.seconds": return settingsToggle(c, f, on,
                        "settings put system clock_seconds 1", "settings delete system clock_seconds");
                case "dev.adb.wifi":      return settingsToggle(c, f, on,
                        "settings put global adb_wifi_enabled 1", "settings put global adb_wifi_enabled 0");
                case "update.freeze":     return updateFreeze(c, f, on);
                case "perf.fps.lock":     return fpsLock(c, f, on);
                case "hook.master": {
                    ShellExec.su("settings put secure sys_perf_hooks " + (on ? 1 : 0));
                    if (!on) ShellExec.su("settings put secure sys_perf_thaw 1");
                    f.setEnabled(c, on);
                    return on ? "Hook 已启用（重开游戏魔盒生效）" : "Hook 已熔断，全部按原厂逻辑";
                }
                case "game.panel.ui": {
                    ShellExec.su("settings put secure sys_perf_ui " + (on ? 1 : 0));
                    f.setEnabled(c, on);
                    return on ? "侧边栏频率面板已开启（重开侧边栏生效）" : "已关闭侧边栏频率面板";
                }
                case "perf.pem.thermal_mode":
                    if (on) {
                        String r = Pem.enable(c);
                        f.setEnabled(c, Pem.isOn(c));
                        return r;
                    } else {
                        String r = Pem.disable(c);
                        f.setEnabled(c, false);
                        return r;
                    }
                default:
                    f.setEnabled(c, on);
                    if (Feature.KIND_HOOK.equals(f.kind)) {
                        return on ? "已记录（需 LSPosed 生效）" : "已关闭";
                    }
                    if (!f.toggleable()) return "该条目暂不可开关（" + f.status + "）";
                    return on ? "已开启" : "已关闭";
            }
        } catch (Throwable t) {
            return "失败：" + t.getMessage();
        }
    }

    // ── CPU 顶频 ────────────────────────────────────────────────────
    /**
     * 顶频必须取 scaling_available_frequencies 的最大值。
     * cpuinfo_max_freq 是硬件能力值（本机 3302400），并不在可写频点表里
     * （最高档 3052800），写进去会被内核拒绝，scaling_max_freq 反而停在
     * 某个中间档（实测 2630400）—— 表现就是"顶频开了，游戏还是很卡"。
     */
    private static String maxAvailFreq(String baseDir) {
        String s = ShellExec.cat(baseDir + "/scaling_available_frequencies");
        if (s == null || s.length() == 0) {
            ShellExec.R r = ShellExec.su("cat " + baseDir + "/scaling_available_frequencies");
            s = r.ok() ? r.out.trim() : null;
        }
        if (s != null && s.length() > 0) {
            long best = -1;
            String bs = null;
            for (String x : s.trim().split("\\s+")) {
                try {
                    long v = Long.parseLong(x.trim());
                    if (v > best) { best = v; bs = x.trim(); }
                } catch (Throwable ignored) { }
            }
            if (bs != null) return bs;
        }
        String hw = ShellExec.cat(baseDir + "/cpuinfo_max_freq");
        return hw == null ? null : hw.trim();
    }

    private static String cpuMax(Context c, Feature f, boolean on) {
        String id = f.id;
        StringBuilder sb = new StringBuilder();
        for (String p : Platform.perfPolicies()) {
            String baseDir = "/sys/devices/system/cpu/cpufreq/" + p;
            String cur = ShellExec.cat(baseDir + "/scaling_max_freq");
            String hw = maxAvailFreq(baseDir);
            if (hw == null || hw.length() == 0) {
                sb.append(p).append(" 无频点表，跳过 ");
                continue;
            }
            if (on) {
                if (cur != null) Prefs.setS(c, "base." + id + "." + p, cur);
                ShellExec.R r = ShellExec.su("echo " + hw + " > " + baseDir + "/scaling_max_freq");
                // 交给模块执行器持续维持（温控会改写 scaling_max_freq）
                ShellExec.su("settings put secure sys_perf_c0_max " + hw
                        + "; settings put secure sys_perf_c0_min " + hw);
                sb.append(p).append(" → ").append(hw).append(r.ok() ? " ✓ " : " ✗ ");
            } else {
                String b = base(c, id + "." + p, hw);
                ShellExec.R r = ShellExec.su("echo " + b + " > " + baseDir + "/scaling_max_freq");
                // 开启时把 c0_min 也拉到了顶频，关闭必须还原下限，
                // 否则 min 会一直停在旧值（min==max 时 CPU 既不能升也不能降）。
                String min = ShellExec.cat(baseDir + "/cpuinfo_min_freq");
                if (min != null && min.trim().length() > 0) {
                    ShellExec.su("echo " + min.trim() + " > " + baseDir + "/scaling_min_freq");
                }
                ShellExec.su("settings delete secure sys_perf_c0_max; settings delete secure sys_perf_c0_min");
                sb.append(p).append(" ← ").append(b).append(r.ok() ? " ✓ " : " ✗ ");
            }
        }
        f.setEnabled(c, on);
        return sb.toString().trim();
    }

    // ── governor ────────────────────────────────────────────────────
    private static String governor(Context c, Feature f, boolean on) {
        StringBuilder sb = new StringBuilder();
        for (String p : Platform.perfPolicies()) {
            String path = "/sys/devices/system/cpu/cpufreq/" + p + "/scaling_governor";
            String cur = ShellExec.cat(path);
            if (on) {
                if (cur != null) Prefs.setS(c, "base." + f.id + "." + p, cur);
                ShellExec.R r = ShellExec.su("echo performance > " + path);
                sb.append(p).append(" → performance").append(r.ok() ? " ✓ " : " ✗ ");
            } else {
                String b = base(c, f.id + "." + p, "walt");
                ShellExec.R r = ShellExec.su("echo " + b + " > " + path);
                sb.append(p).append(" ← ").append(b).append(r.ok() ? " ✓ " : " ✗ ");
            }
        }
        f.setEnabled(c, on);
        return sb.toString().trim();
    }

    // ── GPU ─────────────────────────────────────────────────────────
    /** GPU 最高可用频点（Hz） */
    private static String gpuMaxAvail() {
        String s = ShellExec.catOrRoot("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies");
        if (s != null && s.length() > 0) {
            long best = -1;
            for (String x : s.trim().split("\\s+")) {
                try {
                    long v = Long.parseLong(x.trim());
                    if (v > best) best = v;
                } catch (Throwable ignored) { }
            }
            if (best > 0) return String.valueOf(best);
        }
        return ShellExec.catOrRoot("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq");
    }

    private static String gpuFloor(Context c, Feature f, boolean on) {
        String path = "/sys/class/kgsl/kgsl-3d0/min_pwrlevel";
        if (on) {
            String cur = ShellExec.catOrRoot(path);
            if (cur != null) Prefs.setS(c, "base." + f.id, cur);
            ShellExec.R r = ShellExec.su("echo 0 > " + path);
            // 上限必须取 gpu_available_frequencies 的最高档（本机 903000000）。
            // 原来读 devfreq/max_freq，那是"当前被允许的上限"，当时正好是 770000000，
            // 于是把 GPU 反过来锁死在 770MHz —— 越"锁最高档"越慢。
            String gmax = gpuMaxAvail();
            if (gmax != null && gmax.length() > 0) {
                ShellExec.su("settings put secure sys_perf_g_max " + gmax);
            }
            String now = ShellExec.catOrRoot("/sys/class/kgsl/kgsl-3d0/clock_mhz");
            f.setEnabled(c, true);
            return (r.ok() ? "min_pwrlevel=0 ✓ " : "写入失败 ✗ ") + "clock=" + now + " MHz";
        }
        String b = base(c, f.id, String.valueOf(Platform.gpuDefaultLevel()));
        ShellExec.R r = ShellExec.su("echo " + b + " > " + path);
        ShellExec.su("settings delete secure sys_perf_g_max");
        f.setEnabled(c, false);
        return "min_pwrlevel=" + b + (r.ok() ? " ✓" : " ✗");
    }

    // ── core_ctl ────────────────────────────────────────────────────
    private static String coreCtl(Context c, Feature f, boolean on) {
        String core = Platform.coreCtlCore();
        int min = Platform.coreCtlMin();
        String path = "/sys/devices/system/cpu/" + core + "/core_ctl/min_cpus";
        String tag = core + ".min_cpus=";
        if (on) {
            String cur = ShellExec.cat(path);
            if (cur != null) Prefs.setS(c, "base." + f.id, cur);
            ShellExec.R r = ShellExec.su("echo " + min + " > " + path);
            f.setEnabled(c, true);
            return tag + min + (r.ok() ? " ✓ " : " ✗ ") + Platform.describe();
        }
        String b = base(c, f.id, "0");
        ShellExec.R r = ShellExec.su("echo " + b + " > " + path);
        f.setEnabled(c, false);
        return tag + b + (r.ok() ? " ✓" : " ✗");
    }

    // ── settings 键 ─────────────────────────────────────────────────
    private static String settingsToggle(Context c, Feature f, boolean on, String onCmd, String offCmd) {
        ShellExec.R r = ShellExec.su(on ? onCmd : offCmd);
        f.setEnabled(c, on);
        return (on ? "已写入" : "已还原") + (r.ok() ? " ✓" : " ✗ " + r.out.trim());
    }

    // ── 系统更新冻结 ─────────────────────────────────────────────────
    private static String updateFreeze(Context c, Feature f, boolean on) {
        String onCmd = "pm disable-user --user 0 com.bbk.updater; pm disable-user --user 0 com.vivo.cota";
        String offCmd = "pm enable --user 0 com.bbk.updater; pm enable --user 0 com.vivo.cota";
        ShellExec.R r = ShellExec.su(on ? onCmd : offCmd);
        f.setEnabled(c, on);
        return (on ? "已停用 updater/cota" : "已恢复")
                + (r.ok() ? " ✓" : " ✗ " + r.out.trim());
    }

    /** 一键恢复：所有开关置 false，并逐条执行回滚 */
    public static String restoreAll(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Feature f : Catalog.features()) {
            if (!f.enabled(c)) continue;
            if (Feature.KIND_DIAG.equals(f.kind) || Feature.KIND_META.equals(f.kind)) {
                f.setEnabled(c, false);
                continue;
            }
            String r = apply(c, f, false);
            sb.append(f.name).append("：").append(r).append('\n');
        }
        return sb.length() == 0 ? "没有需要恢复的项" : sb.toString().trim();
    }
}
