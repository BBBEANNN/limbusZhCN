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
 * 保存汉化器自更新状态，使下载跨越屏幕旋转且只由宿主前台触发安装。
 *
 * @param application 用于访问宿主私有缓存与系统包管理器的应用上下文。
 */
internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = AppUpdateManager(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkOnNextStart = true
    private var externalFlow = false
    @Volatile private var cleared = false

    /** 当前界面可观察的检查、下载和待安装状态。 */
    var state by mutableStateOf(AppUpdateUiState())
        private set

    /** 宿主是否已恢复交互；后台下载完成时需等待此前台标志才能打开系统界面。 */
    var hostResumed by mutableStateOf(false)
        private set

    /** 每轮真正返回前台时检查一次，旋转重建和系统安装流程不重复检查。 */
    fun onHostStarted() {
        if (checkOnNextStart && !externalFlow) {
            checkOnNextStart = false
            checkForUpdates()
        }
    }

    /**
     * 标记下一次需要自检的前台入口。
     *
     * @param changingConfigurations 是否仅为屏幕旋转等配置变更而重建界面。
     */
    fun onHostStopped(changingConfigurations: Boolean) {
        if (!changingConfigurations && !externalFlow) checkOnNextStart = true
    }

    /**
     * 更新可安全打开系统安装界面的生命周期状态。
     *
     * @param resumed 宿主当前是否可交互。
     */
    fun updateHostResumed(resumed: Boolean) {
        hostResumed = resumed
    }

    /** 后台检查并自动准备新版，网络错误只更新本卡片，不阻断游戏入口。 */
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
                                message = "新版 ${update.versionName} 已下载，准备打开系统安装界面",
                                update = update,
                                automaticInstallPending = true
                            )
                        }
                    },
                    onFailure = { error -> reportFailure("自动更新未完成", error) }
                )
            }
        }
    }

    /** 用户再次安装或从授权页返回时，重新检查缓存，避免安装被系统清理或损坏的文件。 */
    fun requestInstallation() {
        val update = state.update ?: return
        if (state.working || externalFlow) return
        state = state.copy(working = true, message = "正在校验已下载的新版…")
        executor.execute {
            val result = runCatching { manager.validatePreparedUpdate(update) }
            post {
                result.fold(
                    onSuccess = {
                        state = state.copy(working = false, automaticInstallPending = true)
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
        if (!state.automaticInstallPending || !hostResumed || state.working) return null
        state = state.copy(automaticInstallPending = false)
        return state.update
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
        checkOnNextStart = false
        state = state.copy(message = message)
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
            automaticInstallPending = false
        )
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
 * @property automaticInstallPending 是否有尚未消费的一次性自动安装请求。
 */
internal data class AppUpdateUiState(
    val message: String = "每次打开时自动检查汉化器版本",
    val working: Boolean = false,
    val update: PreparedAppUpdate? = null,
    val automaticInstallPending: Boolean = false
)
