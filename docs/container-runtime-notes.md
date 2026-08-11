# VirtualApp 容器运行细节

本文从 AGENTS.md 拆出,记录 VirtualApp 接入状态、设备实测结论和 Limbus 专项兼容细节。AGENTS.md 只保留架构边界和当前关键判断。

VirtualApp 接入状态:

- 上游固定为 ServenScorpion/VirtualApp Android 12 分支提交 `ae7c4275096b6614fa1aa7befe326630fd3f8833`。
- 源码通过 `scripts/vendor_virtualapp.sh` 放入 `third_party/virtualapp-upstream/`。
- `third_party/virtualapp-upstream/lib` 作为 `:virtualapp` 本地 library module 编译。
- 已迁移 AndroidX annotation/core、AGP 8 namespace、AIDL、Android 12 exported 属性和部分隐藏 API 兼容。
- 已移除旧 phone/SMS observer 启动调用,避免宿主启动阶段触发通话/短信权限异常。
- 已用空实现替代上游专有 Safekey SDK 服务。
- 已启用 ndk-build,固定使用 Windows Android SDK 中完整安装的 NDK `30.0.14904198`,只打包 `arm64-v8a`。
- APK 必须包含 `lib/arm64-v8a/libv++_64.so`;缺失时虚拟进程会在 `NativeEngine` 加载阶段失败。
- 已验证 Google Play 已安装游戏 `v424` 可同步到 VirtualApp 包缓存。
- 已验证 Redmi/Android 13 真机上 Google Play 已安装游戏 `v436` 可同步到容器工作区和 VirtualApp 包缓存。
- VirtualApp server 在 Android 12+ 创建 `PendingIntent` 时必须显式使用 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`;当前 `SyncManager` 使用 `FLAG_IMMUTABLE`,避免 BinderProvider 启动崩溃。
- Android 13 `PackageParser` / `PackageUserState` 反射构造存在平台差异;`PackageParserCompat` 必须通过构造器、`PackageUserState.DEFAULT` 和无参反射逐级 fallback。
- Android 13 包管理 flags 可能以 `long` 或 flags wrapper 传入;VirtualApp `pm` hook 读取 `getApplicationInfo`、`getPackageInfo`、query/resolve 等 flags 时必须统一兼容 `Integer`、`Long` 和 `getValue()` wrapper。
- 启动游戏必须走 `VirtualCore.getLaunchIntent(packageName, 0)` + `VActivityManager.startActivity(intent, 0)`。不得使用宿主 `PackageManager.getLaunchIntentForPackage()` 作为兜底,也不得把宿主系统解析出的 `ActivityInfo` 传入 VirtualApp,否则可能启动回宿主汉化器并形成 Activity 重启循环。
- 若宿主覆盖安装后 VirtualApp 包缓存丢失游戏,启动前必须从容器工作区 `splits/` 自修复安装 `base.apk` 并恢复 split native libraries,再通过 VirtualApp 启动。
- `ShadowActivity` 同进程判断必须优先使用 `VClient.getClientConfig().processName`;`VirtualRuntime.getProcessName()` 在 application bind 前可能为 `null`,直接使用会导致 stub Activity 递归启动。
- `ShadowActivity` 在启动真实 Activity 前必须确保 `VClient.bindApplication(packageName, processName)` 已执行;否则 `VClient.getCurrentApplication()` 为空。
- MuMu x86_64 + Houdini/`libnb.so` ARM64 转译环境下,`NativeEngine.launchEngine()` 进入 `libv++_64.so` 的 `measureNativeOffset()` / `hookAndroidVM()` 会 SIGSEGV。当前在该转译运行时跳过 native VM hook,避免虚拟进程启动期崩溃。
- Android 12 `ActivityThread.AppBindData` 与 `VMRuntime` 反射字段存在空引用差异;写入这些字段前必须判空。
- Android 12/MuMu 上 `Handler.mCallback` 已注入但没有收到可用的 `EXECUTE_TRANSACTION` launch 回调;当前启动接管点前移到 `AppInstrumentation.newActivity()`。
- `AppInstrumentation` 能命中 `ShadowActivity$P0` 并解析 `StubActivityRecord`,可在目标类存在时直接用虚拟应用 classloader 创建真实 Activity,避免 `ShadowActivity.startActivity(realIntent)` 拉起系统原游戏进程。
- Limbus `com.inka.appsealing.AppSealingApplication` 负责释放/挂载 sealed dex;若直接替换为 `android.app.Application`,`com.unity3d.player.UnityPlayerActivity` 不在虚拟 classloader 中,容器启动会失败。
- MuMu Android 12 禁止 `System.setSecurityManager`,因此不能用 Java `SecurityManager` 拦截 AppSealing 的 `System.exit(0)` 后继续保留 sealed dex 副作用;该路径已移除,避免启动前崩溃。
- AppSealing 当前以稳定 fallback 方式跳过真实 Application,并在 `LimbusVA` 中记录 `sourceDir`、`splitSourceDirs`、`nativeLibraryDir`、`dataDir`、installer、签名数量和关键目录存在性,用于下一轮定位需要伪装的环境项。
- Android 13/MIUI 上 `Context.getExternalFilesDirs()` 和 `Environment.isExternalStorageRemovable()` 可能通过 `StorageManagerService.getVolumeList` 触发跨用户权限拒绝;VirtualApp IO relocation 必须使用宿主私有合成 TF 根,并把 removable 判断的 `SecurityException` 视为不可判定而非启动失败。`IStorageManager.getVolumeList` 的首个 Binder 参数在 Android 12 及以前是 UID、Android 13 起是 userId;mount hook 必须按系统版本传宿主真实 UID/userId,不得把宿主 UID 当作 Android 13 的 userId。
- Redmi/Android 13 真机上已确认 `p0` 主 Activity 进程能绑定 Limbus、创建 `UnityPlayerActivity`、绘制 Unity 窗口并隐藏宿主包名;随后 AppSealing 报 `Kill Process [50040]` 并导致 `p0` 被 `SIGKILL` 杀掉。
- Limbus 游戏窗口不继承上游企业容器的防截屏策略。Activity 恢复时会清除一次
  `FLAG_SECURE`，窗口会话的 `add/relayout` 参数还会再次按 Limbus 包名移除该位，
  防止 Unity 或游戏稍后重提窗口参数而重新禁止系统截图；其他虚拟应用仍保留原策略。
- Redmi/Android 12 真机已确认 Google Play Limbus `v436` 能同步到 VirtualApp 包缓存,`p0` 能加载 `com.inka.appsealing.AppSealingApplication`、挂载 sealed dex 并创建 `UnityPlayerActivity`;随后 AppSealing 报 `Kill Process [50040]`,走 `setitimer` 和 `exit(0)` 后仍在 Activity resume 附近死亡,events 记录为 `am_proc_died ... reason=2`。
- Limbus arm64 进程因完整 VirtualApp VM hook 会在真实设备上崩溃,当前跳过完整 VM hook,改为安装 Java `Runtime.nativeExit` / `Process.sendSignal` 轻量 hook,并在 native IO hook 中拦截 `exit`、`_exit`、`abort`、`raise`、`kill`、`tkill`、`tgkill`、`pthread_kill`、`syscall(__NR_exit*)`、`syscall(__NR_kill/tkill/tgkill)` 等路径。
- Limbus native signal guard 只在容器进程内阻断终止类信号: `SIGKILL`、`SIGTERM`、`SIGABRT`、`SIGQUIT`、`SIGTRAP`、`SIGUSR1`、`SIGUSR2`;`SIGALRM` 继续按 AppSealing 计时器路径单独忽略。必须显式放行 `SIGXCPU` / `SIGPWR`: Redmi/Android 12 实测阻断 signal 30 会让 Unity il2cpp 在初始化阶段触发 `abort()`。
- Limbus native syscall 扫描必须覆盖 `libcovault-appsec.so`、`libil2cpp.so`、`libunity.so`、`libmain.so`,并支持 `svc #0` 前多条指令内设置 `x8` / `w8` 的 AppSealing 形态;不能只识别紧邻 `svc` 的 `mov x8,#imm`。
- AppSealing `LoadedApk.makeApplication()` 执行期间必须启动短时 native syscall 扫描,因为 `Kill Process [50040]` 会发生在 `makeApplication` 返回之前;返回后只做一次收尾扫描。
- 对 Limbus inline `kill`、`tkill`、`tgkill`、`exit`、`exit_group` syscall,当前使用指令级补丁把目标 `svc` 改为 `mov x0,#0`,避免在 `svc` 指令上使用 `MSHookFunction` 造成无崩溃记录的进程死亡。
- Redmi/Android 12 真机已验证 Google Play Limbus `v436` 在补丁后可越过 AppSealing 早期自杀点:前台 Activity 为 `ShadowActivity$P0`,root process 为 `com.example.limbuszhcn:p0`,进程名伪装为 `com.ProjectMoon.LimbusCompany`,并能在容器内 `files/` 写入运行期数据。
- Java 层会把 Limbus 可见 `ApplicationInfo.dataDir`、`LoadedApk` dataDir 和 Activity/Context 目录伪装为 `/data/user/0/com.ProjectMoon.LimbusCompany`;真实 IO 仍通过 VirtualApp 重定向到宿主私有 `virtual/data/...`。
- native IO hook 必须正确处理 `readlinkat` 和 `syscall(__NR_readlinkat)`: `readlink` 返回值不保证 NUL 结尾,反向路径伪装必须按返回长度复制,避免 `/proc/self/fd` 扫描暴露宿主虚拟路径。对 Limbus 还要兜底伪装 `/data/data/com.example.limbuszhcn/virtual/storage/emulated/0/` 和 `/data/user/0/com.example.limbuszhcn/virtual/storage/emulated/0/` 为 `/storage/emulated/0/`,否则 AppSealing 可通过 fd readlink 看到宿主 VM 路径。
- Limbus 可见 `ApplicationInfo.sourceDir`、`publicSourceDir`、`splitSourceDirs`、`splitPublicSourceDirs`、`nativeLibraryDir` 和 `dataDir` 必须与 Google Play 真实安装源对齐;该修正既要写入 `LoadedApk`,也要覆盖 PackageManager hook 返回的 `ApplicationInfo`。
- Limbus PlayCore asset pack 不能绑定宿主 Play Store 的 `AssetModuleService`: Finsky 会用 Binder 调用方 UID 校验包归属,并返回 `API_NOT_AVAILABLE(-5)` / `Package name com.ProjectMoon.LimbusCompany is not owned by caller`。当前通过向 Limbus 可见 `ApplicationInfo.metaData` 注入 `local_testing_dir` 让 PlayCore 走 `FakeAssetPackService`。
- Limbus 的 BillingClient 在容器内会先解析到 FakeStore 兼容组件
  `com.android.vending/com.android.vending.billing.InAppBillingService`，但宿主 Play Store 的真实实现类通常是
  `com.google.android.finsky.billing.iab.InAppBillingService`。转发宿主计费绑定时不能直接用容器内显式组件查询宿主；
  必须保留 billing action 与包名、清除旧组件后让宿主 PackageManager 重新解析，再把真实组件写回系统绑定 Intent。
  否则宿主查询必然返回空，代码会错误回退到容器 FakeStore，随后 microG 的 `vending_billing` 查询返回空游标，
  最终表现为每次冷启动弹出 `Purchase Initialize Failed`。
  真实服务绑定成功后，`IInAppBillingService` 调用仍由宿主 Linux UID 发出；Billing 8.3 的
  `isBillingSupported`（混淆方法 `zzb`，transaction 1）及带附加参数版本（`zzc`，transaction 10）中的
  调用包名必须改写为宿主包名，使 Play Store 的包名/UID 校验一致。商品详情 `zzj` 与购买记录 `zzi`
  查询则必须继续使用 `com.ProjectMoon.LimbusCompany`，否则 Play Store 会查询宿主应用的商品目录并返回
  `NoProductsAvailable`。该分流必须同时校验方法名、返回类型和参数签名，不能只按字符串参数全局替换。
