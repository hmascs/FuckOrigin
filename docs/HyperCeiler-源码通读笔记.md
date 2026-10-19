# HyperCeiler 源码通读笔记（v2.10.166 / main）

> 方法：拉取**完整仓库**（`codeload` tar.gz，26 MB，解包 3,917 个路径）后按「构建 → 运行时 → 规则 → 配置 → UI → 安全」六遍通读，
> 不做抽样猜测。所有结论都标了文件路径，可逐条复核。
> 代码规模：**155,802 行** Kotlin/Java，7 个 Gradle 模块。

---

## 1. 模块图（先看这张表，后面都挂在这上面）

| 模块 | 行数 / 文件 | 职责 | 关键依赖 |
|---|---|---|---|
| `app` | 19,412 / 127 | 模块 App 本体：首页三页 UI、设置页、关于页、搜索、安全模式页、更新 | `library:core` `library:provision` |
| `library:libhook` | **101,444 / 840** | **全部 Hook 规则**（68 个作用域、629 个规则文件）+ 运行时入口 + DexKit 封装 + 崩溃拦截 | `libxposed api 102`、`EzHookTool` |
| `library:core` | 15,330 / 135 | 共享资源、偏好页基类、日志、AppHelper、`appiconloader` | `common` `provision` |
| `library:common` | 4,939 / 31 | `PrefsBridge`（配置桥）、日志抽象、跨进程契约常量 | — |
| `library:provision` | 6,167 / 50 | **首次启动引导**：激活说明、权限引导、完成页（`ProvisionDetailActivity`/`CongratulationActivity`） | `common` `libhook` |
| `library:hidden-api` | 108 / 9 | 隐藏 API 编译期桩：`SystemProperties`、`MiuiSettings`、`ViewRootImpl`、`PowerExemptionManager`… | — |
| `library:processor` | 204 / 2 | **注解处理器**：把 `@HookBase` 编译成 `DataBase` 类 | `auto-service` |

另有 `app/src/main/cpp/nativehook/`：**121 个 C++ 文件**（inline hook / GOT hook / ELF 解析 / arm64 反汇编），
说明它的能力不止 Java 层 Hook；`tests/` 下 **80 个测试目录**，每个规则可带 fixture（这是它工程化程度高的关键）。

---

## 2. 构建与产物形态

- **Xposed API**：`library/libhook/src/main/resources/META-INF/xposed/`
  - `java_init.list` → `com.sevtinge.hyperceiler.libhook.base.XposedInitEntry`
  - `module.prop`：`minApiVersion=101`、`targetApiVersion=102`、`autoHotReload=true`、`staticScope=false`
  - `native_init.list` → `libHyperCeilerNative.so`
  - `scope.list` → 静态作用域清单（`system`、`com.android.systemui`…）
  → 用的是 **libxposed 新 API（101/102）**，与老 `IXposedHookLoadPackage` 不是一套；这也是它能做热重载的前提。
- **混淆**：`libhook` 用 `lsparanoid`（`seed=227263`、`classFilter={true}`、仅非 debug 变体），所以**规则类名在 release 里是不可读的**。
- **SDK**：`compileSdk=37`、`minSdk=35`、Java 25 工具链（`libhook/build.gradle.kts`）。
- 依赖解析需要 `gpr.user`/`gpr.key`（`settings.gradle.kts`），因为 `fan.miuix:*` 在 GitHub Packages。

---

## 3. 运行时架构（一条主链）

```
LSPosed 载入模块
   └─ XposedInitEntry (extends XposedModule, libxposed api 102)
        ├─ onModuleLoaded   → initPrefs() + EzXposed.initOnModuleLoaded + BaseLoad.init
        ├─ onPackageReady   → PackageTarget.ofPackageReady(...)  [只处理首个包]
        ├─ onSystemServerStarting → PackageTarget.ofSystemServer(...)
        └─ onHotReloading   → 拒绝条件判定 + EzXposed.handleHotReloading(snapshot, extras)
             └─ onTargetReady → installCurrentTargetHooks()
                  └─ 查 DataBase(由@HookBase生成) → ModuleMatcher.shouldLoad
                       └─ new XxxLoad().onPackageLoaded()
                            └─ initHook(new Rule(), PrefsBridge.getBoolean("key"))
                                 └─ BaseHook.init()  ← 具体 Hook
```

