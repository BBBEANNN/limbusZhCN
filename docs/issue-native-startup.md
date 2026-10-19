# 启动期 native Issue 离线排查

本轮依据 GitHub Issue #2、#5、#8、#9、#10、#13、#15、#16 的公开诊断附件排查。
附件保存在工作区 `.diagnostics/issues/`，作为不可信诊断数据读取，不执行其中内容。
没有连接报告设备；源码回归、指令生成测试或 APK 构建均不等于真机验证。

## ARM64 跳板

#5（OPPO CPH2699 / API 36）和 #9（OnePlus PYR110 / API 36）都出现
`memcpy -> __fix_instructions+1328 -> startIOHook+2840`，进程启动约 2 秒。
#9 的原指令地址为 `0x71448fb578`，旧代码算出的偏移为 `0xfff20d78`，
使复制源变为实际崩溃地址 `0x724481c2f0`。这应是负的 imm19 偏移，
旧表达式的无符号掩码使它向前错误偏移了 4 GiB。
#5 的偏移为正的 `0xda1d8`，访问被拒绝；不能把它同样归因为负数解码。

下载公开 v1.4 APK 后，`libv++_64.so` 的 Build ID 与 #9 完全一致：
`5a66705ab72b9a411b692ba76ea6128c1375c944`。反汇编确认 `startIOHook+2840`
正在 hook `open64`，前一个入口为 `open`。bionic 把二者导出到同址时，旧代码重复
搬运已经改写的绝对跳转，把其中 64 位 replacement 地址的低 32 位误当成机器码：

- #9 的 `x6=0x7098906bd4`：`0x98906bd4` 被当作 LDRSW，解码后的错误地址精确等于故障地址。
- #5 的 `x6=0x769c6d0ecc`：`0x9c6d0ecc` 被当作 SIMD LDR Q，偏移为 `0xda1d8`。

两份附件因此高置信指向同址导出被重复 inline hook。修复首先按真实入口地址登记
已安装 hook；相同 replacement 或明确同义 libc 别名复用第一次的 original trampoline。
未知的同址不同语义 replacement 必须明确拒绝，不能静默复用。orig 槽仍直接交给
原安装器，在改写入口前发布，防止并发 wrapper 读取空 original。
所有 imm14/imm19/imm21 同时按 ARM64 指令位宽进行符号扩展。
跳板缓冲区上限按字节检查，并包含最长指令展开和末尾回跳。
ADRP 始终使用指令规定的 4 KiB 页，不能混用内核的 16/64 KiB 保护页。

## ART 信号链

#8、#10、#15、#16 多次记录 `LimbusZhCN-Redi`、Binder、GC 等线程在 OAT 中
执行 `0xf94002b5` 时收到同步 SIGSEGV。AOSP 定义该指令为隐式挂起检查
`ldr x21, [x21]`，因此不能仅凭这条日志将它归类为游戏或重定向线程 native 崩溃。
参考：[AOSP ARM64 fault handler](https://android.googlesource.com/platform/art/+/master/runtime/arch/arm64/fault_handler_arm64.cc)。

下游信号处理器有权修改通用寄存器、PC、SP、PSTATE，甚至只修复内存而保持寄存器。
包装器先交下游处理；通用寄存器、PC、SP、PSTATE 有变化就返回，不能只比较 PC/SP。
不能泛化跳过暂停指令、推进 PC 或吞掉未知的同步内存错误。未消费的真实故障仍走原
handler 和默认终止语义。已由下游消费的正常 ART 信号不输出整段错误诊断。
这一比较无法证明“只修复内存但完全不改寄存器”的 handler 是否消费信号，因此仍不
声称完整实现所有厂商信号链；也不按暂停指令识别结果直接跳过未知同步故障。
诊断只用预先缓存的模块名，不在 signal 上下文读取 `/proc/self/maps` 或未知指令页。
缺少 OAT 缓存范围只表示未知，不能写成该地址未映射。

## 尚未证明的结论

- #2 只有容器初始化成功记录且退出列表为空。历史测试分支针对精确 Firebase
  `SessionLifecycleService` 的调整可复用，但 ANR 是候选原因，附件不能证实最终根因。
- #8 除 ART 日志外，还在 AppSealing 已确认的自修改代码站点收到同步 SIGSEGV；
  不能将已限定于 SIGILL 的 cleanup 跳转扩展为忽略任意 SIGSEGV。
  vivo V2408A / API 36 / v471 的 logcat 第 1825 行已通过 IL2CPP 边界；第 1925 行
  `pool-5-thread-1` 的 PC/LR 为已记录基址 `+0x4e564/+0x4e550`，但信号是
  `SEGV_MAPERR`，当时指令已被改写成 `0x2d30497d`。故障位置可信，新增跳过策略不成立。
- #10 ASUS_AI2205_C / API 35 / v475 的主附件反复记录保护库 `Kill Process [20058]`
  （如 logcat 第 844 行），且多个 p2 进程随后 `SIGNALED/status=11`。ART 暂停检查
  日志不应被当作 `Redi` 线程本身损坏的证据；20058 的检测含义与失败检查项尚未知。
  OPPO PGJM10 / Android 14 评论中的附件上传失败，不能把主附件结论推广到该评论设备。
- #13 原报告为 OnePlus PLC110 / API 36 / 游戏 v480。`process-exits.txt` 有 p0
  `SIGNALED/status=11`，最近一次为 2026-10-08T12:28:35Z；但附件 `logcat.txt`
  仅 138 行，已经是 20:41 导出时的宿主 UI 日志，未保留 20:28 的故障现场，也无 tombstone。
  评论附件来自 Redmi K70（23113RKC6C / API 36），p0 在 12:57:20、12:57:41、
  12:58:07、13:01:57、13:09:12Z 五次 `SIGNALED/status=11`；704 行 logcat 也是
  21:29 宿主日志，无 LimbusSIG 或调用栈。用户描述 AppSealing 不构成可核验偏移证据。
  两份附件只能证实同步 native 信号退出，根因未定，不能按机型并入 #5/#9 或新增绕过。
- #15 的 `translationSourceFiles=0/0` 表示尚无激活索引的源统计，不能证明下载包为空。
  原游戏下载完成不等于容器中日文资源已就绪；仍须遵守 90% 完整度门槛。
  该包实际已在 19:40:59 走到 `PlayDone`（logcat 第 3451 行），因此不是始终停在
  IL2CPP 之前。同一轮第 3435 行出现 `Kill Process [30010]`，其具体检测项未明。
  随后有 ART/libart 同步错误及输入 ANR；ANR 线程栈需保留原文本才能定位。
- #16 的 APP_SCOUT_HANG/黑屏和重复 ART 日志尚不能证明具体阻塞点；等待后续设备回归。
  原始 logcat 第 1080 行首次记录 `Redi` 的暂停指令，第 5827 行为 MIUI
  APP_SCOUT_HANG；没有 tombstone 或进程退出列表，不能认定与 #5/#9 同根因。

## 离线验证

`python scripts/test_native_startup.py` 会直接编译生产 `And64InlineHook.cpp` 的重定位
逻辑，检查正负 literal、向后 CBZ/TBZ、原页边界 ADRP #0、最高展开容量哨兵、已知
alias、original 在入口改写前发布、x21/LR/PSTATE 单独变化，以及 4/16/64 KiB 保护页边界。
宿主只检查生成的指令，绝不在 x64 上执行 ARM64 机器码。本轮宿主回归通过，NDK 30
对 Android 23 arm64 重定位源码的语法编译通过；设备启动、登录和游戏内回归仍待验证。