- PlayCore 本地测试目录位于宿主私有 `files/playcore-local-testing/com.ProjectMoon.LimbusCompany`,目录内用 `*-master.apk` symlink 指向已安装 Google Play split APK。`UnityDataAssetPack-master.apk` 指向 `split_UnityDataAssetPack.apk`,`UnityStreamingAssetsPack-master.apk` 当前指向 `base.apk` 作为空/兜底 asset pack 源。
- PlayCore 本地测试 symlink 必须按当前 Google Play 安装源幂等更新。判断旧链接时不能只用 `File.exists()`:目标失效的 symlink 会返回 false,但重新 `symlink()` 会得到 `EEXIST`。
- 注入 PlayCore `local_testing_dir` 时会触发 PackageManager 递归查询,必须使用线程内 guard 防止 hook 重入。
- Limbus 绑定 GMS `MeasurementBrokerService` 时,Google Play services/microG 会校验 `GetServiceRequest` 内的调用包名。容器必须通过 `ServiceConnectionDelegate` 包装 `IGmsServiceBroker`,把请求对象里残留的 `com.ProjectMoon.LimbusCompany` 改写为宿主包名,否则会抛出 `SecurityException: Unknown calling package name 'com.ProjectMoon.LimbusCompany'` 并中断启动。此包装既用于宿主 GMS,也用于容器内置 microG;不能以 GMS 已虚拟安装为理由跳过。
- Limbus 的通用 `PackageManager` 查询必须默认隔离宿主环境:`getInstalledApplications`、`getInstalledPackages`、`queryIntent*`、`get*Info` 和虚拟包未命中的精确包查询不得回退到宿主 PM。宿主 Google 包只允许在精确查询 `com.google.android.gms`、`com.google.android.gsf`、`com.android.vending`、`com.google.android.play.games` 信息时显式暴露。
- Limbus `exit`、`_exit`、`abort` native hook 必须记录 native backtrace,至少包含调用方 so 和 offset。默认在容器进程内终止调用线程,避免 AppSealing 自杀路径返回后继续执行导致不可诊断崩溃。
- Redmi/Android 12 上当前黑屏阶段的稳定 AppSealing 退出点是 `libcovault-appsec.so +0x20c58` 调用 `exit(0)`,同一函数后续 `+0x20d40` 是 `mov x8,#0x5d; mov x0,#0; svc #0`,即 direct `exit_group(0)`。当前对该已知点做精准补丁:先把 `+0x20d40` 的 `svc` 改为 `mov x0,#0`,再终止当前 native 调用线程,避免返回 AppSealing 调用方继续执行未知保护分支。
- Limbus 可见 `ContextImpl.mBasePackageName` 必须在游戏 Context 上保持 `com.ProjectMoon.LimbusCompany`；但 `mOpPackageName` 和 `NetworkRequest.requestorPackageName` 必须使用宿主包名，确保跨系统 Binder 的 AppOps 身份与真实 UID 一致。Limbus 专用 `ConnectivityManager.mService` proxy 只修正请求对象中的调用身份，不改写游戏可见包名、数据目录或 APK 路径。
- Redmi/Android 12 真机已验证 Google Play Limbus `v438` 可通过 debug intent 同步已安装 `base.apk`、`split_UnityDataAssetPack.apk` 和 `split_config.arm64_v8a.apk`,在 `ShadowActivity$P0` 容器前台完成 Unity 启动动画并保持宿主 UID。宿主 GMS 透传后不再出现 `requires Google Play services, but they are missing`,Firebase 初始化成功;若进程 `cmdline` 显示 `com.ProjectMoon.LimbusCompany`,需以 ActivityManager 记录和 UID 判断是否仍在容器内。
- Firebase C++ Java fallback 必须保持官方 JNI ABI：`JniResultCallback(Task,long,long)`、
  `CppThreadDispatcherContext(long,long,long)` 及其取消/执行/锁方法、线程调度入口必须成套存在。
  `play-services-tasks` 只允许作为容器模块的 `compileOnly` 依赖；运行时必须使用游戏
  classloader 中的 `Task`，不得把另一份 GMS Task 打进汉化器 APK。
  Auth listener 也必须保留并实际调用官方注册的 native 入口：
  `nativeOnAuthStateChanged(long)`、`nativeOnIdTokenChanged(long)` 及四个 phone-auth
  callback；空实现会让 C++ `RegisterNatives` 直接失败，登录状态永远无法初始化。
