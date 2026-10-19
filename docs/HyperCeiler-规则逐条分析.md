# HyperCeiler 规则逐条分析：systemframework / systemui / securitycenter

> 承接 `HyperCeiler-源码通读笔记.md`。上一轮读了架构，这一轮按笔记建议的三个作用域逐条扫。
> 方法：脚本扫描 629 条规则，抽取 `findClass` / `hook` 目标 / 字符串锚点 / 手法（常量返回、字段改写、DexKit），
> 再对其中 8 条与本项目直接相关的规则做全文精读。

---

## 0. 统计先看（能解释很多设计取舍）

| 作用域 | 规则数 | 平均行数 | 用 DexKit 的比例 |
|---|---|---|---|
| systemframework | 76 | 99 行 | **0%** |
| systemui | 136 | 170 行 | 0.7%（1 条） |
| securitycenter | 56 | 113 行 | **68%（38 条）** |
| 全部 68 个作用域 | 629 | — | 114 条（18%） |

**结论**：DexKit 的使用率与该目标的「混淆/易变程度」强相关，而与复杂度无关。
- systemframework 直接写 `com.android.server.*` / `com.miui.server.*` 类名——AOSP 与 MIUI 的框架类名稳定，**一条 DexKit 都不用**；
- securitycenter 大量用 DexKit——MIUI 安全中心的业务类被混淆且频繁改版。

→ 对 OriginOS 的含义：`com.vivo.gamecube` 的**类名完全没混淆**（我们已验证 19,382 个类可读），
所以我们**不需要 DexKit**，离线索引（`dx.py`）+ 字符串锚点就够；只有将来做 `com.iqoo.secure`（它才可能混淆）时才需要。

---

## 1. 八条精读：机制 → OriginOS 对应点

### 1.1 `systemframework/others/DisableThermal`（温控事件，已读）
- **MIUI 机制**：`com.android.server.power.ThermalManagerService#postEventListener` 的 before 钩子把参数置空，
  等于不再向监听者分发温度事件。
- **OriginOS 对应**：本机 `services.jar` 里同名类存在，**且多出一个 vivo 私有 `IVivoThermalManagerService`**
  与 `postEventListenerCurrentTemperaturesLocked` 符号。
- **可移植性**：逻辑可对齐，但落在 `system_server`（高风险）。我们的第一梯队方案（在 gamecube 进程内解门控）风险低一个数量级，**不建议**改走这条路；
  若要做，必须先确认「ThermalStatus 由 AOSP 层还是 vivo 层决定」，否则 Hook 了 AOSP 层会被 vivo 层覆盖。

### 1.2 `systemframework/others/ThermalBrightness`（高温降亮度）
- **MIUI 机制**：在 `DisplayPowerControllerImpl` 构造函数后把三个标志位全置 false：
  `SUPPORT_TEMEPERATURE_CONTROL`（注意 MIUI 源码里这个拼写就是错的）、
  `mThermalBrightnessControlAvailable`、`mApplyThermalBrightnessRate`；
  并对 `AutomaticBrightnessControllerImpl` / `ThermalBrightnessController` / `TemperatureController` /
  `ThermalHelper` / `ThermalObserver` 做同样处理。
- **OriginOS 对应**：`com.android.server.display.*` 是 AOSP 类，OriginOS 也有；但 vivo 的亮度策略可能走自己的 `BrightnessUtils`。
- **可移植性**：**思路可搬（改标志位而不是拦温度）**，落点需本机确认；仍属 system_server 域（高风险）。

### 1.3 `powerkeeper/DisableGetDisplayCtrlCode`（热控反馈码）
- **MIUI 机制**：`com.miui.powerkeeper.feedbackcontrol.ThermalManager#getDisplayCtrlCode()` → 直接返回 `0`。
- **OriginOS 对应**：vivo 侧等价物是我们已定位的显示/性能链路（`RefreshRateRequester`、`displayenhance`、`com.vivo.common.e`）。
- **可移植性**：**这是"热控→显示"这层最经济的 Hook 点**（一个 int 返回值），值得在 OriginOS 上找同名语义的
  "反馈码/降级码"方法；找到了就比改温度安全。

### 1.4 `powerkeeper/LockMaxFps`（锁帧率上限）
- **MIUI 机制**：`com.miui.powerkeeper.statemachine.DisplayFrameSetting#setScreenEffect(3 参)` → 返回 null，即不施加帧率限制。
- **OriginOS 对应**：`com.originui.core.utils.RefreshRateRequester` + `persist.sys.vivo.refresh_rate_adjuster` + RMS。
- **可移植性**：**行为可直接对齐**——找 vivo 的"下发刷新率/帧率效果"方法并置空。这比改 `peak_refresh_rate` 更稳。

