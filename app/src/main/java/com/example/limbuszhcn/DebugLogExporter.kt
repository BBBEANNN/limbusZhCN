package com.example.limbuszhcn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.container.MicrogContainerConfig
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class DebugLogExporter(private val context: Context) {
    fun export(
        containerStatus: ContainerStatus,
        activeTranslation: PatchInstallSummary?
    ): File {
        val outputDirectory = File(context.cacheDir, "diagnostics").apply {
            require(isDirectory || mkdirs()) { "无法创建诊断目录" }
            listFiles()?.filter { it.name.startsWith(FILE_PREFIX) }?.forEach(File::delete)
        }
        val stamp = LocalDateTime.now().format(FILE_STAMP)
        val output = File(outputDirectory, "$FILE_PREFIX$stamp.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            zip.writeText("README.txt", README)
            zip.writeText(
                "runtime.txt",
                runtimeSummary(containerStatus, activeTranslation)
            )
            zip.writeText("logcat.txt", collectLogcat())
            val exits = collectExitReasons()
            zip.writeText("process-exits.txt", exits.summary)
            exits.traces.forEachIndexed { index, trace ->
                zip.writeText("exit-trace-${index + 1}.txt", trace)
            }
        }
        require(output.length() <= MAX_ARCHIVE_BYTES) {
            output.delete()
            "诊断包超过大小限制"
        }
        return output
    }

    private fun runtimeSummary(
        containerStatus: ContainerStatus,
        activeTranslation: PatchInstallSummary?
    ): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val runtime = Runtime.getRuntime()
        val files = context.filesDir
        val microgStatus = MicrogContainerConfig(context).diagnosticStatus()
        return buildString {
            appendLine("generatedAt=${Instant.now()}")
            appendLine("timezone=${ZoneId.systemDefault().id}")
            appendLine("appVersionName=${packageInfo.versionName}")
            appendLine("appVersionCode=${packageInfo.longVersionCode}")
            appendLine("buildType=${BuildConfig.BUILD_TYPE}")
            appendLine("package=${context.packageName}")
            appendLine("uid=${Process.myUid()}")
            appendLine("deviceManufacturer=${Build.MANUFACTURER}")
            appendLine("deviceModel=${Build.MODEL}")
            appendLine("androidSdk=${Build.VERSION.SDK_INT}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("runtimeMaxBytes=${runtime.maxMemory()}")
            appendLine("runtimeFreeBytes=${runtime.freeMemory()}")
            appendLine("appStorageFreeBytes=${files.freeSpace}")
            appendLine("translationSource=GitHub Releases")
            appendLine("containerAvailable=${containerStatus.available}")
            appendLine("gameImported=${containerStatus.gameImported}")
            appendLine("importedVersionCode=${containerStatus.importedVersionCode ?: "none"}")
            appendLine("importedSplits=${containerStatus.importedSplits}")
            appendLine("importSource=${containerStatus.importSource ?: "none"}")
            appendLine("containerMessage=${containerStatus.message}")
            appendLine("translationActive=${activeTranslation != null}")
            appendLine("translationVersion=${activeTranslation?.version ?: "none"}")
            appendLine("translationSourceFiles=${activeTranslation?.availableSourceFiles ?: 0}/${activeTranslation?.candidateSourceFiles ?: 0}")
            appendLine("translationSourceRecords=${activeTranslation?.sourceRecordCount ?: 0}")
            appendLine("microgSource=${microgStatus.source}")
            appendLine("microgApks=${microgStatus.availableApkCount}/${microgStatus.configuredApkCount}")
            appendLine("microgMigrationCompleted=${microgStatus.migrationCompleted}")
        }.redactedAndBounded(MAX_SUMMARY_BYTES)
    }

    private fun collectLogcat(): String {
        val command = listOf(
            "logcat",
            "-d",
            "-b",
            "all",
            "-v",
            "threadtime",
            "--uid=${Process.myUid()}",
            "-t",
            MAX_LOG_LINES.toString()
        )
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val bytes = process.inputStream.readBounded(MAX_LOGCAT_BYTES)
            val exitCode = process.waitFor()
            buildString {
                appendLine("command=${command.joinToString(" ")}")
                appendLine("exitCode=$exitCode")
                append(String(bytes, Charsets.UTF_8))
            }.redactedAndBounded(MAX_LOGCAT_BYTES)
        }.getOrElse { error ->
            "logcat collection failed: ${error.javaClass.simpleName}: ${error.message}"
                .redactedAndBounded(MAX_SUMMARY_BYTES)
        }
    }

    private fun collectExitReasons(): ExitDiagnostics {
        val manager = context.getSystemService(ActivityManager::class.java)
        val reasons = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
        }.getOrElse { error ->
            return ExitDiagnostics(
                summary = "exit reason collection failed: ${error.javaClass.simpleName}: ${error.message}"
                    .redactedAndBounded(MAX_SUMMARY_BYTES),
                traces = emptyList()
            )
        }
        val traces = mutableListOf<String>()
        val summary = buildString {
            appendLine("count=${reasons.size}")
            reasons.forEachIndexed { index, reason ->
                appendLine()
                appendLine("[$index]")
                appendLine("timestamp=${Instant.ofEpochMilli(reason.timestamp)}")
                appendLine("process=${reason.processName}")
                appendLine("reason=${exitReasonName(reason.reason)}(${reason.reason})")
                appendLine("status=${reason.status}")
                appendLine("importance=${reason.importance}")
                appendLine("pssKb=${reason.pss}")
                appendLine("rssKb=${reason.rss}")
                appendLine("description=${reason.description.orEmpty()}")
                if (traces.size < MAX_EXIT_TRACES) {
                    runCatching {
                        reason.traceInputStream?.use { trace ->
                            String(trace.readBounded(MAX_EXIT_TRACE_BYTES), Charsets.UTF_8)
                        }
                    }.getOrNull()?.takeIf(String::isNotBlank)?.let { trace ->
                        traces += buildString {
                            appendLine("process=${reason.processName}")
                            appendLine("timestamp=${Instant.ofEpochMilli(reason.timestamp)}")
                            append(trace)
                        }.redactedAndBounded(MAX_EXIT_TRACE_BYTES)
                        appendLine("traceFile=exit-trace-${traces.size}.txt")
                    }
                }
            }
        }.redactedAndBounded(MAX_EXIT_SUMMARY_BYTES)
        return ExitDiagnostics(summary, traces)
    }

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit
        var truncated = false
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val retained = minOf(count, remaining)
            if (retained > 0) {
                output.write(buffer, 0, retained)
                remaining -= retained
            }
            if (retained < count) truncated = true
        }
        if (truncated) {
            output.write("\n[truncated]\n".toByteArray(Charsets.UTF_8))
        }
        return output.toByteArray()
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

    private data class ExitDiagnostics(val summary: String, val traces: List<String>)

    companion object {
        private const val FILE_PREFIX = "limbus-diagnostics-"
        private const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L
        private const val MAX_LOGCAT_BYTES = 3 * 1024 * 1024
        private const val MAX_SUMMARY_BYTES = 64 * 1024
        private const val MAX_EXIT_SUMMARY_BYTES = 128 * 1024
        private const val MAX_EXIT_TRACE_BYTES = 512 * 1024
        private const val MAX_LOG_LINES = 6000
        private const val MAX_EXIT_REASONS = 16
        private const val MAX_EXIT_TRACES = 3
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val README = """
            Limbus Company 汉化器调试包

            内容：应用与容器状态、当前汉化索引摘要、本应用 UID 的近期日志、历史进程退出原因。
            不包含：游戏资源、存档、PlayerPrefs、汉化渠道地址、Token 或账号配置。
            文本已经过自动脱敏，但发送前仍建议仅交给汉化器维护者。
        """.trimIndent() + "\n"
    }
}

