package com.example.limbuszhcn

import android.app.Notification
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.lody.virtual.client.core.SettingConfig
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.helper.compat.NotificationChannelCompat
import java.io.File

/**
 * 初始化汉化器宿主进程及 VirtualApp 容器运行时。
 *
 * 主进程、容器服务进程和虚拟应用进程都会创建该 Application，因此初始化逻辑必须可重复执行。
 */
class LimbusZhCNApplication : Application() {
    /**
     * 在各进程创建早期启动 VirtualApp，使后续组件能够安全访问虚拟包管理服务。
     *
     * @param base Android 为当前进程提供的基础上下文
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Log.i(TAG, "attachBaseContext pid=${Process.myPid()} process=${base.getProcessNameForLog()} package=$packageName")
        invalidateStaleGmsChimeraConfig(base)
        runCatching {
            VirtualCore.get().startup(this, LimbusVirtualConfig(packageName))
            Log.i(TAG, "VirtualCore.startup success pid=${Process.myPid()}")
        }.onFailure { error ->
            Log.e(TAG, "VirtualApp startup failed", error)
        }
    }

    private fun invalidateStaleGmsChimeraConfig(context: Context) {
        runCatching {
            val hostApk = File(context.applicationInfo.sourceDir)
            val config = File(
                context.applicationInfo.dataDir,
                "virtual/data/user_de/0/com.google.android.gms/app_chimera/current_config.fb"
            )
            if (config.isFile && config.lastModified() < hostApk.lastModified()) {
                val deleted = config.delete()
                Log.i(
                    TAG,
                    "Invalidated stale virtual GMS Chimera config deleted=$deleted " +
                        "configMtime=${config.lastModified()} hostApkMtime=${hostApk.lastModified()}"
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to invalidate stale virtual GMS Chimera config", error)
        }
    }

    /**
     * 完成 VirtualApp 的进程级初始化，并记录当前进程便于真机诊断。
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application.onCreate pid=${Process.myPid()} process=${getProcessNameForLog()} package=$packageName")
        runCatching {
            VirtualCore.get().initialize(object : VirtualCore.VirtualInitializer() {})
            Log.i(TAG, "VirtualCore.initialize success pid=${Process.myPid()}")
        }.onFailure { error ->
            Log.e(TAG, "VirtualApp initialize failed", error)
        }
    }

    private class LimbusVirtualConfig(
        private val hostPackageName: String
    ) : SettingConfig() {
        override fun getHostPackageName(): String = hostPackageName

        override fun getPluginEnginePackageName(): String? = null

        override fun isAllowCreateShortcut(): Boolean = false

        override fun isHideForegroundNotification(): Boolean = true

        /**
         * 构建可被 Android 识别的容器前台通知。
         *
         * Android 要求前台通知必须带有效的小图标；若沿用 VirtualApp 的空通知，
         * ColorOS 会把通知判定为损坏，容器服务也更容易在内存整理时被回收。
         *
         * @return 带小图标、说明文字和常驻标记的前台服务通知
         */
        override fun getForegroundNotification(): Notification {
            val context = VirtualCore.get().context
            return NotificationChannelCompat.createBuilder(
                context,
                NotificationChannelCompat.DAEMON_ID
            ).apply {
                setSmallIcon(R.drawable.app_icon)
                setContentTitle("Limbus 汉化容器")
                setContentText("正在维护汉化游戏运行环境")
                setOngoing(true)
                setCategory(Notification.CATEGORY_SERVICE)
                setSound(null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setVisibility(Notification.VISIBILITY_SECRET)
                }
            }.build()
        }
    }

    companion object {
        private const val TAG = "LimbusZhCNApp"
    }
}

private fun Context.getProcessNameForLog(): String =
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val pid = Process.myPid()
            manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "unknown"
        }
    }.getOrDefault("unknown")
