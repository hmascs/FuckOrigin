package com.fuckorigin.app;

import android.content.Context;

/** 目标锚点存在性检测：在 gamecube APK 的 dex 里查类描述符（结果缓存，避免反复扫 75MB）。 */
public final class Anchor {

    private static final String GC = "/system/app/GameCube/GameCube.apk";

    public static String check(Context c, Feature f) {
        if (f == null) return "";
        if (!Feature.KIND_HOOK.equals(f.kind) && !f.isGroup()) return "";
        String key = "anchor." + f.id;
        String cached = Prefs.getS(c, key, null);
        if (cached != null) return cached;
        String probe = probe(f);
        Prefs.setS(c, key, probe);
        return probe;
    }

    /** 返回 "存在 / 缺失 / 未知" + 依据 */
    private static String probe(Feature f) {
        String needle = descriptorOf(f.target);
        if (needle == null) return "未知（目标未写类名）";
        ShellExec.R r = ShellExec.su(
            "unzip -p " + GC + " 'classes*.dex' 2>/dev/null | strings -n 6 | grep -c '" + needle + "'");
        if (!r.ok()) return "未知（读取 APK 失败）";
        String n = r.out.trim().replaceAll("[^0-9]", "");
        if (n.length() == 0) return "未知";
        int count = Integer.parseInt(n);
        return count > 0 ? ("存在（dex 命中 " + count + " 次：" + needle + "）")
                         : ("缺失（dex 中找不到 " + needle + "）");
    }

    /** 从 target 字符串里猜类描述符：取第一个 com./android. 开头的段，去掉方法部分 */
    private static String descriptorOf(String target) {
        if (target == null) return null;
        int at = target.indexOf("com.");
        if (at < 0) at = target.indexOf("android.");
        if (at < 0) return null;
        String rest = target.substring(at);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '#' || ch == ' ' || ch == '（' || ch == '/' && i > 0 && rest.charAt(i - 1) == '/') {
                cut = i; break;
            }
        }
        String cls = rest.substring(0, cut).replace('.', '/');
        if (cls.length() < 8) return null;
        return "L" + cls + ";";
    }
}
