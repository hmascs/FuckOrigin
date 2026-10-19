# OriginOS 系统应用「隐藏功能」全量普查（422 包 / 21 个代理切片）

> 目标：把本机 **423 个系统包**逐个解析，判定「是否存在被隐藏的功能/能力开关」，并标出与本项目（温控/性能/云控/游戏侧边栏）的关系。
> 方法：主控先写扫描器对 422 个包做统一体检（dex 规模 + 5 类信号 + 组件面），再切成 21 片交给 21 个子代理逐包判定与深挖；
> 其中 20 片由代理完成，1 片（slice_07）因代理失败由主控手工补判。

---

## 1. 数据口径（必须先说清楚，否则数字会骗人）

扫描器对每个 APK 统计：dex 的类/方法/字符串数、5 类正则信号命中数、组件面（activity/service/provider/receiver）、
`enabled=false` 组件数、`exported=false` 组件数，以及样例字符串。

**代理们在执行中发现了 4 个指标陷阱，已在判定时纠正（这是本轮最有价值的方法论产出）**：

| 指标 | 陷阱 | 纠正方式 |
|---|---|---|
| `sig_cloud` | 正则含 `gray`，大量命中 `lightgray`/`darkgray`/`titleColorGray` 等**颜色常量** | 不看计数，只看命中串语义；54 个"云控"包里只有约一半是真云控 |
| `sig_perf` | 命中 `androidx.profileinstaller.BenchmarkOperation`、字体 `DroidSansFallbackMonster.ttf` | 剔除库串后再判 |
| `hidden_exp` | 实际是 `android:exported=false` 的组件数，**不是隐藏功能数** | 不作为证据（如 hybrid 的 478 是未导出组件） |
| `PRIORITY_POWER_FOR_THERMAL` | 全机 **49 个包**都有，是共享库/框架串 | 不能作为"该包参与温控"的证据 |

---

## 2. 总览

| 维度 | 结果 |
|---|---|
| 覆盖 | **422 / 423**（1 个跨用户包 `pm path` 无权限） |
| 判定分布 | 高 **66** · 中 **90** · 低 **106** · 无 **154** · 无数据 **6** |
| 关系分布 | 温控 22 · 性能 21 · 云控 54 · 游戏 16 · 无关 309 |
| 深挖包 | 41 个（每片 2 个，各给 3–5 条"字符串 → 可能被关闭的能力"） |
| 数据缺口 | 6 个无数据：5 个 `dex=0` 的纯资源 overlay + 1 个 55 类无字符串的高通服务 |

**结论**：全机有隐藏能力位的包集中在 **vivo 自家服务**（67 个高/中信号里约 8 成）；
AOSP 包与高通包基本"干净"（只有系统级框架串），overlay 全部为空壳。

---

## 3. 本轮最重要的发现：`/proc/pem/thml_type`

代理在 `com.vivo.pem`（PowerEffectManager，3,131 类）里发现：

- 类 `com.ueppp.misc.temperature.ThermalTypeConfig` 的方法 `g(int)` 会把 `appMode` 写入 **`/proc/pem/thml_type`**
  （`FileOutputStream` 直写 + 调试用 `echo N > /proc/pem/thml_type`）；
- 调用链：`ThermalTypeConfig.activate / deactivate / execute` 与内部类 `ThermalTypeConfig$b`；
- 兄弟节点：`/proc/pem/thml_fan_type`（散热风扇）、`/proc/pem/heatlimit`（cgroup 热限）、
  `/proc/pem/thml_para`、`/proc/pem/thml_key_type`、`/proc/pem/event`、`/proc/pem/cmd`。

**上机实测（只读）**：

```
$ ls /proc/pem/
aprocess  cmd  event  frozen_sns  pem_pid_time  thml_key_type  thml_para  thml_type
$ cat /proc/pem/thml_type
1
$ ls -l /proc/pem/thml_type
-rw-rw-rw- 1 root root 0 2026-10-19 12:29 /proc/pem/thml_type
```

—— **节点存在、当前值 1、权限 `rw-rw-rw-`（普通应用即可写，不需要 root）**。

