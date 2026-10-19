# FuckOrigin 0.2.0：UI 重构与功能补齐

> 本轮两件事：**UI 密排化（多个选项共用一张卡）** 与 **功能补齐（监控/阈值/PEM 实验/Hook 状态）**。

## 1. UI 布局：一张卡装多个选项

改版后的首页结构（对照截图 `日志/界面-0.2-快捷开关卡.png`）：

```
FuckOrigin（大标题）/ 副标题
[搜索]                      → 有输入时切换为平铺搜索结果（一张卡装完，带域标签）
[橙色环境提示卡]              → 无 root / 无 LSPosed / Hook 未生效时出现，逐条列出受影响的功能
[Tip 卡]
[快捷开关]                   ← 一张卡 4 行：温控解除 / CPU 顶频 / GPU 锁最高档 / 系统更新冻结
[性能与温控 0/9]             ← 每域一张卡：最多 3 行 + 末尾「查看全部 N 项」
[游戏与侧边栏 …] …
底部三标签（主页/设置/关于）
```

具体改动：
- **快捷开关卡**：把 4 个高频开关收进同一张卡，避免首页变成"一域一行"的长列表；
- **域卡片**：每域卡内最多 3 条（已在快捷卡出现的条目不重复），卡底固定一行「查看全部 N 项 →」；
- **搜索态**：不再分区显示，命中项平铺进一张卡，带「域 · 类型 · 风险」副标题；
- 每行加**状态圆点**（绿=已实现 / 蓝=已定位 / 黄=待实验 / 灰=待逆向）；
- 行与标签栏加**涟漪反馈**；底部栏上方加分割线；新增**夜间配色**（`values-night`）；
- 自绘 **MiuixSwitch** 取代框架 `Switch`（44×26dp 胶囊 + 22dp 滑块 + 180ms 缓动）。

## 2. 环境提示（无 root / LSPosed 未生效）

- 顶部**橙色横幅**逐条列出问题：
  - 没有 root → sysfs 写频 / PEM 实验 / 更新冻结不可用
  - 未检测到 LSPosed → Hook 类功能不会生效
  - LSPosed 已装但无 Hook 标记 → 提示检查模块启用、作用域勾选与重启游戏魔盒
- 同时弹**一次性对话框**（同一环境组合只弹一次，记在 SP 里），带「去诊断」入口。

检测口径：`pm list packages | grep lsposed`（已安装）＋ `/data/local/tmp/fuckorigin_hook_alive`
（由 `HookEntry` 在 `handleLoadPackage` 时写入 `包名|进程|时间戳`）——后者是**真正的生效证据**，不是猜的。

## 3. 功能补齐（4 条新条目）

| featureId | 名称 | 类型 | 说明 |
|---|---|---|---|
| `diag.live` | 实时监控 | DIAG | 1 秒刷新：tshell 双温度 / PEM 三个节点 / 双簇 cpufreq / GPU clock+min_pwrlevel / core_ctl / 关键温区（按 type 反查）/ Hook 状态 |
| `q2.thresholds` | Q2 阈值表 | DIAG | 解析 `/system/etc/gamecube/SuperResolutionConfigList.xml`，逐游戏输出「过热/恢复/超分/state」 |
| `hook.status` | Hook 生效状态 | DIAG | 读标记文件并换算"最近 N 分钟前" |
| `perf.pem.thermal_mode` | PEM 温控模式（实验） | ROOT | 写 `/proc/pem/thml_type`，**强制安全闸门**：温度<40℃ 且未充电才可开；开启后 60 秒自动写回原值；原值存 SP |

PEM 这条是本轮审计挖出来的（`com.vivo.pem` 的 `ThermalTypeConfig#g(int)`），节点实测 `rw-rw-rw-`，
但**没有做写入实验**——当时设备 120W 充电 + 板温 49.4℃，属于最不该动的状态。

## 4. 已知问题

1. **底部导航留白**：该窗口 `getRootWindowInsets()` 返回的 `insets.bottom` 恒为 0，改用固定 36dp 兜底
   （截图验证时前台被其他应用占用，这一版**未取得最终截图**）；
2. `Live` 面板每 1 秒起一个 `su` 子进程，长期开着会有开销，建议只在诊断时打开；
3. 设置页的「备份/恢复模块配置」「语言」等行仍是占位（Toast 提示"待接入"）。


---

# 0.3.0：按 HyperCeiler 实际观感再调一轮

对照 HyperCeiler 截图逐项对齐（见 `日志/界面-0.3-主页.png`、`日志/界面-0.3-设置页.png`）：

| 项 | 0.2 | 0.3（对齐后） |
|---|---|---|
| 大标题 | 30sp **粗体** | **34sp 常规体**，上边距 18dp（与 HyperCeiler 一致） |
| 页面标题 | 设置/关于 30 粗，域详情 27 粗 | 统一：顶级页 34sp 常规、二级页 28sp 常规 |
| 搜索框 | 48dp 高 / 14sp | **52dp 高 / 15sp**，图标 20dp，左右 16dp |
| 卡片 | 18dp 圆角 + 0.5dp 描边 | **20dp 圆角、无描边**（更接近 miuix 的"纯白浮起"） |
| 行标题 | 16sp | **17sp**（HyperCeiler 为 18sp），副标题 12→**13sp**，行高 58dp |
| 分组标题 | 上 16dp | 上 **20dp**（留白更接近参考图） |
| 底部选中色 | 蓝 `#3482FF` | **黑（浅色）/ 白（夜间）**——对齐参考图的选中态 |
| 底部留白 | 28dp | **40dp**，创建时即写入 padding（该窗口 `insets.bottom` 恒为 0） |

**按功能分类保持不变**：首页仍是「快捷开关 → 8 个功能域（性能与温控 / 游戏与侧边栏 / 界面与交互 /
系统更新与云控 / 后台与功耗 / 通知与权限 / 原子通知与原子岛 / 设备与调试）」，
每域一张卡、卡内最多 3 条 + 「查看全部 N 项」。

**仍未解决**：`getRootWindowInsets()` 在该窗口返回 0，底部只能靠固定 40dp 兜底；
换设备/换导航模式（三键）时可能不是最优留白，后续应改用 `WindowInsetsController` 或补一次真机校准。


---

# 0.4.0：设置项补齐 + miuix 弹窗 + 底栏对齐 + 一页展示

## 1. 设置里的功能全部落地（不再是占位）

| 行 | 现在的行为 |
|---|---|
| 桌面图标 | 真开关：启用/禁用 `activity-alias LauncherAlias`（`DONT_KILL_APP`），桌面图标即时消失/出现 |
| 设置图标 | 单选（禁用 / 默认），存 `settings.icon` |
| 语言 | 单选（简体中文 / English），存 `settings.lang`（当前只切文案，未做完整 i18n） |
| 主页入口设置 | 单选（首页 / 上次页面），存 `settings.entry` |
| 备份模块配置 | 导出 JSON 到 `Android/data/com.fuckorigin.app/files/backup/config-<时间>.json` |
| 恢复模块配置 | 列出备份文件 → 点选恢复（开关 + 三个设置项） |
| 重置模块配置 | 清空本地开关与设置（不触碰系统状态，提示里写明了） |
| 一键恢复默认 | 原来的 sysfs/settings 回滚 |
| 设备诊断 | 单项诊断面板 |
| 清理临时文件 | 删除 `/data/local/tmp/fuckorigin_*` |

## 2. 弹窗改成 miuix 风格（自绘 `MDialog`）

- 24dp 圆角白卡、20sp 粗标题、14sp 次级正文、按钮右对齐（accent 色/次要灰）；
- 0.45 遮罩 + 透明窗口背景，宽度 = 屏宽 88%；
- 支持自定义内容区（诊断面板、备份列表、单选列表都复用它）；
- 全部 `AlertDialog` 已替换（环境提示、恢复默认确认、结果提示、诊断）。

## 3. 底栏对齐（真修了）

之前 `getRootWindowInsets()` 在该窗口恒返回 0。现在改用
`Display.getRealMetrics() - getMetrics()` 计算导航栏真实高度（px），
底栏高度 = 60dp + 导航栏高度，并把该高度作为底栏自身 paddingBottom。
截图 `日志/界面-0.4-设置页功能.png` 里三个标签完整可见、与 HyperCeiler 的留白一致。

## 4. 一页展示（按你的要求：几个功能合并在一个页面）

- 首页不再有「查看全部 → 二级页」：**每个功能域一张卡，卡内直接列全该域所有条目**，一页到底；
- 顶部保留「快捷开关」卡（温控解除 / CPU 顶频 / GPU 锁最高档 / 系统更新冻结）；
- 新增两个**合并开关**：
  - `perf.fullpower` 性能满血（合并 CPU 顶频 + performance 调度 + GPU 锁频 + 大核常驻）
  - `thermal.group` 温控解除（合并总开关 + 插帧/超分/视效三项豁免）
  卡内显示「合并 4 项 · 已开 N」，点开关级联控制子项。
- 调试入口：`am start -n com.fuckorigin.app/.MainActivity --ez settings true` 直接打开设置页（自动化截图用）。

## 5. 关于 KernelSU 的界面参考

拉取 `tiann/KernelSU` 后确认：**它的管理器 UI 是 Jetpack Compose**（`ui/screen`、`ui/theme`、`navigation3`），
本机的 aapt(1)+javac+d8 流水线无法链接 Compose，所以只能借鉴设计语言（大标题、卡片分组、间距），
代码层面无法复用。WebUI（`webroot/`）是可选的另一条路，但那是模块脚本的网页界面，不适合当原生壳。

## 6. 仍未验证

- **miuix 弹窗的实际观感**：截图时前台被视频/聊天应用反复抢占，`input tap` 打到了别的应用，
  没能抓到弹窗截图。代码路径已通（设置页 4 个单选行都会走 `MDialog`），但**没有眼见为实**。
- 桌面图标开关的实际效果（禁用后桌面图标应消失）未逐一验证。


---

# 0.5.0：底栏错位的真正原因

前几版一直在猜，这版加了埋点量数据：

```
FO_INSETS: cur=2976 real=3168 dpi=640        → 导航栏 = 3168-2976 = 192px = 48dp
FO_INSETS: rootH=2944 barTop=2856 barH=240
```

三个问题叠加：
1. **测量基准错**：我之前按 3 倍屏算坐标，实际 `dpi=640`（**4 倍**），所以所有 dp↔px 估算都偏；
2. **兜底值偏小**：`insets.bottom` 恒 0，兜底写死 40dp=160px，而实际导航栏是 192px，差 32px → 标签永远压在边缘；
3. **窗口被改成沉浸式**：中途调用过 `setDecorFitsSystemWindows(false)`，窗口铺到屏幕底部，底栏自然盖住手势条。

修法：
- 去掉 `setDecorFitsSystemWindows(false)`，恢复默认窗口适配；
- 底栏高度 = **60dp + 实测导航栏像素（192px）**，并把该值作为底栏 paddingBottom；
- 实测值来自 `getRealMetrics() - getMetrics()`，不再依赖 WindowInsets。

验证：`日志/界面-0.5-底栏对齐.png` 里三个标签完整、与屏幕底部保持 48dp 安全距离。

**遗留**：三键导航机型上 `real-cur` 会更大（三键栏 ~48dp+），此逻辑同样适用；
但若某些 ROM 返回 0，会回落到 48dp 常量。


---

# 0.6.0：底栏第三轮（含实测数据）

实测（`FO_INSETS`）：`dpi=640`（4 倍屏）、`cur=2976`、`real=3168` → **导航栏 192px = 48dp**；
窗口 frame 是 `[0,0][1440,3168]` 全屏（与 HyperCeiler 一致），所以必须自己让位。

本轮三处改动：
1. 底栏高度 = **56dp + 192px**，`paddingBottom = 192px`，白底一直铺到屏幕底（不再出现底部灰条）；
2. **列表内容底部留白 78dp**，最后一行不再被底栏压住（截图里"重置模块配置"不再被切）；
3. 底栏内部 padding 收到 4dp、item 上下 6/4dp，标签位置更贴近 HyperCeiler。

仍然存疑：如果你说的"错位"是指**图标与文字的垂直间距**或**三个标签的水平不居中**，
那这一版还没解决，需要你指一下具体是哪一个。


---

# 0.7.0：按你指出的两点修底栏

你说的是「文字和图片未对齐，底栏位置过高」，改了两处：

1. **对齐**：每个 tab 容器改为 `Gravity.CENTER_HORIZONTAL`，图标与文字都显式
   `gravity=CENTER_HORIZONTAL`；文字加 `setIncludeFontPadding(false)`（去掉字体自带的上下留白，
   这是"文字看着比图标偏下"的常见原因）；容器 `setBaselineAligned(false)`；图标 23→22dp、文字上边距 3dp。
2. **位置**：底栏内容高度 56→**52dp**，内部 `paddingBottom` 归零（原来 4dp 把内容又顶上去了一点），
   导航栏留白仍由外层的实测 192px 承担。

截图：`日志/界面-0.7-底栏对齐修正.png`。若仍觉得高，剩下的唯一杠杆是缩小导航栏留白（192px），
但那会重新压到手势条，不建议再动。


---

# 0.8.0：LSPosed 认不出的根因与修复

**根因**：`assets/` 目录是空的。当初写 `xposed_init` 时路径算错，文件落在了 `app/src/assets/`
（aapt 根本不看那里），所以 APK 里**没有** `assets/xposed_init`——
LSPosed 拿不到入口类清单，自然不把这个包认成 Xposed 模块。

用包内容核对：

```
修复前: unzip -l FuckOrigin.apk | grep assets   → 空
修复后: assets/xposed_init                       → com.fuckorigin.app.xposed.HookEntry
        META-INF/xposed/scope.list               → com.vivo.gamecube / com.android.systemui
```

**同时加了"推荐注入声明"**：
- `META-INF/xposed/scope.list`：LSPosed 1.8.3+ 读取，用于在模块页显示"推荐作用域"；
- `res/values/arrays.xml#xposed_scope`：老式 `xposedscope` 元数据指向的同一份清单；
- 两处都只留 `com.vivo.gamecube` + `com.android.systemui`（`com.vivo.abe` 已移除——
  我们目前没有对它的 Hook，留着只会让 LSPosed 白加载一次）。

