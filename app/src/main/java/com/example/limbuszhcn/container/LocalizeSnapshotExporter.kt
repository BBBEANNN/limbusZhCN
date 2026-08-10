package com.example.limbuszhcn.container

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

class LocalizeSnapshotExporter(
    private val context: Context,
    private val storage: ContainerWorkspaceStorage
) {
    fun exportSnapshot(): LocalizeSnapshotExportResult {
        val root = Paths.get(storage.rootPath()).toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Container Localize root does not exist: $root" }

        val outputRoot = File(context.getExternalFilesDir(null), "diagnostics/localize-snapshots")
        if (!outputRoot.isDirectory && !outputRoot.mkdirs()) {
            error("Unable to create snapshot output directory: ${outputRoot.absolutePath}")
        }

        val stamp = LocalDateTime.now().format(STAMP_FORMAT)
        val zipFile = File(outputRoot, "localize-$stamp.zip")
        val summaryFile = File(outputRoot, "localize-$stamp.summary.txt")
        var fileCount = 0
        var jsonFileCount = 0
        var totalBytes = 0L

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() }
                    .sorted { left, right -> left.toString().compareTo(right.toString()) }
                    .forEach { file ->
                        val relative = file.relativeTo(root).toString().replace('\\', '/')
                        val size = Files.size(file)
                        zip.putNextEntry(ZipEntry(relative))
                        Files.newInputStream(file).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                        fileCount += 1
                        if (file.name.endsWith(".json", ignoreCase = true)) {
                            jsonFileCount += 1
                        }
                        totalBytes += size
                    }
            }
        }

        val probe = storage.probe()
        summaryFile.writeText(
            buildString {
                appendLine("root=${root.toString().replace('\\', '/')}")
                appendLine("zip=${zipFile.absolutePath}")
                appendLine("files=$fileCount")
                appendLine("jsonFiles=$jsonFileCount")
                appendLine("bytes=$totalBytes")
                appendLine("probe.exists=${probe.exists}")
                appendLine("probe.enJsonFileCount=${probe.jsonFileCount}")
                appendLine("probe.remoteListPresent=${probe.remoteListPresent}")
            },
            Charsets.UTF_8
        )
        Log.i(TAG, "exported localize snapshot zip=${zipFile.absolutePath} summary=${summaryFile.absolutePath} files=$fileCount json=$jsonFileCount bytes=$totalBytes")
        return LocalizeSnapshotExportResult(
            zipFile = zipFile,
            summaryFile = summaryFile,
            fileCount = fileCount,
            jsonFileCount = jsonFileCount,
            totalBytes = totalBytes
        )
    }

    companion object {
        private const val TAG = "LimbusLocalize"
        private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

data class LocalizeSnapshotExportResult(
    val zipFile: File,
    val summaryFile: File,
    val fileCount: Int,
    val jsonFileCount: Int,
    val totalBytes: Long
)
