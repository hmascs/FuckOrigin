package com.fuckorigin.app;

/** 从 logcat 读取被注入进程写下的标记与 hook 绑定结果。 */
public final class HookStatus {
    public static final String TAG = "FuckOrigin";

    public static String text() {
        ShellExec.R r = ShellExec.su("logcat -d -s " + TAG + ":I 2>/dev/null | tail -80");
        String raw = r == null ? null : r.out;
        if (raw == null || raw.trim().length() == 0) return "未检测到 Hook 标记。\n\n"
                + "排查顺序：\n"
                + "1) LSPosed → 模块 → FuckOrigin → 打开「启用模块」；\n"
                + "2) 在同一页点选「游戏魔盒 com.vivo.gamecube」与「系统界面 com.android.systemui」；\n"
                + "3) 重启游戏魔盒（或重启手机）后再看本页。";

        String head = null;
        long ts = 0;
        int ok = 0, miss = 0;
        StringBuilder list = new StringBuilder();
        for (String ln : raw.split("\n")) {
            int i = ln.indexOf(TAG);
            if (i < 0) continue;
            String body = ln.substring(i + TAG.length()).trim();
            if (body.startsWith(":")) body = body.substring(1).trim();
            if (body.contains("|") && !body.startsWith("H|")) {
                String[] p = body.split("\\|");
                if (p.length >= 3) {
                    try {
                        long t = Long.parseLong(p[2].trim());
                        if (t >= ts) { ts = t; head = p[1]; }
                    } catch (Throwable ignored) { }
                }
            } else if (body.startsWith("H|")) {
                String s2 = body.substring(2);
                int j = s2.indexOf('|');
                if (j > 0) s2 = s2.substring(j + 1).trim();
                if (s2.startsWith("OK")) ok++;
                else if (s2.startsWith("MISS")) { miss++; list.append("\n  ").append(s2); }
            }
        }
        if (head == null) return "已检测到 LSPosed 日志，但没有可解析的标记。\n\n原始输出：\n" + raw.trim();

        StringBuilder sb = new StringBuilder();
        long min = (System.currentTimeMillis() - ts) / 60000L;
        sb.append("已生效：进程 ").append(head);
        if (min >= 0 && min < 100000) sb.append("，最近 ").append(min).append(" 分钟前");
        sb.append("\n已挂上 ").append(ok).append(" 条 hook");
        if (miss > 0) sb.append("，未匹配 ").append(miss).append(" 条：").append(list);
        return sb.toString();
    }
}
