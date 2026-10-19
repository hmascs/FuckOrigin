# OriginOS 限制解除 / 隐藏能力解锁 —— 功能候选池 v1.0

> 本轮**只枚举，不写 Hook、不给实现、不做骨架**。
> 基准机：iQOO Neo10 Pro+（V2463A / PD2463）· Android 16 / OriginOS 6 · `PD2463D_A_16.1.20.2.W10` · KernelSU
> 依据标注：**原生设置项**（本机存在可读写的键/节点）｜**社区已验证**（同类工具在其他 ROM 上跑通的行为逻辑）｜**厂商限制**（官方声明或配置数据）｜**逆向候选**（本机 APK 里已定位到类/字符串/资源，但未验证 Hook 效果）

---

## 0. 标签口径

| 字段 | 取值 |
|---|---|
| 限制类型 | 包名/应用白名单、功能白名单、温度门控、机型/版本限制、隐藏开关、签名/安装校验、权限弹窗、云端策略、UI 入口隐藏、分辨率/刷新率前提 |
| 风险等级 | 低（进程内、可回滚、不影响系统服务）｜中（写 sysfs/属性、影响其他应用可见行为）｜高（system_server / 安全类 / 不可逆） |
| 温控/Q2 联动 | 是 / 否 |
| 来源依据 | 原生设置项 / 社区已验证 / 厂商限制 / 逆向候选 |

---

## 1. 总表

