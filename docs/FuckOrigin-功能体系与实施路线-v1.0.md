# FuckOrigin · 功能体系、空壳化与实施路线 v1.0

> 本文与 `FuckOrigin-OriginOS-技术方案-v1.0.md`（下称 **方案 A**）配套：
> **方案 A 回答"每个 Hook 点在哪、签名是什么"；本文回答"整个工具长什么样、功能怎么归类、
> 壳怎么来、先做什么后做什么、怎么在真机上一步步验证到可用"。**
>
> 撰写日期 2026-10-19 ｜ 基准机：iQOO Neo10 Pro+（V2463A / PD2463）· Android 16 / OriginOS 6 ·
> `PD2463D_A_16.1.20.2.W10` · KernelSU ｜ 标注沿用方案 A：✅实测 / 🟡强候选 / ⚠️需实验 / 📄类比

---

## 0. 本轮新增取证（写进分类体系的硬事实）

| 项 | 实测结果 | 来源 |
|---|---|---|
| 系统更新 | `com.bbk.updater` 4.9.28.3 @ `/system/app/Updater/Updater.apk`；权限含 `INSTALL_PACKAGES`、`INSTALL_SELF_UPDATES`、`REBOOT`、`WRITE_SECURE_SETTINGS` | `aapt dump badging` ✅ |
| OTA 与云控耦合 | **Updater 申请了 `com.vivo.abe.permission.unifiedconfig.provider` 与 `configlist_provider`**，还声明了 `com.vivo.cota.service.access` | 同上 ✅ |
| COTA 通道 | `com.vivo.cota` 1.0.4.6 @ `/system/app/COTA/COTA.apk`，权限 `vivo.cota.permission.COTASERVICE`、`REBOOT` | ✅ |
| 安全/管家 | **实际包名是 `com.iqoo.secure`**（不是 `com.vivo.secure`）9.3.14.1 @ `/system/custom/app/IqooSecure/IqooSecure.apk`；反编译含 `setDaemon`、防火墙名单、隐私保护等字符串 | `aapt` + dex strings ✅ |
| 系统界面 | `com.android.systemui` 16.0.7.251 @ `/system/priv-app/SystemUI/SystemUI.apk`；vivo 定制命名空间 `com/vivo/systemui/**` | ✅ |
| 原子岛 | SystemUI dex 内含 `ISLAND_SHOW/ISLAND_HIDE/ISLAND_CARD_SHOW/TRANS_ISLAND_*`、`vivo_island_status_right_seat_text`、深链 `atomicnotificationsetting` | dex strings ✅ |
| 状态栏图标/时钟 | SystemUI dex 内含设置键 **`icon_blacklist`、`clock_seconds`、`show_seconds`**，以及 vivo 自有 **`gamecube_block_notification_blacklist`** | dex strings ✅ |
| 直驱供电 | `persist.vivo.all_bypass_charge_support=1`、`persist.vivo.game_bypass_charge_support=1`、`persist.vivo.game_bypass_charge_flag=0` | `getprop` ✅ |
| 刷新率仲裁（RMS） | `sys.rms.is_supported=true`、`persist.sys.rms.re_enable=true`、`debug.rms.refresh_rate_adjuster_version=230409`、`persist.rms.list_jank_epm_*` | ✅ |
| 核心调度 | `/sys/devices/system/cpu/cpu*/core_ctl/{max_cpus,min_cpus,active_cpus,busy_up_thres,busy_down_thres,enable}` 全部存在 | `ls` ✅ |
| 显示模式 | `/sys/class/drm/card0-DSI-1/modes` 含 `1440x3168x144`、`1080x2376x120/144` | ✅ |
| GPU 调频 | `devfreq/3d00000.qcom,kgsl-3d0`、`kgsl-busmon`；`kgsl-3d0/min_pwrlevel` 可写 | ✅ |

> 这张表直接决定了两件事：**（1）"系统更新屏蔽"必须与云控对抗放在同一个模块设计**（Updater 自己就在读 abe 的 configlist）；
> **（2）用户提到的 `com.vivo.secure` 在本机不存在**，等价物是 `com.iqoo.secure`，做"守护回写对抗"要按它来。

---

## 模块一：功能分类体系与自动生成规则

### 1.1 三层结构

```
一级作用域（OriginOS 系统组件 / 进程）
   └── 二级功能域（OriginOS 语义，固定 6 + 2 个扩展）
         └── 功能条目（可开关、可回滚、可验收的最小单元）
```

二级功能域统一为 6 个主域：**性能与温控 / 游戏与侧边栏 / 界面与交互 / 后台与功耗 / 通知与权限 / 设备与调试**；
另设 2 个 OriginOS 专属扩展域：**原子通知与原子岛**、**系统更新与云控**。
（后者必须单列——本机证据显示 OTA 与云控共用一个配置通道，混在"后台与功耗"里会漏掉联动关系。）

### 1.2 一级作用域候选映射表（真机核对版）

| 一级作用域 | OriginOS 实际组件（本机核对） | 进程特征 | 增强潜力 | 状态 |
|---|---|---|---|---|
| 系统框架 | `android`（system_server） | `system_server`，单进程 | 权限/温控/隐藏 API/包管理 | ✅ 存在；**风险极高，默认禁区** |
| 系统界面 | `com.android.systemui` + `com.vivo.systemuiplugin` | `com.android.systemui`（uid system） | 状态栏、快捷设置、锁屏、**原子岛**、通知 | ✅ 存在；类锚点已定位（见 1.5） |
| 系统设置 | `com.android.settings`（+ overlay `com.android.settings.overlay.common`） | 独立进程 | 隐藏设置项解锁、应用详情控制 | ✅ 存在；`com.vivo.settings` **本机不存在**（vivo 未拆分包） |
| 游戏魔盒 | `com.vivo.gamecube` 14.0.13.02_de | 主 / `:assistantui` / `:daemon`，均 uid system | 侧边栏注入、Q2 门控、性能面板 | ✅ 已完整逆向（方案 A 模块五/六） |
| 性能与温控 | `thermal-engine`(init)、`com.vivo.devicepower`、`vendor.thermal-hal`、core_ctl/kgsl 节点 | native + system app | 频率、核心分配、温控阈值 | ✅ 节点与属性已取；**Monster 引擎的"引擎"本体在 gamecube 内**（`MonsterCpuAndGpuView`/`MonsterPlusEvent`），不是独立进程 |
| 系统更新 | `com.bbk.updater`、`com.vivo.cota`、`com.bbk.updater` 的 abe provider 依赖 | 独立进程 | 屏蔽 OTA、冻结检查、对抗回写 | ✅ 存在；与云控耦合已确认 |
| 安全/管家 | **`com.iqoo.secure`**（用户所写 `com.vivo.secure` 在本机不存在） | 独立进程 + daemon 位（`setDaemon` 字符串） | 后台管控、自启动、通知拦截、冻结回写 | ✅ 存在；回写机制 ⚠️ 待实验 |
| 原子通知/原子岛 | 无独立包，位于 `com.android.systemui`（`ISLAND_*`） | 同 SystemUI | 展示位置、优先级、样式 | 🟡 字符串已定位，落点 ⚠️ |
| 系统桌面 | `com.bbk.launcher2`（+ `com.vivo.launchercopilot`、`com.vivo.privacylauncher`） | 独立进程 | 动画、图标、布局、原子组件 | ✅ 存在（未逆向） |
| 电话/短信/联系人 | `com.android.contacts` / `com.android.mms`（+ `com.android.incallui`） | 独立进程 | 验证码、拦截规则 | ✅ 包名确认；类未逆向 |
| 相机 | `com.android.camera` / `com.vivo.alphacamera` | 独立进程 | 隐藏模式、水印、功能开关 | ✅ 包名确认；类未逆向 |

### 1.3 功能条目字段定义（机器可读，供模块零/UI/排期共同消费）

