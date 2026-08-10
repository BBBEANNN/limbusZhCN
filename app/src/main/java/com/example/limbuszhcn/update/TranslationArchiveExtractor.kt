package com.example.limbuszhcn.update

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream

class TranslationArchiveExtractor(
    private val limits: ExtractionLimits = ExtractionLimits()
) {
    fun extract(request: ExtractionRequest): ExtractionResult {
        require(request.archive.isRegularFile()) { "Archive does not exist: ${request.archive}" }
        request.cacheRoot.createDirectories()

        val archiveSha256 = sha256(request.archive)
        if (request.expectedSha256 != null && archiveSha256 != request.expectedSha256) {
            throw ArchiveValidationException(
                "Archive SHA-256 mismatch: expected ${request.expectedSha256}, actual $archiveSha256"
            )
        }

        val tempDir = request.cacheRoot.resolve("${request.version}.tmp")
        val finalDir = request.cacheRoot.resolve(request.version)
        resetDirectory(tempDir)

        val filesDir = tempDir.resolve("files").resolve("en")
        filesDir.createDirectories()

        val entries = mutableListOf<PatchFileEntry>()
        var archiveFileCount = 0
        var totalUncompressedBytes = 0L
        var versionFromArchive: Long? = null

        try {
            SevenZFile(request.archive.toFile()).use { archive ->
                var entry = archive.nextEntry
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (entry != null) {
                    if (!entry.isDirectory) {
                        archiveFileCount += 1
                        validateEntryMethods(entry)

                        val sourcePath = entry.name ?: throw ArchiveValidationException("Archive entry has no name")
                        val mapped = mapEntry(sourcePath)
                        if (mapped != null) {
                            val entrySize = entry.size
                            validateSize(sourcePath, entrySize)
                            totalUncompressedBytes += entrySize
                            if (totalUncompressedBytes > limits.maxTotalUncompressedBytes) {
                                throw ArchiveValidationException(
                                    "Archive uncompressed size exceeds ${limits.maxTotalUncompressedBytes} bytes"
                                )
                            }

                            val outputPath = tempDir.resolve(mapped.cachePath)
                            outputPath.parent.createDirectories()
                            copyEntry(archive, outputPath, buffer)

                            val fileSha256 = sha256(outputPath)
                            if (mapped.isVersionFile) {
                                versionFromArchive = readVersion(outputPath)
                            } else {
                                entries += PatchFileEntry(
                                    sourcePath = sourcePath,
                                    cachePath = mapped.cachePath,
                                    targetPath = mapped.targetPath,
                                    size = Files.size(outputPath),
                                    sha256 = fileSha256
                                )
                            }
                        }
                    }
                    entry = archive.nextEntry
                }
            }

            if (archiveFileCount > limits.maxArchiveFiles) {
                throw ArchiveValidationException("Archive file count exceeds ${limits.maxArchiveFiles}")
            }
            if (entries.isEmpty()) {
                throw ArchiveValidationException("Archive contains no patchable JSON files")
            }

            val manifest = PatchManifest(
                requestedVersion = request.version,
                archiveVersion = versionFromArchive,
                archiveSha256 = archiveSha256,
                archiveFileCount = archiveFileCount,
                extractedFileCount = entries.size,
                entries = entries.sortedBy { it.targetPath }
            )
            writeManifest(tempDir.resolve(MANIFEST_FILE), manifest)

            if (finalDir.exists()) {
                deleteDirectory(finalDir)
            }
            tempDir.moveTo(finalDir)

            return ExtractionResult(
                outputDirectory = finalDir,
                manifest = manifest
            )
        } catch (error: Throwable) {
            deleteDirectory(tempDir)
            throw error
        }
    }

    private fun validateEntryMethods(entry: SevenZArchiveEntry) {
        val methods = entry.contentMethods?.map { it.method } ?: emptyList()
        if (methods.isEmpty()) {
            return
        }
        val unsupported = methods.filterNot { it in ALLOWED_METHODS }
        if (unsupported.isNotEmpty()) {
            throw ArchiveValidationException(
                "Unsupported 7z method for ${entry.name}: ${unsupported.joinToString()}"
            )
        }
        if (SevenZMethod.LZMA2 !in methods && SevenZMethod.COPY !in methods) {
            throw ArchiveValidationException("Entry ${entry.name} does not use an allowed content compressor")
        }
    }

    private fun validateSize(sourcePath: String, entrySize: Long) {
        if (entrySize < 0) {
            throw ArchiveValidationException("Unknown entry size: $sourcePath")
        }
        if (entrySize > limits.maxSingleFileBytes) {
            throw ArchiveValidationException("Entry is too large: $sourcePath ($entrySize bytes)")
        }
    }

    private fun mapEntry(sourcePath: String): MappedEntry? {
        if (!sourcePath.startsWith(UPSTREAM_PREFIX)) {
            return null
        }

        val relative = sourcePath.removePrefix(UPSTREAM_PREFIX)
        if (relative.isBlank()) {
            return null
        }

        val normalizedText = normalizeArchiveRelativePath(relative)
        if (normalizedText == null) {
            throw ArchiveValidationException("Unsafe archive path: $sourcePath")
        }

        if (normalizedText == VERSION_ENTRY) {
            return MappedEntry(
                cachePath = "info/version.json",
                targetPath = "",
                isVersionFile = true
            )
        }

        if (!normalizedText.endsWith(".json") || normalizedText.startsWith("Info/")) {
            return null
        }

        val gameRelative = withGameEnglishPrefix(normalizedText)
        return MappedEntry(
            cachePath = "files/en/$gameRelative",
            targetPath = "en/$gameRelative",
            isVersionFile = false
        )
    }

    private fun withGameEnglishPrefix(path: String): String {
        val slash = path.lastIndexOf('/')
        val directory = if (slash >= 0) path.substring(0, slash + 1) else ""
        val fileName = if (slash >= 0) path.substring(slash + 1) else path
        return directory + if (fileName.startsWith("EN_")) fileName else "EN_$fileName"
    }

    private fun normalizeArchiveRelativePath(path: String): String? {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains('\u0000')) {
            return null
        }
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (parts.isEmpty()) {
                        return null
                    }
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun copyEntry(archive: SevenZFile, outputPath: Path, buffer: ByteArray) {
        BufferedOutputStream(outputPath.outputStream()).use { output ->
            while (true) {
                val read = archive.read(buffer)
                if (read < 0) {
                    break
                }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun readVersion(path: Path): Long? {
        val text = String(Files.readAllBytes(path), Charsets.UTF_8)
        return VERSION_REGEX.find(text)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun writeManifest(path: Path, manifest: PatchManifest) {
        path.parent.createDirectories()
        Files.write(path, manifest.toJson().toByteArray(Charsets.UTF_8))
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resetDirectory(path: Path) {
        if (path.exists()) {
            deleteDirectory(path)
        }
        path.createDirectories()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun deleteDirectory(path: Path) {
        if (path.exists()) {
            path.deleteRecursively()
        }
    }

    private data class MappedEntry(
        val cachePath: String,
        val targetPath: String,
        val isVersionFile: Boolean
    )

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        private const val UPSTREAM_PREFIX = "LimbusCompany_Data/Lang/LLC_zh-CN/"
        private const val VERSION_ENTRY = "Info/version.json"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private val VERSION_REGEX = Regex(""""version"\s*:\s*(\d+)""")
        private val ALLOWED_METHODS = setOf(
            SevenZMethod.COPY,
            SevenZMethod.LZMA2,
            SevenZMethod.BCJ_X86_FILTER
        )
    }
}

data class ExtractionRequest(
    val archive: Path,
    val cacheRoot: Path,
    val version: String,
    val expectedSha256: String? = null
)

data class ExtractionLimits(
    val maxArchiveFiles: Int = 5_000,
    val maxSingleFileBytes: Long = 2L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 128L * 1024L * 1024L
)

data class ExtractionResult(
    val outputDirectory: Path,
    val manifest: PatchManifest
)

data class PatchManifest(
    val requestedVersion: String,
    val archiveVersion: Long?,
    val archiveSha256: String,
    val archiveFileCount: Int,
    val extractedFileCount: Int,
    val entries: List<PatchFileEntry>
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"requestedVersion\": ${requestedVersion.jsonValue()},")
        appendLine("  \"archiveVersion\": ${archiveVersion?.toString() ?: "null"},")
        appendLine("  \"archiveSha256\": ${archiveSha256.jsonValue()},")
        appendLine("  \"archiveFileCount\": $archiveFileCount,")
        appendLine("  \"extractedFileCount\": $extractedFileCount,")
        appendLine("  \"entries\": [")
        entries.forEachIndexed { index, entry ->
            append(entry.toJson("    "))
            if (index != entries.lastIndex) {
                appendLine(",")
            } else {
                appendLine()
            }
        }
        appendLine("  ]")
        appendLine("}")
    }

    companion object {
        fun fromJson(json: String): PatchManifest {
            val entries = ENTRY_REGEX.findAll(json).map { match ->
                PatchFileEntry(
                    sourcePath = match.groupValues[1].jsonStringValue(),
                    cachePath = match.groupValues[2].jsonStringValue(),
                    targetPath = match.groupValues[3].jsonStringValue(),
                    size = match.groupValues[4].toLong(),
                    sha256 = match.groupValues[5].jsonStringValue()
                )
            }.toList()
            return PatchManifest(
                requestedVersion = json.requiredString("requestedVersion"),
                archiveVersion = json.nullableLong("archiveVersion"),
                archiveSha256 = json.requiredString("archiveSha256"),
                archiveFileCount = json.requiredLong("archiveFileCount").toInt(),
                extractedFileCount = json.requiredLong("extractedFileCount").toInt(),
                entries = entries
            )
        }

        private val ENTRY_REGEX = Regex(
            """"sourcePath"\s*:\s*"((?:\\.|[^"\\])*)"\s*,\s*""" +
                """"cachePath"\s*:\s*"((?:\\.|[^"\\])*)"\s*,\s*""" +
                """"targetPath"\s*:\s*"((?:\\.|[^"\\])*)"\s*,\s*""" +
                """"size"\s*:\s*(\d+)\s*,\s*""" +
                """"sha256"\s*:\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}

data class PatchFileEntry(
    val sourcePath: String,
    val cachePath: String,
    val targetPath: String,
    val size: Long,
    val sha256: String
) {
    fun toJson(indent: String): String = buildString {
        appendLine("$indent{")
        appendLine("$indent  \"sourcePath\": ${sourcePath.jsonValue()},")
        appendLine("$indent  \"cachePath\": ${cachePath.jsonValue()},")
        appendLine("$indent  \"targetPath\": ${targetPath.jsonValue()},")
        appendLine("$indent  \"size\": $size,")
        appendLine("$indent  \"sha256\": ${sha256.jsonValue()}")
        append("$indent}")
    }
}

class ArchiveValidationException(message: String) : IOException(message)

private fun String.requiredString(field: String): String =
    Regex(""""${Regex.escape(field)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.jsonStringValue()
        ?: throw ArchiveValidationException("Missing manifest field: $field")

private fun String.requiredLong(field: String): Long =
    Regex(""""${Regex.escape(field)}"\s*:\s*(\d+)""")
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?: throw ArchiveValidationException("Missing manifest field: $field")

private fun String.nullableLong(field: String): Long? {
    if (Regex(""""${Regex.escape(field)}"\s*:\s*null""").containsMatchIn(this)) {
        return null
    }
    return requiredLong(field)
}

private fun String.jsonStringValue(): String = buildString {
    var index = 0
    while (index < this@jsonStringValue.length) {
        val char = this@jsonStringValue[index]
        if (char != '\\') {
            append(char)
            index += 1
            continue
        }
        val escaped = this@jsonStringValue.getOrNull(index + 1)
            ?: throw ArchiveValidationException("Invalid JSON escape")
        when (escaped) {
            '"', '\\', '/' -> append(escaped)
            'b' -> append('\b')
            'f' -> append('\u000C')
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'u' -> {
                val code = this@jsonStringValue.substring(index + 2, index + 6).toInt(16)
                append(code.toChar())
                index += 4
            }
            else -> throw ArchiveValidationException("Unsupported JSON escape: \\$escaped")
        }
        index += 2
    }
}
