package com.fuckorigin.app;

/** 运行环境探测：root / LSPosed 安装 / Hook 是否真的跑过。 */
public final class Env {

    public static boolean root;
    public static boolean lsposedInstalled;
    public static boolean hookAlive;
    public static boolean probed;
    public static String hookDetail = "";

    public static void probe() {
        probed = true;
        root = ShellExec.hasRoot();
        lsposedInstalled = lsposedInstalled();
        String h = HookStatus.text();
        hookAlive = !h.startsWith("未检测到");
        hookDetail = h;
    }

    private static boolean lsposedInstalled() {
        // 三种口径任一命中即认为已安装：模块目录 / 管理器包名 / 运行中的守护进程
        try {
            ShellExec.R r = ShellExec.su(
                "test -d /data/adb/modules/zygisk_lsposed && echo 1; " +
                "pm list packages | grep -ci lsposed; " +
                "ps -A | grep -ci lsposed");
            if (r.ok()) {
                String[] lines = r.out.trim().split("\\s+");
                for (String line : lines) {
                    try { if (Integer.parseInt(line.trim()) > 0) return true; } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 返回需要提示的问题清单；空表示环境正常 */
    public static String issues() {
        StringBuilder sb = new StringBuilder();
        if (!root) sb.append("• 没有 root 权限：sysfs 写频、PEM 实验、系统更新冻结全部不可用\n");
        if (!lsposedInstalled) sb.append("• 未检测到 LSPosed：温控解除、Q2 豁免、侧边栏注入等 Hook 类功能不会生效\n");
        else if (!hookAlive) sb.append("• LSPosed 已安装但未捕获到 Hook 标记：请确认模块已启用、作用域勾选了 com.vivo.gamecube，并重启过一次游戏魔盒\n");
        return sb.toString().trim();
    }
}
