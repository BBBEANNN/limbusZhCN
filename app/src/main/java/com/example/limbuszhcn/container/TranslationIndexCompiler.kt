package com.example.limbuszhcn.container

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

data class TranslationIndexRecord(
    val path: String,
    val id: String,
    val field: String,
    val source: String,
    val translation: String
)

data class CompiledTranslationIndex(
    val version: String,
    val archiveSha256: String,
    val inputSha256: String,
    val uniqueEntries: Map<String, String>,
    val termEntries: Map<String, String>,
    val sourceRecordCount: Int,
    val conflictCount: Int,
    val dominantEntryCount: Int,
    val candidateSourceFileCount: Int = 0,
    val availableSourceFileCount: Int = 0
)

class TranslationIndexCompiler {
    private val records = mutableListOf<TranslationIndexRecord>()

    fun add(record: TranslationIndexRecord) {
        if (record.source.isBlank() || record.translation.isBlank() || record.source == record.translation) {
            return
        }
        records += record
    }

    fun compile(
        version: String,
        archiveSha256: String,
        candidateSourceFileCount: Int = 0,
        availableSourceFileCount: Int = 0
    ): CompiledTranslationIndex {
        val sorted = records.distinct().sortedWith(
            compareBy(
                TranslationIndexRecord::source,
                TranslationIndexRecord::translation,
                TranslationIndexRecord::path,
                TranslationIndexRecord::id,
                TranslationIndexRecord::field
            )
        )
        val bySource = sorted.groupBy(TranslationIndexRecord::source)
        val unique = linkedMapOf<String, String>()
        val terms = linkedMapOf<String, String>()
        var conflicts = 0
        var dominantEntries = 0
        bySource.toSortedMap().forEach { (source, occurrences) ->
            val translations = occurrences.groupingBy(TranslationIndexRecord::translation).eachCount()
            val resolved = resolveTranslation(translations)
            if (resolved != null) {
                unique[source] = resolved
                if (translations.size > 1) dominantEntries += 1
            } else {
                conflicts += 1
            }
        }
        val termCandidates = mutableListOf<Pair<String, String>>()
        sorted.forEach { record ->
            if (record.field in TERM_FIELDS) {
                normalizedTerms(record.source, record.translation).forEach(termCandidates::add)
            }
        }
        termCandidates.groupBy { it.first }.toSortedMap().forEach { (source, pairs) ->
            val translations = pairs.groupingBy { it.second }.eachCount()
            resolveTranslation(translations)?.let { terms[source] = it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        sorted.forEach { record ->
            listOf(record.path, record.id, record.field, record.source, record.translation).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update((bytes.size ushr 24).toByte())
                digest.update((bytes.size ushr 16).toByte())
                digest.update((bytes.size ushr 8).toByte())
                digest.update(bytes.size.toByte())
                digest.update(bytes)
            }
        }
        return CompiledTranslationIndex(
            version = version,
            archiveSha256 = archiveSha256,
            inputSha256 = digest.digest().toHex(),
            uniqueEntries = unique,
            termEntries = terms,
            sourceRecordCount = sorted.size,
            conflictCount = conflicts,
            dominantEntryCount = dominantEntries,
            candidateSourceFileCount = candidateSourceFileCount,
            availableSourceFileCount = availableSourceFileCount
        )
    }

    private fun resolveTranslation(counts: Map<String, Int>): String? {
        if (counts.size == 1) return counts.keys.single()
        val ranked = counts.entries.sortedByDescending(Map.Entry<String, Int>::value)
        val winner = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.value ?: 0
        val total = counts.values.sum()
        return winner.key.takeIf {
            winner.value >= 3 && winner.value * 5 >= total * 4 && winner.value >= runnerUp * 3
        }
    }

    private fun normalizedTerms(source: String, translation: String): Set<Pair<String, String>> {
        fun stripPairedBrackets(value: String): String =
            if (value.length >= 3 && value.first() == '[' && value.last() == ']') {
                value.substring(1, value.length - 1)
            } else {
                value
            }

        fun candidate(sourceValue: String, translationValue: String): Pair<String, String>? {
            val normalizedSource = stripPairedBrackets(sourceValue.trim())
            val normalizedTranslation = stripPairedBrackets(translationValue.trim())
            return (normalizedSource to normalizedTranslation).takeIf {
                normalizedSource.isTermLike() && normalizedTranslation.isTermLike() &&
                    normalizedSource != normalizedTranslation &&
                    !normalizedTranslation.contains(normalizedSource)
            }
        }

        return buildSet {
            candidate(source, translation)?.let(::add)
            candidate(
                RICH_TEXT_TAG.replace(source, ""),
                RICH_TEXT_TAG.replace(translation, "")
            )?.let(::add)
        }
    }

    private fun String.isTermLike(): Boolean {
        val codePoints = codePointCount(0, length)
        return codePoints in 2..16 && none {
            it.isWhitespace() || it == '<' || it == '>' || it == '{' || it == '}' || it == '%'
        }
    }

    companion object {
        private val TERM_FIELDS = setOf(
            "content", "name", "shortName", "abName", "title", "summary"
        )
        private val RICH_TEXT_TAG = Regex("<[^>]+>")
    }
}

class TranslationIndexStore(private val root: Path) {
    fun activate(index: CompiledTranslationIndex): Path {
        val versions = root.resolve("versions")
        versions.createDirectories()
        val directoryName =
            "${index.version.safePathSegment()}-s$INDEX_SCHEMA-${index.inputSha256.take(12)}"
        val versionDirectory = versions.resolve(directoryName)
        if (!versionDirectory.isDirectory()) {
            val staging = versions.resolve(".$directoryName.staging-${System.nanoTime()}")
            staging.createDirectories()
            writeIndex(staging.resolve(INDEX_FILE), index.uniqueEntries, index.termEntries)
            writeManifest(staging.resolve(MANIFEST_FILE), index)
            moveAtomically(staging, versionDirectory)
        }
        val indexFile = versionDirectory.resolve(INDEX_FILE).toAbsolutePath().normalize()
        require(Files.isRegularFile(indexFile)) { "Translation index is missing: $indexFile" }
        root.createDirectories()
        val pointerTemp = root.resolve(".$ACTIVE_POINTER.tmp-${System.nanoTime()}")
        pointerTemp.writeText(indexFile.toString().replace('\\', '/') + "\n", Charsets.UTF_8)
        moveAtomically(pointerTemp, root.resolve(ACTIVE_POINTER), replaceExisting = true)
        return indexFile
    }