| featureId | 一级作用域 | 二级功能域 | 功能名称 | 限制类型 | 风险 | 温控/Q2联动 | 来源依据 |
|---|---|---|---|---|---|---|---|
| sys.install.sigbypass | 系统框架 | 安装与校验 | 签名校验全局放宽（仅 debug 包） | 签名/安装校验 | 高 | 否 | 社区已验证 |
| sys.install.downgrade | 系统框架 | 安装与校验 | 应用降级安装解除 | 签名/安装校验 | 高 | 否 | 社区已验证 |
| sys.install.unknownsrc | 系统框架 | 安装与校验 | 未知来源限制解除（系统应用视角） | 签名/安装校验 | 中 | 否 | 逆向候选 |
| sys.notif.manage_all | 系统框架 | 通知与权限 | 通知权限全量可见/可管 | 权限弹窗 | 中 | 否 | 社区已验证 |
| sys.perm.repeat_suppress | 系统框架 | 通知与权限 | 权限重复询问抑制 | 权限弹窗 | 中 | 否 | 逆向候选 |
| sys.thermal.status.bypass | 系统框架 | 性能与温控 | ThermalStatus 上报降级（`com.android.server.power.ThermalManagerService#postEventListener`） | 温度门控 | 高 | 是 | 本机实证（services.jar 中类与 vivo 扩展 `IVivoThermalManagerService` 均存在）＋社区已验证 |
| sys.thermal.brightness_keep | 系统框架 | 性能与温控 | 高温降亮度豁免 | 温度门控 | 中 | 是 | 社区已验证 |
| sys.freeform.count | 系统框架 | 界面与交互 | 分屏/小窗数量上限解除 | 功能白名单 | 中 | 否 | 社区已验证 |
| sys.applink.verify | 系统框架 | 界面与交互 | 应用跳转二次确认解除 | 隐藏开关 | 低 | 否 | 社区已验证 |
| sys.clipboard.white | 系统框架 | 隐私 | 剪贴板读取白名单放开 | 功能白名单 | 中 | 否 | 逆向候选 |
| ui.statusbar.clock_seconds | 系统界面 | 界面与交互 | 状态栏时间显示到秒 | 隐藏开关 | 低 | 否 | 原生设置项（`clock_seconds`/`show_seconds` 键存在于 SystemUI dex） |
| ui.statusbar.icon_blacklist | 系统界面 | 界面与交互 | 状态栏图标黑名单（批量隐藏） | 隐藏开关 | 低 | 否 | 原生设置项（`icon_blacklist`） |
| ui.notif.channel_bulk | 系统界面 | 通知与权限 | 通知渠道批量管理 | UI 入口隐藏 | 低 | 否 | 原生设置项 |
| ui.notif.lockscreen_limit | 系统界面 | 通知与权限 | 锁屏通知条数/内容限制解除 | 功能白名单 | 低 | 否 | 逆向候选 |
| ui.qs.grid | 系统界面 | 界面与交互 | 快捷设置行列数自定义 | 隐藏开关 | 低 | 否 | 社区已验证 |
| ui.statusbar.net_speed | 系统界面 | 界面与交互 | 状态栏网速显示/样式 | UI 入口隐藏 | 低 | 否 | 社区已验证 |
| ui.battery.style | 系统界面 | 界面与交互 | 电池图标样式/百分比内显 | 隐藏开关 | 低 | 否 | 社区已验证 |
| ui.anim.duration | 系统界面 | 界面与交互 | 动画时长/曲线持久化（脱离开发者选项） | 隐藏开关 | 低 | 否 | 社区已验证 |
| ui.blur.force | 系统界面 | 界面与交互 | 强制启用模糊（机型限制解除） | 机型/版本限制 | 中 | 否 | 社区已验证 |
| ui.aod.unlock | 系统界面 | 原子通知 | 息屏显示样式/入口解锁 | 机型/版本限制 | 中 | 否 | 社区已验证 |
| island.position | 系统界面 | 原子通知 | 原子岛展示位置/优先级 | UI 入口隐藏 | 中 | 否 | 逆向候选（`ISLAND_SHOW/ISLAND_HIDE/TRANS_ISLAND_*`） |
| island.style | 系统界面 | 原子通知 | 原子岛卡片样式/动画 | UI 入口隐藏 | 中 | 否 | 逆向候选 |
| island.app_allow | 系统界面 | 原子通知 | 允许任意应用上岛（白名单放开） | 包名/应用白名单 | 中 | 否 | 逆向候选 |
| set.hidden_entries | 系统设置 | 界面与交互 | 隐藏设置项解锁（工程/调试入口） | UI 入口隐藏 | 低 | 否 | 社区已验证 |
| set.app_detail_ctrl | 系统设置 | 界面与交互 | 应用详情页增强项（权限/存储/电池） | UI 入口隐藏 | 低 | 否 | 社区已验证 |
| set.max_fps_list | 系统设置 | 显示 | 刷新率上限列表放开（144Hz 可选） | 机型/版本限制 | 低 | 否 | 社区已验证 |
| set.refresh.custom | 系统设置 | 显示 | 自定义刷新率档位 | 分辨率/刷新率前提 | 中 | 否 | 逆向候选（`RefreshRateRequester` / `persist.sys.vivo.refresh_rate_adjuster`） |
| set.running_services | 系统设置 | 界面与交互 | 运行中服务入口 | UI 入口隐藏 | 低 | 否 | 社区已验证 |
| set.wifi_password_view | 系统设置 | 界面与交互 | 查看已保存 WiFi 密码 | 隐藏开关 | 低 | 否 | 社区已验证 |
| gc.sidebar.entry | 游戏魔盒 | 游戏与侧边栏 | 侧边栏自定义条目注入（温控解除入口） | UI 入口隐藏 | 低 | 是 | 逆向候选（`EdgeFunctionsAdapter` 已定位） |
| gc.panel.extend | 游戏魔盒 | 游戏与侧边栏 | 性能面板扩展（温度/频率/Q2 状态） | UI 入口隐藏 | 低 | 是 | 逆向候选（`PerformancePanelPanelView`） |
| gc.competition.force | 游戏魔盒 | 游戏与侧边栏 | 竞争模式强制激活 | 隐藏开关 | 中 | 是 | 社区已验证（FrameX 行为，键名待本机确认） |
| gc.bypass_charge | 游戏魔盒 | 性能与温控 | 直驱供电强开 | 隐藏开关 | 中 | 是 | 原生设置项（`persist.vivo.game_bypass_charge_flag`/`_support`） |
| gc.mode.switch | 游戏魔盒 | 性能与温控 | 性能模式直切（Monster/均衡/省电） | UI 入口隐藏 | 中 | 是 | 逆向候选（`ModeShiftBar`/`ModeType`） |
| gc.recording.unlock | 游戏魔盒 | 游戏与侧边栏 | 录制/回放分辨率与码率上限解除 | 功能白名单 | 中 | 否 | 逆向候选（`recorder/deathreplay/*`） |
| gc.shoulder.unlock | 游戏魔盒 | 游戏与侧边栏 | 肩键/触控映射白名单放开 | 包名/应用白名单 | 低 | 否 | 逆向候选 |
| q2.l1.supportlist | Q2门控与游戏识别 | 游戏与Q2 | Q2 支持游戏列表解除（任意游戏出现 Q 芯专区） | 功能白名单 | 中 | 是 | 厂商限制（官方 100+/140+ 覆盖）＋逆向候选 |
| q2.l2.pkg_verify | Q2门控与游戏识别 | 游戏与Q2 | 游戏魔盒包验证放开（任意包识别为游戏） | 包名/应用白名单 | 中 | 是 | 逆向候选（`com.vivo.common.utils.l#Z2`、`w0#a`、`v0#e`） |
| q2.l3.per_game_auth | Q2门控与游戏识别 | 游戏与Q2 | 单游戏授权门控解除 | 功能白名单 | 中 | 是 | 逆向候选（`game_memc_pp_apps`/`gpu_memc_tips_whitelist_apps`） |
| q2.l4.resolution_gate | Q2门控与游戏识别 | 游戏与Q2 | 2K/144Hz 前提校验解除 | 分辨率/刷新率前提 | 中 | 是 | 逆向候选（`isSupportBoostFrame2KProductNew`、`ultraResolution`） |
| q2.l5.nongame | Q2门控与游戏识别 | 游戏与Q2 | 非游戏应用 Q2 调用资格放开 | 包名/应用白名单 | 中 | 是 | 逆向候选（同 L2 判定点） |
| q2.l6.power_decouple | Q2门控与游戏识别 | 游戏与Q2 | Q2 功能与省电模式解耦 | 云端策略 | 中 | 是 | 逆向候选（`ChangeFrameReason.POWER_SAVING_MODE`、`power_save_type`） |
| q2.overheat.memc | Q2门控与游戏识别 | 性能与温控 | 插帧高温豁免 | 温度门控 | 低 | 是 | 逆向候选（`frameinterpolation.h#G0(D)Z` 已定位） |
| q2.overheat.sr | Q2门控与游戏识别 | 性能与温控 | 超分高温豁免 | 温度门控 | 低 | 是 | 逆向候选（`superresolution.j` 阈值字段） |
| q2.overheat.ve | Q2门控与游戏识别 | 性能与温控 | 视效增强高温豁免 | 温度门控 | 低 | 是 | 逆向候选（`displayenhance.c/f`） |
| perf.cpufreq.max | 性能与温控服务 | 性能与温控 | CPU 顶频锁定 | 机型/版本限制 | 中 | 是 | 原生设置项（sysfs） |
| perf.governor | 性能与温控服务 | 性能与温控 | 调度器切 performance | 隐藏开关 | 中 | 是 | 原生设置项（sysfs） |
| perf.gpu.floor | 性能与温控服务 | 性能与温控 | GPU 锁最高档 | 隐藏开关 | 中 | 是 | 原生设置项（sysfs） |
| perf.core_ctl | 性能与温控服务 | 性能与温控 | 核心调度重排（大核常驻） | 隐藏开关 | 中 | 是 | 原生设置项（core_ctl） |
| perf.thermal.trip | 性能与温控服务 | 性能与温控 | 抬 passive 温控档 | 温度门控 | 中 | 是 | 逆向候选（按 type 反查 zone） |
| perf.thermal.engine_off | 性能与温控服务 | 性能与温控 | 屏蔽 thermal-engine 配置（激进） | 温度门控 | 高 | 是 | 社区已验证（FuckOrigin Thermal 空文件占位） |
| perf.display.mode | 性能与温控服务 | 显示 | 显示 Mode 锁定（1080p@120 等） | 分辨率/刷新率前提 | 中 | 否 | 逆向候选（`/sys/class/drm/card0-DSI-1/modes`） |
| perf.gpu.render | 性能与温控服务 | 图形与渲染 | 强制 GPU 渲染/禁用 HW 叠加 | 隐藏开关 | 中 | 否 | 社区已验证 |
| perf.msaa | 性能与温控服务 | 图形与渲染 | MSAA/各向异性强制覆盖 | 隐藏开关 | 中 | 否 | 社区已验证 |
| cam.unlock_modes | 相机 | 相机与影像 | 机型/场景限制的算法模式解锁 | 机型/版本限制 | 中 | 否 | 社区已验证 |
| cam.watermark | 相机 | 相机与影像 | 水印/机型标识自定义 | 功能白名单 | 低 | 否 | 社区已验证 |
| cam.4k60 | 相机 | 相机与影像 | 4K60/高码率上限解除 | 功能白名单 | 中 | 否 | 社区已验证 |
| cam.thermal_exempt | 相机 | 相机与影像 | 高温禁用的计算摄影豁免 | 温度门控 | 中 | 是 | 逆向候选 |
| cam.multicam | 相机 | 相机与影像 | 多摄并发限制解除 | 功能白名单 | 中 | 否 | 逆向候选 |
| tel.record | 电话/通信 | 通话与通信 | 通话录音提示音解除 | 隐藏开关 | 中 | 否 | 社区已验证 |
| tel.volte_show | 电话/通信 | 通话与通信 | VoLTE/VoWiFi 开关强制显示 | 机型/版本限制 | 中 | 否 | 逆向候选 |
| tel.band | 电话/通信 | 通话与通信 | 频段/双卡能力放开 | 机型/版本限制 | 高 | 否 | 社区已验证 |
| pm.installer_risk | 应用安装与包管理 | 安装与校验 | 安装风险检测/云查杀跳过 | 云端策略 | 中 | 否 | 社区已验证 |
| pm.installer_info | 应用安装与包管理 | 安装与校验 | 安装包详细信息展示 | UI 入口隐藏 | 低 | 否 | 社区已验证 |
| pm.clone_limit | 应用安装与包管理 | 分身与多开 | 分身数量/包名白名单解除 | 包名/应用白名单 | 中 | 否 | 社区已验证（`com.vivo.doubleinstance`） |
| pm.freeze_app | 应用安装与包管理 | 后台与编译 | 应用冻结/停用不触发保护 | 隐藏开关 | 中 | 否 | 逆向候选 |
| sec.autorun | 安全/管家 | 后台与功耗 | 自启动管控白名单放开 | 包名/应用白名单 | 高 | 否 | 逆向候选（`com.iqoo.secure` `setDaemon`） |
| sec.root_check | 安全/管家 | 安装与校验 | Root 检测绕过 | 签名/安装校验 | 高 | 否 | 社区已验证 |
| sec.report_off | 安全/管家 | 隐私 | 使用数据上报关闭 | 云端策略 | 中 | 否 | 社区已验证 |
| sec.power_rank | 安全/管家 | 后台与功耗 | 耗电排行/电池健康详情 | UI 入口隐藏 | 低 | 否 | 社区已验证 |
| sec.ads | 安全/管家 | 界面与交互 | 管家/商店广告位移除 | 隐藏开关 | 低 | 否 | 社区已验证 |
| up.freeze | 系统更新 | 系统更新 | 更新服务冻结（updater/cota） | 云端策略 | 中高 | 否 | 原生设置项（`pm disable-user`） |
| up.version_spoof | 系统更新 | 系统更新 | 版本号/机型上报改写（影响更新判定） | 机型/版本限制 | 中 | 否 | 社区已验证 |
| up.downgrade | 系统更新 | 系统更新 | 系统降级包校验绕过 | 签名/安装校验 | 高 | 否 | 社区已验证 |
| up.sig_rewrite | 系统更新 | 系统更新 | 更新冻结状态的守护回写对抗 | 云端策略 | 高 | 否 | **本机未发现证据**：iqoo.secure 与 vivo daemonService 均无回写 thermal/cpufreq/settings 的代码，见 `iqoo.secure与守护进程逆向.md`；降级为观察项 |
| cloud.probe | 云控与策略对抗 | 云控对抗 | 云控拉取链路探测（abe/unifiedconfig） | 云端策略 | 低 | 否 | 逆向候选（`unifiedconfig.db` 五表已实测） |
| cloud.freeze_version | 云控与策略对抗 | 云控对抗 | 冻结版本字段（等价 `pref_local_max_version`） | 云端策略 | 中 | 否 | 逆向候选 |
| cloud.forge_receipt | 云控与策略对抗 | 云控对抗 | 伪造"已应用/已上报"回执 | 云端策略 | 中高 | 否 | 逆向候选 |
| cloud.return_snapshot | 云控与策略对抗 | 云控对抗 | 读入口返回冻结快照 | 云端策略 | 中 | 否 | 逆向候选 |
| cloud.db_backup | 云控与策略对抗 | 云控对抗 | 配置 DB 备份与一键回滚 | 云端策略 | 低 | 否 | 逆向候选 |
| link.mishare | 跨设备互联 | 跨设备 | 跨屏互联机型白名单绕过 | 机型/版本限制 | 中 | 否 | 社区已验证 |
| link.cast_quality | 跨设备互联 | 跨设备 | 投屏分辨率/码率上限解除 | 功能白名单 | 中 | 否 | 社区已验证 |
| sensor.global_off | 传感器框架 | 传感器与隐私 | 传感器全局禁止（非仅开屏） | 隐藏开关 | 中 | 否 | 社区已验证 |
| sensor.fake | 传感器框架 | 传感器与隐私 | 传感器数据伪造/注入 | 隐藏开关 | 高 | 否 | 逆向候选 |
| dev.eng_mode | 开发者选项/工程模式 | 设备与调试 | 工程模式常驻入口 | UI 入口隐藏 | 中 | 否 | 逆向候选 |
| dev.hw_debug | 开发者选项/工程模式 | 设备与调试 | 高级图形调试项常驻 | UI 入口隐藏 | 低 | 否 | 社区已验证 |
| dev.adb_wifi | 开发者选项/工程模式 | 设备与调试 | 无线 ADB 开关 | 隐藏开关 | 低 | 否 | 原生设置项（`adb_wifi_enabled`） |
| dev.thermal_diag | 开发者选项/工程模式 | 设备与调试 | 温控诊断输出（zone/tshell/冷却设备） | UI 入口隐藏 | 低 | 是 | 原生设置项 |

