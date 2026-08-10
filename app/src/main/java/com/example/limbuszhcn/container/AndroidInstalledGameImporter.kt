package com.example.limbuszhcn.container

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

class AndroidInstalledGameImporter(
    private val packageManager: PackageManager,
    private val importDirectory: File,
    private val packageName: String = LocalContainerRuntime.GAME_PACKAGE
) {
    fun copyInstalledGame(): InstalledGameImport {
        val appInfo = applicationInfo()
        val packageInfo = packageInfo(packageManager, packageName)
        val sourcePaths = listOfNotNull(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty().toList()
        require(sourcePaths.isNotEmpty()) { "未找到已安装游戏的 APK 路径" }

        resetImportDirectory()
        val copiedPaths = sourcePaths.mapIndexed { index, sourcePath ->
            val source = File(sourcePath)
            require(source.isFile) { "无法读取已安装游戏 APK: $sourcePath" }
            val target = File(importDirectory, source.targetName(index))
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.absolutePath
        }

        return InstalledGameImport(
            packageName = packageName,
            versionCode = packageInfo.versionCodeCompat(),
            splitApkPaths = copiedPaths
        )
    }

    private fun applicationInfo(): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }

    private fun File.targetName(index: Int): String {
        val name = name.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: if (index == 0) "base.apk" else "split-$index.apk"
        return name.replace(Regex("""[^\w.\-]"""), "_")
    }

    private fun resetImportDirectory() {
        if (importDirectory.exists()) {
            // This only clears the host-side temporary APK copy cache. Never use
            // this path to clear VirtualApp game data or downloaded resources.
            importDirectory.listFiles()?.forEach { file ->
                if (!file.deleteRecursively()) {
                    throw IllegalStateException("Failed to delete stale imported APK: ${file.absolutePath}")
                }
            }
        } else if (!importDirectory.mkdirs()) {
            throw IllegalStateException("Failed to create import directory: ${importDirectory.absolutePath}")
        }
    }

    companion object {
        fun installedVersionCode(
            packageManager: PackageManager,
            packageName: String = LocalContainerRuntime.GAME_PACKAGE
        ): Long = packageInfo(packageManager, packageName).versionCodeCompat()

        private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }

        private fun PackageInfo.versionCodeCompat(): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode
            } else {
                @Suppress("DEPRECATION")
                versionCode.toLong()
            }
    }
}

data class InstalledGameImport(
    val packageName: String,
    val versionCode: Long,
    val splitApkPaths: List<String>
)
