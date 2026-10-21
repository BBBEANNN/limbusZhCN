package com.example.limbuszhcn.container

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * 管理容器所需 microG 的来源、私有暂存和完整性校验，供首次导入与后续启动复用。
 *
 * @param context 用于访问宿主私有存储、内置资产和包解析器的应用上下文。
 */
class MicrogContainerConfig(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * 暂存调用方已选定的外部 APK，并记录供容器安装使用的本地路径。
     *
     * @param sourceApks 需要复制到宿主私有目录的 microG APK 文件。
     */
    fun configure(sourceApks: List<File>) {
        require(sourceApks.isNotEmpty()) { "microG requires at least one APK" }
        val stagedApks = stageMicrogApks(sourceApks)
        saveStagedApks(stagedApks, SOURCE_EXTERNAL)
    }

    /**
     * 为已有的兼容或调试入口下载并暂存指定 APK。
     *
     * @param sourceUrls 由调用方验证并提供的 APK 下载地址列表。
     */
    fun configureFromUrls(sourceUrls: List<String>) {
        require(sourceUrls.isNotEmpty()) { "microG requires at least one APK URL" }
        val stageDirectory = prepareStageDirectory()
        val stagedApks = sourceUrls.mapIndexed { index, sourceUrl ->
            val fileName = sourceUrl.substringAfterLast('/').takeIf { it.endsWith(".apk") }
                ?: "microg-$index.apk"
            val target = File(stageDirectory, "%02d-%s".format(index, fileName))
            URL(sourceUrl).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
            }.getInputStream().use { input ->
                target.outputStream().use(input::copyTo)
            }
            target
        }
        saveStagedApks(stagedApks, SOURCE_EXTERNAL)
    }

    /**
     * 取得可安装的 microG APK，首次使用时从内置资产合并并校验完整安装包。
     *
     * @return 已暂存且按文件名排序的 APK 列表。
     */
    fun microgApks(): List<File> {
        val configured = preferences.getStringSet(KEY_MICROG_APKS, emptySet())
            .orEmpty()
            .map(::File)
            .filter(File::isFile)
            .sortedBy(File::getName)
        val configuredSource = preferences.getString(KEY_SOURCE, null)
        if (configuredSource?.startsWith(SOURCE_BUNDLED_PREFIX) == true) {
            val manifest = loadBundledManifest()
            val expectedSource = SOURCE_BUNDLED_PREFIX + manifest.bundleVersion
            val expectedNames = manifest.entries.map(BundledMicrogEntry::assetName).toSet()
            if (
                configuredSource == expectedSource &&
                configured.map(File::getName).toSet() == expectedNames
            ) {
                return configured
            }
            return stageBundledMicrogApks(manifest)
        }
        if (configured.isNotEmpty()) {
            return configured
        }
        return stageBundledMicrogApks(loadBundledManifest())
    }

    /**
     * 查询旧版 Google 服务配置是否已经完成容器迁移。
     *
     * @return 已持久化完成标记时为 true。
     */
    fun migrationCompleted(): Boolean = preferences.getBoolean(KEY_MIGRATION_COMPLETED, false)

    /**
     * 汇总配置来源和 APK 可用数量，供诊断页面展示而不暴露路径正文。
     *
     * @return 当前配置及迁移状态摘要。
     */
    fun diagnosticStatus(): MicrogConfigStatus {
        val configuredPaths = preferences.getStringSet(KEY_MICROG_APKS, emptySet()).orEmpty()
        return MicrogConfigStatus(
            source = preferences.getString(KEY_SOURCE, null)
                ?: if (configuredPaths.isEmpty()) "unconfigured" else "legacy-external",
            configuredApkCount = configuredPaths.size,
            availableApkCount = configuredPaths.count { File(it).isFile },
            migrationCompleted = migrationCompleted()
        )
    }

    /** 标记容器迁移已经完成，并同步落盘以免下次启动重复执行。 */
    fun markMigrationCompleted() {
        preferences.edit().putBoolean(KEY_MIGRATION_COMPLETED, true).commit()
    }

    private fun stageMicrogApks(sourceApks: List<File>): List<File> {
        val stageDirectory = prepareStageDirectory()
        return sourceApks.mapIndexed { index, source ->
            require(source.isFile) { "microG APK is missing: ${source.absolutePath}" }
            val target = File(stageDirectory, "%02d-%s".format(index, source.name))
            source.inputStream().use { input ->
                target.outputStream().use(input::copyTo)
            }
            target
        }
    }

    private fun stageBundledMicrogApks(manifest: BundledMicrogManifest): List<File> {
        val stageDirectory = File(
            context.filesDir,
            "$BUNDLED_STAGE_DIRECTORY/${manifest.bundleVersion.safePathSegment()}"
        )
        require(stageDirectory.isDirectory || stageDirectory.mkdirs()) {
            "Unable to create ${stageDirectory.absolutePath}"
        }
        val stagedApks = manifest.entries.map { entry ->
            val target = File(stageDirectory, entry.assetName)
            val temporary = File(stageDirectory, ".${entry.assetName}.tmp-${System.nanoTime()}")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                temporary.outputStream().use { output ->
                    entry.assetParts.forEach { assetPart ->
                        context.assets.open("$BUNDLED_ASSET_DIRECTORY/$assetPart").use { input ->
                            copyAndDigest(input, output, digest)
                        }
                    }
                }
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
            val actualSha256 = digest.digest().toHex()
            if (actualSha256 != entry.sha256) {
                temporary.delete()
                error(
                    "Bundled microG digest mismatch asset=${entry.assetName} " +
                        "expected=${entry.sha256} actual=$actualSha256"
                )
            }
            moveReplacing(temporary, target)
            verifyBundledApk(target, entry)
            target
        }
        saveStagedApks(
            stagedApks,
            SOURCE_BUNDLED_PREFIX + manifest.bundleVersion
        )
        return stagedApks.sortedBy(File::getName)
    }

    private fun copyAndDigest(input: InputStream, output: java.io.OutputStream, digest: MessageDigest) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
    }

    private fun loadBundledManifest(): BundledMicrogManifest =
        context.assets.open(BUNDLED_MANIFEST_ASSET).use(::parseBundledMicrogManifest)

    /**
     * 按当前系统支持的包信息接口核对内置 APK，旧系统同样必须验证签名。
     *
     * @param apk 已完成整包 SHA-256 校验的暂存 APK。
     * @param entry 内置清单中固定的包名、版本与证书摘要。
     */
    @Suppress("DEPRECATION")
    private fun verifyBundledApk(apk: File, entry: BundledMicrogEntry) {
        // Android 8/8.1 尚无 SigningInfo；用旧接口获取同一 APK 的签名，避免首次启动崩溃。
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            flags
        ) ?: error("Unable to parse bundled microG APK: ${entry.assetName}")
        require(info.packageName == entry.packageName) {
            "Bundled microG package mismatch asset=${entry.assetName} expected=${entry.packageName} actual=${info.packageName}"
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        require(versionCode == entry.versionCode) {
            "Bundled microG version mismatch package=${entry.packageName} expected=${entry.versionCode} actual=$versionCode"
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireNotNull(info.signingInfo) {
                "Bundled microG signing info missing package=${entry.packageName}"
            }.apkContentsSigners
        } else {
            info.signatures
        }
        // 两个系统分支使用同一证书摘要检查，缺失签名也必须拒绝安装。
        val certificateDigests = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }
        require(entry.certificateSha256 in certificateDigests) {
            "Bundled microG certificate mismatch package=${entry.packageName}"
        }
    }

    private fun prepareStageDirectory(): File {
        val stageDirectory = File(context.filesDir, MICROG_STAGE_DIRECTORY)
        if (!stageDirectory.exists()) {
            check(stageDirectory.mkdirs()) { "Unable to create ${stageDirectory.absolutePath}" }
        }
        stageDirectory.listFiles()?.forEach(File::delete)
        return stageDirectory
    }

    private fun saveStagedApks(stagedApks: List<File>, source: String) {
        preferences.edit()
            .putStringSet(KEY_MICROG_APKS, stagedApks.map(File::getAbsolutePath).toSet())
            .putString(KEY_SOURCE, source)
            .putBoolean(KEY_MIGRATION_COMPLETED, false)
            .commit()
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun String.safePathSegment(): String {
        val safe = map { character ->
            if (character.isLetterOrDigit() || character == '.' || character == '-' || character == '_') {
                character
            } else {
                '_'
            }
        }.joinToString("")
        return safe.ifBlank { "unknown" }
    }

    companion object {
        /** 保存 Google 服务容器配置的宿主私有偏好文件名。 */
        const val PREFERENCES_NAME = "limbus_google_auth"
        /** 已暂存的 microG APK 绝对路径集合键。 */
        const val KEY_MICROG_APKS = "microg_apks"
        /** APK 来源标识键，用于区分内置资源与已有外部配置。 */
        const val KEY_SOURCE = "microg_source"
        private const val KEY_MIGRATION_COMPLETED = "migration_completed"
        private const val MICROG_STAGE_DIRECTORY = "container-google/microg"
        private const val BUNDLED_STAGE_DIRECTORY = "container-google/microg-bundled"
        private const val BUNDLED_ASSET_DIRECTORY = "microg"
        private const val BUNDLED_MANIFEST_ASSET = "$BUNDLED_ASSET_DIRECTORY/manifest.properties"
        private const val SOURCE_EXTERNAL = "external"
        private const val SOURCE_BUNDLED_PREFIX = "bundled:"
    }
}

