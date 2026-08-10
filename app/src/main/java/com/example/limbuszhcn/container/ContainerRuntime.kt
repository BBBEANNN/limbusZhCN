package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage

/**
 * 定义汉化器可使用的游戏容器能力，包括状态读取、游戏导入、存储访问与启动。
 */
interface ContainerRuntime {
    /**
     * 读取当前容器及已导入游戏的状态。
     *
     * @return 可供启动流程和界面展示使用的容器状态。
     */
    fun status(): ContainerStatus

    /**
     * 将指定的官方游戏 APK 集合导入容器，同时保留已有的游戏数据。
     *
     * @param request 本次导入所需的 APK 路径、版本与来源信息。
     * @return 实际导入结果。
     */
    fun importGame(request: GameImportRequest): GameImportResult

    /**
     * 获取容器内游戏资源的统一存储入口。
     *
     * @return 可读写汉化资源的游戏存储实现。
     */
    fun storage(): GameStorage

    /**
     * 通过容器启动已导入的游戏。
     */
    fun launchGame()
}

/**
 * 描述容器可用性、游戏导入版本以及运行时 APK 来源是否需要修复。
 *
 * @property available 容器引擎当前是否可用。
 * @property gameImported 是否已有可启动的游戏导入记录。
 * @property message 面向用户展示的状态说明。
 * @property importedSplits 已导入的 APK/split 数量。
 * @property importedVersionCode 已导入游戏的版本号。
 * @property importSource 游戏导入来源，例如 Google Play 已安装包。
 * @property requiresInstalledGameResync VirtualApp 是否仍引用旧的内部 APK，而未引用当前系统安装源。
 */
data class ContainerStatus(
    val available: Boolean,
    val gameImported: Boolean,
    val message: String,
    val importedSplits: Int = 0,
    val importedVersionCode: Long? = null,
    val importSource: String? = null,
    val requiresInstalledGameResync: Boolean = false
)

/**
 * 描述一次游戏导入请求。
 *
 * @property splitApkPaths 官方 base APK 与 split APK 的路径集合。
 * @property versionCode 待导入游戏的版本号。
 * @property source APK 来源标识。
 */
data class GameImportRequest(
    val splitApkPaths: List<String>,
    val versionCode: Long? = null,
    val source: String = "manual"
)

/**
 * 描述一次游戏导入的最终结果。
 *
 * @property importedSplits 已处理的 APK/split 数量。
 * @property packageName 实际导入的游戏包名。
 * @property versionCode 实际导入的游戏版本号。
 * @property source 实际使用的 APK 来源标识。
 */
data class GameImportResult(
    val importedSplits: Int,
    val packageName: String,
    val versionCode: Long? = null,
    val source: String = "manual"
)

/**
 * 表示容器引擎不可用、导入失败或启动条件不满足。
 *
 * @param message 面向用户和诊断日志的错误说明。
 */
class ContainerRuntimeUnavailableException(message: String) : IllegalStateException(message)