**一处坑（已避开）**：先试过加 `META-INF/xposed/module.prop`，但那会让新版 LSPosed 按
"libxposed 新 API 模块"去加载，要求 `java_init.list` + `XposedModule` 基类；我们是老式
`IXposedHookLoadPackage`，加了反而更认不出。所以最终**只保留 legacy 元数据 + scope.list**。

**安装后要做的**：打开 LSPosed → 模块 → 应出现 `FuckOrigin`（标记 legacy）→ 勾选作用域 →
重启目标进程（游戏魔盒）或直接重启手机。标记文件 `/data/local/tmp/fuckorigin_hook_alive`
出现即说明 Hook 真的跑起来了（App 的「Hook 生效状态」页可查）。


---

# 0.9.0：LSPosed 已认出，但作用域没勾上（用配置库证实）

从 LSPosed 的配置库读到了真实状态（注意：**必须连 `-wal` 一起拷，否则看不到新写入**）：

```
modules      : com.fuckorigin.app  ✅ 已注册
modules_state: com.fuckorigin.app  enabled=1  ✅ 已启用
scope        : com.android.systemui            ← 只有这一个
               （com.vivo.gamecube 不在里面）
```

**关键误解**：LSPosed 页面里「游戏魔盒」右侧的蓝色对勾，是我们 `scope.list` 声明的**推荐应用**标记，
不代表"已选中"。所以模块虽然启用，但从未注入进 gamecube 进程 → `/data/local/tmp/fuckorigin_hook_alive`
一直不出现。

修法与提示：
1. 本版把 LSPosed 检测口径放宽为三种任一命中（模块目录 `/data/adb/modules/zygisk_lsposed`、
   `pm list packages | grep lsposed`、`ps -A | grep lsposed`），不会再误报"未检测到 LSPosed"；
2. 「Hook 生效状态」页写清了四步排查，并加了**「重启游戏魔盒（让 Hook 生效）」**按钮；
3. 正确操作：LSPosed → 模块 → FuckOrigin → **手动点选** 游戏魔盒那一行 → 回本页点重启 → 进任意游戏。


---

# 0.10.0：功能详情页深度重构

旧版详情页只有一张元数据卡 + 一个开关，信息密度太低；这版按"状态 → 证据 → 操作"重排：

| 区块 | 内容 |
|---|---|
| 标题区 | 功能名（26sp）+ **状态徽章**（已实现=绿 / 已定位=蓝 / 待实验=黄）+ 副标题（类型 · 风险 · 所属域） |
| 实时状态 | **Hook 注入状态**（读存活标记，未生效时直接给四步排查）+ **目标锚点检测**（在 GameCube.apk 的 dex 里查类描述符，结果缓存） |
| 开关 | 启用开关（miuix 自绘） |
| 操作 | 重启游戏魔盒 / 重新检测锚点（清缓存）/ 设备诊断 |
| 说明 | 该功能的逆向结论 + 目标签名 |

实现要点：
- 新增 `Anchor.java`：从 `feature.target` 里猜出类描述符（`com.vivo.common.e` → `Lcom/vivo/common/e;`），
  用 `su + unzip -p + strings + grep -c` 在 APK 里核实是否存在，结果写进 SP 缓存，避免每次扫 75MB；
- 页面切换加 **160ms 淡入 + 10dp 上移**转场；
- Toast 改为更靠上的位置（避免被底栏压住）。

**调试入口**（自动化截图/验证用）：
```
am start -n com.fuckorigin.app/.MainActivity --ez settings true      # 直达设置页
am start -n com.fuckorigin.app/.MainActivity --es feature <featureId> # 直达功能详情页
```

**未验证**：本版详情页没有取得截图——设备当时自动锁屏，无法解锁截图。
代码通过编译并安装成功，App 进程正常（pid 可查），但"页面上到底长什么样"没有眼见为实。

**注意**：中途为了点开功能页盲点过一次坐标，结果点在 LSPosed 页面上，
弹出了"由于某个应用遮挡了界面"的校验提示。该提示与本项目无关（LSPosed 的防遮挡校验），
我也没有改动 LSPosed 的任何设置。


---

# 0.11.0：为什么"还是不生效"——三层证据

## 一、模块没被注入（LSPosed 侧，需重启）

| 证据 | 结果 |
|---|---|
| LSPosed 配置库（含 `-wal`） | 模块已注册、已启用，作用域已含 `com.android.systemui` + `com.vivo.gamecube` ✅ |
| `/data/local/tmp/fuckorigin_hook_alive`（我们写的存活标记） | **不存在** |
| 重启 gamecube 后 `/proc/<pid>/maps` 里搜 `fuckorigin` | **0 条** |
| 杀掉重启 SystemUI（同样在作用域内）后 maps | **0 条** |
| 对照组：`com.tencent.wetype` 进程 maps 里搜 `wetypehook` | **2 条**（它是在开机前就启用的模块） |

→ 结论：LSPosed 的模块清单在**开机时加载**，我们是在开机后才启用的，
所以两个作用域进程都没有被注入。**必须重启手机**（不是重启应用）。

## 二、就算注入，超分还有一道"背夹 + Monster+"的闸

你发的两张图是决定性证据：Q 芯专区里「超级分辨率」子页写着
**"连接官方散热背夹并开启 Monster+ 模式下，功能可用"**。也就是说这里的限制**不是温度**，而是：
1. 官方散热背夹（蓝牙配件）已连接；
2. Monster+ 模式已开启。

本版已加第一条 hook：
- `com.vivo.gameassistant.entity.MonsterPlusEvent#isMonster()Z` → 恒 `true`
  （该类同时被 `superresolution.j`、`frameinterpolation.h`、`displayenhance.c`、`dc.d`、`SuperResolutionView` 消费，
  一处改写即可覆盖超分/插帧/视效的 Monster+ 判定）。
- 对应功能条目：`perf.monsterplus.force` 强制 Monster+ 模式。

**背夹那一条还没落点**：目前只找到 `modeshift/l$c#onServiceConnected`（蓝牙绑定回调，
日志 `initBlueToothConnect : back clip connected`）与 `ModeShiftBar#setBackClipPreventVCode`，
还没定位到"背夹是否连接"的布尔读取点。下一步应反汇编 `modeshift/l#n0()Z` / `l#Z(...)Z`
或 `ModeShiftBar` 的字段访问，找到那个 boolean。

## 三、操作建议

1. **重启手机**（LSPosed 才会把本模块加载进作用域进程）；
2. 开机后打开游戏魔盒 → 任意游戏 → 侧边栏；
3. 回到 App 的「Hook 生效状态」页，应显示"已生效：com.vivo.gamecube（进程 xxx，最近 N 分钟前）"；
4. 再验证超分：若仍提示"连接背夹"，就是上面第二条没做完的部分。

---

# 0.13.x：「还是不生效」的真正原因（已定位并修正）

前几版一直卡在"装好了、作用域也勾了、就是不生效"。这一轮把它拆到底了，
结论和前几版的猜测**都不一样**，逐条记下来。

## 一、模块其实一直被注入，是"生效检测"坏了

翻 LSPosed 自己生成的故障报告（`/storage/emulated/0/Download/LSPosed_crash_report*.zip`）
里的 `scopes.txt`，它是 LSPosedService 内存状态的直接快照：

```
com.android.systemui/10082            com.fuckorigin.app  com.fuckorigin.app.xposed.HookEntry
com.android.systemui:screenshot/10082 com.fuckorigin.app  com.fuckorigin.app.xposed.HookEntry
com.android.systemui:tuner/10082      com.fuckorigin.app  com.fuckorigin.app.xposed.HookEntry
com.vivo.gamecube/1000                com.fuckorigin.app  com.fuckorigin.app.xposed.HookEntry
com.vivo.gamecube:daemon/1000         com.fuckorigin.app  com.fuckorigin.app.xposed.HookEntry
com.vivo.gamecube:assistantui/1000    com.fuckorigin.app  com.fuckorigin.app.xposed.HookEntry
```

入口类都解析出来了。也就是说模块**确实被加载进了目标进程**，
而 App 却一直显示"未捕获到 Hook 标记"——因为标记机制本身是错的：

| 项目 | 事实 |
|---|---|
| 旧标记 | 被注入的进程往 `/data/local/tmp/fuckorigin_hook_alive` 写文件 |
| 该目录权限 | `drwxrwx--x shell shell`（实测 `ls -ld`） |
| 被注入进程身份 | SystemUI 与 gamecube 都是 **uid 1000**，既不是 owner 也不是 shell |
| 结果 | `FileOutputStream` 必然抛 `FileNotFoundException`，被 `catch (Throwable ignored)` 吞掉 |

→ 所以"未捕获标记"是**假阴性**。0.13.0 起改成 `Log.i("FuckOrigin", ...)` 写 logcat，
App 侧用 `su -c logcat -d -s FuckOrigin:I` 读回。任何 uid 都能写 logcat，这才是可信通道。

同一份报告里 `com.fuckorigin.app/10422` 也挂着自己的模块——LSPosed 会把它注入模块应用自身进程。

## 二、重装应用会让 LSPosed 的模块路径失效（这才是真的不注入）

0.13.0 装完那一刻注入是正常的（上面 scopes.txt 就是那时候的）。0.13.1 重装之后
SystemUI 就再也不加载了，maps 里 0 条、logcat 0 行、也没有崩溃报告。原因是：

- LSPosed 用 `modules_config.db` 里 `modules.apk_path` 定位模块；
- 每次 `pm install -r`，`/data/app/~~xxx~~` 目录哈希会变（实测三次重装换了三个路径）；
- 库里还记着旧路径 → 按路径加载失败 → **静默跳过，不写任何日志**。

`db_modules.txt` 里当时记录的是 `/data/app/~~1CytD8XmsRiQxahD0B3o1Q==/...`，
而实际 APK 已换成 `/data/app/~~9n3XkVTJf_fOe0Oav3pu9Q==/...`。

修法（0.13.2 起内置）：`LsposedFix.autoSync()` 在 App 每次启动时用 root
把 `modules.apk_path` 校正成 `getPackageCodePath()`。实测输出：

```
I FuckOrigin: PATH_SYNC|root=true|apk=/data/app/~~P0nLOOFy8s1OZI37-h7umg==/.../base.apk|模块路径已同步：…
```

两个坑顺带记下：① 普通 uid 连 `/data/adb` 都 stat 不到，`File.exists()` 恒 false，
必须用 `su -c '[ -f ... ]'` 判断；② 改库不会热生效，LSPosedService 的模块表是开机时缓存的。

## 三、Hook 签名错了整整一批（死代码）

拿 `gamecube.db` 反查每个 hook 目标，发现上一版有几个是**根本挂不上**的：

| 目标 | 上一版写的 | 真实签名 | 结果 |
|---|---|---|---|
| `superresolution.j#A0` | 实例 `A0()Z` | `static A0(Lj;)Z`（synthetic 访问器） | 静默 MISS |
| `superresolution.j#B0` | 实例 `B0(Z)Z` | `static B0(Lj;Z)Z` | 静默 MISS |
| `superresolution.j#m0` | `(Context,String,Z,Z)Z` | `static m0(Lj;Context,String,Z,Z)Z` | 静默 MISS |
| `EdgeFunctionsAdapter#getItemCount` | 计数 +1 | 该类自身没有该方法，继承自 BRVAH `BaseQuickAdapter` | 已撤回 |

最后一条要特别说明：`BaseQuickAdapter` 取数走 `data.get(position)`，
凭空把 count +1 会让 `onBindViewHolder` 越界**直接崩溃**，不能算实现。功能状态已改为"未实现"。

现在 `hookOn()` 会把每条 `OK` / `MISS` 连同异常文本写进 logcat，
App 的「Hook 状态」页直接显示"已挂上 N 条 / 未匹配 M 条"，不会再靠猜。

## 四、超分/超帧的门控链已完整逆出来（本轮主要成果）

`com.vivo.gameassistant.superresolution.j#G1(Context,String,Z,Z)Z` 的反汇编守卫顺序：

```
0000 const-string "vsr_controlled"
0004 invoke-static com.vivo.common.algorithem.b#f(Context,String,String)Z   ← 闸①冲突仲裁
0012 if-eqz v0 → 0016 拦截分支
0016 const-string "set super resolution command is intercepted!" → return
0034 iget  j->u:I                       ← 闸②刷新率
0038 const/16 #144
003c if-ne  → 0040 "close isUnableResolution144" → return
004c iget-object j->z:superresolution/a;  invoke a#l()Z   ← 闸③Q 芯总开关
005c iget-boolean j->x:Z                                  ← 闸④Monster+
0068 "setSuperResolutionCommand return only monster+ support  mIsMonster+ =false" → return
```

对应落点：

| 闸 | 落点 | 做法 |
|---|---|---|
| ① 冲突仲裁 | `com.vivo.common.algorithem.b#f/g(Context,String,String)Z`（两个都是 static） | 对 `vsr_controlled`/`memc_controlled` 返回 false |
| ② 144Hz | 字段 `j.u:I` | 入口前强写 144 |
| ③ Q 芯总开关 | `superresolution.p#l()Z`（`a#l()` 唯一非空实现，读 `a8.e#m()==1`；`o#l()` 恒 false） | 返回 true |
| ④ Monster+ | 字段 `j.x:Z` | 入口前强写 true；`B0` setter 改参数 |

另外找到了 Monster+ 的**字面开关**：`modeshift.n#n()Z` 读
`Settings.System` 的 `game_plus_mode_key`（缓存字段 `f:Boolean`，为空时读设置）。
这一个方法被 **14 处**调用点消费，涵盖 `frameinterpolation.h#i0/g1/p0`、
`QSuperResolutionView#k/setCurrentUI`、`SuperResolutionView#l/setCurrentUI`、`QSuperFrameView#a`，
即超分与超帧共用的总闸。插帧侧那句
`"handleBoostFrame only monster plus support! monster+ =false"` 就在 `h#i0` 里，
它前面调的正是 `modeshift.n#f().n()`。

背夹那条，本轮证伪了一半：`modeshift.a#a()Z` 读字段 `a:Z`，9 个调用方**全部在性能面板/模式切换 UI**，
没有一个在 `superresolution` / `frameinterpolation` 包里。所以背夹只影响面板显示"可用/不可用"，
**不参与引擎判定**。UI 侧也一并放行（`f.q2.unlock.all` 时返回 true）。

