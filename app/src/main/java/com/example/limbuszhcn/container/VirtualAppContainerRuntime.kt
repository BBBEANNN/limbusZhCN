package com.example.limbuszhcn.container

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * 使用 VirtualApp 承载官方 Limbus Company，并将容器能力接入汉化器业务层。
 *
 * @param context 宿主应用上下文。
 * @param fallback 保存导入元数据和本地工作区的基础实现。
 */
class VirtualAppContainerRuntime(
    private val context: Context,
    private val fallback: LocalContainerRuntime
) : ContainerRuntime {
    private val bridge = VirtualAppReflectionBridge(context)

    /**
     * 读取容器、游戏导入记录以及当前 APK 来源的综合状态。
     *
     * @return 供启动页决定是否需要重新同步的容器状态。
     */
    override fun status(): ContainerStatus {
        val local = fallback.status()
        return if (bridge.available) {
            // 版本号相同并不代表运行时来源一致。应用更新或旧安装可能让 VirtualApp
            // 继续引用私有 base-1.apk，AppSealing 随后会在 PlayDone 后阻断标题页。
            val requiresSourceResync = bridge.requiresInstalledSourceResync(LocalContainerRuntime.GAME_PACKAGE)
            local.copy(
                available = true,
                message = local.message + if (requiresSourceResync) {
                    "; VirtualApp APK 来源需要重新同步"
                } else {
                    "; VirtualApp 引擎已打包"
                },
                requiresInstalledGameResync = requiresSourceResync
            )
        } else {
            local.copy(
                available = false,
                message = local.message + "; VirtualApp 引擎不可用: ${bridge.unavailableReason}"
            )
        }
    }

    /**
     * 记录导入元数据，并把官方游戏及所需的原生库同步到 VirtualApp。
     *
     * @param request 官方 APK/split 路径、版本和来源信息。
     * @return 实际导入的包名、split 数量与版本信息。
     */
    override fun importGame(request: GameImportRequest): GameImportResult {
        val localResult = fallback.importGame(request)
        bridge.requireAvailable()
        val useInstalledSource = request.source == "installed"
        // Keep VirtualApp game data/storage intact: post-login resources are large
        // and must survive weekly host APK/split resyncs.
        if (request.splitApkPaths.size > 1 && !useInstalledSource) {
            throw ContainerRuntimeUnavailableException("VirtualApp 适配层已建立,但 split APK 安装尚未迁移;Limbus 不能只安装 base.apk")
        }
        val baseApk = request.splitApkPaths.first()
        val result = bridge.installBaseApk(baseApk, useInstalledSource)
        if (!result.success) {
            throw ContainerRuntimeUnavailableException("VirtualApp 安装失败: ${result.error ?: "unknown"}")
        }
        bridge.logGamePackageDiagnostics(LocalContainerRuntime.GAME_PACKAGE)
        val googlePackages = bridge.ensureGoogleAppsInstalled()
        Log.i(TAG, "importGame ensured Google packages=$googlePackages")
        val packageName = result.packageName ?: LocalContainerRuntime.GAME_PACKAGE
        val nativeLibs = bridge.installNativeLibraries(packageName, request.splitApkPaths)
        Log.i(TAG, "importGame installed native libs count=$nativeLibs package=$packageName")
        return localResult.copy(packageName = packageName)
    }

    /**
     * 获取指向 VirtualApp 游戏数据目录的汉化资源存储。
     *
     * @return 引擎可用时返回容器存储，否则返回基础实现以便显示诊断信息。
     */
    override fun storage() =
        if (bridge.available) {
            ContainerWorkspaceStorage(bridge.localizeRoot(LocalContainerRuntime.GAME_PACKAGE).toPath())
        } else {
            fallback.storage()
        }

    /**
     * 准备容器存储和 Google 服务后，通过 VirtualApp 入口启动游戏。
     */
    override fun launchGame() {
        Log.i(TAG, "launchGame requested pid=${Process.myPid()}")
        bridge.requireAvailable()
        // Redirects must not survive into the next resource-validation pass.
        TranslationRedirectGate(context).close()
        ensureGameInstalledInVirtualApp()
        val googlePackages = bridge.ensureGoogleAppsInstalled()
        Log.i(TAG, "launchGame ensured Google packages=$googlePackages")
        bridge.logGamePackageDiagnostics(LocalContainerRuntime.GAME_PACKAGE)
        bridge.ensureGameLocalizeRoot(LocalContainerRuntime.GAME_PACKAGE)
        bridge.logGameLocalizeDiagnostics(LocalContainerRuntime.GAME_PACKAGE)
        bridge.launch(LocalContainerRuntime.GAME_PACKAGE)
    }

    private fun ensureGameInstalledInVirtualApp() {
        if (bridge.isVirtualAppInstalled(LocalContainerRuntime.GAME_PACKAGE)) {
            return
        }
        // This is a package-cache repair only. Do not call uninstall or clear data
        // here; a valid container can hold many GB of downloaded game resources.
        val splitApkPaths = fallback.importedSplitApkPaths()
        if (splitApkPaths.isEmpty()) {
            throw ContainerRuntimeUnavailableException("VirtualApp 未安装游戏,且本地工作区没有可恢复的 APK")
        }
        val baseApk = splitApkPaths.first()
        Log.w(TAG, "VirtualApp game package missing; reinstalling from workspace base=$baseApk splits=${splitApkPaths.size}")
        val result = bridge.installBaseApk(baseApk, useSourceLocationApk = false)
        if (!result.success) {
            throw ContainerRuntimeUnavailableException("VirtualApp 恢复安装游戏失败: ${result.error ?: "unknown"}")
        }
        val nativeLibs = bridge.installNativeLibraries(LocalContainerRuntime.GAME_PACKAGE, splitApkPaths)
        Log.i(TAG, "VirtualApp restored game native libs count=$nativeLibs")
        bridge.logGamePackageDiagnostics(LocalContainerRuntime.GAME_PACKAGE)
    }

    companion object {
        private const val TAG = "LimbusContainer"

        /**
         * 判断当前 APK 是否已包含 VirtualApp 运行时。
         *
         * @return 能加载 VirtualCore 时返回 `true`。
         */
        fun isEnginePackaged(): Boolean =
            runCatching { Class.forName("com.lody.virtual.client.core.VirtualCore") }.isSuccess
    }
}