### 1.5 `securitycenter/sidebar/game/GamePerformanceWildMode`（游戏狂暴模式）
- **MIUI 机制**：DexKit 用字符串 `support_wild_boost` 找到能力判定方法 → 常量 `true`。
- **OriginOS 对应**：gamecube 里同构的能力布尔方法，例如 `com/vivo/common/a#s0`（`isSupportBoostFrame2KProductNew`）
  与 Monster 模式判定（`ModeShiftBar` / `ModeType`）。
- **可移植性**：**结构完全同构**：MIUI 用 feature flag 方法，vivo 用机型常量表 + XML。
  差别是 vivo 有多机型常量（`PD2xxx`），所以要按机型判断而不是一刀切 true。

### 1.6 `systemui/statusbar/icon/all/StatusBarIcon`（状态栏图标）
- **MIUI 机制**：拿到 `MiuiIconManagerUtils` 的**静态名单** `RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST`，
  按用户选择把图标名（`vpn`/`alarm_clock`/`nfc`/`wifi`…）加进/移出这两个 List。
- **OriginOS 对应**：AOSP 的 `Settings.System.icon_blacklist`（SystemUI dex 里已确认该键存在）+ vivo 的
  `VivoNotificationIconsInteractor`。
- **可移植性**：**OriginOS 更简单**——不需要 Hook，直接写 `icon_blacklist` 即可（我们已列入第一梯队）。

### 1.7 `updater/DeviceModify` + `VersionCodeNew`（升级欺骗）
- **MIUI 机制**：DexKit 找 `SystemProperties.get` 的包装方法与 `com.android.updater.Application` 的版本构造，
  按偏好把机型/版本号替换成目标值（含 `Build.MODEL`/`Build.DEVICE`）。
- **OriginOS 对应**：`com.bbk.updater`（已确认申请 `INSTALL_PACKAGES`/`REBOOT`/`WRITE_SECURE_SETTINGS`，
  且依赖 `com.vivo.abe` 的 configlist）。
- **可移植性**：**行为可对齐但风险高**——版本欺骗会影响 OTA 判定与保修状态；
  我们第一梯队用的是"`pm disable-user` 冻结 + abe 读侧冻结"，比欺骗版本更干净。

### 1.8 `joyose/DisableCloudControl`（云控，已读）
- **MIUI 机制**：DexKit 用字符串 `"job exist, sync local..."` 定位同步方法（void 返回）→ Hook 掉同步动作。
  另一条 `EnableGpuTuner` 用 `"GPUTUNER_SWITCH"` 找布尔方法 → true。
- **OriginOS 对应**：本机是 `com.vivo.unifiedconfig.UnifiedConfigProvider#call("getEnryptedStandardConfig", ...)`
  与 `com.vivo.abe` 的 `UnifiedManager`；包验证侧另有 `com.vivo.common.utils.l#Z2` / `w0#a` / `v0#e`。
- **可移植性**：**思路同构（字符串锚点 + DexKit），锚点必须换**。我们的离线索引已经能给出等价锚点。

---

## 2. 三条从大面积扫描里看出来的规律

**规律一：越靠近 AOSP 的部分越敢直接写类名。**
systemframework 76 条里 0 条 DexKit，全部直接 `findClass("com.android.server.xxx")`。
反过来说，**凡是厂商自研业务层（安全中心、游戏中心、商店），就必须 DexKit 或字符串锚点**。

**规律二："改判定"比"改状态"多。**
629 条里 `returnConstant` 明确出现 60+ 次，字段改写（`setBooleanField`/`setObjectField`）大量出现，
真正去写 sysfs / settings 的少数。说明**绝大多数限制是"一个布尔/一个码"决定的**，而不是需要持续维持的状态。
—— 这一条直接支持我们第一梯队把 `q2.l2.pkg_verify`（`l#Z2` 返回 true）排在第一位。

**规律三：作用域页面的组织方式与规则数无关，与"用户心智"有关。**
home 有 139 条（桌面定制），systemui 136 条，但两者在 UI 上都只是"一个作用域 → 一页偏好"。
我们按功能域组织（8 个域 / 24 条），用户心智成本更低——这是我们的优势，不该为了"照搬"而放弃。

---

## 3. 对第一梯队的两处修正建议

1. **新增候选：`q2.thermal.feedback_code`**（对应 1.3 的思路）
   —— 在 OriginOS 上找"热控→显示/性能"的反馈码方法（int 返回），比改温度源更安全、更容易验收。
   优先级：仅次于 `q2.l2.pkg_verify`，因为它是"一个返回值"级别的最小改动。

2. **下调 `up.version_spoof` 的优先级**（原枚举文档里给的是"社区已验证"）
   —— 看完 `DeviceModify`/`VersionCodeNew` 的实现后确认：版本欺骗要同时改 `SystemProperties`、
   `Build` 静态字段和 updater 的构造参数，面比想象大，且会污染保修与 OTA 判定。
   维持"冻结 + 读侧拦截"即可，不做欺骗。