## 五、构建链隐患

`tools/build.sh` 里 javac 后面跟着 `| head -20 || true`，编译报错也会继续打包。
本轮真的踩到：一次语法错误产出 56KB 残缺 APK（正常 88KB）且 `Success` 装上了。
现在改为 `if ! javac ...; then exit 1; fi`，stub 编译同样硬失败。

## 六、现在的状态与唯一剩余步骤

- 0.13.5 已安装，`modules.apk_path` 已自动同步到当前路径，作用域 = `com.android.systemui` + `com.vivo.gamecube`；
- 但 LSPosedService 的模块表**仍是开机时那份**，热改无效（实测：改库后杀 SystemUI，maps 依旧 0 条、logcat 0 行）；
- **需要重启一次手机**。重启后打开 App → 「Hook 状态」页应显示 `已挂上 N 条 hook`，
  以及逐条的 `MISS`（如果有）。

重启后要核对的清单：

1. `logcat -d -s FuckOrigin:I` 能看到 `<pkg>|<process>|<ts>` 与 `H|...|OK  ...` 两类行；
2. 「Hook 状态」页 `未匹配` 为 0，或列出的 MISS 说明哪些类/方法名在本机型 dex 里不存在；
3. 开「解锁全部 Q 芯功能（合并）」，进游戏看 Q 芯专区是否还提示"连接散热背夹并开启 Monster+"；
4. 若超分仍不可用，下一步检查 `G1` 的调用方是否在更早的分支就被拦（`b#f` 之外还有 `com.vivo.common.a#k0()`、`utils.l#a2()`）。

---

# 0.15.x：开关从来没传到 hook 里（最后一处断点）

0.13 解决了"注入"和"检测"，重启也不再是前提——实测 LSPosed 2.2.1 在**新进程 fork 时读当前库**，
只要 `modules.apk_path` 是对的，杀进程重启就会加载。证据（`com.vivo.gamecube:assistantui`）：

```
I FuckOrigin: com.vivo.gamecube|com.vivo.gamecube:assistantui|1792409459616
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   e#b
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   h#G0
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   MonsterPlusEvent#isMonster
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   j#A0
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   j#B0
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   j#G1
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   j#H1
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   j#m0
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   b#f
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   b#g
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   n#n
I FuckOrigin: H|com.vivo.gameassistant...|OK   p#l
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   a#a
```

13 条全 `OK`，零 `MISS`；`:daemon` 进程同样 13 条。**但功能仍然没变化**，因为：

## 最后一处断点：XSharedPreferences 读不到开关

hook 里所有放行都读 `XSharedPreferences("com.fuckorigin.app","fuckorigin")`（标准路径
`/data/data/com.fuckorigin.app/shared_prefs/fuckorigin.xml`）。本机实测：

- 该路径**不存在**；
- App 的 prefs 实际被重定向到
  `/data/misc/apexdata/14b530ab-f4a0-4365-b662-9ac9db4d1470/prefs/com.fuckorigin.app/fuckorigin.xml`
  （`dumpsys package` 报的 `dataDir=/data/user/0/com.fuckorigin.app` 也不存在）。

于是 `on()` 恒为 false —— 也就是说即便 13 条 hook 全挂上、开关在界面上全打开，
**所有放行分支都走不进去**。这解释了为什么每一版都"装好了、也勾了、就是不生效"。

诊断证据（install 阶段打印，此时还没有 Application，所以显示 false；真值在首次触发时打印）：

```
I FuckOrigin: CFG unlockAll=false thermal=false
```

## 修法：改用 Settings.Secure 当跨进程通道

`Settings.Secure` 对 system/gamecube 这类进程天然可读，写入只需 root，
既绕开被重定向的 prefs 目录，也绕开 SELinux 对 `/data/local/tmp` 的限制。

- 写：`Bridge.sync()` 在每次开关变更 / App 启动时投影三个 key
  （`fuckorigin_q2` / `fuckorigin_thermal` / `fuckorigin_monster`），实测：
  ```
  I FuckOrigin: BRIDGE|q2=true thermal=true monster=true
  settings get secure fuckorigin_q2      → 1
  settings get secure fuckorigin_thermal → 1
  settings get secure fuckorigin_monster → 1
  ```
- 读：hook 侧 `refreshCfg()` 每 2 秒最多读一次，Context 取
  `ActivityThread.currentApplication()`，服务型进程为空时退到
  `ActivityThread.currentActivityThread().getSystemContext()`；
  首次读到真值时打印 `CFG2|q2=… thermal=… monster=… @pid`。

新增无界面入口，便于验证与自动化：
```
am start -n com.fuckorigin.app/.MainActivity --ez unlock true
```
一次性打开 6 个解锁开关并同步桥（日志 `PREFS|已写入 6 项解锁开关`）。

## 顺带证伪：直接写系统设置做不到

`Settings.System` 的 `game_plus_mode_key`（`modeshift.n#n()` 读的 Monster+ 开关）写入后
**20 秒内被改回 0**，实测三次。改它的是常驻的 `com.vivo.gamecube:daemon`（pid 8037）。
所以"不改代码、只写设置"的思路行不通；`n#n` 的 hook 才是可靠落点。

`content call` 写 `com.vivo.gamecube.gamespprovider` 也不行：`SpProvider#call` 开头是
`verifyCaller`，shell 调用被拒，返回 `Result: null`。

---

# 0.16：锁帧功能 + 补齐和平精英的档位（配置覆盖）

## 一、"显示不全"的来源是配置表，不是 hook

面板里能出现哪些档位，由 `/system/etc/gamecube/` 下两张表决定，字段由 `La8/a;->c` 解析。
实测和平精英那条：

```xml
<item pkgName="com.tencent.tmgp.pubgmhd" label="和平精英" channel="vivo" state="2"
      frameDepress="72" frameInter="144"  monsterFrameDepress="" monsterFrameInter=""  index="0"/>
```

对比原神那条（有 `ultraResolution="2K"`）：

```xml
<item pkgName="com.miHoYo.Yuanshen" ... state="2" frameDepress="30" frameInter="60"
      monsterFrameDepress="" monsterFrameInter="" overHeatExtremeTemperature="48"
      coolDownExtremeTemperature="45" ultraResolution="2K" index="3"/>
```

结论：和平精英**没有被声明 2K 超分档**（缺 `ultraResolution`），Monster+ 的两个帧率字段也是空的，
所以面板只给出它声明过的东西。顺带排除一个常见误判：`Settings.Global` 的
`vsr_whitelist_apps` 里**已经包含** `com.tencent.tmgp.pubgmhd`，白名单不是原因。

`FrameInterConfigList.xml` 里它是这样声明的（只有 90→144 一档）：

```xml
<game pkgName="com.tencent.tmgp.pubgmhd" label="和平精英" channel="vivo" index="0" supportSDK="false">
    <boostFrameValues><item gameFps="90"  depressValue="72" frameInterValue="144"/></boostFrameValues>
    <optiPowerValues> <item gameFps="120" depressValue="60" frameInterValue="120"/></optiPowerValues>
```

## 二、做法：模块内覆盖 + 立即 bind mount

写进 KernelSU 模块，重启后仍生效；同时 bind mount 让本次立刻生效：

```
/data/adb/modules/fuckorigin-thermal/system/etc/gamecube/SuperResolutionConfigList.xml
/data/adb/modules/fuckorigin-thermal/system/etc/gamecube/FrameInterConfigList.xml
mount --bind <模块文件> /system/etc/gamecube/SuperResolutionConfigList.xml
```

改动内容（只动和平精英那一条）：

| 字段 | 原值 | 新值 |
|---|---|---|
| `ultraResolution` | 不存在 | `2K` |
| `monsterFrameDepress` | 空 | `72` |
| `monsterFrameInter` | 空 | `144` |
| `<version>` | 12 | 13（抬版本促使重读） |

改后实测：

```
grep pubgmhd /system/etc/gamecube/SuperResolutionConfigList.xml
→ ... monsterFrameDepress="72" monsterFrameInter="144" ultraResolution="2K" index="0"
```

模块版本同步到 v1.3.0（versionCode 4），描述里写明它同时承载配置覆盖。
回滚：删掉模块下的 `system/etc/gamecube/`，或 `umount /system/etc/gamecube/SuperResolutionConfigList.xml`。

## 三、新增功能：锁帧 · 始终最高帧率

设备侧的帧率上限来自系统属性 `persist.vivo.phone.fps_max`（反汇编 `e0#v()I` 证实：

```
const-string "persist.vivo.phone.fps_max"
const/16     v1, #60
invoke-static SystemProperties;->getInt(String,I)I
```

出厂值是 **120**，而这块屏是 144Hz，`peak_refresh_rate` 本来就已经是 144——上限被属性卡在 120。

功能 `perf.fps.lock` 做两件事：

1. **root 侧**：`setprop persist.vivo.phone.fps_max 144` +
   `settings put system peak_refresh_rate 144` + `min_refresh_rate 144`；
   关闭时回滚为 120 / 144 / 删除 min。
2. **hook 侧**：`com.vivo.gameassistant.utils.e0#v()I` 直接返回 144
   （被 `a#c`、`PerformancePanelPanelView#i`、`RefreshRateView#e` 三处消费）。

实测开启后：

```
persist.vivo.phone.fps_max = 144
peak_refresh_rate = 144
min_refresh_rate  = 144
settings get secure fuckorigin_fps = 1
```

hook 绑定总数从 13 增至 **14 条，全部 OK**：

```
OK  MonsterPlusEvent#isMonster   OK  a#a    OK  b#f    OK  b#g    OK  e#b
OK  e0#v                          OK  h#G0   OK  j#A0   OK  j#B0   OK  j#G1
OK  j#H1                          OK  j#m0   OK  n#n    OK  p#l
```

无界面入口同步扩展为应用 7 项（`q2.unlock.all / thermal.bypass.global / perf.monsterplus.force /
q2.mask.memc / q2.mask.sr / q2.mask.ve / perf.fps.lock`），
且改为走 `Actions.apply` 真实动作，不再只写 prefs：

```
am start -n com.fuckorigin.app/.MainActivity --ez unlock true
```

---

# 0.17：把全部游戏拉到"和平精英档"

## 一、截图给出的关键对照

19:41 的两组截图说明 0.16 的补齐已生效：

- **和平精英**：超级分辨率出现 原始/超高/极致 三档，超级帧率出现 原始/节能稳帧/极致帧率 三档，
  另有 VAA 抗闪烁开关——即 0.16 补的 `ultraResolution="2K"` 与 Monster+ 字段起了作用。
- **三角洲行动**：只有「超级分辨率 + 游戏视效增强」两张卡，**没有超级帧率卡**。

## 二、差在哪：`state` 与帧率字段

两张表的实测统计（覆盖前）：

| 表 | 结论 |
|---|---|
| `SuperResolutionConfigList.xml` | 177 条；`state="2"` 仅 **9** 条，`state="1"` 有 **168** 条；168 条 `frameInter` 为空；172 条没有 `ultraResolution` |
| `FrameInterConfigList.xml` | 242 个包；与超分表交集 157，**只在超分表 20 个**（含三角洲行动） |

三角洲行动原条目：

```xml
<item pkgName="com.tencent.tmgp.dfm" label="三角洲行动" channel="vivo" state="1"
      frameDepress="" frameInter="" monsterFrameDepress="" monsterFrameInter="" monsterPlusState="1"/>
```

和平精英原条目：

```xml
<item pkgName="com.tencent.tmgp.pubgmhd" label="和平精英" channel="vivo" state="2"
      frameDepress="72" frameInter="144" monsterFrameDepress="" monsterFrameInter=""/>
```

两者差别就是 `state` 与帧率字段。`state="2"` 才同时给出超分与帧率两组档位。

## 三、做法：全量对齐 + 插帧表补缺

脚本 `rev/gamecube/patch_cfg.py`（可重跑）做两件事：

1. **超分表**：所有 `<item>` 统一 `state="2"`；`frameDepress`/`frameInter`/
   `monsterFrameDepress`/`monsterFrameInter` 空则填 `72`/`144`；缺 `ultraResolution` 则补 `2K`。
2. **插帧表**：为"在超分表但不在插帧表"的游戏补一份与和平精英同构的 `<game>` 块
   （`boostFrameValues 90→72/144`、`optiPowerValues 120→60/120`）。

执行结果：

```
SR items=177 state2 9->177 2K 5->177
FI 新增 20 条，含 dfm=True
```

XML 合法性用 `xml.etree.ElementTree` 校验通过（`SuperResolutionConfigList` children=178、
`FrameInterConfigList` children=248），生效文件里三角洲行动已变成：

```xml
state="2" frameDepress="72" frameInter="144"
monsterFrameDepress="72" monsterFrameInter="144" monsterPlusState="1" ultraResolution="2K"
```

之后 `am force-stop com.vivo.gamecube` 让其重读配置，进程重建为
`:daemon 13830` / `:assistantui 13911`，14 条 hook 全部 `OK`。

## 四、风险与回滚

把 168 个游戏一次性声明为"支持插帧 + 2K 超分"，其中不少机型档位本没有 MEMC 白名单，
可能出现画面异常、耗电上升或个别游戏闪退。这是"以和平精英为准"的必然代价，保留两条回滚：

```sh
# 立即回滚本次挂载
umount /system/etc/gamecube/SuperResolutionConfigList.xml
umount /system/etc/gamecube/FrameInterConfigList.xml
# 永久回滚（重启后也不再覆盖）
rm -rf /data/adb/modules/fuckorigin-thermal/system/etc/gamecube
```

只回滚单个游戏时，改模块内对应 `<item>` 的 `state` 回 `1` 并清空四个帧率字段即可。

---

# 0.18：hooks 全部空转的真因——取不到 Context

## 一、现象

配置改动看得见（面板多出了档位），但**行为一条都没变**。日志里从头到尾只有安装期的

```
I FuckOrigin: CFG unlockAll=false thermal=false
```

再也没有出现过任何一次真值。也就是说：hook 明明挂上了、开关明明在 `Settings.Secure` 里是 1，
但每条 hook 内部的 `unlockAll()` 都算成 false —— **等于全部空转**。

## 二、原因

0.15 把开关通道从 `XSharedPreferences` 换成了 `Settings.Secure`，而读它需要 `ContentResolver`，
也就需要 `Context`。取 Context 的两条路在这个进程里都走不通：

