package com.fuckorigin.app;

import android.content.Context;

/**
 * 开关从 App 传到被注入进程的通道。
 * 不能用 XSharedPreferences：本机 App 的数据目录被重定向到 /data/misc/apexdata/...，
 * 被注入进程（uid 1000）按标准路径永远读不到，开关会一直是 false。
 * Settings.Secure 是 system/gamecube 这类进程天然可读的，且写入只需 root。
 */
public final class Bridge {
    public static final String K_Q2 = "fuckorigin_q2";
    public static final String K_THERMAL = "fuckorigin_thermal";
    public static final String K_MONSTER = "fuckorigin_monster";
    public static final String K_FPS = "fuckorigin_fps";

    private static long lastMs;

    private static boolean on(Context c, String id) {
        Feature f = Catalog.feature(id);
        return f != null && f.enabled(c);
    }

    /** 把当前开关状态投影到 Settings.Secure。所有开关变更都应调用它。 */
    public static void sync(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastMs < 300L) return;
        lastMs = now;

        final boolean thermal = on(c, "thermal.bypass.global");
        final boolean q2 = thermal || on(c, "q2.unlock.all") || on(c, "q2.mask.memc")
                || on(c, "q2.mask.sr") || on(c, "q2.mask.ve");
        final boolean monster = thermal || on(c, "perf.monsterplus.force");
        final boolean fps = on(c, "perf.fps.lock");

        new Thread(new Runnable() {
            @Override public void run() {
                String cmd = "settings put secure " + K_Q2 + " " + (q2 ? 1 : 0)
                        + "; settings put secure " + K_THERMAL + " " + (thermal ? 1 : 0)
                        + "; settings put secure " + K_MONSTER + " " + (monster ? 1 : 0)
                        + "; settings put secure " + K_FPS + " " + (fps ? 1 : 0);
                try { ShellExec.su(cmd); } catch (Throwable ignored) { }
                android.util.Log.i("FuckOrigin", "BRIDGE|q2=" + q2 + " thermal=" + thermal
                        + " monster=" + monster + " fps=" + fps);
            }
        }, "fuckorigin-bridge").start();
    }
}