> ⚠️ **没有做写入实验**：当机处于 120W 充电 + `tshell_shell` 55.6 ℃（板温 49.4 ℃），
> 此时把 PEM 温控模式改掉正是最容易出事的场景。**实验必须等设备降到 40 ℃ 以下、拔掉充电器再做**，
> 且流程为：读原值 → 写 0 → 立即回读 → 观察 `dumpsys thermalservice` 与 cpu freq 10 秒 → 立刻写回原值。

**为什么这条比改 gamecube 更有价值**：gamecube 的钩子只管 Q2 门控；
PEM 是**整机性能/温控的调度器**（`AllGameAppMonsterModeThermalConfig`、`/proc/pem/thml_type`），
一个节点可能同时影响 CPU 频率、温控档位、游戏模式——且**不需要 LSPosed**。

---

## 4. 其他高价值发现（按与本项目的关系）

| 包 | 发现 | 意义 |
|---|---|---|
| `com.vivo.sps` | `/sys/class/thermal/`、`MB_THERMAL_THROTTLE_EVENT_NOTIFY`、`MB_CPUFREQCAP_NOTIFY`、`IS_SUPPORT_WECHAT_STRATEGY`、`speedupwhitelist` provider | **把进程/内存策略与温控绑定**的第二个调度器 |
| `com.android.systemui` | `com.vivo.vboostsdk.function.lists.CpuFreqFunction`、`setCpuFreq mSdkInitCPUP…` | **SystemUI 里内嵌 VBoost SDK 能设 CPU 频率**；还有 `PD2510F not support` 的模糊机型闸门 |
| `com.android.settings` | `PRIORITY_POWER_FOR_THERMAL`、`vboostPrioritySafety`、同款 vboostsdk | 设置页也挂了性能通道 |
| `com.android.phone` | `thermal_mitigation_allowlisted_packages`、`dataAccelerateTemperatureLevel` | **温控只对白名单内的包生效**——这是一条可被"加入白名单"利用的机制 |
| `com.android.bbklog` | `/sys/kernel/thermal/cpu_info`、`LL_limit_freq` / `BL_limit_freq`、`vivo_thermal_status.txt` | 直接读内核热限频的日志服务 |
| `com.bbk.iqoo.logsystem` | `thermal_zone0~63` 采集、`PROC_SPEEDUP_*` 四档处置 | 温控告警链路 |
| `com.vivo.gamewatch` | `RmSupportMonsterPlusMode is false`、`not in whitelist use default config` | Monster Plus 的机型/白名单闸门 |
| `com.vivo.gamespace` | `mHybridGameList`、机型串 `PD1616,PD1602,PD1901`、读 `/sys/class/thermal/` | 游戏空间的 boost 表 |
| `com.vivo.pie` | `RESTRICTION_POLICY_WHITELIST`、`PieService -n thermal -s high_temp=360` | 可下发的温控限制动作 |
| `com.vivo.connbase` | `PRIORITY_POWER_FOR_THERMAL`、`perfLockAcquire`、`BoostFramework` | vperf 请求优先级表 |
| `com.vivo.hiboard` | `setHighEndFromFaraway`、`getHiboardWidgetBlackList` | 按"高端机等级"下发能力 |
| `com.vivo.easyshare` | `160MHz_whitelist` / `160MHz_blacklist` | 按 SSID+机型放行 Wi-Fi 速率档 |
| `com.android.camera` / `com.vivo.alphacamera` | `Camera2WhiteList`/`FpsWhiteList`、`FeatureConfig_PD2185`、`/sys/cam_thermal/` | 相机能力按机型+温度双闸门 |
| `com.vivo.gamecube` | `frame_inter_support_game` 表含 `support_sdk`/`monster_plus_state`/`over_heat_temperature` | 与我们已做的 Q2 逆向一致，代理独立复现了结论 |

---

## 5. 按关系分组的全量清单（66 高 / 90 中 / 106 低 / 154 无）

（完整 422 行见附件 `资源/索引/系统应用体检-422.tsv`；每行的评测依据在 `audit/out/<包名>.txt`）


### 温控（22 个）