```java
// 路径一：进程刚 fork 时 Application 还没创建 → null
ActivityThread.currentApplication()
// 路径二：隐藏 API，模块 classloader 未必拿到豁免 → 抛异常后被 catch 吞掉
ActivityThread.currentActivityThread().getSystemContext()
```

`appCtx()` 返回 null → `refreshCfg()` 直接 return → `cfgQ2/cfgThermal/cfgMonster/cfgFps` 恒为 0
→ 所有守卫分支永不进入。这也解释了为什么 0.13～0.17 每一版都"挂上了却没反应"。

## 三、修法

在 `install()` 最前面挂 `android.app.Application#onCreate`，进程一创建 Application 就把 Context 存下来：

```java
hookIfExists("android.app.Application", lp, "onCreate", new XC_MethodHook() {
    @Override protected void afterHookedMethod(MethodHookParam p) {
        if (p.thisObject instanceof android.content.Context) {
            sCtx = (android.content.Context) p.thisObject;
            cfgAt = 0L;          // 强制重读
            refreshCfg();
        }
    }
});
```

`appCtx()` 优先返回 `sCtx`，前两条旧路径保留作兜底。

## 四、验证（首次拿到真值）

重启 gamecube 后，`:daemon` 进程的日志：

```
I FuckOrigin: com.vivo.gamecube|com.vivo.gamecube:daemon|1792410366049
I FuckOrigin: H|com.vivo.gamecube:daemon|OK   Application#onCreate
I FuckOrigin: H|com.vivo.gamecube:daemon|OK   e#b
I FuckOrigin: H|com.vivo.gamecube:daemon|OK   h#G0
I FuckOrigin: H|com.vivo.gamecube:daemon|OK   MonsterPlusEvent#isMonster
I FuckOrigin: H|com.vivo.gamecube:daemon|OK   j#A0 / j#B0 / j#G1 / j#H1 / j#m0
I FuckOrigin: H|com.vivo.gamecube:daemon|OK   b#f / b#g / n#n / p#l / a#a / e0#v
I FuckOrigin: CFG2|q2=1 thermal=1 monster=1 fps=1 @24781
```

`CFG2` 这一行是判据：四个值都为 1 才说明"开关 → hook"这条链路真的通了。
此前所有版本都不可能打出这一行。

## 五、给排查用的一句话

以后判断"到底哪一层没生效"，按顺序看：

1. `logcat -s FuckOrigin:I | grep "<包名>|<进程>|"` —— 有没有存活标记（注入层）；
2. 同一条日志里的 `OK`/`MISS` 清单 —— 每条 hook 是否挂上（目标层）；
3. `CFG2|q2=… thermal=… monster=… fps=…` —— 开关是否读到（配置层）。

三层任一为否，后面的功能都不会变。

---

# 0.19：不是开关的问题，是"Q2 专区"走了另一套布局

## 一、先排除的

DFM 面板所在进程（`com.vivo.gamecube:assistantui`，pid 30565）日志：

```
I FuckOrigin: CFG2|q2=1 thermal=1 monster=1 fps=1 @30565
I FuckOrigin: H|com.vivo.gamecube:assistantui|OK   n#n / p#l / a#a / b#g / e0#v …
```

开关层、目标层都正常，"hook 空转"这个问题在 0.17 已解决。配置也确实被解析了：

```
I _V_GameCube-SuperResolutionConfigXmlParser: parse: Add one game for SuperResolutionConfigList.
I _V_GameCube-FrameInterConfigXmlParser:     parse: Add one game for FrameInterConfigList.
```

## 二、真正的原因：`monsterPlusState="1"`

全表 177 条里只有 **4 条**带这个字段：

```
com.netease.yyslscn        阴阳师
com.kurogame.mingchao      鸣潮
com.kurogame.mingchao.bilibili
com.tencent.tmgp.dfm       三角洲行动
```

它们正好是面板会走「自研电竞芯片 Q2 专区」那套卡片布局的游戏（19:41 截图里 DFM 显示的是
两张竖排大卡片），而和平精英走的是「超级分辨率与超级帧率」分段控件那套——两套 UI 的档位集合本来就不一样。
所以"配置里声明了 2K/插帧"和"面板上出现第三档"不是同一件事。

## 三、本轮改动

1. **移除 `monsterPlusState="1"`**（4 条全去），让这 4 个游戏回落到和平精英那套布局与档位集合：
   ```
   pkgName="com.tencent.tmgp.dfm" ... state="2" frameDepress="72" frameInter="144"
       monsterFrameDepress="72" monsterFrameInter="144" ultraResolution="2K"
   ```
   （`<version>` 递增到 15，XML 经 `ElementTree` 校验，177 条完好）
2. **新增 hook `modeshift.n#m()Z` → true**。它是"是否曾开启过 Monster+"，
   `QChipContentView#j` 取到后调 `l(Z)` 决定 Q2 面板是否展开完整内容；
   这个标记存在 gamecube 自己的 `gamemode_status` SP 里，本机从未写过，一直是 false。

安装后 `:daemon` 16 条 hook 全 `OK`，`CFG2` 四项仍为 1。

## 四、如果还是两档

下一步要进 `QSuperResolutionView` / `GameSuperResolutionFragment` 的档位构造逻辑，
看第三档（极致）到底由 bean 的哪个 int 字段决定（`a8/e` 有 8 个 int 字段，
解析器 `a8/a#c` 按 XML 属性顺序写入）。已知 `a8/e#m()I == 1` 是 Q 芯总开关（`p#l` 用的就是它），
其余 7 个字段与属性的对应关系还没逐一对上。

---

# 0.20：配置改动一直没进游戏进程——挂载命名空间

## 一、先纠正上一条结论

0.17 里我说"和平精英三档出现了，说明补齐生效"——**这是错的**。和平精英本来就声明了三档，
那是它原生行为；我补的 `ultraResolution` 从头到尾没被 gamecube 读到过。这里更正。

## 二、决定性证据

同一个文件，两个进程看到的内容不同：

```
# 我的 shell（root）
/system/etc/gamecube/SuperResolutionConfigList.xml        → <version>15</version>
# gamecube 进程
/proc/8983/root/system/etc/gamecube/SuperResolutionConfigList.xml → <version>12</version>

# 挂载命名空间 inode
shell    → mnt:[4026535574]
gamecube → mnt:[4026535546]
```

gamecube 自己的解析日志也印证：

```
I _V_GameCube-SuperResolutionConfigXmlParser: parse: SuperResolutionConfigList version is 12
I _V_GameCube-FrameInterConfigXmlParser:     parse: FrameInterConfigList  version is 11
```

而模块里那份早已是 15。**每个应用进程有独立的挂载命名空间**，运行期 `mount --bind`
只对执行挂载的那个进程有效。实测用 `nsenter -t <pid> -m` 挂进旧进程后它确实看到 15，
但 `am force-stop` 之后新进程又回到 12——因为新进程从 zygote 继承了原始命名空间。
这就是为什么"改了配置、面板毫无变化"。

## 三、修法：把挂载放到 zygote 之前

写进模块的 `post-fs-data.sh`，由 KernelSU 在开机早期执行，之后 fork 的进程都会继承：

```sh
MODDIR=${0%/*}
SRC="$MODDIR/system/etc/gamecube"
for name in SuperResolutionConfigList.xml FrameInterConfigList.xml; do
  if [ -f "$SRC/$name" ] && [ -f "/system/etc/gamecube/$name" ]; then
    mount --bind "$SRC/$name" "/system/etc/gamecube/$name" 2>/dev/null
  fi
done
```

同时模块里保留 `system/etc/gamecube/` 两份文件，即使 KernelSU 的自动 overlay 不生效，
显式 bind 也能兜住。脚本已过 `sh -n` 语法检查。

## 四、结论：配置部分必须重启一次

| 部分 | 是否需要重启 | 现状 |
|---|---|---|
| Hook（Monster+/Q 芯总开关/帧率/冲突仲裁/锁帧） | 否 | 已生效，`CFG2` 四项全 1，16 条 hook 全 OK |
| 配置表（档位数量、2K、超级帧率卡） | **是** | 待重启后由 `post-fs-data.sh` 挂上 |

重启后验证命令：

```sh
p=$(pidof com.vivo.gamecube:daemon)
grep -o "<version>[0-9]*</version>" /proc/$p/root/system/etc/gamecube/SuperResolutionConfigList.xml
# 期望 15；若是 12 说明模块脚本没执行，需查 /data/adb/modules/fuckorigin-thermal/
```

---

# 0.21：改配置文件这条路彻底否决，改为进程内注入 XML

## 一、软重启也没能解决

软重启（`ctl.restart zygote`）后实测三个进程看到的内容：

| 进程 | 命名空间 | 看到的版本 |
|---|---|---|
| init (1) | mnt:[4026532957] | **15**（我们挂的） |
| zygote64 / zygote | mnt:[4026534494] 等 | **15** |
| com.vivo.gamecube:daemon | mnt:[4026535509] | **12**（原始） |

zygote 明明继承了，fork 之后的应用进程却看不到——应用命名空间在创建时对 `/system` 做了重建，
文件级 bind 被覆盖。顺带验证了 KernelSU 的 `system/` 覆盖在本机**也没生效**
（`adreno_gpu_driver` 模块里的 `/system/lib64/libgpuwork.so` 与实际内容 md5 不一致，且已 disable）。

结论：**任何运行期或模块式的 /system 文件改动，都到不了游戏进程。**

## 二、改用解析器入口注入

配置解析入口是 `La8/a;->b(Ljava/io/InputStream;)La8/b;`（超分）和
`Lg7/a;->b(Ljava/io/InputStream;)Le7/a;`（插帧）。在 `beforeHookedMethod` 里把流读空、
按规则改写、再塞回一个 `ByteArrayInputStream`：

```
CFGPATCH|注入超分配置 36708 bytes
I _V_GameCube-SuperResolutionConfigXmlParser: parse: SuperResolutionConfigList version is 99
```

`version is 99` 是刻意改的标记值（原表 12），出现这一行就证明解析器吃的是我们注入的内容。
改写规则与 `rev/gamecube/patch_cfg.py` 完全一致：`state=2`、补齐
`frameDepress/frameInter/monsterFrame*`、缺则加 `ultraResolution="2K"`、删掉 `monsterPlusState`。

插帧表另用一个标记版本 98，并且会把"只在超分表、不在插帧表"的包按和平精英模板补进去
（补的数量打进 `CFGPATCH|插帧表补 N 条`）。

## 三、插帧表的一个额外发现

```
E _V_GameCube-FrameInterConfigXmlParser: parse: Error occurred!!!:
    java.io.FileNotFoundException: PD2463/FrameInterConfigList.xml
```

插帧表除了 `/system/etc/gamecube/FrameInterConfigList.xml` 还会去找**机型子目录**下的同名文件
（`PD2463/` 前缀），本机没有这个目录，所以这条会报错——是既有行为，不影响 `/system` 那份。

---

# 0.22：过热禁用插帧/视效的落点 + 未上架游戏自动补表

## 一、过热禁用的三个真实判定点

| 功能 | 判定位置 | 依据 |
|---|---|---|
| 插帧 | `frameinterpolation.h#K0(Long)` 里 `G0(D)Z` 为真 → 记 `TEMPERATURE_OVERHEAT` 并关插帧 | 反汇编 005c 处 `invoke-direct G0(D)Z`，随后打印 `Overheat, try to close frame interpolation.` |
| 游戏视效增强 | `gamefilter.a#p(D,D)` 里 `g0() && f0() && l` → `S(...)` 关闭，并提示 `game_filter_high_temp_running_tip` | 反汇编 0016/002c/0034 |
| 温度来源 | `com.vivo.common.utils.TemperatureHelper#f()[D` | 被 `gamefilter.a#g0`、`superresolution.j#b1/l1`、`frameinterpolation` 共用 |

新增两条 hook：

```
OK   TemperatureHelper#f     // 返回等长数组，全 35.0
OK   a#g0                    // gamefilter 过热谓词 → false
```

加上原有的 `h#G0(D)Z → false`，三条路径都堵住。`TemperatureHelper#f()` 是统一温度源，
改它等于同时压住插帧、超分、视效三条链。

## 二、装了但表里没有的游戏

`com.hypergryph.endfield`（明日方舟：终末地）的 `android:appCategory=0x0`，
不是 `CATEGORY_GAME`，所以按系统分类枚举会漏掉。

改为"第三方应用（`FLAG_SYSTEM == 0`）或系统标记为游戏"一律补条目，实测：

```
CFGPATCH|超分表补装机游戏 134 条
CFGPATCH|注入超分配置 72286 bytes
CFGPATCH|插帧表补 154 条
parse: SuperResolutionConfigList version is 99
parse: FrameInterConfigList     version is 98
```

补进去的条目同样带 `state="2"`、`frameInter="144"`、`ultraResolution="2K"`，
并把 `overHeat*Temperature`/`coolDown*Temperature` 一律抬到 99。

hook 总数增至 **19 条，全部 OK，零 MISS**。

---

# 0.23：插帧"不支持本游戏"的判定链 + 回退方案

## 一、插帧不走 SR 那张表

`frameinterpolation.h#i1()` 的取数路径：

```
p.V().O0()                    → 当前包名存入 h.i
e9.a#e(String) → d8.b         → 本游戏的插帧配置 bean
bean == null                  → "updateValues: Failed to update values, not supported game!!!"
```

`e9.a` 是抽象类，具体实现是 **`e9.d` / `e9.h`**（`e(String)d8.b`）。
也就是说：插帧引擎判定"是否支持"，看的是 `e9.x#e(pkg)` 能否取到 bean，
**不是** XML 里有没有 `<game>` 条目。之前只注入 XML，对这条链无效。

第一次尝试挂 `e9.a#e` 直接被 LSPosed 拒绝（日志里能看到）：

```
MISS a#e : java.lang.IllegalArgumentException: Cannot hook abstract methods:
           public abstract d8.b e9.a.e(java.lang.String)
```

改为分别挂 `e9.d#e` 与 `e9.h#e`，现在两者都是 `OK`。

## 二、本轮新增的两条兜底

