package com.example.limbuszhcn.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.readText

/** 验证递归显示字段收集、表内配对边界和不包含正文的覆盖审计。 */
class TranslationRecordCollectorTest {
    /** RPG 显示字段必须跟随本表 key/index 配对，不能因译文重排而串项。 */
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

    /** 有稳定 ID 的源缺项不得用译文的相同下标补齐，审计须保留该缺项。 */
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

    /** 源条目没有 ID 时，不能把同位置带 ID 的另一条译文借过来。 */
    @Test
    fun refusesAnonymousSourceWhenTranslationArrayHasStableIds() {
        val source = """{"dataList":[{"name":"名前A"}]}"""
        val target = """{"dataList":[{"id":2,"name":"名称B"}]}"""

        assertTrue(TranslationRecordCollector().collect("Test.json", source, target).isEmpty())
        val audit = TranslationRecordCollector(audit = true).collect("Test.json", source, target)
        assertEquals(1, audit.size)
        assertEquals("", audit.single().translation)
    }

    /** 混合标识数组中，匿名记录即使恰好仍处于匿名位置也不能据此猜测对应关系。 */
    @Test
    fun pairsOnlyIdentifiedRecordsWhenAnonymousRecordsMoveAroundStableIds() {
        val source = """{"dataList":[{"id":1,"name":"一"},{"name":"二"},{"name":"三"}]}"""
        val target = """{"dataList":[{"name":"第三"},{"id":1,"name":"第一"},{"name":"第二"}]}"""

        val records = TranslationRecordCollector().collect("Test.json", source, target)
        assertEquals(listOf("第一"), records.map { it.translation })
        val audit = TranslationRecordCollector(audit = true).collect("Test.json", source, target)
        assertEquals(listOf("第一", "", ""), audit.map { it.translation })
    }

    /** 没有稳定标识的对象或字符串数组一旦长度不同，生产收集器不能按下标猜译。 */
    @Test
    fun refusesPositionMatchingWhenAnonymousArrayLengthsDiffer() {
        val source = """{"details":[{"desc":"最初の説明"},{"desc":"次の説明"}],"dialog":["最初の話","次の話"]}"""
        val target = """{"details":[{"desc":"第二段说明"}],"dialog":["第二段对白"]}"""

        assertTrue(TranslationRecordCollector().collect("Test.json", source, target).isEmpty())
        val audit = TranslationRecordCollector(audit = true).collect("Test.json", source, target)
        assertEquals(4, audit.size)
        assertTrue(audit.all { it.translation.isEmpty() })
    }

    /** 同长且完全没有标识的对象和显示字符串数组继续支持原有位置配对。 */
    @Test
    fun pairsEquallySizedAnonymousArraysByPosition() {
        val source = """{"details":[{"desc":"最初の説明"},{"desc":"次の説明"}],"dialog":["最初の話","次の話"]}"""
        val target = """{"details":[{"desc":"第一段说明"},{"desc":"第二段说明"}],"dialog":["第一段对白","第二段对白"]}"""

        val records = TranslationRecordCollector().collect("Test.json", source, target)
        assertEquals(4, records.size)
        assertEquals(setOf("第一段说明", "第二段说明", "第一段对白", "第二段对白"),
            records.map { it.translation }.toSet())
    }

    /** 未知字符串数组也要发现含假名的潜在显示字段，但不得进入生产索引。 */
    @Test
    fun auditFindsUnreviewedJapaneseStringArraysIncludingMissingBranches() {
        val source = """{"newDisplayLines":["新しい説明","同じ説明","ascii-key"],"missingLines":["別の説明"]}"""
        val target = """{"newDisplayLines":["新的说明","同じ説明","other-key"]}"""

        assertTrue(TranslationRecordCollector().collect("Test.json", source, target).isEmpty())
        val audit = TranslationRecordCollector(audit = true).collect("Test.json", source, target)
        assertEquals(2, audit.size)
        assertEquals("新的说明", audit.single { it.field == "newDisplayLines" }.translation)
        assertEquals("", audit.single { it.field == "missingLines" }.translation)
        assertTrue(audit.all { it.id.endsWith("[0]") })
    }

    /** 重复 ID 组的两侧数量不同时，没有足够依据决定应该对应哪一项。 */
    @Test
    fun refusesAmbiguousDuplicateTranslationIds() {
        val source = """{"dataList":[{"id":1,"name":"一番"}]}"""
        val target = """{"dataList":[{"id":1,"name":"第一"},{"id":1,"name":"另一个"}]}"""
        assertTrue(TranslationRecordCollector().collect("Test.json", source, target).isEmpty())
    }

    /** 重复剧情 ID 只在同一父数组和同一个 ID 组内按出现次序配对。 */
    @Test
    fun pairsRepeatedStoryIdsByOccurrenceWithinTheSameIdGroup() {
        val source = """{"dataList":[{"id":-1,"content":"音一"},{"id":2,"content":"台詞"},{"id":-1,"content":"音二"}]}"""
        val target = """{"dataList":[{"id":2,"content":"对白"},{"id":-1,"content":"音效一"},{"id":-1,"content":"音效二"}]}"""
        val records = TranslationRecordCollector().collect("StoryData/Test.json", source, target)
        assertEquals(listOf("音效一", "对白", "音效二"), records.map { it.translation })
    }

    /** 嵌套硬币说明与明确的显示字符串数组都应递归进入索引，元数据应被排除。 */
    @Test
    fun collectsNestedCoinDescriptionsAndExplicitStringArrays() {
        val source = """{"dataList":[{"id":1,"levelList":[{"level":2,"coinlist":[{"coindescs":[{"desc":"一行目\n二行目"}]}]}],"dialog":["第一話","第二話"],"model":"not-display"}]}"""
        val target = """{"dataList":[{"id":1,"levelList":[{"level":2,"coinlist":[{"coindescs":[{"desc":"第一行\n第二行"}]}]}],"dialog":["第一话","第二话"],"model":"different-model"}]}"""

        val records = TranslationRecordCollector().collect("Test.json", source, target)

        assertEquals(3, records.size)
        assertEquals("第一行\n第二行", records.single { it.field == "desc" }.translation)
        assertFalse(records.any { it.field == "model" })
    }

    /** 整个译文分支缺失时，审计仍须遍历原始显示字段，避免静默丢失覆盖记录。 */
    @Test
    fun auditVisitsDisplayFieldsInsideEntirelyMissingBranches() {
        val source = """{"dataList":[{"id":1,"name":"一番","details":{"desc":"説明文"}}]}"""
        val records = TranslationRecordCollector(audit = true).collect("Test.json", source, "{}")
        assertEquals(setOf("name", "desc"), records.map { it.field }.toSet())
        assertTrue(records.all { it.translation.isEmpty() })
    }

    /** 覆盖报告只保留定位和哈希信息，不能复制游戏正文或未经脱敏的身份信息。 */
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