```jsonc
{
  "featureId": "thermal.bypass.global",          // 稳定 ID：域.子域.动作，禁止改名（配置/回滚都靠它）
  "scope": "com.vivo.gamecube",                  // 一级作用域 = 目标包名（system_server 用 "android"）
  "scopeProcess": ":assistantui",                // LSPosed 需精确到进程（本机 gamecube 有 3 个）
  "domain": "性能与温控",                          // 二级功能域（8 选 1）
  "name": "温控解除（总开关）",
  "hookType": ["METHOD", "SYSPROP"],             // METHOD | FIELD | SYSPROP | SYSFS | DB | SETTINGS | UI
  "targets": [
    { "kind": "METHOD", "desc": "com.vivo.common.e#b()D", "hookPoint": "AFTER", "expect": "return 35.0" },
    { "kind": "METHOD", "desc": "com.vivo.gameassistant.frameinterpolation.h#G0(D)Z", "hookPoint": "AFTER", "expect": "return false" }
  ],
  "risk": "低",                                   // 低 | 中 | 高（判据见 1.6）
  "linkThermal": true,                            // 与温控解除联动
  "linkQ2": true,                                 // 与 Q2 旁路联动
  "rollback": "关闭开关即恢复；进程重启彻底恢复",
  "status": "已定位",                              // 待逆向 | 已定位 | 已实现 | 已验证
  "evidence": "方案A §6.1/§6.3；实测 tshell_game=46751,46751,46751",
  "acceptance": "logcat 不再出现 'Overheat, try to close frame interpolation.'",
  "depends": ["proto.thermal.source"],
  "owner": "core"
}
```

字段里最关键的是 **`scopeProcess`**：方案 A 已证 `com.vivo.gamecube` 有 3 个进程，`AssistantUIService` 在 `:assistantui`
里做 `initFrameInterSupportList`，若只按包名声明作用域，LSPosed 会把 Hook 装进主进程，然后什么都不发生。

### 1.4 自动生成规则（不是手写清单）

```kotlin
// Stage 1: 采集 → PkgFacts（方案 A §1.2 的 6 条通道）
// Stage 2: 作用域判定 —— 只有"有可 Hook 面 + 有增强价值"的包才成为一级作用域
fun isScopeCandidate(p: PkgFacts): Boolean =
    p.isSystemApp &&
    (p.hasVivoNamespace || p.persistent || p.uid == 1000) &&
    (p.hasDecisionMethod || p.readsPerfSysfs || p.hasIslandStrings || p.isUpdaterLike)

// Stage 3: 二级功能域推导 —— 用"能力指纹"投票，而不是包名匹配
fun inferDomains(p: PkgFacts): Set<Domain> = buildSet {
    if (p.reads("/sys/class/thermal/") || p.reads("/sys/class/tshell/") ||
        p.classes.any { it.startsWith("com/vivo/gameassistant/frameinterpolation") }) add(Domain.PERF_THERMAL)
    if (p.classes.any { it.contains("/sideslide/") } || p.hasProvider("gamesettings")) add(Domain.GAME_SIDEBAR)
    if (p.hasIslandStrings || p.classes.any { it.startsWith("com/vivo/systemui") }) add(Domain.UI)
    if (p.strings.any { it.contains("unifiedconfig") } || p.isUpdaterLike) add(Domain.UPDATE_CLOUD)
    if (p.classes.any { it.contains("autostart", true) || it.contains("setDaemon") }) add(Domain.BACKGROUND)
    if (p.requests("android.permission.WRITE_SECURE_SETTINGS") &&
        p.strings.any { it.contains("icon_blacklist") || it.contains("clock_seconds") }) add(Domain.UI)
    if (p.strings.any { it.contains("dumpsys") || it.contains("/proc/") }) add(Domain.DEVICE_DEBUG)
}

// Stage 4: 条目生成 —— 只在"已定位到具体方法/字段/节点"时才落成条目；
//          没定位的进 backlog（status=待逆向），不允许凭空造 feature
fun generateFeatures(profiles: List<PkgFacts>, catalog: FeatureCatalog): List<Feature> =
    catalog.all.filter { f -> profiles.any { it.pkg == f.scope && inferDomains(it).contains(f.domain) } }
```

**关键约束**：分类体系可以自动生成，**功能条目不能**。条目必须有 `targets`（真实类/方法/节点）才算数；
自动生成只负责"这个包该出现在哪个作用域、哪个域下有候选"，不负责编造 Hook。

### 1.5 本机真实填充示例（三条，覆盖三种 hookType）

```jsonc
// ① 方法 Hook（Q2 门控）—— 证据最硬
{ "featureId": "q2.gate.overheat", "scope": "com.vivo.gamecube", "scopeProcess": "*",
  "domain": "性能与温控", "name": "Q2 过热判定放行", "hookType": ["METHOD"],
  "targets": [{ "kind": "METHOD", "desc": "com.vivo.gameassistant.frameinterpolation.h#G0(D)Z",
                "hookPoint": "AFTER", "expect": "false" }],
  "risk": "低", "linkThermal": true, "linkQ2": true, "status": "已定位" }

// ② Settings 键（状态栏时钟秒）—— 键名已从 SystemUI dex 里捞到
{ "featureId": "ui.statusbar.clock_seconds", "scope": "com.android.systemui", "scopeProcess": "main",
  "domain": "界面与交互", "name": "状态栏时间显示到秒", "hookType": ["SETTINGS"],
  "targets": [{ "kind": "SETTINGS", "desc": "Settings.System.clock_seconds / show_seconds",
                "hookPoint": "WRITE", "expect": "1" }],
  "risk": "低", "linkThermal": false, "linkQ2": false, "status": "已定位" }
// ⚠️ 需实验：AOSP 用 clock_seconds 控制，vivo 是否沿用需真机改一次看效果

// ③ SYSFS 节点（核心调度重排）—— 节点与当前值均已实测
//    scope 不是某个 App，而是"root 守护进程"：SYSFS 类功能由守护执行，不是 LSPosed 进程内执行
{ "featureId": "perf.core_ctl.big_floor", "scope": "root-daemon", "scopeProcess": "main",
  "domain": "性能与温控", "name": "大核簇常驻核心数", "hookType": ["SYSFS"],
  "targets": [{ "kind": "SYSFS",
                "desc": "/sys/devices/system/cpu/cpu6/core_ctl/{min_cpus,max_cpus}",
                "hookPoint": "WRITE", "expect": "min_cpus=2, max_cpus=2（实测当前 min=0/max=2，active=1）" }],
  "risk": "中", "linkThermal": true, "linkQ2": false, "status": "已定位" }
```

### 1.6 风险等级判据（避免"拍脑袋定中/高"）

| 等级 | 判据（命中任一即升级） |
|---|---|
| 高 | ① 作用域是 `system_server` 或 `com.iqoo.secure`；② 会改动持久分区/OTA 通道；③ 失败可能导致无信号/无法开机/无法充电；④ 影响安全类判断（温控保护、充电保护、锁屏） |
| 中 | ① 依赖 sysfs 写入且会被守护回写；② 修改其他应用的可见行为（图标/通知/权限）；③ 需要 root 侧守护进程 |
| 低 | ① 纯进程内 Hook，重启即恢复；② 只改自己进程的返回值；③ 有明确日志可验收 |

---

## 模块二：系统软件探测与云控链路定位（可执行版）

### 2.1 探测命令清单（左列=强候选可直接用，右列=需实验）