1. **`e9.d/e9.h#e` → 取不到就回退到和平精英的 bean**
   ```java
   Object bean = XposedHelpers.callMethod(p.thisObject, "e", "com.tencent.tmgp.pubgmhd");
   if (bean != null) p.setResult(bean);
   ```
   用 `ThreadLocal` 防递归。这样任何游戏在缺少自身配置时，拿到的就是
   `com.tencent.tmgp.pubgmhd` 的插帧参数（`gameFps 90 → depress 72 → interp 144`）。

2. **`frameinterpolation.h#i1()Z` → true**（"是否支持"的总判定）。

hook 总数 **22 条，全部 OK，零 MISS**：

```
Application#onCreate  MonsterPlusEvent#isMonster  TemperatureHelper#f
a#a  a#b  a#g0  b#f  b#g  d#e  e#b  e0#v  h#G0  h#e  h#i1
j#A0  j#B0  j#G1  j#H1  j#m0  n#m  n#n  p#l
```

## 三、仍需实测确认的两点

| 待确认 | 判据 |
|---|---|
| 高温下插帧是否还被关 | 日志里不再出现 `Overheat, try to close frame interpolation.`，且 `NovaUsecaseControllerV3` 的 `gameMemcFps` 非 0 |
| 终末地是否拿到插帧/超分 | 日志里出现 `FI|回退用和平精英配置 com.hypergryph.endfield`，或面板出现对应条目 |

`NovaUsecaseControllerV3.computeUsecase` 那条日志是硬件层的真实状态，
字段 `gameMemcFps / gameMemcTargetFps / gameVisualEnhance` 才是"有没有真的开起来"的铁证，
比 UI 上有没有开关更可信。

---

# 0.24：吸收 SimpleHook 方案 + 侧边栏频率控制 + 信仰模式

## 一、那个 APK 的思路

`游戏魔盒hook_0.1.apk`（包名 `mcwg12.gamecube.hook`）不是自研 Xposed 模块，
而是 **SimpleHook 插件**：`assets/xposed_init` 指向 `me.simplehook.plugin.old.hook.HookInit`，
真正的逻辑是一份 JSON `assets/configs.xml`，逐条声明"类 → 方法 → 参数 → 返回值"。

它的 11 条配置（目标 `com.vivo.gamecube` 14.0.15.00_de）：

| 类 | 方法 | 参数 | 返回值 |
|---|---|---|---|
| modeshift.a | a | — | true |
| modeshift.n | v | boolean | true |
| modeshift.n | m / n | — | true |
| com.vivo.common.utils.w0 | a | Context,String | true |
| com.vivo.common.a | v0 / R0 / u1 | — | true |
| com.vivo.common.a | D1 | Context | true |
| com.vivo.common.utils.m0 | g | Context | true |
| com.vivo.common.utils.m0 | f | Context,String | true |

其中 3 条我们已有（`modeshift.a#a`、`n#m`、`n#n`），**7 条是新增**，
已全部并入 FuckOrigin 的 `unlockAll()` 门控（外加 `n#v(Z)` 的入参强制 true）。

实测踩坑：`m0#g` 在真机版本 **14.0.13.02_de** 上不存在（我们的反编译库来自 14.0.15.00_de），
LSPosed 报 `NoSuchMethodError`，已移除。这条差异说明**模块与 APK 版本必须对齐**。

## 二、侧边栏性能面板注入

`PerformancePanelPanelView` 的继承链是
`BasePerformancePanelPanelView → android.widget.RelativeLayout`，本身是 ViewGroup，
所以可以挂 `i(Context)V` 之后直接 `addView`。

新增 `PanelUi.java`，在面板底部注入一个深色圆角卡片：

- **CPU 最低 / CPU 最高 / GPU 最低 / GPU 最高** 四个 SeekBar，
  档位来自 `scaling_available_frequencies` 与 `gpu_available_frequencies`；
- 底部 **信仰模式** 按钮，金色渐变（`0xFFFFD24A → 0xFFFFA51F`），开启后文案变"信仰模式 · 已开启"；
- 滑块与按钮的取值全部落到 `Settings.Secure`（键名刻意中性：`sys_perf_c0_min/max`、`sys_perf_g_min/max`、`sys_perf_faith`）。

## 三、执行器（root 侧）

新增 `/data/adb/fuckorigin/bin/fo_perf.sh`，由模块 `service.sh` 开机拉起，每 2 秒轮询：

| 设置 | 行为 |
|---|---|
| `sys_perf_c0_min/max` | 写 `policy6`（并同步 `policy0`）的 `scaling_min_freq` / `scaling_max_freq` |
| `sys_perf_g_min/max` | 写 `kgsl-3d0/devfreq/min_freq` / `max_freq` |
| `sys_perf_auto=1` | 冻结 `thermal-engine-v2` + 冷却设备归零，应用滑块档位 |
| `sys_perf_faith=1` | 同上，但强制 CPU/GPU 拉到 `cpuinfo_max_freq` / GPU 最高档 |
| 任一由 1 变 0 | `kill -CONT` 恢复 thermal-engine |

实测（信仰模式）：

```
faith=1 → CPU scaling_max=4320000  scaling_min=4320000
          GPU devfreq max=1100000000
          thermal-engine 状态 T（已冻结）
faith=0 → thermal-engine 状态 S（已恢复）
```

## 四、性能模式联动

挂 `modeshift.n#w(ModeType)`：目标模式名含 `MONSTER/PERFORMANCE/BOOST/TURBO` 时
写 `sys_perf_auto=1`，否则写 0。执行器据此自动去温控并套用滑块档位。

## 五、当前状态

- hook 总数 **31 条，全部 OK，零 MISS**；
- 执行器进程常驻（`sh /data/adb/fuckorigin/bin/fo_perf.sh`）；
- 面板 UI 已注入，但**尚未在真机面板上做过视觉确认**（需要进游戏打开侧边栏看）。

## 六、未完成

1. 面板滑块的**视觉对齐**：目前是底部绝对定位的独立卡片，可能与原有面板元素重叠，
   需要按实际布局微调边距；
2. **隐蔽性**：键名已中性化，但 logcat 仍有 `FuckOrigin` 标记，
   若要对抗检测需要改为可关闭的开关（默认静默）；
3. `信仰模式` 关闭后 CPU 上限不会立刻回到原值，由 thermal 自行接管——
   如需精确还原，应记录进入前的 `scaling_max_freq` 并在关闭时写回。

---

# 0.25：修正面板注入形态（从整块卡片改为底部紧凑条）

## 一、上一版的问题

`PanelUi` 第一版把 4 个滑块 + 标题 + 按钮全部展开成一个 `MATCH_PARENT` 宽的深色卡片，
并且 `ALIGN_PARENT_BOTTOM`。在那个面板里它高约 280dp，而面板本身才 250dp 左右，
结果整块盖住了原界面——用户反馈"把整个界面全挡住了"。

## 二、改法：默认一行，点开才展开

- 折叠态：一行高约 40dp，
  左「FuckOrigin · 频率」+ 中「展开 ▾」+ 右金色小按钮「信仰」；
- 展开态：内容放进 `ScrollView`，高度上限 = 父容器高度的 **45%**，不会撑满；
- 滑块与信仰按钮都在展开区，展开区最后一项就是金色「信仰模式」。

这样无论面板多大，静止状态只占底部一条，不遮挡原内容。

## 三、同时清理的一次事故

测试信仰模式时把设置留在开状态，导致：
- `sys_perf_faith=1` 常驻 → thermal-engine 被 `kill -STOP`、CPU/GPU 拉到满档；
- 温控模块自带的守护进程也在反复执行同样的 `kill -STOP` 脚本，
  手动 `kill -CONT` 会被它立刻覆盖。

处置：`kill` 掉 `fuckorigind`、清掉测试用的四个滑块键、`sys_perf_faith/auto` 归零，
引擎状态已回到 `S`（运行中）。**信仰模式默认关闭，只有点按钮才会开。**

## 四、当前状态

- hook **31 条全 OK，零 MISS**；
- 面板注入改为紧凑条，等待真机视觉确认；
- `sys_perf_auto` 由性能模式联动维持为 1（面板处于性能档时的预期行为）。

---

# 0.26：保存按钮、信仰模式提示、模块自检与退出自动关闭

## 一、面板侧改动

| 需求 | 实现 |
|---|---|
| 保存按钮 | 展开区新增蓝色「保存配置」，滑块改为**只改待保存值**，点保存才写入 |
| 信仰模式提示 | 点击弹 Toast：**"燃烧你的芯片吧，赛博坦怪兽"**；关闭时提示"信仰模式已关闭" |
| 立即生效 | 开启信仰模式时会先把待保存的四个滑块值落盘，再写 `sys_perf_faith=1`，不需要再点保存 |
| 记录当前游戏 | 开启时通过反射取 `com.vivo.gameassistant.p#V().O0()` 写入 `sys_perf_faith_pkg` |

## 二、root 侧改动

`fo_perf.sh` 增加两段"游戏退出自动关闭"：

```
faith=1 且 pidof $sys_perf_faith_pkg 为空  → faith=0 + kill -CONT thermal-engine
auto =1 且 pidof $sys_perf_auto_pkg  为空  → auto =0 + kill -CONT thermal-engine
```

性能模式联动也加了保护：`modeshift.n#w` 触发时若拿不到游戏包名，就不写 `auto=1`，
避免"退出游戏后温控一直被冻结"。

## 三、App 启动自检

新增 `ModuleStatus`：

```
installed()        模块目录存在 module.prop
enabled()          没有 disable 文件
executorRunning()  ps 里能看到 fo_perf.sh
summary()          → "全部功能已启用" / "未安装" / "已禁用" / "执行器未运行"
```

`MainActivity` 启动时在后台线程检查并在主线程弹 `MDialog`：

- 就绪：标题「全部功能已启用」，内容含状态摘要，按钮「知道了」；
- 未就绪：标题「需要启用去温控模块」，按钮「一键安装并启用」——
  从 APK 资源 `assets/module/` 写出 `module.prop / service.sh / post-fs-data.sh / fo_perf.sh`，
  `chmod 755`、删除 `disable`、拉起执行器。

模块文件在构建时由 `module_assets/` 拷入 `app/assets/module/`（build.sh 新增 [3.5/7] 步骤）。

## 四、一次安全问题与处置

性能模式联动会把 `auto` 置 1 从而冻结 thermal-engine。用户当时在聊天而非游戏，
`auto_pkg` 为空导致无法判断"游戏已退出"，引擎一直处于 `T`（冻结）。已：

1. 立即 `sys_perf_auto=0` 并 `kill -CONT`，引擎回到 `S`；
2. 在 hook 里加"拿不到游戏包名就不开启"的保护；
3. 重新安装 0.25.1。

## 五、位置问题（未完成）

用户转达群友建议：**把入口放进「游戏工具」页**，而不是性能面板底部。
当前的紧凑条虽然不再遮挡，但确实不是最佳位置。下一轮需要：

1. 定位侧边栏「游戏工具」页的宿主 Fragment/View（`panels/` 下 145 个类为扁平命名，需按
   `QuickSwitchItemType` 或布局 id 反查）；
2. 把 `PanelUi` 的注入点从 `PerformancePanelPanelView#i(Context)` 换过去。

---

# 0.27：模块完整内置、作者更名、控制台信仰按钮、退出提示

## 一、模块确实没内置完整（已修）

上一版 `assets/module/` 只有 4 个脚本，**缺少守护进程二进制、两份 gamecube 配置覆盖、网页控制台**，
在全新环境下"一键安装"装出来的是残缺模块。现在整棵树都打进去了：

```
assets/module/README.md                                3216
assets/module/action.sh                               1674
assets/module/bin/fuckorigind                       744192
assets/module/customize.sh                             624
assets/module/module.prop                              431
assets/module/post-fs-data.sh                          535
assets/module/service.sh                               885
assets/module/system/etc/gamecube/FrameInterConfigList.xml   106899
assets/module/system/etc/gamecube/SuperResolutionConfigList.xml 36525
assets/module/uninstall.sh                             374
assets/module/webroot/index.html                     11596
```

`ModuleStatus.install()` 改成递归释放整棵树，并按类型给权限
（`*.sh`、`bin/fuckorigind` 755，`module.prop` 与 XML 644），最后删除 `disable`、拉起执行器。

## 二、作者更名 hmascs

- 模块 `module.prop`：`author=hmascs`（APK 内置副本与设备上已安装的模块都已改）；
- 应用「关于」页新增一行 `作者  hmascs`。

## 三、控制面板消失的原因与修复

之前为了恢复温控，我 `kill` 掉了模块守护进程 `fuckorigind`——**控制台（127.0.0.1:8899）正是它提供的**，
所以"控制面板没了"。重新拉起后恢复：

```
pidof fuckorigind → 22557
/api/status → {"unlocked":false,"threshold":52,...}
```

## 四、控制台里的信仰模式按钮

`webroot/index.html` 增加了一个金色按钮：

```html
<button id="faith" style="background:linear-gradient(90deg,#FFD24A,#FFA51F);...">信仰模式</button>
```

点击跳转自定义 scheme `fuckorigin://faith?toggle=1`，应用侧新增 intent-filter 接收并切换
`sys_perf_faith`，弹「燃烧你的芯片吧，赛博坦怪兽」。

## 五、模块简介实时更新

执行器每轮把状态写回 `module.prop` 的 `description`：

```
description=作者 hmascs｜状态：待机｜CPU上限 -｜GPU上限 -｜信仰模式 0
```

状态取值：`信仰模式` / `性能调度` / `待机`。

## 六、游戏退出检测

1. **执行器侧**：每 2 秒比对 `sys_perf_faith_pkg` / `sys_perf_auto_pkg` 的进程是否还在，
   不在就关闭对应开关并 `kill -CONT thermal-engine`；
2. **Hook 侧**：新增 `handleGameForeground(boolean)`（`rb.a`、`yb.b` 两个实现，
   `ub.b` 是抽象接口无法 hook），参数为 false 时立即把 `faith/auto` 置 0 并弹
   Toast「游戏已退出，调度模块已关闭」。

## 七、CPU/GPU 锁频与模块对接

- 未就绪时：频率类功能（`perf.cpufreq.*`、`perf.gpu.*`、`perf.governor`、`perf.core_ctl.big`）
  直接返回"模块未安装或未运行，此功能不生效"，并指向「一键安装并启用」；
