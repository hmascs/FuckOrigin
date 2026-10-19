package com.fuckorigin.app;

/** 给监控面板用的几个只读小工具（避免把 MainActivity 撑更大）。 */
public final class MainActivityRoom {

    /** 按 type 命中关键温区，读 temp；不依赖索引顺序 */
    public static String tzoneSummary() {
        StringBuilder sb = new StringBuilder();
        String[] want = { "gpuss-0", "gpuss-3", "cpu-0-0-0", "cpuss-0-0", "tz_shell", "tz_game_shell", "battery" };
        for (String w : want) {
            String v = findZone(w);
            if (v != null) sb.append(pad(w, 14)).append(v).append('\n');
        }
        return sb.length() == 0 ? "(读取失败，需 root)\n" : sb.toString();
    }

    private static String findZone(String type) {
        for (int i = 0; i < 100; i++) {
            String base = "/sys/class/thermal/thermal_zone" + i;
            String t = ShellExec.cat(base + "/type");
            if (t == null) continue;
            if (t.trim().equals(type)) {
                String temp = ShellExec.cat(base + "/temp");
                return temp == null ? "-" : (temp.trim() + " m°C");
            }
        }
        return null;
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }
}
