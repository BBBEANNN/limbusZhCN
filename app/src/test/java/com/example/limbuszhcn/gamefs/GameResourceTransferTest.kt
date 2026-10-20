package com.example.limbuszhcn.gamefs

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class GameResourceTransferTest {
    private fun transfer(root: Path, cancel: () -> Boolean = { false }, progress: (ResourceTransferProgress) -> Unit = {}) =
        GameResourceTransfer(ContainerWorkspaceStorage(root.resolve("assets/Resources_moved/Localize"),
            mapOf("assets" to root.resolve("assets"), "cache" to root.resolve("cache/Shared"))),
            root.resolve("staging"), 483, cancel, progress)

    private fun file(root: Path, path: String, value: String) {
        val target = root.resolve(path); Files.createDirectories(target.parent); target.writeText(value)
    }

    private fun sample(): Pair<Path, ByteArray> {
        val source = Files.createTempDirectory("resource-source")
        file(source, "assets/Resources_moved/Localize/jp/JP_Test.json", "{\"name\":\"日本語\"}")
        file(source, "cache/Shared/bundle/hash/data", "bundle-data")
        file(source, "assets/PlayerPrefs", "private")
        file(source, "assets/Temp/incomplete", "unfinished")
        file(source, "assets/game.apk", "not-a-resource")
        return source to ByteArrayOutputStream().also { transfer(source).exportResources(it) }.toByteArray()
    }

    @Test
    fun roundTripPreservesExistingResourcesAndExcludesPrivateOrExecutableFiles() {
        val (source, archive) = sample()
        val target = Files.createTempDirectory("resource-target")
        file(target, "cache/Shared/bundle/hash/data", "already-newer")
        val result = transfer(target).importResources(ByteArrayInputStream(archive))
        assertEquals(1, result.files)
        assertEquals(1, result.keptExisting)
        assertArrayEquals(source.resolve("assets/Resources_moved/Localize/jp/JP_Test.json").readBytes(),
            target.resolve("assets/Resources_moved/Localize/jp/JP_Test.json").readBytes())
        assertEquals("already-newer", target.resolve("cache/Shared/bundle/hash/data").readText())
        assertFalse(Files.exists(target.resolve("assets/PlayerPrefs")))
        assertFalse(Files.exists(target.resolve("assets/game.apk")))
        assertFalse(Files.exists(target.resolve("assets/Temp")))
        assertEquals(0, transfer(target).importResources(ByteArrayInputStream(archive)).files)
    }

    private fun rewrite(archive: ByteArray, transform: (String, ByteArray) -> Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { output -> ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val (name, bytes) = transform(entry.name, input.readBytes())
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
                entry = input.nextEntry
            }
        } }
        return out.toByteArray()
    }

    @Test
    fun rejectsChecksumFailureBeforeAddingAnyResources() {
        val archive = rewrite(sample().second) { name, bytes -> name to
            if (name.startsWith("data/")) bytes.clone().also { it[0] = (it[0].toInt() xor 1).toByte() } else bytes }
        val target = Files.createTempDirectory("resource-corrupt")
        file(target, "assets/existing", "keep")
        assertThrows(IllegalArgumentException::class.java) { transfer(target).importResources(ByteArrayInputStream(archive)) }
        assertEquals("keep", target.resolve("assets/existing").readText())
        assertFalse(Files.exists(target.resolve("assets/Resources_moved")))
        Files.list(target.resolve("staging")).use { assertEquals(0, it.count()) }
    }

    @Test
    fun rejectsTraversalAccountsAndCodePathsWithoutTouchingExistingFiles() {
        val archive = sample().second
        for (bad in listOf("assets/../../escape", "assets/shared_prefs/account.xml", "assets/PlayerPrefs",
            "assets/evil.dex", "assets/evil.apk", "assets\\escape", "unknown-root/file")) {
            var changed = false
            val badArchive = rewrite(archive) { name, bytes ->
                if (!changed && name.startsWith("data/")) { changed = true; "data/$bad" to bytes } else name to bytes
            }
            val target = Files.createTempDirectory("resource-path")
            file(target, "assets/existing", "keep")
            assertThrows(IllegalArgumentException::class.java) { transfer(target).importResources(ByteArrayInputStream(badArchive)) }
            assertEquals("keep", target.resolve("assets/existing").readText())
        }
    }

    @Test
    fun cancellationWhileReadingRollsBackStaging() {
        val archive = sample().second
        val target = Files.createTempDirectory("resource-cancel")
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            transfer(target, { cancelled }) { cancelled = true }.importResources(ByteArrayInputStream(archive))
        }
        assertFalse(Files.exists(target.resolve("assets/Resources_moved")))
    }

    @Test
    fun cancellationDuringCommitRemovesOnlyNewFiles() {
        val archive = sample().second
        val target = Files.createTempDirectory("resource-commit-cancel")
        file(target, "assets/existing", "keep")
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            transfer(target, { cancelled }) { if (it.phase == "正在合并资源") cancelled = true }
                .importResources(ByteArrayInputStream(archive))
        }
        assertFalse(Files.exists(target.resolve("assets/Resources_moved/Localize/jp/JP_Test.json")))
        assertEquals("keep", target.resolve("assets/existing").readText())
    }

    @Test
    fun recoversInterruptedCommitAndIgnoresPartialJournalLine() {
        val target = Files.createTempDirectory("resource-recovery")
        file(target, "assets/added", "interrupted")
        file(target, "assets/existing", "keep")
        file(target, "staging/transfer-test/journal.tsv", "assets/added\nassets/existing")
        transfer(target).recoverInterruptedImports()
        assertFalse(Files.exists(target.resolve("assets/added")))
        assertEquals("keep", target.resolve("assets/existing").readText())
        assertFalse(Files.exists(target.resolve("staging/transfer-test")))
    }

    @Test
    fun rejectsDishonestSizeDeclarationBeforeAllocatingResources() {
        val archive = rewrite(sample().second) { name, bytes ->
            if (name != "migration.properties") name to bytes else {
                val properties = Properties().apply { load(ByteArrayInputStream(bytes)); setProperty("bytes", "1") }
                name to ByteArrayOutputStream().also { properties.store(it, "test") }.toByteArray()
            }
        }
        val target = Files.createTempDirectory("resource-bomb")
        assertThrows(IllegalArgumentException::class.java) { transfer(target).importResources(ByteArrayInputStream(archive)) }
        assertFalse(Files.exists(target.resolve("assets/Resources_moved")))
    }

    @Test
    fun streamsMultipleBuffersAndSupportsDifferentGameVersionWithoutReplacingExistingData() {
        val source = Files.createTempDirectory("resource-stream")
        val bytes = ByteArray(400_000) { (it % 251).toByte() }
        Files.createDirectories(source.resolve("assets")); source.resolve("assets/large.bundle").writeBytes(bytes)
        val archive = ByteArrayOutputStream().also { transfer(source).exportResources(it) }.toByteArray()
        val target = Files.createTempDirectory("resource-new-version")
        val storage = ContainerWorkspaceStorage(target.resolve("assets/Resources_moved/Localize"))
        val result = GameResourceTransfer(storage, target.resolve("staging"), 484).importResources(ByteArrayInputStream(archive))
        assertArrayEquals(bytes, target.resolve("assets/large.bundle").readBytes())
        assertEquals(483L, result.sourceVersion)
    }
}
