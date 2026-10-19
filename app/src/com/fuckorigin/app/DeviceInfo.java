package com.fuckorigin.app;

import android.text.TextUtils;

/** 设备信息读取：只在后台线程调用（内部走 su）。 */
public final class DeviceInfo {
    public static String line() {
        String vivo = "";
        try {
            ShellExec.R r = ShellExec.su("getprop ro.vivo.product.model");
            if (r.ok()) vivo = r.out.trim();
        } catch (Throwable ignored) { }
        boolean root;
        try { root = ShellExec.hasRoot(); } catch (Throwable t) { root = false; }
        return android.os.Build.MODEL + (TextUtils.isEmpty(vivo) ? "" : " · " + vivo)
            + " · Android " + android.os.Build.VERSION.RELEASE + " · " + (root ? "root ✓" : "无 root");
    }
}
