# Windows / Codex 调试交接手册

本文面向拿到无 Git 源码压缩包、需要在 Windows 上复原构建环境并适配另一台 Android
设备的开发者和 Codex。开始修改前必须先读仓库根目录的 `AGENTS.md`，再读
`docs/project-architecture.md`、`docs/container-runtime-notes.md` 和
`docs/runtime-debugging-notes.md`。这些文件记录了不能被普通“兼容修复”破坏的容器边界。

## 1. 交接包是什么

- 项目：Limbus Company 汉化器 / VirtualApp 容器运行时
- 游戏包名：`com.ProjectMoon.LimbusCompany`
- 汉化器包名：`com.example.limbuszhcn`
- 源码基线提交：`5008a5359bf75aa52157aa9caf0b419c66e42855`
- 交接包生成日期：2026-07-20
- 目标主机：Windows 10/11 x64
- 目标设备：Android 8.0（API 26）及以上、`arm64-v8a`；Android 12/13 为当前主要真机验证基线

交接包没有 `.git` 历史，但保留完整的 App 源码、VirtualApp 源码、Gradle Wrapper、测试、
文档和生产运行必需资产。内置 microG Services、Companion/FakeStore 和中文字体体积较大，
但属于运行必需文件，不能为减小压缩包而删除。

交接包明确不包含：

- `.git`、`.gradle`、`.idea`、`.kotlin` 和构建产物；
- `local.properties` 和开发机绝对路径；
- 签名密钥、密钥口令、Token 或含凭据的私有地址；
- 诊断包、原始 logcat、截图、反编译/逆向分析产物；
- Limbus 游戏 APK、split APK、游戏资源、PlayerPrefs 或账号数据。

游戏必须由测试者自己通过 Google Play 正常安装。交接包不授权分发游戏文件。

## 2. 固定构建依赖

| 组件 | 固定/建议版本 | 说明 |
| --- | --- | --- |
| Gradle Wrapper | `8.10.2` | 首次运行会从 `services.gradle.org` 下载 |
| Android Gradle Plugin | `8.8.0` | 由 `gradle/libs.versions.toml` 固定 |
| Kotlin | `2.0.0` | 含 Compose 插件 |
| Gradle JVM | Android Studio 内置 JBR | 最低使用 JDK 17；当前已验证 JBR 21.0.10 |
| Java/Kotlin 字节码目标 | Java 11 | 不是 Gradle 自身 JVM 版本 |
| Android SDK Platform | API 35 | `compileSdk=35`、`targetSdk=35` |
| Android SDK Build Tools | `35.0.0` | 当前复建基线 |
| Android Platform Tools | 当前稳定版 | 提供 `adb.exe` |
| Android NDK (Side by side) | **`30.0.14904198`** | r30 beta1，必须精确匹配 |
| Native 构建系统 | `ndk-build` | 入口为 `lib/src/main/jni/Android.mk`，不需要 CMake |
| 7-Zip | 当前稳定 x64 | 用于解压/重新制作 `.7z` 交接包 |
| Python | 3.10+，可选 | 仅运行 `scripts/localize_catalog.py` 时需要 |

App 模块最低 API 是 26，与当前 Limbus Company 本体一致。VirtualApp library 自身声明
最低 API 23，但 App 使用 `java.time`，因此最终 APK 以 API 26 为安全下限。Native 只构建
`arm64-v8a`；模拟器的 x86/x86_64 转译不属于生产基线。AGP 8.8 与 NDK r30 默认生成
16 KB 对齐产物，每次正式构建仍必须用 `zipalign -c -P 16` 与 `llvm-objdump -p` 复核。

Gradle 首次构建还需要访问 `google()`、Maven Central 和 Gradle Plugin Portal。主要依赖：

- AndroidX Core KTX 1.15.0、Lifecycle 2.8.7、Activity Compose 1.10.1；
- Compose BOM 2024.04.01、Material 3；
- Apache Commons Compress 1.28.0、XZ 1.10；
- JUnit 4.13.2；
- `play-services-tasks:18.2.0`：VirtualApp 为 `compileOnly`，测试模块为
  `testImplementation`，不得把第二份 GMS Task 打进 APK。