---

## 2. 按作用域速览（每一行 = 被解除的具体限制）

**系统框架**：安装器对 debug 包的签名强校验、降级安装的版本回退拦截、通知权限的隐藏白名单、
权限弹窗的重复询问、`ThermalManagerService#postEventListener` 的温度事件下发
（本机 services.jar 内已确认该类，并多出一个 vivo 私有 `IVivoThermalManagerService`，说明厂商在 AOSP 之上又包了一层）、高温时的亮度压制、
分屏/小窗的实例数上限、应用互跳的二次确认、剪贴板读取的调用方白名单。

**系统界面**：状态栏时间只到分钟、图标只能逐个开关、通知渠道要进二级页、锁屏通知条数与内容被裁、
快捷设置行列固定、网速/电池样式不可选、动画时长绑死在开发者选项、模糊效果按机型下发、
息屏样式按机型解锁、原子岛的展示位置与上岛应用白名单。

**系统设置**：工程/调试入口默认隐藏、应用详情页缺增强项、刷新率上限列表被裁到 120Hz、
刷新率档位不可自定义、运行中服务与 WiFi 密码入口被藏。

**游戏魔盒**：侧边栏条目只能选厂商内置、性能面板只显示厂商字段、竞争模式需满足条件才可开、
直驱供电藏在场景判定后、性能模式要绕 UI 点击、录制码率按白名单给、肩键映射绑游戏白名单。