关键类：
- `base/XposedInitEntry.java`：阶段式入口；带 **热重载状态快照**（`EXTRA_MAGIC` + `EXTRA_VERSION` 头，布局变更必须升版本，否则整体拒绝）。
- `base/PackageTarget.java`：把「普通包」与 `system`（system_server）统一成同一抽象。
- `base/BaseLoad.java`：每个目标应用一个子类；`initHook(hook, condition)` **全部包 try/catch**，单条规则失败不影响宿主。
- `base/BaseHook.java`：规则基类（`init()`、`useDexKit()`、`requiredMember/optionalMember`、DSL 入口）。
- `base/ModuleMatcher.java`：加载门控，判定顺序 = **安全模式 → 修改检测 → 包名 → SDK → OS 版本 → 设备类型**。

---

## 4. 规则装配：`@HookBase` + 注解处理器（很值得借鉴的一招）

- 每个目标应用一个 `BaseLoad` 子类，标 `@HookBase(targetPackage="com.xiaomi.joyose")`；
- `@HookBase` 字段：`targetPackage / minSdk / maxSdk / minOSVersion / maxOSVersion / deviceType`（`library/processor/.../HookBase.java`）；
- `HookBaseProcessor` 在编译期生成 `com.sevtinge.hyperceiler.libhook.base.DataBase`（HashMap：包名 → 元数据），
  **运行时零反射扫描**；
- 例子（`libhook/app/Joyose.java`，全文仅 8 行逻辑）：
  ```java
  @HookBase(targetPackage = "com.xiaomi.joyose")
  public class Joyose extends BaseLoad {
      @Override public void onPackageLoaded() {
          initHook(new DisableCloudControl(), PrefsBridge.getBoolean("various_disable_cloud_control"));
          initHook(new EnableGpuTuner(),     PrefsBridge.getBoolean("joyose_enable_gpu_tuner"));
      }
  }
  ```

DexKit 规则会被**延迟批处理**：`initHook` 发现 `useDexKit()` 就先挂进 `mPendingDexKitHooks`，
下一次非 DexKit 规则/收尾时统一提交到 `ThreadPoolManager` 并行初始化，再 flush。

---

## 5. 配置层：`PrefsBridge`（双实现，是这套架构里最容易被忽略的一环）

`library/common/.../PrefsBridge.java`：
- 名称常量 `PREFS_NAME = "hyperceiler_prefs"`；
- **App 进程**：`initForApp()` → 设备保护存储（`createDeviceProtectedStorageContext`）里的物理 SP；
- **Hook 进程**：`initForHook(SharedPreferences remote)` → 由 LSPosed 注入的**远程 SP**（不是 `XSharedPreferences`）；
- `wrap(key)` 自动补前缀：`"various_x"` → `"prefs_key_various_x"`，所以规则里写短 key、XML 里写全 key；
- Hook 进程读值走 `sHookCache`，写操作会被警告（`sWarnedHookWrites`）——**配置的唯一写侧是 App**。

→ 对我们项目的影响：我们现在的 stub 用的是老的 `XSharedPreferences`。若要跟进，需要改成
`XposedInterface` 提供的 remote prefs 接口，或在 App/Hook 之间自建 ContentProvider 通道。

---

## 6. UI 层：一层 XML 驱动整个首页

| 事实 | 文件 |
|---|---|
| 首页 = `activity_home.xml`（只有 `HomeViewPage2`）+ `HomeContentAdapter extends FragmentStateAdapter`（三页：主页/设置/关于） | `app/.../home/widget/HomeViewPage2.java`、`home/adapter/HomeContentAdapter.java` |
| 每页骨架 = `NestedHeaderLayout`（可折叠头部）+ `miuix_appcompat_search_stub` + 可滚动内容 | `res/layout/fragment_page.xml` |
| **首页列表完全由 XML 声明**：`preference-headers`，465 行，每项 `android:title` + `android:summary`(包名) + `android:icon`(默认占位) + `android:fragment` 或 `app:inflatedXml` | `app/src/main/res/xml/settings_header.xml` |
| 列表行 = 真实 App 图标（`IconTitleLoader` 从 PackageManager 取）替换占位图标；行规格见 `item_app_list.xml`：图标 40dp、左右 15dp、标题 18sp | `home/IconTitleLoader.java`、`library/core/res/layout/item_app_list.xml` |
| 搜索 = **ActionMode 覆盖层**（点搜索图标进入，不是常驻输入框），带历史记录与结果列表 | `HomePageFragment#mSearchCallback` |
| 底部导航有**两种样式**：`CAPSULE_ICON`（悬浮胶囊）/ `BOTTOM_LABEL`（传统三标签） | `home/widget/NavigationStyle.java` |
| 设置页 = `NestedHeaderLayout` + `SpringBackLayout` + `RecyclerView(id=scroll_headers)` | `res/layout/home_settings.xml` |
| 偏好页 XML 形态：`<PreferenceScreen app:myLocation app:quick_restart="包名">` → `<PreferenceCategory>` → `<SwitchPreference android:key="prefs_key_*">` | `library/core/src/main/res/xml/joyose.xml` |