## 3. Windows 安装与 SDK 准备

1. 安装 Android Studio x64，并保留它自带的 `jbr`。
2. 在 SDK Manager 中启用 “Show Package Details”。
3. 安装 Android SDK Platform 35、Build Tools 35.0.0、Platform Tools。
4. 在 “NDK (Side by side)” 中安装 **30.0.14904198**。不要用 beta2
   `30.0.15729638` 或其它近似版本替代。
5. 安装 7-Zip x64，并确保 `7z.exe` 可用。

也可在安装了 Android SDK Command-line Tools 后执行：

```powershell
$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --channel=3 --install `
  "platforms;android-35" `
  "build-tools;35.0.0" `
  "platform-tools" `
  "ndk;30.0.14904198"
```

如果 SDK Manager 无法列出该 NDK，先更新 Command-line Tools，再使用 `--channel=3`。
不要修改 `third_party/virtualapp-upstream/lib/build.gradle` 来迁就本机已有的其它 NDK。

## 4. 解压和首次构建

建议解压到短、纯英文且可写的路径，例如 `D:\code\limbusZhCN`。不要把源码放在 OneDrive
同步目录；长路径、实时同步和杀毒软件可能干扰 Gradle/NDK 的中间文件。

在 PowerShell 中：

```powershell
cd D:\code\limbusZhCN

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\30.0.14904198"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
$env:JAVA_TOOL_OPTIONS = ''

.\gradlew.bat --version
.\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
```

若 Android Studio 不在默认目录，请把 `JAVA_HOME` 指向实际的 `Android Studio\jbr`。
Android Studio 可以自行生成不入包的 `local.properties`；命令行只设置上述环境变量即可。

构建产物：

```text
app\build\outputs\apk\debug\app-debug.apk
```

确认 NativeEngine 已打入 APK：

```powershell
& 'C:\Program Files\7-Zip\7z.exe' l `
  .\app\build\outputs\apk\debug\app-debug.apk |
  Select-String 'lib/arm64-v8a/libv\+\+_64.so'
```

Debug 构建当前故意设置为 `isDebuggable=false`，用于贴近 AppSealing 实际运行条件；不要只为
方便调试而改成 true，然后把由此产生的保护行为差异当作兼容结论。

## 5. 设备准备：不需要 Root

生产目标和所有正式修复必须适用于无 Root 手机。Root、Magisk、LSPosed、Frida、Shizuku
都不是运行依赖；ADB 只用于安装、启动和采集有限诊断。不要修改测试机的 VPN、代理或路由。

设备要求：

- Android 12 或更高，首选真实 arm64 手机；
- 已开启开发者选项和 USB/无线调试；
- 已从 Google Play 安装当前版本 Limbus Company；
- 手机有足够空间保存游戏资源和容器副本。

连接并记录基础信息：

```powershell
adb devices -l
$Device = '<adb-serial>'
adb -s $Device shell getprop ro.product.manufacturer
adb -s $Device shell getprop ro.product.model
adb -s $Device shell getprop ro.build.version.release
adb -s $Device shell getprop ro.build.version.sdk
adb -s $Device shell getprop ro.product.cpu.abilist
adb -s $Device shell dumpsys package com.ProjectMoon.LimbusCompany |
  Select-String 'versionName|versionCode|primaryCpuAbi'
```

## 6. 安装、同步和启动

```powershell
$Device = '<adb-serial>'
adb -s $Device install -r .\app\build\outputs\apk\debug\app-debug.apk

# 首次或宿主游戏更新后：同步 Google Play 安装并启动容器游戏
adb -s $Device shell am start -n com.example.limbuszhcn/.MainActivity `
  --ez sync_installed_game true --ez launch_container_game true

# 已同步且版本未变化：仅启动
adb -s $Device shell am start -n com.example.limbuszhcn/.MainActivity `
  --ez launch_container_game true
