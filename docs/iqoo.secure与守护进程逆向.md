# com.iqoo.secure / com.vivo.daemonService 逆向：谁在回写？

> 动机：功能枚举里有一条 `up.sig_rewrite`（"对抗 com.iqoo.secure 对更新服务冻结状态的 SIG 回写"），
> 以及"守护进程回写拦截"的设计假设。这轮把两个候选守护进程拆开验证。

## 0. 结论先行

**本机证据不支持"守护进程回写性能/温控状态"这个假设。**
- `com.iqoo.secure`（71 MB / 6 dex / **21,829 类** / 115,126 方法）里，与温度相关的只有一个**风控/异常检测**管理器，
  没有任何写 `cpufreq` / `trip_point` / `kgsl` 的代码；
- `com.vivo.daemonService`（6,287 类）**整包没有出现 `thermal` / `cpufreq` / `scaling_` / `kgsl` 任一字符串**；
- `com.vivo.gamecube` 只有**读** sysfs（`/sys/class/tshell/`、`/sys/class/thermal/`、`/sys/devices/system/cpu/`、`kgsl`），
  也没有写频点/温控点的代码。

→ 真正周期性改写冷却设备的只有 **`thermal-engine`（native）**。所以我们的"写 `scaling_max_freq` + 心跳"方案方向正确，
但不需要（也无法）hook 任何 Java 守护；`up.sig_rewrite` 这条功能应降级为"观察项"。

---

## 1. com.iqoo.secure 的模块地图

| 子系统 | 代表类 | 作用 |
|---|---|---|
| 清理/加速 | `clean/`、`clean/fastclean/FastCleanActivity`、`clean/s3` | 内存清理、杀后台、快速加速 |
| 清理守护通道 | `com.vivo.securedaemonservice.ISecureDaemonservice` | AIDL：`killBgRunningApps`、`getRunningTasks`、`getTotalCanBeReleaseRamSpace`、`removeRecentTaskInfo` |
| 流量防火墙 | `datausage/firewall/server/FirewallScriptExecutor` | 经 `vivo_daemon.service` 执行防火墙脚本 |
| 应用时长管控 | `timemanager/view/AppTimeControlActivity` | 读取 `sys.super_power_save`，超时提醒 |
| 降温 UI | `temp/PhoneCoolActivity`、`temp/model/coolingItem/{CpuItem,GpuItem,CoolingFanItem,AutoBrightnessItem,FlashlightItem,RuntimeAppItem}` | "手机降温"面板：列出可执行的降温动作 |
| 风控/异常 | `utils/temperature/TemperatureControlManager` | 温度异常判定 + 上报（见 §2） |
| 自启动 | `q4/c`（`auto_start_fast_clean` / `low_auto_start_fast_clean`） | 自启动快速清理的开关位 |

---

## 2. `TemperatureControlManager`：它是风控，不是节流器

- `c()Z`：读 `/sys/class/tshell/shelltemp_en`（温度上报总开关）
- `d()V`：扫 `/sys/class/thermal/`，白名单 `case_therm|board_therm|quiet_therm|mtktsAP|quiet-therm-adc`
  —— **与 gamecube 的 `com.vivo.common.e` 白名单几乎一致**，但是两个独立实现
- `b(I)F`：读 `/sys/class/fuelsummary/tshell_shell`（**第四条温度路径**，和 `/sys/class/tshell/` 不是同一个节点）
- `a(D)Z`：对两个传感器取差值（`abs(b(0)-b(1))`），与 `3.0f` 比较，再叠加 Calendar 时段判断
  → 这是"温度异常/欺诈事件"的判定，配套字符串有 `FraudEvent:`、`isMsgFraudOpen = `、`upUrl:`
- 调用方：`AutoSecurityCheckTask`、`AutoSecurityCheckPresenter$c`、`BaseReleaseJobService$a`、`n3/a`、`dd/a`
  → 全部是"自动安全检测"和"后台清理"，没有一个是"性能/温控策略"

**含义**：i管家会在界面上显示高温并可能提示"一键降温"，但它不会去调 CPU/GPU 频率。
我们的旁路如果只解 gamecube，i管家里的温度显示仍会是真实的——这是**信息不一致**，不是功能冲突。

---

## 3. 云控消费者：第三个

`com.iqoo.secure` 里出现：
- `content://com.vivo.daemonservice.unifiedconfigprovider/configs`
- `com.vivo.daemonService.unifiedconfig.update_finish_broadcast_IqooSecure`

加上之前确认的 gamecube（`com.vivo.abe.configlist.provider/appcategoryinfo`）和
`com.bbk.updater`（`com.vivo.abe.configlist_provider`），**本机至少有 3 个云控消费者**，
且 iqoo.secure 走的是 `com.vivo.daemonservice` 那条 provider，不是 `com.vivo.abe` 的。

→ 我们的"云控读侧冻结"如果只 hook `com.vivo.abe` 的 `UnifiedConfigProvider`，**覆盖不到 iqoo.secure 这条路**。
必须同时看 `com.vivo.daemonservice.unifiedconfigprovider` 的实现（该 provider 在 `com.vivo.daemonService` 进程里）。

---

## 4. 温度读取路径总账（本机四条）

| 路径 | 读法 | 读者 |
|---|---|---|
| `/sys/class/tshell/tshell_game` | 逗号分隔三值 | gamecube `com.vivo.common.e#b()` |
| `/sys/class/tshell/tshell_shell` | 同上 | gamecube（非游戏态）、`TemperatureControlManager` 备用 |
| `/sys/class/thermal/thermal_zone*/temp` | 按 type 白名单 | gamecube `e#d()`、iqoo.secure `TemperatureControlManager#d()` |
| `/sys/class/fuelsummary/tshell_shell` | 单值 | iqoo.secure `temp/k#e()`、`TemperatureControlManager#b()` |

**设计含义**：想让"全机温度一致"，得处理 **4 条路径 / 3 个读者**；
只关心 Q2 门控的话，只需第 1 条（gamecube）。这也解释了为什么"改温度"和"改阈值"要分开评估：
改温度影响面大，改阈值只影响目标读者。

---

## 5. 对已有文档的修正

| 条目 | 原判断 | 修正 |
|---|---|---|
| `up.sig_rewrite`（枚举文档） | 高风险、需对抗 | **降级为观察项**：本机未发现 iqoo.secure 有回写行为；守护对抗的对象应是 `thermal-engine`（native） |
| `sec.autorun`（枚举文档） | 高 | 维持高，但确认了开关位是 `auto_start_fast_clean` / `low_auto_start_fast_clean`（`q4/c`），不是黑名单式管控 |
| 云控冻结方案 | 只 hook abe | **补一条**：iqoo.secure 走 `com.vivo.daemonservice.unifiedconfigprovider`，需单独覆盖 |

---

## 6. 未验证 / 下一步

1. `com.vivo.daemonservice.unifiedconfigprovider` 的实现类与返回结构**未定位**（它在 daemonService 包内，本轮只索引了字符串，没查 provider 类）；
2. iqoo.secure 的 `FirewallScriptExecutor` 通过 `vivo_daemon.service` 执行脚本，**具体脚本内容未抓**；
3. `temp/k` 的其余方法（`a/b/c/d/e`）未逐条读；
4. 未验证 iqoo.secure 是否在"游戏模式"下改 `sys.super_power_save`（有字符串 `sys.super_power_save`，但读写方向未确认）；
5. 真机实验未做：在游戏高负载时观察 `dumpsys activity` 里 iqoo.secure 是否被拉起、是否触发清理。
