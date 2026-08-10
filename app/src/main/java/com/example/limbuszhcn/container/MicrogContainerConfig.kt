package com.example.limbuszhcn.container

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

class MicrogContainerConfig(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun configure(sourceApks: List<File>) {
        require(sourceApks.isNotEmpty()) { "microG requires at least one APK" }
        val stagedApks = stageMicrogApks(sourceApks)
        saveStagedApks(stagedApks, SOURCE_EXTERNAL)
    }

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

    fun migrationCompleted(): Boolean = preferences.getBoolean(KEY_MIGRATION_COMPLETED, false)

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

    private fun verifyBundledApk(apk: File, entry: BundledMicrogEntry) {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: error("Unable to parse bundled microG APK: ${entry.assetName}")
        require(info.packageName == entry.packageName) {
            "Bundled microG package mismatch asset=${entry.assetName} expected=${entry.packageName} actual=${info.packageName}"
        }
        require(info.longVersionCode == entry.versionCode) {
            "Bundled microG version mismatch package=${entry.packageName} expected=${entry.versionCode} actual=${info.longVersionCode}"
        }
        val signingInfo = requireNotNull(info.signingInfo) {
            "Bundled microG signing info missing package=${entry.packageName}"
        }
        val certificateDigests = signingInfo.apkContentsSigners.map { signature ->
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
        const val PREFERENCES_NAME = "limbus_google_auth"
        const val KEY_MICROG_APKS = "microg_apks"
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
