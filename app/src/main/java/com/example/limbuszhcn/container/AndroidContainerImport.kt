package com.example.limbuszhcn.container

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class AndroidContainerImporter(
    private val contentResolver: ContentResolver,
    private val importDirectory: File
) {
    fun copyUris(uris: List<Uri>): List<String> {
        require(uris.isNotEmpty()) { "请选择 Limbus Company 的 split APK 文件" }
        resetImportDirectory()
        return uris.mapIndexed { index, uri ->
            val name = displayName(uri)?.takeIf { it.endsWith(".apk", ignoreCase = true) }
                ?: "split-$index.apk"
            val target = uniqueTarget(name)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取选择的文件: $uri" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.absolutePath
        }
    }

    private fun displayName(uri: Uri): String? {
        val cursor: Cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            return if (index >= 0) it.getString(index) else null
        }
    }

    private fun uniqueTarget(name: String): File {
        val safeName = name.replace(Regex("""[^\w.\-]"""), "_")
        var target = File(importDirectory, safeName)
        var suffix = 1
        while (target.exists()) {
            val dot = safeName.lastIndexOf('.')
            val nextName = if (dot > 0) {
                safeName.substring(0, dot) + "-$suffix" + safeName.substring(dot)
            } else {
                "$safeName-$suffix"
            }
            target = File(importDirectory, nextName)
            suffix += 1
        }
        return target
    }

    private fun resetImportDirectory() {
        if (importDirectory.exists()) {
            importDirectory.listFiles()?.forEach { file ->
                if (!file.deleteRecursively()) {
                    throw IllegalStateException("Failed to delete stale imported APK: ${file.absolutePath}")
                }
            }
        } else if (!importDirectory.mkdirs()) {
            throw IllegalStateException("Failed to create import directory: ${importDirectory.absolutePath}")
        }
    }
}