**Q2 门控与游戏识别**：见第 3 节（六层）。

**性能与温控服务**：CPU 上限被热控压低、调度器被锁在 walt、GPU 只能降不能顶、
大核常驻数受限、passive 温控档位偏低、thermal-engine 整包生效、
显示模式只能系统选、GPU 渲染与 MSAA 由应用自决。

**相机**：算法模式按机型/场景白名单给、水印机型标识固定、4K60 与码率上限、
高温时计算摄影被禁、多摄并发限制。

**电话/通信**：通话录音提示音强制、VoLTE/VoWiFi 开关按运营商隐藏、频段/双卡能力按机型裁剪。

**应用安装与包管理**：安装风险检测走云端、安装包信息不展示、分身数量与包名白名单、
应用冻结触发保护。

**安全/管家**：自启动管控名单、Root 检测、使用数据上报、耗电排行与电池健康详情、广告位。

**系统更新**：updater/cota 冻结、版本号上报、降级包校验、冻结状态的守护回写。

**跨设备互联**：跨屏互联机型白名单、投屏分辨率/码率上限。

**传感器框架**：传感器只能"开屏禁止"、数据注入。

**开发者选项/工程模式**：工程模式入口、图形调试项、无线 ADB、温控诊断。

---

## 3. Q2 六层门控（本机真实落点）