- MIUI 会把 `Activity.requestPermissions()` 定向到 `com.lbe.security.miui`。Limbus 的
  宿主 Activity 隔离只可对精确的 `android.content.pm.action.REQUEST_PERMISSIONS`
  系统动作放行，并把 Binder 调用包名改写为汉化器宿主包；不得据此暴露任意 MIUI
  Activity 或放宽通用宿主 PackageManager 查询。Android 13 的 `startActivity` 在
  `callingPackage` 与 `Intent` 之间增加了 `callingFeatureId`，因此调用包名必须按
  “第一个已安装虚拟包字符串”定位后替换，不得继续使用 `intentIndex - 1`。
- Android 8+ 的 VirtualApp server 保活必须由 `KeepAliveService` 自身立即
  `startForeground`。旧版通过第二个 `HiddenForeNotification` 服务隐藏通知的方案既不会
  提升 server 进程优先级，也会在 Android 12+ 后台重启时触发
  `ForegroundServiceStartNotAllowedException`，导致 `:x` server 死亡并连带退出全部容器进程。
- Redmi/Android 12 真机在 Limbus `v1.107.1` 上已验证:容器游戏可通过 `PlayVideo -> PlayWarningAnim -> PlayDone` 并进入完整标题页。最终黑屏根因是 arm64 `getcwd` hook 把 raw syscall 返回的路径字节数 `2` 暴露给 il2cpp,随后触发 `strlen(0x2)` 并破坏 Addressables 运行时路径;恢复 libc 的缓冲区指针返回语义后,`RuntimeData is null` 和 FMOD 资源缺失错误均消失。
### Android 13 网络状态与 AppOps 调用身份

