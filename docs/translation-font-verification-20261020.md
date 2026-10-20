# 字体、文本对应与资源迁移验证

本次交付为汉化器 1.5（versionCode 6）测试 APK。设备为 OPPO PHY110 / Android 16，
游戏为 1.117.0（483）；核对使用设备导出的日文资源与公开汉化包 2026101602。

## 实现与证据

| 问题 | 处理 | 验证边界 |
| --- | --- | --- |
| 同组按钮字号不同 | 自动字号控件保存配置上限，移除按字符数缩字与 48 字单行判定 | 布局策略回归通过；新包游戏界面未验收 |
| 长文已有换行 | 保留换行与 `<br>`、原控件换行策略；显式 `line-height` 不重复补偿 | native 策略回归通过 |
| Buff「保存」变成「检查点」 | 下载词典中的同文译名参与完整索引与最长术语保护 | 编译器与实际 native trie 回归通过 |
| RPG/剧情漏接管 | 统一增加 11 个已审查显示字段；按表内稳定标识配对；重复 ID 数量相等时按本组次序对应 | 全量字段扫描与递归配对测试通过 |
| 亮字遇浅背景不清楚 | 中文 TMP SDF 使用 0.12 不透明黑边，启用 `OUTLINE_ON` | APK 已构建；Unity 材质与视觉效果尚需新包真机验收 |
| 纯标点破坏代码样式文本 | 标点映射仅保留整句匹配，禁止参与嵌入短术语 | 编译器回归通过 |
| 卸载/换机后复用资源 | 高级设置增加系统文件选择器迁出/迁入；流式 ZIP、SHA-256、保留已有文件、回滚与恢复 | 8 项迁移单元测试通过；SAF 与多 GB 实际资源迁移未验收 |

旧包与本机新证书不同，尚未覆盖或卸载手机上的旧安装。本次没有清除旧资源、修改
PlayerPrefs 或调整设备代理。新包不能反向给旧安装添加迁出功能。

## 全量文本对应结果

扫描 2,420 个设备日文 JSON、2,348 个汉化候选 JSON，成功配对 2,341 个文件，
无 JSON 解析错误。编译得到 181,414 条去重记录、110,159 条完整映射与 24,430 条短术语，
758 个原文仍有无法用现有优先级可靠消除的完整索引冲突。

逐字段报告覆盖 194,787 条记录及 86 条文件状态，报告未截断；每条保存资源路径、
原数组位置/标识、字段名和源/译文哈希。设备独立程序直接调用生产 native 查找逻辑，
未加载或修改游戏。合并时检查每一行源哈希与行数，结果如下：

| native 结果 | 条数 | 含义 |
| --- | ---: | --- |
| matches_pair | 184,167 | 最终输出等于该记录的汉化配对值，包含原译文相同和空文本 |
| unchanged | 9,008 | 最终原样保留，不能直接解释成日文漏译 |
| different_from_pair | 1,483 | 词典优先、上下文歧义或短术语处理使输出不同于该处配对值 |
| translated_without_pair | 129 | 此处没有可用配对，但其它已验证全文/术语产生输出，仍需语义复核 |

`needs-review.tsv` 共 3,490 行，是可逐条定位的语义复核清单，不能当成漏译数量：

| 原因 | 条数 |
| --- | ---: |
| 歧义原文保持原样 | 1,857 |
| 歧义原文经过其它完整匹配/术语处理 | 663 |
| 词典/多数译名优先于局部配对 | 799 |
| 原译文相同但经过其它术语处理 | 21 |
| 缺可用配对且保持原样 | 21 |
| 缺可用配对但产生 fallback 输出 | 129 |