> 关键结论：**OriginOS 的"包验证"不在 UI 层，而在 `com.vivo.common.utils` 的三个工具类里**，
> 这与 ColorOS 的 `com.coloros.gamespaceui.config.cloud` 结构不同——不能套用 OShin 的类名。

| 层 | 解除的限制 | 本机落点（逆向候选） | 证据 |
|---|---|---|---|
| **L1 支持列表** | 不在官方支持列表的游戏，侧边栏不出现/灰显 Q 芯专区 | `com/vivo/common/supportlist/pojo/SupportGameItem`、`ConfiguredFunction`（`BOOST_FRAME_V3_90_72_144` 等枚举）；`a8/a` 解析 `/system/etc/gamecube/*ConfigList.xml` | ✅ XML 实测：`SuperResolutionConfigList.xml` v12、`FrameInterConfigList.xml` v11（PD2463 段 48℃/45℃） |
| **L2 包验证** | "哪些包算游戏"由工具类判定，非游戏包拿不到插帧调度权 | `com.vivo.common.utils.l#Z2(String, Context)→Z`（日志 `qryIsGameInConfig pkg=` / `isGame=`）；`w0#a(Context,String)→Z`（`isGame:`）；`v0#e(Context)→List`（`qryIsGameFromAbe`，即**从云控取游戏列表**） | ✅ 类/方法/字符串均已在本机 19,382 类索引中定位 |
| **L3 单游戏授权** | 每款游戏需在魔盒内单独授权，且运行时还要在侧边栏手动开 | `frameinterpolation/i` 的白名单键：`game_memc_pp_apps`、`gpu_memc_tips_whitelist_apps`、`game_memc_vsr_mutex_apps` | ✅ 字符串实证；授权状态的读写点待定位 |
| **L4 分辨率/刷新率前提** | "超清/2K 才可超分""144Hz 才可超帧"的 UI 校验 | `com/vivo/common/a#s0`（`isSupportBoostFrame2KProductNew`）；`com/originui/core/utils/RefreshRateRequester`（`persist.sys.vivo.refresh_rate_adjuster`、`debug.rms.refresh_rate_adjuster_version`）；XML 属性 `ultraResolution` | ✅ 方法名与属性实测；"前提不满足置灰"的 UI 判定点待定位 |
| **L5 非游戏应用** | 模拟器/播放器/串流工具不被当作 Q2 可优化对象 | 与 L2 同一判定点（`l#Z2` / `v0#e` 的游戏集合）；旁证：`com.vivo.gamelib` 维护游戏库 | 🟡 与 L2 共用 Hook 点即可覆盖 |
| **L6 与省电/温控解耦** | 省电模式静默禁插帧；高温禁 Q2（无提示） | `frameinterpolation/ChangeFrameReason` 枚举含 `POWER_SAVING_MODE`；`lb9/b` 监听 `power_save_type`；`CommunicationProvider#z/A`（`lastmode is power save mode or high quality mode`）；温控侧见 `h#G0(D)Z` | ✅ 枚举与字符串实证 |

