# Limbus Company 汉化器

这是一个面向 `com.ProjectMoon.LimbusCompany` 的 Android 汉化辅助项目。当前主线方案是把 Google Play 已安装的 Limbus Company 同步进 VirtualApp 容器，在容器内启动游戏，并把汉化资源写入容器可见的游戏数据目录。

项目目标不是修改游戏 APK，也不是注入游戏进程做热补丁；核心是维护一个尽量接近真实 Google Play 安装环境、同时对游戏隐藏宿主机工具和汉化器自身的容器运行时。

> 本项目为非官方社区项目，与 Project Moon 无隶属、授权或合作关系。仓库不包含
> Limbus Company 游戏 APK、付费内容、账号数据或运行时下载的游戏资源；使用者必须自行
> 通过合法渠道安装游戏，并自行承担第三方组件许可与所在地法律带来的责任。

## 当前状态

- 目标游戏包名：`com.ProjectMoon.LimbusCompany`
- 汉化器包名：`com.example.limbuszhcn`
- 最低系统版本：Android 12 / API 31
- 主模块：`:app`
- 容器模块：`:virtualapp`，源码位于 `third_party/virtualapp-upstream/lib`
- 已验证设备路径：Redmi / Android 12 + Google Play 版 Limbus `v1.107.1`
- 已验证能力：
  - 同步宿主 Google Play 安装的 `base.apk`、split APK 和 native library 到容器
  - 从 VirtualApp 容器启动游戏
  - 进入标题页
  - 使用容器内 microG 完成 Google 登录
  - 登录后进入资源下载流程

资源下载很大。进入资源下载阶段后，不要清空容器数据，也不要卸载容器内的游戏包，否则会触发重新下载。

## 仓库结构

```text
.
├── app/                                  # 汉化器 App，Compose UI 与容器编排
├── third_party/virtualapp-upstream/lib/  # VirtualApp library module
├── docs/                                 # 架构、容器、运行时排障记录
├── scripts/                              # 辅助脚本
├── AGENTS.md                             # 当前必须遵守的协作/架构约束
├── CHANGELOG.md                          # 变更记录
└── settings.gradle.kts
```

建议先读：

- `AGENTS.md`：当前硬约束和调试入口
- `docs/windows-codex-handoff.md`：无 Git 源码包在 Windows 上的依赖复原与机型适配流程
- `docs/project-architecture.md`：模块、数据流、补丁管线
- `docs/container-runtime-notes.md`：VirtualApp 接入和设备实测
- `docs/runtime-debugging-notes.md`：运行时排障记录
- `docs/commercial-container-reference.md`：商业容器参考结论

## 构建

Windows / PowerShell：