internal fun String.redactedAndBounded(maxBytes: Int): String {
    var value = this
    value = URL_CREDENTIAL.replace(value) { match -> "${match.groupValues[1]}<redacted>@" }
    value = CREDENTIAL_FIELD.replace(value) { match -> "${match.groupValues[1]}<redacted>" }
    value = FIREBASE_USER.replace(value) { match ->
        "${match.groupValues[1]}<redacted-account>${match.groupValues[2]}"
    }
    value = JWT_VALUE.replace(value, "<redacted-jwt>")
    value = EMAIL_ADDRESS.replace(value, "<redacted-email>")
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return value
    val suffix = bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
    return "[truncated to recent data]\n" + String(suffix, Charsets.UTF_8)
}

private val URL_CREDENTIAL = Regex("(https?://)[^\\s/@:]+(?::[^\\s/@]*)?@", RegexOption.IGNORE_CASE)
private val CREDENTIAL_FIELD = Regex(
    "([\\\"']?(?:authorization|access[_-]?token|refresh[_-]?token|token|api[_-]?key|secret|password)[\\\"']?\\s*[:=]\\s*)(?:bearer\\s+)?(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)",
    RegexOption.IGNORE_CASE
)
private val JWT_VALUE = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])")
private val EMAIL_ADDRESS = Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9.-])")
private val FIREBASE_USER = Regex("(about\\s+user\\s*\\(\\s*)[^)\\r\\n]+(\\s*\\))", RegexOption.IGNORE_CASE)