容器内 Limbus 的 `ContextImpl.mBasePackageName` 仍保持游戏包名，供游戏自身的包名检查；
但 `mOpPackageName` 必须保持汉化器宿主包名。ConnectivityManager 等系统服务看到的
Linux UID 属于宿主，如果把 AppOps 包名伪装为 `com.ProjectMoon.LimbusCompany`，
Android 13 会以 `Package ... does not belong to uid ...` 拒绝调用，Unity 随后会把本机
判定为不可联网。`NetworkRequest.requestorPackageName` 同样必须在跨系统 Binder 前改写
为宿主包名。Android 13 起 `IConnectivityManager` 还可能把调用身份作为独立字符串或
`AttributionSource` 传递，兼容层必须按参数实际类型改写三种载体，不能依赖固定参数位置
或只处理旧版 `NetworkRequest`。这种身份分离不改变游戏可见的包名、数据目录或 APK 路径。

Google 登录的 microG UI 会使用系统 WebView。Chromium 通过 `bindIsolatedService` 启动
`BIND_EXTERNAL_SERVICE` 渲染进程时，Android 13 的 `bindServiceInstance` 在 flags 后同时
带有 `instanceName` 与 `callingPackage`；固定改写第 6 个参数会漏掉真正的调用包名，导致
`calling package not owned by calling UID`。所有跨真实 ActivityManager 的 bind 调用必须
按“已安装虚拟包字符串”定位并改写为宿主包名，包括虚拟服务最终绑定宿主 stub 的分支。

