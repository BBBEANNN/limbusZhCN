# Issue #2 / #3 / #7 / #12 / #14 诊断核对

核对日期：2026-10-19。源码基线为 GitHub main `997dde5e0a1fadec9f18ded852d7f285ea613b66`。
结论来自各 Issue 的原始诊断 ZIP 和所有公开评论；附件仅作为数据读取，未执行其中内容。
下列问题均缺少修复后的对应设备回归，不能以本地编译通过替代真机结论。

## #2：realme / Android 14 启动黑屏

- 原始附件确认游戏 versionCode 461、容器 p0 创建成功、VirtualCore 初始化成功；
  `process-exits.txt` 为 `count=0`，没有 ANR 或 native tombstone。
- 维护者于 2026-09-01 提供 `issue-2-fix-20260901` 测试分支
  `7948c3dd07fa8d1b62870e8d0c8b66c1a39c20e7`，精确拒绝游戏自身的
  `com.google.firebase.sessions.SessionLifecycleService` 绑定。main 尚未包含该策略。
- 该统计服务会经 VirtualApp 创建宿主 ShadowService；若主线程仍在同步解压 native
  数据，服务创建消息可能等不到执行而超时。这是历史测试分支的候选解释，原始附件没有
  足以证明此因果关系的线程栈，不能把推测写成已证实根因。
- 本次选择性移植该非关键统计服务策略：当前虚拟包、组件包和完整 Service 类名必须全部
  精确匹配。返回绑定失败，不伪造连接成功；Firebase Auth、GMS broker 及其他服务仍按
  原有分支运行。策略在三个共用 bind 入口中生效，并以正反例验证边界。
- 回归：原设备覆盖安装后连续冷启动三次，观察是否越过 Logo / 警告页；若仍黑屏，
  保留系统无响应提示，再导出诊断包确认是否出现 `ANR` 或真正的 native 崩溃。

## #3：Redmi / Android 16 无效 AppSealing 回调

- Redmi K80 原始附件：游戏 v462，`Thread-7` 的 `PC == si_addr == x2 == 0x18`；
  LR 位于 `libcovault-appsec.so` 的文件映射 `+0x248e0`。栈参数为
  `x1 == sp + 0x558`、`x4 == sp + 0xc8`，进程历史退出为 `SIGNALED / status=11`。
- Redmi K70 Pro 补充附件：游戏 v478，相同线程类型、LR、栈参数和稳定寄存器特征下，
  两次现场分别是 `PC == si_addr == x2 == 0x1000` 与 `0`。
- 历史 `issue3` 分支把该 LR 换算为 ELF 装载基址 `+0xd18e0`；不能把文件映射偏移
  `+0x248e0` 直接加到 `g_limbus_appsealing_base`。PR #11 将低地址条件放宽至
  `<=0x1000`，但附件仅证实上述三个离散地址，不能据此允许所有低地址。
- main 不含历史 Issue #3 guard。当前协作约定要求同步内存错误交还原 handler，
  本次不移植按 SIGSEGV 隔离线程的做法。已核实的是故障位置与特征，尚没有版本二进制、
  调用者反汇编和有效回调来源，无法提交符合约定的根治补丁。
- 后续需取得发生故障的同版本保护库，在该调用位置检查回调对象的来源与失败处理，
  再确定是否存在可按版本与机器码严格校验的初始化兼容修复。

## #7：一加 13 / ColorOS 16 应用游戏设置后卡死

- 游戏 v468，汉化索引 `2026090501` 已激活，源文件为 `2047/2047`，不能归因于
  日文资源未下载或汉化未激活。
- 日志确认 `04:00:46.451` 出现 Unity pause 2000 ms 超时，`04:00:48.512`
  出现 detach primary window 2000 ms 超时，表明 Unity 未及时响应窗口生命周期。
  附件没有应用设置当时的 UnityMain 栈、ANR 栈或设置参数，无法区分锁等待、图形资源
  重建、语言切换和其他原因。