| 目的 | 强候选命令（本机验证过） | 需实验的补充 |
|---|---|---|
| 系统包枚举 | `pm list packages -s \| sed 's/package://'`（本机 423 条） | `pm list packages --show-versioncode`（部分 ROM 支持） |
| 组件/进程 | `dumpsys package <pkg> \| sed -n '/^Service Resolver Table/,/^Registered ContentProviders/p'` | `dumpsys activity services <pkg>`（vivo 可能裁剪输出） |
| 进程角色 | `ps -A \| grep -E "gamecube\|iqoo.secure\|bbk.updater\|systemui"`（需 root，app uid 只看到自己） | `cat /proc/<pid>/cgroup` 判 top-app/foreground |
| 性能/温控属性 | `getprop \| grep -E "^(persist\|vendor\|sys)\..*(thermal\|perf\|fps\|bypass\|rms)"` | `getprop` 全量落盘后做差分（改一次设置跑一次） |
| 温区 | 按 `type` 反查（方案 A §3.2），**不要用索引** | `dumpsys thermalservice` 的 `Cached temperatures` 与 zone 对账 |
| 云控缓存 | `ls -la /data/data/com.vivo.abe/databases/`（需 root）；`ls -la /data/data/*/databases/*.db` | `inotifywait` 监控 `-wal` 变化 |
| 云控消费方 | 全系统 APK grep `content://com.vivo.abe`（本机已知：gamecube、updater） | 动态 hook `ContentResolver.query` 统计调用者 |
| OTA 面 | `aapt dump badging /system/app/Updater/Updater.apk`（本机已取权限） | `pm dump com.bbk.updater \| grep -i cota` |

### 2.2 云控链路定位（在方案 A 基础上补一条本机新发现）

方案 A 已给出四段链路（拉取端 `/data/data/com.vivo.abe` → `romsp-unifyconfig` → `unifiedconfig.db` 五表 → provider 消费）。
**本轮新增的关键事实**：`com.bbk.updater` 自己就申请了 `com.vivo.abe.permission.unifiedconfig.provider` /
`configlist_provider`，并依赖 `com.vivo.cota.service.access`。这意味着：

```
abe(unifiedconfig) ──┬──→ gamecube（游戏分类 appcategoryinfo）
                     └──→ updater（OTA 策略/灰度名单）→ cota → 实际下载/重启
```

**推论（对工具设计有直接影响）**：
1. "系统更新屏蔽"不能只冻 `com.bbk.updater`——它的策略来自 abe，**abe 的云控若继续拉取，updater 解冻后会立刻补上新策略**。
2. 正确的组合是：`D3 读侧冻结（abe）` + `updater 停用/冻结` + `cota 拦截`，三者同属"系统更新与云控"域，开关要一起动。
3. 这条链路也让"回执伪造"有了更明确的目标：只要 abe 认为该 module 未更新，就会再次下发；伪造点在 `standard_config` 的读侧，而不是 updater 侧。

### 2.3 对抗手段候选与验证状态

| 手段 | 具体做法 | 状态 | 备注 |
|---|---|---|---|
| 版本字段冻结 | 冻结 `config_list.version` / `standard_config.engine_version`；read 侧返回快照 | 🟡 | DB 写会被 abe 重算，读侧 Hook 更稳（方案 A §2.3 D2/D3） |
| 读入口改写 | Hook `UnifiedConfigProvider#call("getEnryptedStandardConfig", module, bundle)` | 🟡 | 方法签名已实证；返回结构需实机抓一次 |
| 回执伪造 | 上报前把 `result=0 → 1`、`report_num` 固定 | ⚠️ | 写入点未定位（方案 A 附录 D-7 已注明） |
| 属性借用（CosaSpoof 变体） | 进程内 hook `ro.vivo.product.model` → 走另一机型的阈值分支 | 🟡 | 端侧机型常量表已确认（`com/vivo/common/a`、`bc/r`） |
| 网络层只断上报 | iptables 限 `abeacdataort-stsdk`/`vhs.wwstat.com` 等 | 🟡 | 端点来自实抓，脚本 `vhs/cut_cloud.sh` 已有 plan/apply/revert |

---

## 模块三：UI 空壳化与功能注入

### 3.1 前置说明（必须先讲清楚）

本轮工作区里**没有**你要用作外壳的那个第三方工具 APK（`/storage/emulated/0/FuckOrigin/` 下只有图标与
`thermal/` 参考模块）。所以本节给的是**可对任意 APK 复用的流水线 + 验收标准**，
落地时把 `<target.apk>` 换成实际文件即可；每一步都标注了"能不能自动判定"。

另外必须提前说明一个工程事实：**"保留 UI 但剥离全部业务代码"在技术上有两条路，成本差一个量级**：

| 路线 | 做法 | 成本 | 风险 |
|---|---|---|---|
| **R1 反编译改造**（用户描述的那条） | `apktool d` → 删 smali → 补骨架 → 回编译 | 高（原 App 的 Adapter/ViewModel/DI 都要重写，否则删了 smali 就闪退） | 回编译常失败（签名校验、资源混淆、so 依赖）；且残留的类名/资源仍是他人作品 |
| **R2 资源级复用 + 代码重写** | 只取 `res/`（布局/图标/动画/values）+ Manifest 的导航结构，用新工程重写 Activity/Fragment 骨架 | 中（骨架本来就简单：一个主页 + N 个详情页 + 开关列表） | 资源版权问题仍在，但**没有代码搬运**，且可控可维护 |

**推荐 R2**，理由：你给的"功能代码零引用"边界在 R2 下天然满足；R1 的"删 smali 还能跑"在实践中几乎不成立
（删掉一个 `onCreate` 里调用的类，Activity 直接 `NoClassDefFoundError`）。R2 也更容易过"零业务残留"验收（§3.7）。

### 3.2 R2 流水线（命令级）

```bash
# ① 基线：先确认原 App 能装能跑，并记录指纹（后续"零残留"验收要用）
aapt dump badging target.apk | head -5         # 包名/版本
md5sum target.apk > shell_baseline.md5

# ② 只解资源（不解 dex，减少污染；R2 不需要原 smali）
#    ⚠️ 本机未安装 apktool（已验证可用的替代路径：unzip 取 res/* + aapt dump xmltree 单文件 + arsc.py 建索引）
apktool d target.apk -r -f -o shell_res/
#    若 apktool 因资源混淆失败，退回：
unzip -o target.apk 'res/*' 'resources.arsc' 'AndroidManifest.xml' -d shell_res/

# ③ 清单化：先拿到 type→name 全表，再按类型建白名单（只留 layout/drawable/anim/animator/interpolator/values/mipmap/xml）
aapt dump resources target.apk > shell_res/res.txt       # 若为空（资源表新版格式），改用 apktool 的 res 目录树
grep -E "^ *type [0-9]+ configCount" -A2 shell_res/res.txt | head    # 确认能列出类型
#    本机可用的替代：python3 工具/arsc.py index（对 resources.arsc 直接建索引，已验证 19,587 条）

# ④ 建新工程（骨架重写，不是改原工程）
gradle init --type basic &&  mkdir -p app/src/main/{java,res}
#    res/ 只搬白名单目录；AndroidManifest 只保留：application + 3~5 个 Activity + 主题

# ⑤ 逐个 Activity/Fragment 写骨架（下面 §3.4 的接口契约）
# ⑥ 回编译、装机、跑 §3.7 验收
```

### 3.3 目录级保留 / 剥离清单

| 目录 / 文件 | 处理 | 说明 |
|---|---|---|
| `res/layout/` | **保留白名单** | 只留主页、详情页、列表项、对话框；其余丢弃 |
| `res/drawable*/` | **保留白名单** | 图标、背景、开关样式；注意 `-night`/`-xxhdpi` 变体要成套留，否则夜间模式塌 |
| `res/anim/`、`res/animator/`、`res/interpolator/` | **保留** | 这是"视觉一致"的主要来源；`interpolator` 常被 anim 引用，漏一个就 `Resources$NotFoundException` |
| `res/values*/`（colors、styles、dimens、themes、strings） | **保留** | 缺 `style` 会导致 inflate 失败；`strings` 只需 UI 文案 |
| `res/xml/`（preference、shortcuts、file_paths） | **按需** | `file_paths` 若指向业务目录，删 |
| `res/raw/`、`assets/` | **默认剥离** | 常见位置：模型文件、证书、脚本、字体（字体若要保，移到 `res/font`） |
| `smali*`、`classes*.dex` | **全剥离** | R2 不保留任何原代码 |
| `lib/`（native so） | **全剥离** | 业务逻辑常在 so 里；保留会带来 ABI 与安全面问题 |
| `AndroidManifest.xml` | **重写** | 只留应用壳、主题、Activity 声明；删所有权限（尤其 `INTERNET`、`QUERY_ALL_PACKAGES`） |
| `META-INF/`（签名） | 重新签名 | 用模块自己的 keystore |

