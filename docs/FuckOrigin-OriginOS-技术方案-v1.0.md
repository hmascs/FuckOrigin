# FuckOrigin · OriginOS 性能/温控/云控一体化模块 —— 技术实现方案

> 版本 v1.0 ｜ 撰写日期 2026-10-19 ｜ 全部结论基于本人对 `V2463A / PD2463` 真机的只读实机取证
> 与 `com.vivo.gamecube 14.0.13.02_de`、`com.vivo.abe 5180100` 两个 APK 的静态反编译结果。

---

## 0. 证据基线与可信度标注

### 0.1 评估机（本方案所有 `✅` 结论的出处）

| 项 | 值 | 来源 |
|---|---|---|
| 机型 | iQOO Neo10 Pro+（`ro.product.model=V2463A`，`ro.vivo.product.model=PD2463`） | `getprop` ✅ |
| 系统 | Android 16 / OriginOS 6（`ro.vivo.os.version=16.0`），`PD2463D_A_16.1.20.2.W10` | `getprop` ✅ |
| SoC | 骁龙 8 Elite，8 核 2 cluster（policy0=cpu0-5 / policy6=cpu6-7），governor 全 `walt` | `perf-cloudctl-audit.md` ✅ |
| GPU | Adreno830v2 / `/sys/class/kgsl/kgsl-3d0`，`msm-adreno-tz` | 同上 ✅ |
| Root | KernelSU，`su` context `u:r:ksu:s0`，需用 `/system/bin/` 绝对路径（Termux 版 `id/ls` 在 root 环境链接失败） | 实测 ✅ |
| LSPosed | **未安装**（仅 `com.xposed.wetypehook` 一个疑似无关包） | `pm list packages` ✅ |
| 游戏魔盒 | `com.vivo.gamecube` v14.0.13.02_de，`/system/app/GameCube/GameCube.apk` 75,430,603 B，targetSdk 35 | `aapt dump badging` ✅ |
| 云控宿主 | `com.vivo.abe` versionCode 5180100（data 分区更新版）+ `/system/app/AppBehaviorEngine` 5170011，PERSISTENT | `dumpsys package` ✅ |
| 系统包数 | 系统 423 / 三方 136 | `pm list packages -s` ✅ |

### 0.2 关于"OriginOS 7"的说明

任务描述里提到"OriginOS 7 已整合亮度锁定、超级分辨率"。**评估机实际是 OriginOS 6**，
但反向结果显示这一整合已经存在：`performance_panel_layout`（`0x7f0d0226`）内已同时挂载
`PhoneStatusPanelLayout`（状态环）、`BrightnessView`（亮度锁定）、`RefreshRateView`（刷新率）、
`SamplingRateView`（采样率）、`NetSwitchView`、`ModeShiftBar`（模式条）——即任务描述的"性能面板"。
下文一律以本机 `OriginOS 6 / PD2463` 的真实资源与类名为准；换机型时按第 5 章的"锚点定位法"重定位。

### 0.3 可信度标注（全文通用）

| 标记 | 含义 |
|---|---|
| ✅ | **本机实测**：命令输出、反编译字符串/类名/资源 ID 三重对得上 |
| 🟡 | **强候选**：静态证据充分（类名、字符串、调用点齐全），但未在设备上跑通 |
| ⚠️ | **需实验**：方向明确，但关键分支/写权限/回写行为必须在真机验证 |
| 📄 | **类比推断**：借鉴小米 Joyose / HyperCeiler / CosaSpoof 的机制，OriginOS 上等价物未确认 |

---

## 模块一：OriginOS 系统软件自动探测与角色识别

### 1.1 分层工艺图

```
┌─ 探测层 ────────────────────────────────────────────────────────────┐
│ PackageManager(系统包枚举) · dumpsys package(组件/权限/进程)          │
│ /proc/<pid>/{cmdline,maps} · getprop(3270 条) · /sys/class/{thermal,  │
│ cpufreq,devfreq,kgsl} · /sys/class/tshell · pm path(系统 vs data 更新) │
└──────────────┬──────────────────────────────────────────────────────┘
               ▼
┌─ 决策层（角色分类器）────────────────────────────────────────────────┐
│ 特征向量抽取 → 五类角色打分 → ComponentProfile(JSON)                  │
│ 角色：云控拉取端 / 策略执行端 / 温控决策端 / 性能调度端 / 辅助监控端   │
│       + 本新增：游戏场景性能决策端 / Q2 功能门控端                     │
└──────────────┬──────────────────────────────────────────────────────┘
               ▼
┌─ 注入层 ─────────────┐  ┌─ 执行层 ──────────────┐  ┌─ 对抗层 ──────┐
│ LSPosed 作用域声明    │→ │ sysfs 写入 / setprop   │  │ 探测结果脱敏   │
│ Hook 点注册表         │  │ 模块内 SP 持久化        │  │ 延时触发 / 分级 │
└──────────────────────┘  └───────────────────────┘  └───────────────┘
```

### 1.2 探测入口（可直接落地的 6 条采集通道）

```java
// ① 系统包枚举：区分"出厂系统应用"与"data 分区热更新应用"（后者才是云控实际代码）
PackageManager pm = ctx.getPackageManager();
List<ApplicationInfo> apps = pm.getInstalledApplications(
        PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DISABLED_COMPONENTS);
for (ApplicationInfo ai : apps) {
    boolean updated = ai.sourceDir.startsWith("/data/app/");   // abe 就是这种
    // ai.flags & (FLAG_SYSTEM|FLAG_UPDATED_SYSTEM_APP|FLAG_PERSISTENT)
}

// ② 组件面：dumpsys package <pkg> 段落化解析（注意 head -40 会截断 Service/Provider 表）
//    sed -n '/^Service Resolver Table/,/^Registered ContentProviders/p'

// ③ 进程面：/proc/*/cmdline 全量扫描（app uid 下 ps -A 只看到自己 7 个进程，必须 root）
// ④ 属性面：getprop 全量（本机 3270 条，vivo/bbk 相关 888 条，persist.* 506 条）
// ⑤ 内核面：/sys/class/thermal/*（95 zone / 63 cooling_device / 285 trip_point）
//            /sys/devices/system/cpu/cpufreq/policy*、/sys/class/kgsl/kgsl-3d0
// ⑥ vivo 私有面：/sys/class/tshell/*（游戏/整机温度出口，见 6.1 实测）
```

### 1.3 角色分类规则（五类 + 两类新增）

判据不是"包名像不像"，而是**能力特征**。下表每行都能用一条命令验证：

| 角色 | 判定特征（≥2 条即归类） | 本机命中实例 ✅ |
|---|---|---|
| **云控拉取端** | ① 注册 `UnifiedConfigProvider`/`AbeConfigDataProvider` ② APK 内含 `romsp-unifyconfig.vivo.com.cn`/`vcode-*` 域名 ③ 持有 `com.vivo.abe.permission.unifiedconfig.provider` | `com.vivo.abe`（含 `UnifiedConfigService`/`UnifiedManager`/`ConfigSqliteHelper`）、`com.vivo.daemonService`（触发/代理：发 update_finish/quick_upgrade 广播） |
| **策略执行端** | ① `WRITE_SECURE_SETTINGS` ② 写 `/sys/devices/system/cpu/cpufreq/*`、`/sys/class/kgsl/*` ③ 持有 `msm_performance` 节点写权 | `thermal-engine`（init.svc 在跑）、`com.vivo.devicepower`、`com.vivo.abe`（vperf 侧） |
| **温控决策端** | ① 读 `/sys/class/thermal/*/temp`、`/sys/class/tshell/*` ② 回调 `ThermalStatus` / `PowerManager.OnThermalStatusChangedListener` | `com.vivo.gamecube`（`com/vivo/common/e`）、`android`/`system_server`、`com.vivo.abe` |
| **性能调度端** | ① 绑 `vivo.hardware.*` HIDL/AIDL ② `CPUBoost`/`vperf`/`walt` 参数读写 | `vendor.thermal-hal`、perfd 侧、`com.vivo.gamecube:daemon` |
| **辅助监控端** | ① PERSISTENT + 周期 netstats 上报 ② `vhs.wwstat.com`/`abeacdata*` 上报端点 | `com.vivo.abe`、`com.bbk.cloud`、`com.vivo.gamewatch` |
| **游戏场景性能决策端**（新增） | ① 包名 `com.vivo.gamecube` 且进程 `:daemon`/`:assistantui` ② 含 `homegui.sideslide.panels.performance.*` ③ 持有 `persist.vivo.gamecube.*` 读 | `com.vivo.gamecube` ✅ |
| **Q2 功能门控端**（新增） | ① 含 `frameinterpolation`/`superresolution`/`displayenhance` 控制器 ② 读取 `/sys/class/tshell/tshell_game` ③ 解析 `*ConfigList.xml` | `com.vivo.gamecube` ✅ |

### 1.4 OriginOS 特异性线索（重点扫描清单，全部本机实测存在）

```text
# ── vivo 私有温度出口（这是本方案的核心锚点，小米没有对应物）──────────
/sys/class/tshell/tshell_game        # 游戏场景温度，"46751,46751,46751"（三通道 m°C）
/sys/class/tshell/tshell_shell       # 整机场景温度，"46682,46682,46058"
/sys/class/tshell/tshell_ntc         # 传感器名表：BOARD_THERM,WIFI_PA_THERM,5GPA,CPU,...
/sys/class/tshell/tshell_scenes      # 场景号，当前 0
/sys/class/tshell/{tshell_batt,tshell_ntc_thmal_name,shelltemp_en,ntctemp_raw,...}

# ── 标准温控面 ─────────────────────────────────────────────────────────
/sys/class/thermal/thermal_zone{0..94}/{type,temp,trip_point_*_temp}
/sys/class/thermal/cooling_device{0..62}/{type,cur_state,max_state}
  ├ cpu-cluster0 state=9  → policy0 频率索引 15-9=6 = 1555200 kHz   ← 限频就发生在这里
  └ cpu-cluster1 state=13 → policy6 索引 15-13=2 = 1401600 kHz

# ── 频率/调度面 ────────────────────────────────────────────────────────
/sys/devices/system/cpu/cpufreq/policy{0,6}/{scaling_max_freq,scaling_min_freq,scaling_governor}
/sys/devices/system/cpu/cpu*/cpufreq/  （每核一份，值同 policy）
/sys/kernel/msm_performance/parameters/cpu_max_freq   （本机全 2147483647 = 空置）
/sys/class/kgsl/kgsl-3d0/{min_pwrlevel,max_pwrlevel,gpu_available_frequencies,clock_mhz}

# ── 厂商配置（阈值真源，第 6 章要用）──────────────────────────────────
/system/etc/gamecube/SuperResolutionConfigList.xml   # 31,434 B，version 12（阈值真源）
/system/etc/gamecube/FrameInterConfigList.xml        # 99,344 B，version 11（插帧参数表）
/oem/etc/gamecube/SuperResolutionConfigList.xml      # 代码里的备用路径（本机不存在）

# ── 属性前缀族（需整体监控其变化，而不是逐条猜）──────────────────────
persist.vivo.gamecube.*        persist.vivo.game_*        persist.vivo.phone.fps_*
persist.vivo.benchmark_thermal.enable(1)                  persist.vivo.unifiedconfig.sec
persist.sys.games.test.{overheat_threshold,current_temperature}   ← 厂商自带测试覆盖位
vendor.display.thermal.version                            sys.game.memc.sw.enable
```

### 1.5 探测结果数据结构：`ComponentProfile`

```jsonc
{
  "schema": "fuckorigin/profile@1",
  "capturedAt": "2026-10-19T13:20:00+08:00",
  "device": { "model": "V2463A", "vivoModel": "PD2463", "rom": "PD2463D_A_16.1.20.2.W10",
              "soc": "8 Elite", "clusters": 2, "governors": ["walt"],
              "thermalZones": 95, "coolingDevices": 63 },
  "components": [{
    "pkg": "com.vivo.gamecube",
    "apk": "/system/app/GameCube/GameCube.apk", "versionName": "14.0.13.02_de",
    "updatedSystemApp": false,
    "processes": [
      { "name": "com.vivo.gamecube",           "uid": "system", "role": "主 UI" },
      { "name": "com.vivo.gamecube:assistantui","uid": "system", "role": "侧边栏/悬浮窗宿主" },
      { "name": "com.vivo.gamecube:daemon",     "uid": "system", "role": "守护/策略常驻" }
    ],
    "roles": ["游戏场景性能决策端", "Q2 功能门控端", "温控决策端"],
    "confidence": 0.95,
    "evidence": ["class:com/vivo/gameassistant/homegui/sideslide/**",
                 "str:/sys/class/tshell/tshell_game",
                 "str:persist.sys.games.test.overheat_threshold",
                 "provider:com.vivo.gamedaemon.provider.DaemonProvider"],
    "surfaces": {
      "activities": ["GameCubeMainActivity", "GameCubeSettings$*"],
      "services":   ["AssistantUIService", "GameCubeFuncService", "GameDaemonService"],
      "providers":  ["AppCommunicationProvider", "GameSettingsProvider", "DaemonProvider", "SpProvider"],
      "receivers":  ["BootCompleteReceiver", "AppAddOrRemoveReceiver"]
    },
    "hookPoints": [
      { "id": "gc.thermal.read",  "target": "com.vivo.common.e#b()D",              "risk": "low" },
      { "id": "gc.q2.overheat",   "target": "com.vivo.gameassistant.frameinterpolation.h#G0(D)Z", "risk": "low" }
    ],
    "risk": "medium",
    "reversible": true
  }],
  "sysfsWatchers": [ { "path": "/sys/class/tshell/tshell_game", "mode": "rw", "period": 1000 } ],
  "propWatchers":  [ "persist.sys.games.test.overheat_threshold" ]
}
```

