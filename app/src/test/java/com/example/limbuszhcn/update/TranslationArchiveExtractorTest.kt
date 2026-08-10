package com.example.limbuszhcn.update

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class TranslationArchiveExtractorTest {
    @Test
    fun extractsPatchableJsonFilesAndWritesManifest() {
        val workDir = Files.createTempDirectory("limbus-7z-test")
        val archive = workDir.resolve("LimbusLocalize_2026050702.7z")
        createArchive(
            archive,
            mapOf(
                "LimbusCompany_Data/Lang/LLC_zh-CN/MainUIText.json" to """
                    {"dataList":[{"id":"mainui_main_button1","content":"罪人"}]}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/StoryData/S001.json" to """
                    {"dataList":[{"id":"1","dialog":"你好"}]}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/Info/version.json" to """
                    {"version":2026050702,"notice":""}
                """.trimIndent(),
                "LimbusCompany_Data/Lang/LLC_zh-CN/Info/LICENSE" to "ignored"
            )
        )

        val extractor = TranslationArchiveExtractor()
        val result = extractor.extract(
            ExtractionRequest(
                archive = archive,
                cacheRoot = workDir.resolve("cache"),
                version = "2026050702",
                expectedSha256 = null
            )
        )

        assertEquals(2026050702L, result.manifest.archiveVersion)
        assertEquals(4, result.manifest.archiveFileCount)
        assertEquals(2, result.manifest.extractedFileCount)
        assertEquals(
            listOf("en/EN_MainUIText.json", "en/StoryData/EN_S001.json"),
            result.manifest.entries.map { it.targetPath }
        )
        assertTrue(result.outputDirectory.resolve("files/en/EN_MainUIText.json").exists())
        assertTrue(result.outputDirectory.resolve(TranslationArchiveExtractor.MANIFEST_FILE).exists())
        assertTrue(
            result.outputDirectory.resolve(TranslationArchiveExtractor.MANIFEST_FILE)
                .readText()
                .contains("\"archiveVersion\": 2026050702")
        )
    }

    @Test
    fun rejectsUnsafePathsInsideExpectedPrefix() {
        val workDir = Files.createTempDirectory("limbus-7z-test")
        val archive = workDir.resolve("unsafe.7z")
        createArchive(
            archive,
            mapOf(
                "LimbusCompany_Data/Lang/LLC_zh-CN/../evil.json" to "{}"
            )
        )

        try {
            TranslationArchiveExtractor().extract(
                ExtractionRequest(
                    archive = archive,
                    cacheRoot = workDir.resolve("cache"),
                    version = "unsafe"
                )
            )
            fail("Expected unsafe path to be rejected")
        } catch (error: ArchiveValidationException) {
            assertTrue(error.message!!.contains("Unsafe archive path"))
        }
    }

    private fun createArchive(path: Path, entries: Map<String, String>) {
        SevenZOutputFile(path.toFile()).use { archive ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    this.size = bytes.size.toLong()
                }
                archive.putArchiveEntry(entry)
                archive.write(bytes)
                archive.closeArchiveEntry()
            }
        }
    }
}