GMS broker 跨系统 Binder 时仍须把 `GetServiceRequest.packageName` 改为宿主包名以通过
UID 归属校验，但 microG Identity Sign-In 会继续把该值作为 OAuth 客户端身份。启动精确的
`GOOGLE_SIGN_IN` / `ASSISTED_SIGNIN` 虚拟 UI 前，必须把 PendingIntent 中的
`client_package_name` 从宿主恢复为 `com.ProjectMoon.LimbusCompany`，使 Google 使用游戏
包名和游戏签名校验。不得取消 broker 改写，也不得泛化修改其它 Intent action 或 extras。
Android 9+ 的 HCallback 可能在 `Instrumentation.newActivity()` 前已经把宿主 stub 解包，
因此 microG `AuthSignInActivity` 的 calling-activity 恢复不能只挂在 `pendingStubRecords`
分支；必须在 `callActivityOnCreate()` 根据最终虚拟 `ActivityInfo` 安装 Android 13
`ActivityClient` caller proxy，否则 microG 会在联网前返回 `Status 10: package name mismatch`。
登录 UI 成功后，游戏还会连接 microG 的
`org.microg.gms.auth.credentials.identity.AuthorizationService`。该服务通过容器内调用
进程校验 `GetServiceRequest.packageName`，因此只对这一精确虚拟服务保留游戏包名；若仍
套用通用宿主改写，会返回 `API 17 / DEVELOPER_ERROR`。Measurement、宿主 GMS 和其它未知
broker 服务继续使用宿主包名，不能把这个例外扩展到整个 microG。

