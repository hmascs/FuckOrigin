package com.fuckorigin.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三层分类体系：一级作用域（OriginOS 组件） → 二级功能域 → 功能条目。 */
public final class Catalog {

    public static final class Domain {
        public final String id, name, pkg, icon, desc;
        public Domain(String id, String name, String pkg, String icon, String desc) {
            this.id = id; this.name = name; this.pkg = pkg; this.icon = icon; this.desc = desc;
        }
    }

    private static final List<Domain> DOMAINS = new ArrayList<>();
    private static final List<Feature> FEATURES = new ArrayList<>();
    private static final Map<String, List<Feature>> BY_DOMAIN = new LinkedHashMap<>();

    static {
        DOMAINS.add(new Domain("perf", "性能与温控", "com.vivo.gamecube / thermal-engine", "ic_thermo",
                "频率、核心调度、温控旁路、Q2 豁免"));
        DOMAINS.add(new Domain("game", "游戏与侧边栏", "com.vivo.gamecube", "ic_game",
                "侧边栏注入、性能面板、直驱供电"));
        DOMAINS.add(new Domain("ui", "界面与交互", "com.android.systemui", "ic_setting_gc",
                "状态栏、动画曲线、时间显示"));
        DOMAINS.add(new Domain("cloud", "系统更新与云控", "com.bbk.updater / com.vivo.abe", "ic_cloud",
                "OTA 冻结、云控链路、组件清单"));
        DOMAINS.add(new Domain("bg", "后台与功耗", "com.iqoo.secure", "ic_power",
                "自启动、Doze 白名单"));
        DOMAINS.add(new Domain("notif", "通知与权限", "com.android.systemui", "ic_notify",
                "通知优先级、营销通知拦截"));
        DOMAINS.add(new Domain("island", "原子通知与原子岛", "com.android.systemui(ISLAND)", "ic_island",
                "展示位置与优先级"));
        DOMAINS.add(new Domain("dev", "设备与调试", "system", "ic_dev",
                "温控诊断、设备信息、无线 ADB"));

        // ── 合并组（把多个细项并成一个开关）───────────────────────────
        Feature full = new Feature("perf.fullpower", "perf", "性能满血（合并）", "中", "已实现",
                Feature.KIND_GROUP, "CPU 顶频 + performance 调度 + GPU 锁频 + 大核常驻",
                "一键把 4 个性能项同时打开/关闭；细项仍可在本域下方单独调整。");
        full.children = new String[] { "perf.cpufreq.max", "perf.governor", "perf.gpu.floor", "perf.core_ctl.big" };
        add(full);

        Feature thm = new Feature("thermal.group", "perf", "温控解除（合并）", "低", "已定位",
                Feature.KIND_GROUP, "e#b()D + G0(D)Z + 插帧/超分/视效三项豁免",
                "一次性打开总开关与三项 Q2 豁免；细项仍可单独关。");
        thm.children = new String[] { "thermal.bypass.global", "q2.mask.memc", "q2.mask.sr", "q2.mask.ve" };
        add(thm);

        Feature unlockAll = new Feature("q2.unlock.all", "game", "解锁全部 Q 芯功能（合并）", "中", "已定位",
                Feature.KIND_GROUP, "温控 + Monster+ + 超分标志位 一起放开",
                "一次打开：温控解除、插帧/超分/视效豁免、强制 Monster+。背夹判定仍未覆盖。");
        unlockAll.children = new String[] { "thermal.bypass.global", "q2.mask.memc", "q2.mask.sr",
                "q2.mask.ve", "perf.monsterplus.force" };
        add(unlockAll);

        // ── 性能与温控 ────────────────────────────────────────────────
        add(new Feature("hook.master", "perf", "全局 Hook 开关（熔断）", "低", "已实现",
                Feature.KIND_SETTINGS, "Settings.Secure sys_perf_hooks",
                "关闭后所有 hook 立即恢复原厂逻辑（不需卸载模块），用于排查"
                        + "侧边栏/Q 芯页异常时快速定位。默认开启。"));
        add(new Feature("game.panel.ui", "game", "侧边栏频率面板（实验）", "中", "已实现",
                Feature.KIND_SETTINGS, "Settings.Secure sys_perf_ui",
                "开启后游戏侧边栏底部出现频率条。默认关闭：早期版本注入形态有缺陷，"
                        + "会干扰侧边栏收起与 Q 芯页打开，确认稳定后再开启。"));
        add(new Feature("perf.fps.lock", "perf", "锁帧 · 始终最高帧率", "中", "已定位",
                Feature.KIND_ROOT, "persist.vivo.phone.fps_max / peak_refresh_rate / min_refresh_rate",
                "把设备侧帧率上限从 120 抬到 144，并同时钉住 min/peak 刷新率，"
                        + "避免温度或省电策略把帧率压下来。Hook 侧同时改写 e0#v() 的返回值。"));
        add(new Feature("thermal.bypass.global", "perf", "温控解除（总开关）", "低", "已定位",
                Feature.KIND_HOOK, "com.vivo.common.e#b()D · frameinterpolation.h#G0(D)Z",
                "需 LSPosed，作用域 com.vivo.gamecube。开启后本机温度源与过热判定对 Q2 调用方放行。"));
        add(new Feature("q2.mask.memc", "perf", "插帧 MEMC 温控豁免", "低", "已定位",
                Feature.KIND_HOOK, "frameinterpolation.h#G0(D)Z / z0()",
                "与总开关同源；单独关闭时仅插帧恢复原生判定。"));
        add(new Feature("q2.mask.sr", "perf", "超分 温控豁免", "低", "已定位",
                Feature.KIND_HOOK, "superresolution.j#a1()/R0()/O0()",
                "阈值 48℃/45℃ 来自 /system/etc/gamecube/SuperResolutionConfigList.xml。"));
        add(new Feature("q2.mask.ve", "perf", "视效增强 温控豁免", "低", "已定位",
                Feature.KIND_HOOK, "displayenhance.c/f#…", "DisplayEnhancement 的过热分支。"));
        add(new Feature("perf.cpufreq.max", "perf", "CPU 顶频锁定", "中", "已实现",
                Feature.KIND_ROOT, "policy0/policy6/scaling_max_freq", "写入 cpuinfo_max_freq，关闭时回滚基线。"));
        add(new Feature("perf.governor", "perf", "调度器切 performance", "中", "已实现",
                Feature.KIND_ROOT, "policy*/scaling_governor", "本机可选 walt/conservative/powersave/performance/schedutil。"));
        add(new Feature("perf.gpu.floor", "perf", "GPU 锁最高档", "中", "已实现",
                Feature.KIND_ROOT, "kgsl-3d0/min_pwrlevel = 0", "14 档频率，最高 1.1GHz；写后读 clock_mhz 验证。"));
        add(new Feature("perf.core_ctl.big", "perf", "大核常驻双核", "中", "已实现",
                Feature.KIND_ROOT, "cpu6/core_ctl/min_cpus = 2", "实测 cpu6 簇 max=2/min=0/active=1。"));

        // ── 游戏与侧边栏 ──────────────────────────────────────────────
        
        add(new Feature("game.panel.readout", "game", "性能面板读数扩展", "低", "已定位",
                Feature.KIND_HOOK, "PerformancePanelPanelView#onAttachedToWindow",
                "向 performance_content_cl(0x7f0a0715) 追加温度/频率行。"));
        add(new Feature("game.bypass.charge", "game", "直驱供电", "中", "待实验",
                Feature.KIND_META, "persist.vivo.game_bypass_charge_flag",
                "属性存在(=0/support=1)，是否真切换需看充电电流。"));

        // ── 界面与交互 ────────────────────────────────────────────────
        add(new Feature("ui.statusbar.seconds", "ui", "状态栏时间到秒", "低", "待实验",
                Feature.KIND_SETTINGS, "Settings.System.clock_seconds",
                "键名从 SystemUI dex 中取得，vivo 是否沿用需实测。"));
        add(new Feature("ui.statusbar.icons", "ui", "状态栏图标黑名单", "低", "待实验",
                Feature.KIND_META, "Settings.System.icon_blacklist",
                "同源键名；写入前需确认 vivo 语义，暂不自动改写。"));
        

        // ── 系统更新与云控 ────────────────────────────────────────────
        add(new Feature("update.freeze", "cloud", "系统更新冻结", "中高", "已实现",
                Feature.KIND_PKG, "pm disable-user com.bbk.updater / com.vivo.cota",
                "可逆（pm enable）。注意 updater 依赖 abe 的 configlist，需同时冻结云控才彻底。"));
        
        add(new Feature("cloud.inventory", "cloud", "云控组件清单", "低", "已实现",
                Feature.KIND_DIAG, "assets/cloud_inventory.txt",
                "本机扫描得到的端点与 Provider 清单。"));

        // ── 后台与功耗 ────────────────────────────────────────────────
        
        add(new Feature("bg.doze.whitelist", "bg", "Doze 白名单", "中", "待实验",
                Feature.KIND_META, "dumpsys deviceidle whitelist", "命令级可做，未接入。"));

        // ── 通知与权限 ────────────────────────────────────────────────
        

        // ── 原子通知与原子岛 ──────────────────────────────────────────
        

        // ── 设备与调试 ────────────────────────────────────────────────
        add(new Feature("perf.monsterplus.force", "perf", "强制 Monster+ 模式", "中", "已定位",
                Feature.KIND_HOOK, "com.vivo.gameassistant.entity.MonsterPlusEvent#isMonster()Z",
                "Q 芯专区的超分/插帧在「未连背夹或未开 Monster+」时不可用；此项让 MonsterPlusEvent 恒为 true。"));
        add(new Feature("perf.pem.thermal_mode", "perf", "PEM 温控模式（实验）", "高", "待实验",
                Feature.KIND_ROOT, "/proc/pem/thml_type",
                "PowerEffectManager 的温控模式节点，实测 rw-rw-rw-。仅在 温度<40℃ 且未充电 时可开，开启后 60 秒自动写回原值。"));
        add(new Feature("diag.live", "dev", "实时监控", "低", "已实现",
                Feature.KIND_DIAG, "tshell · cpufreq · kgsl · core_ctl · PEM",
                "1 秒刷新的只读面板。"));
        add(new Feature("q2.thresholds", "game", "Q2 阈值表（本地配置）", "低", "已实现",
                Feature.KIND_DIAG, "/system/etc/gamecube/SuperResolutionConfigList.xml",
                "按游戏解析过热/恢复/超分档位，只读。"));
        add(new Feature("hook.status", "dev", "Hook 生效状态", "低", "已实现",
                Feature.KIND_DIAG, "/data/local/tmp/fuckorigin_hook_alive",
                "LSPosed 侧写入的存活标记，用来确认 Hook 是否真的跑过。"));
        add(new Feature("dev.thermal.diag", "dev", "温控诊断", "低", "已实现",
                Feature.KIND_DIAG, "tshell / thermal_zone(按 type) / cpufreq / kgsl",
                "一键输出当前温度、频率、热状态、core_ctl。"));
        add(new Feature("dev.info", "dev", "设备信息", "低", "已实现",
                Feature.KIND_DIAG, "Build / getprop", "机型、ROM、root 状态。"));
        add(new Feature("dev.adb.wifi", "dev", "无线 ADB", "低", "已实现",
                Feature.KIND_SETTINGS, "settings put global adb_wifi_enabled",
                "开关此项只改系统开关，实际连接仍需 adb 配对。"));
    }

    private static void add(Feature f) {
        FEATURES.add(f);
        List<Feature> l = BY_DOMAIN.get(f.domain);
        if (l == null) { l = new ArrayList<>(); BY_DOMAIN.put(f.domain, l); }
        l.add(f);
    }

    public static List<Domain> domains() { return DOMAINS; }
    public static List<Feature> features() { return FEATURES; }
    public static List<Feature> of(String domain) {
        List<Feature> l = BY_DOMAIN.get(domain);
        return l == null ? new ArrayList<Feature>() : l;
    }
    public static Domain domain(String id) {
        for (Domain d : DOMAINS) if (d.id.equals(id)) return d;
        return null;
    }
    public static Feature feature(String id) {
        for (Feature f : FEATURES) if (f.id.equals(id)) return f;
        return null;
    }
    public static int enabledCount(android.content.Context c, String domain) {
        int n = 0;
        for (Feature f : of(domain)) if (f.enabled(c)) n++;
        return n;
    }
}
