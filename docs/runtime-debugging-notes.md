# Limbus 运行诊断记录

本文记录已解决黑屏问题的诊断过程。架构约定仍以 `AGENTS.md` 为准,VirtualApp 兼容细节见 `docs/container-runtime-notes.md`。

## Limbus 1.109.1 Japanese-only full text takeover

The multilingual schema-2 experiment produced an 88 MiB runtime index on the test
device: 245,587 source entries, 332,862 ID entries and 376,296 context rows. Native
startup loaded both hash tables and installed sixteen `TextData_*::GetText` hooks.
That coverage was too expensive for the normal launch path.

Production mode compiles only container-visible `jp/JP_*` resources into a compact
source-string index. Compilation must recursively include nested skill/coin tables
and story display fields rather than accepting only top-level `dataList` strings.
TMP setter hooks are only the final UI fallback; the string acquisition and
parameter-formatting paths must be covered for full character, skill and story
translation. `GlobalGameManager.Lang` is forced to the confirmed enum value
`JP=2` after the IL2CPP initialization boundary, with
`LocalSave.LocalGameOptionData.GetLanguage()` retained as a compatibility hook.
Validation requires an actual getter-hit log; hook installation alone does not
prove the game selected Japanese. A user stuck before the settings screen does
not need to edit the saved English value (`EN=1`) first. PlayerPrefs itself is
left unchanged.

The launcher must not treat a downloaded translation archive as an active
translation. On a new install, official Japanese resources appear only after the
game reaches its post-login download flow. Activating an index from the few files
present before that point creates a misleading partial installation. The launcher
therefore requires at least 90 percent of candidate manifest JSON files to have a
readable `jp/JP_*` counterpart. Below that threshold it preserves the previous
index, caches the package, launches a resource-preparation session, and rebuilds
from cache on the next run.

On the Redmi/Android 12 device, recursive compilation of package `2026071001`
increased the Japanese-only index from 81,642 unique entries / 123,695 source
records to 93,369 unique entries / 151,950 source records, with 1,080 ambiguous
sources excluded. This confirms that top-level-only traversal had omitted a
substantial portion of skill levels and coin descriptions.

Schema 6 retains 19,659 idempotent embedded-term mappings after filtering 26
unsafe schema-5 candidates, alongside 93,488 exact
mappings. It resolves dominant UI terminology such as `囚人 -> 罪人`, extracts
bracketed keywords such as `攻撃前 -> 攻击前`, and strips TMP tags to recover
terms such as `長姉 -> 长姊`. Runtime validation on the identity skill page showed
the nested `coinlist/coindescs` traversal running before formatting and replaced
the formerly mixed Japanese effect sentences with Chinese.

An identity skill later displayed a long run of `或` although its Chinese resource
contained the correct single sentence. TMP-input logging proved the run already
existed before layout. The schema-5 term table contained `以上 -> 或以上`; because
the result still ends in `以上`, each `SkillPerLevel -> Skill -> TMP` translation
layer matched it again. Schema 6 excludes every term whose translation contains
its source, with the same defensive filter when the native trie is built. Rich
text tags are still copied verbatim and ASCII-only terms require word boundaries.

Dante-note details initially remained Japanese except for a few term replacements.
TMP-input logging showed that `TextData_DanteNote` had inserted
`<line-height=170%>` before each newline and `<line-height=100%>` after the next
dash. Removing only those tags reproduced the exact indexed Japanese source.
The exact fallback now normalizes this layout-only markup and reapplies the same
paragraph spacing to translated Chinese lines.

The bundled LocalizeLimbusCompany `ChineseFont.ttf` is Sarasa Gothic SC Bold.
Its initial 1024x1024 dynamic atlas filled after roughly one thousand
translations and produced black blocks or missing letters, so production uses a
4096x4096 atlas. Keeping the Japanese primary face for translated paragraphs
then exposed a separate visual defect: common Han glyphs came from that face
while Simplified-Chinese-only glyphs came from the bold fallback, producing
alternating weights within a word. The runtime now tags managed strings it
creates as translation results and temporarily assigns the same Chinese asset as
primary only for those tagged strings. It remembers and restores the component's
original font when a recycled TMP object receives untagged text. The global
fallback remains registered for early layout and this policy is not inferred
from non-ASCII content alone.

Page-by-page formatter whitelisting was removed after identity passives and Dante
notes exposed the same omission pattern. The runtime now discovers all 59
`TextData_*` classes and selects string-returning getters with one or two
formatting arguments. Limbus 1.109.1 installed 54 one-argument and four
two-argument hooks, including passive/summary, abnormality events, EGO gifts,
quests, UI, Dante notes and mirror/railway text. Every selected body must still
have at least 16 bytes before the next method; adjacent trivial getters remain on
the TMP/UI exact-match fallback.

