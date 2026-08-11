package com.example.limbuszhcn

import android.os.Bundle
import android.os.Process
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.container.AndroidContainerRuntimeFactory
import com.example.limbuszhcn.container.AndroidInstalledGameImporter
import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerRuntime
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.container.GameImportRequest
import com.example.limbuszhcn.container.LocalizeSnapshotExporter
import com.example.limbuszhcn.container.MicrogContainerConfig
import com.example.limbuszhcn.container.TranslationRedirectGate
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import java.io.File

/**
 * 汉化器主界面，负责准备容器、从 GitHub Releases 更新汉化并启动容器内游戏。
 */
class MainActivity : ComponentActivity() {
    private lateinit var containerRuntime: ContainerRuntime

    /**
     * 创建主界面并初始化容器运行时，同时迁移旧版本遗留的中转服务配置。
     *
     * @param savedInstanceState Android 保存的 Activity 状态；首次创建时为 `null`。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistentDiagnosticLog.info(
            TAG,
            "MainActivity.onCreate pid=${Process.myPid()} taskId=$taskId " +
                "intent=${intent.describeForLog()} stack=${shortStack()}"
        )
        enableEdgeToEdge()
        val preferences = getSharedPreferences("limbus_zhcn", MODE_PRIVATE)
        // 旧版本可能在 SharedPreferences 中保存过中转地址和 Token。新版本不再读取这些值，
        // 并在启动时主动删除，避免已经停用的凭据继续留在设备备份或诊断环境中。
        preferences.edit()
            .remove(LEGACY_KEY_RELAY_BASE_URL)
            .remove(LEGACY_KEY_TOKEN)
            .remove(LEGACY_KEY_TRANSLATION_CHANNEL)
            .apply()
        containerRuntime = AndroidContainerRuntimeFactory.create(
            context = applicationContext,
            workspaceRoot = filesDir.toPath().resolve("container-workspace")
        )
        val deviceCompatibility = DeviceCompatibility(applicationContext)
        val compatibilityReport = deviceCompatibility.report()
        PersistentDiagnosticLog.info(
            TAG,
            "compatibility device=${compatibilityReport.deviceLabel} " +
                "android=${compatibilityReport.androidLabel} abis=${compatibilityReport.abiLabel} " +
                "pageSize=${compatibilityReport.pageSizeBytes} supported=${compatibilityReport.isSupported} " +
                "warnings=${compatibilityReport.warnings.joinToString("|")}"
        )
        launchContainerGameFromDebugIntent(intent)
        setContent {
            fun readContainerStatus(): ContainerStatus {
                val current = containerRuntime.status()
                val installedVersion = runCatching {
                    AndroidInstalledGameImporter.installedVersionCode(packageManager)
                }.getOrNull()
                return current.withInstalledVersion(installedVersion)
            }

            var containerStatus by remember { mutableStateOf(readContainerStatus()) }
            var githubReleaseUrl by remember {
                mutableStateOf(preferences.nonBlankString(KEY_GITHUB_RELEASE_URL, DEFAULT_GITHUB_RELEASE_URL))
            }
            var busy by remember { mutableStateOf(false) }
            var busyMessage by remember { mutableStateOf("处理中") }
            var message by remember { mutableStateOf("准备就绪") }
            var lastInstall by remember {
                mutableStateOf(
                    runCatching {
                        ContainerPatchInstaller(
                            cacheRoot = filesDir.toPath().resolve("translation-cache"),
                            storage = containerRuntime.storage()
                        ).activeInstallSummary()
                    }.getOrNull()
                )
            }
            var pendingDiagnosticArchive by remember { mutableStateOf<File?>(null) }
            val createDiagnosticDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip")
            ) { destination ->
                val archive = pendingDiagnosticArchive
                if (destination == null || archive == null) {
                    pendingDiagnosticArchive = null
                    archive?.delete()
                    message = "已取消导出 Issue 诊断包"
                } else {
                    busy = true
                    busyMessage = "正在保存 Issue 诊断包"
                    Thread {
                        val result = runCatching {
                            contentResolver.openOutputStream(destination, "w").use { output ->
                                requireNotNull(output) { "无法打开所选保存位置" }
                                archive.inputStream().use { input -> input.copyTo(output) }
                            }
                        }
                        archive.delete()
                        runOnUiThread {
                            pendingDiagnosticArchive = null
                            busy = false
                            message = result.fold(
                                onSuccess = { "Issue 诊断包已导出，请将 ZIP 文件附加到 Issue" },
                                onFailure = { error -> error.userMessage() }
                            )
                        }
                    }.start()
                }
            }

            fun runTask(progressMessage: String = "处理中", task: () -> String) {
                busy = true
                busyMessage = progressMessage
                message = progressMessage
                PersistentDiagnosticLog.info(TAG, "Task started stage=$progressMessage")
                Thread {
                    val result = runCatching(task).fold(
                        onSuccess = { resultMessage ->
                            PersistentDiagnosticLog.info(
                                TAG,
                                "Task completed stage=$progressMessage result=$resultMessage"
                            )
                            resultMessage
                        },
                        onFailure = { error ->
                            PersistentDiagnosticLog.error(TAG, "Task failed stage=$progressMessage", error)
                            error.userMessage()
                        }
                    )
                    runOnUiThread {
                        busy = false
                        message = result
                    }
                }.start()
            }

            fun prepareGame(forceSync: Boolean = false): String {
                val current = readContainerStatus()
                val installedVersion = runCatching {
                    AndroidInstalledGameImporter.installedVersionCode(packageManager)
                }.getOrElse { error ->
                    if (error is android.content.pm.PackageManager.NameNotFoundException) {
                        error("未检测到已安装的 Limbus Company,请先从 Google Play 安装或更新游戏")
                    }
                    throw error
                }
                val shouldSync = forceSync ||
                    !current.gameImported ||
                    current.importedVersionCode == null ||
                    current.importedVersionCode != installedVersion ||
                    current.requiresInstalledGameResync

                if (!shouldSync) {
                    runOnUiThread { containerStatus = readContainerStatus() }
                    return "游戏版本已匹配 v$installedVersion"
                }

                val reason = when {
                    !current.gameImported -> "首次导入游戏"
                    current.importedVersionCode == null -> "容器版本未知"
                    current.importedVersionCode != installedVersion ->
                        "宿主版本 v$installedVersion, 容器版本 v${current.importedVersionCode}"
                    current.requiresInstalledGameResync ->
                        "游戏版本相同，但 VirtualApp APK 来源不是当前 Google Play 安装源"
                    else -> "手动重新同步"
                }
                Log.i(TAG, "prepareAndLaunchGame syncing installed game reason=$reason")
                val installed = AndroidInstalledGameImporter(
                    packageManager = packageManager,
                    importDirectory = File(filesDir, "container-imports")
                ).copyInstalledGame()
                val result = containerRuntime.importGame(
                    GameImportRequest(
                        splitApkPaths = installed.splitApkPaths,
                        versionCode = installed.versionCode,
                        source = "installed"
                    )
                )
                runOnUiThread {
                    containerStatus = readContainerStatus()
                }
                return "已同步游戏 v${installed.versionCode}, ${result.importedSplits} 个 split"
            }

            fun refreshContainerStatus() {
                containerStatus = readContainerStatus()
                message = containerStatus.message
            }

            fun saveUpdateConfig() {
                preferences.edit()
                    .putString(KEY_GITHUB_RELEASE_URL, githubReleaseUrl.trim())
                    .apply()
                message = "配置已保存"
            }

            fun installSelectedTranslation(): PatchInstallSummary {
                val installer = ContainerPatchInstaller(
                    cacheRoot = filesDir.toPath().resolve("translation-cache"),
                    storage = containerRuntime.storage()
                )
                val releaseUrl = githubReleaseUrl.trim()
                require(releaseUrl.isNotBlank()) { "需要填写 GitHub Releases 地址" }
                return installer.installFromGitHub(releaseUrl)
            }

            fun installResultMessage(summary: PatchInstallSummary): String = when {
                summary.indexActivated ->
                    "汉化 ${summary.version} 已就绪，日文资源 ${summary.availableSourceFiles}/${summary.candidateSourceFiles}"
                summary.previousIndexPreserved ->
                    "新汉化包 ${summary.version} 已缓存；游戏资源尚未完整，继续使用现有汉化"
                else ->
                    "汉化包 ${summary.version} 已缓存；请进入游戏完成资源下载，之后返回汉化器再次启动"
            }

            LimbusZhCNTheme {
                LimbusApp(
                    containerStatus = containerStatus,
                    lastInstall = lastInstall,
                    compatibilityReport = compatibilityReport,
                    githubReleaseUrl = githubReleaseUrl,
                    busy = busy,
                    busyMessage = busyMessage,
                    message = message,
                    onGithubReleaseUrlChange = { githubReleaseUrl = it },
                    onSaveConfig = ::saveUpdateConfig,
                    onContainerRefresh = ::refreshContainerStatus,
                    onOpenCompatibilitySettings = {
                        runCatching {
                            startActivity(deviceCompatibility.settingsIntent())
                            PersistentDiagnosticLog.info(TAG, "Opened device compatibility settings")
                            message = "请允许汉化器自启动、后台运行和后台高耗电"
                        }.onFailure { error ->
                            PersistentDiagnosticLog.error(TAG, "Unable to open compatibility settings", error)
                            message = error.userMessage()
                        }
                    },
                    onSyncInstalledGame = {
                        runTask("正在重新同步游戏并启动") {
                            val prepared = prepareGame(forceSync = true)
                            containerRuntime.launchGame()
                            "$prepared，已启动游戏"
                        }
                    },
                    onLaunchTranslatedGame = {
                        runTask("正在检查游戏与汉化更新") {
                            val prepared = prepareGame()
                            val installer = ContainerPatchInstaller(
                                cacheRoot = filesDir.toPath().resolve("translation-cache"),
                                storage = containerRuntime.storage()
                            )
                            val previous = installer.activeInstallSummary()
                            val update = runCatching(::installSelectedTranslation)
                            val summary = update.getOrElse { error ->
                                if (previous == null) throw error
                                PersistentDiagnosticLog.warn(
                                    TAG,
                                    "Translation update check failed; launching active version",
                                    error
                                )
                                previous
                            }
                            val active = installer.activeInstallSummary()
                            runOnUiThread { lastInstall = active }
                            containerRuntime.launchGame()
                            if (update.isFailure && previous != null) {
                                "$prepared；联网检查失败，已使用汉化 ${previous.version} 启动"
                            } else {
                                "$prepared；${installResultMessage(summary)}；已启动游戏"
                            }
                        }
                    },
                    onUpdateTranslation = {
                        runTask("正在检查汉化更新") {
                            require(readContainerStatus().gameImported) { "请先启动一次游戏以准备容器" }
                            val summary = installSelectedTranslation()
                            val active = ContainerPatchInstaller(
                                cacheRoot = filesDir.toPath().resolve("translation-cache"),
                                storage = containerRuntime.storage()
                            ).activeInstallSummary()
                            runOnUiThread { lastInstall = active }
                            installResultMessage(summary)
                        }
                    },
                    onRestoreTranslation = {
                        runTask {
                            require(containerStatus.gameImported) { "请先同步或导入游戏到容器" }
                            val summary = ContainerPatchInstaller(
                                cacheRoot = filesDir.toPath().resolve("translation-cache"),
                                storage = containerRuntime.storage()
                            ).uninstallLatest()
                            runOnUiThread {
                                lastInstall = null
                            }
                            "已停用汉化 ${summary.version}"
                        }
                    },
                    onExportDebugLogs = {
                        busy = true
                        busyMessage = "正在生成 Issue 诊断包"
                        message = busyMessage
                        PersistentDiagnosticLog.info(TAG, "Issue diagnostics export started")
                        Thread {
                            val result = runCatching {
                                DebugLogExporter(applicationContext).export(
                                    containerStatus = readContainerStatus(),
                                    activeTranslation = lastInstall
                                )
                            }
                            runOnUiThread {
                                busy = false
                                result.fold(
                                    onSuccess = { archive ->
                                        pendingDiagnosticArchive = archive
                                        PersistentDiagnosticLog.info(
                                            TAG,
                                            "Issue diagnostics export completed bytes=${archive.length()}"
                                        )
                                        message = "Issue 诊断包已生成，请选择保存位置"
                                        createDiagnosticDocument.launch(archive.name)
                                    },
                                    onFailure = { error ->
                                        PersistentDiagnosticLog.error(TAG, "Issue diagnostics export failed", error)
                                        message = error.userMessage()
                                    }
                                )
                            }
                        }.start()
                    }
                )
            }
        }
    }

    /**
     * 处理复用现有 Activity 时收到的调试 Intent，并转交给受限的容器调试入口。
     *
     * @param intent Android 传入的新 Intent。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "MainActivity.onNewIntent pid=${Process.myPid()} taskId=$taskId intent=${intent.describeForLog()} stack=${shortStack()}")
        launchContainerGameFromDebugIntent(intent)
    }

    /**
     * 销毁主界面并记录任务状态，便于定位宿主与虚拟游戏之间的生命周期切换。
     */
    override fun onDestroy() {
        Log.i(TAG, "MainActivity.onDestroy pid=${Process.myPid()} taskId=$taskId finishing=$isFinishing changingConfigurations=$isChangingConfigurations")
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LAUNCH_CONTAINER_GAME = "launch_container_game"
        private const val EXTRA_SYNC_INSTALLED_GAME = "sync_installed_game"
        private const val EXTRA_EXPORT_LOCALIZE_SNAPSHOT = "export_localize_snapshot"
        private const val EXTRA_REFRESH_TRANSLATION_REDIRECTS = "refresh_translation_redirects"
        private const val EXTRA_ENABLE_TRANSLATION_REDIRECTS = "enable_translation_redirects"
        private const val EXTRA_MICROG_APK_PATHS = "microg_apk_paths"
        private const val EXTRA_MICROG_APK_URLS = "microg_apk_urls"
        private const val KEY_GITHUB_RELEASE_URL = "github_release_url"
        private const val LEGACY_KEY_RELAY_BASE_URL = "relay_base_url"
        private const val LEGACY_KEY_TOKEN = "token"
        private const val LEGACY_KEY_TRANSLATION_CHANNEL = "translation_channel"
        private const val DEFAULT_GITHUB_RELEASE_URL = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases"
        private const val TAG = "LimbusZhCN"
    }

