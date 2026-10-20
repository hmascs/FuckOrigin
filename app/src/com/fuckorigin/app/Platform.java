package com.fuckorigin.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台自适应层。
 *
 * 原作者只在骁龙 8 至尊版（sun / SM8750）+ OriginOS 6 上实测，代码里把
 * 「超大核簇 = policy6」「cpu6/core_ctl」「GPU 14 档」这些写死了。
 * 8 Gen 3（pineapple / SM8650）的簇编号完全不同：
 *
 *   8 Elite  (sun)       policy0(小核 cpu0-5)  policy6(超大核 cpu6-7)
 *   8 Gen 3  (pineapple) policy0(cpu0-1) policy2(cpu2-4) policy5(cpu5-6) policy7(cpu7=X4)
 *
 * 注意：8 Gen 3 上 X4 簇的 cpuinfo_max_freq(3052800) 反而低于 cpu2-4 簇(3148800)，
 * 所以「最高频=超大核」的判据在这里是错的。改用「核数最少的簇 = 超大核」
 * （8 Elite 的 policy6 有 2 核、8 Gen 3 的 policy7 只有 1 核），同核数时再看频率。
 *
 * GPU 档数从 gpu_available_frequencies 数出来（8 Elite 14 档 / 8 Gen 3 12 档）；
 * 配置表同理，8 Gen 3 的 /system/etc/gamecube 下只有 FrameInterConfigList.xml。
 */
public final class Platform {

    public static final String CPUFREQ_DIR = "/sys/devices/system/cpu/cpufreq/";
    public static final String CPU_DIR = "/sys/devices/system/cpu/";
    public static final String KGSL = "/sys/class/kgsl/kgsl-3d0";
    public static final String GAMECUBE_ETC = "/system/etc/gamecube/";

    /** 一个 cpufreq 簇 */
    public static final class Pol {
        public String name;        // policy7
        public int[] cpus;         // {7}
        public long maxFreq;       // 3052800
        public int index;          // 7
        public String toString() { return name + "(" + cpus.length + "核/" + maxFreq + ")"; }
    }

    private static final List<Pol> POLS = new ArrayList<Pol>();
    private static Pol prime, little;
    private static int gpuLevels = 14;
    private static String gamecubeCfg = GAMECUBE_ETC + "SuperResolutionConfigList.xml";
    private static boolean probed;

    private Platform() { }

    /** 幂等；只读 sysfs / 普通文件，不调 su，可在任意线程调用。 */
    public static synchronized void probe() {
        if (probed) return;
        probed = true;

        POLS.clear();
        POLS.addAll(scan());

        if (POLS.isEmpty()) {
            // 极端兜底：至少让调用方拿到 8 Elite 的旧行为
            Pol p = new Pol();
            p.name = "policy6"; p.cpus = new int[] { 6, 7 }; p.maxFreq = 0; p.index = 6;
            POLS.add(p);
            Pol q = new Pol();
            q.name = "policy0"; q.cpus = new int[] { 0, 1 }; q.maxFreq = 0; q.index = 0;
            POLS.add(q);
        }

        little = null;
        prime = null;
        for (Pol p : POLS) {
            // maxFreq<=0 表示没读到 cpuinfo_max_freq，不能拿它当"最低频簇"
            if (p.maxFreq > 0 && (little == null || p.maxFreq < little.maxFreq)) little = p;
            if (prime == null) { prime = p; continue; }
            // 核数少者优先（超大核簇），核数相同看频率，再相同看编号
            if (p.cpus.length < prime.cpus.length) prime = p;
            else if (p.cpus.length == prime.cpus.length) {
                if (p.maxFreq > prime.maxFreq) prime = p;
                else if (p.maxFreq == prime.maxFreq && p.index > prime.index) prime = p;
            }
        }
        if (little == null) {
            // 所有簇都读不到频率（极端情况）：退回编号最小的簇，别让 little 跟着 prime 跑
            little = POLS.get(0);
            for (Pol p : POLS) if (p.index < little.index) little = p;
        }
        if (prime == null) {
            // POLS 非空时不会走到这里，保险起见给个可用值
            prime = new Pol();
            prime.name = "policy0"; prime.cpus = new int[] { 0 }; prime.maxFreq = 0; prime.index = 0;
            little = prime;
        }

        int n = tokens(ShellExec.cat(KGSL + "/gpu_available_frequencies"));
        if (n <= 0) n = tokens(ShellExec.cat(KGSL + "/freq_table_mhz"));
        if (n > 0) gpuLevels = n;

        String[] cands = { "SuperResolutionConfigList.xml", "FrameInterConfigList.xml" };
        for (String f : cands) {
            if (new File(GAMECUBE_ETC + f).exists()) {
                gamecubeCfg = GAMECUBE_ETC + f;
                break;
            }
        }
    }