> 这段解释了我之前的疑问：首页那个「应用列表」不是代码里硬编码的，而是 `settings_header.xml` 声明 + 运行时换图标。
> 我们要做「按功能分类」，等价做法是把这层 XML 换成「功能域 → 功能项」的两级声明，其余组件照旧。

---

## 7. 安全模式（崩溃自恢复）

`safecrash/` 四个类：`CrashMonitor.kt`、`CrashScope.kt`、`SafeModeHandler.kt`、`DataCrashRecord.kt`。
- 崩溃发生时 `SafeModeHandler.onCrashDetected()` 判断是否已在安全模式（配置或属性两种口径），
- 未在则写属性标记 + 拉起模块内的 `CrashActivity`（`FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK|SINGLE_TOP`），携带包别名与堆栈，
- 用户可据此只关掉出错的那个作用域，而不是整个模块。

→ 这是我们目前**完全没有**的一层，而它恰恰是"敢装到别人机器上"的前提。

---

## 8. 规则写法库（四种常见模式）

1. **常量返回**（最常见）：`findClass("...").findAllMethods{ name("setScreenEffect"); paramCount(3) }.single().createHook { returnConstant(null) }`
2. **DexKit 定位 + 兜底链**（版本差异大时）：`requiredMember` → `optionalMember`，按版本从新到旧依次尝试
   （见 `rules/aiasst/NewAiCaptions.kt`：先用字段 `Build.DEVICE` 的特征找新方法，找不到回退旧特征）
3. **字段改写**：拿 `XC_MethodHook` 后在 `afterHookedMethod` 里改对象字段（如我们见过的 `DisableThermal` 直接消掉 `postEventListener` 的结果）
4. **字符串锚点 + DexKit**：`DisableCloudControl` 用 `usingStrings("job exist, sync local...")` 找 Joyose 的同步方法——
   这与我们定位 OriginOS 的 `qryIsGameInConfig` 是同一类思路，只是我们的锚点换成了中文日志与 `isGame=` 字符串。

统计上的偏好：`findClass(` 261 次、`requiredMember` 196 次、`useDexKit` 114 次、`isPad` 82 次
（说明平板适配在小米生态里是刚需，OriginOS 侧对应的是机型常量表 `PD2xxx`）。

---

## 9. 对我们项目的移植结论

**可以直接对齐的机制（不涉及代码搬运）**
1. **两级门控**：`@HookBase` 那套「包 + 版本 + 设备类型」判定 → 我们需要的是「包 + 进程 + 版本」判定（OriginOS 有 `:assistantui` 多进程）；
2. **规则 try/catch + 单条失败不影响宿主**：我们目前的 `HookEntry` 已有 `hookIfExists`，但没有失败记录与热重载校验；
3. **XML 驱动 UI**：把我们的 8 个功能域做成声明式列表，而不是硬编码（可维护性）；
4. **安全模式**：崩溃 → 记包名 → 拉起降级页 → 只关出问题的规则。这是我们最该补的；
5. **DexKit 思路**：OriginOS 上我用的是离线索引（`dx.py`）+ 字符串锚点，等价于它的 `requiredMember`，但缺少"版本回退链"。

**不能搬的**
- 代码本身（AGPL-3.0 + 任务书的"代码零引用"）；
- `com.miui.*` / `com.xiaomi.*` 的类名与包名——OriginOS 上不存在，必须换成我们逆向出的 `com.vivo.*` 落点。

**明确不适用**
- 小米的 `MiuiSettings` / `MiuiFramework` 字符串与资源键；
- 依赖 `fan.miuix` 的界面控件（GitHub Packages + aapt2，本机工具链拿不到）。

---

## 10. 还没读到的部分（诚实清单）

1. `library/core` 的 15k 行只读了偏好页基类与资源，`AppHelper`、`FragmentHelper`、各 `*Settings` 页未逐行读；
2. `app` 的 19k 行里只精读了 `home/` 与安全模式入口，`settings/`、`about/`、`search/` 只看了结构；
3. `nativehook/` 121 个 C++ 文件只看了目录与后端命名（inline/GOT），**未读实现**；
4. `library/provision` 的引导流程未展开；
5. 629 条规则里只抽读了 6 条（systemframework / systemui / joyeye 系 / powerkeeper / aiasst / securitycenter 各一），
   其余按目录名归类，未逐条验证行为。
6. 热重载的完整状态机（`prepareHotReloadState` 之后的分支）只读到一半。

下一步若要继续：建议按「systemui（136 条）→ systemframework（76 条）→ securitycenter（56 条）」的顺序逐条读，
因为这三块与 OriginOS 的对应面最大。
