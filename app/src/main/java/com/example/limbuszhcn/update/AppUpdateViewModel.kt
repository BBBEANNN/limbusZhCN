package com.example.limbuszhcn.update

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.limbuszhcn.PersistentDiagnosticLog
import java.util.concurrent.Executors

/**
 * 保存玩家手动检查的更新状态，使下载跨越屏幕旋转，且安装只能来自玩家的明确请求。
 *
 * @param application 用于访问宿主私有缓存与系统包管理器的应用上下文。
 */
internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = AppUpdateManager(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var externalFlow = false
    // 离开前台使尚未完成的安装请求失效，避免后台校验结束后在下次打开时突弹安装器。
    private var installationAttempt = 0L
    @Volatile private var cleared = false

    /** 当前界面可观察的检查、下载和待安装状态。 */
    var state by mutableStateOf(AppUpdateUiState())
        private set

    /** 宿主是否已恢复交互；后台下载完成时需等待此前台标志才能打开系统界面。 */
    var hostResumed by mutableStateOf(false)
        private set

    /** 系统实际安装的显示版本，不使用可能被旧构建内联的版本常量。 */
    var installedVersionName by mutableStateOf("读取中")
        private set

    /**
     * 更新可安全打开系统安装界面的生命周期状态。
     *
     * @param resumed 宿主当前是否可交互。
     */
    fun updateHostResumed(resumed: Boolean) {
        hostResumed = resumed
        if (resumed) {
            refreshInstalledState()
        } else {
            installationAttempt++
            state = state.copy(installationRequested = false)
        }
    }

    /** 玩家点击检查后才联网并准备新版，准备完成只显示按钮，不打开系统安装器。 */
    fun checkForUpdates() {
        if (state.working || externalFlow) return
        state = AppUpdateUiState(message = "正在检查汉化器新版本…", working = true)
        executor.execute {
            val result = runCatching {
                manager.prepareUpdate { progress -> post { state = state.copy(message = progress) } }
            }
            post {
                result.fold(
                    onSuccess = { update ->
                        state = if (update == null) {
                            AppUpdateUiState(message = "当前已是最新版本")
                        } else {
                            AppUpdateUiState(
                                message = "新版 ${update.versionName} 已准备好，点击安装新版继续",
                                update = update
                            )
                        }
                    },
                    onFailure = { error -> reportFailure("检查更新未完成", error) }
                )
            }
        }
    }

    /** 用户再次安装或从授权页返回时，重新检查缓存，避免安装被系统清理或损坏的文件。 */
    fun requestInstallation() {
        val update = state.update ?: return
        if (state.working || externalFlow) return
        val attempt = ++installationAttempt
        state = state.copy(working = true, message = "正在校验已下载的新版…")
        executor.execute {
            val result = runCatching { manager.validatePreparedUpdate(update) }
            post {
                if (attempt != installationAttempt) {
                    // 玩家在校验期间离开页面，本次安装请求不在返回前台后自动重放。
                    state = state.copy(working = false, installationRequested = false)
                    return@post
                }
                result.fold(
                    onSuccess = { needed ->
                        if (needed) {
                            state = state.copy(working = false, installationRequested = true)
                        } else {
                            clearObsoleteUpdate()
                        }
                    },
                    onFailure = { error -> reportFailure("安装包校验失败，请重新检查更新", error) }
                )
            }
        }
    }

    /**
     * 一次性领取安装请求，防止 Compose 重组反复启动安装器。
     *
     * @return 本轮已验证的 APK；没有请求时返回 null。
     */
    fun consumeInstallationRequest(): PreparedAppUpdate? {
        if (!state.installationRequested || !hostResumed || state.working || externalFlow) return null
        state = state.copy(installationRequested = false)
        val update = state.update ?: return null
        return runCatching {
            // 校验任务结束到打开安装器之间，系统可能已通过其它入口完成升级；再次只读核对。
            if (manager.isUpdateNewerThanInstalled(update)) update else {
                clearObsoleteUpdate()
                null
            }
        }.getOrElse { error ->
            reportFailure("无法确认当前安装版本", error)
            null
        }
    }

    /**
     * 进入授权页或安装页；返回时只续接该流程，避免重新下载和重复弹窗。
     *
     * @param message 界面展示的当前安装步骤。
     */
    fun beginExternalFlow(message: String) {
        externalFlow = true
        state = state.copy(message = message)
    }

    /**
     * 记录系统界面返回；取消安装后保留手动重试入口。
     *
     * @param message 本轮返回后的提示文字。
     */
    fun finishExternalFlow(message: String) {
        externalFlow = false
        state = state.copy(message = message)
        refreshInstalledState()
    }

    /**
     * 记录可恢复错误，并使用户仍可手动重试检查。
     *
     * @param stage 出错的更新阶段。
     * @param error 检查、下载或系统界面启动异常。
     */
    fun reportFailure(stage: String, error: Throwable) {
        PersistentDiagnosticLog.warn("LimbusZhCN", "App self-update failed stage=$stage", error)
        state = state.copy(
            message = "$stage：${error.message ?: "请检查网络后重试"}",
            working = false,
            update = null,
            installationRequested = false
        )
    }

    /** 仅查询本地 PackageManager，清除系统已安装的待更新项，不发起任何联网或安装。 */
    private fun refreshInstalledState() {
        runCatching {
            installedVersionName = manager.installedVersionName()
            state.update?.let { update ->
                if (!manager.isUpdateNewerThanInstalled(update)) clearObsoleteUpdate()
            }
        }.onFailure { error -> reportFailure("无法读取当前安装版本", error) }
    }

    /** 同版或更高版本已安装时移除旧按钮与请求，避免继续重试同一个 APK。 */
    private fun clearObsoleteUpdate() {
        installationAttempt++
        installedVersionName = manager.installedVersionName()
        state = AppUpdateUiState(message = "当前已是最新版本")
    }

    /** 界面永久销毁时取消后台任务，避免下载线程持有已失效的状态。 */
    override fun onCleared() {
        cleared = true
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    /**
     * 将下载线程状态串行交给主线程，已销毁的 ViewModel 不再发布回调。
     *
     * @param action 主线程执行的状态变更。
     */
    private fun post(action: () -> Unit) {
        if (!cleared) mainHandler.post { if (!cleared) action() }
    }
}

/**
 * 自更新卡片的不可变状态，不与汉化包安装或容器任务共享忙碌标志。
 *
 * @property message 当前更新步骤或结果。
 * @property working 是否正在检查、下载或重新校验。
 * @property update 已验证并可交给系统安装器的新版 APK。
 * @property installationRequested 玩家是否请求了尚未消费的一次性安装操作。
 */
internal data class AppUpdateUiState(
    val message: String = "点击检查更新，不会自动检查或弹出安装界面",
    val working: Boolean = false,
    val update: PreparedAppUpdate? = null,
    val installationRequested: Boolean = false
)