| 包名 | 等级 | 线索 |
|---|---|---|
| `com.google.android.gms` | 高 | ActiveUnlockUwb__uwb_default_off；gms:sidewinder:whitelist_by_device（主动解锁默认关） |
| `com.vivo.sps` | 高 | --- IS_SUPPORT_WECHAT_STRATEGY 与 projectWhiteList='：按机型/云控下发后台保活与回收策略 |
| `com.vivo.abe` | 高 | abe_v2_whitelist.xml 白名单 + "mTemptureWhiteList = " + "persist.sys.enable_cgroup" |
| `com.vivo.pem` | 高 | `AllGameAppMonsterModeThermalConfig`、`/proc/pem/thml_type` 直接写温控节点 |
| `com.vivo.easyshare` | 高 | 160MHz_whitelist、160MHz_blacklist、iQOO Z5 机型白名单 |
| `com.android.bbklog` | 高 | sys/kernel/thermal/cpu_info, LL_limit_freq, BL_limit_freq, B_limit_freq |
| `com.google.android.projection.gearhead` | 高 | CAR_IN_MAKE_MODEL_YEAR_EV_ALLOWLIST 按车型放行 + THERMAL_MITIGATION |
| `com.vivo.globaldragdrop` | 高 | "isSupportLLMDownload=" 开关 + "LLMServerError_temperature_limit_high" 温控降级码 |
| `vendor.qti.data.txpwradmin` | 高 | "WHITELISTED_ISV_INFO" ISV 白名单门控采集 + "THERMAL_STATUS_CRITICAL" 热等级 |
| `com.vivo.aiservice` | 高 | getLowLimitTemperature 与 /sys/class/thermal/ 温度门 + aiservice_plugin/beta 灰度插件目录 |
| `com.vivo.alphacamera` | 高 | /sys/cam_thermal/ + cam_thermal_sensor0 + FeatureConfig_DPD2329 机型分支 |
| `com.vivo.connbase` | 高 | PRIORITY_POWER_FOR_THERMAL / PRIORITY_APP_REQUEST_FOR_BENCH_MARK / isDPD2329 |
| `com.vivo.pie` | 高 | RESTRICTION_POLICY_WHITELIST + PieService -n thermal -s high_temp=360 |
| `com.bbk.iqoo.logsystem` | 高 | PROC_SPEEDUP_NOT_KILL_ONLY_REMOVETASK 四档 + thermal_zone0~63 温控采集 |
| `com.android.phone` | 高 | 「thermal_mitigation_allowlisted_packages」白名单决定哪些包被温控节流 |
| `com.android.settings` | 中 | "PRIORITY_POWER_FOR_THERMAL"、"vboostPrioritySafety"、vboostsdk CpuFreqFunction |
| `com.vivo.smartshot` | 中 | "PRODUCT_PD1923"/"PRODUCT_PD1924" 机型分支 + "getThermalLevel" 温控等级 |
| `com.vivo.health` | 中 | doChargingLogSync: high thermal, skip 与 VBoostFrameworkRequest：温控门+性能锁 |
| `com.vivo.connbase.deviceaccessory` | 中 | /sys/class/thermal/ + getThermalLevel；support_projects 与 unsupport_projects |
| `com.qualcomm.qti.server.qtiwifi` | 低 | `CTRL-EVENT-THERMAL-CHANGED level=([0-9]+)`、`GET_THERMAL_INFO`、`ThermalData` |
| `com.android.traceur` | 低 | THERMAL_TRACE_TAGS / atrace 分类 thermal_tj |
| `com.google.android.webview` | 低 | addThermalStatusListener/getCurrentThermalStatus 监听热状态，其余为 Chromium 自带 flag |

### 性能（21 个）