Acquisition hooks can return Chinese before TMP setters run. Initially the setter
therefore saw no local pointer change and created the fallback font too late: the
first loading hint showed squares for Simplified-Chinese-only glyphs. Font setup
now happens before any non-ASCII TMP string is laid out. A cold-start loading hint
then rendered the whole Chinese sentence without missing glyphs.

The global `dlsym` lifecycle hook must be installed after VirtualApp finishes its
libc IO-hook pass. Installing it during translation configuration caused the later
ARM64 relocator to process an already-patched prologue and crash in
`__fix_instructions`; the corrected order still intercepts Unity's first
`dlsym("il2cpp_init")` request before managed initialization.

The following section records the retired multilingual experiment for comparison.

## Retired multilingual ID translation index

Runtime inventory confirmed that `LocalizeTextData` owns a string `id`, while
character, personality, skill, passive, enemy, EGO and buff tables expose derived
`TextData_*::GetText(System.Object[]) -> System.String` methods. The runtime resolves
the base field with `il2cpp_class_get_field_from_name` and reads it through
`il2cpp_field_get_value`; it does not embed a managed object-layout offset.

Index schema 2 stores exact-source mappings and unambiguous `id + source` mappings.
The compiler reads every installed `en`, `jp`, and `kr` source row and maps them to
the same Chinese target. Translation package `2026071001` produced 245,587 source
entries, 332,862 ID entries, and 376,296 context rows on the test device. Sixteen
targeted `TextData_*::GetText(Object[])` hooks installed before `PlayDone`, alongside
the TMP fallback hooks. Japanese mode therefore retains the game's CJK-oriented
font/material path without the localizer forcing a language.

## Limbus 1.109.1 runtime translation index

On the Android 12 Redmi test device, translation package `2026071001` was
compiled against the container-visible official English tables into:

- 61,318 unambiguous source-to-translation entries;
- 76,359 context rows;
- 617 ambiguous source strings excluded from the context-free fast index.

The immutable index was loaded in the Limbus guest before application startup.
Because AppSealing loads `libil2cpp.so` in an isolated linker namespace,
`RTLD_NOLOAD` from the VirtualApp library cannot obtain its handle. The resolver
instead uses the offset-zero `/proc/self/maps` mapping plus validated ELF64
`dynsym` values. After `PlayDone`, the resolver observed 149 assemblies and
resolved both target methods:

```text
TMPro.TMP_Text.set_text
UnityEngine.UI.Text.set_text
```

No method hook is installed in this stage. The resolver waits until after the
measured startup window before calling IL2CPP domain APIs, because AppSealing
maps the library before IL2CPP initializes its domain.

An apparent startup regression with AppSealing code `20033` was caused by a
mixed package view: the visible APK paths pointed to host versionCode 450 while
the Virtual PackageManager record still reported 448. Running the installed
game sync updated the package record and native libraries while preserving
container data. The next launch completed `PlayVideo -> PlayWarningAnim ->
PlayDone` and restored `SetLoginInfo : GOOGLE`. Treat this as an APK/package
consistency failure, not as a new AppSealing termination offset.

## Android 16 / ColorOS 16 兼容结论

2026-08-08 在 PHY110、Android 16（API 36）和 Limbus v1.109.1 上完成真机验证。启动已
走完 `PlayVideo -> PlayWarningAnim -> PlayDone` 并渲染到 `TRIGGER WARNING` 页面；主游戏
进程持续存活，未再出现此前的 `libc rmdir+0 SIGILL` 或 Java 深色模式空指针。

- `readlinkat` hook 必须保持 bionic 的 `ssize_t` 返回 ABI。错误的窄返回值会污染调用方寄存器，
  并在 libc realpath 路径中形成看似无关的 native 崩溃。
- `SIGSEGV` 包装器调用已登记的下游 handler 后必须立即返回。下游 handler 已通过修改上下文
  消费信号时，再恢复默认处理并重发信号会把 ART 的隐式空指针检查误判为进程崩溃。
- arm64 必须同时阻断 libc `syscall(__NR_exit)`、`syscall(__NR_exit_group)` 和扫描到的直接
  `exit`/`exit_group` 指令；否则 AppSealing 能绕过普通导出符号 hook 终止主线程。