### 3.4 注入接口定义（空壳 UI 只依赖这三个）

```kotlin
/** 功能注册：空壳 UI 不知道任何 OriginOS 细节，只认 featureId */
object FeatureRegistry {
    interface Handler {
        fun isEnabled(): Boolean
        fun setEnabled(v: Boolean)
        fun state(): FeatureState                 // ON / OFF / UNAVAILABLE(reason) / DEGRADED
        fun meta(): FeatureMeta                   // 名称、所属域、风险、依赖
    }
    fun register(featureId: String, h: Handler)
    fun resolve(featureId: String): Handler?
    fun all(domain: Domain? = null): List<Handler>
}

/** 开关绑定：把任意 SwitchCompat / 自绘开关绑到一个 featureId，双向同步 */
object SwitchBinder {
    fun bind(view: CompoundButton, featureId: String): Disposable
    // 契约：view 初始态 = handler.state()==ON；点击 → handler.setEnabled()；
    //       handler 外部变化（如安全闸门自动降级）→ 回写 view（用 Observe 回调）
}

/** 详情页加载：按域或功能组渲染，UI 结构由空壳提供，内容由模块提供 */
object DetailPageLoader {
    fun load(groupId: String, container: ViewGroup)   // 内部按 FeatureRegistry.all(domain) 渲染
}
```

**契约四条（不写清楚必踩坑）**：
1. **线程**：`setEnabled` 可能被 UI 线程调用，但内部要写 sysfs/SP → 接口实现内部切到工作线程，UI 侧不做阻塞等待。
2. **幂等**：`onCreate` 可能因旋转重建多次调用 `bind`，`Disposable` 必须可重入撤销。
3. **异常隔离**：任何 `Handler` 抛异常只能把该条目置为 `UNAVAILABLE`，**不能冒泡到 UI 线程**（否则整页白屏）。
4. **状态可观测**：安全闸门自动降级时（方案 A §8.5），必须能把 `state()` 变化推给正在显示的面板。

### 3.5 分类体系 ↔ 空壳导航的映射规则

空壳 App 的原导航（假设是"首页卡片 + 侧边抽屉 + 详情页"）与我们的 8 个域**不可能一一对应**，规则如下：

| 情况 | 处理 |
|---|---|
| 原导航域 ⊂ 我们的域 | 保留原分组名作"视觉标签"，内部按我们的域重排 |
| 原导航域 ⊃ 我们的域（如"实验室/工具箱"） | 拆成我们的域，原标签降级为副标题 |
| 原导航有但我们没有（如"关于/捐赠"） | 保留（无害），但必须移除任何跳转外链 |
| 我们没有但原导航没有 | 新建域入口，图标从 `res/drawable` 里挑语义最近的（或回退 OriginOS 原生图标） |

**顺序规则**：默认排序 = 性能与温控 → 游戏与侧边栏 → 界面与交互 → 系统更新与云控 → 后台与功耗 →
通知与权限 → 设备与调试。理由：前三项是本工具的主价值，后四项是"顺手"。

### 3.6 资源复用边界与双轨建议

| 允许复用 | 禁止 |
|---|---|
| 布局骨架、图标、动画曲线、开关样式、主题色、字体层级 | 任何业务代码、Hook 实现、配置解析、云控逻辑、native so |
| 原 App 的**导航层级**（视觉信息架构） | 原 App 的类名/包名/资源命名（会构成"实质性替代"证据） |

**双轨建议（工程上更优，也是我实际能验证的那条）**：

- **轨道 A（你指定的）第三方空壳**：适合个人测试环境。发布前需自行评估 UI 资源版权与"实质性替代"风险——
  你给的规则里也这么写了，我照此执行，不再展开。
- **轨道 B（推荐并行）OriginOS 原生壳**：直接用本机已提取的 19,587 个 gamecube 资源做外壳
  （`expand_settings_view` 作设置页、`edge_function_item_layout` 作开关行、`pad_center_window_in/out` 作动效、
  `originui_vtoolbar_icon_*` 作图标）。好处有三：
  ① 视觉天然与系统一致（比第三方壳更像原生）；② 不涉及第三方商业 App 的 UI 复制；
  ③ **资源 ID/名字已在本机核实**（方案 A §7.4 给了 14 条可直接引用的清单）。
  代价：需要处理"从系统包取资源"的兼容性（`createPackageContext` + 名字校验，方案 A §7.3 已给实现）。

### 3.7 "零业务残留"自动化验收（R2 必须全过）

```bash
# 1) 没有任何原始包名/类名残留
unzip -p shell/app-release.apk 'classes*.dex' | strings -n 8 | grep -c "<target.package>"      # 期望 0
# 2) 没有 native 库
unzip -l shell/app-release.apk | grep -c '\.so$'                                              # 期望 0
# 3) 权限收敛（只留必要项）
aapt dump badging shell/app-release.apk | grep uses-permission                                # 期望 ≤3 条
# 4) 方法数合理（骨架应远小于原 App）
python3 -c "import subprocess;print(subprocess.run(['dexdump','-f','classes.dex'],capture_output=True).stdout[:200])" 2>/dev/null || echo "用 dx.py 统计"
# 5) 资源指纹：保留资源的 md5 清单存档（便于日后换版本比对）
```

---

## 模块四：游戏魔盒侧边栏注入与 Q2 温控旁路

### 4.1 注入点结论（方案 A 已证，这里只给执行版）

| 事实 | 值 |
|---|---|
| 承载方式 | **`WindowManager` 悬浮窗**，由 `com.vivo.gameassistant.b0`（字面量 `AssistantWindowManager`）管理，`PRIVATE_FLAG_TRUSTED_OVERLAY`，`bindToApp` 绑定到游戏窗口 |
| 进程 | `com.vivo.gamecube:assistantui`（manifest 实证：`AssistantUIService`/`GameCubeFuncService` 在此） |
| 开关列表 | `EdgeFunctionsAdapter` → `BaseEdgeFunctionsAdapter` → BRVAH `BaseQuickAdapter`，**单布局**（`edge_function_item_layout` 0x7f0d008a） |
| 性能面板 | `PerformancePanelPanelView`（自定义 ViewGroup，`performance_panel_layout` 0x7f0d0226），内容容器 id `0x7f0a0715` |
| 注入策略 | RecyclerView → **`getItemCount()` +1，三参 `onBindViewHolder` 里用 `position >= data.size()` 识别哨兵位**（方案 A §5.3） |

### 4.2 侧边栏条目与详情页（功能组：`游戏与侧边栏 / 性能与温控`）

```
[侧边栏快捷开关列表]
 ├─ 原生条目…
 └─ ★ 温控解除   ← 我们插入（图标复用 originui_vtoolbar_icon_settings_rom13_5）
      └─ 点击 → [温控解除详情页]
           ├─ 总开关（联动 featureId: thermal.bypass.global）
           ├─ 分项：插帧豁免 / 超分豁免 / 视效增强豁免（q2.mask.*）
           ├─ 实时读数：tshell_game 温度 · CPU 当前/上限频率 · Q2 状态 · 生效 Hook 数
           ├─ 安全闸门状态：温度 >55 ℃ 时自动降级中的提示
           └─ [恢复默认] → DisasterRecovery.restoreAll()
```

实时读数的数据源，按可靠性排序（方案 A §8.4）：
**进程内反射调 `com.vivo.common.e#b()D`**（最稳，我们本来就 Hook 了它）→ 读 `/sys/class/tshell/tshell_game`
（需 root 或 system uid 有 SELinux 许可，⚠️）→ `dumpsys thermalservice`（app uid 可读，但只有分类温度）。

### 4.3 Q2 旁路三方案（分层伪代码，标注首选/兜底）