最后 21 条未配对且原样保留的字段分布在：Hohenheim 战斗播报 1、Sinclair 语音 1、
`7D111I2/E001X/E715B/S706B/S736B` 剧情 10、StageNode 占位标题 2、UserBanner 2、
UserTicket 3、RPG `I990931` 物品 2。部分来自汉化包未提供对应文件，部分来自两侧
重复 ID 数量不一致；不能用另一个 ID 的相邻位置猜译。检查过这些字段的原文，既有
可见对白，也有开发占位文字。仍需后续汉化包补项或带场景的语义确认。

另有 7 个汉化候选缺设备源文件、79 个设备源文件未包含在汉化包中（其中 66 个没有
当前审查白名单的显示字段）。这与界面一定可见的漏译是不同状态。此次全量扫描未再
发现含假名的未审查显示字段；该扫描条件不等于证明所有游戏可见字符串都已接管。

## 可复现命令

使用 Android Studio JBR、Gradle 与仓库指定 NDK：

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
$env:ANDROID_NDK_HOME='<SDK>/ndk/30.0.14904198'
python scripts/test_native_translation.py --device <device> --adb <adb-path>
```

离线审计直接调用已构建的 Kotlin 生产类，classpath 还需 Kotlin stdlib 和真实
`org.json`（测试依赖）；在 Gradle 缓存中取其 JAR。`source.zip` 可用旧版 debug 导出的
Localize 快照（只取 `jp/`），或公开 JP 源文件归档。不要将资源快照放进用户诊断包。

```powershell
$auditClasspath='app/build/tmp/kotlin-classes/debug;<kotlin-stdlib.jar>;<org.json.jar>'
java -Xmx2g --class-path $auditClasspath scripts/AuditTranslations.java <source.zip> <translation.zip> <audit-dir> <package-version>
$auditIndex=(Get-Content <audit-dir>/native-index.path -Raw).Trim()
python scripts/test_native_translation.py --device <device> --adb <adb-path> --audit-input <audit-dir>/runtime-probes.bin --index $auditIndex
python scripts/join_translation_audit.py <audit-dir> .diagnostics/native-translation-tests/runtime-audit.tsv
```

`runtime-probes.bin` 与临时 index 含游戏显示文本，仅用于本机受控调试，不提交、不作为
普通诊断报告上传。`coverage-complete.tsv`、`needs-review.tsv` 与汇总只有定位信息/哈希。
审计结果取决于游戏与汉化包版本；截断、无源文件与歧义都要独立处理。

此次 Gradle 结果：101 项测试中 98 项通过、3 项跳过、0 失败；构建成功。
设备 native 回归 33 项通过，全量查找核对完成。APK 签名校验与 16 KiB ZIP 对齐检查
通过，包含 `lib/arm64-v8a/libv++_64.so`。APK SHA-256：
`dd39dee841087cb299c80f19e696e15abfdfed0110b76d764b8234a2bdc4b5f2`。
这些结果不代替新包的游戏内排版、描边、SAF、跨文件系统多 GB 迁移实测。

## 资源迁移用法

1. 在支持迁移的新版中打开「高级设置 → 资源迁移 → 迁出资源」。迁移会关闭容器游戏。
2. 将 ZIP 保存到下载目录或其它卸载后仍保留的位置，等待显示迁出完成。
3. 重新安装或换机后先安装 Google Play 原版游戏，再打开汉化器选择「迁入资源」。
4. 选择迁移 ZIP。汉化器先同步本机游戏 APK、暂存并逐文件校验，再补入不存在的资源。
5. 迁入需要额外容纳整份资源的可用空间。已有文件全部保留；游戏版本改变时可能补下载。
6. 再启动游戏并登录。迁移只保存资源，不保存账号、登录态、PlayerPrefs 或游戏 APK。

导出/导入支持进度与取消。失败或取消回滚本次新增文件；进程中断时，下次启动游戏前
恢复未完成的导入。不同签名不能直接覆盖旧包，本次旧包也不具备完整资源迁出能力，
因此上述流程解决的是以后支持此功能的版本之间迁移，不承诺救回本次旧包全部下载。