- Android 16 arm64 bionic 的 `renameat` 导出只有 12 字节，紧邻的下一函数是 `rmdir`。旧 inline
  hook 需要覆盖 16 字节，会把 `rmdir` 首条 `BTI c` 改成无效 trampoline 指令。因此 API 36
  跳过 `renameat` inline hook，保留其余 IO 重定向；不要把该现象当作 AppSealing 新偏移。
- ColorOS 16 的 `OplusActivityManager.isAppInDarkModeDataEntity()` 没有检查厂商服务是否为空。
  VirtualApp 的 ActivityManager 代理无法创建该扩展服务时，应在 Limbus Activity `onCreate`
  前向厂商 Singleton 安装其 AIDL `Default` 无操作实现。触发条件使用 API、目标包名和厂商类
  是否存在，不依赖虚拟进程内可能被代理的 ROM 系统属性。

## 当前结论

- Redmi/Android 12 + Limbus v1.107.1 可在容器内启动到 `PlayVideo -> PlayWarningAnim -> PlayDone` 并进入完整标题页。
- `FakeAssetPackService` 生效,GMS broker 包名改写生效,前台保持 `ShadowActivity$P0`,进程 UID 仍为宿主。
- 跳过 libc `read` inline hook 后,已规避 `perfetto_hprof_` 线程在 bionic `read+8` 触发的 `SIGILL`。
- 黑屏根因是 arm64 `getcwd` hook 返回 raw syscall 字节数 `2`,导致 il2cpp 的 C++ 字符串构造路径执行 `strlen(0x2)`。恢复 `char *getcwd(...)` 的返回值语义后,Addressables 和 FMOD 正常初始化。

## 历史排查方向

1. 对比容器外 Google Play 原游戏运行日志。
   - 先确认同一设备、同一 VPN 下原游戏是否能从开屏进入登录/主页面。
   - 对比原游戏在 `PlayDone` 后的 Firebase Auth、GMS sign-in、Play Games、Remote Config、资源热更和网络请求日志。

2. 观察容器内 Google/商店链路。
   - 重点看 `com.android.vending`、`com.google.android.gms`、`GmsApiService`、`MeasurementBrokerService`、Play Games sign-in。
   - 若容器内只有 `SetLoginInfo : NONE` 后沉默,优先怀疑登录或服务发现链路被隔离/伪装破坏。

3. 检查渲染与视频层差异。
   - 当前 SurfaceFlinger 能看到 `SurfaceView[ShadowActivity$P0]` 和 Activity 主窗口,但截图仍全黑。
   - 需要对比容器外开屏视频结束后是否还会创建额外 Surface、切换 EGL/Vulkan 上下文或进入登录 UI 场景。

4. 检查 AppSealing 后续保护分支。
   - 已知 `libcovault-appsec.so +0x23088` 和 `+0x20c58` 会调用 `exit(0)`,并有后续 direct `exit_group`。
   - 当前策略是补掉后续 `exit_group` 并终止当前 native 调用线程;如果原游戏在同一时间点没有这些分支,需要继续定位触发条件。

## 建议采集

普通用户应先在汉化器“高级设置”中选择“导出调试日志”。ZIP 包包含本应用 UID 的近期
日志、容器/汉化索引状态和 Android 历史进程退出原因,适合排查启动失败、闪退和汉化未
生效。导出器会脱敏常见凭据,但不采集游戏资源、存档、PlayerPrefs 或汉化渠道配置。
若应用自身无权读取某个 logcat buffer,包内会保留采集失败说明,而不是申请全设备
`READ_LOGS` 权限。

新安装设备不应再出现 `microG APK 未配置`。生产包内置 microG Services 与
Companion/FakeStore,首次导入游戏时从 assets 校验并暂存。如果日志出现
`Bundled microG digest mismatch` 或无法识别内置包名,应视为 APK 构建/分发损坏;
不要让用户通过 adb 补 `microg_apk_paths`。旧设备日志中的 `microG already installed`
仍表示复用原有有效容器组件。

Android 13 新机即使已完成内置 microG 迁移,broker 请求仍可能携带真实游戏包名。若随后
出现 `Unknown calling package name 'com.ProjectMoon.LimbusCompany'`,先确认
`Wrap GMS service broker`、`Created explicit Limbus GMS broker proxy` 和
`Rewrite Limbus GMS GetServiceRequest` 三条日志。包装不得因为 microG 已安装在 VirtualApp
内而被短路;其后的 AppSealing `SIGILL`/`SIGSEGV` 可能只是未捕获 Java 异常触发的清理路径。
Limbus 的 service bind 必须在分支解析前统一包装 `IServiceConnection`;broker 回调除目标
组件外还须以 `IGmsServiceBroker` Binder descriptor 兜底识别,避免 MIUI 或厂商改写后的
Intent/Component 绕过。Android 14 起还必须覆盖 `bindServiceInstance` 并兼容 `long flags`,
不得按系统版本或 ROM 名称做特判。

