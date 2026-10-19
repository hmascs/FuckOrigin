package com.fuckorigin.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String SET_ICON = "settings.icon";          // 禁用 | 默认
    public static final String SET_LANG = "settings.lang";          // 简体中文 | English
    public static final String SET_ENTRY = "settings.entry";        // 首页 | 上次页面
    private static final String FILE = "fuckorigin";
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    public static boolean get(Context c, String k, boolean d) { return sp(c).getBoolean(k, d); }
    public static void set(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    public static String getS(Context c, String k, String d) { return sp(c).getString(k, d); }
    public static void setS(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }
}