**六层的覆盖关系**：L5 ≈ L2（同一判定点）；L1 与 L4 是两条独立读取（支持列表 vs 产品能力/显示前提）；
L3 与 L6 各自有自己的状态源。**一个 Hook 点覆盖不了六层**，最少需要 3 个：包验证（L2/L5）、支持列表（L1）、
状态门控（L3/L4/L6 分别处理）。

> ⚠️ 放开白名单后，非适配游戏的超分/超帧可能渲染异常或额外发热，所以每条 Q2 条目都要求**按游戏单独启停**的回退入口，
> 而不是一个全局开关。

---

## 4. 第一梯队（8–10 项，优先做）

选入标准：限制明确可定位 + 风险低/中 + 有外部验证或本机实证。

| # | featureId | 为什么进第一梯队 | 风险 |
|---|---|---|---|
| 1 | `q2.l2.pkg_verify` | 本机已定位到**具体方法签名**（`l#Z2(String,Context)→Z`），且 ColorOS 侧同类逻辑已有社区验证，行为逻辑清晰 | 中 |
| 2 | `q2.l1.supportlist` | 支持列表来自**只读 XML**（已实测内容），判定点集中，不改系统数据 | 中 |
| 3 | `q2.overheat.memc` / `q2.overheat.sr` | 温度门控点已实证（`h#G0` / `j` 阈值字段），低风险、可立即验证 | 低 |
| 4 | `q2.l6.power_decouple` | 枚举 `POWER_SAVING_MODE` 明确指向原因字段，改判定即可 | 中 |
| 5 | `ui.statusbar.clock_seconds` | 原生键名实锤（SystemUI dex），零 Hook 成本 | 低 |
| 6 | `up.freeze` | `pm disable-user` 可逆，组件与权限已核实 | 中高 |
| 7 | `gc.sidebar.entry` | 侧边栏注入点已完整逆向（BRVAH 单布局 + 计数+1），是所有 Q2 开关的**用户入口** | 低 |
| 8 | `perf.refresh.custom` / `set.max_fps_list` | 刷新率仲裁面已摸到（RMS 属性 + `RefreshRateRequester`），与 L4 前提同源 | 中 |
| 9 | `dev.thermal_diag` | 纯读，用于验证前八项是否真生效（没有它，其他项无法验收） | 低 |
| 10 | `cam.watermark` / `cam.unlock_modes` | 社区验证充分、收益直观，且与温控/Q2 无关，可作为"低风险交付面" | 低/中 |