打分函数（伪代码，规则引擎 + 加权，避免硬编码包名）：

```kotlin
fun classify(p: PkgFacts): List<Role> {
    val hits = mutableMapOf<Role, Double>()
    fun add(r: Role, w: Double, why: String) { hits[r] = (hits[r] ?: 0.0) + w; p.evidence += why }

    if (p.providers.any { it.endsWith("UnifiedConfigProvider") || it.endsWith("AbeConfigDataProvider") })
        add(CLOUD_PULL, 0.6, "provider:unifiedconfig")
    if (p.strings.any { it.contains("romsp-unifyconfig") || it.contains("vcode-api") })
        add(CLOUD_PULL, 0.4, "str:cloud-endpoint")

    if (p.reads("/sys/class/tshell/") || p.reads("/sys/class/thermal/"))
        add(THERMAL_DECIDER, 0.5, "sysfs:thermal")
    if (p.strings.any { it in setOf("android.util.FtDeviceInfo", "getBoardTempure") })
        add(THERMAL_DECIDER, 0.3, "api:board-temp")

    if (p.writes("/sys/devices/system/cpu/cpufreq") || p.perm("android.permission.WRITE_SECURE_SETTINGS"))
        add(SCHED_EXECUTOR, 0.4, "perm/sysfs:write")

    if (p.classes.any { it.startsWith("com/vivo/gameassistant/homegui/sideslide") })
        add(GAME_DECIDER, 0.6, "class:sideslide")
    if (p.classes.any { it.startsWith("com/vivo/gameassistant/frameinterpolation")
                     || it.startsWith("com/vivo/gameassistant/superresolution") })
        add(Q2_GATE, 0.6, "class:q2-controller")

    if (p.persistent && p.periodicReport) add(MONITOR, 0.3, "persistent+report")
    return hits.filter { it.value >= 0.5 }.keys.toList()
}
```

> **为什么要做特征化而不是维护包名表**：`com.vivo.abe` 本机就是 data 分区热更新
> （versionCode 5180100 > 系统内置 5170011），代码随时会变；包名表三个月就过期。

---

## 模块二：云控 Hook、对抗与"伪造下发"

### 2.0 先给结论（这条比技术细节更重要）

任务书假设 OriginOS 像 Joyose 一样"云控下发保守策略导致降频锁帧"。**本机证据不支持这个假设。**

- CPU/GPU 被压到 44%/40% 的直接写者是**温控冷却设备**（`cpu-cluster0 state=9` → policy0 频率索引 6），
  不是云控；链条是 `thermal-engine` ← `tz_shell/skin 49.5 °C` ← 120 W 充电。✅
- 游戏场景里 Q2 功能（插帧/超分/视效）的温度阈值来自**本地 XML**
  （`/system/etc/gamecube/SuperResolutionConfigList.xml`，PD2463 段 `overHeatExtremeTemperature="48"`），
  不来自云端。✅
- `com.vivo.abe` 的 unifiedconfig 确实存在完整云控链路，但它的消费面是**应用分类、能力开关、实验分组、上报**，
  游戏魔盒只通过 `content://com.vivo.abe.configlist.provider/appcategoryinfo` 取「游戏分类信息」。✅

**所以优先级应为：本地阈值 Hook（模块三/六） ≫ 云控对抗（本模块）。**
云控对抗仍然要做，但目的是「防止云控把开关/白名单关掉」，而不是「防止云控降频」。

### 2.1 分层工艺图

```
┌─ 探测层 ──────────────────────────────────────────────────────────────┐
│ 拉取端识别(abe/UnifiedConfigService) · 端点枚举(romsp-unifyconfig/vcode) │
│ 缓存文件指纹(unifiedconfig.db 的 WAL/mtime) · 消费方枚举(provider query) │
└────────────────┬──────────────────────────────────────────────────────┘
                 ▼
┌─ 决策层 ──────────────────────────────────────────────────────────────┐
│ 版本冻结 / 网络分流 / 读入口改写 / 回执伪造 —— 四档强度，按"可回滚"排序   │
└────────────────┬──────────────────────────────────────────────────────┘
                 ▼
┌─ Hook 层 ────────────────────────────┐ ┌─ 执行层 ──────────────────────┐
│ UnifiedConfigProvider#call/query     │ │ 模块内策略矩阵 + 事件总线      │
│ IUnifiedConfigServerInterface(AIDL)  │ │ sysfs/prop 执行器             │
│ 解密入口 getEnryptedStandardConfig   │ │ 快照/回滚(undo)               │
└──────────────────────────────────────┘ └──────────────────────────────┘
                 ▼
┌─ 对抗层 ──────────────────────────────────────────────────────────────┐
│ 检测面规避(maps/pm/属性) · 延迟触发 · 分级上报 · 作用域最小化            │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 链路定位方法论：四段拆解（Joyose 类比 → OriginOS 落地）

| 段 | 小米 Joyose 的做法（任务书给的先验） | OriginOS 上的定位手段 | 本机结果 ✅ |
|---|---|---|---|
| ① 拉取客户端 | Joyose 进程内 MCC 通道 | 枚举带 `PERSISTENT` + `com.vivo.abe.permission.*` 的系统应用；反编译找 `Unified/Config/Pull` 命名空间 | `com.vivo.abe` → `com/vivo/unifiedconfig/{UnifiedConfigService,UnifiedManager,UnifiedConfigProvider}` |
| ② 传输通道 | TLS → 小米云 | `tcpdump` + SNI 提取（无 root 也可用 `netstat`+`/proc/<pid>/fd` 定位 socket）；或 hook `okhttp3.RealCall`/`HttpURLConnection` | **abe APK 内**出现 `romsp-unifyconfig.vivo.com.cn`（romsp = ROM 服务平台的统一配置，`com/vivo/abnormalmonitor/b` 字符串）；`vcode-api/od/or.vivo.com.cn`、`abeacdataort-stsdk` 为上报/实验面（抓包实证） |
| ③ 本地缓存 | `/data/data/com.xiaomi.joyose/databases/teg_config.db`，表 `booster_config` | `find /data/data -name '*.db' -newer <marker>`；观察 `-wal` 大小变化；`inotify` 监控 | `/data/data/com.vivo.abe/databases/unifiedconfig.db`，**5 张表**（schema 见下） |
| ④ 策略消费端 | Joyose 内部读取 booster_config | 找 `ContentProvider` 的 caller：`grep -r "content://com.vivo.abe"` 全系统 APK；AIDL 接口反查 | `UnifiedConfigProvider.query()/call()`；`AbeConfigDataProvider`；AIDL `IUnifiedConfigServerInterface`；gamecube 侧 `content://com.vivo.abe.configlist.provider/appcategoryinfo` |

**`unifiedconfig.db` 真实 schema**（`ConfigSqliteHelper` 反编译所得，✅ 与 Joyose 的 `booster_config` 一一对应）：

```sql
-- 下发索引：一个 module 一行，version/url/dimension 决定"下发哪一份"
CREATE TABLE config_list(id INTEGER PRIMARY KEY AUTOINCREMENT, version text, module text,
    url text, key text, engine_version text, dimension text, identifier text, type text,
    system_ver text, rom_ver text, android_ver text, city text, province text);
-- 实际键值：加密下发解密后的落地表
CREATE TABLE standard_config(id INTEGER PRIMARY KEY AUTOINCREMENT, module_name text,
    item_name text, item_value text, engine_version text, file_id text);
-- ★回执与阅读计数：伪造"已应用"就改这张表
CREATE TABLE updated_modules(id INTEGER PRIMARY KEY AUTOINCREMENT, module text, identifier text,
    packagename text, read_time INTEGER, report_num INTEGER DEFAULT 0, file_id text, read_num INTEGER DEFAULT 0);
CREATE TABLE config_stat(period INTEGER, md5 text, min_period INTEGER);
CREATE TABLE event_data(_id INTEGER PRIMARY KEY AUTOINCREMENT, file_id text, type INTEGER,
    reason_type INTEGER, result INTEGER, event_time text);
```

**触发点**（决定"什么时候会重新拉"）：`BOOT_COMPLETED`（`util/BootCompleteReceiver`）、
`android.intent.action.TIME_SET`、alarm `com.vivo.abe.uconfig.timealarm`、
广播 `com.vivo.daemonService.unifiedconfig.update_finish_broadcast_UnifiedSwitch` 与
`com.vivo.daemonService.unifiedconfig.quick_upgrade_broadcast`。✅

**加密下发**：`UnifiedConfigProvider` 内字符串 `getEnryptedStandardConfig start` / `filecontent:` /
`item_name` / `item_value` —— 说明 `standard_config` 是**解密后**的中间产物，
真正的入参是加密 `filecontent`。这决定了 Hook 点选"解密前"还是"解密后"。

### 2.3 冻结与绕开：四档强度，从"最不破坏系统"往上排

| 档 | 手段 | OriginOS 对应物 | 强度/风险 | 备注 |
|---|---|---|---|---|
| D1 | **只断上报，不断下发** | iptables 限 `abeacdataort-stsdk`、`vhs.wwstat.com`、`vpush*`、`moni-*` | 低 | `vhs/cut_cloud.sh` 已实现双层（IP + SNI 字符串匹配）与 plan/apply/revert 流程；`vhs/cap/*.pcap` 是其抓到的真实端点 |
| D2 | **版本冻结**：让端侧认为"已是最新" | `config_list.version`、`standard_config.engine_version`、`updated_modules.report_num/read_time` 冻结；对应 Joyose 的 `pref_local_max_version` | 中 | 纯 DB 写；abe 会周期重算 → 需 15–60 s 心跳重写，或用 D3 的读侧 Hook 一劳永逸 |
| D3 | **读侧 Hook（推荐）** | Hook `UnifiedConfigProvider#call(String,String,Bundle)` / `query(...)`，按 module 白名单返回**冻结快照** | 中低 | 只影响 abe 进程（作用域 `com.vivo.abe`），不动 DB，最可回滚 |
| D4 | **断掉拉取通道** | 封 `romsp-unifyconfig.vivo.com.cn`；或 Hook `UnifiedManager` 的拉取方法直接 return | 中高 | 类比 Joyose 的 `persist.sys.sc_allow_conn` 冻结；⚠️ abe 同时承载隐私协议/跨设备等业务，粗断会波及其他功能 |

**如何发现 OriginOS 上等价于 `persist.sys.sc_allow_conn` 的关键开关**（方法，不是猜）：

```kotlin
// 在 com.vivo.abe 进程内 hook SystemProperties.get / getBoolean / getInt，
// 前 10 分钟把「所有被读到的 key + value + 调用栈前 3 帧」落盘，然后按读取频次排序。
XposedHelpers.findAndHookMethod("android.os.SystemProperties", lpparam.classLoader,
    "get", String.class, String.class, object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val k = p.args[0] as String
            if (k.startsWith("persist.vivo") || k.startsWith("persist.sys") || k.contains("config"))
                Probe.log(k, p.args[1] as? String, Throwable().stackTrace.take(3))
        }
    })
```

本机已确认存在、且**语义明确**的两个厂商测试覆盖位（先验证它们，比盲找开关高效得多）：

```text
persist.sys.games.test.overheat_threshold    # 游戏场景过热阈值覆盖（默认未设 = -1）
persist.sys.games.test.current_temperature   # 游戏场景当前温度覆盖（默认未设）
persist.vivo.unifiedconfig.sec               # unifiedconfig 安全/加密相关开关
persist.vivo.frameworkhook.support           # ★ 框架 Hook 支持位：gamecube 会读它（见 2.5）
```

### 2.4 伪造下发：三层，从"读入口"到"回执"到"机型借用"

**第一层：改读入口返回值（推荐，改动最小）**

```kotlin
// 作用域：com.vivo.abe
val P = "com.vivo.unifiedconfig.UnifiedConfigProvider"
XposedHelpers.findAndHookMethod(P, lpparam.classLoader,
    "call", String::class.java, String::class.java, Bundle::class.java,
    object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val (method, arg) = p.args[0] as String? to p.args[1] as String?
            if (method != "getEnryptedStandardConfig") return
            val module = arg ?: return
            val forged = PolicyStore.forge(module) ?: return      // 只改"性能相关 module"
            p.result = forged                                     // Bundle：已解密的 key/value
            Log.i(TAG, "forge unifiedconfig module=$module keys=${forged.keySet()}")
        }
    })
```

比 `query()` 更好用的原因：`query` 的返回是 `Cursor`，跨进程还要过 `MatrixCursor` 序列化；
`call()` 直接返回 `Bundle`，且 `module_name/item_name/item_value` 结构简单（字符串证据已确认）。

**第二层：伪造回执**