```kotlin
// ── 方案 A（首选，低风险）：Hook 判定，不改任何系统状态 ─────────────────────
hook("com.vivo.common.e#b()D", AFTER) { ret ->
    if (!thermalBypass || !gameActive) return ret          // 非游戏/未开启 → 真实温度
    if (callerOfQ2Feature()) Policy.fakeTempC else ret      // 只对 Q2 调用方放行（§6.3 精准 Hook）
}
hook("com.vivo.gameassistant.frameinterpolation.h#G0(D)Z", AFTER) { _ ->
    if (thermalBypass) false else original()                // 永不判过热
}
// 阈值侧（可选，替代温度伪造）：hook z0()/a1() 后强制写入 mOverheatThreshold = 55.0
// 优点：不改温度，只改阈值，副作用最小；缺点：要逐控制器挂（插帧/超分各一处）

// ── 方案 B（兜底，中风险）：系统级温控覆盖 + 回写监控 ───────────────────────
// B1: cmd thermalservice override-status 0   → 只影响 ThermalStatus，对 Q2 门控无效（结论见方案 A §6.2）
// B2: 写 /sys/class/tshell/tshell_game        → 需 root；是否被驱动周期刷新 ⚠️ 未实测
//     守护：1 s 心跳重写 + 每次写后回读；关闭开关立即停止并恢复原值（原值不可备份 → 只能停止写入）
// B3: 抬 passive trip_point                   → 解 CPU/GPU 降频，不解 Q2 门控

// ── 方案 C（Q2 定向，需先验证存在性）───────────────────────────────────────
// 本机未发现 vendor.q2.*；已知只有 vivo.hardware.game.memc(HAL)、sys.game.memc.sw.enable、
// pq_memc_sr_delay_off。→ 只有在你实测发现"某个 vendor 属性 = Q2 总闸"之后才启用本方案。
```

**决策树**：`OEM 属性存在？` 是 → C 优先；否 → A（温度源 Hook）+ 阈值 Hook 双保险；A 失效（Hook 点被 OTA 改名）→ B3 顶着，同时打日志提示。

### 4.4 与云控伪造的联动

方案 A §2.0 的结论是"云控不参与 Q2 降频"，所以这里的联动**不是必需的**，只需保证不互相打架：

| 开关状态 | 云控层动作 | 理由 |
|---|---|---|
| 温控解除 ON | **无需**放宽云控字段（本来就没管温度） | 避免"为了联动而动云控"，增加风险 |
| 若用户额外开了"云控冻结" | abe 读侧返回快照 | 与温控开关解耦，独立开关 |
| 温控解除 OFF | 不做任何恢复动作（L1 Hook 无持久副作用） | 进程内 Hook 关闭即复原 |

> 与任务书原文的差异要明确写出来：原文要求"温控解除开启时云控伪造层同步放宽温度字段"。
> **本机证据（方案 A §2.0/§3.5）表明 gamecube 的温度阈值来自 `/system/etc/gamecube/*.xml`，不来自云控**，
> 所以这条联动在本机型上属于"空操作"。保留接口，但不实现联动，避免制造虚假的功能依赖。

---

## 模块五：行为对齐 vs 代码原创

### 5.1 对照清单（允许 / 禁止）

| 可以对齐的"行为信号" | 绝对不可做的"代码操作" |
|---|---|
| "解除温控"的目标 = 抑制温控对性能策略的干预 | 反编译目标软件/HyperCeiler 提取 smali 嵌入本项目 |
| "全局插帧"的目标 = 让不支持高帧的场景跑高帧 | 复制其 Java/Kotlin 源文件改名使用 |
| "直驱供电"的目标 = 边充边玩切换供电路径 | 原样移植其 Hook 方法签名与返回值伪造方式 |
| "系统更新屏蔽"的目标 = 阻止 OTA 检查与下载 | 复用其资源命名空间/类名作为本项目的"证据链" |
| 其"某方向可行"的结论可以作为线索 | 把其字符串/常量表直接当作我们的目标清单 |

**判定标准（我自己的执行口径）**：每一个 Hook 点必须能回答"我是从哪个 APK 的哪个类/字符串/资源 ID 推出来的"。
答不出就是搬运，不是逆向。本文所有 `✅` 项都能在方案 A 的证据索引里指到具体命令输出。

### 5.2 逆向描述模板（按计划功能逐个填；空栏=尚未逆向）

> 模板字段就是你要求的六项。**已填的部分是本机实证，空着的是必须真机补的**——不填不许进排期。

#### T1 温控解除（总开关）✅ 已填
```
目标类/进程 : com.vivo.common.e（gamecube 全进程）+ frameinterpolation.h（:assistantui 优先）
目标方法    : e#b()D（温度源：读 /sys/class/tshell/tshell_game）；h#G0(D)Z（过热判定）
Hook 时机   : AFTER（返回值改写）
预期返回    : b() → 35.0；G0() → false；仅对 Q2 调用方放行（栈判定）
回写风险    : 无（进程内）；关闭开关即恢复
独立依据    : 方案A §6.1/§6.3；实测 tshell_game="46751,46751,46751"
```

#### T2 Q2 三件套独立豁免 ✅ 已填
```
目标类      : frameinterpolation.h（插帧）、superresolution.j（超分）、displayenhance.c/f（视效）
目标字段    : h.f:D / j.g:D / j.i:D（阈值）；或统一在 e#b() 出口按调用方分流
Hook 时机   : AFTER 字段写入（z0()/a1() 之后）
预期值      : 55.0（阈值）或 35.0（伪温度）
回写风险    : 无
独立依据    : 七个控制器的调用点已由调用图实证（方案A §6.1 表）
```

#### T3 CPU 大核频率与 governor 🟡 节点已确认，策略待定
```
目标进程    : root 守护（非 LSPosed）
目标节点    : /sys/devices/system/cpu/cpufreq/policy6/{scaling_max_freq,scaling_governor}
预期值      : max = cpuinfo_max_freq(4320000)；governor = performance
禁止项      : scaling_min_freq（锁死高频 → 过热保护）
回写风险    : thermal-engine 可能改写 max_freq（⚠️ 未实测，需回读观测）
独立依据    : 方案A §3.3/§3.4；节点权限实测 system:system rw-rw-r--
```

#### T4 核心调度重排 🟡 节点已确认，语义待实验
```
目标节点    : /sys/devices/system/cpu/cpu*/core_ctl/{max_cpus,min_cpus,active_cpus,busy_up_thres,enable}
本机实测    : cpu0 簇 max=6 min=4 active=5；cpu6 簇 max=2 min=0 active=1；两簇 enable=1
预期值      : 例 cpu6.min_cpus=2（大核常驻双核），cpu6.max_cpus 保持 2
回写风险    : 内核 core_ctl 自行调度，写入是"上限"不是"强制"，通常不会被回写
独立依据    : 本机 ls 实测节点齐全；语义需读内核文档/DTS
```

#### T5 GPU 高频锁定 🟡（注意：**超频不可行**）
```
目标节点    : /sys/class/kgsl/kgsl-3d0/{min_pwrlevel,max_pwrlevel,clock_mhz,gpu_available_frequencies}
本机实测    : 14 档频率，最高 1100000000（1.1 GHz）、最低 160000000；当前 clock_mhz=525
              min_pwrlevel=13、max_pwrlevel=0
结论        : 只能"锁高频"，**没有超频接口**（频率表由 GPU 固件给定）。
              写 min_pwrlevel=0 后若 clock_mhz 升到 1100，即证明它是"性能下限"；否则改试 max_pwrlevel
回写风险    : kgsl 热限会抬 min_pwrlevel（审计已见 12→13 漂移），需回读监控
独立依据    : devfreq/3d00000.qcom,kgsl-3d0 与上述节点实测
```

#### T6 全局插帧 🟡 参数表已确认，跨 App 分发点待逆向
```
目标类      : frameinterpolation.h（控制器）+ FrameInterConfigList.xml（每游戏参数）
目标节点    : /system/etc/gamecube/FrameInterConfigList.xml（只读，需 hook 解析结果，不可改文件）
预期        : 对白名单外 App 也返回"支持插帧"；帧率上限抬到 120/144
回写风险    : 无（解析结果在内存）
独立依据    : 配置实测（v11，含 boostFrameValues/optiPowerValues）
待补        : "哪些 App 允许插帧"的分发判定点（`game_memc_pp_apps`、`game_memc_vsr_mutex_apps` 字符串是线索）
```