    private static List<Pol> scan() {
        List<Pol> out = new ArrayList<Pol>();
        File d = new File(CPUFREQ_DIR);
        String[] names = d.list();
        if (names == null) return out;
        java.util.TreeSet<String> set = new java.util.TreeSet<String>();
        for (String n : names) {
            if (n.matches("policy\\d+")) set.add(n);
        }
        for (String n : set) {
            Pol p = new Pol();
            p.name = n;
            try { p.index = Integer.parseInt(n.substring(6)); } catch (Throwable t) { p.index = 0; }
            p.maxFreq = num(ShellExec.cat(CPUFREQ_DIR + n + "/cpuinfo_max_freq"));
            String rel = ShellExec.cat(CPUFREQ_DIR + n + "/related_cpus");
            p.cpus = parseCpus(rel);
            if (p.cpus.length == 0) p.cpus = new int[] { p.index };
            out.add(p);
        }
        return out;
    }

    private static int[] parseCpus(String s) {
        if (s == null) return new int[0];
        String t = s.trim();
        if (t.length() == 0) return new int[0];
        String[] parts = t.split("\\s+");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { r[i] = Integer.parseInt(parts[i].trim()); } catch (Throwable e) { return new int[0]; }
        }
        return r;
    }

    /** 只读副本，调用方改它不会污染内部状态 */
    public static List<Pol> policies() {
        probe();
        return new ArrayList<Pol>(POLS);
    }

    /** 性能相关簇：小核 + 超大核（对应原代码的 policy0 / policy6 语义） */
    public static String[] perfPolicies() {
        probe();
        return new String[] { little.name, prime.name };
    }

    public static String perfPoliciesShell() {
        probe();
        return little.name + " " + prime.name;
    }

    public static String primePolicy() { probe(); return prime.name; }

    public static String littlePolicy() { probe(); return little.name; }

    public static String primeDir() { return CPUFREQ_DIR + primePolicy(); }

    /** 超大核簇的首核（8 Elite = cpu6，8 Gen 3 = cpu7） */
    public static String primeCore() {
        probe();
        return "cpu" + prime.cpus[0];
    }

    /** core_ctl 相关核：小核簇首核 + 超大核簇首核 */
    public static String[] coreCtlCores() {
        probe();
        return new String[] { "cpu" + little.cpus[0], primeCore() };
    }

    /** core_ctl 目标核（超大核簇；若该簇只有 1 核，min_cpus 只能写 1） */
    public static String coreCtlCore() { return primeCore(); }

    /** core_ctl min_cpus 的取值：原意「常驻双核」，簇内不足 2 核就退化为 1 */
    public static int coreCtlMin() {
        probe();
        return prime.cpus.length >= 2 ? 2 : 1;
    }

    /** GPU 频率档数（8 Elite 14 / 8 Gen 3 12） */
    public static int gpuLevels() { probe(); return gpuLevels; }

    /** min_pwrlevel 的最大合法值 = 档数 - 1（0 是最高频） */
    public static int gpuDefaultLevel() { probe(); return gpuLevels - 1; }

    public static String gamecubeCfg() { probe(); return gamecubeCfg; }

    /** 一行平台摘要，给界面/日志用 */
    public static String describe() {
        probe();
        StringBuilder sb = new StringBuilder();
        sb.append("超大核簇 ").append(prime.name).append("（").append(primeCore())
          .append("）· 小核簇 ").append(little.name)
          .append(" · GPU ").append(gpuLevels).append(" 档")
          .append(" · 配置表 ").append(new File(gamecubeCfg).getName());
        return sb.toString();
    }

    private static int tokens(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.length() == 0) return 0;
        return t.split("\\s+").length;
    }

    private static long num(String s) {
        if (s == null) return -1;
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return -1; }
    }
}