---

## 4. 未读完的部分

- systemui 136 条里只精读了 3 条（StatusBarIcon、StatusBarClockNew 的目录、HideBatteryIcon 同类），
  其余按目标类归类（`MiuiClock`/`MiuiBatteryMeterView`/`NetworkSpeedController`/`MiuiIconManagerUtils` 等）；
- securitycenter 56 条里精读 2 条（GamePerformanceWildMode、RemoveVideoTempLimit 所属的 video 组未读）；
- `mediaeditor`(7)、`camera`(10)、`home`(139) 三个作用域完全没读；
- 规则之间的**依赖与互斥**（例如同时开 `HideBatteryIcon` 与 `BatteryStyle` 会不会冲突）未验证。

---

## 附：三个重点作用域的完整规则清单


### systemframework（76 条）

| 规则 | 主要目标类 | 手法 |
|---|---|---|
| `AllDarkMode` | com.android.server.ForceDarkAppListManager | - |
| `AllowAutoStart` | miui.app.ActivitySecurityHelper | - |
| `AllowDisableProtectedPackage` | com.android.server.pm.PackageManagerService, com.android.server.pm.Pro | - |
| `AllowManageAllNotifications` | android.app.NotificationChannel | const |
| `AllowUntrustedTouchForU` | android.hardware.input.InputManager, com.android.server.wm.WindowState | - |
| `AllowUpdateSystemApp` | com.android.server.pm.PackageManagerServiceImpl | - |
| `AntiQues` | com.android.server.SystemServiceManager, com.android.server.wifi.Utils | - |
| `AppLinkVerify` | com.android.server.pm.verify.domain.DomainVerificationUtils | - |
| `AuthCreakPatch` | android.app.ActivityThread, android.content.pm.PackageParser.PackagePa | field |
| `AutoEffectSwitchForSystem` | com.android.server.audio.AudioService | - |
| `BackgroundBlur` | android.os.SystemProperties | - |
| `BypassForceDownloadui` | android.intent.action.VIEW_DOWNLOADS, com.android.server.wm.ActivitySt | - |
| `BypassForceMiAppStore` | com.android.server.wm.ActivityTaskManagerService | - |
| `BypassIsolationViolation` | com.android.internal.pm.parsing.pkg.ParsedPackage, com.android.server. | - |
| `BypassSignCheckForT` | android.util.apk.ApkSignatureVerifier | - |
| `BypassUnknownSourcesRestrictions` | android.permission.REQUEST_INSTALL_PACKAGES, com.android.packageinstal | field |
| `BypassWakePathChecker` | com.miui.server.WakePathChecker | - |
| `CleanOpenMenu` | com.android.server.pm.ComputerEngine, com.android.server.pm.PackageMan | - |
| `CleanProcessTextMenu` | com.android.server.pm.ComputerEngine, com.android.server.pm.PackageMan | - |
| `CleanShareMenu` | com.android.server.pm.ComputerEngine, com.android.server.pm.PackageMan | - |
| `ClipboardWhitelist` | com.android.server.clipboard.ClipboardService | - |
| `CorePatchHelper` | （DexKit/字符串定位） | - |
| `DeleteOnPostNotification` | com.android.server.wm.AlertWindowNotification | - |
| `DigestCreakPatch` | android.content.pm.ApplicationInfo, android.content.pm.PackageParser.S | - |
| `DisableCleaner` | com.android.server.am.ActivityManagerService, com.android.server.am.Ac | - |
| `DisableFreeformBlackList` | android.app.ActivityTaskManager, android.util.MiuiMultiWindowAdapter,  | const |
| `DisableGestureMonitor` | com.miui.server.input.gesture.MiuiGestureMonitor | - |
| `DisableLowApiCheckForB` | com.android.server.pm.InstallPackageHelper, com.android.server.pm.Inst | - |
| `DisableLowApiCheckForU` | com.android.server.pm.InstallPackageHelper, com.android.server.pm.Inst | - |
| `DisableMiuiLite` | miui.os.Build, miui.util.DeviceLevel | - |
| `DisableMiuiWatermark` | com.android.server.wm.MIUIWatermark | - |
| `DisablePersistent` | com.android.server.pm.InstallPackageHelper, com.android.server.pm.Inst | - |
| `DisablePinVerifyPer72h` | com.android.server.locksettings.LockSettingsStrongAuth | - |
| `DisableRemoveFingerprintSensorConfig` | com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProv | - |
| `DisableThermal` | com.android.server.power.ThermalManagerService | - |
| `DisableVerifyCanBeDisabled` | com.android.server.pm.PackageManagerServiceImpl | - |
| `DisplayCutout` | android.view.DisplayCutout, com.android.server.display.LogicalDisplay | field |
| `DowngradeCheckPatch` | android.content.pm.PackageInfoLite, com.android.server.pm.PackageManag | - |
| `EffectBinderProxy` | android.app.IApplicationThread, android.content.IIntentReceiver, com.a | - |
| `EnhanceRecentsVisibility` | android.content.Intent | - |
| `ExactSignCheckPatch` | android.content.pm.PackageParser.SigningDetails, android.content.pm.Si | - |
| `FlagSecure` | android.app.IScreenCaptureObserver, android.os.Build$VERSION_CODES_FUL | - |
| `FreeformBubble` | android.util.MiuiMultiWindowUtils, com.android.server.wm.MiuiFreeformS | const |
| `FreeFormCount` | com.android.server.wm.MiuiFreeFormStackDisplayStrategy | const |
| `GMSDozeFixFramework` | android.app.IApplicationThread, android.content.IIntentReceiver, com.a | field |
| `HookEntry` | com.android.server.MiuiBatteryIntelligence$BatteryNotificationListerne | const |
| `IgnoreStylusKeyGesture` | com.miui.server.input.stylus.MiuiStylusPageKeyListener, com.miui.serve | const |
| `LinkTurboToast` | com.xiaomi.NetworkBoost.slaservice.SLAToast | - |
| `MiAospImeSystem` | com.android.server.inputmethod.InputMethodManagerService, com.android. | - |
| `MiuiImeUnlockSystem` | android.view.InputMethod, com.android.server.pm.AppsFilterUtils | - |
| `NativeFilePicker` | com.android.photopicker.hyper.HyperMainActivity, com.android.server.pm | - |
| `NoAccessDeviceLogsRequest` | com.android.server.logcat.LogcatManagerService | - |
| `OpenAppInFreeForm` | android.util.MiuiMultiWindowUtils, com.android.server.wm.ActivityStart | - |
| `PstedClipboard` | com.android.server.clipboard.ClipboardService | - |
| `QuickScreenshot` | com.android.server.policy.PhoneWindowManager | - |
| `RemoveStylusBluetoothRestriction` | com.miui.server.input.stylus.MiuiStylusDeviceListener, miui.util.ITouc | - |
| `RestoreEsc` | com.android.server.input.InputManagerServiceStubImpl, com.android.serv | - |
| `ReturnConstant` | （DexKit/字符串定位） | - |
| `ScreenRotation` | com.android.internal.view.RotationPolicy, com.android.server.wm.Displa | const |
| `SetGestureNeedFingerNum` | com.miui.server.input.gesture.multifingergesture.gesture.BaseMiuiMulti | const |
| `SharedUserPatch` | android.content.pm.PackageParser.SigningDetails, android.content.pm.Si | field |
| `SpeedInstall` | android.content.pm.PackageInstaller | - |
| `StickyFloatingWindows` | android.util.MiuiMultiWindowAdapter, android.util.MiuiMultiWindowUtils | field |
| `SystemLockApp` | android.app.ActivityTaskManager, com.android.server.input.shortcut.sin | field |
| `ThemeProvider` | （DexKit/字符串定位） | - |
| `ThermalBrightness` | com.android.server.audio.MultimediaMiPerception.utils.ThermalHelper, c | field |
| `UnForegroundPin` | com.android.server.wm.MiuiFreeFormActivityStack, com.android.server.wm | - |
| `UnlockAlwaysOnDisplay` | miui.util.FeatureParser | - |
| `UseAndroidPackageInstaller` | com.android.server.pm.PackageManagerServiceImpl | - |
| `UseAOSPScreenShot` | com.android.internal.util.ScreenshotHelperStub | const |
| `VerificationAgentPatch` | com.android.server.pm.PackageManagerService, com.android.server.pm.Ver | - |
| `VolumeDefaultStream` | com.android.server.audio.AudioService | - |
| `VolumeDisableSafe` | com.android.server.audio.SoundDoseHelper, com.android.server.audio.Sou | - |
| `VolumeFirstPress` | com.android.server.audio.AudioService$VolumeController | - |
| `VolumeMediaSteps` | android.os.SystemProperties | - |
| `VolumeSteps` | com.android.server.audio.AudioService | - |

