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

/**
 * 表示一条从官方日文显示字段到中文显示字段的结构化配对记录。
 *
 * @property path 译文资源在汉化包内的相对路径。
 * @property id 由对象稳定标识与递归路径组成的诊断上下文。
 * @property field 已通过显示文本白名单的字段名。
 * @property source 容器内官方日文原文。
 * @property translation 与原文结构对齐的中文译文。
 */
internal data class TranslationIndexRecord(
    val path: String,
    val id: String,
    val field: String,
    val source: String,
    val translation: String
)

/**
 * 保存一次日文单源索引编译的不可变结果与完整性统计。
 *
 * @property version 汉化包版本。
 * @property archiveSha256 汉化归档的 SHA-256。
 * @property inputSha256 所有有效配对记录的确定性摘要。
 * @property uniqueEntries 无歧义或已满足优势阈值的完整文本映射。
 * @property termEntries 可幂等地嵌入格式化文本的短术语映射。
 * @property sourceRecordCount 参与本次编译的去重源记录数量。
 * @property conflictCount 因译文冲突而未进入完整索引的原文数量。
 * @property dominantEntryCount 通过优势阈值解决冲突的原文数量。
 * @property candidateSourceFileCount 汉化包中候选 JSON 文件数量。
 * @property availableSourceFileCount 容器中可读取日文对应文件的数量。
 */
