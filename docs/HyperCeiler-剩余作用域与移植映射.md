# HyperCeiler 剩余作用域分析 + 68 作用域移植映射

> 承接 `HyperCeiler-规则逐条分析.md`（已覆盖 systemframework/systemui/securitycenter 268 条）。
> 本轮把剩下 65 个作用域、361 条规则全部扫完，并把 68 个作用域逐个映射到本机（iQOO Neo10 Pro+）的实际组件。

---

## 1. 最重要的一张表：只有 14/68 个作用域在本机存在

用本机 423 个系统包对 HyperCeiler 的 68 个作用域做精确匹配：

| 类别 | 数量 | 例子 |
|---|---|---|
| **本机存在同名包** | 14 | `com.android.systemui`、`com.android.settings`、`com.android.camera`、`com.android.contacts`、`com.android.mms`、`com.android.incallui`、`com.android.phone`、`com.android.nfc`、`com.android.browser`、`com.android.providers.downloads(.ui)`、`com.android.externalstorage`、`com.android.photopicker`、`com.android.server.telecom` |
| **本机不存在但可角色映射** | 12 | home→`com.bbk.launcher2`、securitycenter→`com.iqoo.secure`、joyose→`com.vivo.abe`、powerkeeper→`com.vivo.devicepower`+`gamecube`、updater→`com.bbk.updater`+`com.vivo.cota`、misettings→`com.android.settings`、packageinstaller→`com.android.packageinstaller`、gallery→`com.vivo.gallery`、fileexplorer→`com.android.filemanager`、weather→`com.vivo.weather`、thememanager→`com.bbk.theme`、browser→`com.vivo.browser` |
| **本机无任何对应**（小米生态独有） | 42 | joyose 之外的小米云/商店/钱包/健康/社区/大师贴膜(MTB)/视频编辑/搜索指数… |

**结论**：HyperCeiler 的 629 条规则里，能直接谈"移植"的只有前两类（26 个作用域）；
剩下 42 个作用域在 OriginOS 上**连宿主都没有**，讨论移植没有意义。这解释了为什么我们只能"行为对齐"而不是"功能照搬"。

---

## 2. 角色映射表（12 个可映射，标注证据强度）

| HyperCeiler 作用域 | OriginOS 等价包 | 证据 | 我们是否已逆向 |
|---|---|---|---|
| home（139 条） | `com.bbk.launcher2`（+`com.vivo.launchercopilot`、`com.vivo.privacylauncher`） | 包名实测存在 | ❌ 未逆向 |
| securitycenter（56 条） | `com.iqoo.secure` | 包名+`setDaemon`/防火墙字符串实测 | 🟡 只到字符串层 |
| joyose（2 条） | `com.vivo.abe` | `UnifiedConfigProvider`/`unifiedconfig.db` 五表实测 | ✅ 已完整逆向 |
| powerkeeper（6 条） | `com.vivo.devicepower` + gamecube 的 Monster | `com.vivo.devicepower` 存在；Monster 类在 gamecube 内 | 🟡 部分 |
| updater（6 条） | `com.bbk.updater` + `com.vivo.cota` | 权限与依赖实测（申请了 abe 的 configlist 权限） | ✅ 组件层 |
| misettings（2 条） | `com.android.settings`（vivo 未分包） | `com.vivo.settings` 不存在 | ❌ |
| packageinstaller（10 条） | `com.android.packageinstaller` | 包名实测 | ❌ |
| gallery / fileexplorer / weather / thememanager | `com.vivo.gallery` / `com.android.filemanager` / `com.vivo.weather` / `com.bbk.theme` | 包名实测 | ❌ |
| camera（10 条） | `com.android.camera` + `com.vivo.alphacamera` | 包名实测 | ❌ |

---

## 3. `home` 那 139 条到底在做什么（读结构，不逐条）

按目录拆成 12 个子系统：