```powershell
cd D:\code\limbusZhCN
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS=''
.\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

注意：debug 构建当前设置为 `isDebuggable = false`，这是为了更贴近运行时验证环境。

APK 必须包含：

```text
lib/arm64-v8a/libv++_64.so
```

缺少该库时，VirtualApp native engine 会在虚拟进程启动阶段失败。

## 安装与启动

安装汉化器：

```powershell
adb -s <device> install -r .\app\build\outputs\apk\debug\app-debug.apk
```

仅启动容器内游戏：

```powershell
adb -s <device> shell am start -n com.example.limbuszhcn/.MainActivity --ez launch_container_game true
```

同步宿主已安装游戏后再启动：

```powershell
adb -s <device> shell am start -n com.example.limbuszhcn/.MainActivity --ez sync_installed_game true --ez launch_container_game true
```

设备地址经常变化，以当前测试时实际可用的 adb 地址为准。

## Google Play 游戏更新与容器数据

Limbus Company 通常会随 Google Play 周更。宿主机游戏更新后，应重新走“同步已安装游戏”路径，把新的 `base.apk`、split APK 和 native library 导入容器。

不要为了周更清空容器：

- 不要卸载 VirtualApp 内的 `com.ProjectMoon.LimbusCompany`
- 不要清理 VirtualApp 游戏数据目录
- 不要删除虚拟外部存储里的游戏资源

同步流程只应该更新 APK/split/native library 来源，并保留已经下载的 14G 级资源和登录相关状态。

## 汉化资源写入边界

汉化包仅从公开的 [LocalizeLimbusCompany GitHub Releases](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases)
获取。应用会复用已校验的本地缓存；高级设置只用于给分叉版本调整公开的 Releases 地址，
不支持中转服务或 Token。

容器可见的本地化根目录是：

```text
/sdcard/Android/data/com.ProjectMoon.LimbusCompany/files/Assets/Resources_moved/Localize/
```

游戏文本文件通常要在登录并进入主流程后才会下载。标题页或登录阶段目录为空是正常现象。

JSON 补丁只能结构化修改这些字段：

- `dataList[].content`
- `dataList[].dialog`
- `dataList[].teller`

不得修改 `id`、`personalityid`、`voicefile`、`usage` 等元数据字段。

UI 层不要直接访问真实 `Android/data`。安装、卸载、复原和写入都必须走 `GameStorage` 等容器边界抽象。

## 容器运行时关键原则

- 启动游戏必须走 `VirtualCore.getLaunchIntent(packageName, 0)` 和 `VActivityManager.startActivity(intent, 0)`。
- 不得回退到宿主 launcher 启动真实游戏进程。
- Limbus 可见的 `ApplicationInfo` / `LoadedApk` 路径必须与 Google Play 真实安装源和容器重定向一致。
- PackageManager 查询默认隔离宿主环境；宿主 Google 包只允许按白名单精确暴露。
- PlayCore asset pack 必须通过注入 `local_testing_dir` 使用 `FakeAssetPackService`。
- GMS broker 请求需要通过 `ServiceConnectionDelegate` 修正残留调用包名。
- Credentials `HiddenActivity` 的 GMS Parcelable / Binder extras 不得经过 VAMS server 解包，必须保留系统 token 和 Activity result 链路。
- Limbus native DNS 与 socket 连接必须绕过 VirtualApp 域名/IP 策略。
- 不要修改测试机 VPN、代理或路由。

更细的约束见 `AGENTS.md`。

## 登录调试观察点

常用过滤：

```powershell
adb -s <device> logcat -v threadtime | rg "LimbusZhCN|LimbusContainer|LimbusVA|Unity|AndroidRuntime|NativeEngine|firebase|LimbusSIG"
```

关键状态：

```powershell
adb -s <device> shell pidof com.example.limbuszhcn:p0
adb -s <device> shell dumpsys activity activities | rg "mResumedActivity|ShadowActivity|ShadowPendingActivity|ProjectMoon|limbuszhcn"
```

期望结果：

- `pidof com.example.limbuszhcn:p0` 返回虚拟游戏进程 pid
- 前台 Activity 是 `ShadowActivity$P0`
- 不应回退到真实游戏进程
- Google 登录成功后，日志中通常能看到 Firebase 用户态更新和 Unity `SetLoginInfo : GOOGLE`

## 安全与敏感配置

不要把这些内容写死进源码或文档：

- Token、签名口令和私钥
- 含凭据的私有下载地址
- Google 账号信息
- 临时调试账号
- 设备专属代理/VPN/路由配置

如果需要临时配置，请走本地配置、调试 Intent 或不入库的文件。

## 维护建议

方案级变更先更新 `AGENTS.md` 或对应 `docs/`，再改代码。这个项目的坑主要集中在容器边界和运行时行为，文档比普通项目更接近“安全绳”：少写一条，后面很容易重新踩一次。

## 许可证与第三方组件

项目作者有权许可的代码采用 [GNU GPL v3.0](LICENSE)。VirtualApp、microG、字体、
Android 派生代码和其他第三方材料不因此被重新许可，仍分别适用其原始条款。完整来源、
固定版本和许可证边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