- 就绪时：`cpuMax` 额外下发 `sys_perf_c0_max/min`，`gpuFloor` 额外下发 `sys_perf_g_max`，
  由执行器持续维持（否则会被温控改写回去）；关闭时删除对应设置键。

## 八、顺带修的回归

`e9.x#e(pkg)` 的"回退到和平精英配置"原来对**所有包**生效（日志里能看到
`FI|回退用和平精英配置 com.dsharnessmobile.shell`），会污染非游戏场景。
现在只有 `pkg == 当前前台游戏（com.vivo.gameassistant.p#V().O0()）` 时才回退。

侧边栏频率面板改为开关控制（`game.panel.ui` → `Settings.Secure sys_perf_ui`），**默认关闭**，
避免再次干扰侧边栏收起与 Q 芯页打开。

---

# 0.28：频率面板改插到原列表里 + 超分支持判定 + 切开关自动重启魔盒

## 一、面板注入位置重做

上一版把控件挂在 `PerformancePanelPanelView` 根布局上做绝对定位底部条，结果是"浮"在面板上、
不随页面滚动，也和原 UI 不统一。这一版改成**找容器、插同级**：

1. 遍历面板视图树，找文本含「刷新率」的 TextView（即"调整屏幕刷新率"那一行）；
2. `TextView.parent` → 行内容器，再上两级即为承载各行的列表容器；
3. 把「CPU 最低/最高、GPU 最低/最高」四个滑块作为**同级兄弟**追加进去。

这样它们自然跟随列表滚动，字体与间距沿用原面板样式；每行结构也与原来一致（上标题、下 SeekBar）。
日志会打印容器类型：`PANEL|已插入频率行 16/14 @LinearLayout`。

## 二、终末地为什么没有超分

`superresolution.j#V0(String,Z)` 的第一道判据：

```
e0#Y0(Context,String)Z  →  false 时直接 return，并打印
                            "handGameForeOrBack return  isSupportPD = false"
之后  sget-object a8/f;->a  → 遍历 List<a8/e>，用 a8/e#p() 的包名与当前游戏比对
```

`e0#Y0` 本身就是对 `a8.f.a` 这个静态列表做包名匹配，所以**支持与否完全取决于列表里有没有这条**。
新增 hook `e0#Y0 → true`（仅在 Q2 解锁开启时），把第一道门放行。

需要说明：放行 `Y0` 之后，`V0` 仍会遍历列表找 `a8/e`，列表由解析器填充，
我们的注入只保证"解析出的内容包含该游戏"，**如果该进程的列表来自别处（缓存/云端），仍可能为空**。
这一条需要真机复测确认。

## 三、切换 hook 开关后自动重启游戏魔盒

`MainActivity.runAction()` 里新增判断：若该功能是 `KIND_HOOK` 且 `affectsGamecube(f)`
（目标/说明含 `gameassistant`、`vivo.gamecube`、`modeshift`、`superresolution`、
`frameinterpolation`、`gamefilter`，或 id 以 `q2.`、`game.`、`perf.monsterplus` 开头，
或为 `thermal.bypass.global`），则执行：

```sh
am force-stop com.vivo.gamecube
```

并 Toast「已重启游戏魔盒，重开游戏后生效」。这样开关切完不用手动去清进程。

## 四、当前 hook 数量

**34 条，全部 OK，零 MISS。**
频率面板开关 `sys_perf_ui` 已置 1（开启状态）。

---

# 0.29：注入位置改为「滚动列表的最底下」

## 一、上一版为什么还是错位

`findRowContainer` 取的是「刷新率」Textview 的 `parent.parent.parent`。
那个容器实际是**绝对定位布局**（RelativeLayout 系），把控件加进去就会和原有行叠在一起——
截图里 `CPU 最低频率` 压在「刷新率」上、`CPU 最高频率` 压在「亮度」上，正是这个原因。

## 二、这一版的做法

`findListContainer`：

1. 先遍历视图树找 `ScrollView`（含 `NestedScrollView`，按类名匹配）；
2. 取其第 0 个子 View 作为内容容器；若内容是"单子 LinearLayout"则再进一层；
3. 把四个滑块 + 保存/信仰按钮作为**该容器的最后一个子项**追加——即"可滑动列表的最底下"；
4. 找不到 ScrollView 时才退回旧的行容器逻辑。

日志会打印实际容器：

```
PANEL|已插入频率行 16/14 @LinearLayout
```

若打印的是 `未找到可插入的列表容器`，说明该面板没有 ScrollView，需要换判据。

## 三、当前状态

- 已安装 0.30.0，`sys_perf_ui=1`，gamecube 进程已清空；
- 待用户重开侧边栏后确认：滑块应出现在列表最底部，随列表一起滚动，
  不再与「刷新率 / 亮度 / 触控采样率」重叠。

## 四、仍未解决

- 终末地（`com.hypergryph.endfield`）Q 芯面板仍只有「游戏光线追踪 / 游戏视效增强」，
  没有超分/超帧条目。`e0#Y0` 已放行，但 `j#V0` 之后还要在 `a8.f.a` 列表里找到该游戏的
  `a8/e` 条目；若该进程的列表不是来自我们注入的解析流，仍会判定不支持。
  下一步需要确认 `a8.f.a` 的填充时机与来源（静态列表，可能在 Application 启动时一次性构建）。

---

# 0.30：控制台信仰按钮打通 + 面板改插竖向列表

## 一、ERR_UNKNOWN_URL_SCHEME 的原因

上一版给 `MainActivity` 加自定义 scheme 的 manifest 改动**没有生效**——
`dumpsys package com.fuckorigin.app` 里只看到 LAUNCHER 过滤器，没有 VIEW/fuckorigin。
所以浏览器无法解析 `fuckorigin://`，报 `ERR_UNKNOWN_URL_SCHEME`。

这次两件事都做了：

1. manifest 里真正给 `MainActivity` 加上 `VIEW + DEFAULT + BROWSABLE + data scheme="fuckorigin"`，
   重新安装后 `dumpsys ... | grep android.intent.action.VIEW` 计数为 1，scheme 已注册；
2. **控制台按钮不再依赖自定义 scheme**：改为直接调用守护进程自带的接口：

```js
const st = await (await fetch(API + '/api/status')).json();
await fetch(API + '/api/' + (st.unlocked ? 'relock' : 'unlock'), {method:'POST'});
```

`/api/unlock` 会创建 `/data/adb/fuckorigin/unlocked`，`/api/relock` 删除它。
执行器把该文件视为信仰模式开关：

```sh
[ -f /data/adb/fuckorigin/unlocked ] && faith=1
```

实测（真实链路，不是模拟）：

```
curl -X POST /api/unlock  → {"ok":true}，文件出现
CPU scaling_max_freq = 4320000
GPU devfreq max_freq = 1100000000
module.prop: 状态：信仰模式｜CPU上限 4320000｜信仰模式 1

curl -X POST /api/relock  → {"ok":true}，文件删除
```

## 二、执行器补 PATH

GPU 频率一度拉不上去，原因是脚本里 `tr / sort` 依赖 PATH，被 Termux 的 su 环境带歪导致取空。
现在脚本首行 `export PATH=/system/bin:/vendor/bin:/product/bin:$PATH`，
且 `tr/sort/tail` 改用绝对路径。

## 三、面板注入再改

- 取「刷新率」TextView 往上找**竖向 LinearLayout（childCount ≥ 2）**，把控件作为最后一项追加；
- 插进去时复制兄弟行的 `LayoutParams`（左右/上下 margin），保证宽度与原本对齐；
- 若容器是 `ConstraintLayout`，改用反射构造 `ConstraintLayout.LayoutParams`，
  `topToBottom` 指向最后一个子项，宽度 MATCH_PARENT。

日志会打印容器类型：`PANEL|已插入频率行 16/14 @LinearLayout`。

## 四、已知小问题

`module.prop` 的 `GPU上限` 一栏在信仰模式下显示的是"用户设定值"而不是当前最大值，
属于文案取值问题，不影响实际频率（实测已是 1100000000）。

---

# 0.31：侧边栏不显示的排查 + 神力全开

## 一、侧边栏完全不显示

现状：`com.vivo.gamecube:daemon` 在跑，`:assistantui` 没有进程；
`logcat -b crash` 为空，`/data/tombstones` 里没有对应时间点的记录，dropbox 也没有崩溃。
也就是说**没有观察到崩溃**，侧边栏进程根本没被拉起（它通常由游戏前台事件触发）。

为便于二分定位，这一版加了**全局熔断开关**：

```sh
settings put secure sys_perf_hooks 0   # 所有 hook 立即按原厂逻辑
settings put secure sys_perf_hooks 1   # 恢复
```

实现上在 `refreshCfg()` 里读这个键，为 0 时把四个能力开关全部置零，
于是每条 hook 的 `unlockAll()` 都返回 false，等价于没装模块——但不用卸载。

同时把上一版有风险的改动收窄：

- `e0#Y0`（超分支持判定）原来对**所有包**返回 true，会让 gamecube 把任意应用都当成超分游戏；
  现在只有 `pkg == 当前前台游戏` 时才放行；
- 侧边栏频率面板 `sys_perf_ui` 保持 **0（关闭）**，等侧边栏恢复正常后再单独开。

排查顺序建议：

1. 先按现状进游戏，看侧边栏是否出现；
2. 不出现 → `settings put secure sys_perf_hooks 0`，`am force-stop com.vivo.gamecube`，再进游戏；
3. 若熔断后能出现 → 是我们的 hook 引起，按 `hook.master` 逐项二分；
4. 若熔断后仍不出现 → 与模块无关，需查 gamecube 自身的服务状态（此时可临时禁用模块验证）。

## 二、神力全开按钮

首页卡片最顶部新增一个红字按钮「神力全开」：

- 字体 `17sp` 加粗、颜色 `#E53935`，浅红描边圆角背景；
- 点击后遍历全部 `Feature`，对 `enabled() == true` 的逐条重新执行 `Actions.apply(f, true)`；
- 结束后 Toast「神力全开：已重新下发 N 项」。

用途：模块重装、设备重启、或温控把频率改回去之后，一键把所有已开启的选项重新落地，
不需要逐项再点一次。

---

# 0.32：侧边栏自动触发失效的完整排查与接管

## 一、排查结论

| 检查项 | 结果 |
|---|---|
| 包状态 | 未禁用、`stopped=false`、`enabled` 正常 |
| 崩溃 | `logcat -b crash` 为空，`/data/tombstones` 无对应记录，dropbox 无崩溃 |
| 进程 | 只有 `com.vivo.gamecube:daemon`，**没有 `:assistantui`** |
| 服务组件 | `com.vivo.gamecube/com.vivo.gameassistant.AssistantUIService` 存在且可用 |
| 手动启动 | `am start-service -n .../AssistantUIService` → `:assistantui` 正常拉起（pid 27136） |
| 关键设置 | `current_game_package` **为空**，`current_background_game_package=com.dsharnessmobile.shell`（残留） |
| 系统游戏服务 | `dumpsys game` 正常，`com.vivo.gamewatch`、`com.vivo.gamespace` 都在跑 |
| 悬浮窗权限 | `SYSTEM_ALERT_WINDOW: default`（正常） |

→ 组件本身没坏，**是"游戏前台 → 拉起侧边栏"这个自动触发失效了**：
gamecube 始终没有把前台游戏写进 `current_game_package`，于是 daemon 不会去 start `AssistantUIService`。
这也解释了为什么手动启动后侧边栏能出来。

## 二、接管方案

root 执行器 `fo_perf.sh` 增加 `ensure_sidebar()`，每 10 秒检查一次：

1. 从 `dumpsys activity activities` 取 `topResumedActivity` 的包名；
2. 排除 `com.vivo.*`、`com.android.*`、本应用；
3. 判定"是不是游戏"用三个来源任一命中：
   - `/system/etc/gamecube/SuperResolutionConfigList.xml` 里的 `pkgName`；
   - `Settings.Global vsr_whitelist_apps`；
   - `Settings.Secure sys_perf_games`（预留，可手工添加）；
4. 命中且 `com.vivo.gamecube:assistantui` 不在运行时，执行
   `am start-service -n com.vivo.gamecube/com.vivo.gameassistant.AssistantUIService`，
   并写日志 `sidebar_拉起 <包名>`。

同时把 `com.hypergryph.endfield` 追加进 `vsr_whitelist_apps`——
它不在磁盘上的配置表里（我们的补充只存在于注入的内存解析流），
不加白名单 watchdog 不会对它触发。

## 三、日志判据

```sh
tail -f /data/adb/fuckorigin/log/perf.log | grep sidebar_
```

出现 `sidebar_拉起 com.tencent.tmgp.dfm` 即表示接管生效。

---

# 0.33：watchdog 之前根本没被调用（已修）

## 一、为什么上一版"接管"没生效

`ensure_sidebar()` 函数确实写进脚本了，但**调用点那处替换没有命中**，
于是函数定义了却从不执行——`grep ensure_sidebar` 只有定义、没有调用，
`perf.log` 里也从来没有 `sidebar_拉起` 记录。

这类"替换没命中却静默继续"的问题在这个项目里已经出现过两次（manifest 的 scheme 也是），
教训是：所有 sed/python 替换后必须 `grep` 验证目标行真的存在。

## 二、修复与实测

在 `while true` 循环开头补上：

```sh
tick=$((tick + 1))
[ $((tick % 5)) -eq 0 ] && ensure_sidebar
```

重启执行器后，把三角洲行动拉到前台，18 秒内：

```
21:20:01 sidebar_拉起 com.tencent.tmgp.dfm
```

进程列表同时出现：

```
13182 com.vivo.gamecube
19207 com.vivo.gamecube:assistantui   ← 新增
32730 com.vivo.gamecube:daemon
```

即"检测到游戏 → 主动拉起 AssistantUIService"这条链路确实通了。
（截图工具当前未授权无障碍，改用 `dumpsys window` 与进程列表佐证。）

## 三、关于"退出游戏自动关闭调度"

这个逻辑只做两件事：把 `sys_perf_faith` / `sys_perf_auto` 置 0，并 `kill -CONT thermal-engine`。
**它不会 force-stop 游戏魔盒，也不会杀 assistantui**，所以与侧边栏打不开无关。

