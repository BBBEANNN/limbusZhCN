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
                "ISSUE_TEMPLATE.md",
                issueTemplate(containerStatus, activeTranslation)
            )
            zip.writeText(
                "runtime.txt",
                runtimeSummary(containerStatus, activeTranslation)
            )
            // 持久日志比 logcat 更能覆盖 vivo 等系统在后台直接回收进程后的故障现场。
            PersistentDiagnosticLog.files(context)
                .sortedByDescending(File::lastModified)
                .take(MAX_PERSISTENT_LOG_FILES)
                .sortedBy(File::getName)
                .forEach { logFile ->
                    val content = logFile.inputStream().use { input ->
                        String(input.readBounded(MAX_PERSISTENT_LOG_BYTES), Charsets.UTF_8)
                    }.redactedAndBounded(MAX_PERSISTENT_LOG_BYTES)
                    zip.writeText("persistent/${logFile.name}", content)
                }
            zip.writeText("logcat.txt", collectLogcat())
            val exits = collectExitReasons()
            zip.writeText("process-exits.txt", exits.summary)
            exits.traces.forEachIndexed { index, trace ->
                // Native tombstone 是 protobuf 二进制；按 UTF-8 写回会破坏地址和字段长度。
                zip.writeBytes("exit-trace-${index + 1}.pb", trace.bytes)
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
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val runtime = Runtime.getRuntime()
        val files = context.filesDir
        val microgStatus = MicrogContainerConfig(context).diagnosticStatus()
        val compatibility = DeviceCompatibility(context).report()
        return buildString {
            appendLine("generatedAt=${Instant.now()}")
            appendLine("timezone=${ZoneId.systemDefault().id}")
            appendLine("appVersionName=${packageInfo.versionName}")
            appendLine("appVersionCode=$versionCode")
            appendLine("buildType=${BuildConfig.BUILD_TYPE}")
            appendLine("package=${context.packageName}")
            appendLine("uid=${Process.myUid()}")
            appendLine("deviceManufacturer=${Build.MANUFACTURER}")
            appendLine("deviceBrand=${Build.BRAND}")
            appendLine("deviceModel=${Build.MODEL}")
            appendLine("deviceProduct=${Build.PRODUCT}")
            appendLine("deviceName=${Build.DEVICE}")
            appendLine("buildDisplay=${Build.DISPLAY}")
            appendLine("androidSdk=${Build.VERSION.SDK_INT}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("androidSecurityPatch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("abis64=${Build.SUPPORTED_64_BIT_ABIS.joinToString(",")}")
            appendLine("process64Bit=${compatibility.is64BitProcess}")
            appendLine("pageSizeBytes=${compatibility.pageSizeBytes}")
            appendLine("lowRamDevice=${compatibility.isLowRamDevice}")
            appendLine("ignoringBatteryOptimizations=${compatibility.isIgnoringBatteryOptimizations}")
            appendLine("compatibilitySupported=${compatibility.isSupported}")
            appendLine("compatibilityWarnings=${compatibility.warnings.joinToString("|")}")
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ExitDiagnostics(
                summary = "unavailable: historical process exit reasons require Android 11 or newer\n",
                traces = emptyList()
            )
        }
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
        val traces = mutableListOf<ExitTrace>()
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
                    val trace = runCatching {
                        reason.traceInputStream?.use { trace ->
                            trace.readBinaryAtMost(MAX_EXIT_TRACE_BYTES)
                        }
                    }.getOrNull()
                    if (trace?.exceededLimit == true) {
                        // 截断 protobuf 会得到无法解析的假栈，因此超限时只在摘要中说明。
                        appendLine("traceSkipped=exceeds-${MAX_EXIT_TRACE_BYTES}-bytes")
                    } else if (trace != null && trace.bytes.isNotEmpty()) {
                        traces += ExitTrace(trace.bytes.redactedBinaryCopy())
                        appendLine("traceFile=exit-trace-${traces.size}.pb")
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

    /**
     * 将已经完成等长脱敏的二进制诊断内容写入 ZIP。
     *
     * @param name ZIP 内的条目名称
     * @param bytes 保持 protobuf 字段边界的二进制内容
     */
    private fun ZipOutputStream.writeBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    /**
     * 生成用户可直接复制到 GitHub Issue 的结构化说明。
     *
     * @param containerStatus 当前容器状态。
     * @param activeTranslation 当前激活的汉化版本。
     * @return 不包含账号与本地路径的 Markdown 模板。
     */
    private fun issueTemplate(
        containerStatus: ContainerStatus,
        activeTranslation: PatchInstallSummary?
    ): String {
        val compatibility = DeviceCompatibility(context).report()
        return """
            ## 问题现象

            <!-- 请说明是无法安装、打开即闪退、同步失败、启动游戏失败、黑屏，还是汉化显示异常。 -->

            ## 复现步骤

            1.
            2.
            3.

            ## 设备信息

            - 机型：${compatibility.deviceLabel}
            - 系统：${compatibility.androidLabel}
            - ABI：${compatibility.abiLabel}
            - 内存页：${compatibility.pageSizeBytes} bytes
            - 容器状态：${containerStatus.message}
            - 游戏版本：${containerStatus.importedVersionCode ?: "未导入"}
            - 汉化版本：${activeTranslation?.version ?: "未激活"}
            - 兼容提醒：${compatibility.recommendation}

            ## 补充说明

            <!-- 请把本 ZIP 一并附加到 Issue。公开上传前仍建议检查是否包含不希望公开的信息。 -->
        """.trimIndent() + "\n"
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

    /**
     * 在不截断二进制格式的前提下读取单项诊断内容。
     *
     * @param limit 允许写入诊断包的最大字节数
     * @return 完整数据及是否超过上限；超限时返回的数据不会被写入 ZIP
     */
    private fun InputStream.readBinaryAtMost(limit: Int): BoundedBinary {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) {
                return BoundedBinary(output.toByteArray(), exceededLimit = false)
            }
            if (output.size() + count > limit) {
                return BoundedBinary(ByteArray(0), exceededLimit = true)
            }
            output.write(buffer, 0, count)
        }
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

    private data class ExitDiagnostics(val summary: String, val traces: List<ExitTrace>)

    private data class ExitTrace(val bytes: ByteArray)

    private data class BoundedBinary(val bytes: ByteArray, val exceededLimit: Boolean)

    companion object {
        private const val FILE_PREFIX = "limbus-diagnostics-"
        private const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L
        private const val MAX_LOGCAT_BYTES = 3 * 1024 * 1024
        private const val MAX_PERSISTENT_LOG_BYTES = 768 * 1024
        private const val MAX_PERSISTENT_LOG_FILES = 12
        private const val MAX_SUMMARY_BYTES = 64 * 1024
        private const val MAX_EXIT_SUMMARY_BYTES = 128 * 1024
        private const val MAX_EXIT_TRACE_BYTES = 512 * 1024
        private const val MAX_LOG_LINES = 6000
        private const val MAX_EXIT_REASONS = 16
        private const val MAX_EXIT_TRACES = 3
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val README = """
            Limbus Company 汉化器 Issue 诊断包

            内容：可复制的 Issue 模板、设备兼容信息、应用内滚动日志、本应用 UID 的近期日志、历史进程退出原因，以及等长脱敏的原生 tombstone protobuf。
            不包含：游戏资源、存档、PlayerPrefs、汉化渠道地址、Token 或账号配置。
            文本已经过自动脱敏，但公开上传前仍建议自行检查内容。
        """.trimIndent() + "\n"
    }
}

/**
 * 对 protobuf 等二进制内容中的可打印 ASCII 凭据执行等长覆盖。
 *
 * <p>只扫描连续可打印 ASCII 区间，避免正则跨过 protobuf 的字段键或长度字节；替换后的
 * 字节数与原始内容完全一致，因此地址、varint 和嵌套消息边界仍可被标准工具解析。</p>
 *
 * @return 保持原长度且已覆盖常见凭据、邮箱和 JWT 的新字节数组
 */
internal fun ByteArray.redactedBinaryCopy(): ByteArray {
    val result = copyOf()
    var start = 0
    while (start < result.size) {
        while (start < result.size && result[start].toInt() !in PRINTABLE_ASCII_RANGE) {
            start++
        }
        var end = start
        while (end < result.size && result[end].toInt() in PRINTABLE_ASCII_RANGE) {
            end++
        }
        if (end > start) {
            val segment = String(result, start, end - start, Charsets.US_ASCII)
            BINARY_SENSITIVE_PATTERNS.forEach { pattern ->
                pattern.findAll(segment).forEach { match ->
                    for (relativeIndex in match.range) {
                        result[start + relativeIndex] = REDACTED_BINARY_BYTE
                    }
                }
            }
        }
        start = if (end == start) start + 1 else end
    }
    return result
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
private val PRINTABLE_ASCII_RANGE = 0x20..0x7e
private const val REDACTED_BINARY_BYTE: Byte = 0x2a
private val BINARY_SENSITIVE_PATTERNS = listOf(
    URL_CREDENTIAL,
    CREDENTIAL_FIELD,
    FIREBASE_USER,
    JWT_VALUE,
    EMAIL_ADDRESS
)
