package com.fuckorigin.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 gamecube 的本地配置表（只读），用于展示「每个游戏的阈值/帧率档位」。
 *
 * 8 Elite 机器上存在 SuperResolutionConfigList.xml（含 overHeat/coolDown 阈值）；
 * 8 Gen 3 的 /system/etc/gamecube 下只有 FrameInterConfigList.xml（含帧率档位表）。
 * 两条路径都要能读，否则这页在 8 Gen 3 上永远是「读取失败」。
 */
public final class Thresholds {

    private static final Pattern ITEM = Pattern.compile("<item\\s+([^>]+)/>");
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern GAME = Pattern.compile("<game\\s+([^>]*?)(/>|>(.*?)</game>)", Pattern.DOTALL);

    public static String dump() {
        String cfg = Platform.gamecubeCfg();
        String xml = readAsRoot(cfg);
        if (xml == null) return "读取失败（需要 root）\n路径：" + cfg;
        String body = cfg.endsWith("FrameInterConfigList.xml") ? frameInter(xml) : superRes(xml);
        return "来源：" + cfg + "\n\n" + body;
    }

    /** SuperResolutionConfigList.xml：每个游戏一行，带过热/恢复温度 */
    private static String superRes(String xml) {
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
        return sb.length() == 0 ? "未解析到条目" : sb.toString();
    }

    /** FrameInterConfigList.xml：pkgName 在 <game> 标签上，帧率档位在子 <item> 上 */
    private static String frameInter(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher g = GAME.matcher(xml);
        int n = 0;
        while (g.find() && n < 30) {
            String attrs = g.group(1);
            String inner = g.group(3) == null ? "" : g.group(3);
            String pkg = null, label = null, sdk = null;
            Matcher a = ATTR.matcher(attrs);
            while (a.find()) {
                String k = a.group(1), v = a.group(2);
                if ("pkgName".equals(k)) pkg = v;
                else if ("label".equals(k)) label = v;
                else if ("supportSDK".equals(k)) sdk = v;
            }
            if (pkg == null) continue;
            sb.append(pkg).append(label == null ? "" : "（" + label + "）")
              .append("  sdk=").append(sdk == null ? "-" : sdk).append('\n');
            Matcher it = ITEM.matcher(inner);
            int k = 0;
            while (it.find() && k < 4) {
                String ia = it.group(1);
                String fps = null, dep = null, fi = null;
                Matcher b = ATTR.matcher(ia);
                while (b.find()) {
                    String key = b.group(1), v = b.group(2);
                    if ("gameFps".equals(key)) fps = v;
                    else if ("depressValue".equals(key)) dep = v;
                    else if ("frameInterValue".equals(key)) fi = v;
                }
                if (fps != null) {
                    sb.append("    ").append(fps).append("fps → 插帧 ").append(fi == null ? "-" : fi)
                      .append(" / 降帧 ").append(dep == null ? "-" : dep).append('\n');
                    k++;
                }
            }
            n++;
        }
        return sb.length() == 0 ? "未解析到条目" : sb.toString();
    }

    private static String readAsRoot(String path) {
        ShellExec.R r = ShellExec.su("cat " + path);
        return r.ok() && r.out.length() > 0 ? r.out : null;
    }
}
