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
    fun keepsPunctuationTranslationsExactWithoutRewritingCodesAsEmbeddedTerms() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("UI.json", "question", "content", "??", "？？"))
            add(record("UI.json", "ellipsis", "content", "...。", "…"))
        }
        val compiled = compiler.compile("test", "archive")
        assertEquals("？？", compiled.uniqueEntries["??"])
        assertEquals("…", compiled.uniqueEntries["...。"])
        assertTrue(compiled.termEntries.isEmpty())
    }

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
        assertEquals("罪人", compiled.termEntries["Sinner"])
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
        compiler.add(record(
            "Keyword.json",
            "formatted-bracket-name",
            "name",
            "代行[テスト指令]",
            "代行[测试指令]"
        ))

        val compiled = compiler.compile("2026071001", "archive")

        assertEquals("罪人", compiled.uniqueEntries["囚人"])
        assertEquals("罪人", compiled.termEntries["囚人"])
        assertEquals("攻击前", compiled.termEntries["攻撃前"])
        assertEquals("震颤", compiled.termEntries["振動"])
        assertEquals("长姊", compiled.termEntries["長姉"])
        assertEquals("测试指令", compiled.termEntries["テスト指令"])
        assertEquals("或以上", compiled.termEntries["以上"])
        assertEquals(1, compiled.dominantEntryCount)
    }

    /** 验证剧情、章节关联文本和解锁编号均进入 schema 8 显示字段策略。 */
    @Test
    fun includesVerifiedStoryAndUnlockDisplayFields() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("Guide.json", "story", "story", "記録を読む。", "阅读记录。"))
            add(record("Unlock.json", "chapter", "relatedChapterText", "第九章", "第九章"))
            add(record("Unlock.json", "condition", "openConditionNumber", "九・五―十四", "9.5-14"))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertEquals("阅读记录。", compiled.uniqueEntries["記録を読む。"])
        assertEquals("9.5-14", compiled.uniqueEntries["九・五―十四"])
        assertFalse(compiled.uniqueEntries.containsKey("第九章"))
        assertTrue(TranslationTextPolicy.isDisplayField("story"))
        assertTrue(TranslationTextPolicy.isDisplayField("relatedChapterText"))
        assertTrue(TranslationTextPolicy.isDisplayField("openConditionNumber"))
    }

    /** 验证确定损坏的 Unicode/control 文本被跳过，而剧情符号文本仍被保留。 */
    @Test
    fun rejectsDefinitivelyCorruptedDisplayText() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("Broken.json", "replacement", "content", "壊れた一", "损坏\uFFFD文本"))
            add(record("Broken.json", "control", "content", "壊れた二", "损坏\u0085文本"))
            add(record("Good.json", "symbol", "content", "信号", "?!>@!#$@!?!>#!#!"))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertFalse(compiled.uniqueEntries.containsKey("壊れた一"))
        assertFalse(compiled.uniqueEntries.containsKey("壊れた二"))
        assertEquals("?!>@!#$@!?!>#!#!", compiled.uniqueEntries["信号"])
    }

    /** 验证格式化术语全部从下载包派生，并优先采用包内关键词词典。 */
    @Test
    fun derivesFormattedTermsFromDownloadedGlossaries() {
        val compiler = TranslationIndexCompiler().apply {
            // 普通页面即使重复使用同一日文，也不能压过 Bufs 中的正式关键词译名。
            repeat(4) { index ->
                add(record("Passives.json", "passive-$index", "name", "呼吸", "喘息未定"))
            }
            add(record("Bufs.json", "Breath", "name", "呼吸", "呼吸法"))
            add(record("Bufs-a1c9p1.json", "KarmaOfIndexAlly", "name", "カルマ", "业"))
            add(record(
                "Bufs-a1c9p3.json",
                "StackRienSpecialSkill",
                "name",
                "代行[ヘルメス]",
                "代行[赫尔墨斯]"
            ))
            add(record(
                "Bufs-a1c9p3.json",
                "coin-condition",
                "desc",
                "- 打撃ダメージを与える\n- 破壊されずに命中時、自身に呼吸を2付与",
                "- 造成打击伤害\n- 未摧毁并命中时，使自身获得2层呼吸法"
            ))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertEquals("呼吸法", compiled.uniqueEntries["呼吸"])
        assertEquals("呼吸法", compiled.termEntries["呼吸"])
        assertEquals("业", compiled.termEntries["カルマ"])
        assertEquals("赫尔墨斯", compiled.termEntries["ヘルメス"])
        assertEquals("未摧毁并命中时", compiled.termEntries["破壊されずに命中時"])
        assertFalse(compiled.termEntries.containsKey("喘息未定"))
    }

    /** 验证生产 jp/JP_ 路径下词典权威优先级依然压过页面译名。 */
    @Test
    fun appliesGlossaryPriorityToProductionJapaneseSourcePaths() {
        val compiler = TranslationIndexCompiler().apply {
            repeat(4) { index ->
                add(record("jp/JP_Passives.json", "passive-$index", "name", "呼吸", "喘息未定"))
            }
            add(record("jp/JP_Bufs.json", "Breath", "name", "呼吸", "呼吸法"))
            add(record("jp/JP_LoginUIText.json", "retry", "content", "リトライ", "重试"))
            add(record("jp/JP_RailwayDungeonUI_2.json", "retry", "content", "リトライ", "再次挑战"))
            add(record("jp/JP_BattleKeywords.json", "retry", "name", "リトライ", "重试"))
        }

        val compiled = compiler.compile("2026102001", "archive")

        assertEquals("呼吸法", compiled.uniqueEntries["呼吸"])
        assertEquals("呼吸法", compiled.termEntries["呼吸"])
        assertFalse(compiled.termEntries.containsKey("喘息未定"))
        assertEquals("重试", compiled.uniqueEntries["リトライ"])
    }

    /** 同字词典记录仍有语义：不能把技能“保存”替换为地牢 UI 的“检查点”。 */
    @Test
    fun protectsUnchangedGlossaryTermsFromUiHomographs() {
        val compiler = TranslationIndexCompiler().apply {
            repeat(4) { index ->
                add(record("jp/JP_StoryDungeonUI.json", "save-$index", "content", "保存", "检查点"))
            }
            add(record("jp/JP_Bufs-a1c10p1.json", "ChargeNoir", "name", "保存", "保存"))
            add(record("jp/JP_BattleKeywords.json", "ChargeNoirAlly", "name", "保存", "保存"))
            add(record("jp/JP_Bufs.json", "field", "name", "保存神域", "保存神域"))
            add(record("jp/JP_OtherUIText.json", "field", "content", "保存神域", "检查点神域"))
            add(record("jp/JP_Bufs.json", "long-field", "name", "保存神域 全体効果", "保存神域 全体効果"))
            add(record("jp/JP_OtherUIText.json", "long-field", "content", "保存神域 全体効果", "检查点神域"))
        }

        val compiled = compiler.compile("2026102001", "archive")

        assertEquals("保存", compiled.uniqueEntries["保存"])
        assertEquals("保存", compiled.termEntries["保存"])
        assertEquals("保存神域", compiled.uniqueEntries["保存神域"])
        assertEquals("保存神域", compiled.termEntries["保存神域"])
        assertEquals("保存神域 全体効果", compiled.uniqueEntries["保存神域 全体効果"])
    }

    /** 验证 2:1 多数派在放宽阈值后直接采纳，1:1 与 2:2 平票仍被丢弃。 */
    @Test
    fun adoptsTwoThirdsMajorityTranslations() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("jp/JP_LoginUIText.json", "retry", "content", "リトライ", "重试"))
            add(record("jp/JP_MainUIText.json", "retry", "content", "リトライ", "重试"))
            add(record("jp/JP_RailwayDungeonUI_2.json", "retry", "content", "リトライ", "再次挑战"))
            add(record("jp/JP_Battle.json", "one", "content", "撤退", "退场"))
            add(record("jp/JP_Story.json", "two", "content", "撤退", "撤退失败"))
            add(record("jp/JP_StageA.json", "a", "content", "準備中", "准备中"))
            add(record("jp/JP_StageB.json", "b", "content", "準備中", "正在准备"))
            add(record("jp/JP_StageC.json", "c", "content", "準備中", "准备中"))
            add(record("jp/JP_StageD.json", "d", "content", "準備中", "正在准备"))
        }

        val compiled = compiler.compile("2026102001", "archive")

        assertEquals("重试", compiled.uniqueEntries["リトライ"])
        assertEquals(1, compiled.dominantEntryCount)
        assertFalse(compiled.uniqueEntries.containsKey("撤退"))
        assertFalse(compiled.uniqueEntries.containsKey("準備中"))
        assertEquals(2, compiled.conflictCount)
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
        assertTrue(indexFile.parent.fileName.toString().contains("-s10-"))
        assertEquals(indexFile.toString().replace('\\', '/'), root.resolve("active-index.path").readText().trim())
        DataInputStream(indexFile.inputStream()).use { input ->
            assertEquals("LZTI1\u0000", String(input.readNBytes(6), Charsets.US_ASCII))
            assertEquals(10, input.readInt())
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
