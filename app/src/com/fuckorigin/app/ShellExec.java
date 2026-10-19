package com.fuckorigin.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** 统一的 shell / 文件读取入口。所有写操作都走 su，读操作优先直接读文件。 */
public final class ShellExec {

    public static final class R {
        public final int code;
        public final String out;
        R(int c, String o) { code = c; out = o; }
        public boolean ok() { return code == 0; }
    }

    private static final String[] SU = { "su", "-c", null };
    private static final String PATH = "export PATH=/system/bin:/vendor/bin:/product/bin; ";

    public static boolean hasRoot() {
        try {
            R r = exec(new String[] { "su", "-c", "id" }, 6000);
            return r.ok() && r.out.contains("uid=0");
        } catch (Throwable t) { return false; }
    }

    public static R su(String cmd) {
        return exec(new String[] { "su", "-c", PATH + cmd }, 20000);
    }

    public static R exec(String[] argv, int timeoutMs) {
        Process p = null;
        StringBuilder sb = new StringBuilder();
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) { p.destroy(); return new R(124, sb + "\n[timeout]"); }
            return new R(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            return new R(-1, String.valueOf(t.getMessage()));
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 直接读（不 root），读不到返回 null */
    public static String cat(String path) {
        FileInputStream in = null;
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return null;
            in = new FileInputStream(f);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 直接读，失败再 su cat */
    public static String catOrRoot(String path) {
        String v = cat(path);
        if (v != null && v.length() > 0) return v;
        R r = su("cat " + path);
        return r.ok() ? r.out.trim() : null;
    }
}