真正会 force-stop 游戏魔盒的只有 App 里切换 hook 类开关时的
`ShellActions → am force-stop com.vivo.gamecube`（为了让新 hook 生效），
一次性动作，不会反复触发。

---

# 0.34：进程起来了但窗口不出现——补写 current_game_package

## 一、现象拆解

上一版 watchdog 生效后，实测：

```
21:20:01 sidebar_拉起 com.tencent.tmgp.dfm
ps: 19207 com.vivo.gamecube:assistantui   ← 进程确实起来了
```

但侧边栏窗口没有出现。说明卡在**服务内部判断**这一步，而不是进程没起。

对应的状态键：

```
settings get system current_game_package   → 空
```

`com.vivo.common.utils.l#N(Context)` 读它，`l#n3(Context,String)` 写它，
写入方是 `com.vivo.gameassistant.p#r2` 与另一个内部类——都是 gamecube 自己的游戏检测链路。
gamecube 判定"当前没有游戏"，于是 `AssistantUIService` 起来也不创建悬浮窗。

## 二、这一版补的动作

`ensure_sidebar()` 在拉起服务前，先补写这个键：

```sh
cur=$(settings get system current_game_package)
[ "$cur" != "$fg" ] && settings put system current_game_package "$fg"
say "sidebar_拉起 $fg"
am start-service -n com.vivo.gamecube/com.vivo.gameassistant.AssistantUIService
```

即"检测到配置表/白名单里的游戏在前台"时，同时把游戏状态补上，再拉起服务。

## 三、仍未验证的部分

受限于当前设备控制未授权（无障碍服务关闭、`android_screenshot` 返回未授权），
我无法直接看到悬浮窗是否出现，只能通过进程与设置状态推断。
需要用户在游戏内确认，并把 `perf.log` 与 `dumpsys window windows | grep gamecube` 的结果反馈。

---

# 0.35：链路实测打通 + 残留状态清理

## 一、实测：整条链路确实走通了

启动三角洲行动后每 5 秒采样一次：

```
t=5s  fg=com.tencent.tmgp.dfm/com.epicgames.ue4.GameActivity
      current_game=com.tencent.tmgp.dfm     ← 执行器写入成功
      assistantui=1                          ← 侧边栏进程已拉起
```

配合执行器日志：

```
21:20:01 sidebar_拉起 com.tencent.tmgp.dfm
21:23:28 sidebar_拉起 com.tencent.tmgp.dfm
```

说明"检测前台游戏 → 写 `current_game_package` → 启动 `AssistantUIService`"三步全部生效。

## 二、顺手修的两个副作用

1. **残留**：游戏退出后 `current_game_package` 一直是旧值，会让侧边栏在别的应用上也弹。
   现在每秒检查一次，若该包已无进程则 `settings delete system current_game_package`，
   日志打 `清理残留 current_game_package=<pkg>`。
2. **`null` 字符串误判**：`settings get` 对不存在的键返回字符串 `null`，被当成有效包名反复"清理"，
   已加 `case "$cur0" in ""|null)` 过滤。

另外发现执行器容易被重复拉起（多次 `nohup` 叠加），已清理为单实例；
若后续再出现多实例，应在脚本开头加 pidfile 互斥。

## 三、判据

游戏运行中执行：

```sh
dumpsys window windows | grep -i gamecube
```

- 有输出（悬浮窗条目）→ 窗口已创建，属于显示/权限层；
- 无输出 → 服务没创建窗口，需要往 `AssistantUIService` 内部判断继续查。

---

# 0.36：侧边栏问题的收敛结论与重启建议

## 一、已经确认的事实链

1. `AssistantUIService` 组件可用，手动 `am start-service` 能拉起 `:assistantui` 进程；
2. 我们的 watchdog 在游戏前台时确实会：
   - 写 `Settings.System current_game_package`；
   - 启动 `AssistantUIService`；
   日志有 `sidebar_拉起 com.tencent.tmgp.dfm`，进程列表有 `:assistantui`；
3. 但**悬浮窗始终不出现**，`dumpsys window windows | grep gamecube` 无输出。

## 二、指向系统层的关键证据

`com.vivo.upslide` 的 `GlobalStateMonitor` 持续打印：

```
mIsGameModeOn={mProperty=game_mode_state, mValue=false}
mIsGameSideSlideOpen=true
```

系统级"游戏模式"始终是 false。手动写 `settings put global is_game_mode 1` 也不会翻转它
（这条属性来自系统服务，不是设置项）。同时：

```
dumpsys game → No intervention found for package com.tencent.tmgp.dfm
```

即 GameManagerService 里没有该游戏的有效记录。

结论：**游戏魔盒的游戏识别链路在系统层被卡住了**，不是我们的 hook 能补的
（我们只能补 `current_game_package` 这类应用层状态，补不了系统服务的判定）。

## 三、已尝试的运行时手段（均无效）

- 重启 `com.vivo.gamewatch` / `com.vivo.gamespace` 进程；
- 写 `is_game_mode`；
- 写 `current_game_package` 后再拉起服务；
- 关闭全部 hook（`sys_perf_hooks=0`）后复测，现象一致。

## 四、建议：完整重启一次

重启会重新初始化 GameManagerService 与游戏识别链路，这大概率能恢复。
重启前已确认全部前置条件就位：

| 项目 | 状态 |
|---|---|
| LSPosed 记录的模块路径 | 与实际 APK 路径一致（`~~yy-_rLlo4AX7dbT_0df_bA==`） |
| 模块文件 | 11 项齐全（含 `bin/fuckorigind`、两份配置、webroot） |
| `post-fs-data.sh` | 会在 zygote 之前 bind 配置覆盖 |
| `service.sh` | 会拉起 `fo_perf.sh` |
| 执行器 | 当前单实例运行 |
| hook | 34 条（`sys_perf_hooks=1`） |
| 频率面板 | `sys_perf_ui=0`（先不开，避免干扰排查） |
| 信仰模式 | `sys_perf_faith=0` |

重启后请依次确认：

```sh
# 1) 执行器是否自启
ps -A -o args | grep fo_perf.sh
# 2) 配置覆盖是否生效（期望 15 / 12）
p=$(pidof com.vivo.gamecube:daemon)
grep -o "<version>[0-9]*</version>" /proc/$p/root/system/etc/gamecube/SuperResolutionConfigList.xml
# 3) 进游戏后侧边栏是否出现，以及
dumpsys window windows | grep -i gamecube
```

若重启后仍不出现，下一步只剩两个手段：
`pm clear com.vivo.gamecube`（会清掉游戏魔盒的用户设置，属于最后手段），
或改用「本应用内的控制面板」代替侧边栏注入。

---

# 0.37：按游戏魔盒自己的布局 id 注入频率控件

## 一、反汇编结论：面板是静态布局，不是动态建行

`PerformancePanelPanelView#i(Context)` 全文只有一次 `inflate`：

```
sget  R$layout;->performance_panel_layout:I
invoke-virtual LayoutInflater;->inflate(I, ViewGroup)V
sget  R$id;->performance_scroll_layout:I      → CustomVFastScrollView   （滚动容器）
sget  R$id;->performance_content_cl:I          → 内容容器（各行都在里面）
```

也就是说「亮度 / 网络 / 触控采样率」这些行是 **XML 里写死的**，
不存在"往列表数据源里插一条"的入口。要在它原本的结构里出现，
只能把控件加进 `performance_content_cl` 这个内容容器。

## 二、这一版的做法

不再靠"找含刷新率的文字"来猜容器，改为**按 id 精确取**：

```java
int id = ctx.getResources().getIdentifier(
        "performance_content_cl", "id", "com.vivo.gamecube");
View v = root.findViewById(id);      // 直接用 gamecube 自己的 id
```

- 取到后把「CPU 最低/最高、GPU 最低/最高 + 保存/信仰模式」作为该容器的子项追加；
- 容器若是 `ConstraintLayout`，则构造 `ConstraintLayout.LayoutParams`，
  `topToBottom` 指向最后一个子项、宽度 MATCH_PARENT，保证排在最底部且左右对齐；
- 取不到 id 时才回退到"找竖向列表"的旧逻辑。

## 三、当前状态

- 已安装 0.35.0，`sys_perf_ui=1`（注入开启），gamecube 进程已清空；
- 注入日志：`logcat -d -s FuckOrigin:I | grep PANEL`，
  期望 `PANEL|已插入频率行 16/14 @ConstraintLayout`；
- 需要用户划开侧边栏触发 `i(Context)` 才会执行注入。

## 四、如果仍不出现

说明 `performance_content_cl` 的布局类型与我们的 LayoutParams 不匹配
（例如它是 `LinearLayout` 但被设置成固定高度，或内容由 ViewStub 延迟加载）。
届时应改为 hook `inflate` 之后、`findViewById` 之前插入，
或直接给该容器加 `LinearLayout` 包裹层。

---

# 0.38：接线审计 + 清理不可用卡片 + 署名

## 一、功能可用后做的完整接线审计

### 1) 开关链路（App 写 → Hook 读）

| 键 | 写入方 | 读取方 | 实测 |
|---|---|---|---|
| `fuckorigin_q2` | `Bridge.sync` | `HookEntry.refreshCfg` | 1 |
| `fuckorigin_thermal` | 同上 | 同上 | 0（用户未开温控解除，符合预期） |
| `fuckorigin_monster` | 同上 | 同上 | 1 |
| `fuckorigin_fps` | 同上 | 同上 | 1 |
| `sys_perf_hooks` | `Actions`（全局熔断） | `refreshCfg` | 1 |
| `sys_perf_ui` | `Actions`（面板开关） | 面板注入 hook | 1 |

### 2) 频率/温控链路（App 写 → 执行器读）

| 键 | 写入方 | 读取方 | 实测 |
|---|---|---|---|
| `sys_perf_faith` / `_pkg` | `PanelUi`、控制台 `/api/unlock` | `fo_perf.sh` | 曾出现 faith=1 而 pkg 为空 |
| `sys_perf_auto` / `_pkg` | `HookEntry` 模式联动 | `fo_perf.sh` | auto=0 |
| `sys_perf_c0_min/max` | `PanelUi` 保存、`Actions.cpuMax` | `fo_perf.sh` | 1017600 / 4320000 |
| `sys_perf_g_min/max` | `PanelUi` 保存、`Actions.gpuFloor` | `fo_perf.sh` | 160000000 / 1100000000 |

### 3) 反向状态（执行器写 → gamecube/hook 读）

`current_game_package`：执行器写、`l#N` 兜底 hook 读，用于让服务认为"有游戏在跑"。

## 二、审计发现并修复的两个真问题

### 问题 1：APK 内置的模块缺执行器脚本

模块目录里没有 `fo_perf.sh`（它按设计放在 `/data/adb/fuckorigin/bin/`），
而我把模块树打包进 APK 时只拷了模块目录，导致**全新安装后没有执行器**，
滑块与信仰模式会全部无效。

修复：把最新版执行器一并打包，现在 `assets/module/` 共 **12 个文件**：

```
README.md  action.sh  bin/fuckorigind  customize.sh  fo_perf.sh
module.prop  post-fs-data.sh  service.sh  uninstall.sh  webroot/index.html
system/etc/gamecube/SuperResolutionConfigList.xml
system/etc/gamecube/FrameInterConfigList.xml
```

`ModuleStatus.install()` 会把它释放到 `/data/adb/fuckorigin/bin/fo_perf.sh`。

### 问题 2：信仰模式在"没有游戏包名"的情况下被打开

审计时现场是 `sys_perf_faith=1` 而 `sys_perf_faith_pkg` 为空：
这种状态下"游戏退出自动关闭"永远不成立，设备会一直满频（实测 CPU 4320000 / GPU 1100000000）。
已当场关闭并恢复 thermal-engine（状态回到 `S`）。

代码层修复：`PanelUi` 的信仰模式按钮现在要求 `currentGame()` 非空，
否则弹「未识别到游戏，请进入游戏后再开启」并拒绝开启。

## 三、界面调整

- **删除 6 张暂不可用的卡片**：`game.sidebar.entry`（未实现）、
  `ui.anim.curve`、`update.abe.freeze`、`bg.autostart`、`notif.marketing`、`island.position`（均为待逆向）；
  剩余 29 个功能条目。
- **关于页署名**：`作者 hmascs` 下面新增 `初期资金 Nick Ji`。

## 四、审计后的最终状态

```
hook OK = 35 条，MISS = 0
CFG2|q2=1 …（hook 能读到开关）
执行器单实例运行
模块守护 fuckorigind 运行
LSPosed 记录的模块路径 = 实际 APK 路径
```

## 五、已知未接线项（有意保留）

`sys_perf_games`：执行器会读（逗号分隔的额外游戏包名），但 App 侧没有写入入口，
属预留的手工兜底键，可通过
`settings put secure sys_perf_games com.xxx.game` 手工添加。

---

# 0.39：移除神力全开 + 详情页打开变慢的根因

## 一、去掉神力全开

首页顶部那个红字「神力全开」按钮已移除（方法体一并删除），
重新打包后 dex 里已无该字符串。

## 二、详情页卡顿 2~3 秒的根因

`featurePage()` 在构建详情页时同步调用：

```java
live.addView(kvRow("Hook 注入", HookStatus.text()));
```

而 `HookStatus.text()` 的实现是：

```java
ShellExec.su("logcat -d -s FuckOrigin:I 2>/dev/null | tail -80")
```

也就是说**每点一次卡片，都要在主线程上：启动 su → 拉起 shell → 跑 logcat → tail**。
KernelSU 的 su 本身有一次握手，加上 logcat 读缓冲，2~3 秒完全对得上。
`Anchor.check()` 早就改成后台线程了，唯独这一行漏了。

## 三、改法

改成占位 + 异步回填，和 `Anchor.check` 一致：

```java
final TextView hookTv = tv("检测中…", 14, R.color.dim, Typeface.NORMAL);
...
new Thread(() -> {
    final String st = HookStatus.text();
    ui.post(() -> hookTv.setText(st));
}).start();
```

「Hook 状态」专用页（`hook.status`）里同样的同步调用也改成异步：
先放一个 `TextView("检测中…")`，后台取完再 `setText`。

## 四、其他页面的检查结果