    private fun launchContainerGameFromDebugIntent(intent: Intent?) {
        if (BuildConfig.BUILD_TYPE != "debug" || intent == null || !intent.hasDebugContainerAction()) {
            return
        }
        Thread {
            runCatching {
                configureMicrogFromDebugIntent(intent)
                if (intent.getBooleanExtra(EXTRA_SYNC_INSTALLED_GAME, false)) {
                    Log.i(TAG, "Debug intent syncing installed game before launch")
                    val installed = AndroidInstalledGameImporter(
                        packageManager = packageManager,
                        importDirectory = File(filesDir, "container-imports")
                    ).copyInstalledGame()
                    val result = containerRuntime.importGame(
                        GameImportRequest(
                            splitApkPaths = installed.splitApkPaths,
                            versionCode = installed.versionCode,
                            source = "installed"
                        )
                    )
                    Log.i(TAG, "Debug intent synced installed game version=${installed.versionCode} splits=${result.importedSplits}")
                }
                if (intent.getBooleanExtra(EXTRA_EXPORT_LOCALIZE_SNAPSHOT, false)) {
                    val storage = containerRuntime.storage()
                    require(storage is com.example.limbuszhcn.container.ContainerWorkspaceStorage) {
                        "Localize snapshot export requires container workspace storage"
                    }
                    val result = LocalizeSnapshotExporter(applicationContext, storage).exportSnapshot()
                    Log.i(
                        TAG,
                        "Debug intent exported Localize snapshot zip=${result.zipFile.absolutePath} " +
                            "summary=${result.summaryFile.absolutePath} json=${result.jsonFileCount} bytes=${result.totalBytes}"
                    )
                }
                if (intent.getBooleanExtra(EXTRA_REFRESH_TRANSLATION_REDIRECTS, false)) {
                    val summary = ContainerPatchInstaller(
                        cacheRoot = filesDir.toPath().resolve("translation-cache"),
                        storage = containerRuntime.storage()
                    ).refreshActiveRedirects()
                    Log.i(TAG, "Debug intent refreshed translation redirects version=${summary.version} files=${summary.writtenFiles}")
                }
                if (intent.getBooleanExtra(EXTRA_LAUNCH_CONTAINER_GAME, false)) {
                    Log.i(TAG, "Debug intent launching container game")
                    containerRuntime.launchGame()
                }
                if (intent.getBooleanExtra(EXTRA_ENABLE_TRANSLATION_REDIRECTS, false)) {
                    require(!intent.getBooleanExtra(EXTRA_LAUNCH_CONTAINER_GAME, false)) {
                        "Enable translation redirects only after the running game finishes resource validation"
                    }
                    val pid = TranslationRedirectGate(applicationContext).openForRunningGame()
                    Log.i(TAG, "Debug intent enabled translation redirects for pid=$pid")
                }
            }.onSuccess {
                recordDebugIntentResult("SUCCESS")
            }.onFailure { error ->
                recordDebugIntentResult("FAILURE", error)
                Log.e(TAG, "Debug intent launch failed", error)
            }
        }.start()
    }