| 包名 | 等级 | 线索 |
|---|---|---|
| `com.bbk.launcher2` | 高 | "PARAMS_CPU_GPU_QCOM_8650"、"PARAMS_CPU_LIMIT_TASK_LAUNCH" 为按 SoC 枚举的调频参数 |
| `com.vos.as.vit` | 高 | "PD1824_A_1.16.23"、"PD1710F_EX" 等 73 处机型串，配 "SECURE_WHITE_LIST" |
| `com.iqoo.secure` | 高 | !is_support_privacy_app_protection + #support_i_manager_fraud_query_level 两个能力位 |
| `com.vivo.privacylauncher` | 高 | insertToSpeedUpWhiteListDatabase 与 privacylauncher_whiteList.xml：提速白名单 |
| `com.iqoo.powersaving` | 高 | "getMonsterKey"、"key_monster_mode_time"、"monster_dialog_checked" 三个 Monster 模式键 |
| `com.android.camera` | 高 | Camera2WhiteList/FpsWhiteList 与 FeatureConfig_PD2185 逐机型能力类（642 处机型串） |
| `com.android.systemui` | 高 | shouldUseVivoBlur false: PD2510F not support / setCpuFreq mSdkInitCPUPreq false |
| `com.vivo.recents` | 高 | speedUp.WhiteList 落库(WhiteListHelper)；mDoubleScreen/mAtomic 两套加速白名单 |
| `com.vivo.systemuiplugin` | 高 | isSupportSoftwareResolutionSwitch、replace monster tile to performance Tile |
| `com.vivo.hiboard` | 高 | 「getHiboardWidgetBlackList」小部件黑名单 + 「setHighEndFromFaraway」按高端机分级下发卡片 |
| `com.vivo.epm` | 中 | isEpmBinderEnable= / isEpmThirdCrashEnable=；epm_catch_trace_common_white_list |
| `com.vivo.widget.cleanspeed` | 中 | 130 条 "PD2046_A_5.5.0"~"PD2047_A_5.7.0" 机型版本表，配 "isInWhiteList = " |
| `com.vivo.carlauncher` | 中 | "sSupportCpuFreqBoost = " 升频开关 + "vehicle_whitelist_mode_group_name" 车型白名单 |
| `com.vivo.livewallpaper.behaviorcity` | 中 | 帧率/功耗优先级表：PRIORITY_APP_REQUEST_FOR_BENCH_MARK 与 PRIORITY_POWER_FOR_THERMAL 同现 |
| `com.vivo.connbase.connectcenter` | 中 | com.vivo.framework.vperf.VivoPerfManager 与 perfLockAcquire/BoostFrameworkVivo 同现 |
| `com.bbk.SuperPowerSave` | 中 | getWhiteList/getBlockList/clearWhiteListCache：后台应用限制黑白名单 |
| `com.vivo.fuelsummary` | 中 | content://com.iqoo.secure.provider.secureprovider/speedupwhitelist 直读加速白名单 |
| `com.vivo.vivoconsole` | 中 | PerformanceMonsterModePanelView、currentMonsterModeRotateValue 等性能模式面板开关 |
| `com.qualcomm.qti.workloadclassifier` | 低 | HeavyGame / VERYHEAVY 分级；PKGNAME_STARRAIL_BETA 游戏包名单；wlc_enable_engine_info |
| `com.vivo.livewallpaper.behavioriqoo` | 低 | 资源 XML 内 monster="true"、mMonsterMode: 仅为性能模式变体资源；INTEREST 多为 Glide 噪音 |
| `com.qualcomm.qti.powersavemode` | 无 | n_strings=254、sig_flag=0，SAMPLES 与 INTEREST 均为空，15 个类无开关串 |

### 云控（54 个）

