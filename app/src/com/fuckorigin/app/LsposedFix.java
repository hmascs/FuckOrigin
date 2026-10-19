package com.fuckorigin.app;

/**
 * LSPosed 用 modules_config.db 里的 apk_path 定位模块。
 * 每次重装应用，/data/app/~~xxx~~ 目录会变，库里的旧路径随即失效，
 * 表现是"模块已启用、作用域也对，但就是不注入"，且没有任何报错。
 * 这里在每次启动时把路径校正成当前安装路径。
 */
public final class LsposedFix {

    /** 后台同步的结果缓存，供设置页直接展示（避免在 UI 线程跑 su） */
    private static volatile String last = "检测中…";

    public static final String DB = "/data/adb/lspd/config/modules_config.db";

    /** 返回一句人类可读的结果，供诊断页展示 */
    public static String sync(String pkg, String apkPath) {
        if (!ShellExec.hasRoot()) return "无 root，跳过模块路径同步";
        // 普通 uid 无法 stat /data/adb（0700 root），必须借 root 判断
        ShellExec.R ex = ShellExec.su("[ -f " + DB + " ] && echo yes");
        if (ex.out == null || !ex.out.contains("yes")) return "未找到 LSPosed 数据库，跳过同步";
        String sql = "update modules set apk_path='" + apkPath + "' where module_pkg_name='" + pkg + "';";
        ShellExec.R w = ShellExec.su("sqlite3 " + DB + " \"" + sql + "\"");
        if (!w.ok()) return "写入 LSPosed 数据库失败：" + w.out.trim();
        ShellExec.R r = ShellExec.su(
                "sqlite3 " + DB + " \"select apk_path from modules where module_pkg_name='" + pkg + "'\"");
        String cur = r.out == null ? "" : r.out.trim();
        if (cur.equals(apkPath)) return "模块路径已同步：" + shortPath(apkPath);
        if (cur.length() == 0) return "LSPosed 库中还没有本模块记录（先在管理器里启用一次）";
        return "路径仍不一致：库中为 " + shortPath(cur);
    }

    /** 只关心是否一致，供状态页判断 */
    public static boolean matches(String pkg, String apkPath) {
        ShellExec.R r = ShellExec.su(
                "sqlite3 " + DB + " \"select apk_path from modules where module_pkg_name='" + pkg + "'\"");
        return r.out != null && r.out.trim().equals(apkPath);
    }

    public static String lastResult() { return last; }

    private static String shortPath(String p) {
        int i = p.indexOf("/base.apk");
        String q = i > 0 ? p.substring(0, i) : p;
        int s = q.lastIndexOf("/~~");
        return s > 0 ? "…" + q.substring(s) + "/base.apk" : p;
    }

    /** 启动时后台校正一次，失败不影响主流程 */
    public static void autoSync(final android.content.Context ctx) {
        final String pkg = ctx.getPackageName();
        final String apk = ctx.getPackageCodePath();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    last = sync(pkg, apk);
                } catch (Throwable t) {
                    last = "同步异常：" + t;
                }
                android.util.Log.i("FuckOrigin", "PATH_SYNC|root=" + ShellExec.hasRoot()
                        + "|apk=" + apk + "|" + last);
            }
        }, "lsposed-path-sync").start();
    }
}