### systemui（136 条）

| 规则 | 主要目标类 | 手法 |
|---|---|---|
| `AllowThirdLockScreenUseFace` | com.android.keyguard.KeyguardUpdateMonitor, miui.stub.keyguard.Keyguar | const |
| `AlwaysDark` | com.android.systemui.statusbar.notification.fullaod.NotifiFullAodContr | field |
| `AlwaysDarkOS4` | com.miui.utils.configs.MiuiConfigs | - |
| `AmbientColorResolverOS4` | android.graphics.drawable.ColorDrawable, android.graphics.drawable.Dra | - |
| `AmbientLight` | com.android.systemui.statusbar.notification.fullaod.NotifiFullAodContr | - |
| `AmbientLightOS4` | com.android.systemui.statusbar.notification.fullaod.NotifiFullAodContr | - |
| `AmbientNativeColorOS4` | （DexKit/字符串定位） | - |
| `AodBlurButton` | com.miui.keyguard.shortcuts.controller.ShortcutViewLayoutController | field |
| `AutoCollapse` | com.android.systemui.animation.Expandable, com.android.systemui.qs.til | - |
| `AutoDismissExpandedPopupsHook` | com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl | - |
| `AutoSEffSwitchForSystemUi` | android.media.audiofx.AudioEffectCenter, miui.systemui.quicksettings.s | - |
| `BatteryDetailIndicator` | com.android.keyguard.charge.ChargeUtils, com.android.systemui.Dependen | field |
| `BatteryStyle` | com.android.systemui.statusbar.views.MiuiBatteryMeterView | - |
| `BlockEditor` | com.android.keyguard.KeyguardEditorHelper | replace |
| `BlurButton` | （DexKit/字符串定位） | field |
| `BlurEnable` | com.android.systemui.statusbar.BlurUtils | - |
| `BrightnessPct` | com.android.systemui.controlcenter.policy.BrightnessUtils, com.android | - |
| `CCGridForHyperOSKt` | miui.systemui.controlcenter.qs.tileview.QSTileItemIconView | - |
| `ChargingCVP` | com.android.keyguard.charge.ChargeUtils, com.android.systemui.Dependen | field |
| `ControlCenterStyle` | com.android.systemui.controlcenter.policy.ControlCenterControllerImpl, | field |
| `CustomBackground` | com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMedi | - |
| `CustomBackgroundDrawableOS4` | （DexKit/字符串定位） | - |
| `CustomBackgroundOS4` | com.android.systemui.statusbar.notification.fullaod.NotifiFullAodContr | - |
| `CustomBackgroundRendererOS4` | android.graphics.drawable.ColorDrawable, android.graphics.drawable.Gra | - |
| `CustomCardTiles` | miui.systemui.controlcenter.panel.main.qs.QSCardsController, miui.syst | - |
| `CustomCarrierText` | com.android.systemui.controlcenter.shade.ControlCenterHeaderController | - |
| `CustomizeBottomButton` | com.android.keyguard.negative.KeyguardMoveLeftController, com.android. | - |
| `DarkInfo` | （DexKit/字符串定位） | - |
| `DataSaverIcon` | com.android.systemui.statusbar.phone.PhoneStatusBarPolicy | - |
| `DataSaverIconOS4` | com.android.internal.statusbar.StatusBarIcon | - |
| `DefaultPluginTheme` | miui.systemui.util.ThemeUtils | - |
| `DefaultPluginTheme` | miui.systemui.util.ThemeUtils | - |
| `DisableAnim` | com.android.systemui.statusbar.policy.FakeStatusBarClockController | - |
| `DisableBottomBar` | com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.M | const |
| `DisableDeviceManaged` | com.android.systemui.security.data.model.SecurityModel, com.android.sy | field |
| `DisableDeviceManagedNew` | miui.systemui.controlcenter.policy.SecurityController | const |
| `DisableInfinitymodeGesture` | com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeSiz | const |
| `DisableMiuiMultiWinSwitch` | com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.M | const |
| `DisableTransparent` | com.android.systemui.statusbar.notification.row.NotificationContentInf | - |
| `DisableUnlockByBleToast` | com.android.keyguard.KeyguardSecurityContainerController$3, com.androi | - |
| `DoubleTapToSleep` | com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView | - |
| `DualRowSignalHookV` | （DexKit/字符串定位） | - |
| `EnableVolumeBlur` | com.android.systemui.miui.volume.Util | const |
| `ExpandNotificationKt` | com.android.systemui.statusbar.notification.row.ExpandableNotification | - |
| `FiveGTile` | com.android.phone.settings.MiuiFiveGNetworkSetting, com.android.system | - |
| `FixColor` | com.android.systemui.controlcenter.phone.widget.NotificationShadeFakeS | - |
| `FixTilesList` | （DexKit/字符串定位） | - |
| `FlashLightNotificationColor` | miui.systemui.action.ACTION_CLOSE_FLASHLIGHT, miui.systemui.flashlight | - |
| `FocusNotifLyric` | com.android.systemui.statusbar.notification.row.NotifBindPipeline, com | const |
| `FuckStatusbarGestures` | com.android.systemui.statusbar.phone.CentralSurfacesImpl | - |
| `GmsTile` | com.android.systemui.qs.tiles.ScreenLockTile, com.miui.googlebase.ui.G | - |
| `GuidedAccessDialogBlock` | android.app.ActivityTaskManager, com.android.systemui.statusbar.phone. | - |
| `GuidedAccessTile` | android.app.ActivityTaskManager, com.android.settings.SubSettings, com | - |
| `HandleLineCustom` | （DexKit/字符串定位） | - |
| `HideBatteryIcon` | com.android.systemui.statusbar.views.MiuiBatteryMeterView | - |
| `HideBatteryIconB` | com.android.systemui.statusbar.views.MiuiBatteryMeterView | - |
| `HideCollpasedFootButton` | com.android.systemui.miui.volume.MiuiVolumeDialogView | - |
| `HideDelimiter` | com.android.keyguard.CarrierText, com.android.keyguard.CarrierText$$Ex | field |
| `HideEditButton` | miui.systemui.controlcenter.panel.main.MainPanelModeController$MainPan | const |
| `HideFakeStatusBar` | android.app.Application, com.android.systemui.controlcenter.shade.Noti | - |
| `HideLockScreenHint` | com.android.systemui.statusbar.KeyguardIndicationController | const |
| `HideLockScreenHintOS4` | com.android.systemui.keyguard.KeyguardIndication, com.android.systemui | - |
| `HideLockScreenStatusBar` | com.android.systemui.statusbar.phone.CentralSurfacesImpl | field |
| `HideLockscreenZenMode` | com.android.notification.tinypanel.FlipNotifDataStore, com.android.sys | field |
| `HideStatusBarBeforeScreenshot` | com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment, c | - |
| `HideStrongToast` | （DexKit/字符串定位） | - |
| `HideVoWiFiIcon` | com.miui.interfaces.IOperatorCustomizedPolicy$OperatorConfig | field |
| `IconsFromSystemManager` | com.android.internal.statusbar.StatusBarIcon, com.android.systemui.sta | field |
| `KeepNotification` | com.android.systemui.statusbar.notification.interruption.KeyguardNotif | field |
| `LinkageAnimCustomer` | com.android.keyguard.clock.animation.ClockBaseAnimation, com.android.k | - |
| `LockScreenDoubleTapToSleep` | com.android.systemui.shade.NotificationsQuickSettingsContainer | - |
| `MediaSeekBar` | com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel, com | - |
| `MediaSeekBarColorOS4` | （DexKit/字符串定位） | - |
| `MediaViewLayout` | com.miui.utils.DrawableUtils | field |
| `MediaViewSize` | （DexKit/字符串定位） | - |
| `MobilePublicHookV` | android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED, androi | field |
| `MobileSignalHook` | （DexKit/字符串定位） | - |
| `MobileTypeRenderStateStore` | （DexKit/字符串定位） | - |
| `MobileTypeSingle2Hook` | android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED, androi | const |
| `MobileTypeTextCustom` | com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiM | - |
| `MobileTypeViewRenderer` | （DexKit/字符串定位） | - |
| `MobileTypeVisibilityResolver` | （DexKit/字符串定位） | - |
| `MonetThemeOverlay` | com.android.systemui.theme.ThemeOverlayController | - |
| `MuteVisibleNotifications` | com.android.systemui.statusbar.notification.policy.MiuiAlertManager | - |
| `NavigationCustom` | （DexKit/字符串定位） | - |
| `NetworkSpeedSec` | com.android.systemui.statusbar.views.NetworkSpeedView | - |
| `NetworkSpeedSpacing` | com.android.systemui.statusbar.policy.NetworkSpeedController, com.andr | - |
| `NewBrightnessPct` | miui.systemui.controlcenter.panel.main.brightness.BrightnessPanelSlide | - |
| `NewFlashLight` | com.android.systemui.controlcenter.policy.BrightnessUtils, com.android | field |
| `NewNetworkSpeed` | com.android.systemui.statusbar.policy.NetworkSpeedController, com.andr | - |
| `NewNetworkSpeedStyle` | com.android.systemui.statusbar.views.NetworkSpeedView | - |
| `NewPluginHelperKt` | com.android.systemui.shared.plugins.PluginInstance$PluginFactory, com. | - |
| `NewShowVolumePct` | com.android.systemui.miui.volume.VolumePanelViewController, com.androi | - |
| `NewUnlockCustomActions` | com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataMan | field |
| `NotificationColor` | （DexKit/字符串定位） | - |
| `NotificationFreeform` | com.android.systemui.statusbar.notification.row.ExpandableNotification | field |
| `NotificationIconColumns` | com.android.systemui.statusbar.phone.NotificationIconContainer, com.an | field |
| `NotificationImportanceHyperOSFix` | com.android.systemui.statusbar.notification.collection.coordinator.Sta | - |
| `NotificationShowOnKeyguard` | com.android.systemui.statusbar.notification.ExpandedNotification | const |
| `NotificationWeather` | com.android.systemui.controlcenter.shade.CombinedHeaderController, com | - |
| `OldWeather` | com.android.systemui.qs.MiuiQSHeaderView | field |
| `QQSGrid` | com.android.systemui.qs.MiuiQuickQSPanel | - |
| `QSColor` | com.android.systemui.plugins.qs.QSTile$State, com.android.systemui.qs. | field |
| `QSGrid` | com.android.systemui.qs.MiuiTileLayout | field |
| `QsTileSuperBlur` | com.android.systemui.miui.volume.MiuiRingerModeLayout$RingerButtonHelp | - |
| `RedirectToNotificationChannelSetting` | com.android.settings.SubSettings, com.android.settings.notification.Ch | - |
| `ReduceBrightColorsTile` | com.android.systemui.qs.tiles.ReduceBrightColorsTile | - |
| `RemoveCamera` | com.android.keyguard.KeyguardMoveRightController | const |
| `RemoveMiuiMultiWinSwitch` | com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.M | const |
| `RemoveNotifNumLimit` | com.android.systemui.statusbar.notification.collection.NotifPipeline,  | replace |
| `RemoveNotifNumLimitOS4` | com.android.systemui.statusbar.notification.collection.NotificationEnt | replace |
| `RemoveSmartScreen` | （DexKit/字符串定位） | - |
| `RotationButton` | com.android.systemui.navigationbar.NavigationBar, com.android.systemui | - |
| `RotationButtonB` | com.android.systemui.navigationbar.views.NavigationBar, com.android.sy | - |
| `ScramblePIN` | com.android.keyguard.KeyguardPINView, com.android.keyguard.NumPadKey,  | field |
| `SelectiveHideIconForAlarmClock` | android.intent.action.LOCALE_CHANGED, android.intent.action.TIMEZONE_C | - |
| `ShadeHeaderGradientBlur` | com.miui.systemui.shade.header.ShadeHeaderClipController | - |
| `SmartHome` | com.android.systemui.shared.plugins.PluginManagerImpl, miui.systemui.d | - |
| `SnowLeopardModeTile` | com.android.systemui.qs.tiles.QuietModeTile | - |
| `StartCollpasedColumnPress` | com.android.systemui.miui.volume.MiuiVolumeDialogMotion, com.android.s | field |
| `StatusBarClockNew` | com.android.systemui.statusbar.views.MiuiClock | - |
| `StatusBarIcon` | com.android.systemui.statusbar.phone.MiuiIconManagerUtils | - |
| `SunlightModeTile` | com.android.settings.Settings$DisplaySettingsActivity, com.android.sys | - |
| `SwapWiFiAndMobileNetwork` | com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModul | - |
| `TaplusTile` | com.android.systemui.qs.tiles.NightModeTile, com.miui.contentextension | - |
| `ToastBlur` | android.widget.ToastPresenter, android.widget.ToastStub, android.widge | - |
| `UiLockApp` | com.android.systemui.Dependency, com.android.systemui.SystemUIApplicat | field |
| `UnimportantNotification` | com.android.systemui.shade.ShadeExpansionChangeEvent, com.android.syst | field |
| `UnlockCarSicknessTile` | miui.systemui.util.DeviceUtils | - |
| `UnlockClipboard` | com.android.systemui.clipboardoverlay.ClipboardListener | field |
| `UnlockFocus` | com.miui.systemui.notification.NotificationSettingsManager | - |
| `VolumeOrQSBrightnessValue` | com.android.systemui.miui.volume.Util, com.android.systemui.miui.volum | - |
| `WifiNetworkIndicator` | com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.Wifi | field |
| `WifiStandard` | com.android.systemui.statusbar.StatusBarWifiView, com.android.systemui | field |
| `WifiStandard` | com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewMode | field / DexKit |
| `ZenModeFix` | com.android.systemui.statusbar.notification.policy.MiuiAlertManager | - |