    private fun recordDebugIntentResult(status: String, error: Throwable? = null) {
        runCatching {
            // ColorOS 可能在高日志量时丢弃应用日志。额外写入一个仅调试包可读取的结果文件，
            // 让 ADB/Android Studio 即使遇到日志流控也能取得完整故障链。
            File(filesDir, "debug-intent-result.txt").writeText(
                buildString {
                    appendLine("status=$status")
                    appendLine("time=${System.currentTimeMillis()}")
                    if (error != null) {
                        appendLine("type=${error.javaClass.name}")
                        appendLine("message=${error.message.orEmpty()}")
                        append(error.stackTraceToString())
                    }
                }
            )
        }.onFailure { writeError ->
            Log.e(TAG, "Unable to persist debug intent result", writeError)
        }
    }

    private fun configureMicrogFromDebugIntent(intent: Intent) {
        val rawUrls = intent.getStringExtra(EXTRA_MICROG_APK_URLS)
        if (rawUrls != null) {
            val urls = rawUrls.split('|').map(String::trim).filter(String::isNotEmpty)
            MicrogContainerConfig(applicationContext).configureFromUrls(urls)
            Log.i(TAG, "Downloaded container microG apkCount=${urls.size}")
            return
        }
        val rawPaths = intent.getStringExtra(EXTRA_MICROG_APK_PATHS) ?: return
        val sourceApks = rawPaths
            .split(File.pathSeparatorChar)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::File)
        MicrogContainerConfig(applicationContext).configure(sourceApks)
        Log.i(TAG, "Configured container microG apkCount=${sourceApks.size}")
    }
}