| 子系统 | 条数 | 代表规则 | 说明 |
|---|---|---|---|
| dock | 26 | `DockCustom`、`DockGlass*`（21 条）、`HomeDockWindow` | 桌面 Dock 的自定义与"玻璃"模糊效果；**21 条 DockGlass 是一个带 native + 测试的子系统** |
| recent | 23 | `HideRecentCard`、`TaskViewHeight`、`RemoveCardAnim` | 最近任务卡片布局/动画 |
| title | 17 | `IconSize`、`IconTitleColor`、`BigIconCorner` | 图标与标题外观 |
| folder | 13 | `BigFolderItemMaxCount`、`FolderColumns`、`FolderBlur` | 文件夹 |
| other | 13 | `BlurRadius`、`InfiniteScroll`、`UnlockBlurSupported` | 杂项模糊/滑动 |
| . (根) | 13 | `AllAppsBlur`、`DisablePrestart`、`MaxFreeForm` | 全局行为 |
| gesture | 10 | `QuickBack`、`CornerSlide`、`PredictiveBackProgress` | 手势 |
| layout | 8 | `HotSeatsHeight`、`WorkspacePadding` | 桌面网格布局 |
| widget | 5 | `ResizableWidgets`、`WidgetBlurOpt` | 小组件 |
| drawer | 4 | `AppDrawer`、`PinyinArrangement` | 应用抽屉 |
| navigation | 4 | `HideNavigationBar`、`BackGestureAreaHeight` | 导航栏 |
| mipad | 3 | `EnableMoreSetting`、`SetGestureNeedFingerNum` | 平板适配 |

**判断**：这是"桌面美化工具箱"，与本项目的目标（温控/性能/云控/Q2）无关。
我们的 `com.bbk.launcher2` 甚至还没逆向，做这块等于从零开一个新项目。**明确放弃**。

---

## 4. `camera` 10 条：手法值得记，落点要重找

| 规则 | 手法 | 字符串锚点 |
|---|---|---|
| `Unlock4k60` | DexKit 找方法 → `returnConstant(true)` | — |
| `UnlockLeica` | DexKit → 常量 | `themeCustomize` |
| `UnlockSuperHighQuality` | DexKit → 常量 | — |
| `CloudWatermark` | DexKit 找类→方法双层 | — |
| `BlackLeica` | DexKit 找类 + 方法 | `get(ColorSpace.Named.SRGB)`, `bitmap`, `deviceNameLengthType` |
| `EnableLabOptions` | DexKit 找"读系统属性"的方法 | `getBoolean`, `SystemProperties` |
| `CustomWatermark` / `CustomCameraColor` / `MaxScreenBrightness` / `UnlockTrackEyes` | DexKit 或直接类名 | 多为 `java.lang.String` 参数特征 |

**规律**：相机类规则 9/10 靠 DexKit + 字符串锚点，因为 MIUI 相机被混淆。
OriginOS 相机（`com.android.camera` / `com.vivo.alphacamera`）大概率同样混淆，
所以这块**要么用我们的离线索引 + 字符串锚点（等价 DexKit），要么不做**。

---

## 5. 其余作用域里对我们有参考价值的 6 条

| 规则 | 作用域 | 机制 | 对我们的意义 |
|---|---|---|---|
| `DisableGetDisplayCtrlCode` | powerkeeper | `ThermalManager#getDisplayCtrlCode()` → 0 | 已写入第一梯队候选（`q2.thermal.feedback_code`） |
| `LockMaxFps` | powerkeeper | `DisplayFrameSetting#setScreenEffect` → null | 对应 OriginOS 的 RMS/刷新率下发，**行为可对齐** |
| `AppsFreezerEnable` | systemsettings | 设置页开关（冻结器） | 与 `com.iqoo.secure` 的后台管控同域 |
| `UnlockMaxFps` / `UnlockMemc` / `UnlockSuperResolution` | systemsettings | 解锁设置里被隐藏的显示能力 | **与我们的 Q2 支持列表/能力判定直接同构**（vivo 也有 `isSupportBoostFrame2KProductNew`） |
| `DisableCloudCheck` / `DisableAppInfoUpload` | packageinstaller | 安装时跳过云查杀与信息上报 | 对应 `com.android.packageinstaller` 的云校验，属"安装校验"域 |
| `ClipboardUnlock` / `ClearClipboard` | various | 剪贴板读取白名单/自动清空 | 低风险、可独立做 |

---

## 6. 结论：只做 5 个作用域

| 优先级 | 作用域 | 理由 |
|---|---|---|
| 1 | `systemui`（136） | 本机存在同名包，且我们已经拿到 `icon_blacklist`/`clock_seconds`/`ISLAND_*` 实锤 |
| 2 | `systemsettings`（28） | 本机存在；`UnlockMaxFps`/`UnlockMemc` 与 Q2 能力判定同构 |
| 3 | `powerkeeper`→`com.vivo.devicepower`+gamecube（6） | 热控反馈码/锁帧两条机制已确认可对齐 |
| 4 | `updater`→`com.bbk.updater`+`cota`（6） | 组件与依赖已实测，冻结方案可逆 |
| 5 | `camera`（10） | 行为价值高，但需先逆向 vivo 相机（未做） |

**明确不做**：`home`(139)、`securitycenter` 的深层业务(56)、42 个无宿主作用域。

---

## 附：其余 62 个作用域清单（节选目标类）

