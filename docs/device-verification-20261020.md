# 2026-10-20 OPPO / Android 16 设备复测

## 测试基线

- 设备：OPPO PHY110，Android 16 / API 36，4 KB 内存页。
- 游戏：Google Play 已安装的 Limbus Company 1.117.0，versionCode 483。
- 汉化器：代码提交 `fd227bcceb30608838ff20d2fdb2eee25ee75d90` 的 Debug 测试包。
- APK SHA-256：`e39268c76e049940699094cf8b2e4bb13cc501e6986f4498294c94a0de4c76f9`。
- 手机原先未安装该汉化器，本次为新安装，不代表已有大容量容器的覆盖升级回归。
- 只同步宿主正式安装源并启动容器；未修改 VPN、代理、路由、游戏 APK 或原游戏数据。

## 已观察到的结果

1. 测试包安装成功，容器导入并启动游戏，实际显示 Project Moon 视频和游戏标题页。
   系统前台为宿主 `ShadowActivity$P0`，游戏进程仍使用汉化器物理 UID，未回退原版启动。
2. 容器进程日志实际命中 `reuse libc alias open64 -> open`，同时命中 `fopen64/fopen`、
   `openat/openat64` 等复用分支。本次没有出现原报告的 `__fix_instructions` 启动崩溃。
   这只证明本设备本次启动通过，原 Issue #5/#9 设备和重复冷启动仍需分别验收。
3. 点击 Google 后，游戏的 Credentials `HiddenActivity` 经 client-local stub 启动，
   接着进入容器内 microG 登录进程，系统前台为 `ShadowActivity$P3`。请求码 1 的
   PendingIntent result bridge 已保存并恢复，OAuth 客户端包名恢复及 caller proxy 均命中。
4. 日志确认 `AssistedSignInActivity` 与 `AuthSignInActivity` 均在生命周期前重建目标主题，
   本次未出现旧报告的 `Theme.AppCompat` 异常。
5. microG 的 Provider `query/openTypedAssetFile` 实际改写为宿主物理 UID 和宿主包名。
   这些调用未触发 UID 不匹配崩溃，但尚不能代替原 Issue #4/#6 的 Provider `call`
   失败路径以及完整账号登录的验收。
6. 已显示 microG“正在建立到 Google 服务器的连接”页面。账号输入、Google 最终返回、
   Firebase/Unity 登录态和登录后资源准备需单独验证。
   点击 Google 时、账号操作前出现的 `ID token changed / Auth state changed` 只代表
   监听器通知，不能作为认证成功的依据。

## 登录提交后退桌面的新故障

用户确认在提交登录或验证成功之后退到桌面。随后检查发现游戏 p0 和 microG UI
进程仍存活，汉化器 UID 的 crash buffer 没有 Java fatal，系统历史退出只记录了两个
辅助进程的 cached/low-memory 清理。游戏任务仍保留五层 Activity，并处于后台，
因此现有证据不能把这次现象归类为游戏 native 崩溃。

源码与本轮调用记录共同指出一处返回链缺口：`AuthSignInActivity` 已使用 client-local
启动且没有 VAMS ActivityRecord，但其请求码 100 的
`org.microg.gms.auth.login.LoginActivity` 仍进入 VAMS。VAMS 找不到父 token 时将
`resultTo` 清空，并可能创建独立的新任务；这与登录页结束后回不到游戏的现象吻合。
修复限定为同包、同进程的这一精确登录组件，保留系统 token 与结果链，不泛化整个
login 包，也不以恢复前台任务掩盖丢失的结果。修复后的完整链路仍需实际复测。

## 返回链补丁后的复测

同日覆盖安装新增返回链补丁的测试包，保留现有容器和账号数据。新 APK SHA-256 为
`695ca3ea74eddc0e90dd5f1c41cef4df4d16ec82fa64dc9a01287e9841b35ba0`。
构建通过，JUnit 共 70 项：67 通过、3 项既有跳过、无失败或错误。

本轮从标题页再次进入 Google 账号选择，日志在 14:07:23 记录
`Relay Google Identity UI result resultCode=-1`，随后 14:07:24 出现
`SetLoginInfo : GOOGLE`。用户确认已经进入游戏资源下载流程。
因此本机已验证选择已有账号后的 Google 结果可返回游戏，而不只是打开选择器。
本轮使用上一轮留下的账号，没有再次打开 LoginActivity，不能据此声称新增账号的
`LoginActivity -> AuthSignInActivity` 请求码 100 链路已经实测通过。

用户要求先自行下载资源，下载完成后再继续。已停止手机操作和本轮实时日志采集，
未清理容器、未更改网络，也未在下载期间继续切换游戏页面。

## 资源下载后恢复测试

用户完成下载后恢复测试，容器原任务可进入游戏主界面。此时汉化器仍显示“未激活”，
点击普通“启动汉化版游戏”实际收到 GitHub release API HTTP 403；故障发生在获取
汉化发布元数据阶段，尚未编译或激活索引，不能据此判断日文资源不完整。

在尚未激活索引时，仅完成设置对照：技能工具提示开→关→开、渲染比例中→低→中，
每次确认后仍可打开设置，最终恢复原值。该结果只证明未启用汉化时本机此操作未卡死，
不能作为 Issue #7 的修复验收。用户指出此边界后，停止扩大未激活状态的测试，优先
处理 GitHub 403；激活汉化后必须重新执行相同操作，并单独检查字体和运行时索引。

## 尚未验收

- 新增 Google 账号时 LoginActivity 的提交/验证返回，以及原问题设备的登录回归。
- Apple ID 浏览器回调。
- 容器日文资源下载、完整汉化索引激活和中文显示；标题页出现日文不等于汉化失败，
  本轮启动验证尚未准备或激活汉化包。
- 进入主界面后修改游戏设置是否卡死。
- 重复冷启动、已经存在大量资源时的覆盖升级及其它报告机型。

当前不据此关闭任何 issue。日志仅采集本应用 UID 的有限条目，落盘前脱敏；
日志、截图、账号交互和设备连接信息保留在本机诊断目录，不加入 Git 提交。