private fun Intent.hasDebugContainerAction(): Boolean =
    getBooleanExtra("launch_container_game", false) ||
        getBooleanExtra("sync_installed_game", false) ||
        getBooleanExtra("export_localize_snapshot", false) ||
        getBooleanExtra("refresh_translation_redirects", false) ||
        getBooleanExtra("enable_translation_redirects", false)

private fun Intent?.describeForLog(): String =
    if (this == null) {
        "null"
    } else {
        "action=$action component=$component package=$`package` flags=0x${flags.toString(16)} data=$data type=$type categories=$categories extras=${extras?.keySet()}"
    }

private fun shortStack(): String =
    Throwable().stackTrace
        .drop(1)
        .take(6)
        .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

private fun android.content.SharedPreferences.nonBlankString(key: String, defaultValue: String): String =
    getString(key, null)?.takeIf { it.isNotBlank() } ?: defaultValue

private fun Throwable.userMessage(): String {
    val chain = generateSequence(this) { it.cause }.toList()
    return chain.joinToString(separator = "\n") { error ->
        val message = error.message?.takeIf { it.isNotBlank() }
        if (message == null) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }.take(900)
}

private fun ContainerStatus.withInstalledVersion(installedVersionCode: Long?): ContainerStatus {
    if (!gameImported || installedVersionCode == null || importedVersionCode == null) {
        return this
    }
    if (installedVersionCode == importedVersionCode) {
        return copy(message = "$message,已匹配当前安装版本")
    }
    return copy(
        message = "Google Play 已安装版本 v$installedVersionCode,容器内版本 v$importedVersionCode,需要重新同步已安装游戏"
    )
}