- 大量 `LimbusSIG` 位于 ART 的 `boot*.oat`，首条机器码为 `f94002b5`，随后线程仍
  继续运行；这些是 ART 信号链的隐式检查现场，不能将其全部当成致命 native 崩溃。
  更早的一次 `EXCESSIVE_RESOURCE_USAGE` 只证明有过 CPU 超额退出，不能直接等同
  当前设置卡死的根因。
- 本次仅改进历史退出诊断：ANR trace 按文本脱敏并使用 `.txt`，native tombstone
  保持二进制与 `.pb`；记录 trace 不可用、读取失败及超限原因。不会读取游戏配置、
  资源或进程内存，也不增加全设备日志权限。
- Android 官方 [ApplicationExitInfo 文档](https://developer.android.com/reference/android/app/ApplicationExitInfo#getTraceInputStream())
  明确说明：已恢复 ANR 的 trace 也可能随其他原因的退出保留，只有 native crash 的 trace
  使用 protobuf。因此不能只给 `REASON_ANR` 使用文本，也不能把 `SIGNALED` 一概写成 `.pb`。
- 此问题仍未根治。原设备需要重现“应用设置”，保留系统无响应提示产生的历史记录，
  再立即导出新诊断；仅切回汉化器时进程尚未退出，系统可能还没有 ANR trace。

## #12：OPPO Reno12 Pro / Android 15 警告页后崩溃

- 游戏 v478、汉化索引未激活，已进入 `PlayVideo -> PlayWarningAnim -> PlayDone`；
  因而不是已激活翻译条目或中文字体 hook 的输入导致该次退出。
- `01:52:16.650` 的最终故障为 `Thread-5`：PC 在 `libart.so+0x137c78`，LR 在
  `libart.so+0x2ac698`，fault address 为 `0x10`。随后可见被容器阻断的
  `exit_group(139)`，历史记录为 p0 `SIGNALED / status=11`。
- 附件没有 native tombstone、完整调用栈和该系统版本 ART 符号，不能仅因线程名相同
  就复用 #3 或 #14 的 AppSealing 偏移。此次仅记录精确现场，保持原信号终止语义。
- 后续需要新诊断中的 native tombstone 或同版本带符号调用栈，先确认传入 ART 的
  参数由哪个调用者构造，再确定 Java/JNI 兼容层是否需要修复。

## #14：vivo / Android 14 警告页后无效对象读取

- 游戏 v483、汉化索引未激活，已实际命中日语 getter，退出不由残缺汉化索引引起。
- `12:48:12.921` 最终故障为 AppSealing `Thread-5`：PC 文件映射 `+0x248d4`，
  LR 文件映射 `+0x248cc`，`x0=0x21800`，fault address `0x21830`。现场四条指令为
  `f9401802 / aa1303e0 / d63f0040 / aa0003f6`，即读取对象 `+0x30` 的回调字段后
  调用该回调。随后出现被容器阻断的 `exit_group(139)`，进程以 signal 11 退出。
- 该位置与旧版空对象回调分支相关，但本次对象是 `0x21800`，并不是 `0`。
  旧代码将文件映射偏移与 ELF 基址混用也应单独核对；没有版本二进制不能将所有低对象
  地址视为同一错误，更不能把任意同步 SIGSEGV 吞掉或改为线程退出。
- 当前没有安全的根治补丁。后续需对同版本库反汇编并沿对象来源确认失效原因，
  优先修复容器初始化或 JNI 交互，保持未知同步内存错误的原处理流程。

## 来源

- [Issue #2](https://github.com/BBBEANNN/limbusZhCN/issues/2)
- [Issue #3 与补充诊断](https://github.com/BBBEANNN/limbusZhCN/issues/3)
- [PR #11](https://github.com/BBBEANNN/limbusZhCN/pull/11)
- [Issue #7](https://github.com/BBBEANNN/limbusZhCN/issues/7)
- [Issue #12](https://github.com/BBBEANNN/limbusZhCN/issues/12)
- [Issue #14](https://github.com/BBBEANNN/limbusZhCN/issues/14)
