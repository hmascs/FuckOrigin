package com.fuckorigin.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** 配置备份 / 恢复 / 重置：导出成 JSON 存到应用外部目录，无需存储权限。 */
public final class Config {

    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "backup");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String export(Context c) {
        try {
            StringBuilder sb = new StringBuilder("{\n");
            sb.append("\"version\": \"0.3.0\",\n\"features\": {");
            boolean first = true;
            for (Feature f : Catalog.features()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(f.id).append("\":").append(f.enabled(c));
            }
            sb.append("},\n\"settings\": {");
            sb.append("\"icon\":\"").append(Prefs.getS(c, Prefs.SET_ICON, "禁用")).append("\",");
            sb.append("\"lang\":\"").append(Prefs.getS(c, Prefs.SET_LANG, "简体中文")).append("\",");
            sb.append("\"entry\":\"").append(Prefs.getS(c, Prefs.SET_ENTRY, "首页")).append("\"");
            sb.append("}\n}\n");
            String name = "config-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
            File out = new File(dir(c), name);
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            return "已导出：" + out.getAbsolutePath();
        } catch (Throwable t) { return "导出失败：" + t.getMessage(); }
    }

    public static String[] list(Context c) {
        File[] fs = dir(c).listFiles(new FilenameFilter() {
            public boolean accept(File d, String n) { return n.endsWith(".json"); }
        });
        if (fs == null) return new String[0];
        java.util.Arrays.sort(fs);
        String[] out = new String[fs.length];
        for (int i = 0; i < fs.length; i++) out[i] = fs[fs.length - 1 - i].getName();
        return out;
    }

    public static String restore(Context c, String name) {
        try {
            File f = new File(dir(c), name);
            String s = new String(readAll(f), "UTF-8");
            int n = 0;
            for (Feature ft : Catalog.features()) {
                String key = "\"" + ft.id + "\":";
                int i = s.indexOf(key);
                if (i < 0) continue;
                boolean v = s.startsWith("true", i + key.length());
                ft.setEnabled(c, v);
                n++;
            }
            for (String k : new String[] { Prefs.SET_ICON, Prefs.SET_LANG, Prefs.SET_ENTRY }) {
                String shortK = k.substring(k.indexOf('.') + 1);
                int i = s.indexOf("\"" + shortK + "\":\"");
                if (i < 0) continue;
                int b = i + shortK.length() + 4;
                int e = s.indexOf('"', b);
                if (e > b) Prefs.setS(c, k, s.substring(b, e));
            }
            return "已恢复 " + n + " 项开关（配置：" + name + "）";
        } catch (Throwable t) { return "恢复失败：" + t.getMessage(); }
    }

    public static String reset(Context c) {
        try {
            for (Feature f : Catalog.features()) f.setEnabled(c, false);
            Prefs.setS(c, Prefs.SET_ICON, "禁用");
            Prefs.setS(c, Prefs.SET_LANG, "简体中文");
            Prefs.setS(c, Prefs.SET_ENTRY, "首页");
            return "已重置全部本地配置（不含已写入的系统状态，请用「恢复默认」回滚）";
        } catch (Throwable t) { return "重置失败：" + t.getMessage(); }
    }

    public static String cleanTmp() {
        ShellExec.R r = ShellExec.su("rm -f /data/local/tmp/fuckorigin_* 2>/dev/null; ls /data/local/tmp | grep -c fuckorigin");
        return r.ok() ? "已清理临时文件（残留 " + r.out.trim() + " 个）" : "清理失败：" + r.out.trim();
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n <= 0) break;
            off += n;
        }
        in.close();
        return buf;
    }
}