internal data class CompiledTranslationIndex(
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

/**
 * 将结构化的日中字段配对编译为确定性的完整文本索引与短术语索引。
 */
internal class TranslationIndexCompiler {
    private val records = mutableListOf<TranslationIndexRecord>()

    /** 保存一条由下载包配对记录派生的短术语及其来源优先级。 */
    private data class TermCandidate(
        val source: String,
        val translation: String,
        val priority: Int
    )

    /**
     * 添加一条通过字段与编码质量校验的配对记录。
     *
     * @param record 待验证的日中显示文本记录。
     */
    fun add(record: TranslationIndexRecord) {
        if (
            !TranslationTextPolicy.isDisplayField(record.field) ||
            record.source.isBlank() ||
            record.translation.isBlank() ||
            record.source == record.translation ||
            !TranslationTextPolicy.isUsableDisplayText(record.source) ||
            !TranslationTextPolicy.isUsableDisplayText(record.translation)
        ) {
            return
        }
        // 不维护宿主自定义译名；有效译文原样来自用户已下载并校验的汉化包。
        records += record
    }

    /**
     * 解析冲突、生成幂等术语并计算输入摘要。
     *
     * @param version 汉化包版本。
     * @param archiveSha256 汉化归档 SHA-256。
     * @param candidateSourceFileCount 候选译文 JSON 数量。
     * @param availableSourceFileCount 已找到官方日文对应文件的数量。
     * @return 可写入 schema 8 二进制文件的不可变索引。
     */
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
        val termCandidates = mutableListOf<TermCandidate>()
        sorted.forEach { record ->
            val priority = termSourcePriority(record.path)
            if (TranslationTextPolicy.isTermField(record.field)) {
                normalizedTerms(record.source, record.translation).forEach { (source, translation) ->
                    termCandidates += TermCandidate(source, translation, priority)
                }
            }
            if (TranslationTextPolicy.isStructuredDescriptionField(record.field)) {
                normalizedLeadingClauseTerms(record.source, record.translation)
                    .forEach { (source, translation) ->
                        termCandidates += TermCandidate(source, translation, priority)
                    }
            }
        }
        termCandidates.groupBy(TermCandidate::source).toSortedMap().forEach { (source, candidates) ->
            val highestPriority = candidates.maxOf(TermCandidate::priority)
            val preferred = candidates.filter { candidate -> candidate.priority == highestPriority }
            val translations = preferred.groupingBy(TermCandidate::translation).eachCount()
            resolveTranslation(translations)?.let { translation ->
                terms[source] = translation
                if (highestPriority == CANONICAL_GLOSSARY_PRIORITY) {
                    // TMP 最终只提供字符串、没有资源 ID。发生同文异译时，以下载包的
                    // Buff/战斗关键词词典作为界面格式化后的唯一权威译名。
                    unique[source] = translation
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateDigest(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        sorted.forEach { record ->
            listOf(record.path, record.id, record.field, record.source, record.translation)
                .forEach(::updateDigest)
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

    private fun termSourcePriority(path: String): Int {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return when {
            fileName.startsWith("bufs") || fileName.startsWith("battlekeywords") ->
                CANONICAL_GLOSSARY_PRIORITY
            "keyword" in fileName -> 2
            else -> 1
        }
    }

    private fun normalizedTerms(source: String, translation: String): Set<Pair<String, String>> {
        return buildSet {
            candidateTerm(source, translation)?.let(::add)
            candidateTerm(
                RICH_TEXT_TAG.replace(source, ""),
                RICH_TEXT_TAG.replace(translation, "")
            )?.let(::add)
            val sourceBracketTerms = SQUARE_BRACKET_CONTENT.findAll(source)
                .map { match -> match.groupValues[1] }
                .toList()
            val translationBracketTerms = SQUARE_BRACKET_CONTENT.findAll(translation)
                .map { match -> match.groupValues[1] }
                .toList()
            if (sourceBracketTerms.size == translationBracketTerms.size) {
                // 游戏格式化器可能只输出方括号内部的可见名称。同步派生内部术语，
                // 让所有同类标签在格式化后仍能二次汉化，而不是只修某个页面或角色。
                sourceBracketTerms.zip(translationBracketTerms).forEach { (sourceTerm, translationTerm) ->
                    candidateTerm(sourceTerm, translationTerm)?.let(::add)
                }
            }
        }
    }

    private fun normalizedLeadingClauseTerms(
        source: String,
        translation: String
    ): Set<Pair<String, String>> {
        val sourceLines = RICH_TEXT_TAG.replace(source, "").lines()
        val translationLines = RICH_TEXT_TAG.replace(translation, "").lines()
        if (sourceLines.size != translationLines.size) return emptySet()
        return buildSet {
            sourceLines.zip(translationLines).forEach { (sourceLine, translationLine) ->
                // 技能说明通常以“条件，效果”逐行对齐。只取第一个标点前的条件，
                // 译名完全来自下载包，供游戏把标签格式化成日文后再次命中。
                val sourceClause = sourceLine.leadingClause()
                val translationClause = translationLine.leadingClause()
                if (sourceClause != null && translationClause != null) {
                    candidateTerm(sourceClause, translationClause)?.let(::add)
                }
            }
        }
    }

    private fun candidateTerm(
        sourceValue: String,
        translationValue: String
    ): Pair<String, String>? {
        val normalizedSource = sourceValue.trim().stripPairedBrackets()
        val normalizedTranslation = translationValue.trim().stripPairedBrackets()
        return (normalizedSource to normalizedTranslation).takeIf {
            normalizedSource.isTermLike(minimumCodePoints = 2) &&
                normalizedTranslation.isTermLike(minimumCodePoints = 1) &&
                normalizedSource != normalizedTranslation
        }
    }

    private fun String.leadingClause(): String? {
        val normalized = replace(LEADING_BULLET, "").trim()
        val delimiter = normalized.indexOfFirst { character -> character in CLAUSE_DELIMITERS }
        if (delimiter !in 2..32) return null
        return normalized.substring(0, delimiter)
    }

    private fun String.stripPairedBrackets(): String =
        if (length >= 3 && first() == '[' && last() == ']') {
            substring(1, length - 1)
        } else {
            this
        }

    private fun String.isTermLike(minimumCodePoints: Int): Boolean {
        val codePoints = codePointCount(0, length)
        return codePoints in minimumCodePoints..16 && none {
            it.isWhitespace() || it == '<' || it == '>' || it == '{' || it == '}' || it == '%'
        }
    }

    companion object {
        private val RICH_TEXT_TAG = Regex("<[^>]+>")
        private val SQUARE_BRACKET_CONTENT = Regex("\\[([^\\[\\]]+)]")
        private val LEADING_BULLET = Regex("^\\s*[-·•]\\s*")
        private val CLAUSE_DELIMITERS = setOf('、', '，', ',', '：', ':')
        private const val CANONICAL_GLOSSARY_PRIORITY = 3
    }
}

/**
 * 以不可变版本目录和原子指针发布运行时翻译索引。
 *
 * @param root 宿主私有的 `translation-index` 根目录。
 */
internal class TranslationIndexStore(private val root: Path) {
    /**
     * 写入或复用指定版本索引，并原子切换当前生效指针。
     *
     * @param index 已完成冲突与质量校验的编译结果。
     * @return 当前激活的二进制索引绝对路径。
     */
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

    /**
     * 仅移除当前索引指针，保留不可变版本目录以便回退。
     *
     * @return 指针存在且已删除时返回 `true`。
     */
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
        // schema 8 会强制重建旧索引，使包内词典优先级与条件术语派生立即生效。
        private const val INDEX_SCHEMA = 8
        private val INDEX_MAGIC = byteArrayOf('L'.code.toByte(), 'Z'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte(), 0)
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
