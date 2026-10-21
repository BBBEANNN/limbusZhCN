# 中文描边向内侵蚀字面的修复

2026-10-21 在 PHY110 当前打开的人格编队页确认：卡片底部名称被黑边覆盖，
黄色字面只剩细线。修复前截图保存在本机 `.diagnostics/device-font-outline-20261021/before.png`。

TMP SDF 描边以原轮廓为中心，向内、向外各扩展一半。此前只设置
`_OutlineWidth=0.12`，没有对应的 `_FaceDilate`，细笔画因而被侵蚀。

处理范围只限新建的中文材质：保留 `_OutlineWidth=0.12`、不透明黑边与
`OUTLINE_ON`，同时设置等宽 `_FaceDilate=0.12`。不复制日文材质的图集、
字重或尺度参数，也不更换字体。两项参数在 TMP shader 中具有相同的倍率，
等宽补偿可保留原字面边界，将新增黑边移到外侧。

依据：

- [Unity TMP SDF 材质说明](https://docs.unity.cn/Packages/com.unity.textmeshpro%404.0/manual/ShadersDistanceField.html)
- [Unity 官方示例的 TMP SDF shader](https://github.com/Unity-Technologies/com.unity.services.samples.game-lobby/blob/main/Assets/TextMesh%20Pro/Shaders/TMP_SDF-Mobile.shader)

验证要求：用实际生产参数代入 shader 的填充/外轮廓阈值，在不同图集倍率和渲染尺度下
验证字面保持、描边外移；设备独立回归运行后，重新构建并沿用原签名覆盖安装。
游戏内需复查人格名称、正文、浅色背景与切页复用后的表现，不能只以材质写入日志判定验收通过。

## 本次验证结果

- PHY110 上独立运行的 native 回归共 51 项通过，其中包含 18 项新增描边边界检查。
- Release 原生库编译成功，APK 签名与 16 KiB ZIP/ELF 对齐检查通过。
- 同一工作区存在并行的宿主自更新功能开发。本次交付以此前已安装的 v1.5 为基包，
  仅替换 `lib/arm64-v8a/libv++_64.so` 并重新对齐、签名；逐条核对其它载荷内容一致。
  重新签名产生的 `META-INF` 签名元数据更新属于预期变化，自更新改动未混入本次包。
- 已使用原签名覆盖安装，数据保留；用户在手机复验后明确确认“可以了”。
  本次以用户视觉验收为依据，未将尚未捕获的运行时材质回读日志算作已验证证据。
- 修复包：`out/release-v1.5-outlinefix-f094ed5/LimbusZhCN-v1.5-outlinefix.apk`。
  SHA-256：`26757af1082a93d557b303d0d5522faf3d6289c9590b67104568d81c514b045c`。