#### T7 直驱供电强开 🟡 属性已确认
```
目标属性    : persist.vivo.game_bypass_charge_flag（当前 0）、persist.vivo.game_bypass_charge_support=1
目标类      : gamecube 内 bypass charge 相关类（字符串 persist.vivo.game_bypass_charge_flag）
Hook/写入   : 属性写 1（进程内 hooked 读 + root 侧 setprop 双保险）
回写风险    : 充电策略由内核/充电 IC 决定，属性只是"请求"，⚠️ 是否真切换需实测（看 dumpsys battery 的充电电流）
独立依据    : getprop 实测三条属性存在
```

#### T8 自定义分辨率/显示 Mode 锁定 🟡 模式表已确认
```
目标节点    : /sys/class/drm/card0-DSI-1/modes（只读，列出 1440x3168x120/144、1080x2376x120/144）
目标接口    : dumpsys display 的 mode id（本机 mActiveModeId=1）+ vivo RMS（sys.rms.is_supported=true）
预期        : 锁定 1080x2376@120（Mode 4 之类）
待补        : "以程序方式设 mode"的入口（SurfaceFlinger / RMS 私有接口 / settings 键）
独立依据    : modes 与 dumpsys display 实测；RMS 属性实测
```

#### T9 温控节点写入 + 回写拦截 🟡
```
目标节点    : /sys/class/thermal/thermal_zone*/trip_point_*_temp（**按 type 反查**，禁止写死索引）
              /sys/class/thermal/cooling_device*/cur_state
预期        : 抬 passive 档 5–10 ℃，hot/critical 不动
回写风险    : thermal-engine 周期重算 cooling device（⚠️ 频率未实测）
独立依据    : 方案A §3.0（跨重启序号变化的实证）/§3.3
```

#### T10 性能模式切换（Monster/均衡/省电）🟡
```
目标类      : homegui/sideslide/panels/performance/modeshift/ModeShiftBar + ModeType
目标点      : 模式切换的写入端（GameSettingsProvider / DaemonProvider / SpProvider）
预期        : 直接写设置，绕过 UI 点击
待补        : 具体键名（读 providers 的 uri + 值域）
独立依据    : 类名与 provider 实测
```

#### T11 侧边栏条目注入 ✅
```
目标类      : EdgeFunctionsAdapter（+ BaseQuickAdapter.onBindViewHolder 三参）
Hook 时机   : getItemCount AFTER(+1)；onBindViewHolder BEFORE（拦截越界位）
预期        : 列表末尾出现"温控解除"条目
回写风险    : 无；若 OTA 改类名 → 存在性探测失败 → 静默不注入
独立依据    : 方案A §5.3
```

#### T12 游戏内性能面板扩展 ✅
```
目标类      : PerformancePanelPanelView（容器 id 0x7f0a0715）/ PhoneStatusPanelLayout
Hook 时机   : onAttachedToWindow AFTER
预期        : 面板底部出现"当前温度/频率/Q2 状态"一行
回写风险    : 无（幂等 + tag 去重）
独立依据    : 布局与 id 实测（方案A §5.2）
```

#### T13 OEM daemon 白名单注入 🟡
```
目标类      : com.vivo.gamedaemon.*（GameSettingsProvider/DaemonProvider/SpProvider）
目标        : 把我们关心的游戏/设置写进"不被守护覆盖"的白名单
待补        : 白名单键名与格式（读 provider 的 query 结果）
独立依据    : provider 与进程实测（manifest）
```

#### T14 状态栏图标隐藏 / 时钟到秒 ✅ 键名实锤，效果待验
```
目标        : Settings.System.icon_blacklist / clock_seconds / show_seconds
              （SystemUI dex 中三个字符串实测存在）
Hook        : 无需 Hook，直接 settings put（root）或模块内 SettingsBinder
预期        : 图标隐藏、时间带秒
回写风险    : 无；⚠️ vivo 是否完全沿用 AOSP 语义需改一次看效果
独立依据    : SystemUI dex strings 实测；vivo 侧类 VivoNotificationIconsInteractor（图标过滤）
```

#### T15 原子岛展示位置/优先级 ⚠️ 字符串实锤，落点未定位
```
目标        : com.android.systemui（ISLAND_SHOW/ISLAND_HIDE/ISLAND_CARD_SHOW/TRANS_ISLAND_*、
              vivo_island_status_right_seat_text）
待补        : 广播接收者类与"位置/座位"字段的写入点
风险        : 中（SystemUI 崩溃 = 状态栏消失，需 `killall com.android.systemui` 恢复）
独立依据    : dex strings 实测
```

#### T16 系统更新屏蔽（含守护对抗）✅ 组件实锤，回写待验
```
目标        : com.bbk.updater（INSTALL_PACKAGES/INSTALL_SELF_UPDATES/REBOOT/WRITE_SECURE_SETTINGS）
              com.vivo.cota（COTASERVICE）
              ★ updater 依赖 com.vivo.abe.unifiedconfig/configlist → 必须同时冻 abe 读侧
做法        : pm disable-user --user 0 com.bbk.updater（可逆）+ abe D3 读侧冻结 + cota 冻结
守护对抗    : com.iqoo.secure（字符串 setDaemon）是否拉回 → ⚠️ 需实测 pm list packages -d 观察 24h
独立依据    : aapt badging + dex strings 实测
```

#### T17 营销通知总控 🟡
```
目标        : NotificationManagerService（system_server，高风险）
替代方案    : 用 vivo 自有名单 gamecube_block_notification_blacklist（SystemUI dex 实测字符串）
              + 应用级 NotificationChannel 关闭（无需 system_server）
风险        : 高（system_server 禁用）
独立依据    : 字符串实测
```

#### T18 温控诊断 / 设备信息面板 ✅ 纯读，零风险
```
数据源      : dumpsys thermalservice / sysfs thermal（按 type）/ cpufreq / kgsl / /proc/meminfo
实现        : 模块内直接读（root 守护侧汇总，UI 侧订阅）
独立依据    : 方案A 附录 C 的速查命令全部实测可用
```

#### T19 无线 ADB / DPI ✅ 命令级
```
无线 ADB    : settings put global adb_wifi_enabled 1（另需 adb tcpip 或 配对流程）
DPI         : wm density <n>（可逆：wm density reset）
独立依据    : Android 原生能力；本机 root 可执行
```

#### T20 按键映射 ⚠️ 高风险
```
目标        : PhoneWindowManager（system_server）
结论        : 不进排期（风险等级=高，收益低）。若要做，改为"游戏手柄映射"（gamecube 域内，低风险）
```

---

## 模块六：功能排期表

### 6.1 MVP（三项）

| # | 功能 | 作用域 / 进程 | 二级域 | hookType | 风险 | 依赖的逆向结论 | 验收 |
|---|---|---|---|---|---|---|---|
| M1 | **侧边栏"温控解除"+ Q2 旁路** | `com.vivo.gamecube` / `:assistantui` | 游戏与侧边栏 + 性能与温控 | METHOD | 低 | T1/T2/T11 已定位 → 方案 A §5.3/§6.3 | logcat 无 `Overheat, try to close frame interpolation.`；高温下插帧仍开 |
| M2 | **空壳 UI + 分类体系 + FeatureRegistry** | 模块自身 APK | 全部 | UI | 低 | 模块一分类体系 + §3.4 接口 | 8 个域可见，开关可持久化，状态回读正确 |
| M3 | **安全闸门 + 一键恢复 + 温控诊断** | 模块 + root 守护 | 设备与调试 | SYSFS/SETTINGS | 低 | T9/T18 + 方案 A §3.6 | "恢复默认"后所有节点回到基线（有 diff 报告） |

选它们的理由：M1 是唯一"确定有效"的价值点；M2 是全部功能的地基（没有 Registry，后面每加一个功能都要重写 UI）；
M3 是"敢给人用"的前提（可回滚 + 可诊断），且成本最低（纯读 + 一套备份脚本）。

