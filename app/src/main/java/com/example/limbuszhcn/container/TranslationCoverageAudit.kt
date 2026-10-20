package com.example.limbuszhcn.container

import com.example.limbuszhcn.redactedAndBounded
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** 仅开发诊断使用的逐条覆盖清单。正文只存哈希，不写入游戏资源或用户配置。 */
internal class TranslationCoverageAudit(directory: Path) : Closeable {
    private val root = directory.also { Files.createDirectories(it) }
    private val writer = Files.newBufferedWriter(root.resolve("records.tsv"), Charsets.UTF_8)
    private val counts = sortedMapOf<String, Long>()
    private var bytes = 0
    private var omitted = 0L

    init {
        writer.appendLine("resource\tcontext\tfield\tstatus\tsourceHash64\ttranslationHash64")
    }

    fun file(path: String, status: String) = row(path, "", "", status, "", "")

    fun record(record: TranslationIndexRecord, index: CompiledTranslationIndex) {
        val status = when {
            !TranslationTextPolicy.isDisplayField(record.field) -> "unreviewed_field"
            record.source.isBlank() -> "empty_source"
            record.translation.isBlank() -> "missing_translation"
            !TranslationTextPolicy.isUsableDisplayText(record.source) ||
                !TranslationTextPolicy.isUsableDisplayText(record.translation) -> "invalid_text"
            index.uniqueEntries[record.source] == record.translation ->
                if (record.source == record.translation) "protected_identity" else "exact"
            index.uniqueEntries[record.source] != null -> "resolved_to_other_translation"
            record.source == record.translation -> "unchanged"
            else -> "ambiguous_source"
        }
        row(record.path, record.id, record.field, status, hash(record.source), hash(record.translation))
    }

    private fun row(path: String, context: String, field: String, status: String, source: String, target: String) {
        counts[status] = (counts[status] ?: 0) + 1
        val line = listOf(path, context, field, status, source, target).joinToString("\t") {
            it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').redactedAndBounded(1024)
        } + "\n"
        val size = line.toByteArray(Charsets.UTF_8).size
        if (bytes + size <= MAX_BYTES) {
            writer.append(line)
            bytes += size
        } else {
            omitted += 1
        }
    }

    override fun close() {
        writer.close()
        val summary = Properties().apply {
            counts.forEach { (status, count) -> setProperty(status, count.toString()) }
            setProperty("omittedRows", omitted.toString())
            setProperty("recordsBytes", bytes.toString())
            setProperty("containsTextBodies", "false")
        }
        Files.newOutputStream(root.resolve("summary.properties")).use { summary.store(it, "Translation coverage audit") }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