**明确不进第一梯队**：`sys.install.*`（签名/降级，破坏完整性）、`sec.root_check`（安全类）、
`tel.band`（基带，可能无信号）、`up.downgrade`（降级包，可能变砖）、`sensor.fake`（隐私争议）。

---

## 5. 待真机确认的三个关键点

1. **`l#Z2` 的返回值语义**：`true` 表示"是游戏"还是"允许游戏优化"？需一次注入日志确认（它同时打印 `isGame=`）。
2. **`v0#e` 与云控的关系**：`qryIsGameFromAbe` 说明游戏集合可能由 `com.vivo.abe` 下发——若是，L2 的放开需要同时考虑云控覆盖（与 `cloud.return_snapshot` 联动）。
3. **vivo 私有 `IVivoThermalManagerService`**：本机 services.jar 里出现 `postEventListenerCurrentTemperaturesLocked`
   等符号，说明 vivo 在 AOSP `ThermalManagerService` 外自建了一层接口。若走系统框架路线，需先确认
   `ThermalStatus` 到底由哪一层决定（AOSP 层 vs vivo 层），否则 Hook 了 AOSP 层仍会被 vivo 层覆盖。
4. **L4 的 UI 校验位置**：`isSupportBoostFrame2KProductNew` 是产品能力判定，但"置灰"发生在面板层（`RefreshRateView`/`SamplingRateView`/`CommonSuperResolutionFrameView`），两者可能不同源。

---

*本文档只做枚举与依据标注；所有"逆向候选"条目在进入实现前，必须先按第 5 节完成真机确认。*