### securitycenter（56 条）

| 规则 | 主要目标类 | 手法 |
|---|---|---|
| `AddAppInfoEntry` | com.android.settings.SubSettings, com.android.settings.applications.ap | - |
| `AddAppManagerEntry` | com.android.settings.applications.ManageApplications, com.miui.appmana | - |
| `AddSideBarExpandReceiver` | com.android.systemui.navigationbar.gestural.RegionSamplingHelper | - |
| `AppDefaultSort` | com.miui.appmanager.AppManagerMainActivity | - |
| `AppDetails` | com.miui.appmanager.AMAppInfomationActivity | - |
| `AppDisable` | com.miui.appmanager.ApplicationsDetailsActivity, com.miui.appmanager.f | - / DexKit |
| `AppLockPinScramble` | com.miui.applicationlock.widget.MiuiNumericInputView | - |
| `AppRestrict` | com.miui.appmanager.AppManageUtils, com.miui.networkassistant.service. | field |
| `BatteryHealth` | （DexKit/字符串定位） | - / DexKit |
| `BeautyLightAuto` | （DexKit/字符串定位） | const / DexKit |
| `BeautyPc` | （DexKit/字符串定位） | const / DexKit |
| `BeautyPrivacy` | （DexKit/字符串定位） | const / DexKit |
| `BlurSecurity` | android.widget.CompoundButton, android.widget.ImageView, com.miui.comm | - / DexKit |
| `BypassAdbInstallVerify` | android.os.AsyncTask, com.miui.permcenter.install.AdbInstallVerifyActi | field / DexKit |
| `BypassSimLockMiAccountAuth` | android.content.Context | const / DexKit |
| `DisableAutoTurnAccessibilityOff` | （DexKit/字符串定位） | - / DexKit |
| `DisableNetworkAssistantOfflineInfoManager` | com.miui.networkassistant.ui.bean.OffLineData$BaseData | field |
| `DisableRemoveScreenHoldOn` | （DexKit/字符串定位） | const / DexKit |
| `DisableReport` | android.intent.action.VIEW | const / DexKit |
| `DisableRootCheck` | （DexKit/字符串定位） | const / DexKit |
| `DisableRootedCheck` | （DexKit/字符串定位） | - / DexKit |
| `DisableSafepayAutoScan` | （DexKit/字符串定位） | - / DexKit |
| `FuckRiskPkg` | （DexKit/字符串定位） | const / DexKit |
| `GamePerformanceWildMode` | （DexKit/字符串定位） | const / DexKit |
| `GetBubbleAppString` | com.miui.bubbles.Bubble, com.miui.bubbles.settings.BubblesSettings | - |
| `HideXOptModeTip` | （DexKit/字符串定位） | - / DexKit |
| `InstallIntercept` | （DexKit/字符串定位） | - / DexKit |
| `IsSbnBelongToActiveBubbleApp` | com.miui.bubbles.settings.BubblesSettings | const |
| `LockOneHundredPoints` | com.miui.securityscan.scanner.ScoreManager, com.miui.securityscan.ui.m | const / DexKit |
| `MoreBatteryInfoNew` | com.miui.powercenter.nightcharge.ChargeProtectFragment, com.miui.power | - / DexKit |
| `NewBoxBlur` | com.miui.gamebooster.windowmanager.newbox.NewToolBoxTopView, com.miui. | - |
| `NewPrivacyThumbnailBlur` | （DexKit/字符串定位） | - / DexKit |
| `NoLowBatteryWarning` | （DexKit/字符串定位） | - |
| `OpenByDefaultSetting` | com.miui.appmanager.ApplicationsDetailsActivity, com.miui.appmanager.f | - / DexKit |
| `PowerConsumptionRanking` | （DexKit/字符串定位） | - / DexKit |
| `PowerSaver` | （DexKit/字符串定位） | - |
| `RemoveConversationBubbleSettingsRestriction` | android.util.MiuiMultiWindowUtils, com.miui.bubbles.settings.BubbleApp | - |
| `RemoveGameToast` | （DexKit/字符串定位） | const / DexKit |
| `RemoveMacroBlackList` | （DexKit/字符串定位） | const / DexKit |
| `RemoveOpenAppConfirmationPopup` | android.widget.TextView | - |
| `RemoveSIMLockSuccessDialog` | com.miui.simlock.activity.SuccessDialogActivity | - |
| `RemoveVideoTempLimit` | （DexKit/字符串定位） | const / DexKit |
| `ScLockApp` | （DexKit/字符串定位） | - / DexKit |
| `ScreenUsedTime` | （DexKit/字符串定位） | const / DexKit |
| `ShowBatteryTemperatureNew` | android.intent.action.BATTERY_CHANGED, com.miui.powercenter.BatteryFra | - / DexKit |
| `SidebarLineCustom` | （DexKit/字符串定位） | - |
| `SimplifyMainFragment` | com.miui.common.card.CardViewRvAdapter, com.miui.common.card.models.Co | - |
| `SkipCountDownLimit` | com.miui.permcenter.privacymanager.InterceptBaseFragment, com.miui.per | - / DexKit |
| `UnlockCarSicknessRelief` | com.miui.carsickness.ui.CarSicknessReliefSettingsActivity$MotionSickne | const / DexKit |
| `UnlockGunService` | （DexKit/字符串定位） | const / DexKit |
| `UnlockLowTempExtEndurance` | （DexKit/字符串定位） | - / DexKit |
| `UnlockSmartCharge` | （DexKit/字符串定位） | const / DexKit |
| `UnlockSuperWirelessCharge` | （DexKit/字符串定位） | const / DexKit |
| `UnlockVideoSomeFunc` | （DexKit/字符串定位） | const / DexKit |
| `VBVideoMode` | （DexKit/字符串定位） | const / DexKit |
| `VideoDolbyOpen` | com.miui.gamebooster.service.DockWindowManagerService | - / DexKit |
