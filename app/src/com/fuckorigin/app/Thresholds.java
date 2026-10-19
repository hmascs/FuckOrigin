package com.fuckorigin.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 gamecube 的本地阈值表（只读），用于展示"每个游戏的过热/降温阈值"。 */
public final class Thresholds {

    private static final Pattern ITEM = Pattern.compile("<item\\s+([^>]+)/>");
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    public static String dump() {
        String xml = readAsRoot("/system/etc/gamecube/SuperResolutionConfigList.xml");
        if (xml == null) return "读取失败（需要 root）";
        StringBuilder sb = new StringBuilder();
        Matcher m = ITEM.matcher(xml);
        int n = 0;
        while (m.find() && n < 30) {
            String attrs = m.group(1);
            String pkg = null, heat = null, cool = null, ultra = null, state = null;
            Matcher a = ATTR.matcher(attrs);
            while (a.find()) {
                String k = a.group(1), v = a.group(2);
                if ("pkgName".equals(k)) pkg = v;
                else if (k.startsWith("overHeat")) heat = v;
                else if (k.startsWith("coolDown")) cool = v;
                else if ("ultraResolution".equals(k)) ultra = v;
                else if ("state".equals(k)) state = v;
            }
            if (pkg == null) continue;
            sb.append(pkg).append('\n')
              .append("    过热=").append(heat == null ? "-" : heat + "℃")
              .append("  恢复=").append(cool == null ? "-" : cool + "℃")
              .append("  超分=").append(ultra == null ? "-" : ultra)
              .append("  state=").append(state == null ? "-" : state).append('\n');
            n++;
        }
        if (sb.length() == 0) return "未解析到条目";
        return "来源：/system/etc/gamecube/SuperResolutionConfigList.xml\n\n" + sb;
    }

    private static String readAsRoot(String path) {
        ShellExec.R r = ShellExec.su("cat " + path);
        return r.ok() && r.out.length() > 0 ? r.out : null;
    }
}
