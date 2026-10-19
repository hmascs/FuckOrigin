package com.fuckorigin.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 去温控模块（KernelSU）的安装状态检查与一键安装。 */
public final class ModuleStatus {

    public static final String DIR = "/data/adb/modules/fuckorigin-thermal";
    private static final String EXE = "/data/adb/fuckorigin/bin/fo_perf.sh";

    public static boolean installed() {
        ShellExec.R r = ShellExec.su("[ -f " + DIR + "/module.prop ] && echo yes");
        return r.out != null && r.out.contains("yes");
    }

    public static boolean enabled() {
        ShellExec.R r = ShellExec.su("[ -f " + DIR + "/disable ] && echo disabled || echo enabled");
        return r.out != null && r.out.contains("enabled");
    }

    public static boolean executorRunning() {
        ShellExec.R r = ShellExec.su("ps -A -o args 2>/dev/null | grep -c '[f]o_perf.sh'");
        try { return r.out != null && Integer.parseInt(r.out.trim()) > 0; }
        catch (Throwable t) { return false; }
    }

    /** 一句话状态，给首页提示用 */
    public static String summary() {
        if (!ShellExec.hasRoot()) return "未获取 root：频率控制与去温控不可用";
        if (!installed()) return "去温控模块未安装：滑块与信仰模式不会真正生效";
        if (!enabled()) return "去温控模块已安装但被禁用";
        if (!executorRunning()) return "去温控模块已安装，但执行器未运行";
        return "全部功能已启用";
    }

    public static boolean allReady() {
        return ShellExec.hasRoot() && installed() && enabled() && executorRunning();
    }

    /** 一键安装/修复：写入模块文件、清掉 disable、拉起执行器 */
    public static String install(Context ctx) {
        if (!ShellExec.hasRoot()) return "没有 root，无法安装";
        StringBuilder log = new StringBuilder();
        try {
            ShellExec.su("mkdir -p " + DIR + "/bin " + DIR + "/system/etc/gamecube "
                    + DIR + "/webroot /data/adb/fuckorigin/bin /data/adb/fuckorigin/log");
            String[] names = ctx.getAssets().list("module");
            if (names != null) {
                for (String n : names) {
                    copyAssetTree(ctx, "module/" + n, DIR + "/" + n, log);
                }
            }
            // 执行器需要同时在 /data/adb/fuckorigin/bin
            byte[] perf = readAsset(ctx, "module/fo_perf.sh");
            if (perf != null) {
                String tmp = ctx.getFilesDir() + "/fo_perf.sh";
                FileOutputStream fo = new FileOutputStream(tmp);
                fo.write(perf);
                fo.close();
                ShellExec.su("cp '" + tmp + "' " + EXE);
            }
            ShellExec.su("chmod 755 " + DIR + "/service.sh " + DIR + "/post-fs-data.sh " + EXE
                    + " " + DIR + "/bin/fuckorigind "
                    + "; chmod 644 " + DIR + "/module.prop " + DIR + "/system/etc/gamecube/*.xml"
                    + "; rm -f " + DIR + "/disable"
                    + "; mkdir -p /data/adb/fuckorigin/log");
            ShellExec.su("for p in $(ps -A -o pid,args 2>/dev/null | grep '[f]o_perf.sh' | awk '{print $1}'); do kill $p; done;"
                    + " nohup setsid " + EXE + " >/dev/null 2>&1 < /dev/null &");
            log.append("已启用并启动执行器");
        } catch (Throwable t) {
            return "安装失败：" + t;
        }
        return log.toString();
    }

    private static void copyAssetTree(Context ctx, String assetPath, String destPath,
                                      StringBuilder log) {
        try {
            String[] kids = ctx.getAssets().list(assetPath);
            if (kids != null && kids.length > 0) {
                ShellExec.su("mkdir -p '" + destPath + "'");
                for (String k : kids) copyAssetTree(ctx, assetPath + "/" + k, destPath + "/" + k, log);
                return;
            }
            byte[] data = readAsset(ctx, assetPath);
            if (data == null) return;
            String fileName = assetPath.substring(assetPath.lastIndexOf('/') + 1);
            String tmp = ctx.getFilesDir() + "/" + fileName;
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(data);
            fo.close();
            ShellExec.su("mkdir -p '$(dirname " + destPath + ")'; cp '" + tmp + "' '" + destPath + "'");
            log.append(fileName).append(' ');
        } catch (Throwable t) {
            log.append("[失败 ").append(assetPath).append("] ");
        }
    }

    private static byte[] readAsset(Context ctx, String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private ModuleStatus() { }
}