`updated_modules.report_num` 与 `event_data` 是端侧对云端的"已应用/已上报"凭据。
最省事的做法不是改 DB（abe 会重算），而是 Hook 上报前的组装函数，把
`result=0`（失败/未应用）改成 `result=1`，并把 `report_num` 固定为一个较大值。
⚠️ 具体方法名需在 `com.vivo.abe` 内进一步定位（`event_data` 的写入点），本方案给的是结构，
落地时用「在 `ConfigSqliteHelper` 的 `insert/update` 上打断点 + 读调用栈」三步定位。

**第三层：机型/维度借用（CosaSpoof 思路在 OriginOS 的变体）**

CosaSpoof 的实质是"用 hook 改机型代号，让云控按另一台机器的画像下发"。OriginOS 上这条路**有两条并行通道**：

| 通道 | 证据 | 借用效果 | 风险 |
|---|---|---|---|
| 云控维度字段 | `config_list` 含 `dimension/system_ver/rom_ver/android_ver/city/province` | 改变下发的 module 组合（可能是给更激进机型的配置） | 云端一侧有校验，且可能下发不兼容配置 |
| **端侧机型分支**（更实用） | `com/vivo/common/a;->I0` 含字符串 `PD2463`；`bc/r` 内含 `PD2196/PD2171/PD2136/PD2049`；`frameinterpolation.h;->M0` 含 `PD2183/PD2186`；`superresolution.j$q` 含 `PD2329` | 直接改 `SystemProperties.get("ro.vivo.product.model")` 的**进程内返回值**，让 gamecube 走另一分支（功能白名单/阈值） | 必须按进程 scope，不能全局改 `ro.*`（会破坏相机/OTA/指纹） |

```kotlin
// 只对 gamecube 进程生效的"机型伪装"，等价 CosaSpoof 但作用域更窄
XposedHelpers.findAndHookMethod("android.os.SystemProperties", lpparam.classLoader,
    "get", String::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            when (p.args[0] as String) {
                "ro.vivo.product.model" -> p.result = spoofModel      // 例："PD2196"
                "ro.vivo.os.version"    -> p.result = spoofOsVersion  // 例："15.0"
            }
        }
    })
```

⚠️ 借用前必须先验证"目标机型分支在本机硬件上是否真的更激进"——不同机型的阈值未必比 `PD2463` 宽
（本机 `PD2463` 的插帧阈值是 48 ℃，见 §6.1）。**正确做法是从 XML 里直接读出目标机型的阈值再决定借谁**，
而不是凭机型代号猜（第 6 章给了读法）。

### 2.5 Hook 隐藏与检测对抗（架构层预留，不做完整方案）

本机发现一条**必须正视的事实**：gamecube 会读 `persist.vivo.frameworkhook.support`
（`com/vivo/common/a;->H0`、`com/vivo/gameassistant/utils/e0;->g0` 两处）。当前值**为空**（未设置）✅。
该属性的确切语义未确认（可能是"支持框架增强"的开关位，也可能是 Hook 检测位），
但**代码路径存在**这一点足以说明：厂商具备"按属性值切换行为"的能力，对抗层必须预留。

对抗层设计（只做 4 件事，够用且不引战）：

```kotlin
// ① maps 检测：只在目标进程内过滤自己，不做全局伪造（全局伪造易被交叉校验）
// ② 属性面：把 persist.vivo.frameworkhook.support 伪造成 "false"（进程内）
// ③ 包面：隐藏模块自身包名（pm list packages / getInstalledPackages 过滤）
// ④ 时序：所有 Hook 延迟到"首次游戏的 onResume + 2 s"之后安装，避开启动期自检
object AntiDetect {
    fun installOn(lpparam: XposedHelpers, delayMs: Long = 2_000) {
        Handler(Looper.getMainLooper()).postDelayed({
            hideProp(lpparam, "persist.vivo.frameworkhook.support", "false")
            hidePackages(lpparam, setOf("com.fuckorigin.thermal"))
            filterProcMaps(lpparam)   // 仅本进程，且只隐藏 liblspd/libriru 字样
        }, delayMs)
    }
}
```

---

## 模块三：性能满血状态的维持与伪造

### 3.0 一个必须先纠正的前提：thermal_zone 序号跨重启不稳定

`FuckOrigin Thermal v1.0.3`（`/storage/emulated/0/FuckOrigin/thermal/`）的 `service.sh` 是：

```sh
sleep 10
echo "115000" > /sys/class/thermal/thermal_zone32/trip_point_0_temp   # 注释：GPU 温控墙
echo "99000"  > /sys/class/thermal/thermal_zone36/trip_point_0_temp   # 注释：CPU 温控墙
```

本机实测这两条**在本次启动后指错了对象**：

```
# 11:0x 那次快照（perf-cloudctl-audit.md）：z32=gpuss-0  z36=gpuss-4
# 13:2x 本次实测（同一台机、重启后）：
z26=gpuss-0 z28=gpuss-1 z30=gpuss-2 z32=gpuss-3 z34=gpuss-4 z35=gpuss-5
z36=pmih010x-ibat2-lvl0   ← 电池电流传感器，不是 CPU
z1=cpu-0-0-0 ... z13=cpuss-0-0 ... z26=gpuss-0
```

结论：**任何写死 `thermal_zoneN` 的实现都不可移植，甚至会在换机/重启后写到无关传感器上。**
正确做法永远是"按 `type` 反查索引"，见 3.2 的 `ZoneResolver`。参考模块标"iQOO 系列专用版"，推测就是按某一批机型的注册顺序试出来的（未与作者确认）。

### 3.1 分层工艺图

```
┌─ 探测层 ───────────────────────────────────────────────────────────────┐
│ zone type→index 反查 · cooling_device state↔cpufreq 索引换算 ·          │
│ tshell 温度读取 · ThermalStatus(dumpsys thermalservice) · 属性族扫描     │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ 决策层 ──────────────────────────────────────────────────────────────┐
│ 策略矩阵: {温控解除 ON/OFF} × {Q2 分项} × {充电态} × {游戏白名单}       │
│ 输出: 需要做什么(伪温度/阈值/trip/频率) + 风险闸门(温度>55℃ 自动降级)    │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ Hook 层（LSPosed，无需 root 写权）┐ ┌─ 执行层（root 守护，需写权）─────┐
│ com.vivo.common.e#b()D / c(Context) │ │ 按 type 解析后的 zone 写入      │
│ frameinterpolation.h#G0(D)Z         │ │ cpufreq policy*/governor       │
│ superresolution.j 阈值字段          │ │ kgsl min_pwrlevel / max_pwrlevel│
└─────────────────────────────────────┘ └────────────────────────────────┘
                ▼
┌─ 对抗层 ──────────────────────────────────────────────────────────────┐
│ 心跳重写(防 thermal-engine 回写) · 写后回读校验 · 单点失败自动回滚        │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 温控覆盖：三条路线的取舍（明确推荐顺序）

| 路线 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **L1 Hook 决策函数** | 让"读温度"和"判过热"两个点返回健康值 | 不影响真实散热管理；只影响 gamecube 的 Q2 门控；进程内存活，重启即恢复 | 只覆盖 gamecube 自己的判断，覆盖不到 `thermal-engine` 对 cpufreq 的压制 | **首选**，默认开启 |
| **L2 写 sysfs** | 抬高 `trip_point_*_temp`、把 `cooling_device*.cur_state` 压低、把 `scaling_max_freq` 顶满 | 能真正解除 CPU/GPU 降频（L1 做不到） | 需要 root 写权；被 thermal-engine 周期回写；写错 zone 有风险 | **次选**，开关控制，带心跳与回读校验 |
| **L3 屏蔽厂商温控配置** | 参照 FuckOrigin Thermal：把 `vendor/etc/thermal-engine.conf`、`vendor/etc/.tp/*`、`vendor/bin/thermal-engine*`、`init.thermal*.rc` 全部用 **0 字节文件**占位（实测该模块 15 个文件全是 0 字节） | 一劳永逸，整套温控引擎不启动 | **风险最高**：充电/电池/相机/壳体温度全失控；OTA 后签名/路径变化；出问题难定位 | **不默认开启**，仅在"我就是要极限跑分"时手动开，且要求强制降温提示 |

L1 的具体实现（真实签名，见第 6 章溯源）：

```kotlin
// ① 温度源：com.vivo.common.e.b()D —— 读 /sys/class/tshell/tshell_game，失败回落缓存 d:D
XposedHelpers.findAndHookMethod("com.vivo.common.e", lpparam.classLoader, "b",
    object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            if (!Policy.thermalBypass) return
            p.result = Policy.fakeTempC                    // 例：35.0
        }
    })

// ② 判定函数：com.vivo.gameassistant.frameinterpolation.h.G0(D)Z —— true 表示"过热要关插帧"
XposedHelpers.findAndHookMethod("com.vivo.gameassistant.frameinterpolation.h",
    lpparam.classLoader, "G0", Double::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            if (Policy.thermalBypass) p.result = false      // 永不判过热
        }
    })
```

L2 的安全写入器（关键：按 type 解析 + 回读校验 + 心跳）：

```kotlin
object ZoneResolver {
    /** type 名的优先级：越靠前越"应该"被改 */
    private val TARGETS = listOf("cpu-0-0-0", "cpuss-0-0", "gpuss-0", "gpuss-1", "tz_shell", "skin-therm")
    fun resolve(tag: String, root: File = File("/sys/class/thermal")): List<File> =
        root.listFiles().orEmpty()
            .filter { it.name.startsWith("thermal_zone") }
            .filter { File(it, "type").readTextOrNull()?.trim() == tag }
            .sortedBy { it.name }

    /** cooling_device 的 type→cur_state 同样要按 type 反查（索引跨启动不稳定） */
    fun cooling(tag: String, root: File = File("/sys/class/thermal")): List<File> =
        root.listFiles().orEmpty()
            .filter { it.name.startsWith("cooling_device") }
            .filter { File(it, "type").readTextOrNull()?.trim() == tag }
}

object ThermalWriter {
    fun raiseTrip(tag: String, tempMilliC: Int, verify: Boolean = true): Boolean {
        val zones = ZoneResolver.resolve(tag); if (zones.isEmpty()) return false
        var ok = 0
        for (z in zones) for (f in z.listFiles { it.name.startsWith("trip_point_") && it.name.endsWith("_temp") }
                                .orEmpty().sorted()) {
            if (f.writeSafely("$tempMilliC") && (!verify || f.readTextOrNull()?.trim() == "$tempMilliC")) ok++
        }
        return ok > 0
    }
}
```

> 写入权限实测（决定放在哪一层执行）：
> `trip_point_*_temp`、`cooling_device*/cur_state`、`kgsl/min_pwrlevel` 为 **root:root `rw-r--r--`**，`tshell_game` 为 **root:root `rw-rw-r--`**，
> 只有 `cpufreq/policy6/scaling_max_freq` 是 **system:system `rw-rw-r--`**。
> 因此：**LSPosed（进程内 system uid）只能改 cpufreq，其余必须由 root 侧守护脚本执行。**

### 3.3 防止被守护进程回写

本机的限频写者是 `thermal-engine`（`init.svc.thermal-engine=running`）。它的行为是**周期性重算**
cooling device 状态，而不是"一次性写"。所以：

1. **不要和它抢 sysfs**。`cur_state` 由它每周期重写，你写下去 1 秒内可能就被覆盖。
2. 想真正解除 CPU 限频，要么停它的服务（`stop thermal-engine`，⚠️ 需要 init/selinux 配合，
   重启后失效），要么**抢在它的下游**：直接写 `scaling_max_freq` 并高频回写（1 s 心跳）。
   原理上 `thermal-engine` 是通过 cooling device 间接压低 max_freq 的，直接写 max_freq 会覆盖它的结果——
   **但"它是否也会周期性改写 max_freq 本身"未实测**：落地时必须先做回读观测
   （写一次，然后每 5 s 回读，看值是否被拉回），再决定心跳周期。
3. 优先级：`L1 Hook ≫ 心跳写 scaling_max_freq（1 s） ≫ 改 trip_point（60 s） ≫ 屏蔽引擎（不推荐）`。

```kotlin
// root 守护：1 s 心跳，只在"温控解除 ON 且温度 < 55℃"时维持顶频
while (Policy.thermalBypass && Temp.readC() < 55.0) {
    for (p in listOf("policy0", "policy6")) {
        val base = "/sys/devices/system/cpu/cpufreq/$p"
        writeIfDiff("$base/scaling_max_freq", readFirstLine("$base/cpuinfo_max_freq"))   // 3532800 / 4320000
        writeIfDiff("$base/scaling_governor", "performance")
    }
    writeIfDiff("/sys/class/kgsl/kgsl-3d0/min_pwrlevel", "0")   // 锁最高档；写后读 clock_mhz 验证是否升到 1100
    // ⚠️ 语义需一次实测确认：本机 min_pwrlevel=13 / max_pwrlevel=0 / clock=525，
    //    写 0 后若 clock_mhz 升到 1100 即证明"min_pwrlevel 是性能下限"成立；否则改用 max_pwrlevel
    sleep(1000)
}
restoreBaseline()   // 退出循环即回滚，基准值来自模块启动时的快照
```

### 3.4 CPU/GPU 锁定的安全边界（这条决定了"会不会炸机"）

| 项 | 本机实测值 | 建议 | 理由 |
|---|---|---|---|
| CPU 硬件上限 | policy0 `3532800` kHz / policy6 `4320000` kHz | 可锁到上限 | 是 `cpuinfo_max_freq`，不是超频值 |
| governor 可选 | `walt conservative powersave performance schedutil`（**无 interactive**） | 用 `performance` | `walt` 是 CAF 调度器，锁 `performance` 最直接 |
| **不要做的** | 把 `scaling_min_freq` 也顶到上限 | ❌ 禁止 | 会锁死高频 → 温升失控 → 触发 `hot`/`critical` trip → 强制重启 |
| GPU | `kgsl-3d0`：**可用频率 14 档，硬件最高 `1100000000`（1.1 GHz）**；审计里读到的 `max_freq=443000000` 是**当时的限制值**，不是上限；当前 `clock_mhz=525` | 只改 `min_pwrlevel=0`（锁最高档），不碰 `max_pwrlevel` | 直接写 `max_gpuclk` 易与 devfreq 打架；`max_freq` 会被热限改写 |
| `msm_performance` | `cpu_max_freq` 全 `2147483647`（**空置**） | 不要动 | 说明厂商没走这条路锁频，动了反而制造冲突 |
| trip 档位（zone1/zone72 快照） | `passive 105/118/120/125`、`hot 125/135`、`critical 140` | 只抬 `passive` 档 | `hot`/`critical` 是保命线，抬了真的会烧 |
| 温度闸门 | `tz_shell` 45.6 ℃ 常态、充电时 49.5 ℃ | 模块内置 55 ℃ 自动降级 | 留出 2–3 ℃ 安全余量再触发原厂保护 |

### 3.5 "伪造下发"与"本地执行"的衔接

任务书担心"改过的策略在中间层被再次覆盖"。本机链路给出了明确答案：

```
云控(abe/unifiedconfig) ──✗ 不参与 ──┐
                                    ├─→ gamecube 的 Q2 门控（唯一入口: com.vivo.common.e#b()D 温度 + 本地 XML 阈值）