### 6.2 第二阶段（五项，覆盖任务书要求的各方向）

| # | 功能 | 方向 | 作用域 | hookType | 风险 | 依赖 |
|---|---|---|---|---|---|---|
| S1 | **直驱供电强开**（T7） | 性能与温控 | `com.vivo.gamecube` + sysprop | SYSPROP | 中 | 属性已确认；需实测充电电流是否变化 |
| S2 | **核心调度重排**（T4） | 性能与温控 | root 守护 → core_ctl | SYSFS | 中 | 节点已确认；需实验 max_cpus 语义 |
| S3 | **状态栏图标管理 + 时钟到秒**（T14） | 界面与交互 | `com.android.systemui` + settings | SETTINGS | 低 | 键名已实锤；需验 vivo 是否沿用 |
| S4 | **系统更新屏蔽**（T16） | 系统更新与云控 | `com.bbk.updater`/`com.vivo.cota`/`com.vivo.abe` | SETTINGS/METHOD | 中高 | 组件与耦合已确认；需 24h 观察 `com.iqoo.secure` 是否拉回 |
| S5 | **设备信息面板 + 温控诊断输出**（T18） | 设备与调试 | 纯读 | — | 低 | 已在 M3 打底，这里做完整版 |

### 6.3 后续扩展（按"收益/风险比"排序，不排期）

| 功能 | 方向 | 风险 | 先决条件 |
|---|---|---|---|
| 显示 Mode 锁定（T8） | 性能与交互 | 中 | 找到"程序化设 mode"的入口（RMS/私有接口） |
| GPU 高频锁定（T5） | 性能 | 中 | 只需 min_pwrlevel（低风险），可与 S2 合并 |
| 全局插帧（T6） | 游戏 | 中 | 定位"哪些 App 允许插帧"的分发点 |
| 原子岛位置/优先级（T15） | 原子通知 | 中 | 定位广播接收者与座位字段 |
| 营销通知总控（T17） | 通知 | 高 | 优先走 vivo 自有名单，避免动 system_server |
| 充电动画样式 | 界面 | 中低 | 定位 SystemUI 充电动画资源 |
| 无线 ADB / DPI（T19） | 设备 | 低 | 无（随时可做） |
| 云控伪造下发（方案 A 模块二） | 系统更新与云控 | 中高 | ⚠️ 回执写入点未定位；收益低（不降频） |
| 按键映射（T20） | 设备 | 高 | 建议改做"手柄映射"，走 gamecube 域 |
| 应用降级安装/签名放宽 | 系统框架 | 极高 | **建议放弃**（破坏系统完整性，且与安全目标冲突） |

---

## 七、真机验证最小步骤清单

> 从"反编译 gamecube"走到"侧边栏出现温控解除、Q2 在高温下仍可用"。
> 每一步都给了**预期现象**与**回退动作**；回退动作全部实测可用（root 环境，`su -c '/system/bin/sh -c ...'`）。

### V0 先做基线快照（不做这步不许往下走）

> ⚠️ 写法坑：`su -c` 会继承 Termux 的 PATH，直接 `cat` 会 `CANNOT LINK EXECUTABLE`。
> **每条命令先 `export PATH=/system/bin:/vendor/bin:/product/bin;`**，下面全部按这个写法，已实测。

```bash
B=/data/adb/fuckorigin/baseline
su -c '/system/bin/sh -c "export PATH=/system/bin:/vendor/bin; mkdir -p '"$B"'"'

# ① CPU 频率/调度（policy0 / policy6）→ 存基线
su -c '/system/bin/sh -c "export PATH=/system/bin; for p in policy0 policy6; do \
  b=/sys/devices/system/cpu/cpufreq/\$p; \
  echo \"\$p max=\$(cat \$b/scaling_max_freq) min=\$(cat \$b/scaling_min_freq) gov=\$(cat \$b/scaling_governor)\"; \
  done"'

# ② GPU（本机实测：14 档，最高 1100000000，当前 525；min_pwrlevel=13 max_pwrlevel=0）
su -c '/system/bin/sh -c "export PATH=/system/bin; \
  echo min_pwrlevel=\$(cat /sys/class/kgsl/kgsl-3d0/min_pwrlevel) \
       max_pwrlevel=\$(cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel) \
       clock=\$(cat /sys/class/kgsl/kgsl-3d0/clock_mhz)"'

# ③ core_ctl（本机实测：cpu0 簇 max=6/min=4/active=5；cpu6 簇 max=2/min=0/active=1）
su -c '/system/bin/sh -c "export PATH=/system/bin; for d in /sys/devices/system/cpu/cpu*/core_ctl; do \
  echo \"\$d max=\$(cat \$d/max_cpus) min=\$(cat \$d/min_cpus) active=\$(cat \$d/active_cpus) en=\$(cat \$d/enable)\"; \
  done"'

# ④ 温区（按 type 记，不按索引）→ 存文件
su -c '/system/bin/sh -c "export PATH=/system/bin; i=0; while [ \$i -lt 95 ]; do \
  t=/sys/class/thermal/thermal_zone\$i; ty=\$(cat \$t/type 2>/dev/null); \
  [ -n \"\$ty\" ] && echo \"\$ty \$i \$(cat \$t/trip_point_0_temp 2>/dev/null)\"; i=\$((i+1)); \
  done" > '"$B"'/zones.txt'

# ⑤ settings 与属性
su -c '/system/bin/sh -c "export PATH=/system/bin; settings get system clock_seconds; \
  settings get system icon_blacklist; getprop persist.vivo.game_bypass_charge_flag"'

# ⑥ 温度观测量（tshell_game 无法"备份原值"——写它即改，这里只记录观测值）
su -c '/system/bin/sh -c "export PATH=/system/bin; cat /sys/class/tshell/tshell_game; \
  cat /sys/class/tshell/tshell_shell"'
```

**预期现象**：全部命令有输出、无报错；`zones.txt` 里能看到 `gpuss-*`、`cpu-*-*-*`、`tz_shell` 等 type。
**回退**：这是只读步骤，无需回退；但 `zones.txt` 必须留到验收结束。

### V1 反编译/索引 gamecube，确认三件事

```bash
unzip -l /system/app/GameCube/GameCube.apk | head          # ① 三个 dex + resources.arsc
aapt dump badging /system/app/GameCube/GameCube.apk | head -3   # ② 版本 14.0.13.02_de
# ③ 类与资源存在性（用方案 A 的工具）
python3 工具/dx.py q "select cls from classes where cls like '%EdgeFunctionsAdapter%'"
grep -E "layout	performance_panel_layout|layout	edge_function_item_layout" 资源/索引/filemap.tsv
```

**预期现象**：三行都能命中；资源 id 分别是 `0x7f0d0226` / `0x7f0d008a`。
**若失败**：说明 OTA 改版 → 走方案 A §5.5「锚点定位法」重找，**不要硬编码旧 id**。回退：无（只读）。

### V2 确认温度链路真的在用 tshell

```bash
# 起游戏（或让其在前台），然后：
adb logcat -c && adb logcat -s FrameInterpolationController:V GameSuperResolutionController:V \
  | grep -E "mOverheatThreshold|Overheat|isOverheat|temperature"
su -c '/system/bin/sh -c "cat /sys/class/tshell/tshell_game"'
```

**预期现象**：出现 `mOverheatThreshold = 48.0` 一类日志；tshell 值在 40–50 ℃ 区间随负载变化。
**若失败**：日志 tag 改了 → 用 `adb logcat | grep -iE "overheat|threshold"` 泛搜；
仍无 → 说明该机型走的是 `e.c(Context)`（带埋点）那条路，改挂 `c`。**回退：只读，无需回退。**

### V3 属性通道实验（决定 P0 走 Hook 还是走属性）

```bash
adb shell setprop persist.sys.games.test.overheat_threshold 55
# 重进游戏，看日志是否变成 55.0
adb shell setprop persist.sys.games.test.overheat_threshold ""     # 回退
```
**预期现象**：若日志变 55.0 → 属性通道可用（模块直接 setprop 即可，最省事）；不变 → 走字段改写。
**回退**：`setprop ... ""` 置空（本机默认就是空值，实测过）。

