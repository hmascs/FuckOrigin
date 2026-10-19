package com.fuckorigin.app.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。作用域：com.vivo.gamecube（另可扩到 com.android.systemui / com.vivo.abe）。
 *
 * 依据（独立逆向，见 文档/FuckOrigin-OriginOS-技术方案-v1.0.md §6）：
 *   - 温度源     com.vivo.common.e#b()D  → 读 /sys/class/tshell/tshell_game
 *   - 过热判定   com.vivo.gameassistant.frameinterpolation.h#G0(D)Z
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String PKG = "com.vivo.gamecube";

    /** Q 芯全解锁：温控解除 或 解锁全部 任一开启都视为放行 */
    private static final String TAG = "FuckOrigin";
    private static final StringBuilder HOOKS = new StringBuilder();

    /** 是否强制开放 Q2 卡片（默认关闭：白名单走数据库，强开容易让 gamecube 查表 NPE 闪退） */
    private static boolean forceCards() {
        try {
            android.content.Context c = appCtx();
            if (c == null) return false;
            return android.provider.Settings.Secure.getInt(
                    c.getContentResolver(), "sys_perf_force_cards", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean unlockAll() {
        return on("f.thermal.bypass.global") || on("f.q2.unlock.all");
    }

    private static long cfgAt;
    private static int cfgQ2, cfgThermal, cfgMonster, cfgFps;

    private static android.content.Context appCtx() {
        if (sCtx != null) return sCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) return (android.content.Context) app;
            // 服务型进程可能还没有 Application，退到 system context
            Object th = at.getMethod("currentActivityThread").invoke(null);
            Object sys = at.getMethod("getSystemContext").invoke(th);
            if (sys instanceof android.content.Context) return (android.content.Context) sys;
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean cfgLogged;
    /** Application#onCreate 抓到后缓存在这里；目标进程内取 Context 只有这条路稳 */
    private static volatile android.content.Context sCtx;
    private static final ThreadLocal<Boolean> IN_FALLBACK = new ThreadLocal<Boolean>();

    private static void refreshCfg() {
        long now = System.currentTimeMillis();
        if (now - cfgAt < 2000L) return;
        cfgAt = now;
        android.content.Context c = appCtx();
        if (c == null) return;
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            cfgQ2 = android.provider.Settings.Secure.getInt(cr, "fuckorigin_q2", 0);
            cfgThermal = android.provider.Settings.Secure.getInt(cr, "fuckorigin_thermal", 0);
            cfgMonster = android.provider.Settings.Secure.getInt(cr, "fuckorigin_monster", 0);
            cfgFps = android.provider.Settings.Secure.getInt(cr, "fuckorigin_fps", 0);
            // 全局熔断：sys_perf_hooks=0 时所有 hook 一律走原逻辑
            if (android.provider.Settings.Secure.getInt(cr, "sys_perf_hooks", 1) == 0) {
                cfgQ2 = 0; cfgThermal = 0; cfgMonster = 0; cfgFps = 0;
            }
            if (!cfgLogged) {
                cfgLogged = true;
                android.util.Log.i(TAG, "CFG2|q2=" + cfgQ2 + " thermal=" + cfgThermal
                        + " monster=" + cfgMonster + " fps=" + cfgFps + " @" + android.os.Process.myPid());
            }
        } catch (Throwable ignored) { }
    }

    private static boolean on(String key) {
        refreshCfg();
        if (key == null) return false;
        switch (key) {
            case "f.thermal.bypass.global": return cfgThermal == 1;
            case "f.perf.monsterplus.force": return cfgMonster == 1;
            default: return cfgQ2 == 1;   // q2.unlock.all / 各 q2 子项
        }
    }

    /** 存活标记走 logcat：被注入进程多为 uid 1000，写不了 /data/local/tmp */
    private static void markAlive(String process) {
        try {
            android.util.Log.i(TAG, PKG + "|" + process + "|" + System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        String pkg = lp.packageName;
        if (pkg == null) return;
        if (!PKG.equals(pkg) && !"com.android.systemui".equals(pkg)) return;
        markAlive(lp.processName);
        install(lp);
    }

    private void install(final XC_LoadPackage.LoadPackageParam lp) {
        // ⓪ 先抓 Context：没有它读不到 Settings.Secure，所有开关都会是 false
        hookIfExists("android.app.Application", lp, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.thisObject instanceof android.content.Context) {
                    sCtx = (android.content.Context) p.thisObject;
                    PanelUi.UI_CTX = sCtx;
                    cfgAt = 0L;
                    refreshCfg();
                }
            }
        });

        // ① 温度源：b()D —— 只在 Q2 相关调用方生效，避免影响高温提示等安全逻辑
        hookIfExists("com.vivo.common.e", lp, "b", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                // 彻底去掉高温关闭：只要有任一解锁开关开启，温度源一律返回 35℃
                if (!unlockAll()) return;
                p.setResult(Double.valueOf(35.0d));
            }
        });

        // ② 过热判定：G0(D)Z
        hookIfExists("com.vivo.gameassistant.frameinterpolation.h", lp, "G0",
                new Class<?>[] { double.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!on("f.thermal.bypass.global") && !on("f.q2.mask.memc")) return;
                p.setResult(Boolean.FALSE);
            }
        });

        // ③ Monster+ 门控：Q 芯专区的超分/插帧要求"已连背夹 + 开 Monster+"
        hookIfExists("com.vivo.gameassistant.entity.MonsterPlusEvent", lp, "isMonster",
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!on("f.perf.monsterplus.force") && !on("f.thermal.bypass.global")) return;
                p.setResult(Boolean.TRUE);
            }
        });

        // ④ 超分 mIsMonsterPlus：A0/B0 是 static synthetic 访问器（首参是本类实例）
        final Class<?> jCls = findCls(lp, "com.vivo.gameassistant.superresolution.j");
        if (jCls != null) {
            hookOn(jCls, "A0", new Class<?>[] { jCls }, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (unlockAll()) p.setResult(Boolean.TRUE);
                }
            });
            hookOn(jCls, "B0", new Class<?>[] { jCls, boolean.class }, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (unlockAll()) p.args[1] = Boolean.TRUE;
                }
            });
            // ⑤ 超分命令入口：G1/H1 是实例方法，m0 是 static（实例在 args[0]）。
            //    G1 的守卫链实测为 b#f("vsr_controlled") → u==144 → a#l() → x(monster+)
            Class<?>[] srSig = new Class<?>[] {
                    android.content.Context.class, String.class, boolean.class, boolean.class };
            for (String mn : new String[] { "G1", "H1" })
                hookIfExists("com.vivo.gameassistant.superresolution.j", lp, mn, srSig, srBefore(false));
            hookOn(jCls, "m0", new Class<?>[] {
                    jCls, android.content.Context.class, String.class, boolean.class, boolean.class },
                    srBefore(true));
        }

        // ⑥ 冲突仲裁：超分/超帧都从 com.vivo.common.algorithem.b#f/g 取"是否被管控"
        hookIfExists("com.vivo.common.algorithem.b", lp, "f",
                new Class<?>[] { android.content.Context.class, String.class, String.class },
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (unlockAll()) p.setResult(Boolean.FALSE);   // false = 未被管控
            }
        });
        hookIfExists("com.vivo.common.algorithem.b", lp, "g",
                new Class<?>[] { android.content.Context.class, String.class, String.class },
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (unlockAll()) p.setResult(Boolean.FALSE);
            }
        });

        // ⑦ Monster+ 总闸：超分/超帧引擎与全部 Q 芯面板共 14 处调用点都读它
        hookIfExists("com.vivo.gameassistant.homegui.sideslide.panels.performance.modeshift.n", lp, "n",
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (unlockAll()) p.setResult(Boolean.TRUE);
            }
        });

        // ⑧ Q2 面板的"曾开启过 Monster+"标记：QChipContentView#j 用它决定是否展开完整内容
        hookIfExists("com.vivo.gameassistant.homegui.sideslide.panels.performance.modeshift.n", lp, "m",
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (unlockAll()) p.setResult(Boolean.TRUE);
            }
        });

        // ⑨ Q 芯功能总开关 a#l() 的唯一非空实现（p#l 读 a8.e#m()==1）
        hookIfExists("com.vivo.gameassistant.superresolution.p", lp, "l",
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (unlockAll()) p.setResult(Boolean.TRUE);
            }
        });

        // ⑩ 背夹连接态：只影响性能面板把超分/超帧显示成"不可用"，不参与引擎判定
        hookIfExists("com.vivo.gameassistant.homegui.sideslide.panels.performance.modeshift.a", lp, "a",
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (unlockAll()) p.setResult(Boolean.TRUE);
            }
        });

        // ⑪ 设备帧率上限：e0#v() 读 persist.vivo.phone.fps_max，锁帧开启时直接给最高档
        hookIfExists("com.vivo.gameassistant.utils.e0", lp, "v", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgFps == 1) p.setResult(Integer.valueOf(144));
            }
        });

        // ⑫ 配置注入：解析器入口改写 XML（运行期改 /system 对应用进程无效）
        hookIfExists("a8.a", lp, "b", new Class<?>[] { java.io.InputStream.class }, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!unlockAll()) return;
                final java.io.InputStream in = (java.io.InputStream) p.args[0];
                if (in == null) return;
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(65536);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    byte[] patched = patchSrXml(bo.toByteArray());
                    patched = patchFiXml(patched);
                    p.args[0] = new java.io.ByteArrayInputStream(patched);
                    android.util.Log.i(TAG, "CFGPATCH|注入超分配置 " + patched.length + " bytes");
                } catch (Throwable t) {
                    android.util.Log.i(TAG, "CFGPATCH|失败 " + t);
                }
            }
        });

        // ⑬ 插帧表注入：解析器是独立类 g7.a
        hookIfExists("g7.a", lp, "b", new Class<?>[] { java.io.InputStream.class }, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!unlockAll()) return;
                final java.io.InputStream in = (java.io.InputStream) p.args[0];
                if (in == null) return;
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(65536);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    byte[] patched = patchFiXml(bo.toByteArray());
                    p.args[0] = new java.io.ByteArrayInputStream(patched);
                } catch (Throwable t) {
                    android.util.Log.i(TAG, "CFGPATCH|插帧注入失败 " + t);
                }
            }
        });

        // ⑭ 温度源：TemperatureHelper#f()[D 被插帧(h.j#b1/l1)、超分、游戏视效共用
        hookIfExists("com.vivo.common.utils.TemperatureHelper", lp, "f", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 != 1 && cfgThermal != 1) return;
                Object r = p.getResult();
                int n = (r instanceof double[]) ? ((double[]) r).length : 4;
                if (n <= 0) n = 4;
                double[] v = new double[n];
                java.util.Arrays.fill(v, 35.0d);
                p.setResult(v);
            }
        });

        // ⑮ 游戏视效增强的过热判定
        hookIfExists("com.vivo.gameassistant.gamefilter.a", lp, "g0", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.FALSE);
            }
        });

        // ⑯ 插帧支撑：e9.a#e(pkg) 取不到就回退到和平精英那份 bean
        for (String impl : new String[] { "e9.d", "e9.h" })
        hookIfExists(impl, lp, "e", new Class<?>[] { String.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (!forceCards()) return;   // 默认不伪造 bean，避免构造插帧视图时崩
                if (cfgQ2 != 1 && cfgThermal != 1) return;
                if (p.getResult() != null) return;
                String pkg = String.valueOf(p.args[0]);
                String game = PanelUi.currentGame();
                // 只对当前前台游戏生效，否则会污染所有应用
                if (game == null || game.length() == 0 || !game.equals(pkg)) return;
                if (Boolean.TRUE.equals(IN_FALLBACK.get())) return;
                IN_FALLBACK.set(Boolean.TRUE);
                try {
                    Object bean = XposedHelpers.callMethod(p.thisObject, "e", "com.tencent.tmgp.pubgmhd");
                    if (bean != null) {
                        p.setResult(bean);
                        android.util.Log.i(TAG, "FI|回退用和平精英配置 " + p.args[0]);
                    }
                } catch (Throwable ignored) {
                } finally {
                    IN_FALLBACK.remove();
                }
            }
        });

        // ⑰ 插帧支撑判定
        hookIfExists("com.vivo.gameassistant.frameinterpolation.h", lp, "i1", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.TRUE);
            }
        });

        // ⑱ 补强：参考 SimpleHook 配置式方案补齐的能力查询点
        String[][] more = {
            { "com.vivo.common.a", "v0" }, { "com.vivo.common.a", "R0" },
            { "com.vivo.common.a", "u1" },
        };
        for (String[] t : more) {
            hookIfExists(t[0], lp, t[1], new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.TRUE);
                }
            });
        }
        hookIfExists("com.vivo.common.a", lp, "D1",
                new Class<?>[] { android.content.Context.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.TRUE);
            }
        });
        hookIfExists("com.vivo.common.utils.m0", lp, "f",
                new Class<?>[] { android.content.Context.class, String.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.TRUE);
            }
        });
        hookIfExists("com.vivo.common.utils.w0", lp, "a",
                new Class<?>[] { android.content.Context.class, String.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.TRUE);
            }
        });
        // n#v(Z) 是 Monster+ 的 setter，强制入参为 true
        hookIfExists("com.vivo.gameassistant.homegui.sideslide.panels.performance.modeshift.n", lp, "v",
                new Class<?>[] { boolean.class }, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.args[0] = Boolean.TRUE;
            }
        });

        // ⑲ 性能面板注入：CPU/GPU 频率滑块 + 信仰模式
        hookIfExists("com.vivo.gameassistant.homegui.sideslide.panels.performance.common.panel.PerformancePanelPanelView",
                lp, "i", new Class<?>[] { android.content.Context.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                android.util.Log.i(TAG, "PANELHOOK|enter " + p.thisObject);
                if (!(p.thisObject instanceof android.view.ViewGroup)) {
                    android.util.Log.i(TAG, "PANELHOOK|不是 ViewGroup");
                    return;
                }
                try {
                    android.content.Context c = appCtx();
                    if (c == null) {
                        android.util.Log.i(TAG, "PANELHOOK|context 为空");
                        return;
                    }
                    int ui = android.provider.Settings.Secure.getInt(c.getContentResolver(),
                            "sys_perf_ui", 0);
                    android.util.Log.i(TAG, "PANELHOOK|sys_perf_ui=" + ui);
                    if (ui != 1) return;
                    PanelUi.attach((android.view.ViewGroup) p.thisObject);
                } catch (Throwable t) {
                    android.util.Log.i(TAG, "PANEL|注入失败 " + t);
                }
            }
        });

        // ⑳ 性能模式联动：切到性能/怪兽档自动下发配置并去温控
        final Class<?> modeType = findCls(lp, "com.vivo.gameassistant.homegui.sideslide.panels.performance.modeshift.ModeType");
        if (modeType != null) {
            hookIfExists("com.vivo.gameassistant.homegui.sideslide.panels.performance.modeshift.n",
                    lp, "w", new Class<?>[] { modeType }, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (cfgQ2 != 1 && cfgThermal != 1) return;
                    String name = String.valueOf(p.args[0]).toUpperCase();
                    boolean perf = name.contains("MONSTER") || name.contains("PERFORMANCE")
                            || name.contains("BOOST") || name.contains("TURBO");
                    android.content.Context c = appCtx();
                    if (c == null) return;
                    try {
                        String gp = perf ? PanelUi.currentGame() : "";
                        // 拿不到游戏包名时不开启，避免退出游戏后温控一直被冻结
                        boolean enable = perf && gp != null && gp.length() > 0;
                        android.provider.Settings.Secure.putInt(c.getContentResolver(),
                                "sys_perf_auto", enable ? 1 : 0);
                        if (enable) {
                            android.provider.Settings.Secure.putString(c.getContentResolver(),
                                    "sys_perf_auto_pkg", gp);
                        }
                        android.util.Log.i(TAG, "AUTO|模式=" + name + " -> auto=" + (perf ? 1 : 0));
                    } catch (Throwable t) {
                        android.util.Log.i(TAG, "AUTO|写失败 " + t);
                    }
                }
            });
        }

        // ㉑ 游戏退到后台：关闭调度并提示
        for (String cls : new String[] { "rb.a", "yb.b" }) {
            hookIfExists(cls, lp, "handleGameForeground", new Class<?>[] { boolean.class },
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object a = p.args[0];
                    if (!(a instanceof Boolean) || ((Boolean) a)) return;
                    android.content.Context c = appCtx();
                    if (c == null) return;
                    try {
                        android.content.ContentResolver cr = c.getContentResolver();
                        int f = android.provider.Settings.Secure.getInt(cr, "sys_perf_faith", 0);
                        int au = android.provider.Settings.Secure.getInt(cr, "sys_perf_auto", 0);
                        if (f == 0 && au == 0) return;
                        android.provider.Settings.Secure.putInt(cr, "sys_perf_faith", 0);
                        android.provider.Settings.Secure.putInt(cr, "sys_perf_auto", 0);
                        android.os.Handler h = new android.os.Handler(
                                android.os.Looper.getMainLooper());
                        h.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    android.widget.Toast.makeText(appCtx(),
                                            "游戏已退出，调度模块已关闭",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                } catch (Throwable ignored) { }
                            }
                        });
                        android.util.Log.i(TAG, "GAMEEXIT|已关闭调度");
                    } catch (Throwable ignored) { }
                }
            });
        }

        // ㉒ 超分支持判定：Y0(Context,String)Z 为 false 时直接判定不支持本游戏
        hookIfExists("com.vivo.gameassistant.utils.e0", lp, "Y0",
                new Class<?>[] { android.content.Context.class, String.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (!forceCards()) return;
                if (cfgQ2 != 1 && cfgThermal != 1) return;
                // 只对当前前台游戏放行，避免所有应用都被当成超分游戏
                String pkg = p.args.length > 1 ? String.valueOf(p.args[1]) : null;
                String game = PanelUi.currentGame();
                if (pkg == null || game == null || !game.equals(pkg)) return;
                p.setResult(Boolean.TRUE);
            }
        });

        // ㉓ 当前游戏状态：l#N(Context)String 读 current_game_package，
        // 取不到时用 root 侧写入的值兜底，避免服务认为"没有游戏"而不创建悬浮窗
        hookIfExists("com.vivo.common.utils.l", lp, "N",
                new Class<?>[] { android.content.Context.class }, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object r = p.getResult();
                if (r instanceof String && ((String) r).length() > 0) return;
                android.content.Context c = appCtx();
                if (c == null) return;
                try {
                    String cur = android.provider.Settings.System.getString(
                            c.getContentResolver(), "current_game_package");
                    if (cur == null || cur.length() == 0) return;
                    p.setResult(cur);
                    android.util.Log.i(TAG, "GAMEPKG|兜底返回 " + cur);
                } catch (Throwable ignored) { }
            }
        });

        // ㉔ Q2 面板卡片可见性：ua.c#a/b 是"本游戏是否支持插帧/超分"，
        //    j#j1 / h#F0 是引擎侧的总判定，四个都为真才出卡片。
        for (String mn : new String[] { "a", "b" }) {
            hookIfExists("ua.c", lp, mn,
                    new Class<?>[] { android.content.Context.class, String.class },
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    refreshCfg();
                    if (forceCards() && (cfgQ2 == 1 || cfgThermal == 1)) p.setResult(Boolean.TRUE);
                }
            });
        }
        hookIfExists("com.vivo.gameassistant.superresolution.j", lp, "j1", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.TRUE);
            }
        });
        hookIfExists("com.vivo.gameassistant.frameinterpolation.h", lp, "F0", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) p.setResult(Boolean.TRUE);
            }
        });

        // ㉕ 插帧命令：l0#b(Context,Z,I,I,I,I) 会写入 Settings.System gamecube_frame_interpolation
        //     形如 "0:-1:<pid>:0:0"，首位 0 表示未开启。这里记录真实参数并强制开启。
        hookIfExists("com.vivo.common.utils.l0", lp, "b",
                new Class<?>[] { android.content.Context.class, boolean.class,
                        int.class, int.class, int.class, int.class }, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                android.util.Log.i(TAG, "FI命令|on=" + p.args[1] + " a=" + p.args[2]
                        + " b=" + p.args[3] + " c=" + p.args[4] + " d=" + p.args[5]);
                refreshCfg();
                if (cfgQ2 == 1 || cfgThermal == 1) {
                    // gameIndex 未知时用 3（与配置表里补的条目一致），depress 48 / target 144
                    if (!Boolean.TRUE.equals(p.args[1])) {
                        p.args[1] = Boolean.TRUE;
                        if (((Integer) p.args[2]).intValue() < 0) p.args[2] = Integer.valueOf(3);
                        if (((Integer) p.args[4]).intValue() <= 0) p.args[4] = Integer.valueOf(48);
                        if (((Integer) p.args[5]).intValue() <= 0) p.args[5] = Integer.valueOf(144);
                        android.util.Log.i(TAG, "FI命令|改写为开启 " + p.args[2] + ":"
                                + p.args[3] + ":" + p.args[4] + ":" + p.args[5]);
                    }
                }
                // 暂不强改参数：a=-1 说明 gamecube 自己都算不出目标帧率，
                // 强行把 on 置 true 可能让显示驱动收到非法组合。先只记录真实取值。
            }
        });

        flushHooks(lp.processName);
    }


    /** 配置XML注入：应用进程看不到我们改过的 /system 文件（独立挂载命名空间），
     *  所以在解析器入口改输入流。规则与 rev/gamecube/patch_cfg.py 一致。 */
    private static byte[] patchSrXml(byte[] raw) {
        try {
            String xml = new String(raw, "UTF-8");
            if (!xml.contains("frameDepress")) return raw;   // 不是超分表就原样返回
            StringBuilder out = new StringBuilder(xml.length() + 8192);
            int i = 0;
            while (true) {
                int s = xml.indexOf("<item ", i);
                if (s < 0) { out.append(xml, i, xml.length()); break; }
                int e = xml.indexOf("/>", s);
                if (e < 0) { out.append(xml, i, xml.length()); break; }
                e += 2;
                out.append(xml, i, s);
                String it = xml.substring(s, e);
                it = it.replace("state=\"1\"", "state=\"2\"");
                it = it.replace(" monsterPlusState=\"1\"", "");
                it = fillAttr(it, "frameDepress", "72");
                it = fillAttr(it, "frameInter", "144");
                it = fillAttr(it, "monsterFrameDepress", "72");
                it = fillAttr(it, "monsterFrameInter", "144");
                if (it.indexOf("ultraResolution=") < 0) {
                    it = it.substring(0, it.length() - 2) + " ultraResolution=\"2K\"/>";
                }
                out.append(it);
                i = e;
            }
            String res = out.toString();
            // 已安装但表里没有的游戏，动态补条目（终末地这类）
            java.util.HashSet<String> have = new java.util.HashSet<String>();
            int hp = 0;
            while ((hp = res.indexOf("pkgName=\"", hp)) >= 0) {
                int q = res.indexOf('"', hp + 9);
                if (q < 0) break;
                have.add(res.substring(hp + 9, q).toLowerCase());
                hp = q;
            }
            String extra = "";
            for (String rec : gamePackages(have).split("\u0002")) {
                if (rec.length() == 0) continue;
                int k = rec.indexOf('\u0001');
                if (k < 0) continue;
                extra += srItem(rec.substring(0, k), rec.substring(k + 1));
            }
            if (extra.length() > 0) {
                res = res.replace("</games>", extra + "</games>");
                android.util.Log.i(TAG, "CFGPATCH|超分表补装机游戏 " + (extra.split("<item ").length - 1) + " 条");
            }
            // 温度阈值抬到 99，绕开"设备降温后可生效"
            res = raiseTemperatures(res);
            int vs = res.indexOf("<version>");
            int ve = res.indexOf("</version>");
            if (vs > 0 && ve > vs) res = res.substring(0, vs) + "<version>99" + res.substring(ve);
            return res.getBytes("UTF-8");
        } catch (Throwable t) {
            return raw;
        }
    }

    private static String fillAttr(String it, String k, String v) {
        String empty = " " + k + "=\"\"";
        String any = " " + k + "=\"";
        if (it.indexOf(empty) >= 0) return it.replace(empty, " " + k + "=\"" + v + "\"");
        if (it.indexOf(any) < 0) {
            return it.substring(0, it.length() - 2) + " " + k + "=\"" + v + "\"/>";
        }
        return it;
    }



    /** 枚举"已安装且系统标记为游戏"的包，用于给配置表里没有的游戏补条目。 */
    private static String gamePackages(java.util.Set<String> skip) {
        StringBuilder sb = new StringBuilder();
        android.content.Context c = sCtx;
        if (c == null) return "";
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> apps =
                    pm.getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo ai : apps) {
                boolean isGame = ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME;
                boolean thirdParty = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
                if (!isGame && !thirdParty) continue;
                String pkg = ai.packageName;
                if (pkg == null || pkg.equals(c.getPackageName())) continue;
                if (skip != null && skip.contains(pkg.toLowerCase())) continue;
                String label = "";
                try { label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Throwable ignored) { }
                sb.append(pkg).append('\u0001').append(label).append('\u0002');
            }
        } catch (Throwable t) {
            android.util.Log.i(TAG, "CFGPATCH|枚举游戏失败 " + t);
        }
        return sb.toString();
    }

    private static String srItem(String pkg, String label) {
        return "    <item pkgName=\"" + pkg + "\" label=\"" + label
                + "\" channel=\"#\" state=\"2\" frameDepress=\"72\" frameInter=\"144\""
                + " monsterFrameDepress=\"72\" monsterFrameInter=\"144\""
                + " overHeatExtremeTemperature=\"99\" coolDownExtremeTemperature=\"99\""
                + " ultraResolution=\"2K\" index=\"0\"/>\n";
    }

    private static String fiGame(String pkg, String label) {
        return "    <game pkgName=\"" + pkg + "\" label=\"" + label
                + "\" channel=\"#\" index=\"0\" supportSDK=\"false\">\n"
                + "        <boostFrameValues>\n"
                + "            <item gameFps=\"90\" depressValue=\"72\" frameInterValue=\"144\"/>\n"
                + "        </boostFrameValues>\n"
                + "        <optiPowerValues>\n"
                + "            <item gameFps=\"120\" depressValue=\"60\" frameInterValue=\"120\"/>\n"
                + "        </optiPowerValues>\n"
                + "    </game>\n";
    }

    /** 插帧表注入：把"只在超分表、不在插帧表"的游戏补上（模板同和平精英）。 */
    private static byte[] patchFiXml(byte[] raw) {
        try {
            String xml = new String(raw, "UTF-8");
            if (!xml.contains("boostFrameValues")) return raw;
            String sr = readFile("/system/etc/gamecube/SuperResolutionConfigList.xml");
            if (sr == null) return raw;
            String add = "";
            int i = 0;
            java.util.HashSet<String> have = new java.util.HashSet<String>();
            int hp = 0;
            while ((hp = xml.indexOf("pkgName=\"", hp)) >= 0) {
                int q = xml.indexOf('"', hp + 9);
                if (q < 0) break;
                have.add(xml.substring(hp + 9, q).toLowerCase());
                hp = q;
            }
            while ((i = sr.indexOf("pkgName=\"", i)) >= 0) {
                int q = sr.indexOf('"', i + 9);
                if (q < 0) break;
                String pkg = sr.substring(i + 9, q);
                int li = sr.indexOf("label=\"", q);
                String label = "";
                if (li > 0) {
                    int lq = sr.indexOf('"', li + 7);
                    if (lq > 0) label = sr.substring(li + 7, lq);
                }
                i = q;
                if (have.contains(pkg.toLowerCase())) continue;
                add += "    <game pkgName=\"" + pkg + "\" label=\"" + label
                        + "\" channel=\"vivo\" index=\"0\" supportSDK=\"false\">\n"
                        + "        <boostFrameValues>\n"
                        + "            <item gameFps=\"90\" depressValue=\"72\" frameInterValue=\"144\"/>\n"
                        + "        </boostFrameValues>\n"
                        + "        <optiPowerValues>\n"
                        + "            <item gameFps=\"120\" depressValue=\"60\" frameInterValue=\"120\"/>\n"
                        + "        </optiPowerValues>\n"
                        + "    </game>\n";
            }
            for (String rec : gamePackages(have).split("\u0002")) {
                if (rec.length() == 0) continue;
                int k = rec.indexOf('\u0001');
                if (k < 0) continue;
                add += fiGame(rec.substring(0, k), rec.substring(k + 1));
            }
            if (add.length() == 0) return raw;
            String res = xml.replace("</games>", add + "</games>");
            int vs = res.indexOf("<version>");
            int ve = res.indexOf("</version>");
            if (vs > 0 && ve > vs) res = res.substring(0, vs) + "<version>98" + res.substring(ve);
            android.util.Log.i(TAG, "CFGPATCH|插帧表补 " + (add.split("<game ").length - 1) + " 条");
            return res.getBytes("UTF-8");
        } catch (Throwable t) {
            return raw;
        }
    }


    /** 把配置里各档温度阈值数值抬到 99，绕开降温提示 */
    private static String raiseTemperatures(String xml) {
        String[] keys = { "overHeatExtremeTemperature", "coolDownExtremeTemperature",
                "overHeatTemperature", "coolDownTemperature" };
        for (String k : keys) {
            int i = 0;
            while (true) {
                int a = xml.indexOf(k + "=\"", i);
                if (a < 0) break;
                int v = a + k.length() + 2;
                int q = xml.indexOf('"', v);
                if (q < 0) break;
                String num = xml.substring(v, q);
                try {
                    if (Double.parseDouble(num) < 99) {
                        xml = xml.substring(0, v) + "99" + xml.substring(q);
                    }
                } catch (Throwable ignored) { }
                i = v;
            }
        }
        return xml;
    }

    private static String readFile(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(65536);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** ④⑤ 用：把 j 实例上的 x(monster+) 与 u(刷新率) 强推到达标值 */
    private static XC_MethodHook srBefore(final boolean selfInArgs) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!unlockAll()) return;
                Object inst = selfInArgs ? p.args[0] : p.thisObject;
                if (inst == null) return;
                try {
                    XposedHelpers.setBooleanField(inst, "x", true);
                    XposedHelpers.setIntField(inst, "u", 144);
                } catch (Throwable ignored) { }
            }
        };
    }

    /** 只对 Q2 七个控制器的调用方放行（栈判定） */
    private static String callerQ2() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 0; i < st.length && i < 12; i++) {
            String cn = st[i].getClassName();
            if (cn == null) continue;
            if (cn.startsWith("com.vivo.gameassistant.frameinterpolation")
                || cn.startsWith("com.vivo.gameassistant.superresolution")
                || cn.startsWith("com.vivo.gameassistant.displayenhance")
                || cn.startsWith("com.vivo.gameassistant.gamefilter")
                || cn.startsWith("com.vivo.gameassistant.coollight")
                || cn.startsWith("com.vivo.gameassistant.recorder.deathreplay")
                || cn.equals("com.vivo.common.utils.TemperatureHelper")) {
                return cn;
            }
        }
        return null;
    }

    private static Class<?> findCls(XC_LoadPackage.LoadPackageParam lp, String name) {
        try { return XposedHelpers.findClass(name, lp.classLoader); } catch (Throwable t) { return null; }
    }

    private void hookIfExists(String cls, XC_LoadPackage.LoadPackageParam lp, String method,
                              XC_MethodHook cb) {
        hookIfExists(cls, lp, method, null, cb);
    }

    private void hookIfExists(String cls, XC_LoadPackage.LoadPackageParam lp, String method,
                              Class<?>[] argTypes, XC_MethodHook cb) {
        Class<?> c = findCls(lp, cls);
        if (c == null) { note("MISS " + shortName(cls) + "#" + method + " (class)"); return; }
        hookOn(c, method, argTypes, cb);
    }

    private void hookOn(Class<?> c, String method, Class<?>[] argTypes, XC_MethodHook cb) {
        try {
            if (argTypes == null || argTypes.length == 0) {
                XposedHelpers.findAndHookMethod(c, method, cb);
            } else {
                Object[] params = new Object[argTypes.length + 1];
                System.arraycopy(argTypes, 0, params, 0, argTypes.length);
                params[argTypes.length] = cb;
                XposedHelpers.findAndHookMethod(c, method, params);
            }
            note("OK   " + shortName(c.getName()) + "#" + method);
        } catch (Throwable t) {
            note("MISS " + shortName(c.getName()) + "#" + method + " : " + t);
        }
    }

    private static String shortName(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    /** 逐条记录绑定结果，App 侧从 logcat 读回，重启后可核对哪条 hook 没挂上 */
    private static void note(String line) {
        HOOKS.append(line).append('\n');
    }

    private static void flushHooks(String process) {
        try {
            for (String ln : HOOKS.toString().split("\n")) {
                if (ln.length() > 0) android.util.Log.i(TAG, "H|" + process + "|" + ln);
            }
        } catch (Throwable ignored) { }
        HOOKS.setLength(0);
    }
}