    fun deactivate(): Boolean = Files.deleteIfExists(root.resolve(ACTIVE_POINTER))

    private fun writeIndex(
        path: Path,
        entries: Map<String, String>,
        terms: Map<String, String>
    ) {
        DataOutputStream(BufferedOutputStream(path.outputStream())).use { output ->
            output.write(INDEX_MAGIC)
            output.writeInt(INDEX_SCHEMA)
            output.writeInt(entries.size)
            output.writeInt(terms.size)
            entries.forEach { (source, translation) ->
                output.writeSizedUtf8(source)
                output.writeSizedUtf8(translation)
            }
            terms.forEach { (source, translation) ->
                output.writeSizedUtf8(source)
                output.writeSizedUtf8(translation)
            }
        }
    }

    private fun writeManifest(path: Path, index: CompiledTranslationIndex) {
        val properties = Properties().apply {
            setProperty("schema", INDEX_SCHEMA.toString())
            setProperty("version", index.version)
            setProperty("archiveSha256", index.archiveSha256)
            setProperty("inputSha256", index.inputSha256)
            setProperty("uniqueEntryCount", index.uniqueEntries.size.toString())
            setProperty("termEntryCount", index.termEntries.size.toString())
            setProperty("sourceRecordCount", index.sourceRecordCount.toString())
            setProperty("conflictCount", index.conflictCount.toString())
            setProperty("dominantEntryCount", index.dominantEntryCount.toString())
            setProperty("candidateSourceFileCount", index.candidateSourceFileCount.toString())
            setProperty("availableSourceFileCount", index.availableSourceFileCount.toString())
        }
        path.outputStream().use { properties.store(it, "Limbus translation index") }
    }

    private fun moveAtomically(source: Path, target: Path, replaceExisting: Boolean = false) {
        val options = mutableListOf(StandardCopyOption.ATOMIC_MOVE)
        if (replaceExisting) options += StandardCopyOption.REPLACE_EXISTING
        try {
            Files.move(source, target, *options.toTypedArray())
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source, target, *fallback)
        }
    }

    private fun DataOutputStream.writeSizedUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
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
        const val ACTIVE_POINTER = "active-index.path"
        const val INDEX_FILE = "index.bin"
        private const val MANIFEST_FILE = "manifest.properties"
        private const val INDEX_SCHEMA = 6
        private val INDEX_MAGIC = byteArrayOf('L'.code.toByte(), 'Z'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte(), 0)
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