### V4 只读 Hook：先证明"能命中"，不改任何东西

```kotlin
// ThermalProbe.kt —— 只打日志
hook("com.vivo.common.e#b()D", AFTER) { ret -> Log.i("FO", "temp=$ret caller=${topCaller()}"); ret }
hook("com.vivo.gameassistant.frameinterpolation.h#G0(D)Z", AFTER) { ret -> Log.i("FO", "G0(${args[0]})=$ret") }
```
```bash
adb logcat -s FO:V | head -30
```
**预期现象**：游戏运行中出现成对日志（温度 + 判定），说明 Hook 命中且**进程选对了**（若是 `:assistantui`，日志会带该 pid）。
**若失败**：`logcat -p <pid>` 确认进程；类找不到 → 走存在性探测分支（不要抛异常）。
**回退**：关闭模块开关（进程内 Hook 随进程退出彻底消失）。

### V5 小范围生效：只改插帧一个功能

```kotlin
hook("com.vivo.gameassistant.frameinterpolation.h#G0(D)Z", AFTER) { if (on) false else it }
```
```bash
adb logcat -s FrameInterpolationController:V | grep -c "Overheat, try to close"
```
**预期现象**：高温下计数不再增长；插帧开关保持开启。
**回退**：关开关；如仍异常 → 强制停游戏进程并重启游戏（`am force-stop <game>`），插帧状态会重建。

### V6 侧边栏注入：只加空条目（先不接任何策略）

```kotlin
// getItemCount()+1 + 三参 onBindViewHolder 越界位绑定（方案A §5.3）
```
**预期现象**：侧边栏快捷开关列表末尾出现"温控解除"条目（点击暂时只弹 Toast）。
**若失败**：条目数不对/列表错位 → 立即回滚 `getItemCount` Hook（这是唯一可能让原生列表"看起来坏掉"的点）。
**回退**：关闭该功能开关 → 列表恢复；若注入造成崩溃，`am force-stop com.vivo.gamecube` 后重进游戏。

### V7 条目接上真实开关 + 详情页

**预期现象**：点击进入详情页，能看到实时温度（与 `tshell_game` 换算一致）、总开关、三个分项、恢复默认。
**回退**：详情页崩了不影响 gamecube（独立 Activity）；关闭模块即可。

### V8 高温场景验证（关键验收）

```bash
# 制造高温：120W 充电 + 重负载游戏 10–20 分钟，观察
adb logcat -s FrameInterpolationController:V GameSuperResolutionController:V | grep -E "Overheat|threshold"
su -c '/system/bin/sh -c "cat /sys/class/tshell/tshell_game; dumpsys thermalservice | grep -m1 \"Thermal Status\""'
```
**预期现象**：温度越 48 ℃ 时，原生逻辑会打印 `Overheat, try to close...`；开启我们的开关后该行为消失、
Q2 面板显示功能仍开启。
**回退（最重要的一条）**：立即关模块开关 → 拔掉充电器 → 静置降温；
若出现异常发热/自动关机，执行 V0 的恢复命令并重启设备。

### V9 全量回滚并验证

```bash
# CPU 恢复（注意：恢复的是"原生由温控压低后的值"，不是 cpuinfo_max_freq）
su -c '/system/bin/sh -c "export PATH=/system/bin; for p in policy0 policy6; do \
  b=/sys/devices/system/cpu/cpufreq/\$p; cat \$b/cpuinfo_max_freq > \$b/scaling_max_freq; \
  echo walt > \$b/scaling_governor; done"'

# GPU 恢复（min_pwrlevel 回到基线，本机基线是 13）
su -c '/system/bin/sh -c "export PATH=/system/bin; echo 13 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel"'

# core_ctl 恢复（用 V0 记录值：cpu6.min_cpus=0 / cpu0.min_cpus=4）
su -c '/system/bin/sh -c "export PATH=/system/bin; echo 0 > /sys/devices/system/cpu/cpu6/core_ctl/min_cpus; \
  echo 4 > /sys/devices/system/cpu/cpu0/core_ctl/min_cpus"'

# settings 恢复
su -c '/system/bin/sh -c "export PATH=/system/bin; settings delete system clock_seconds; \
  settings delete system icon_blacklist"'

# 属性恢复
su -c '/system/bin/sh -c "export PATH=/system/bin; setprop persist.vivo.game_bypass_charge_flag 0"'

# 停用项恢复（若做过系统更新屏蔽）
su -c '/system/bin/sh -c "export PATH=/system/bin; pm enable com.bbk.updater; pm enable com.vivo.cota"'
```

**预期现象**：`scaling_max_freq` 回到被温控压低的值（例如 1555200，而不是 4320000），说明恢复成功；
`dumpsys thermalservice` 的 Thermal Status 回到原生档位。
**验证**：把 V0 的基线打印与当前值逐项 diff，必须一致（tshell 除外）。

### 验证清单总表

| 步骤 | 目的 | 可回退 | 阻塞后续 |
|---|---|---|---|
| V0 基线快照 | 保证能恢复 | 无需 | **是** |
| V1 反编译确认 | 锚点是否仍有效 | 无需 | 是 |
| V2 温度链路 | 证明链路在用 | 无需 | 是 |
| V3 属性通道 | 选实现路线 | 是（置空） | 否 |
| V4 只读 Hook | 证明能命中 | 是（关开关） | 是 |
| V5 单功能生效 | 最小可用 | 是 | 是 |
| V6 侧边栏注入 | UI 可见 | 是 | 否 |
| V7 详情页 | 可操作 | 是 | 否 |
| V8 高温验收 | 真实效果 | 是（关开关 + 拔电） | — |
| V9 全量回滚 | 交付前提 | — | — |

---

## 附录 A：本文档与方案 A 的对应关系

| 本文引用 | 方案 A 对应章节 |
|---|---|
| 温度源/阈值/七个控制器 | §6.0–§6.3 |
| 侧边栏宿主/视图树/注入代码 | §5.2–§5.3 |
| 资源 ID 清单（图标/动效/色值） | §7.4–§7.5 |
| 云控四段链路与 DB schema | §2.2–§2.3 |
| sysfs 写入权限与 zone 反查 | §3.2–§3.4 |
| 可信度标注与未验证清单 | §0.3、附录 D |

## 附录 B：回滚速度对照

| 改动类型 | 恢复方式 | 恢复耗时 | 是否需要重启 |
|---|---|---|---|
| 进程内 Hook（T1/T2/T11/T12） | 关开关 / 杀进程 | 立即 | 否 |
| sysfs 写入（T3/T4/T5/T9） | 按基线回写 | 1 命令 | 否 |
| settings 键（T14） | `settings delete` | 1 命令 | 否（状态栏会自刷新） |
| 系统属性（T7） | `setprop` 回原值 | 1 命令 | 否 |
| 停用包（T16） | `pm enable` | 1 命令 | 否 |
| 空壳 UI（模块自身） | 卸载模块 APK | 1 步 | 否 |
| L3 屏蔽厂商温控配置（未进 MVP） | 模块内保留的原始文件还原 + 重启 | 分钟级 | **是** |

## 附录 C：合规与分发（务实版）

- **轨道 A（第三方空壳）**：UI 资源来自他人闭源 App，公开分发前需自行评估版权与"实质性替代"风险；
  本文只提供技术流水线，不代为判断。默认场景是个人测试环境（与你在任务书里写的边界一致）。
- **轨道 B（OriginOS 原生壳）**：资源来自用户自己设备的系统分区，无第三方作品复制问题；**工程上也更稳**
  （资源 ID/名字已实测）。本文把它列为推荐并行路线（§3.6）。
- **功能侧**：所有 Hook 目标均来自本机 APK/系统节点的独立逆向（证据见方案 A 附录 A），未引用 HyperCeiler
  或任何第三方工具的代码、类名或方法签名。

---

*文档结束。与方案 A 一样：所有 ✅ 项可在真机复现，⚠️ 项必须实验后再进排期。*