2026-08-11 的 vivo/Android 13 Issue #1 诊断包确认，Google 登录调起的 `:p1/:p2`
辅助进程在绑定 microG 应用时进入 `libv++_64.so -> hookAndroidVM ->
NativeEngine.launchEngine()` 并发生同步 `SIGSEGV`。主游戏进程已经因为 AppSealing/ART
兼容要求跳过旧式 `jmethodID` 内存改写；microG Services 与 FakeStore 同样不依赖该旧 Hook，
其 IO 重定向会在 `launchEngine()` 前独立完成，Binder 身份和结果链也由 Java 代理维护，
因此必须对这两个精确包跳过 VM Hook，不能泛化到未知虚拟应用。

同一版本游戏的 Firebase Auth manifest 声明了
`genericidp://firebase.auth/` 与 `recaptcha://firebase.auth/` 两个浏览器返回入口。游戏 APK
没有直接安装为汉化器宿主组件，系统浏览器无法把回调解析到容器内 Activity；宿主须声明
只匹配这两个精确 URI 的导出跳板，丢弃浏览器携带的 component/package/extras，仅保留
ACTION_VIEW、原始回调 URI 和 BROWSABLE/DEFAULT category，再显式启动容器内
`GenericIdpActivity` 或 `RecaptchaActivity`。其它 scheme/host/path 一律拒绝。

### Android 16 资源完成状态持久化

Android 16（API 36）的 arm64 bionic `renameat` 入口只有 12 字节，并会继续跳转到
`renameat2`。旧版 inline hook 若直接覆盖该入口会破坏相邻的 `rmdir`，因此 native 层会在
API 36 跳过这个 hook。副作用是 Unity `PlayerPrefs` 通过 `SharedPreferencesImpl` 执行
“临时文件重命名为正式文件”时，仍会使用游戏可见路径
`/data/user/0/com.ProjectMoon.LimbusCompany/...`，无法命中宿主私有容器目录。

这类失败不会删除已经下载的资源文件，但会让游戏无法保存资源版本和下载完成标记，
下次启动便会再次要求下载。容器必须在 `Libcore.os.rename` 代理中同时重定向源路径和目标路径；
native `renameat` hook 仍保持跳过，避免在 Android 16 上引入函数入口覆盖崩溃。该代理对旧系统也安全：
已经重定向过的宿主路径再次经过 native 层时不会重复改写。

Unity Addressables 的缓存提交不经过 Java Libcore。下载完成后，Unity 会把
`UnityCache/Temp/<临时文件>` 原子移动到 `UnityCache/Shared/<资源键>/<哈希>`；Android 16
的 `rename` 和 `renameat` 都会跳转到 28 字节的 `renameat2` syscall 包装。若只修 Java 代理，
日志仍会出现 `Couldn't move cache data ... error code -1`，资源会反复下载后滞留在临时目录。
API 36 必须保留 12 字节的 `renameat` 不动，改为 hook 具有足够入口空间的 `renameat2`，并在
执行 syscall 前同时按各自 dirfd 重定向源路径和目标路径。这样既不会覆盖相邻 `rmdir`，也能
让 Unity 的 native 缓存移动真正提交到宿主私有虚拟存储。

VirtualApp 服务进程还可能被系统冻结或回收。宿主读取虚拟安装状态遇到
`RemoteException` 时应清除旧的 service fetcher 与应用管理 Binder，主动拉起服务后只重试一次；
整个恢复过程不得卸载虚拟游戏或清理数据目录，以保证已经下载的资源继续保留。
