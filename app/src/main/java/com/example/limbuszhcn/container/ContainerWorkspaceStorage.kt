package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameStorageProbe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.stream.Collectors
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream

class ContainerWorkspaceStorage(
    private val localizeRoot: Path
) : GameStorage {
    override fun probe(): GameStorageProbe {
        val en = localizeRoot.resolve("en")
        if (!Files.isDirectory(en)) {
            return GameStorageProbe(exists = false, jsonFileCount = 0, remoteListPresent = false)
        }
        val jsonCount = Files.walk(en).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(".json") }.count().toInt()
        }
        return GameStorageProbe(
            exists = true,
            jsonFileCount = jsonCount,
            remoteListPresent = localizeRoot.resolve("RemoteLocalizeFileList.json").isRegularFile()
        )
    }

    override fun exists(path: String): Boolean =
        resolveAbsolute(path).exists()

    override fun read(path: String): ByteArray? {
        val target = resolveAbsolute(path)
        return if (target.isRegularFile()) {
            Files.readAllBytes(target)
        } else {
            null
        }
    }

    override fun write(path: String, source: Path) {
        val target = resolveAbsolute(path)
        target.parent.createDirectories()
        val temp = target.parent.resolve("${target.fileName}.tmp-${System.currentTimeMillis()}")
        source.inputStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        Files.move(
            temp,
            target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    override fun delete(path: String): Boolean =
        Files.deleteIfExists(resolveAbsolute(path))

    override fun deleteResource(relativePath: String): Boolean =
        Files.deleteIfExists(resolveResourceAbsolute(relativePath))

    override fun resolvePatchTarget(targetPath: String): String {
        val relative = targetPath.removePrefix("en/")
        val slash = relative.lastIndexOf('/')
        val prefixedRelative = if (slash >= 0) {
            relative.substring(0, slash + 1) + "EN_" + relative.substring(slash + 1)
        } else {
            "EN_$relative"
        }
        val prefixed = rootPath().trimEnd('/') + "/en/" + prefixedRelative
        return if (exists(prefixed)) {
            prefixed
        } else {
            rootPath().trimEnd('/') + "/" + targetPath
        }
    }

    override fun rootPath(): String =
        localizeRoot.toAbsolutePath().normalize().toString().replace('\\', '/')

    private fun resolveAbsolute(path: String): Path {
        val root = localizeRoot.toAbsolutePath().normalize()
        val absolute = if (path.startsWith(rootPath())) {
            root.resolve(path.removePrefix(rootPath()).removePrefix("/"))
        } else {
            root.resolve(path.removePrefix("/"))
        }.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            throw IOException("Rejected path outside container Localize root: $path")
        }
        return absolute
    }

    private fun resolveResourceAbsolute(path: String): Path {
        val root = localizeRoot.parent.toAbsolutePath().normalize()
        val absolute = root.resolve(path.removePrefix("/")).toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            throw IOException("Rejected path outside container resource root: $path")
        }
        return absolute
    }
}

class LocalContainerRuntime(
    private val workspaceRoot: Path
) : ContainerRuntime {
    private val metadataFile = workspaceRoot.resolve("container-state.properties")
    private val localizeRoot = workspaceRoot
        .resolve("virtual-data")
        .resolve(GAME_PACKAGE)
        .resolve("files")
        .resolve("Assets")
        .resolve("Resources_moved")
        .resolve("Localize")

    override fun status(): ContainerStatus {
        val metadata = readMetadata()
        val imported = metadata != null
        val importedSplits = metadata?.getProperty("importedSplits")?.toIntOrNull() ?: 0
        val versionCode = metadata?.getProperty("versionCode")?.toLongOrNull()
        val source = metadata?.getProperty("source")
        return ContainerStatus(
            available = true,
            gameImported = imported,
            message = if (imported) {
                buildString {
                    append("容器工作区已导入游戏")
                    if (versionCode != null) append(" v").append(versionCode)
                    if (importedSplits > 0) append(", ").append(importedSplits).append(" 个 split")
                }
            } else {
                "容器工作区待导入游戏"
            },
            importedSplits = importedSplits,
            importedVersionCode = versionCode,
            importSource = source
        )
    }

    override fun importGame(request: GameImportRequest): GameImportResult {
        require(request.splitApkPaths.isNotEmpty()) { "需要至少一个 split APK 路径" }
        val splitDir = workspaceRoot.resolve("splits")
        splitDir.createDirectories()
        Files.list(splitDir).use { paths ->
            paths
                .filter { it.isRegularFile() && it.name.endsWith(".apk", ignoreCase = true) }
                .forEach { Files.deleteIfExists(it) }
        }
        request.splitApkPaths.forEach { rawPath ->
            val source = java.io.File(rawPath).toPath()
            require(source.isRegularFile()) { "Split APK does not exist: $rawPath" }
            Files.copy(
                source,
                splitDir.resolve(source.fileName),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
        localizeRoot.resolve("en").createDirectories()
        metadataFile.parent.createDirectories()
        val metadata = Properties()
        metadata.setProperty("packageName", GAME_PACKAGE)
        metadata.setProperty("importedSplits", request.splitApkPaths.size.toString())
        metadata.setProperty("source", request.source)
        request.versionCode?.let { metadata.setProperty("versionCode", it.toString()) }
        metadataFile.outputStream().use { output ->
            metadata.store(output, "Limbus container import state")
        }
        return GameImportResult(
            importedSplits = request.splitApkPaths.size,
            packageName = GAME_PACKAGE,
            versionCode = request.versionCode,
            source = request.source
        )
    }

    override fun storage(): GameStorage =
        ContainerWorkspaceStorage(localizeRoot)

    fun importedSplitApkPaths(): List<String> {
        val splitDir = workspaceRoot.resolve("splits")
        if (!splitDir.exists()) {
            return emptyList()
        }
        return Files.list(splitDir).use { paths ->
            paths
                .filter { it.isRegularFile() && it.name.endsWith(".apk", ignoreCase = true) }
                .sorted { left, right ->
                    fun rank(path: Path): Int =
                        if (path.name.equals("base.apk", ignoreCase = true)) 0 else 1
                    compareBy<Path> { rank(it) }.thenBy { it.name }.compare(left, right)
                }
                .map { it.toAbsolutePath().normalize().toString() }
                .collect(Collectors.toList())
        }
    }

    override fun launchGame() {
        throw ContainerRuntimeUnavailableException("真实容器运行时尚未接入;当前仅完成工作区与文件系统 Spike")
    }

    private fun readMetadata(): Properties? {
        if (!metadataFile.isRegularFile()) {
            return null
        }
        return Properties().also { metadata ->
            metadataFile.inputStream().use { input -> metadata.load(input) }
        }
    }

    companion object {
        const val GAME_PACKAGE = "com.ProjectMoon.LimbusCompany"
    }
}