```

如果出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，说明设备上已有 APK 使用不同签名。不要在有
14 GB 级容器资源或登录状态的设备上直接卸载；应使用原签名密钥重新签名，或先与设备所有者
确认数据迁移方案。签名密钥必须通过安全渠道单独交接，不能加入源码压缩包。

## 7. 机型适配的最小诊断流程

先复现一次，再导出 App 内置“诊断包”。诊断包会限制大小并脱敏，优先分享它；不要直接分享
完整原始 logcat，因为第三方库可能把授权结果以字节数组形式写入日志。

本地观察可使用：

```powershell
adb -s $Device logcat -c
adb -s $Device shell am start -n com.example.limbuszhcn/.MainActivity `
  --ez launch_container_game true

adb -s $Device logcat -v threadtime |
  Select-String 'LimbusZhCN|LimbusContainer|LimbusVA|Unity|AndroidRuntime|NativeEngine|firebase|LimbusSIG'
```

容器身份检查：

```powershell
adb -s $Device shell pidof com.example.limbuszhcn:p0
adb -s $Device shell dumpsys activity activities |
  Select-String 'topResumedActivity|mResumedActivity|ShadowActivity|ProjectMoon|limbuszhcn'
```

期望 `p0` 存在，前台为汉化器宿主下的 `ShadowActivity$P0`，而不是系统直接启动的真实游戏
进程。常见阶段按顺序检查：

1. 导入的 `sourceDir`、split、native library 与宿主 Google Play 版本一致；
2. `PlayVideo -> PlayWarningAnim -> PlayDone` 是否完成；
3. Android 系统是否接受 `requestNetwork`，CDN DNS/TCP 是否发生；
4. microG broker、Credentials `HiddenActivity`、WebView isolated service 是否完成；
5. OAuth 业务包名是否保持 `com.ProjectMoon.LimbusCompany`；
6. Activity result 是否回到 Unity，最终是否出现 `SetLoginInfo : GOOGLE`；
7. 登录后才检查日文资源下载和运行时汉化索引。

每次兼容修改都应：

1. 先用日志证明具体系统 ABI/身份差异；
2. 把例外限制到精确系统版本、方法、包名、service 或 Activity；
3. 不改变游戏可见包名与跨系统 Binder 所需宿主身份之间的边界；
4. 增加 JVM 单测或可重复的真机日志断言；
5. 运行 `:app:testDebugUnitTest :app:assembleDebug`；
6. 在无 Root 真机复测启动、网络、登录和资源下载。

## 8. 当前兼容状态与待复测项

已在 Redmi Android 12/13 上验证过容器启动、标题页、Android 13 Connectivity/AppOps、
microG 登录 UI 和 OAuth 授权生成。源码基线还包含针对 Android 13 WebView
`bindServiceInstance`、StorageManager userId ABI、Firebase C++ Auth JNI ABI、MIUI 权限
控制器和后台前台服务限制的修复。

最后新增的 microG `Identity AuthorizationService` 精确 broker 身份例外已经通过单元测试和
APK 构建，但交接时仍应在目标机实际选择账号，确认授权结果最终回到 Unity 并出现
`SetLoginInfo : GOOGLE`。不要因为“能打开账号页”就宣称登录链路完成。

## 9. 绝对不能做的事

- 不修改或重打包游戏 APK；
- 不把 Root/代理/VPN/路由改动作为正式修复；
- 不清空已有容器数据来掩盖升级兼容问题；
- 不把宿主 PackageManager 全量暴露给游戏；
- 不泛化屏蔽 AppSealing 信号、maps 检查或未知 syscall；
- 不上传游戏资源、账号信息、PlayerPrefs、Token、签名私钥和原始登录 logcat；
- 不用其它 NDK 版本构建后假定 Native 行为等价。

其余架构硬约束以 `AGENTS.md` 为准。

## 10. 重新制作无 Git 交接包

Windows 上安装 7-Zip 后，从项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\package_windows_handoff.ps1
```

脚本会复制白名单源码结构、排除本机状态和敏感目录，生成 `.7z` 与 `.sha256`。生成后应在
一个全新临时目录解压，并至少重新运行一次单测和 APK 构建。