internal data class BundledMicrogManifest(
    val bundleVersion: String,
    val entries: List<BundledMicrogEntry>
)

/**
 * 向诊断界面提供 microG 配置摘要，不包含 APK 内容或敏感设置。
 *
 * @property source 当前 APK 配置来源标识。
 * @property configuredApkCount 已记录的 APK 路径数量。
 * @property availableApkCount 当前仍可读取的 APK 数量。
 * @property migrationCompleted 旧配置是否已完成容器迁移。
 */
data class MicrogConfigStatus(
    val source: String,
    val configuredApkCount: Int,
    val availableApkCount: Int,
    val migrationCompleted: Boolean
)

internal data class BundledMicrogEntry(
    val assetName: String,
    val assetParts: List<String>,
    val packageName: String,
    val versionCode: Long,
    val sha256: String,
    val certificateSha256: String
)

internal fun parseBundledMicrogManifest(input: InputStream): BundledMicrogManifest {
    val properties = Properties().apply { load(input) }
    val bundleVersion = properties.getProperty("bundleVersion")?.trim().orEmpty()
    require(bundleVersion.isNotBlank()) { "Bundled microG manifest has no bundleVersion" }
    val assetNames = properties.getProperty("entries")
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    require(assetNames.isNotEmpty()) { "Bundled microG manifest has no entries" }
    require(assetNames.distinct().size == assetNames.size) { "Bundled microG manifest has duplicate entries" }
    val entries = assetNames.map { assetName ->
        require(assetName.endsWith(".apk") && '/' !in assetName && '\\' !in assetName && ".." !in assetName) {
            "Invalid bundled microG asset name: $assetName"
        }
        val prefix = "$assetName."
        val assetParts = properties.getProperty(prefix + "parts")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
            .ifEmpty { listOf(assetName) }
        require(assetParts.distinct().size == assetParts.size) {
            "Bundled microG asset has duplicate parts: $assetName"
        }
        assetParts.forEach { partName ->
            require('/' !in partName && '\\' !in partName && ".." !in partName) {
                "Invalid bundled microG asset part: $partName"
            }
        }
        val packageName = properties.requiredValue(prefix + "packageName")
        val versionCode = properties.requiredValue(prefix + "versionCode").toLongOrNull()
            ?: error("Invalid bundled microG versionCode for $assetName")
        val sha256 = properties.requiredSha256(prefix + "sha256")
        val certificateSha256 = properties.requiredSha256(prefix + "certificateSha256")
        BundledMicrogEntry(assetName, assetParts, packageName, versionCode, sha256, certificateSha256)
    }
    require(entries.any { it.packageName == "com.google.android.gms" }) {
        "Bundled microG manifest does not contain com.google.android.gms"
    }
    return BundledMicrogManifest(bundleVersion, entries)
}

private fun Properties.requiredValue(key: String): String =
    getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Bundled microG manifest is missing $key")

private fun Properties.requiredSha256(key: String): String =
    requiredValue(key).lowercase().also { value ->
        require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $key" }
    }

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