private class VirtualAppReflectionBridge(
    private val context: Context
) {
    val available: Boolean
    val unavailableReason: String?

    private val virtualCoreClass: Class<*>?

    init {
        val loaded = runCatching { Class.forName("com.lody.virtual.client.core.VirtualCore") }
        virtualCoreClass = loaded.getOrNull()
        available = loaded.isSuccess
        unavailableReason = loaded.exceptionOrNull()?.javaClass?.simpleName
    }

    fun requireAvailable() {
        if (!available || virtualCoreClass == null) {
            throw ContainerRuntimeUnavailableException("VirtualApp 引擎未打包: $unavailableReason")
        }
    }

    fun installBaseApk(apkPath: String, useSourceLocationApk: Boolean): VirtualInstallResult {
        requireAvailable()
        Log.i(TAG, "installBaseApk path=$apkPath useSourceLocationApk=$useSourceLocationApk pid=${Process.myPid()}")
        val core = core()
        val options = installOptions(useSourceLocationApk)
        val result = virtualCoreClass!!
            .getMethod("installPackageSync", String::class.java, options.javaClass)
            .invoke(core, apkPath, options)
        val success = result.javaClass.getField("isSuccess").getBoolean(result)
        val packageName = result.javaClass.getField("packageName").get(result) as? String
        val error = result.javaClass.getField("error").get(result) as? String
        Log.i(TAG, "installBaseApk result success=$success package=$packageName error=$error")
        return VirtualInstallResult(success = success, packageName = packageName, error = error)
    }

    fun launch(packageName: String) {
        requireAvailable()
        // 后台已有游戏任务时直接恢复；否则 VAMS 会尝试重复初始化
        // 同一容器进程，并在服务端曾被重启的情况下返回 -1。
        if (resumeExistingGameTask(packageName)) {
            return
        }
        val core = core()
        Log.i(TAG, "VirtualApp launch begin package=$packageName pid=${Process.myPid()}")
        val intent = virtualCoreClass!!
            .getMethod("getLaunchIntent", String::class.java, Int::class.javaPrimitiveType)
            .invoke(core, packageName, 0) as? android.content.Intent
            ?: throw ContainerRuntimeUnavailableException("VirtualApp 未找到启动入口: $packageName")
        Log.i(TAG, "VirtualApp launch intent=${intent.describeForLog()}")
        requireGameLaunchIntent(intent, packageName)
        startVirtualActivity(intent, packageName)
    }

    /**
     * 将已存在的游戏 ShadowActivity 任务恢复到前台。
     *
     * @param packageName 容器内的目标游戏包名。
     * @return 找到并成功恢复任务时返回 `true`。
     */
    private fun resumeExistingGameTask(packageName: String): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return runCatching {
            val existingTask = activityManager.appTasks.firstOrNull { appTask ->
                val taskInfo = appTask.taskInfo
                isVirtualGameTask(
                    packageName = packageName,
                    baseIntentType = taskInfo.baseIntent?.type,
                    baseActivityClassName = taskInfo.baseActivity?.className,
                    topActivityClassName = taskInfo.topActivity?.className
                )
            } ?: return false
            existingTask.moveToFront()
            Log.i(TAG, "Moved existing VirtualApp game task to foreground package=$packageName")
            true
        }.onFailure { error ->
            // 厂商 ROM 若限制任务查询，仍可继续使用标准 VAMS 启动路径。
            Log.w(TAG, "Unable to resume existing VirtualApp game task package=$packageName", error)
        }.getOrDefault(false)
    }

    fun ensureGoogleAppsInstalled(): List<String> {
        requireAvailable()
        val microgConfig = MicrogContainerConfig(context)
        val microgApks = microgConfig.microgApks()
        if (microgApks.isEmpty()) {
            throw ContainerRuntimeUnavailableException(
                "内置 microG 组件不可用，请重新安装完整的汉化器 APK"
            )
        }
        val expectedPackages = microgApks.map { apk ->
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
                ?: throw ContainerRuntimeUnavailableException("无法识别 microG APK: ${apk.absolutePath}")
        }
        if (microgConfig.migrationCompleted() && expectedPackages.all(::isVirtualAppInstalled)) {
            Log.i(TAG, "ensureGoogleAppsInstalled microG already installed=$expectedPackages")
            return expectedPackages
        }
        val staleGooglePackages = listOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.play.games"
        )
        if (!microgConfig.migrationCompleted()) {
            staleGooglePackages.filter { isVirtualAppInstalled(it) }.forEach { packageName ->
                if (uninstallVirtualPackage(packageName)) {
                    Log.i(TAG, "ensureGoogleAppsInstalled removed previous Google implementation=$packageName")
                }
            }
        }
        val installed = microgApks.zip(expectedPackages).map { (apk, expectedPackage) ->
            val result = installBaseApk(apk.absolutePath, useSourceLocationApk = false)
            if (!result.success || result.packageName != expectedPackage) {
                throw ContainerRuntimeUnavailableException(
                    "microG APK 安装失败: expected=$expectedPackage actual=${result.packageName} error=${result.error}"
                )
            }
            val nativeLibs = installNativeLibraries(expectedPackage, listOf(apk.absolutePath))
            Log.i(TAG, "Installed container microG package=$expectedPackage nativeLibs=$nativeLibs")
            expectedPackage
        }
        if ("com.google.android.gms" !in installed) {
            throw ContainerRuntimeUnavailableException(
                "配置的 APK 不包含 com.google.android.gms microG Services: $installed"
            )
        }
        microgConfig.markMigrationCompleted()
        Log.i(TAG, "ensureGoogleAppsInstalled microG installed=$installed")
        return installed
    }

    private fun uninstallVirtualPackage(packageName: String): Boolean {
        requireAvailable()
        return runCatching {
            virtualCoreClass!!
                .getMethod("uninstallPackage", String::class.java)
                .invoke(core(), packageName) as Boolean
        }.onFailure {
            Log.w(TAG, "uninstallVirtualPackage failed package=$packageName", it)
        }.getOrDefault(false)
    }

    fun localizeRoot(packageName: String): File {
        requireAvailable()
        val environment = Class.forName("com.lody.virtual.os.VEnvironment")
        val appData = environment
            .getMethod("getExternalStorageAppDataDir", Int::class.javaPrimitiveType, String::class.java)
            .invoke(null, 0, packageName) as File
        return appData.resolve("files/Assets/Resources_moved/Localize")
    }

    fun ensureGameLocalizeRoot(packageName: String) {
        val root = localizeRoot(packageName)
        if (root.isDirectory) {
            return
        }
        val created = root.mkdirs()
        Log.i(TAG, "ensureGameLocalizeRoot root=${root.absolutePath} created=$created exists=${root.isDirectory}")
    }

    fun logGamePackageDiagnostics(packageName: String) {
        requireAvailable()
        val installed = runCatching { isVirtualAppInstalled(packageName) }.getOrElse {
            Log.w(TAG, "diagnostics isVirtualAppInstalled failed package=$packageName", it)
            false
        }
        val component = android.content.ComponentName(packageName, "com.unity3d.player.UnityPlayerActivity")
        val activityInfo = runCatching {
            val pmClass = Class.forName("com.lody.virtual.client.ipc.VPackageManager")
            val pm = pmClass.getMethod("get").invoke(null)
            pmClass
                .getMethod(
                    "getActivityInfo",
                    android.content.ComponentName::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .invoke(pm, component, 0, 0)
        }.onFailure {
            Log.w(TAG, "diagnostics getActivityInfo failed component=$component", it)
        }.getOrNull()
        val launcherCount = runCatching {
            val pmClass = Class.forName("com.lody.virtual.client.ipc.VPackageManager")
            val pm = pmClass.getMethod("get").invoke(null)
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            @Suppress("UNCHECKED_CAST")
            val result = pmClass
                .getMethod(
                    "queryIntentActivities",
                    Intent::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .invoke(pm, intent, intent.resolveType(context), 0, 0) as? List<Any>
            result?.size ?: -1
        }.onFailure {
            Log.w(TAG, "diagnostics query launcher failed package=$packageName", it)
        }.getOrDefault(-1)
        Log.i(TAG, "diagnostics package=$packageName installed=$installed activityInfo=${activityInfo != null} launcherCount=$launcherCount")
    }

    fun logGameLocalizeDiagnostics(packageName: String) {
        val root = localizeRoot(packageName)
        val remoteList = root.resolve("RemoteLocalizeFileList.json")
        val localizeZip = root.resolve("localize_en.zip")
        val enDir = root.resolve("en")
        val tempDir = root.resolve("Temp/LocalizeTemp_en/LocalizeTemp_en")
        Log.i(
            TAG,
            "localize diagnostics root=${root.absolutePath} rootExists=${root.exists()} " +
                "remoteList=${remoteList.exists()}:${remoteList.length()} " +
                "localizeZip=${localizeZip.exists()}:${localizeZip.length()} " +
                "enDir=${enDir.exists()}:${enDir.listFiles()?.size ?: -1} " +
                "tempDir=${tempDir.exists()}:${tempDir.listFiles()?.size ?: -1}"
        )
    }

    fun installNativeLibraries(packageName: String, apkPaths: List<String>): Int {
        requireAvailable()
        val environment = Class.forName("com.lody.virtual.os.VEnvironment")
        val libDir = environment
            .getMethod("getAppLibDirectory", String::class.java)
            .invoke(null, packageName) as File
        if (!libDir.exists() && !libDir.mkdirs()) {
            throw ContainerRuntimeUnavailableException("无法创建容器 native lib 目录: ${libDir.absolutePath}")
        }
        libDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.forEach { file ->
                if (!file.delete()) {
                    Log.w(TAG, "installNativeLibraries failed to delete stale lib=${file.absolutePath}")
                }
            }

        var count = 0
        apkPaths.forEach { rawPath ->
            val apk = File(rawPath)
            if (!apk.isFile) {
                Log.w(TAG, "installNativeLibraries skip missing apk=$rawPath")
                return@forEach
            }
            ZipFile(apk).use { zip ->
                val nativeEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .toList()
                val selectedAbi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
                    nativeEntries.any { it.name.startsWith("lib/$abi/") }
                }
                if (nativeEntries.isNotEmpty() && selectedAbi == null) {
                    throw ContainerRuntimeUnavailableException(
                        "APK 不包含设备支持的 native ABI: apk=${apk.name} supported=${Build.SUPPORTED_ABIS.toList()}"
                    )
                }
                nativeEntries.asSequence()
                    .filter { selectedAbi == null || it.name.startsWith("lib/$selectedAbi/") }
                    .forEach { entry ->
                        val target = libDir.resolve(entry.name.substringAfterLast('/'))
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        target.setReadable(true, false)
                        target.setExecutable(true, false)
                        count += 1
                        Log.i(TAG, "installNativeLibraries copied ${entry.name} -> ${target.absolutePath}")
                    }
            }
        }
        return count
    }

    private fun core(): Any =
        requireNotNull(virtualCoreClass!!.getMethod("get").invoke(null)) { "VirtualCore.get() returned null" }

    fun isVirtualAppInstalled(packageName: String): Boolean {
        val core = core()
        return virtualCoreClass!!
            .getMethod("isAppInstalled", String::class.java)
            .invoke(core, packageName) as Boolean
    }

    /**
     * 判断 VirtualApp 的游戏记录是否仍引用旧的私有 APK，而不是当前系统安装源。
     *
     * 同版本更新不会触发旧的版本号比较，但 AppSealing 会同时核对 APK 路径与包信息，
     * 因此必须把来源路径作为独立的新鲜度条件。读取失败时保守地保持现状，避免仅因
     * 一次反射异常就重复复制体积较大的 split APK。
     */
    fun requiresInstalledSourceResync(packageName: String): Boolean {
        if (!isVirtualAppInstalled(packageName)) {
            return true
        }
        val installedSourceDir = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).sourceDir
        }.onFailure { error ->
            Log.w(TAG, "Unable to read installed game source package=$packageName", error)
        }.getOrNull() ?: return false
        val virtualSourceDir = runCatching {
            val pmClass = Class.forName("com.lody.virtual.client.ipc.VPackageManager")
            val pm = pmClass.getMethod("get").invoke(null)
            val appInfo = pmClass
                .getMethod(
                    "getApplicationInfo",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .invoke(pm, packageName, 0, 0) as? android.content.pm.ApplicationInfo
            appInfo?.sourceDir
        }.onFailure { error ->
            Log.w(TAG, "Unable to read VirtualApp game source package=$packageName", error)
        }.getOrNull() ?: return false
        val needsResync = installedSourceNeedsResync(installedSourceDir, virtualSourceDir)
        Log.i(
            TAG,
            "game source freshness package=$packageName installed=$installedSourceDir " +
                "virtual=$virtualSourceDir requiresResync=$needsResync"
        )
        return needsResync
    }

    private fun installedPackageApkPaths(packageName: String): List<String> =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            buildList {
                add(info.sourceDir)
                info.splitSourceDirs?.let(::addAll)
            }.filter { it.isNotBlank() }
        }.onFailure { error ->
            Log.w(TAG, "installedPackageApkPaths unavailable package=$packageName", error)
        }.getOrDefault(emptyList())

    private fun startVirtualActivity(intent: Intent, packageName: String) {
        Log.i(TAG, "VActivityManager.startActivity call package=$packageName intent=${intent.describeForLog()}")
        val managerClass = Class.forName("com.lody.virtual.client.ipc.VActivityManager")
        val manager = managerClass.getMethod("get").invoke(null)
        val result = managerClass
            .getMethod("startActivity", Intent::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, intent, 0) as Int
        Log.i(TAG, "VActivityManager.startActivity result=$result package=$packageName intent=${intent.describeForLog()}")
        if (result < 0) {
            throw ContainerRuntimeUnavailableException("VirtualApp 启动失败: result=$result, package=$packageName, intent=$intent")
        }
    }

    private fun requireGameLaunchIntent(intent: Intent, packageName: String) {
        val componentPackage = intent.component?.packageName
        val intentPackage = intent.`package`
        val resolvedPackage = componentPackage ?: intentPackage
        Log.i(TAG, "requireGameLaunchIntent expected=$packageName componentPackage=$componentPackage intentPackage=$intentPackage intent=${intent.describeForLog()}")
        if (resolvedPackage != packageName) {
            throw ContainerRuntimeUnavailableException(
                "拒绝启动非游戏入口: expected=$packageName, actual=${resolvedPackage ?: "null"}, intent=$intent"
            )
        }
    }

    private fun installOptions(useSourceLocationApk: Boolean): Any {
        val optionsClass = Class.forName("com.lody.virtual.remote.InstallOptions")
        val strategyClass = Class.forName("com.lody.virtual.remote.InstallOptions\$UpdateStrategy")
        val strategies = requireNotNull(strategyClass.enumConstants) { "Missing InstallOptions.UpdateStrategy enum constants" }
        val forceUpdate = strategies.first { (it as Enum<*>).name == "FORCE_UPDATE" }
        return requireNotNull(optionsClass
            .getMethod(
                "makeOptions",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                strategyClass
            )
            .invoke(null, useSourceLocationApk, false, forceUpdate)) { "InstallOptions.makeOptions() returned null" }
    }
}

