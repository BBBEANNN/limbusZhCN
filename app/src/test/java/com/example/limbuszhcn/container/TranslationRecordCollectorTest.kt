package com.example.limbuszhcn.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.readText

class TranslationRecordCollectorTest {
    @Test
    fun pairsRpgItemKeysAndCollectsReviewedRpgFields() {
        val source = """{"items":[{"key":"a","displayName":"紙切れ","statText":"体力+1"},{"key":"b","displayName":"衣服"}],"dialogue":[{"index":1,"speaker":"ダンテ","text":"こんにちは"}],"quests":[{"index":2,"goalDescription1":"探索する","goalDescription8":"完了する"}]}"""
        val target = """{"items":[{"key":"b","displayName":"衣服"},{"key":"a","displayName":"纸片","statText":"体力+1"}],"dialogue":[{"index":1,"speaker":"但丁","text":"你好"}],"quests":[{"index":2,"goalDescription1":"探索","goalDescription8":"完成"}]}"""
        val records = TranslationRecordCollector().collect("RPGSystem/Test.json", source, target)
        assertEquals("纸片", records.single { it.field == "displayName" && "key=a" in it.id }.translation)
        assertEquals("但丁", records.single { it.field == "speaker" }.translation)
        assertEquals("完成", records.single { it.field == "goalDescription8" }.translation)
        assertEquals(7, records.size)
        assertFalse(records.any { it.field == "key" || it.field == "index" })
    }

    @Test
    fun pairsReorderedIdsAndNeverSubstitutesMissingIdsByPosition() {
        val source = """{"dataList":[{"id":1,"name":"一番"},{"id":2,"name":"二番"},{"id":3,"name":"三番"}]}"""
        val target = """{"dataList":[{"id":2,"name":"第二"},{"id":1,"name":"第一"},{"id":4,"name":"第四"}]}"""

        val records = TranslationRecordCollector().collect("jp/JP_Test.json", source, target)

        assertEquals(listOf("第一", "第二"), records.map { it.translation })
        assertFalse(records.any { "id=3" in it.id })
        val audit = TranslationRecordCollector(audit = true).collect("jp/JP_Test.json", source, target)
        assertEquals("", audit.single { "id=3" in it.id }.translation)
    }

    @Test
    fun refusesAmbiguousDuplicateTranslationIds() {
        val source = """{"dataList":[{"id":1,"name":"一番"}]}"""
        val target = """{"dataList":[{"id":1,"name":"第一"},{"id":1,"name":"另一个"}]}"""
        assertTrue(TranslationRecordCollector().collect("Test.json", source, target).isEmpty())
    }

    @Test
    fun pairsRepeatedStoryIdsByOccurrenceWithinTheSameIdGroup() {
        val source = """{"dataList":[{"id":-1,"content":"音一"},{"id":2,"content":"台詞"},{"id":-1,"content":"音二"}]}"""
        val target = """{"dataList":[{"id":2,"content":"对白"},{"id":-1,"content":"音效一"},{"id":-1,"content":"音效二"}]}"""
        val records = TranslationRecordCollector().collect("StoryData/Test.json", source, target)
        assertEquals(listOf("音效一", "对白", "音效二"), records.map { it.translation })
    }

    @Test
    fun collectsNestedCoinDescriptionsAndExplicitStringArrays() {
        val source = """{"dataList":[{"id":1,"levelList":[{"level":2,"coinlist":[{"coindescs":[{"desc":"一行目\n二行目"}]}]}],"dialog":["第一話","第二話"],"model":"not-display"}]}"""
        val target = """{"dataList":[{"id":1,"levelList":[{"level":2,"coinlist":[{"coindescs":[{"desc":"第一行\n第二行"}]}]}],"dialog":["第一话","第二话"],"model":"different-model"}]}"""

        val records = TranslationRecordCollector().collect("Test.json", source, target)

        assertEquals(3, records.size)
        assertEquals("第一行\n第二行", records.single { it.field == "desc" }.translation)
        assertFalse(records.any { it.field == "model" })
    }

    @Test
    fun auditVisitsDisplayFieldsInsideEntirelyMissingBranches() {
        val source = """{"dataList":[{"id":1,"name":"一番","details":{"desc":"説明文"}}]}"""
        val records = TranslationRecordCollector(audit = true).collect("Test.json", source, "{}")
        assertEquals(setOf("name", "desc"), records.map { it.field }.toSet())
        assertTrue(records.all { it.translation.isEmpty() })
    }

    @Test
    fun auditRecordsCoverageWithoutCopyingGameTextOrCredentials() {
        val root = Files.createTempDirectory("translation-audit-test")
        val record = TranslationIndexRecord("jp/Test.json", "id=user@example.com", "name", "日本語本文", "中文正文")
        val compiler = TranslationIndexCompiler().apply { add(record) }
        TranslationCoverageAudit(root).use { audit ->
            audit.record(record, compiler.compile("test", "archive"))
            audit.file("jp/Missing.json", "missing_source_file")
        }
        val report = root.resolve("records.tsv").readText()
        assertTrue(report.contains("\texact\t"))
        assertFalse(report.contains("日本語本文"))
        assertFalse(report.contains("中文正文"))
        assertFalse(report.contains("user@example.com"))
        val summary = Properties().apply { Files.newInputStream(root.resolve("summary.properties")).use(::load) }
        assertEquals("1", summary.getProperty("exact"))
        assertEquals("1", summary.getProperty("missing_source_file"))
        assertEquals("0", summary.getProperty("omittedRows"))
    }
}