| 页面 | 是否阻塞 | 说明 |
|---|---|---|
| 普通功能详情 | 已修 | 原先唯一阻塞点是 `HookStatus.text()` |
| `hook.status` | 已修 | 改为异步回填 |
| `diag.live` | 否 | `Live` 用 `ShellExec.cat` 直接读 sysfs，不经过 su |
| `q2.thresholds` | 一次 su | `Thresholds.dump()` 单次 su，约 0.3~1s，属于诊断页可接受 |

## 五、验证

重新安装后点击任意功能卡片应立即进入详情页，
「Hook 注入」一行先显示"检测中…"，随后几百毫秒内变成实际状态。

---

# 0.40：游戏识别兜底 + 侧边栏触发配置 + VAA 白名单

## 一、识别不到游戏导致的误判

信仰模式按钮之前只认 `com.vivo.gameassistant.p#V().O0()`，
gamecube 自身识别失效时它返回空，于是弹「未识别到游戏」。

改为三级兜底：

1. `p.V().O0()`（gamecube 自身判定）；
2. `Settings.System current_game_package`（root 执行器写入的值）；
3. `ActivityManager.getRunningTasks(1)` 的栈顶包名（system uid 可读）。

`HookEntry` 在 `Application#onCreate` 时把 Context 回填给 `PanelUi.UI_CTX`，
供第 2、3 级使用。

## 二、侧边栏进程被拉起即套用配置

按用户思路，执行器不只依赖"检测到游戏前台"，还直接看侧边栏进程：

```sh
if ps -A -o name | grep -q "com.vivo.gamecube:assistantui"; then
    [ "$(get sys_perf_auto)" != "1" ] && 有已保存频率 && settings put secure sys_perf_auto 1
else
    [ auto=1 且 auto_pkg 为空 ] && settings put secure sys_perf_auto 0
fi
```

实测日志：

```
22:18:17 sidebar_应用配置 检测到侧边栏，auto=1
```

即侧边栏一起来，已保存的频率配置就被套用；侧边栏关掉则自动撤销。

## 三、VAA 抗闪烁为什么只有部分游戏有

VAA 有独立白名单 `Settings.Global vaa_whitelist_apps`，原值只有：

```
com.tencent.tmgp.pubgmhd;com.tencent.tmgp.projectg
```

已追加 `com.hypergryph.endfield`、`com.tencent.mf.uam`、`com.tencent.tmgp.dfm`。
相关代码路径：`com.vivo.common.utils.h0` 读 `vaa_whitelist_apps` 与
`key_super_resolution_vaa_state`，冲突仲裁用 `vaa_controlled`。

## 四、终末地仍然特殊的点

磁盘上的两张表里：

| 表 | 终末地 | 暗区突围 |
|---|---|---|
| SuperResolutionConfigList | **不在** | 在（state=2 / 72→144 / 2K 齐全） |
| FrameInterConfigList | **不在** | — |

终末地只能靠我们注入的内存流补条目；暗区本来就在表里，
却仍缺 VAA——说明这些游戏的 Q 芯面板还受其他判据控制（如 `QChipContentView#l(Z)`、
`isSupportPD`、或机型白名单），需要继续逆。

## 五、当前实机状态

```
侧边栏进程在跑 → auto=1（已套用保存配置并冻结 thermal-engine）
faith=0（控制台的 unlocked 文件已清理）
CPU 4320000 / GPU 1100000000（由 auto 拉满）
```

---

# 0.41：Q2 面板卡片的四个真实判据

## 一、继续借鉴那份 SimpleHook 配置的思路

那份 `游戏魔盒hook_0.1` 的本质是"把一串能力查询强制返回 true"。
之前我只是照抄了它列出的 11 条，但**它列的是它自己关心的点，不等于卡片显示的判据**。
这次换成从卡片构建代码反推。

## 二、反汇编 QChipContentView#l(Z)

```
0008  p.V().x0()                      → superresolution.j 实例
001c  ua.c#b(Context,String)Z         → 本游戏是否支持超分
0030  j#j1()Z                         → 超分引擎总判定
0062  ua.c#a(Context,String)Z         → 本游戏是否支持插帧
0072  h#F0()Z                         → 插帧引擎总判定
```

四个条件同时为真才会把「超级分辨率」「超级帧率」两张卡加进去；
任一为假就只有「游戏光线追踪 / 游戏视效增强」两张——正是终末地截图的样子。

## 三、新增四条 hook

| 目标 | 签名 | 作用 |
|---|---|---|
| `ua.c#a` | `(Context,String)Z` | 插帧支持 |
| `ua.c#b` | `(Context,String)Z` | 超分支持 |
| `superresolution.j#j1` | `()Z` | 超分引擎总判定 |
| `frameinterpolation.h#F0` | `()Z` | 插帧引擎总判定 |

均受 `unlockAll()` 门控（`fuckorigin_q2=1` 或 `fuckorigin_thermal=1` 时返回 true）。
实测绑定：

```
OK  a#a   OK  a#b   OK  j#j1   OK  h#F0
```

hook 总数增至 39 条，零 MISS。

## 四、与前一版的差别

前一版只改了 XML 配置（`state=2`、`ultraResolution` 等），
但**卡片可见性根本不看 XML 里那几个字段**，而是看上面四个运行时判定。
这就是为什么"配置改对了，卡片还是不出来"。

---

# 0.42：闪退根因 + 改用数据库白名单（借鉴 add_game_whitelist_v2.sh）

## 一、闪退的确切堆栈

```
java.lang.RuntimeException
  at com.vivo.gameassistant.homegui.sideslide.panels.qchip.QSuperFrameView.<init>
  at ...CommonSuperResolutionFrameView.c
  at ...CommonSuperResolutionFrameView.<init>
  at ...QChipContentView.onClick
```

点了 Q2 卡片、构造插帧视图时崩。
原因就是我们上一版把 `ua.c#a/b`、`j#j1`、`h#F0` 强行返回 true，
让 gamecube 以为"这个游戏支持超分/插帧"，但数据库里没有对应记录，
后续查表拿到 null 直接 NPE。

## 二、白名单的真实位置（用户提供的脚本揭示）

`add_game_whitelist_v2.sh` 说明了 gamecube 的支持列表存在**四个 SQLite 库**：

```
/data/user/0/com.vivo.gamecube/databases/
  frame_inter_list.db        frame_inter_support_game
  super_resolution_list.db   super_resolution_support_game
  game_cube.db               gameWhiteListTable / supportGamesTable /
                             operatedSupportGamesTable / funcTable
  support_dimension_list.db  game_dimen（按功能一条）
```

这才是"以它原本的方式"注入。实测终末地在库里：

```
super_resolution_support_game: 0 行
game_cube.gameWhiteListTable : 1 行
```

——游戏被认识，但没有任何超分/插帧支持记录。

## 三、本次注入的内容

先备份四个库到 `/sdcard/fuckorigin_db_<时间>.tar`，停掉 gamecube，然后按脚本的 schema 插入：

| 库 | 表 | 插入 |
|---|---|---|
| super_resolution_list | super_resolution_support_game | 1 行（state=2、frame 30→60、2K） |
| frame_inter_list | frame_inter_support_game | 1 行（boost 60→48→144） |
| game_cube | supportGamesTable / operatedSupportGamesTable / funcTable | 各 1 行 |
| support_dimension_list | game_dimen | 9 行（super_resolution / boost_frame / optimize_power / filter 等） |

校验：

```
SR 行: 1    FI 行: 1    dimen 行: 9    funcTable: 1
```

## 四、代码侧同步收敛

- `ua.c#a`、`ua.c#b`、`j#j1`、`h#F0` 四条强开 hook 改为**默认关闭**，
  受新键 `Settings.Secure sys_perf_force_cards` 控制（默认 0）；
- `e9.x#e` 的"回退和平精英 bean"与 `e0#Y0` 放行也同样只在 `force_cards=1` 时生效。

原因很直接：伪造能力 → 视图构造时拿到不匹配的数据 → 崩。
正经做法是往库里写记录，让 gamecube 自己认为支持。

## 五、访问路径的一个坑

本机 root shell 的 `/data/user/0` 被重定向（Termux 私有挂载），看不到其他应用数据；
必须走 `nsenter -t 1 -m --` 在 init 命名空间里操作，否则会误判"数据库不存在"。

---

# 0.43：插帧"没效果"的取证——命令一直是关闭态

## 一、关键发现

插帧的实际开关不是 UI 上的按钮，而是一条写进系统的命令串：

```
Settings.System gamecube_frame_interpolation = "0:-1:<pid>:0:0"
```

构造它的是 `com.vivo.common.utils.l0#b(Context, boolean isOn, int a, int b, int c, int d)`，
反汇编可见其末尾调用 `Settings$System.putString(cr, "gamecube_frame_interpolation", ...)`。
日志里对应 `IrisUtils: setFrameInterpolationConfig: Command for putting is: ...`。

实测历史值只有两种，**第一位永远是 0（未开启）**：

```
0:-1:12303:0:0
0:-1:21082:0:0
```

也就是说：不是"插帧效果弱"，而是**引擎根本没被要求启动**。

## 二、加了取证 hook（只记录，不强改）

对 `l0#b` 挂了 before hook，打印五个真实参数：

```
FI命令|on=false a=-1 b=21082 c=0 d=0
```

`a=-1` 表示 gamecube 自己也没算出目标帧率——这通常意味着它认为当前不是受支持的游戏场景。

## 三、为什么先不强改

如果直接把 `on` 置 true，而 `a` 仍是 -1，等于给显示驱动一个自相矛盾的组合
（开启但没有目标帧率），有概率造成花屏或驱动异常。
所以这一版只记录，等拿到"真正支持的游戏 + 极致帧率"下的参数组合再决定怎么改。

## 四、下一步需要的现场数据

在游戏里把「超级帧率」切到「极致帧率」，然后取：

```sh
logcat -d -s FuckOrigin:I | grep "FI命令"
settings get system gamecube_frame_interpolation
```

若此时 `a` 变成 60/90 之类的真实帧率，说明链路是通的，只是被某个判定挡在最后一步；
若 `a` 仍是 -1，说明 gamecube 层面的支持判定还没过，需要回到
`frameinterpolation.h#i1` / `a8.f` 那条链继续查。

---

# 0.44：把"魔盒里添加的应用"批量补齐白名单

## 一、先说清楚三张表的区别

`game_cube.db` 里：

| 表 | 行数 | 含义 |
|---|---|---|
| `gameWhiteListTable` | 5120 | 厂商云端下发的大名单（label 多为空） |
| `supportGamesTable` | 15 | **用户实际添加进游戏魔盒的应用** |
| `operatedSupportGamesTable` | 7 | 用户手动操作过的（带中文 label） |

截图里「其他」那一栏（123 云盘、哔哩哔哩、抖音、高德…）属于第二/第三类。
而超分/插帧引擎只认 `super_resolution_support_game` / `frame_inter_support_game` /
`game_dimen` 这三张表——**加了游戏魔盒 ≠ 拿到 Q 芯功能**。

## 二、批量补全

对 `supportGamesTable ∪ operatedSupportGamesTable` 的 16 个应用，
逐个检查三张表，缺则按 `add_game_whitelist_v2.sh` 的 schema 补：

```
SR:     177 → 193 行
FI:     267 → 283 行
dimen:  775 → 919 行（每个应用 9 个功能）
```

校验（微信为例）：

```
com.tencent.mm   SR=1  FI=1  dimen=9
```

## 三、可重复执行的脚本

新增 `/data/adb/fuckorigin/bin/fo_whitelist.sh`，逻辑是"遍历魔盒里添加的应用，
缺哪张表补哪张"，新增应用后再跑一遍即可：

```sh
su -c /data/adb/fuckorigin/bin/fo_whitelist.sh
```

脚本已打进 APK 的 `assets/module/`，一键安装时会一起释放；
执行前会输出各表总数，便于确认。

## 四、注意事项

- 改动前自动备份四个库到 `/sdcard/fuckorigin_db_<时间>.tar`；
- 必须 `am force-stop com.vivo.gamecube` 之后再写库，否则 Room 的 WAL 会覆盖写入；
- 批量补的是"全功能白名单"，是否真的生效还取决于游戏的帧率与分辨率场景。

---

# 0.45：超分/插帧同步到全部已安装应用 + 去重

## 一、范围扩大

上一版只补了"游戏魔盒里添加的应用"（16 个）。
这一版把目标改成：

```
supportGamesTable ∪ operatedSupportGamesTable ∪ 已安装的第三方应用（pm list packages -3）
```

共 **137 个包**，每个补三张表：

```
SR:     193 → 330 → 去重后 311
FI:     283 → 420 → 去重后 395
dimen:  919 → 2152 → 去重后 1987
```

## 二、插帧档位从单档改为多档

原来的 `boost_frame_values` 只有一档 `[{"a":60,"b":48,"c":144}]`，
所以「超级帧率」里只有 原始 / 节能 / 极致 三个固定项，部分游戏只显示两个。

现在改为三档，覆盖 60/90/120 三种原生帧率：

```
boost: [{"a":60,"b":48,"c":144},{"a":90,"b":72,"c":144},{"a":120,"b":60,"c":120}]
opti : [{"a":60,"b":30,"c":60},{"a":90,"b":45,"c":90},{"a":120,"b":60,"c":120}]
```

并对已经写入的旧行执行了 UPDATE（条件：`channel='#'` 且 boost 里不含 90）。

## 三、踩到的坑：重复行

`frame_inter_support_game` / `super_resolution_support_game` 只有自增主键，
**`pkg_name` 上没有唯一索引**，`INSERT OR IGNORE` 不会去重。
连续两次批量写入后出现重复：

```
FI 重复包数: 21    SR 重复包数: 16    dimen 重复组: 153
```

去重 SQL（保留最小 game_id）：

```sql
DELETE FROM frame_inter_support_game
 WHERE game_id NOT IN (SELECT MIN(game_id) FROM frame_inter_support_game GROUP BY pkg_name);
```

dimen 按 `(pkg_name, function)` 分组去重。执行后重复数归零。

## 四、当前状态

```
SR=311 轮  FI=395 轮  DIMEN=1987 轮
微信：state=2 / 2K，FI 行 1 条，boost 三档
```

`fo_whitelist.sh` 也同步更新（目标含已安装应用、多档插帧值），
但脚本里的内联 SQL 在 `su -c` 嵌套引号下容易出错，
实际批量操作建议用生成 SQL 文件再 `sqlite3 < file` 的方式。