| 作用域 | 条数 | 规则（节选） | 典型目标类 |
|---|---|---|---|
| aiasst | 2 | NewAiCaptions, UnlockSplitTranslation | com.xiaomi.aiasst.vision.utils.SupportAiSubtitlesUtils |
| aiengine | 1 | ChangeBrowserForAIEngine | android.content.Context |
| analytics | 1 | FuckMiuiUpload | — |
| aod | 3 | UnlockAiWallpaper, UnlockAodAon, UnlockShortCuts | com.miui.keyguard.editor.edit.aiwapper.AIWallPaperManager |
| backup | 2 | AllowBackupAllApps, UnlockBrokenScreenBackup | com.miui.backup.Customization |
| barrage | 4 | AnyBarrage, BarrageNotTouchable, CustomBarrageLength, GlobalBarrage | com.xiaomi.barrage.service.NotificationMonitorService |
| browser | 3 | DebugMode, DisableReadFiles, EnableDebugEnvironment | com.android.browser.provider.AdBlockRuleProvider |
| calendar | 1 | UnlockSubscription | android.app.SharedPreferencesImpl$EditorImpl |
| camera | 10 | BlackLeica, CloudWatermark, CustomCameraColor, CustomWatermark, EnableLabOptions, MaxScreenBrightness … | com.android.camera.customization.BGTintTextView |
| cloudservice | 1 | CloudList | — |
| community | 2 | DeviceModify, FuckDetection | com.xiaomi.vipbase.utils.SystemProperties |
| contacts | 2 | BusinessHallAdBlock, DisableNetworkAssistantOfflineInfoManager | com.mobile.businesshall.bean.CommonProduct |
| contentextension | 5 | DoublePress, LinkOpenMode, Taplus, UnlockTaplus, UseThirdPartyBrowser | com.miui.contentextension.utils.ContentCatcherUtil |
| creation | 1 | UnlockCreation | — |
| downloads | 1 | RemoveXlDownload | com.android.providers.downloads.config.XLConfig |
| downloadsui | 1 | AlwaysShowDownloadLink | android.intent.action.PICK |
| externalstorage | 1 | DisableFolderCantUse | com.android.externalstorage.ExternalStorageProvider |
| fileexplorer | 2 | SelectName, UnlockFileParse | com.android.fileexplorer.view.FileListItem |
| gallery | 12 | ChangeBackupServer, EnableHdrEnhance, EnableIdPhoto, EnableMagicMatting, EnableOcr, EnableOcrForm … | com.miui.gallery.transfer.GoogleSyncHelper |
| getapps | 6 | BypassRiskCheck, DeviceModify, DisableAds, DisablePackageMonitor, DisableStartPushDialog, NewIcon | com.xiaomi.market.business_ui.main.mine.app_security.AppSecurityCheckManager |
| guardprovider | 2 | DisableRootedCheck, DisableUploadAppListNew | — |
| health | 2 | DisableSplashAd, UnlockFoucsAuth | com.xiaomi.fitness.login.ad.SplashAdPreference |
| home | 139 | AllAppsBlur, AllAppsContainerViewBlur, AllAppsContainerViewSuperBlur, AllowMoveAllWidgetToMinus, AllowShareApk, AllWidgetAnimation … | com.miui.home.launcher.Launcher |
| htmlviewer | 1 | DisableUpdateCloudAllData | com.android.settings.cloud.JobTask |
| huanji | 1 | AllowMoveAllApps | — |
| incallui | 2 | AnswerInHeadUp, HideCrbt | com.android.incallui.InCallPresenter |
| joyose | 2 | DisableCloudControl, EnableGpuTuner | — |
| lbe | 2 | AutoStart, PasteToast | com.miui.privacy.autostart.AutoRevokePermissionManager |
| lpa | 1 | CustomImei | — |
| mediaeditor | 7 | CustomWatermark, GalleryCreationAvailabilityOS4, UnlockAigc, UnlockCustomPhotoFrames, UnlockDisney, UnlockLeicaFilter … | com.miui.mediaeditor.provider.MediaEditorProviderForGallery |
| milink | 5 | AllowCameraDevices, DisablePcManagerClipboardRegionCheck, FuckHpplay, UnlockMiShare, UnlockSendApp | com.xiaomi.vtcamera.cloud.RulesConfig |
| mirror | 1 | UnlockSendAppM | com.xiaomi.mirror.RemoteDeviceInfo |
| misettings | 2 | CustomRefreshRate, ShowMoreFpsList | com.xiaomi.misettings.display.RefreshRate.RefreshRateActivity |
| mishare | 1 | NoAutoTurnOff | android.content.Context |
| misound | 5 | BaseEffectControlUI, IncreaseSamplingRate, NewAudioEffectControl, NewAutoSEffSwitch, NewFWAudioEffectControl | com.miui.misound.EqualizerView |
| miwallpaper | 1 | UnlockSuperWallpaper | com.miui.superwallpaper.SuperWallpaperUtils |
| mms | 4 | AutoCopyVerificationCode, DisableAd, DisableRiskTip, ImOldDevice | com.miui.smsextra.ui.BottomMenu |
| mtb | 2 | BypassAuthentication, IsUserBuild | com.xiaomi.mtb.MtbApp |
| nfc | 2 | AllowInformationScreen, DisableSound | com.android.nfc.NfcService |
| notes | 2 | DisableAiWatermark, UnlockAI | com.miui.notes.ai.ui.AiWaterMarkView |
| packageinstaller | 10 | AllAsSystemApp, DisableAd, DisableAppInfoUpload, DisableCloudCheck, DisableCountChecking, DisableInstallerFullSafeVersion … | android.content.pm.ApplicationInfo |
| personalassistant | 3 | DisableLiteVersion, UnlockWidgetCountLimit, WidgetBlurOpt | com.miui.personalassistant.PAApplication |
| phone | 9 | DisableRemoveNetworkMode, DualNrSupport, DualSaSupport, ModemFeature, N1BandPhone, N28BandPhone … | com.android.phone.NetworkModeManager |
| photopicker | 1 | DisableReroute | android.intent.action.OPEN_DOCUMENT |
| phrase | 2 | NewUnPhraseLimit, UnlockImeClipboardFix | com.miui.inputmethod.InputMethodUtil |
| powerkeeper | 6 | CustomRefreshRate, DisableGetDisplayCtrlCode, DontKillApps, GmsDozeFix, LockMaxFps, PreventBatteryWitelist | com.miui.powerkeeper.feedbackcontrol.ThermalManager |
| remotecontroller | 1 | DisableAd | com.duokan.phone.remotecontroller.operation.Operation |
| scanner | 7 | EnableCard, EnableDocPpt, EnableDocument, EnableExcel, EnableOcr, EnablePpt … | com.xiaomi.scanner.settings.FeatureManager |
| screenrecorder | 4 | ForceSupportPlaybackCapture, SaveToMovies, ScreenRecorderConfig, UnlockMoreVolumeFromNew | android.os.SystemProperties |
| screenshot | 6 | DeviceShellCustomize, HideStatusBarWhenShot, SaveToPictures, UnlockCopyPicture, UnlockMinimumCropLimit2, UnlockPrivacyMarking | android.os.Build |
| securityadd | 1 | DisableGameBoosterAds | — |
| simactivate | 1 | RoamingActivateHelper | android.permission.READ_PRIVILEGED_PHONE_STATE |
| soundrecorder | 3 | DisableAiWatermark, UnlockAIMode, UnlockRecordingScene | com.android.soundrecorder.view.WaterMarkView |
| systemsettings | 28 | AddGoogleListHeader, AllowManageAllNotifications, AntiQues, AppsFreezerEnable, ControlCenterStyle, EnableSpeedMode … | com.android.settings.MiuiSettings |
| telecom | 1 | ScamReminderBypass | com.android.server.telecom.MiuiScamReminder |
| thememanager | 4 | AllowDownloadMore, AllowThirdTheme, DisableThemeAdNew, UnlockAIWallPaper | com.android.thememanager.basemodule.ad.model.AdInfoResponse |
| trustservice | 1 | DisableMrm | — |
| tsmclient | 1 | AutoNfc | com.miui.tsmclient.entity.CTAHelper |
| updater | 6 | AndroidVersionCode, AutoUpdateDialog, DeviceModify, VabUpdate, VersionCodeModify, VersionCodeNew | android.os.SystemProperties |
| various | 12 | BaiduClipboard, ClearClipboard, ClipboardUnlock, CollapseMiuiTitle, DialogBlur, DialogCustom … | cn.lyric.getter.api.API |
| voiceassist | 1 | RemoveScreenTransWatermark | — |
| voicetrigger | 1 | BypassUDKWordLegalCheck | android.content.Context |
| wallet | 1 | DisableSplashAd | com.xiaomi.jr.ad.AdManager |
| weather | 5 | SetCardLightDarkMode, SetDeviceLevel, UnlockSuperBlur, WeatherNativeBridgeOS4, WeatherNativeEndpointOS4 | com.miui.weather2.ActivityWeatherMain |
| xmsf | 1 | UnlockFoucsAuth | android.os.Bundle |
