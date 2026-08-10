package com.example.limbuszhcn.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText

class TranslationIndexCompilerTest {
    @Test
    fun keepsOnlyUnambiguousSourceMappingsInFastIndex() {
        val compiler = TranslationIndexCompiler()
        compiler.add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        compiler.add(record("OtherUIText.json", "2", "content", "Sinner", "罪人"))
        compiler.add(record("Battle.json", "3", "dialog", "Ready", "准备"))
        compiler.add(record("Story.json", "4", "dialog", "Ready", "出发"))
        compiler.add(record("Same.json", "5", "teller", "Dante", "Dante"))

        val compiled = compiler.compile("2026071001", "archive")

        assertEquals(mapOf("Sinner" to "罪人"), compiled.uniqueEntries)
        assertEquals(mapOf("Sinner" to "罪人"), compiled.termEntries)
        assertEquals(4, compiled.sourceRecordCount)
        assertEquals(1, compiled.conflictCount)
        assertEquals(0, compiled.dominantEntryCount)
        assertEquals(64, compiled.inputSha256.length)
    }

    @Test
    fun resolvesDominantTerminologyAndExtractsBracketedTerms() {
        val compiler = TranslationIndexCompiler()
        repeat(10) { index ->
            compiler.add(record("Main$index.json", index.toString(), "content", "囚人", "罪人"))
        }
        compiler.add(record("Story.json", "other", "title", "囚人", "罪人们"))
        compiler.add(record("Keyword.json", "before", "content", "[攻撃前]", "[攻击前]"))
        compiler.add(record("Keyword.json", "tremor", "name", "振動", "震颤"))
        compiler.add(record("Keyword.json", "above", "content", "以上", "或以上"))
        compiler.add(record(
            "UnitKeyword.json",
            "sister",
            "content",
            "<color=#d40000><s>長姉</s></color>",
            "<color=#d40000><s>长姊</s></color>"
        ))

        val compiled = compiler.compile("2026071001", "archive")

        assertEquals("罪人", compiled.uniqueEntries["囚人"])
        assertEquals("罪人", compiled.termEntries["囚人"])
        assertEquals("攻击前", compiled.termEntries["攻撃前"])
        assertEquals("震颤", compiled.termEntries["振動"])
        assertEquals("长姊", compiled.termEntries["長姉"])
        assertFalse(compiled.termEntries.containsKey("以上"))
        assertEquals(1, compiled.dominantEntryCount)
    }

    @Test
    fun activatesImmutableBinaryIndexAndPointer() {
        val root = Files.createTempDirectory("limbus-index-test")
        val compiler = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        }
        val compiled = compiler.compile("2026071001", "archive-sha")

        val indexFile = TranslationIndexStore(root).activate(compiled)

        assertTrue(indexFile.exists())
        assertTrue(indexFile.parent.fileName.toString().contains("-s6-"))
        assertEquals(indexFile.toString().replace('\\', '/'), root.resolve("active-index.path").readText().trim())
        DataInputStream(indexFile.inputStream()).use { input ->
            assertEquals("LZTI1\u0000", String(input.readNBytes(6), Charsets.US_ASCII))
            assertEquals(6, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals("Sinner", input.readSizedUtf8())
            assertEquals("罪人", input.readSizedUtf8())
            assertEquals("Sinner", input.readSizedUtf8())
            assertEquals("罪人", input.readSizedUtf8())
        }
        val properties = Properties().apply {
            indexFile.parent.resolve("manifest.properties").inputStream().use(::load)
        }
        assertEquals("1", properties.getProperty("uniqueEntryCount"))
        assertEquals("1", properties.getProperty("termEntryCount"))
        assertEquals("1", properties.getProperty("sourceRecordCount"))
        assertEquals("0", properties.getProperty("conflictCount"))
    }

    @Test
    fun deactivationOnlyRemovesActivePointer() {
        val root = Files.createTempDirectory("limbus-index-test")
        val compiled = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        }.compile("2026071001", "archive")
        val store = TranslationIndexStore(root)
        val indexFile = store.activate(compiled)

        assertTrue(store.deactivate())

        assertFalse(root.resolve("active-index.path").exists())
        assertTrue(indexFile.exists())
    }

    @Test
    fun requiresNinetyPercentOfJapaneseSourcesBeforeActivation() {
        assertTrue(hasSufficientJapaneseSources(90, 100))
        assertTrue(hasSufficientJapaneseSources(9, 10))
        assertFalse(hasSufficientJapaneseSources(89, 100))
        assertFalse(hasSufficientJapaneseSources(0, 0))
    }

    private fun record(
        path: String,
        id: String,
        field: String,
        source: String,
        translation: String
    ) = TranslationIndexRecord(path, id, field, source, translation)

    private fun DataInputStream.readSizedUtf8(): String {
        val size = readInt()
        return String(readNBytes(size), Charsets.UTF_8)
    }
}