@Composable
private fun LimbusApp(
    containerStatus: ContainerStatus,
    lastInstall: PatchInstallSummary?,
    compatibilityReport: DeviceCompatibilityReport,
    githubReleaseUrl: String,
    busy: Boolean,
    busyMessage: String,
    message: String,
    onGithubReleaseUrlChange: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onContainerRefresh: () -> Unit,
    onOpenCompatibilitySettings: () -> Unit,
    onSyncInstalledGame: () -> Unit,
    onLaunchTranslatedGame: () -> Unit,
    onUpdateTranslation: () -> Unit,
    onRestoreTranslation: () -> Unit,
    onExportDebugLogs: () -> Unit
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Limbus Company 汉化器",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "自动检查更新并启动汉化版游戏",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (busy) {
            BusyDialog(message = busyMessage)
        }
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusCard(
                containerStatus = containerStatus,
                lastInstall = lastInstall,
                message = message
            )
            CompatibilityCard(
                report = compatibilityReport,
                busy = busy,
                onOpenSettings = onOpenCompatibilitySettings
            )
            StepCard(
                title = "启动",
                status = if (lastInstall == null) "尚未激活汉化" else "汉化 ${lastInstall.version}"
            ) {
                Text(
                    "启动时会检查游戏版本和最新汉化。已下载的汉化包会直接复用。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "汉化来源：GitHub Releases",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onLaunchTranslatedGame,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("启动汉化版游戏")
                }
                OutlinedButton(
                    onClick = onUpdateTranslation,
                    enabled = containerStatus.gameImported && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("仅检查并更新汉化")
                }
            }
            TextButton(
                onClick = { advancedExpanded = !advancedExpanded },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (advancedExpanded) "收起高级设置" else "高级设置")
            }
            if (advancedExpanded) {
                StepCard(title = "高级设置", status = "GitHub Releases") {
                    Text(
                        "普通情况无需修改下列配置。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = githubReleaseUrl,
                        onValueChange = onGithubReleaseUrlChange,
                        label = { Text("GitHub Releases 地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = onSaveConfig, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("保存设置")
                    }
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onSyncInstalledGame,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("重新同步游戏")
                        }
                        OutlinedButton(
                            onClick = onRestoreTranslation,
                            enabled = lastInstall != null && !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("停用汉化")
                        }
                    }
                    TextButton(onClick = onContainerRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("刷新状态")
                    }
                    OutlinedButton(
                        onClick = onExportDebugLogs,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出 Issue 诊断包（ZIP）")
                    }
                    Text(
                        "诊断包包含滚动日志、机型兼容信息和异常退出原因；会自动脱敏，不包含游戏资源、存档或更新地址配置。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 展示当前机型的基础兼容结论，并提供 OEM 后台权限设置入口。
 *
 * @param report 当前设备兼容报告。
 * @param busy 主界面是否正在执行其他任务。
 * @param onOpenSettings 打开系统或 OEM 后台运行设置的回调。
 */
@Composable
private fun CompatibilityCard(
    report: DeviceCompatibilityReport,
    busy: Boolean,
    onOpenSettings: () -> Unit
) {
    StepCard(title = "设备兼容", status = report.statusLabel) {
        Text(report.deviceLabel, style = MaterialTheme.typography.bodyMedium)
        StatusLine("系统", report.androidLabel)
        StatusLine(
            "运行环境",
            "${if (report.is64BitProcess) "64 位" else "32 位"} / " +
                "${if (report.pageSizeBytes > 0) "${report.pageSizeBytes / 1024} KB 页" else "页大小未知"}"
        )
        Text(report.recommendation, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = onOpenSettings,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开后台兼容设置")
        }
    }
}

@Composable
private fun BusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("请稍候") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    )
}

@Composable
private fun StatusCard(
    containerStatus: ContainerStatus,
    lastInstall: PatchInstallSummary?,
    message: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("当前状态", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            StatusLine("游戏", if (containerStatus.gameImported) "已准备" else "等待同步")
            StatusLine("汉化", lastInstall?.let { "已激活 ${it.version}" } ?: "未激活")
            StatusLine("来源", "GitHub Releases")
            HorizontalDivider()
            Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    status: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(value, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Preview(showBackground = true)
@Composable
private fun LimbusAppPreview() {
    LimbusZhCNTheme {
        LimbusApp(
            containerStatus = ContainerStatus(available = true, gameImported = true, message = "容器工作区已导入游戏"),
            lastInstall = PatchInstallSummary(version = "2026050702", writtenFiles = 1952),
            compatibilityReport = DeviceCompatibilityReport(
                deviceLabel = "vivo / iQOO 示例机型",
                androidLabel = "Android 15（API 35）",
                sdkInt = 35,
                abiLabel = "arm64-v8a",
                hasRequiredAbi = true,
                pageSizeBytes = 16 * 1024L,
                is64BitProcess = true,
                isLowRamDevice = false,
                isIgnoringBatteryOptimizations = false,
                warnings = listOf("建议允许自启动、后台高耗电和后台运行")
            ),
            githubReleaseUrl = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases",
            busy = false,
            busyMessage = "正在检查游戏版本并准备启动",
            message = "等待操作",
            onGithubReleaseUrlChange = {},
            onSaveConfig = {},
            onContainerRefresh = {},
            onOpenCompatibilitySettings = {},
            onSyncInstalledGame = {},
            onLaunchTranslatedGame = {},
            onUpdateTranslation = {},
            onRestoreTranslation = {},
            onExportDebugLogs = {}
        )
    }
}
