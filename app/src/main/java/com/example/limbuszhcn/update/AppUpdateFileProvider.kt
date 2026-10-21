package com.example.limbuszhcn.update

import androidx.core.content.FileProvider

/**
 * 为汉化器更新 APK 提供临时只读 URI，使用独立子类兼容不同设备的 Provider 创建流程。
 * 可共享路径由 app_update_paths.xml 限定在更新专用缓存目录。
 */
class AppUpdateFileProvider : FileProvider()