private data class VirtualInstallResult(
    val success: Boolean,
    val packageName: String?,
    val error: String?
)

private const val TAG = "LimbusContainer"

/**
 * 比较系统安装源与 VirtualApp 记录的 base APK 路径。
 *
 * @param installedSourceDir Google Play 当前安装的 base APK 路径。
 * @param virtualSourceDir VirtualApp 包记录中的 base APK 路径。
 * @return 两者均可判断且路径不一致时返回 `true`。
 */
internal fun installedSourceNeedsResync(installedSourceDir: String?, virtualSourceDir: String?): Boolean =
    !installedSourceDir.isNullOrBlank() &&
        !virtualSourceDir.isNullOrBlank() &&
        File(installedSourceDir).absolutePath != File(virtualSourceDir).absolutePath

/**
 * 判断宿主任务是否是指定虚拟包的 VirtualApp 游戏任务。
 *
 * VirtualApp 把真实目标组件写入 ShadowActivity 基础 Intent 的 MIME type。同时校验
 * ShadowActivity 类名可避免把同宿主的启动器任务误认为游戏。
 *
 * @param packageName 目标虚拟包名。
 * @param baseIntentType 任务基础 Intent 的 MIME type。
 * @param baseActivityClassName 任务根 Activity 类名。
 * @param topActivityClassName 任务顶部 Activity 类名。
 * @return MIME type 指向目标包且任务由 ShadowActivity 承载时返回 `true`。
 */
internal fun isVirtualGameTask(
    packageName: String,
    baseIntentType: String?,
    baseActivityClassName: String?,
    topActivityClassName: String?
): Boolean {
    val targetsPackage = baseIntentType?.startsWith("$packageName/") == true
    val usesShadowActivity = sequenceOf(baseActivityClassName, topActivityClassName)
        .filterNotNull()
        .any { className -> className.startsWith("com.lody.virtual.client.stub.ShadowActivity") }
    return targetsPackage && usesShadowActivity
}

private fun Intent.describeForLog(): String =
    "action=$action component=$component package=$`package` flags=0x${flags.toString(16)} data=$data type=$type categories=$categories extras=${extras?.keySet()}"