2026-07-17 的 Redmi/Android 13/MIUI 诊断包进一步确认:游戏先在已知的
`libcovault-appsec.so+0x4e564` 触发 `SIGILL`,约 0.7 秒前日志虽已记录 50048 branch
patch 成功,但 AppSealing 初始化完成后恢复了原指令。Unity 的信号处理随后在 PlayCore
注册 asset-pack listener 阶段触发同步 `SIGSEGV`,PC 为 MIUI `libc.so+0x86a90`,`x0`
和 fault address 均为 null。旧实现的进程级一次性标记使 `makeApplication` 后的收尾扫描
无法重补。当前改为逐条比较目标机器码,仅当已确认的 70034/50040/50048 版本偏移被恢复
时精准重写。Android 12 回归进一步观察到保护库会在收尾扫描数秒后再次恢复
`+0x4e564`。尝试包装全局 `SIGILL` handler 会干扰 ART/Unity 自身的信号链,该方案已
撤销。当前在完整 syscall 扫描结束后启动轻量
指令维护器,只读取五个确认偏移;发现 50048 cleanup branch 被恢复后立即精准重补并停止。
真机维护日志表明 `+0x4e564` 会被改写为每次不同的瞬态机器码,而非首次扫描值。因此仅
该多次 backtrace 确认的 50048 站点允许把瞬态值改为固定 cleanup branch;其余四个偏移
仍要求与首次机器码完全一致。保护库会在首次重补后约百毫秒内再次恢复该站点,因此维护器
覆盖完整 30 秒启动窗口,而不是首次重补后立即停止。
Android 13 最新日志进一步证明轮询刚重补 3ms 后同一线程仍会在 `+0x4e564` 执行
`ILL_ILLOPC`,继而触发原 `libc+0x86a90` 空指针崩溃。单纯缩短轮询间隔无法消除保护库
改写与执行之间的竞争窗口。因此保留轮询重补作为主路径,同时独立维护 `SIGILL` 包装器:
仅当 `si_code=ILL_ILLOPC`、PC=`base+0x4e564` 且 LR=`base+0x4e550` 三项同时满足时,
才把 PC 改到已确认的 cleanup `base+0x4e998`;其余 `SIGILL` 完整转发给下游 handler 或
恢复默认终止。包装器通过原始 `sigaction` 安装并在 2ms 维护周期内重新包裹保护库或
Unity 后续设置的 handler,不扩大现有 libc `sigaction` inline hook。直接扩展该 inline
hook 的实验会改变其前导指令布局,并在 Android 12 的
`A64HookFunctionV -> __fix_instructions` 阶段崩溃,因此该接入方式已撤回。

Redmi/Android 12 回归中,包装器经历 Unity/AppSealing 多次替换 handler 后仍能持续维护,
并走完 `PlayVideo -> PlayWarningAnim -> PlayDone -> SetLoginInfo : GOOGLE`;未出现
`__fix_instructions`、`SIGILL` 或 `SIGSEGV` 回归。Android 12 的该启动分支没有实际命中
上述精确 PC/LR,因此 Android 13 的重定向效果仍须由发生过该竞争的设备日志最终确认。

同步 `SIGSEGV` 诊断必须保留原 handler/默认终止语义。信号处理器只使用预先缓存的已加载
ELF 模块范围和固定缓冲区,输出 `si_code`、tid、PC/LR/SP、x0--x7、fault address 以及
PC/LR 的模块名和相对偏移;不得在 signal handler 内读取 `/proc/self/maps`、分配内存或调用
`dladdr`。模块缓存可在 IO hook 初始化和脱离 linker 回调的 native syscall 扫描阶段刷新,
不得在 `onSoLoaded` 内调用 `dl_iterate_phdr`;缓存只保存库文件 basename。

```powershell
adb -s <device> logcat -c
adb -s <device> shell monkey -p com.ProjectMoon.LimbusCompany 1
Start-Sleep -Seconds 90
adb -s <device> logcat -d -v time > logs\limbus_outside_playstore.log
```

容器内对照:

```powershell
adb -s <device> logcat -c
adb -s <device> shell am start -n com.example.limbuszhcn/.MainActivity --ez launch_container_game true
Start-Sleep -Seconds 90
adb -s <device> logcat -d -v time > logs\limbus_container.log
```
