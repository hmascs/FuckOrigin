package com.fuckorigin.app;

import android.content.Context;
import java.io.File;

/**
 * PEM（PowerEffectManager）温控模式节点。
 * 依据：com.vivo.pem 的 com.ueppp.misc.temperature.ThermalTypeConfig#g(int) 会写 /proc/pem/thml_type；
 * 该节点实测为 -rw-rw-rw-（普通应用可写）。
 *
 * 安全约束（本类强制）：
 *   1) 只有 温度 < 40℃ 且 未充电 时才允许切换；
 *   2) 打开后 60 秒自动写回原值；
 *   3) 原值写入 Prefs，进程被杀后下次启动也能恢复。
 */
public final class Pem {

    public static final String NODE = "/proc/pem/thml_type";
    public static final String FAN = "/proc/pem/thml_fan_type";
    public static final String HEAT = "/proc/pem/heatlimit";
    private static final String KEY_BASE = "pem.base_value";
    private static final String KEY_ON = "pem.experiment_on";
    private static final String KEY_ON_AT = "pem.on_at";

    public static String readNode(String path) {
        String v = ShellExec.cat(path);
        if (v != null) return v;
        ShellExec.R r = ShellExec.su("cat " + path);
        return r.ok() ? r.out.trim() : null;
    }

    /** 当前是否满足安全前置条件 */
    public static String gate(Context c) {
        String t = Diag.quickTemp();               // 形如 "45.8℃ / 45.2℃"
        double temp = parseFirstTemp(t);
        boolean charging = isCharging();
        if (temp < 0) return "无法读取温度，禁止操作";
        if (charging) return "正在充电，禁止操作（" + t + "）";
        if (temp >= 40.0) return "温度过高（" + t + "），请降至 40℃ 以下";
        return null;
    }

    public static boolean isOn(Context c) {
        return Prefs.get(c, KEY_ON, false);
    }

    /** 开启：记录原值 → 写 0 → 标记时间；由 MainActivity 负责 60s 后自动恢复 */
    public static String enable(Context c) {
        String block = gate(c);
        if (block != null) return block;
        String cur = readNode(NODE);
        if (cur == null) return "读取 " + NODE + " 失败（可能无权限）";
        Prefs.setS(c, KEY_BASE, cur.trim());
        ShellExec.R r = ShellExec.su("echo 0 > " + NODE);
        if (!r.ok()) return "写入失败：" + r.out.trim();
        Prefs.set(c, KEY_ON, true);
        Prefs.setS(c, KEY_ON_AT, String.valueOf(System.currentTimeMillis()));
        return "已写入 0（原值 " + cur.trim() + "，60 秒后自动恢复）";
    }

    public static String disable(Context c) {
        String base = Prefs.getS(c, KEY_BASE, null);
        Prefs.set(c, KEY_ON, false);
        if (base == null) return "无基线记录，未做改动";
        ShellExec.R r = ShellExec.su("echo " + base + " > " + NODE);
        return r.ok() ? "已恢复为 " + base : "恢复失败：" + r.out.trim();
    }

    public static long onAt(Context c) {
        try { return Long.parseLong(Prefs.getS(c, KEY_ON_AT, "0")); } catch (Throwable t) { return 0; }
    }

    public static boolean isCharging() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent i = App.get().registerReceiver(null, f);
            if (i == null) return true;
            int s = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            return s == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || s == android.os.BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) { return true; }
    }

    private static double parseFirstTemp(String s) {
        try {
            String head = s.split("/")[0].trim().replace("℃", "").trim();
            return Double.parseDouble(head);
        } catch (Throwable t) { return -1; }
    }
}