| 包名 | 等级 | 线索 |
|---|---|---|
| `com.vivo.iotserver` | 高 | "[getUnifiedConfig] cursor is null, lock failed" 等 5 条云控日志 |
| `com.vivo.devicereg` | 高 | "DmOnlineConfig{mDmSwitchCmcc='" 按运营商在线开关，配 "CFG_TYPE_WHITE_LIST" |
| `com.vivo.gallery` | 高 | SwitchWhiteListData{switchInProject=；local_whitelist_version=；PD1818BF_EX 机型分支 |
| `com.vivo.agent` | 高 | KEY_ENGINE_ONLY_CLOUD / RECOG_ONLY_SUPPORT_CLOUD：识别引擎限定云端；getAppWhiteList 应用白名单 |
| `com.vivo.pushservice` | 高 | `total switch closed, return 0` 与 ` modelBlackList:`、`PD2203,PD2207` |
| `com.vivo.ai.copilot` | 高 | 230 处机型串 + 193 条 cloud/abtest；类量 9.9 万，能力位极多 |
| `com.vivo.sdkplugin` | 高 | ACCOUNT_VERSION_SUPPORT_* 系列版本闸门 + setVideoOutputInternal 等 273 条白名单 |
| `com.vivo.assistant` | 高 | `CALLER_SIGNATURE_WHITELIST` + `vehicle_whitelist_mode_selected_policy` 车载策略 |
| `com.vivo.multinlp` | 高 | `enableVivoNlpWhiteList` + `config_locationProviderPackageNames_nlp_PD2148` 分机型 |
| `com.android.photopicker` | 高 | "CLOUD_ENFORCE_PROVIDER_ALLOWLIST" 云控 provider 白名单 + 包名黑名单校验 |
| `com.vivo.accessibilityenhance` | 高 | "AICollectWhiteList"、"isInLiteAccessibilityWhiteList" 等多份业务白名单 |
| `com.vivo.upnpserver` | 高 | "isInScreenOffWhiteList packageName : " + "addMirrorScreenOffWhiteList" 息屏投屏白名单 |
| `com.vivo.appfilter` | 高 | BringupWhiteList 与 LockscreenWhiteListEntry：跳转/锁屏白名单由 SafeCenter 下发 |
| `com.vivo.daemonService` | 高 | "RESTRICTION_POLICY_WHITELIST" + "ITransOnlineConfig" 云控 + "everyDayCanUseTime" |
| `com.vivo.video.floating` | 高 | 43 个按机型的 FeatureConfig_PD*/DPD* 配置类（如 FeatureConfig_DPD2345） |
| `com.vivo.safecenter` | 高 | isSupportPrivacyAppProtect 与 isSupportTransferSafeCenter，配 whiteListData 云下发 |
| `com.android.providers.media.module` | 高 | ((PD2266)/(PD2324)/…(PD2505)).* 机型正则；vivo.intent.action.MEDIA_SCAN_WHITELIST |
| `com.bbk.appstore` | 高 | silentInstallTargetAppWhiteList + isSupportVHiddenApp + getDownloadGray 三处能力闸门 |
| `com.vivo.translator` | 高 | ro.vendor.vivo.software.ai.llm.disable 属性 + llmSwitchContentResolverObserver 开关 |
| `com.android.providers.telephony` | 中 | "content://com.vivo.abe.unifiedconfig.provider/configs" 云控 provider |
| `com.vivo.space` | 中 | "sdkname": "ABTestSDK"；isInRemoteWhitelist；abTestSwitch close |
| `com.android.packageinstaller` | 中 | content://com.vivo.abe.unifiedconfig.provider/configs；finger_unlock_open |
| `com.vivo.vms` | 中 | mInBlackList= + ATT whitelist config request success!（黑白名单 + 云配置） |
| `com.vivo.audiofx` | 中 | com.vivo.audiofx.hifi_whitelist.xml 与 HIFI_ALWAYS_ON：HiFi 按名单/开关授权 |
| `com.vivo.browser` | 中 | "/gameList"、"getABTestSidList" 为业务名单与 ABTest 分流；内嵌 PD2170_A_13.1.7.0 机型配置 |
| `com.bbk.updater` | 中 | ", betaInfo="、"&betaId=" 灰度通道与 PD1955_13.0 等 27 处机型分支 |
| `com.vivo.doubletimezoneclock` | 中 | ACTION_SERVER_DISABLE_SUPERX_RAILWAY/..._UMETRIP 服务端关卡 |
| `com.vivo.networkimprove` | 中 | persist.vivo.apmd.mdlog.test.flag、persist.vivo.apmd.emcall 隐藏测试开关 |
| `com.vivo.telephonyapp` | 中 | `ACC_WHITE_LIST`、`,T2AccDomainWhitelist:` 防拦截白名单；sig_model 仅 2，机型分支弱 |
| `com.vivo.globalanimation` | 中 | "FunctionConfig.mNotificationWhiteList"、"deleteAllNotificationWhiteList" |
| `com.vivo.magazine` | 中 | "getAbTestId"/"getWaterfallABTest" 灰度取号，并存 "PD1616BA" 机型串 |
| `com.vivo.findphone` | 中 | "content://com.vivo.abe.unifiedconfig.provider/configs" 云配置通道 |
| `com.android.mms` | 中 | "OnLineConfigReceiver" 在线配置广播，走 abe.unifiedconfig.provider |
| `com.vivo.permissionmanager` | 中 | "AbeConfigWhiteList" + "content://com.vivo.abe.configlist.provider" 云配置通道 |
| `com.bbk.cloud` | 中 | setFunctionSwitch err, funcId is 与 CHECK_BBKCLOUD_SWITCH_STATE_CLOSE：云端功能开关及关闭态 |
| `com.vivo.doubleinstance` | 中 | WhitelistQureyService / update_finish_broadcast_MultiUserDoubleInstance |
| `com.vivo.dr` | 中 |  mHasWhiteListApp: / MSG_UPDATE_WHITE_LIST_PKG_START / VivoLocationNotify |
| `com.vivo.vdfs` | 中 | "UnifiedConfigManager" + "TRANSACTION_addBlackList" + "support_prop" 能力列 |
| `com.android.permissioncontroller` | 中 | "getCameraPrivacyAllowlist" + "update_finish_broadcast_PermissionController" |
| `com.android.providers.contacts` | 中 | "is_secret INTEGER NOT NULL DEFAULT 0" 隐藏列 + "VcodeSwitch" 在线配置 |
| `com.vivo.aiengine` | 中 | aieAbTest、isMatchAbTest is false、perfLockAcquireQCOM：灰度判定与性能锁同包 |
| `com.vivo.visionaid.builtin` | 中 | Camera2WhiteList、FpsWhiteList 等多套白名单 + unifiedconfig.provider/configs 云通道 |
| `com.android.bbkmusic` | 中 | abTestItem is null / abTest not show：广告与推荐依赖云端 abTest 下发 |
| `com.vivo.widget.calendar` | 中 | PD2046_A_5.5.0…PD2046_A_5.8.0 机型+版本闸门；FromUnifiedConfig analysisHolidayXml |
| `com.bbk.calendar` | 中 | isMinorsModeWhiteList + whitelist.properties；UPDATE_UNIFIEDCONFIG_CALENDAR 云控 |
| `com.vivo.singularity` | 中 | 「ADBLOCK_WHITELIST_FILE」拦截名单 + 「com.vivo.abe.unifiedconfig.provider」云控拉配置 |
| `com.vivo.cota` | 中 | 「isAutoDownloadOrNot=」自动下载状态位 + 「ActivationFeedbackBean{internalModel='」激活上报 |
| `com.vivo.symmetry` | 中 | ACCOUNT_VERSION_SUPPORT_COMMAND_AIDL 版本闸 + CallingPkgWhiteListPresenter 白名单 |
| `com.qualcomm.qti.modemmbnmode` | 低 | sys.vivo.apmd.allow_modem_reset；mOemConfigList / mSwConfigList 读云配置 |
| `com.vivo.widget.timemanager` | 低 | 仅 `GET_TIME_MANAGET_SUPPORT_PROVIDER_KEY` 一处能力位，其余为 gson/internal 库串 |
| `com.android.BBKClock` | 低 | `mIsSupportDiffuseLight`、`isFbeProjAndUserKeyUnlocked` 属设备能力探测，非能力开关 |
| `com.mobiletools.systemhelper` | 低 | "CloudConfig hplocation config = hms " 定位云配置 + "&abtest=1" 灰度 |
| `com.google.android.configupdater` | 低 | 仅 Phenotype 开关 "gms:phenotype:phenotype_flag:debug_disable_caching" |
| `com.tencent.soter.soterserver` | 低 | 仅 " is in Soter BlackList, return,"；云配置走 UnifiedConfigUtils |

### 游戏（16 个）

| 包名 | 等级 | 线索 |
|---|---|---|
| `com.vivo.floatingball` | 高 | hiddenFuncSpecList = / hiddenAppSpecs: / mDuringRealGameMode（隐藏清单） |
| `com.vivo.secime.service` | 高 | initGameWhiteList 与 isRemoteGameWhiteListUpdated：游戏键盘白名单可云端更新 |
| `com.vivo.gamelib` | 高 | "initAppLowResWhiteList"、"mIsVNSSWhitelistApp"、"mMonsterPlusMode" 白名单与性能模式 |
| `com.vivo.hybrid` | 高 | preLoadGameWhiteListWithSubPkg checkDeviceStatus not reach... |
| `com.vivo.screenagent` | 高 | "com.tencent.tmgp.sgame"、"changeAiCaptionGame"、"AiCaptionGameOn=" 游戏名单 |
| `com.vivo.vtouch` | 高 | "VTouchGameAppBlackListV2" 与 "VTouchSystemAppWhiteList" 的 Includes/Excludes |
| `com.vivo.game` | 高 | "enable_gamecenter_memory_recycle" 内存回收开关 + "increaseCPUFrequence" 提频 |
| `com.vivo.smartmultiwindow` | 高 | mWhiteListSwitchFreeform 与 freeformHighLoadGameList 小窗/游戏白名单，随 unifiedconfig 更新 |
| `com.vivo.gamewatch` | 高 | RmSupportMonsterPlusMode is false… / not in whitelist use default config |
| `com.vivo.upslide` | 高 | disableSideDockSettingIfNeed … not support freeform / is_support_gamecube_9.0 |
| `com.vivo.gamespace` | 高 | "mHybridGameList=" + 机型串 "PD1616,PD1602,PD1901" + "/sys/class/thermal/" |
| `com.vivo.gamecube` | 高 | GAME_FILTER_PLANB_BLACK_LIST + mIsOnlyMonsterPlus；白名单 252 条、云控 78 条 |
| `com.android.server.telecom` | 高 | is_support_gamecube_8.1 + com.vivo.gamecube.callprovider 游戏态联动 |
| `com.vivo.vibrator4d` | 中 | ", vibrator4d_white_list:" 与 ", light_white_list:" 两份 4D 振感名单 |
| `com.vivo.gamekit` | 中 | "whiteListStr"/"setWhiteList" 游戏名单 + "isSupportDisplaySize" 等通用能力判定 |
| `com.vivo.gametrain` | 低 | 只有「game_cube」「close gridIndent, isSupport=」零星串，其余为 originui/okhttp 噪音 |

---

## 6. 缺口与下一步

**缺口**
1. 6 个包无数据（5 个纯 overlay、`com.qualcomm.qti.xrcb`），已用 `dex=0` / 类数复核，非漏扫；
2. `com.android.camera`（414 MB）首轮扫描 0 字节，由代理单独重扫，结果在 `audit/.work/camera_scan.txt`；
3. `com.android.incallui` / `com.android.filemanager` / `com.android.mms` / `com.android.bbkmusic` 也曾 0 字节，均已重扫；
4. `slice_07` 的执行代理中途失败，由主控手工补判 20 包（判定口径与其他切片一致，但未做深挖）。

**下一步（按价值排序）**
1. **`/proc/pem/thml_type` 实验**（降温后）：这是唯一"不需要 LSPosed、不需要 root、可能整机生效"的开关；
2. 把 `com.vivo.pem` 的 `ThermalTypeConfig` 全量读一遍（`activate/deactivate/execute` 的取值语义）；
3. `com.vivo.sps` 的 `MB_CPUFREQCAP_NOTIFY` 与 `speedupwhitelist` provider（第二个调度器）；
4. `com.android.phone` 的 `thermal_mitigation_allowlisted_packages`——如果能把目标包加入白名单，等于合法地"少被节流"；
5. `com.vivo.hybrid` / `com.vivo.ai.copilot` 两个 9 万+ 类的巨包，本轮只做了浅层判定。

---

*附件：`资源/索引/系统应用体检-422.tsv`（全量）、`资源/索引/审计切片-21份/`（每片的逐包 JSON 与深挖候选）、`工具/scan_pkg.py`（扫描器，可复跑）。*
