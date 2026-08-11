package com.example.limbuszhcn

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/**
 * 在应用私有目录维护按进程分离的滚动诊断日志。
 *
 * 系统 logcat 在部分 OEM 系统上会在进程被清理后丢失，因此关键启动阶段和未捕获异常还要
 * 同步写入私有文件。日志不会写入外部存储，也不会包含游戏资源或账号配置；导出时仍会再次脱敏。
 */
internal object PersistentDiagnosticLog {
    private var installed = false
    private var logFile: File? = null
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 为当前进程安装日志文件与未捕获异常处理器。
     *
     * @param context 当前进程可用的 Context；允许在 Application.attachBaseContext 阶段传入。
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val directory = diagnosticDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) {
            Log.w(TAG, "Unable to create persistent diagnostics directory=${directory.absolutePath}")
            return
        }
        val processName = resolveProcessName(context)
        val safeProcessName = processName.replace(UNSAFE_FILE_NAME, "_").take(80)
        logFile = File(directory, "runtime-$safeProcessName.log")
        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 先同步落盘再交还 Android/目标应用的原处理器，确保 OEM 立即杀进程时仍保留最后现场。
            error(TAG, "Uncaught exception thread=${thread.name}", error)
            previousExceptionHandler?.uncaughtException(thread, error)
                ?: run {
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(10)
                }
        }
        installed = true
        info(
            TAG,
            "persistent log installed pid=${Process.myPid()} process=$processName " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString(",")}"
        )
    }

    /**
     * 记录普通诊断事件，同时保留到系统 logcat。
     *
     * @param tag 事件所属模块。
     * @param message 不含账号与凭据的诊断说明。
     */
    fun info(tag: String, message: String) {
        Log.i(tag, message)
        append("INFO", tag, message, null)
    }

    /**
     * 记录可恢复的兼容异常。
     *
     * @param tag 事件所属模块。
     * @param message 不含账号与凭据的诊断说明。
     * @param error 可选异常堆栈。
     */
    fun warn(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
        append("WARN", tag, message, error)
    }

    /**
     * 记录导致当前操作失败的异常。
     *
     * @param tag 事件所属模块。
     * @param message 不含账号与凭据的诊断说明。
     * @param error 可选异常堆栈。
     */
    fun error(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
        append("ERROR", tag, message, error)
    }

    /**
     * 列出可附加到 Issue 诊断包的持久化日志。
     *
     * @param context 用于定位应用私有诊断目录。
     * @return 按文件名排序的当前与上一轮日志文件。
     */
    fun files(context: Context): List<File> =
        diagnosticDirectory(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("runtime-") && it.extension == "log" }
            ?.sortedBy(File::getName)
            .orEmpty()

    /** 将一条事件追加到当前进程文件，并在超过上限时保留一份上一轮日志。 */
    @Synchronized
    private fun append(level: String, tag: String, message: String, error: Throwable?) {
        val target = logFile ?: return
        runCatching {
            rotateIfNeeded(target)
            val stack = error?.let(Log::getStackTraceString).orEmpty()
            val rawEntry = buildString {
                append(Instant.now())
                append(" pid=")
                append(Process.myPid())
                append(" tid=")
                append(Process.myTid())
                append(' ')
                append(level)
                append('/')
                append(tag.take(MAX_TAG_CHARS))
                append(": ")
                appendLine(message)
                if (stack.isNotBlank()) appendLine(stack)
            }
            val safeEntry = rawEntry.redactedAndBounded(MAX_ENTRY_BYTES)
            FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append(safeEntry)
                if (!safeEntry.endsWith('\n')) writer.newLine()
            }
        }.onFailure { writeError ->
            // 持久化日志属于辅助能力，磁盘满或文件锁异常不能让容器主流程失败。
            Log.w(TAG, "Unable to append persistent diagnostic log", writeError)
        }
    }

    /** 超过固定大小后滚动文件，避免长期使用导致应用数据无限增长。 */
    private fun rotateIfNeeded(target: File) {
        if (target.length() < MAX_LOG_BYTES) return
        val previous = File(target.parentFile, "${target.nameWithoutExtension}.previous.log")
        if (previous.exists() && !previous.delete()) {
            Log.w(TAG, "Unable to remove previous diagnostic log=${previous.absolutePath}")
        }
        if (!target.renameTo(previous)) {
            // 某些 OEM 文件系统可能拒绝 rename；截断当前文件仍能保证空间上限。
            FileOutputStream(target, false).use { }
        }
    }

    /** 统一返回不参与云备份的私有目录，防止诊断日志迁移到其他手机。 */
    private fun diagnosticDirectory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY_NAME)

    /** 在 Application 尚未完成创建时也尽量解析真实进程名，用于区分 server 与 p0。 */
    private fun resolveProcessName(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
                ?: context.applicationInfo.processName
        }
    }.getOrDefault(context.applicationInfo.processName)

    private const val TAG = "PersistentDiagnostics"
    private const val DIRECTORY_NAME = "diagnostics"
    private const val MAX_LOG_BYTES = 512L * 1024L
    private const val MAX_ENTRY_BYTES = 64 * 1024
    private const val MAX_TAG_CHARS = 64
    private val UNSAFE_FILE_NAME = Regex("[^A-Za-z0-9._-]")
}