本地 XML(/system/etc/gamecube/*.xml) ─┘                │
                                                       ▼
                                        frameinterpolation.h / superresolution.j / displayenhance.c
                                                       │
                                                       ▼
                                          vendor HAL: vivo.hardware.game.memc（字符串证据）
```

也就是说：**只要把 L1 的两个 Hook 点（温度读 + 过热判定）钉死，篡改后的策略不会在中间层被覆盖**，
因为中间层根本不存在——gamecube 直接读内核节点、直接比阈值。
真正需要担心的"覆盖"只发生在 CPU 频率这一侧（thermal-engine 持续压制），已在 3.3 用"下游写 max_freq"处理。

### 3.6 回滚

- 所有 sysfs 写入前先 `readText()` 存快照到 `/data/adb/fuckorigin/baseline.json`；
- 模块卸载脚本（`uninstall.sh`）与"恢复默认"按钮执行同一套 `restoreBaseline()`；
- L1 Hook 本身无持久副作用，关闭开关即恢复（进程存活期间即时生效）；
- L3（屏蔽厂商配置）**必须提供一键还原**：模块目录里保留原始 15 个文件的备份副本。

---

## 模块四：系统应用遍历与功能增强可行性分析

### 4.1 分层工艺图

```
┌─ 探测层 ───────────────────────────────────────────────────────────────┐
│ 423 个系统包全量枚举 · sourceDir 判定(system/priv-app/data 更新) ·        │
│ 进程隔离(N 个进程) · 组件面(Activity/Service/Provider/Receiver) ·         │
│ APK 内类命名空间抽样(是否存在 com.vivo.xxx 可 Hook 入口)                  │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ 决策层（增强潜力评级）───────────────────────────────────────────────┐
│ 四维打分: 解锁潜力 / 可拦截点 / 稳定性风险 / 收益                        │
│ → 标签: 【推荐】【可做】【观察】【禁区】                                  │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ 注入层（HyperCeiler 式）────────────┐ ┌─ 执行层 ──────────────────────┐
│ Scope 声明(@AppScope) + Feature 注册表 │ │ 模块 App 内配置(Room) →        │
│ 每个功能一个 IXposedFeature 实现        │ │ 导出到 /data/adb/fuckorigin/   │
└───────────────────────────────────────┘ └───────────────────────────────┘
                ▼
┌─ 对抗层 ──────────────────────────────────────────────────────────────┐
│ 禁区进程(如 system_server)默认不 Hook · 单功能崩溃熔断 · 版本指纹校验    │
└──────────────────────────────────────────────────────────────────────┘
```

### 4.2 遍历机制（可直接实现）

```kotlin
data class AppFacts(
    val pkg: String, val versionName: String, val sourceDir: String,
    val updatedSystemApp: Boolean, val privileged: Boolean, val persistent: Boolean,
    val processes: List<String>, val services: List<String>, val providers: List<String>,
    val permissions: Set<String>, val hasVivoNamespace: Boolean, val uid: Int
)

fun collect(ctx: Context): List<AppFacts> {
    val pm = ctx.packageManager
    return pm.getInstalledApplications(MATCH_SYSTEM_ONLY or MATCH_DISABLED_COMPONENTS).map { ai ->
        val pkg = ai.packageName
        val pi = pm.getPackageInfo(pkg, GET_SERVICES or GET_PROVIDERS or GET_PERMISSIONS)
        AppFacts(
            pkg = pkg, versionName = pi.versionName.orEmpty(), sourceDir = ai.sourceDir,
            updatedSystemApp = ai.sourceDir.startsWith("/data/app/"),
            privileged = ai.sourceDir.contains("/priv-app/"),
            persistent = (ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0,
            processes = pi.activities?.mapNotNull { it.processName }?.distinct().orEmpty(),
            services  = pi.services?.map { it.name }.orEmpty(),
            providers = pi.providers?.map { it.name }.orEmpty(),
            permissions = pi.requestedPermissions?.toSet().orEmpty(),
            hasVivoNamespace = false,             // 由 APK 抽样步骤补齐（见下）
            uid = ai.uid
        )
    }
}
```

APK 命名空间抽样（判断"有没有值得 Hook 的入口"，避免全量反编译 400 个 APK）：

```bash
# 只读 dex 的字符串表，找典型入口命名；1 秒/包
unzip -p "$apk" 'classes*.dex' | strings -n 8 | grep -m1 -E '^Lcom/vivo/(gameassistant|unifiedconfig|abe|perf)' && echo HAS_ENTRY
```

### 4.3 增强可行性评级

| 维度 | 判据 | 权重 |
|---|---|---|
| **解锁潜力** | 包内是否存在"开关/白名单/机型分支"字符串（如 `PD2xxx`、`support_*`、`whitelist`） | 0.35 |
| **可拦截点** | 是否有明确的决策函数（返回 boolean/int/threshold）而非纯 UI | 0.30 |
| **稳定性风险** | 是否 PERSISTENT / uid=system / 承载通讯/支付/相机/OTA | −0.35 |
| **收益** | 对"性能满血 / 温控"目标的直接贡献 | 0.30 |

### 4.4 遍历结果（本机真实包名，按角色/风险分级）

| 应用名 | 包名 | 角色 | 增强类型 | 风险 |
|---|---|---|---|---|
| 游戏魔盒 | `com.vivo.gamecube` | 游戏场景性能决策端 + **Q2 功能门控端** | **侧边栏注入、Q2 温控旁路、阈值改写**【推荐】 | 中（`system` uid，`:assistantui`/`:daemon` 三进程） |
| AppBehaviorEngine | `com.vivo.abe` | **云控拉取端** + 策略执行端 | unifiedconfig 读入口改写、回执伪造、维度借用【可做】 | 高（PERSISTENT，承载隐私协议/跨设备/OTA 参数） |
| 系统服务 | `system_server`（`android`） | 温控决策端（ThermalService） | `ThermalStatus` 伪造【观察】 | 极高（崩溃=软重启）→ 默认禁区 |
| 系统 UI | `com.android.systemui` | 辅助监控端 | 状态栏温度/功耗显示【可做】 | 中 |
| 设置 | `com.android.settings` | 辅助监控端 | 开发者项/温度面板解锁【观察】 | 中低 |
| 相机 | `com.android.camera` / `com.vivo.alphacamera` | 温控敏感端 | HyperCeiler 式隐藏功能解锁【观察】 | 中（算法依赖真实温度） |
| 设备电源 | `com.vivo.devicepower` | 性能调度端 | 省电策略豁免、充电策略【观察】 | 高 |
| 超级省电 | `com.bbk.SuperPowerSave` | 策略执行端 | 关闭自动省电覆盖【可做】 | 中低 |
| AI/效果引擎 | `com.vivo.aiengine` / `com.vivo.effectengine` | 辅助监控端 | 游戏视效增强链路观察点 | 中 |
| 游戏服务 | `com.vivo.gamekit` / `com.vivo.gamespace` / `com.vivo.game` / `com.vivo.gamelib` | 游戏场景辅助 | 启动加速、白名单注入【观察】 | 中低 |
| 游戏训练/表 | `com.vivo.gametrain` / `com.vivo.gamewatch` | 辅助监控端 | 数据面 | 低 |
| 守护服务 | `com.vivo.daemonService` | 辅助监控端 | 云控触发链观察 | 高 |
| 云服务 | `com.bbk.cloud` | 辅助监控端（云备份） | 与本目标无关 | 中 |

> 说明：`system_server` 的 `ThermalService` 一定是最"根治"的 Hook 点，但它一旦崩整个系统重启，
> 且 LSPosed 对 `android` 进程的 Hook 与厂商加固冲突面最大。**本方案明确不做**，
> 改为在 gamecube 进程内做等效效果（第 6 章）——这是"效果相同、风险降一个数量级"的选择。

### 4.5 HyperCeiler 式架构映射（作用域与功能分离）

HyperCeiler 的关键不是"能 Hook"，而是**"作用域声明"与"功能实现"解耦**：用户勾选哪个 App，
LSPosed 就把模块加载进哪个进程，功能自己声明 `scope`。映射到 OriginOS 工具：

```kotlin
// ① 作用域声明：一个包一个 Scope，模块 App 与 Hook 侧共用同一份枚举
enum class Scope(val pkg: String) {
    GAMECUBE("com.vivo.gamecube"),
    ABE("com.vivo.abe"),
    SYSTEMUI("com.android.systemui"),
    SETTINGS("com.android.settings")
}

// ② 功能元数据：声明"我要进哪些进程 + 什么条件下启用"
@Retention(AnnotationRetention.RUNTIME)
annotation class FeatureMeta(
    val id: String,
    val name: String,
    val scope: Array<Scope>,
    val minVersion: String = "0",
    val risk: Risk = Risk.LOW,
    val defaultOn: Boolean = false
)

// ③ 功能实现：每个功能一个类，只关心"Hook 什么"，不关心"怎么被加载"
interface IXposedFeature {
    val meta: FeatureMeta
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, cfg: FeatureConfig)
}

// ④ 注册表：SPI 扫描 + 配置过滤（模块 App 写配置，Hook 侧只读）
object FeatureRegistry {
    private val all: List<IXposedFeature> by lazy {
        listOf(ThermalBypassFeature(), Q2GateFeature(), SidebarEntryFeature(), UnifiedConfigGuardFeature())
    }
    fun activeFor(pkg: String, cfg: ConfigStore): List<IXposedFeature> =
        all.filter { f ->
            f.meta.scope.any { it.pkg == pkg } && cfg.isEnabled(f.meta.id) && cfg.versionOk(f.meta.minVersion)
        }
}
```

与 HyperCeiler 的差异点（OriginOS 特有，必须额外做）：

1. **多进程作用域**：`com.vivo.gamecube` 有 3 个进程（主/`:assistantui`/`:daemon`）。
   `AssistantUIService`（`:assistantui`）里有 `initFrameInterSupportList:` 与 `VBoostSDKManager` 初始化，
   **说明 Q2 控制器大概率也活在 `:assistantui`**（⚠️ 未用 Hook 实测）。
   工程上不要赌：把 Hook 装进**所有 gamecube 进程**，每个进程各自做"类是否存在"探测，
   存在即安装——既覆盖了正确进程，也不会在错误进程里抛异常。
2. **版本指纹**：`14.0.13.02_de` 这类版本号每次 OTA 都变，`FeatureMeta.minVersion` 不够，
   要加"目标类/方法存在性探测"（找不到就静默跳过，而不是抛异常）。
3. **降级链**：Hook 点找不到 → 退到 sysfs 路线 → 再退到"仅显示不可用"，绝不空指针崩溃。

---

## 模块五：com.vivo.gamecube 逆向分析与侧边栏注入点定位

### 5.0 分层工艺图

```
┌─ 探测层 ───────────────────────────────────────────────────────────────┐
│ aapt badging(版本/权限/组件) · Manifest 三进程 · DEX 索引(19,382 类)      │
│ resources.arsc 全表索引(19,587 资源 ID) · 单文件 xmltree 结构还原        │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ 决策层（注入点定位）─────────────────────────────────────────────────┐
│ ① 侧边栏宿主 = WindowManager overlay(AssistantWindowManager)           │
│ ② 快捷开关区 = RecyclerView(BRVAH 单布局) → 数据源插入最省事            │
│ ③ 性能面板 = ViewBinding 组合视图(ViewGroup) → addView 插入            │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ 注入层 ─────────────────────────────┐ ┌─ 执行层 ──────────────────────┐
│ getItemCount()+1 / onBindViewHolder  │ │ 条目点击 → 详情页 → 策略开关    │
│ addView(自定义行)                    │ │ → 写模块 SP → 通知 Hook 层      │
└──────────────────────────────────────┘ └───────────────────────────────┘
                ▼
┌─ 对抗层 ──────────────────────────────────────────────────────────────┐
│ 只在 :assistantui 进程工作 · 视图存在性探测 · 异常吞掉不打断原 UI       │
└──────────────────────────────────────────────────────────────────────┘
```

### 5.1 APK 实况（全部本机实测）

| 项 | 值 |
|---|---|
| APK | `/system/app/GameCube/GameCube.apk`，75,430,603 B，md5 `8de805cbe4c55de428a7d57d34dde150` |
| 版本 | `14.0.13.02_de`，versionCode `1401302`，minSdk 29 / **targetSdk 35**，compileSdk 36 |
| 代码 | 3 个 dex（8.2 / 8.8 / 4.0 MB），**19,382 类 / 132,460 方法 / 69,376 条字符串常量** |
| 资源 | `resources.arsc` 10,763,940 B，**19,587 个资源 ID**，5,787 个资源文件 |
| 资源名 | **未混淆**（layout 673 个名字全部可读，1:1 对得上 zip 内 5,787 个文件）✅ |
| 资源路径 | **AndResGuard 式扁平改名**：`res/6YZ.xml` = `performance_panel_layout` |
| 类名 | **基本未混淆**：`com.vivo.gameassistant.*`、`com.vivo.gamedaemon.*` 全部可读 ✅ |
| 进程 | 主进程 + `:daemon`（守护/设置常驻）+ `:assistantui`（侧边栏 UI），三种均以 **system uid** 运行 ✅ |

> 这两条结论直接决定了整个项目的工作量：
> **资源名可读 → 可以按名字定位资源、按 ID 复用；类名可读 → 可以直接写出精确的 Hook 签名。**

进程与组件面（Manifest 解析）：

```
:assistantui 进程（manifest 实证）：AssistantUIService、GameCubeFuncService、
                   GameCubeGameEcosystemService、CloseLiveNotiService
:daemon 进程      （manifest 实证）：GameDaemonService、IntervalCollectService、UnBindTaskJobService、
                   LiveStateService、GamepadService、GameSettingsProvider、DaemonProvider、SpProvider
主进程            （manifest 实证）：GameCubeMainActivity；provider AppCommunicationProvider 未声明 process
```

### 5.2 侧边栏组件架构（逐层实证）

**① 宿主：`WindowManager` 悬浮窗，不是 Activity/Fragment** ✅

```
Lcom/vivo/gameassistant/b0;   ← 内部类名 AssistantWindowManager（字面量自证）
  ├─ e/g/h/i/j/k/l/m/o()   : 构造各种 WindowManager.LayoutParams
  ├─ f(View, LayoutParams) : addView
  ├─ y(View, LayoutParams) : addView（带绑定逻辑）
  ├─ a/b/c(View, Z)        : 显示/隐藏
  ├─ q(String) / r()       : 取绑定参数；★ r() 里 new EdgeSlideLayout
  └─ 字面量: "AssistantWindowManager", "addView view = ", "removeView",
            "PRIVATE_FLAG_TRUSTED_OVERLAY", "bindToProcessParams", "bindToApp"
```

关键语义：`PRIVATE_FLAG_TRUSTED_OVERLAY` 说明它不是普通 `TYPE_APPLICATION_OVERLAY`，
而是拿到了 system 进程的可信悬浮窗私有标志——这也是为什么它必须运行在 `system` uid 的 `:assistantui` 进程。
**它通过 `bindToApp` / `bindToProcessParams` 把窗口绑到前台游戏进程**，所以"与游戏进程的关联"是
"overlay 绑定到游戏 window token"，而不是"运行在游戏进程里"。

**② 视图树（资源 ID 与文件名均已核实）**

```
edge_game_mode_layout_new       0x7f0d008d  res/qOD.xml   ConstraintLayout
 ├─ EdgeDockView                                          ← 边缘停靠小球
 ├─ EdgeExpandPageView            (FrameLayout, getOverlay())  ← 展开页容器
 │    └─ game_cube_window_panel  0x7f0d00d3  res/iAi.xml
 └─ UniversalTipView                                      ← 提示层

side_slide_custom_layout        0x7f0d0267  res/ch3.xml(默认)/res/5zB.xml(port)
side_slide_first_remind         0x7f0d0268  res/--y.xml
side_slide_back_remind          0x7f0d0266  res/1CW.xml

EdgeSlideLayout  ← 手势滑出容器：onInterceptTouchEvent / onTouchEvent / setMode / setState
                  内部枚举 EdgeSlideLayout$EdgeMode / $EdgeState
NewEdgeGameModeView ← 业务壳：getDock() / getExpandPage() / getRect() / dispatchTouchEvent
                      接收 org.json.JSONObject 配置（R/J 方法），说明条目是配置驱动

performance_panel_layout        0x7f0d0226  res/6YZ.xml   ← 「性能面板」
 └─ com.vivo.gameassistant.view.CustomVFastScrollView  id 0x7f0a0717
     └─ ConstraintLayout  performance_content_cl   id 0x7f0a0715
         ├─ PhoneStatusPanelLayout  cpu_and_gpu_status_layout 0x7f0a01e6 （CPU/GPU 状态环）
         ├─ ModeShiftBar            mode_shift_bar_layout     0x7f0a0610 （模式条）
         ├─ View 分割线             0x7f0a08c3
         ├─ ViewStub  cooling_fan_view → layout_cooling_fan_stub 0x7f0a01dd
         ├─ BrightnessView          brightness_view          0x7f0a0128
         ├─ RefreshRateView         refresh_rate_test_view   0x7f0a07b0
         ├─ SamplingRateView        sampling_rate_view       0x7f0a0804
         ├─ NetSwitchView           network_switch_view      0x7f0a0665
         └─ Barrier                 0x7f0a0285
```

**③ 快捷开关区：真正的 ListView 化区域是它，不是性能面板** ✅

```
EdgeFunctionsAdapter
  extends BaseEdgeFunctionsAdapter  (构造签名: <init>(int layoutResId, List))
  extends com.chad.library.adapter.base.BaseQuickAdapter   ← BRVAH
  ├─ convert(BaseViewHolder, Object)          ← 最终绑定
  ├─ onBindViewHolder(BaseViewHolder, int)
  ├─ getItemCount() / getDefItemViewType(int) / getItemViewType(int)
  └─ 条目布局: edge_function_item_layout 0x7f0d008a res/LTA.xml
       ConstraintLayout(0x7f0a018a)
        └─ ConstraintLayout(0x7f0a018b)
            ├─ SwitchImageView(0x7f0a041a)   ← 开关图标本体
            ├─ ImageView(0x7f0a0472)         ← 角标/NEW
            └─ ImageView(0x7f0a0490)         ← 状态图标
```

**开关条目是枚举驱动的**（`QuickSwitchItemType.<clinit>` 实测字符串）：
`GAME_SUPER_RESOLUTION`、`HAWK_EYE`、`VISUAL_ENHANCEMENT`、`VISUAL_ENHANCEMENT_PAD`、
`LIGHT_TRACK`、`FRAME_INTERPOLATION`、`GAME_CUSTOM_SOUND`、`GESTURE_CONTROL` ……
每个枚举常量自带一个 handler 对象（`model/g`、`model/b`、`model/a`…），**单布局 + 动态图标文本**。

### 5.3 注入点判定（直接回答任务书的三个问题）

| 问题 | 结论（有证据） |
|---|---|
| 侧边栏是 Activity / Fragment / WindowManager 悬浮视图？ | **`WindowManager` 悬浮视图**，由 `AssistantWindowManager`(`com.vivo.gameassistant.b0`) 管理，运行在 `:assistantui` 进程，`bindToApp` 绑到游戏窗口 |
| 性能面板的布局与 Adapter？ | 布局 = `performance_panel_layout`(0x7f0d0226)；**性能面板本身不是 RecyclerView**，是 `PerformancePanelPanelView`（自定义 ViewGroup + ViewBinding `f6/e`）。真正 RecyclerView 化的是**快捷开关区**（`EdgeFunctionsAdapter`） |
| 注入开关放在哪？ | **两条腿走路**：① 快捷开关列表末尾插一条（RecyclerView 数据源）② 性能面板底部 `addView` 一行（更显眼）。推荐先做 ①，因为它天然随原生布局自适应，且改动面最小 |
| Hook 阶段选 onCreateViewHolder / getView / 数据绑定？ | **数据绑定阶段**（`onBindViewHolder` / `convert`）。理由：条目全是同一 layout，View 创建没问题，问题在"位置→数据"的映射；在绑定阶段拦可以完全复用原生 ViewHolder，不引入自定义 View |
| 是否需要新增枚举常量？ | **不需要，也不能**。enum 在 `<clinit>` 里静态初始化，LSPosed 无法追加常量（会破坏 `values()`/`ordinal()`）。正确做法是**不动数据表**：只把 `getItemCount()` 撑大 1，再在绑定阶段用"`position >= data.size()`"识别出那一格 |

**推荐方案 A：数据源末尾插入哨兵（RecyclerView 路线）**

```kotlin
// 作用域：com.vivo.gamecube，且只装进 :assistantui 进程
private val SENTINEL = Any()                 // 我们自己的哨兵，不依赖任何厂商类型
private const val TAG_TEMP_BYPASS = "fuckorigin_thermal_bypass"

XposedHelpers.findAndHookMethod(
    "com.vivo.gameassistant.homegui.sideslide.controlcenter.switches.view.EdgeFunctionsAdapter",
    lpparam.classLoader, "getItemCount", object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            p.result = (p.result as Int) + 1                   // 声明多一项
        }
    })

// ★ 必须挂三参版本：RecyclerView 调的是 onBindViewHolder(VH,int)，
//   而 BRVAH 内部再转调 onBindViewHolder(VH,int,List)，三参才是真正的绑定路径。
XposedHelpers.findAndHookMethod(
    "com.chad.library.adapter.base.BaseQuickAdapter", lpparam.classLoader,
    "onBindViewHolder", BaseViewHolder::class.java, Int::class.javaPrimitiveType, List::class.java,
    object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val pos  = p.args[1] as Int
            val data = p.args[2] as List<*>          // 真实数据表
            if (pos < data.size) return              // 原生条目：放行
            bindOurEntry(p.args[0] as BaseViewHolder) // 只有"越界的最后一位"是我们的哨兵位
            p.result = null                          // 吞掉原绑定逻辑
        }
    })
// 判定依据不是位置猜谜，而是"position >= data.size"——只有被 getItemCount()+1 撑出来的那一格成立。

private fun bindOurEntry(h: BaseViewHolder) {
    val ctx = h.itemView.context
    val iconId = ResourcesUtil.id(ctx, "drawable", "originui_vtoolbar_icon_settings_rom13_5")  // 详见模块七
    (h.getView<ImageView>(0x7f0a041a)).setImageResource(iconId ?: android.R.drawable.ic_menu_manage)
    h.getView<ImageView>(0x7f0a0472).visibility = if (Policy.thermalBypass) View.VISIBLE else View.GONE
    h.itemView.contentDescription = "温控解除"
    h.itemView.setOnClickListener { DetailRouter.open(ctx) }     // 打开模块详情页
}
```

**方案 B：性能面板底部 addView（ViewGroup 路线，作为增强）**

```kotlin
// PerformancePanelPanelView 是 ViewGroup，且内含 ConstraintLayout(0x7f0a0715) 作为内容容器
XposedHelpers.findAndHookMethod(
    "com.vivo.gameassistant.homegui.sideslide.panels.performance.common.panel.PerformancePanelPanelView",
    lpparam.classLoader, "onAttachedToWindow", object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val root = p.thisObject as ViewGroup
            if (root.findViewWithTag<View>(TAG_TEMP_BYPASS) != null) return   // 幂等
            val container = root.findViewById<ViewGroup>(0x7f0a0715) ?: root
            val row = Inflater.inflateGameCubeLayout(root.context, "originui_dialog_list_item_singleline_rom13_5")
            row.tag = TAG_TEMP_BYPASS
            container.addView(row)                          // 位置可用 index 控制
        }
    })
```

**方案 A 与 B 的取舍**

| | 方案 A（开关列表） | 方案 B（性能面板 addView） |
|---|---|---|
| 改动面 | 2 个 Hook（`getItemCount` + 三参 `onBindViewHolder`） | 1 个 Hook + 1 个 layout |
| 视觉一致性 | 天然一致（复用原生条目） | 需要自己排版，容易歪 |
| 位置显眼度 | 中（可能在列表末尾需滚动） | 高 |
| 列表刷新风险 | 低：计数由 `getItemCount()+1` 动态推导，`notifyDataSetChanged` 后仍然正确 | 无（但 `onAttachedToWindow` 可能被多次调用，需幂等） |
| 建议 | **MVP 做 A** | 迭代期补 B |

### 5.4 与系统性能服务的通信方式（决定能不能"绕过 UI 直接改状态"）

| 通道 | 证据 | 含义 |
|---|---|---|
| 同 APK 内 ContentProvider | `com.vivo.gamedaemon.gamesettings.GameSettingsProvider`、`com.vivo.gamedaemon.provider.DaemonProvider`、`SpProvider`、`com.vivo.gameassistant.CommunicationProvider` | 设置读写走 **Provider + SharedPreferences 代理**，不是 bindService 到厂商服务 |
| 对外 Provider | `content://com.vivo.gamecube.gamesettings.provider`、`content://com.vivo.gamecube.provider` | 第三方/系统模块可读写游戏设置 |
| SDK | `AssistantUIService` 内 `VBoostSDKManager`（`init registerFunctionPermission permissionCode=`） | 性能模式/加速能力走 vivo 自研 SDK |
| Q2 HAL | 字符串 `vivo.hardware.game.memc`、`sys.game.memc.sw.enable`、`pq_memc_sr_delay_off` | 插帧/超分最终由 HAL + sysfs 开关落地 |
| 系统云控 | `content://com.vivo.abe.configlist.provider/appcategoryinfo` | 游戏分类信息来自模块二的 abe 云控（**与降频无关**） |

### 5.5 换版本/换机型的"锚点定位法"（OTA 后必用）

```
1) 资源锚点：aapt/python 解析 resources.arsc → 找名字 performance_panel_layout / edge_function_item_layout
             → 拿到资源 ID（本机 0x7f0d0226 / 0x7f0d008a）
2) 代码锚点：在 dex 里搜该 ID 的 const 立即数（smali: const vX, 0x7f0d0226）→ 得到 inflate 调用点所在类
3) 行为锚点：搜日志字符串（"createViewIfNeed: New view created!!!"、"the item is null!!!"）
             → 直接定位 PerformancePanelManager / EdgeFunctionsAdapter
4) 进程锚点：用 ps 确认 :assistantui/:daemon/主进程都在；Hook 侧按"类存在性"逐进程决定装不装
```

---

## 模块六：Q2 芯片温控禁用机制分析与旁路设计

### 6.0 结论先行

Q2 功能（插帧 / 超分 / 视效增强 / 滤镜 / 极光灯效 / 死亡回放 / 光轨）的高温禁用**不是一条统一链路**，
而是**七个各自独立的温度判定**，它们共享同一个温度源和同一套阈值来源。这意味着：
**只要钉住"温度源"和"阈值"两个点，七个功能会同时解除**，不需要逐个 Hook。

链路（全部本机实证）：

```
/sys/class/tshell/tshell_game  ("46751,46751,46751" m°C, 实测 46.751 ℃)
        │  或 /sys/class/thermal/<zone>/temp（按 type 白名单匹配）
        ▼
com.vivo.common.e#b()D  /  #c(Context)I          ← 统一温度源
      b(): 读 tshell 节点 → 解析 → 缓存静态字段 d:D
      c(Context): 走缓存字段 b:I，并在首次读取时打点上报 10058_34
        │   可被属性 ±overriding: persist.sys.games.test.current_temperature
        ▼
各功能控制器:  h(G0/z0) · j(a1/R0/O0) · displayenhance.c/f · gamefilter.a · coollight.a · deathreplay.r · dc.d
        │   阈值: persist.sys.games.test.overheat_threshold > 0 时覆盖，
        │         否则回落 /system/etc/gamecube/*ConfigList.xml 的 overHeatTemperature
        ▼
判过热 → 关闭功能 + 上报事件（10058_32/34）+ 通知（HIGH_TEMPERATURE_NOTIFICATION）
```

### 6.1 七个受控功能的真实坐标

| 功能 | 控制器类 | 温度读 | 阈值字段/来源 | 关键日志（可直接验证） |
|---|---|---|---|---|
| **插帧 MEMC** | `com.vivo.gameassistant.frameinterpolation.h` | `n0()D`→属性/`e.b()`；`r0()D`→配置 | `f:D`，`z0()` 读属性否则 `r0()` | `FrameInterpolationController: mOverheatThreshold = `、`Overheat, try to close frame interpolation.` |
| **超分 SR** | `com.vivo.gameassistant.superresolution.j` | 同源（`M0/N0` 取温度，`R0/O0` 取阈值） | `g:D`（普通）+ `i:D`（极限），`a1()` 读属性否则 `R0()/O0()` | `GameSuperResolutionController: mOverheatThreshold = `、`mExtremeOverheatThreshold = ` |
| 超分观察者 | `com.vivo.gameassistant.superresolution.j$q` | 订阅 `onChange(Z)` | LiveData<Boolean> `isOverheat` | ` isOverheat = `、`temperature:` |
| **视效增强** | `com.vivo.gameassistant.displayenhance.c`（`f` 为另一实现） | `e.c(Context)` | 属性 + `mOverheatThreshold` | `Overheat, try to turn off DisplayEnhancement.`、`handleOn: Overheat, failed to turn on DisplayEnhance. temperature=` |
| 游戏滤镜 | `com.vivo.gameassistant.gamefilter.a` | `e.c` | 同上（`c0()`） | `Overheat, try to turn off DisplayEnhancement.` |
| 极光灯效 | `com.vivo.gameassistant.coollight.a` | `e.c` | — | `current temperature overHeat, stop game light!`、`isOverHeat!!!!` |
| 死亡回放 | `com.vivo.gameassistant.recorder.deathreplay.r` | `e.c` | `o0()` | `Check if isOverheat: temperature=`、`Overheat during running, force stop death replay.` |
| 光轨 | `dc.d` | `e.c` | `h0()` | `setLightTrack open return isOverheat`、`Overheat, try to turn off lightTrack.` |

判定函数真实签名（对 `frameinterpolation.h` 反汇编）：

```java
public final boolean G0(double temp) {   // true = 过热
    if (E0()) {                          // E0(): "no need to check temperature"（功能未开/非游戏态）
        Log.i("FrameInterpolationController", "isOverheat: no need to check temperature!");
        return false;
    }
    return temp >= this.f;               // f:D = mOverheatThreshold（边界严格性未逐字核对，见附录 D-3）
}
public final boolean B0(double temp) {   // true = 已充分降温（用于恢复）
    return temp < this.g;
}
```

阈值真源（本机 `/system/etc/gamecube/SuperResolutionConfigList.xml`，✅ 逐字读取）：

```xml
<!--PD2463配置文件-->
<version>12</version>
<item pkgName="com.miHoYo.Yuanshen" label="原神" state="2"
      frameDepress="30" frameInter="60"
      overHeatExtremeTemperature="48" coolDownExtremeTemperature="45"
      ultraResolution="2K" index="3"/>
<item pkgName="com.miHoYo.hkrpg" label="崩坏·星穹铁道" state="1"
      highFrameDepress="45" highFrameInter="90"
      overHeatTemperature="48" coolDownTemperature="45" index="29"/>
```

即：**默认 48 ℃ 关、45 ℃ 恢复**，按 `pkgName` 逐游戏配置，位于**只读的 /system 分区**。
`FrameInterConfigList.xml`（v11, 99,344 B）另有每个游戏的 `boostFrameValues/optiPowerValues` 插帧参数表。

### 6.2 三种旁路方案评估

| 方案 | 具体做法 | 在 OriginOS 上是否有效 | 风险 | 结论 |
|---|---|---|---|---|
| **A：Hook 温度/判定** | Hook `com.vivo.common.e#b()D` 返回伪温度；或 Hook 各控制器 `G0(D)Z` 返回 false | **有效且精准**：七个功能全部走这两个点（调用点已实证） | 低：进程内，重启即恢复 | ✅ **首选** |
| **B1：`cmd thermalservice override-status 0`** | 把系统 ThermalStatus 压到 NONE | **对 Q2 门控基本无效**：gamecube 直接读 `/sys/class/tshell` 与 `/sys/class/thermal`，不看 `ThermalService` | 低 | ⚠️ 只能解决"系统级高温提示/降亮度"这类走 ThermalStatus 的路径 |
| **B2：直接写温度节点** | root 写 `/sys/class/tshell/tshell_game`（`-rw-rw-r-- root:root`，可写） | **可能有效但不可靠**：该节点是否被驱动周期刷新**未实测**；若会刷新则写入被覆盖，且会同时骗过系统其它温控消费者 | 中：影响整机热管理判断 | ⚠️ 仅作为 A 失效时的降级手段，1 s 心跳重写 |
| **B3：抬高 trip_point** | 提高 `passive` 档 trip 温度 | 有效，但影响的是 CPU/GPU 降频那一层，不直接改变 Q2 门控（门控看 tshell 温度） | 中 | 与模块三共用 |
| **C：vendor 属性强开** | 找 `vendor.q2.*` 之类属性 | **本机未发现**：Q2 相关只有 `vivo.hardware.game.memc`（HAL）、`sys.game.memc.sw.enable`（memc 开关）、`pq_memc_sr_delay_off` | 高（要逆 HAL） | ❌ 不作为方案 |

**推荐组合**：A（主）+ B3（配合模块三解 CPU 降频）+ B1（仅在需要压系统高温提示时开）。

### 6.3 方案 A 的落地实现（带"精准 Hook"防误伤）

直接全局改 `e.b()` 有副作用：同一个温度源还被 `HIGH_TEMPERATURE_NOTIFICATION`（`c9/b`）、
`GAME_SUSPEND_EXIT_TEMPERATURE_EVENT`（`cb/c`）等**安全相关**逻辑使用。
所以必须按**调用方**放行，而不是一刀切：

```kotlin
private val Q2_CALLERS = setOf(
    "com.vivo.gameassistant.frameinterpolation.h",
    "com.vivo.gameassistant.superresolution.j",
    "com.vivo.gameassistant.displayenhance.c",
    "com.vivo.gameassistant.displayenhance.f",
    "com.vivo.gameassistant.gamefilter.a",
    "com.vivo.gameassistant.coollight.a",
    "com.vivo.gameassistant.recorder.deathreplay.r",
    "com.vivo.common.utils.TemperatureHelper"
)

fun hookTemperatureSource(lpparam: XC_LoadPackage.LoadPackageParam) {
    XposedHelpers.findAndHookMethod("com.vivo.common.e", lpparam.classLoader, "b",
        object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (!Policy.thermalBypass) return
                if (!Policy.isGameActive()) return                    // 只在实际游戏中生效
                val caller = Throwable().stackTrace
                    .firstOrNull { it.className.startsWith("com.vivo.") }?.className ?: return
                if (caller !in Q2_CALLERS) return                     // 安全相关调用方一律放行真实温度
                p.result = Policy.fakeTempC                            // 例 35.0
            }
        })

    // 双保险：即使温度源被别的路径绕过，判定函数也必须放行
    XposedHelpers.findAndHookMethod("com.vivo.gameassistant.frameinterpolation.h",
        lpparam.classLoader, "G0", java.lang.Double.TYPE, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (Policy.thermalBypass) p.result = false
            }
        })
}
```

阈值层（不想动温度时，直接抬阈值更稳，且不影响任何上报）：

```kotlin
// z0() 读属性 → 若属性 > 0 就用它；否则 r0() 读配置。
// 我们只在"配置读取后"改写字段，等于无条件抬阈值而不改属性、不改 XML（XML 在只读分区）
XposedHelpers.findAndHookMethod("com.vivo.gameassistant.frameinterpolation.h",
    lpparam.classLoader, "z0", object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            if (Policy.thermalBypass) XposedHelpers.setDoubleField(
                p.thisObject, "f", Policy.overheatThresholdC)        // 例 55.0
        }
    })
```

### 6.4 如何在不装机的情况下验证（上线前必做）

```bash
# ① 观察当前阈值是否被读出（原生就会打印，无需 Hook）
adb logcat -c && adb logcat -s FrameInterpolationController:V GameSuperResolutionController:V | grep -E "Threshold|Overheat"
# 期望: mOverheatThreshold = 48.0 / mExtremeOverheatThreshold = 48.0

# ② 用厂商自带测试覆盖位验证"链路是否吃属性"（只读验证，不改任何东西）
adb shell setprop persist.sys.games.test.overheat_threshold 55
# 重进游戏，看 logcat 是否变成 mOverheatThreshold = 55.0
#   - 若变了 → 属性通道有效，模块可直接用它（最省事，连 Hook 都省了）
#   - 若没变 → 说明该分支被 ROM 关闭，必须走 6.3 的字段改写
adb shell setprop persist.sys.games.test.overheat_threshold ""

# ③ 验证温度源：把 tshell_game 临时改成低值（root，重启即恢复）
su -c 'echo "30000,30000,30000" > /sys/class/tshell/tshell_game'
```

> ⚠️ 第 ②/③ 步会真实改变热保护行为，必须在**非充电、非高负载**状态下做，且做完立即恢复。

### 6.5 与模块三/模块八的联动

```
侧边栏"温控解除"总开关 (模块八)
        │  写入模块 SP: thermal_bypass=true, q2_mask=0b111
        ▼
策略矩阵 (模块三 决策层)
        ├─→ L1: hook e.b()/G0() 放行（本模块 6.3）
        ├─→ L2: 心跳顶 scaling_max_freq + kgsl min_pwrlevel（模块三 3.3）
        └─→ L3: （默认关）屏蔽 vendor 温控配置
        ▼
安全闸门: tz_shell > 55 ℃ 或 电池 > 45 ℃ → 自动降级回原生策略并提示
```

---

## 模块七：UI 资源原生逆向复用

### 7.1 资源实况（决定性结论）

| 项 | 实测 | 对复用策略的影响 |
|---|---|---|
| 资源**名** | **未混淆**（`performance_panel_layout`、`edge_function_item_layout`…） | 可以按名字定位，不必靠猜 |
| 资源**文件路径** | **AndResGuard 式扁平改名**（`res/6YZ.xml`、`res/LTA.xml`），无 `res/layout/` 目录 | 不能用"路径即类型"的直觉；必须先建 `filemap.tsv` |
| 资源 ID | 19,587 个，全部 `0x7f…` 前缀（单包 `0x7f`） | 在目标进程内可直接用 ID 常量引用，无需 `getIdentifier` |
| 类型分布 | layout 673 / drawable 3,134（4,409 文件）/ anim 176 / animator 107 / interpolator 22 | 面板动效与图标的可选池足够大 |
| 引用关系 | **面板 layout 里没有任何 anim 家族引用** | 动画是代码里 `setAnimation()` 触发的，复用要按"动画名+代码锚点"找 |

### 7.2 提取流水线（本机全部跑通）

```bash
# ① 只取资源（快，不需要 apktool）
mkdir -p rev/gc_res && cd rev/gc_res
unzip -o ../GameCube.apk 'res/*' 'resources.arsc'

# ② 建索引：名字 ↔ 垃圾路径 ↔ 资源 ID（本方案自带解析器）
python3 ../arsc.py index          # → arsc_index.tsv（19,587 行）、filemap.tsv（5,787 行）；参数以 `arsc.py --help` 为准

# ③ 按真实名重建可读目录（复用/比对时唯一舒服的方式）
python3 - <<'PY'
import csv, os, shutil
for r in csv.DictReader(open('filemap.tsv'), delimiter='\t'):
    if r['config'] != 'default':           # land/port 变体后面单独处理
        continue
    dst = f"named/{r['type_name']}/{r['entry_name']}{os.path.splitext(r['file_path'])[1]}"
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy(r['file_path'], dst)
PY
# 结果: named/layout/performance_panel_layout.xml, named/anim/pad_center_window_in.xml, ...

# ④ 看单个布局的结构（无需 apktool，实测可用）
aapt dump xmltree /system/app/GameCube/GameCube.apk res/6YZ.xml | head -60

# ⑤ 有 apktool 时（⚠️ 未在本 APK 验证）
#   apktool d GameCube.apk -r -f          # -r: 只解资源；因 arsc 名字完好，通常会按真实名落地
#   ⚠️ 若 apktool 按垃圾路径落地，则回到 ③ 的改名方案
```

### 7.3 三种复用方式与取舍

| 方式 | 代码 | 适用 | 风险 |
|---|---|---|---|
| **A. 进程内直接用 ID**（推荐） | `ResourcesUtil.inflate(holder.itemView.context, 0x7f0d008a)` | 我们的 Hook 就在 `com.vivo.gamecube` 进程里，资源随包可用 | OTA 可能改 ID → 必须做名字校验 |
| **B. 跨包引用** | `ctx.createPackageContext("com.vivo.gamecube", CONTEXT_IGNORE_SECURITY).resources` | 模块自有 App / 详情页里复用图标、颜色、动画 | 目标包被停用/卸载时降级；`getIdentifier` 有性能开销（缓存住） |
| **C. 自带 XML 副本** | 提取 XML 放自己 APK 的 `res/layout/` | 只想抄结构、不依赖目标包 | **属性会失效**：`app:*`（res-auto）属性、`@style/…`、`@id/…` 跨包引用全部要改；只建议用于"我完全自己控制"的行布局 |

**防 OTA 变号的硬校验**（必做，5 行）：

```kotlin
object RUtil {
    const val LAYOUT_EDGE_ITEM = 0x7f0d008a        // edge_function_item_layout
    fun verify(ctx: Context): Boolean = runCatching {
        ctx.resources.getResourceName(LAYOUT_EDGE_ITEM) ==
            "com.vivo.gamecube:layout/edge_function_item_layout"
    }.getOrDefault(false)
    // 校验失败 → 自动走 getIdentifier 按名字兜底，仍失败则禁用该功能（绝不崩）
    fun fallback(ctx: Context, type: String, name: String): Int =
        ctx.resources.getIdentifier(name, type, "com.vivo.gamecube")
}
```

### 7.4 动效与图标：可直接抄的清单（本机实测 ID）

| 用途 | 资源名 | ID | 文件 |
|---|---|---|---|
| 侧滑面板/弹窗居中呼出 | `pad_center_window_in` | `0x7f0100a1` | `res/l14.xml` |
| 侧滑面板退出 | `pad_center_window_out` | `0x7f0100a5` | `res/dMF.xml` |
| 面板进出插值器 | `pad_center_window_*_interpolator`（4 个，含 `..._out_scale_small_interpolator`） | `0x7f0100a2`–`a7` 区间（逐个以 `filemap.tsv` 为准） | 见 `filemap.tsv` |
| OriginOS 菜单窗口动效 | `vigour_menu_window_enter_2236` / `exit_2236` | `0x7f0100ab` / `0x7f0100ac` | `res/1tI.xml` / `res/Evi.xml` |
| 开关切换提示动效 | `hint_switch_enter_anim` / `hint_switch_out_anim` | `0x7f010032` / `0x7f010033` | `res/XBe.xml` / `res/I2_.xml` |
| 缓动曲线（进入） | `decelerate_cubic_enter` / `decelerate_quint_enter` | `0x7f0c0006` / `0x7f0c0008` | — |
| 缓动曲线（退出） | `decelerate_cubic_exit` / `decelerate_quint_exit` | `0x7f0c0007` / `0x7f0c0009` | — |
| Material3 标准缓动 | `m3_sys_motion_easing_emphasized(_accelerate/_decelerate)` | `0x7f0c000b`–`0x7f0c000d` | — |
| 设置类图标（用于"温控解除"条目） | `originui_vtoolbar_icon_settings_rom13_5` | `0x7f080abb` | `res/vyW.xml` |
| 更多/选项图标 | `originui_vtoolbar_icon_option_rom13_5` / `originui_vlinearmenu_icon_more_rom13_5` | `0x7f080ab0` / `0x7f080a42` | — |
| 开关底色（开/关/描边） | `quick_switch_bg_on_color` / `quick_switch_bg_off_color` / `quick_switch_stroke_off_color` | `0x7f06067d` / `0x7f06067c` / `0x7f06067e` | — |
| 开关底色 drawable | `shape_quick_switch_bg_on` | `0x7f080b23` | `res/t3u.xml` |

> 图标选取原则（符合任务书"不得使用外部素材"）：
> **优先 gamecube 内语义相近者 → 其次 `framework-res.apk` 的 `android.R.drawable.*` → 都没有才用自定义矢量**，
> 且自定义矢量的尺寸/描边/配色照抄所参照的那个 `originui_vtoolbar_icon_*`（直接量它的 viewport 与 strokeWidth，
> 不要凭经验规定 dp 值）。

**动画复用的硬约束**（踩坑点）：

1. `anim` 里若引用 `@dimen`/`@color`/`@interpolator`，**必须把这些 values 一并搬**，否则 inflate 时 `Resources$NotFoundException`。
2. `animator`（属性动画）里的 `pathData` 依赖 VectorDrawable 的 `name`（`$originui_vcheckbox_*` 这类命名），
   抄动画不抄路径数据会静默无效（不报错但不显示）。
3. 原生面板动画是代码触发（§7.1），所以复刻"侧边栏呼出/收起"时，
   应该用 `pad_center_window_in/out` 这一对，而不是去 layout 里找 `android:animation`。

### 7.5 复用清单（本方案自己要用到的资源，全部已验证存在）

```
layout/performance_panel_layout        0x7f0d0226  ← 性能面板（注入位置 B 的宿主）
layout/edge_function_item_layout       0x7f0d008a  ← 快捷开关条目（注入位置 A 的模板）
layout/side_slide_custom_layout        0x7f0d0267  ← 侧滑主布局（含 RecyclerView，推导见下）
layout/game_cube_window_panel          0x7f0d00d3  ← 展开页窗口
layout/layout_qchip_super_resolution   0x7f0d0157  ← 超分面板（详情页可参考其排版）
layout/layout_qchip_super_frame        0x7f0d0156  ← 插帧面板（内含 exoplayer PlayerView，预览是视频）
layout/expand_settings_view            0x7f0d0097  ← 设置类页面模板（SwitchTextView + 滚动）
anim/pad_center_window_in|out          0x7f0100a1 / 0x7f0100a5
interpolator/decelerate_quint_enter|exit 0x7f0c0008 / 0x7f0c0009
drawable/originui_vtoolbar_icon_settings_rom13_5  0x7f080abb
color/quick_switch_bg_on|off|stroke_off           0x7f06067d / 0x7f06067c / 0x7f06067e
```

---

## 模块八：侧边栏"温控解除"开关的集成实现

### 8.1 分层工艺图

```
┌─ 探测层 ───────────────────────────────────────────────────────────────┐
│ 侧边栏窗口是否已挂载 · 开关列表 adapter 是否就绪 · 资源 ID 名校验         │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ 决策层 ──────────────────────────────────────────────────────────────┐
│ 开关状态机: OFF ⟷ ON（含"预热中/失败降级"）· 分项掩码 q2_mask             │
│ 安全闸门: tz_shell>55℃ / 电池>45℃ / 充电中 → 自动 OFF 并提示             │
└───────────────┬───────────────────────────────────────────────────────┘
                ▼
┌─ 注入层 ─────────────────────────────┐ ┌─ 执行层 ──────────────────────┐
│ ① 开关列表末尾插条目（模块五 A）      │ │ XSharedPreferences → 策略      │
│ ② 详情页 Activity（模块自有）         │ │ root 守护读 config.json        │
└──────────────────────────────────────┘ └───────────────────────────────┘
                ▼
┌─ 对抗层 ──────────────────────────────────────────────────────────────┐
│ 条目文本/图标随状态变化 · 不写系统应用私有目录 · 失败静默降级             │
└──────────────────────────────────────────────────────────────────────┘
```

### 8.2 详情页：结构自建 + 素材全复用

任务要求"布局/图标/动画全部从系统应用提取"。这里的**工程折中**必须讲清楚：

- **结构**（谁在上面、几行、间距）→ 自建。原因：原生没有"温控解除"这个页面，
  硬套 `expand_settings_view` 会带出无关控件与不可控的 id 依赖，OTA 一改就崩。
- **素材**（图标、色值、动效、条目样式）→ **100% 复用**，通过 `createPackageContext` 引用，
  或直接 inflate `edge_function_item_layout` 作为单行模板。
- **主题** → 在详情页里 `theme.applyStyle` 套 OriginUI 的对话框/设置主题（若可解析），
  解析不到就退回 `Theme.DeviceDefault.DayNight`，保证不崩。

```kotlin
class ThermalDetailActivity : Activity() {
    private lateinit var gcRes: Resources          // com.vivo.gamecube 的 Resources
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        gcRes = runCatching {
            createPackageContext("com.vivo.gamecube", CONTEXT_IGNORE_SECURITY).resources
        }.getOrElse { resources }
        setContentView(buildUi())                  // 结构自建
        window.attributes = window.attributes.apply {
            windowAnimations = resId("anim", "pad_center_window_in")   // 复用原生动效
        }
    }
    private fun resId(type: String, name: String): Int =
        gcRes.getIdentifier(name, type, "com.vivo.gamecube")

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        // 总开关行（图标复用原生 settings 图标）
        root.addView(switchRow(
            icon = resId("drawable", "originui_vtoolbar_icon_settings_rom13_5"),
            title = "温控解除",
            checked = Policy.thermalBypass
        ) { on -> Policy.setThermalBypass(this, on) })

        // 分项（Q2 三件套），各自独立掩码
        listOf("q2_memc" to "插帧温控豁免", "q2_sr" to "超分温控豁免", "q2_ve" to "视效增强温控豁免")
            .forEach { (k, label) ->
                root.addView(switchRow(null, label, Policy.mask(this, k)) { on -> Policy.setMask(this, k, on) })
            }

        // 实时读数（数据源见 8.4）
        root.addView(readoutRow())                        // 当前温度 / Q2 状态 / 生效的 Hook 数

        // 恢复默认
        root.addView(buttonRow("恢复默认") { DisasterRecovery.restoreAll(this) })
        return root
    }
}
```

### 8.3 状态持久化：绝不写系统应用私有目录

| 方案 | 机制 | 评价 |
|---|---|---|
| **XSharedPreferences**（LSPosed 提供） | 模块 App 写自己的 `SharedPreferences`，Hook 侧用 `XSharedPreferences(pkg, name)` 读 | ✅ **首选**：无需 root、无文件权限问题、LSPosed 原生支持（`makeWorldReadable` 已废弃，新版默认可用） |
| `/data/adb/fuckorigin/config.json` | 模块 App 通过 root 写；Hook 进程（system uid）直接读 0644 文件 | ✅ 兜底：root 守护脚本也读它，两边同源 |
| 写 `com.vivo.gamecube` 的 SP | — | ❌ 禁止：触发完整性校验/被覆盖/需 root |

```kotlin
object Policy {
    private const val SP = "fuckorigin_policy"
    private fun read(ctx: Context): XSharedPreferences = XSharedPreferences("com.fuckorigin.thermal", SP)
    fun thermalBypass(ctx: Context): Boolean = read(ctx).getBoolean("thermal_bypass", false)
    fun setThermalBypass(ctx: Context, on: Boolean) {
        XSharedPreferences("com.fuckorigin.thermal", SP).edit().putBoolean("thermal_bypass", on).commit()
        AtomicFile(File("/data/adb/fuckorigin/config.json")).write("""{"thermal_bypass":$on}""")
    }
}
```

### 8.4 实时读数：优先"进程内调用"，而不是读文件

| 数据 | 首选 | 兜底 |
|---|---|---|
| 游戏场景温度 | **在 gamecube 进程内反射调用 `com.vivo.common.e#b()D`**（我们本来就 Hook 了它） | 读 `/sys/class/tshell/tshell_game`（需 root 或 system uid 有 SELinux 许可，⚠️ 未验证） |
| 整机温度 | `/sys/class/thermal/` 里按 `type` 命中 `tz_shell` | `dumpsys thermalservice`（app uid 可读） |
| Q2 功能实际状态 | 反射读控制器字段（`frameinterpolation.h` 的 `mCurrentState` 等） | logcat 抓 `FrameInterpolationController` 日志 |
| 当前生效策略 | 模块内部状态机直接读 | 同左 |

> 关键点：详情页若运行在**模块自有进程**，拿不到 gamecube 的类；所以读数应通过
> "Hook 侧（`com.vivo.gamecube` 进程内）把读数写进 XSharedPreferences / 广播给详情页"这条**单向数据流**，
> 而不是详情页自己去跨进程反射。

### 8.5 状态机与安全闸门

```
        ┌────────── OFF ──────────┐
        │  (默认，零副作用)        │
        └──────┬──────────┬───────┘
      开           │          │ 安全闸门触发
        ▼          │          ▼
   ┌─ 预热中 ─┐    │     ┌─ 自动降级 ─┐
   │ 校验 Hook │────┤     │ 回滚所有写   │
   │ 点存在性   │    │     │ 并提示原因   │
   └────┬─────┘    │     └─────────────┘
        ▼          │
     ┌─ ON ────────┘
     │ Hook 生效 + root 守护心跳
     └──────┬──────────────┐
            │ 预热失败      │ 温度 > 55℃
            ▼              ▼
     ┌─ 部分生效 ─┐   ┌─ 自动降级 ─┐
     │ 仅 L1 生效 │   │ 保留 L1     │
     └───────────┘   └─────────────┘
```

---

## 模块九：MVP 优先级排序

### 9.1 如果只能先做三个功能

| 排序 | 功能 | 为什么是它 | 工作量 | 可验证性 |
|---|---|---|---|---|
| **P0** | **Q2 温控旁路**（模块六 方案 A：Hook `com.vivo.common.e#b()D` + `frameinterpolation.h#G0(D)Z`） | ① 它是本项目"唯一确定有效"的招：调用链、温度源、阈值、日志全部实机对得上；② 改动面最小（2–3 个 Hook，一个类文件）；③ **不需要 root 写权限**；④ 失败也只是不生效，不会崩系统 | 1–2 人日 | `logcat -s FrameInterpolationController` 立刻能看到"不再打印 Overheat" |
| **P1** | **侧边栏注入 + 详情页**（模块五 方案 A + 模块八） | ① 这是用户的硬需求（原生注入，不是悬浮窗）；② 没有它，P0 就只是个"隐身开关"，体验上完全不可用；③ 依赖已被逆向钉死（`EdgeFunctionsAdapter` + 单布局 + 哨兵对象） | 3–5 人日 | 打开游戏侧边栏肉眼可见"温控解除"条目；详情页能实时显示温度 |
| **P2** | **探测/画像 + 安全闸门 + 一键恢复**（模块一 + 模块八 8.5） | ① 保证"可恢复"这个前提（任务书明确要求）；② 换机型/OTA 后靠它重新定位锚点，否则模块直接报废；③ 成本低（一条 `pm/dumpsys/getprop` 流水线 + 一个恢复按钮） | 2–3 人日 | 断网/重启/换游戏后仍能一键回到原厂状态 |

### 9.2 为什么**不**把云控对抗放进 MVP

1. **证据不支持**：本机的降频写者是 `thermal-engine`，Q2 门控读的是本地 XML，云控（abe）不在这条链上（§2.0）。
2. **风险收益比最差**：`com.vivo.abe` 是 PERSISTENT，同时承载隐私协议、跨设备、OTA 参数；动它可能牵连无关功能。
3. **验证成本高**：要证明"云控确实下发过保守策略"需要抓包 + 长时间观察，而它对本目标的贡献近乎 0。

结论：云控相关只做**D1（只断上报）+ D3（读侧冻结）**这两件低风险的事，作为 P3 后续迭代，
不进 MVP；且必须以"默认关闭"的独立开关出现。

### 9.3 里程碑与验收标准

| 里程碑 | 内容 | 验收（可复现的命令/现象） |
|---|---|---|
| M0（已完成） | 逆向与取证 | 本文档 §0–§7 全部 `✅` 项可在真机复现 |
| M1 | P0 旁路可独立运行 | `setprop persist.sys.games.test.overheat_threshold` 实验结论明确（属性通道是否可用）；Hook 开启后 logcat 不再出现 `Overheat, try to close frame interpolation.`，且游戏内插帧/超分在高负载下仍保持开启 |
| M2 | P1 侧边栏可见可点 | 侧边栏出现"温控解除"条目，状态与 M1 的 Hook 联动；详情页显示实时温度（±1 ℃ 与 `tshell_game` 一致） |
| M3 | P2 可恢复 + 可移植 | "恢复默认"后 `scaling_max_freq`/`min_pwrlevel`/温度读数全部回到基线；换一台非 PD2463 的 vivo 机器时，模块能通过"锚点定位法"重新绑定或安全禁用 |

### 9.4 风险清单（按"会不会变砖"排序）

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 写错 `thermal_zone` 索引（参考模块就踩了这个坑） | 高 | 中（改到无关传感器） | 一律按 `type` 反查；写入前 `verify` 类型名 |
| 把 `scaling_min_freq` 也顶满 | 中 | **高**（过热保护/重启） | 代码层禁止写 `min_freq`；只写 `max_freq` |
| 屏蔽 vendor 温控配置（L3）后充电异常 | 中 | **高**（电池/安全） | 默认关闭；开启时强制弹窗告知；提供 30 秒自动回滚 |
| Hook 在 OTA 后失配 | 高 | 低（不生效） | 类/方法存在性探测 + 名字校验 + 静默降级 |
| gamecube 崩溃导致侧边栏消失 | 低 | 中（用户可见） | 所有 UI Hook 包 try/catch；`getItemCount` 的 +1 必须有对应绑定，否则回滚计数 |
| `com.vivo.abe` 被改动触发保护 | 低 | 高 | 不进 MVP；D1/D3 默认关闭 |

---

## 附录 A：本方案的证据索引（可逐条复现）

| 证据 | 文件/命令 |
|---|---|
| 设备与系统、CPU/GPU/温区/刷新率全量只读审计 | `日志/设备只读审计-iQOO Neo10 Pro+.md`（805 行，含每条命令与真实输出） |
| `com.vivo.gamecube` 全类/方法/字符串索引 | 由 `工具/dx.py` 生成 `gamecube.db`（19,382 类 / 132,460 方法 / 69,376 字面量；库文件不入库，可 1 分钟重建） |
| 资源全表 | `资源/索引/arsc_index.tsv`（19,587 行）、`资源/索引/filemap.tsv`（5,787 行） |
| 面板布局清单 | `资源/索引/panel_layouts.tsv`（102 个含 gameassistant 视图的 layout） |
| 面板素材清单 | `资源/索引/panel_assets.tsv`（1,502 条引用，174 个 drawable 带路径） |
| 60 个面板 XML 结构 | `资源/面板XML/` + `MANIFEST.tsv`（root 标签/自定义视图类/aapt 一致性） |
| `com.vivo.abe` 云控实现 | 由 `工具/dx.py` 对 abe 的 APK 建同款索引（`com.vivo.unifiedconfig.*`、`ConfigSqliteHelper` 建表语句） |
| 云控端点与拦截脚本 | `vhs/cloud_block/target_ips.txt`、`vhs/cut_cloud.sh`、`vhs/cap/*.pcap` |
| 参考模块（解温控） | `/storage/emulated/0/FuckOrigin/thermal/`（v1.0.3，15 个 0 字节占位文件 + `service.sh`） |
| 温度节点实测 | `su -c '/system/bin/cat /sys/class/tshell/tshell_game'` → `46751,46751,46751` |
| 本地阈值配置 | `/system/etc/gamecube/SuperResolutionConfigList.xml`（PD2463，48/45 ℃）、`FrameInterConfigList.xml` |

## 附录 B：本方案自带的逆向工具（都在 `工具/`）

| 工具 | 作用 | 用法 |
|---|---|---|
| `dx.py` | 无依赖 DEX 索引器（类/方法/字符串字面量/调用图 → sqlite） | `python3 dx.py build`；`python3 dx.py q "select cls,name,value from literals where value like '%thermal%'"` |
| `smali.py` | 轻量反汇编器（解析 invoke/iget/const-string，带方法签名） | `python3 smali.py 'Lcom/vivo/gameassistant/frameinterpolation/h;' G0` |
| `arsc.py` | `resources.arsc` 解析器（dense/sparse/offset16/compact 全支持） | `python3 arsc.py index / stats / find <re> / files <re> / verify` |
| `axml.py` | 最小二进制 XML 读取器（与 `aapt dump xmltree` 交叉校验 60/60 一致） | `python3 axml.py <file.xml>` |
| `analyze.py` | 由索引生成 `panel_layouts.tsv` / `panel_assets.tsv` / `attributed_*.tsv` | `python3 analyze.py` |

> 注意：`dx.py`/`smali.py` 与标准库 `dis` 无冲突（早期版本曾命名为 `dis.py`，会遮蔽标准库导致 `argparse` 崩溃，已改名）。

## 附录 C：速查命令（本机实测通过）

```bash
# ── root 的正确姿势（Termux 的 id/ls 在 root 环境链接失败，必须用 /system/bin 绝对路径）
su -c "/system/bin/sh -c '/system/bin/id; /system/bin/ls /sys/class/tshell/'"

# ── 温区按 type 反查（永远不要写死索引）
su -c "/system/bin/sh -c 'i=0; while [ \$i -lt 95 ]; do /system/bin/echo -n \"z\$i=\"; /system/bin/cat /sys/class/thermal/thermal_zone\$i/type; i=\$((i+1)); done'"

# ── 游戏侧 Q2 过热门控的实时日志（验证旁路是否生效）
adb logcat -c && adb logcat -s FrameInterpolationController:V GameSuperResolutionController:V \
  | grep -E "mOverheatThreshold|Overheat|isOverheat|温度"

# ── 当前系统热状态
dumpsys thermalservice | grep -E "Thermal Status|skin|Threshold"

# ── 面板/开关的类是否存在（换机型后先跑这条）
python3 dx.py q "select cls from classes where cls like '%EdgeFunctionsAdapter%' or cls like '%PerformancePanelPanelView%'"

# ── 一键回滚 sysfs（模块应内置同一套逻辑）
for p in policy0 policy6; do
  b=/sys/devices/system/cpu/cpufreq/$p
  cat $b/cpuinfo_max_freq > $b/scaling_max_freq
  echo walt > $b/scaling_governor
done
```

## 附录 D：本方案**没有**验证的部分（诚实清单）

1. **没装 LSPosed**（本机只有 `com.xposed.wetypehook`），所有 Hook 代码均未在设备上运行过；
   伪代码中的类名/方法签名来自静态反编译，运行时行为未验证。
2. `persist.sys.games.test.overheat_threshold` **是否真的被 ROM 启用**未验证（属性当前为空；
   需要一次 `setprop` + 进游戏看 logcat 的实验，见 §6.4）。
3. 阈值读取函数的**分支方向**（`if (value > 0) 用属性 else 用配置`）来自指令级反汇编，
   但该方法内 `const-wide` 处存在 2 个指令对齐瑕疵，**方向需以 §6.4 的实测为准**。
4. `system` uid（gamecube 进程）**能否直接读** `/sys/class/tshell/tshell_game` 未验证（app uid 被 SELinux 拒绝）。
5. 侧边栏注入的**目标进程**（`:assistantui` 还是主进程）由类归属与进程列表推断，
   未用 Hook 实测；落地时用 `logcat -p <pid>` 或首行 `Log.i` 确认。
6. `apktool` 未安装，`apktool d` 在本 APK 上的输出（是否按真实资源名落地）未验证；
   已验证的提取路径是 `aapt dump xmltree <apk> <path>` + 自研 `arsc.py`。
7. `com.vivo.abe` 的**回执伪造**只给到表结构与方法面（`reportEvent`、`updated_modules.report_num`），
   具体写入点未定位。
8. Q2 的 vendor HAL（`vivo.hardware.game.memc`）**未逆向**，方案 C 因此被排除。
9. 所有涉及"OriginOS 7"的描述均以本机 **OriginOS 6** 为准，未在 7 上验证。

---

*文档结束。所有 `✅` 结论都可以在本文附录 A/C 的命令下复现；任何与实机不符之处，以实机输出为准。*
