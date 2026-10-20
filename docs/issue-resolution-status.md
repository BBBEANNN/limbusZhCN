# GitHub issue 逐项处理记录

本轮以 `BBBEANNN/limbusZhCN` 的 `main` 提交
`997dde5e0a1fadec9f18ded852d7f285ea613b66` 为修复基线，核查日期为 2026-10-19。
原工作区是较早的无 Git 交接包，因此修复在独立检出的
`codex/fix-reported-issues` 分支中完成，避免把旧版代码误用于已发布的 v1.4。

## 验收边界

- 诊断附件可以证明特定故障发生过，离线回归可以验证代码行为；两者都不能代替原设备复测。
- 未复测的 issue 保持开放，不把候选修复标为已根治，不自动关闭 issue。
- 同步 SIGSEGV 仍须转交下游处理器，不增加按机型或低地址范围吞掉故障的规则。
- 不更改设备 VPN、代理、路由、游戏资源、账号数据或 PlayerPrefs。

## 启动结果的含义

`VActivityManager.startActivity()` 的返回值只代表启动请求是否被系统接受。
负数以及 `START_SWITCHES_CANCELED`、`START_ABORTED` 等正数拒绝码都必须报告失败；
成功返回也不能证明稍后加载的游戏 native 库没有崩溃。因此界面使用“已请求启动游戏”，
不在仅收到请求返回时宣称游戏已经启动完成。实际进入标题页、登录和设置的验收单独记录。

结果区间遵循 AOSP 的
[ActivityManager.isStartResultSuccessful](https://android.googlesource.com/platform/frameworks/base/+/506e732548abf5664abe77181f4f121f93081be6/core/java/android/app/ActivityManager.java)，
不引入反射隐藏 API 或固定等待秒数来推断游戏就绪。

## 逐项状态

| Issue | 诊断结论与本轮处理 | 验收状态 |
| --- | --- | --- |
| #1 | 最新附件确认 microG 登录页 Theme.AppCompat 崩溃；为两层精确登录页提前重建目标主题。 | 代码已修复，Google 最终登录及 Apple 回调待原机验证 |
| #2 | 选择性移植 Firebase SessionLifecycleService 的精确绑定限制，保留其它认证服务。 | 候选兼容修复，原附件不能证明完整因果链 |
| #3 | AppSealing 无效回调现场已确认；不采用 PR #11 的低地址范围信号绕过。 | 待按当前版本二进制继续定位 |
| #4 | Android 12+ Provider 把虚拟 UID 写入系统 AttributionSource，已改为宿主物理身份。 | 代码已修复，需 Android 16 登录复测 |
| #5 | 与 #9 同一调用位置；首次跳转内嵌指针被误当作 LDRQ 指令，符合别名重复 hook 的现场。 | 原地址复用跳板的修复，需原机启动复测 |
| #6 | 与 #4 相同的 Provider AttributionSource UID 不匹配。 | 与 #4 共同验证 |
| #7 | 附件有 Unity pause / detach 超时，缺少设置应用时的完整线程栈。 | 尚未根治，补充 ANR 诊断可读性 |
| #8 | 已进 IL2CPP；另有保护库 +0x4e564 的同步 SIGSEGV，不能沿用只限定于 SIGILL 的跳转。 | 信号链修正后仍需当前版本保护库现场 |
| #9 | 第二次 hook 把首次跳转内嵌指针当成 LDRSW 指令，错误读取地址与 tombstone 完全一致。 | 同时修复别名重复 hook 和有符号偏移，需原机启动复测 |
| #10 | 有 ART 挂起日志，同时保护库报 20058、p2 多次 signal 11；检测项和调用栈未明。 | 根因未定，不按未知检测码新增绕过 |
| #12 | PlayDone 后 Thread-5 的 libart 同步内存错误。 | 待更完整的对应版本 native 栈 |
| #13 | 原报告和评论附件仅保留 p0 的 signal 11 历史退出，缺少故障时段日志及 tombstone。 | 已核查，根因未定；不能按机型复用其它偏移 |
| #14 | AppSealing Thread-5 的已确认无效指针读取现场。 | 待按当前版本二进制定位，不吞同步 SIGSEGV |
| #15 | 实际到达过 PlayDone，同轮有 30010 及后续输入 ANR；0/0 只是没有已激活索引统计。 | 检测项和 ANR 根因未定，保留完整性阈值 |
| #16 | ART 正常 suspend poll 在容器信号日志中被高频记录。 | 信号日志/消费判断候选，需原机复测 |

详细证据见 [native 启动排查](issue-native-startup.md)、
[登录修复记录](runtime-debugging-notes.md)、[后期崩溃与设置卡死](issue-late-crashes.md)。

2026-10-19 用户说明当日没有可供复测的手机；当日没有连接或更改任何测试设备。
离线验证后保留测试包、提交和逐项结论，等待后续设备验收。

## 2026-10-20 设备验证进展

用户随后连接 OPPO PHY110 / Android 16，已验证容器启动至标题页、libc 别名复用、
两层 microG 登录页主题重建与部分 Provider 身份改写。补充同进程 LoginActivity 结果链
修复后，选择已有 Google 账号可返回游戏，用户确认已进入资源下载；新增账号提交链路
仍待单独复测。按用户要求等待资源下载完成后继续汉化和设置验证，详细边界见
[设备复测记录](device-verification-20261020.md)。
