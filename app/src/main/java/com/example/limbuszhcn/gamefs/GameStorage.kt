package com.example.limbuszhcn.gamefs

import com.example.limbuszhcn.update.PatchManifest
import java.nio.file.Path
import kotlin.io.path.inputStream

interface GameStorage {
    fun probe(): GameStorageProbe
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray?
    fun write(path: String, source: Path)
    fun delete(path: String): Boolean
    fun resolvePatchTarget(targetPath: String): String
    fun rootPath(): String
    fun deleteResource(relativePath: String): Boolean = false
}

data class GameStorageProbe(
    val exists: Boolean,
    val jsonFileCount: Int,
    val remoteListPresent: Boolean
)

data class PatchInstallSummary(
    val version: String,
    val writtenFiles: Int,
    val indexActivated: Boolean = true,
    val candidateSourceFiles: Int = writtenFiles,
    val availableSourceFiles: Int = writtenFiles,
    val sourceRecordCount: Int = 0,
    val previousIndexPreserved: Boolean = false
)

data class PatchUninstallSummary(
    val version: String,
    val deletedFiles: Int
)

class GameStorageInstaller(
    private val storage: GameStorage
) {
    fun install(patchDirectory: Path, manifest: PatchManifest): PatchInstallSummary {
        var written = 0
        manifest.entries.forEach { entry ->
            val source = patchDirectory.resolve(entry.cachePath)
            storage.write(storage.resolvePatchTarget(entry.targetPath), source)
            written += 1
        }
        return PatchInstallSummary(version = manifest.requestedVersion, writtenFiles = written)
    }

    fun uninstall(manifest: PatchManifest): PatchUninstallSummary {
        val targets = linkedSetOf<String>()
        manifest.entries.forEach { entry ->
            targets += storage.resolvePatchTarget(entry.targetPath)
            targets += storage.rootPath().trimEnd('/') + "/" + entry.targetPath
        }

        var deleted = 0
        targets.forEach { target ->
            if (storage.exists(target) && storage.delete(target)) {
                deleted += 1
            }
        }
        return PatchUninstallSummary(version = manifest.requestedVersion, deletedFiles = deleted)
    }
}

fun readFileBytes(path: Path): ByteArray =
    path.inputStream().use { it.readBytes() }
