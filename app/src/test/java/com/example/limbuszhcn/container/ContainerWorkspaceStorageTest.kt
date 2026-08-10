package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorageInstaller
import com.example.limbuszhcn.update.PatchFileEntry
import com.example.limbuszhcn.update.PatchManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ContainerWorkspaceStorageTest {
    @Test
    fun probesContainerLocalizeTree() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("RemoteLocalizeFileList.json").writeText("{}")
        localize.resolve("en/EN_LoginUIText.json").writeText("{}")

        val probe = ContainerWorkspaceStorage(localize).probe()

        assertTrue(probe.exists)
        assertEquals(1, probe.jsonFileCount)
        assertTrue(probe.remoteListPresent)
    }

    @Test
    fun installsPatchToExistingPrefixedFile() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("en/EN_LoginUIText.json").writeText("""{"old":true}""")
        val patchRoot = root.resolve("patch")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/LoginUIText.json").writeText("""{"content":"Google登录"}""")
        val storage = ContainerWorkspaceStorage(localize)

        val summary = GameStorageInstaller(storage).install(patchRoot, manifest("LoginUIText.json"))

        assertEquals(1, summary.writtenFiles)
        assertEquals("""{"content":"Google登录"}""", localize.resolve("en/EN_LoginUIText.json").readText())
        assertFalse(localize.resolve("en/LoginUIText.json").exists())
    }

    @Test
    fun restoresExistingFileFromBaseline() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("en/EN_MainUIText.json").writeText("""{"content":"Sinners"}""")
        val patchRoot = root.resolve("translation-cache/patch-cache/2026050702")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/MainUIText.json").writeText("""{"content":"罪人"}""")
        val manifest = manifest("MainUIText.json")
        patchRoot.resolve("manifest.json").writeText(manifest.toJson())
        val installer = ContainerPatchInstaller(
            cacheRoot = root.resolve("translation-cache"),
            storage = ContainerWorkspaceStorage(localize)
        )

        installer.installPrepared(patchRoot, manifest)
        val summary = installer.uninstallLatest()

        assertEquals("2026050702", summary.version)
        assertEquals(1, summary.deletedFiles)
        assertEquals("""{"content":"Sinners"}""", localize.resolve("en/EN_MainUIText.json").readText())
    }

    @Test
    @Ignore("activateRedirect uses Android org.json; cover this with device/runtime validation")
    fun activatesRedirectWithoutOverwritingOriginalFile() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("en/EN_MainUIText.json").writeText("""{"content":"Sinners"}""")
        val patchRoot = root.resolve("translation-cache/patch-cache/2026071001")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/MainUIText.json").writeText("""{"content":"罪人"}""")
        val manifest = manifest("MainUIText.json")
        patchRoot.resolve("manifest.json").writeText(manifest.toJson())
        val installer = ContainerPatchInstaller(
            cacheRoot = root.resolve("translation-cache"),
            storage = ContainerWorkspaceStorage(localize)
        )

        val summary = installer.activateRedirect(patchRoot, manifest)
        val redirects = root.resolve("translation-cache/active-redirects.tsv").readText()

        assertEquals(1, summary.writtenFiles)
        assertEquals("""{"content":"Sinners"}""", localize.resolve("en/EN_MainUIText.json").readText())
        assertTrue(redirects.contains("Localize/en/EN_MainUIText.json"))
        assertTrue(redirects.contains("redirect-cache/2026050702/files/en/MainUIText.json"))
    }

    @Test
    @Ignore("activateRedirect uses Android org.json; cover this with device/runtime validation")
    fun redirectUsesOriginalJsonShapeAndOnlyCopiesTextFields() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("en/EN_AbDlg_Outis.json").writeText(
            """
            {"dataList":[{"id":1,"personalityid":11101,"voiceFile":0,"teller":"Outis","dialog":"Victory","usage":"ORIGINAL"}]}
            """.trimIndent()
        )
        val patchRoot = root.resolve("translation-cache/patch-cache/2026071001")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/AbDlg_Outis.json").writeText(
            """
            {"dataList":[{"id":1,"personalityid":99999,"voiceFile":7,"teller":"奥提斯","dialog":"胜利","usage":"TRANSLATED"}]}
            """.trimIndent()
        )
        val manifest = manifest("AbDlg_Outis.json")
        patchRoot.resolve("manifest.json").writeText(manifest.toJson())
        val installer = ContainerPatchInstaller(
            cacheRoot = root.resolve("translation-cache"),
            storage = ContainerWorkspaceStorage(localize)
        )

        installer.activateRedirect(patchRoot, manifest)
        val redirectLine = root.resolve("translation-cache/active-redirects.tsv")
            .readText()
            .lineSequence()
            .first { it.contains("EN_AbDlg_Outis.json=") }
        val redirected = java.nio.file.Path.of(redirectLine.substringAfter("=")).readText()

        assertTrue(redirected.contains(""""teller":"奥提斯""""))
        assertTrue(redirected.contains(""""dialog":"胜利""""))
        assertTrue(redirected.contains(""""personalityid":11101"""))
        assertTrue(redirected.contains(""""voiceFile":0"""))
        assertTrue(redirected.contains(""""usage":"ORIGINAL""""))
        assertFalse(redirected.contains("99999"))
        assertFalse(redirected.contains("TRANSLATED"))
    }

    @Test
    @Ignore("activateRedirect uses Android org.json; cover this with device/runtime validation")
    fun uninstallRedirectOnlyDisablesActiveRedirect() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        localize.resolve("en/EN_MainUIText.json").writeText("""{"content":"Sinners"}""")
        val patchRoot = root.resolve("translation-cache/patch-cache/2026071001")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/MainUIText.json").writeText("""{"content":"罪人"}""")
        val manifest = manifest("MainUIText.json")
        patchRoot.resolve("manifest.json").writeText(manifest.toJson())
        val installer = ContainerPatchInstaller(
            cacheRoot = root.resolve("translation-cache"),
            storage = ContainerWorkspaceStorage(localize)
        )

        installer.activateRedirect(patchRoot, manifest)
        val summary = installer.uninstallLatest()

        assertEquals(1, summary.deletedFiles)
        assertFalse(root.resolve("translation-cache/active-redirects.tsv").exists())
        assertEquals("""{"content":"Sinners"}""", localize.resolve("en/EN_MainUIText.json").readText())
    }

    @Test
    fun removesNewFileWhenRestoringMissingBaseline() {
        val root = Files.createTempDirectory("limbus-container-test")
        val localize = root.resolve("Localize")
        localize.resolve("en").toFile().mkdirs()
        val patchRoot = root.resolve("translation-cache/patch-cache/2026050702")
        patchRoot.resolve("files/en").toFile().mkdirs()
        patchRoot.resolve("files/en/NewUIText.json").writeText("""{"content":"新增"}""")
        val manifest = manifest("NewUIText.json")
        patchRoot.resolve("manifest.json").writeText(manifest.toJson())
        val installer = ContainerPatchInstaller(
            cacheRoot = root.resolve("translation-cache"),
            storage = ContainerWorkspaceStorage(localize)
        )

        installer.installPrepared(patchRoot, manifest)
        val summary = installer.uninstallLatest()

        assertEquals(1, summary.deletedFiles)
        assertFalse(localize.resolve("en/NewUIText.json").exists())
    }

    @Test
    fun rejectsPathTraversalOutsideLocalizeRoot() {
        val root = Files.createTempDirectory("limbus-container-test")
        val storage = ContainerWorkspaceStorage(root.resolve("Localize"))

        val deleted = runCatching { storage.delete("${storage.rootPath()}/../escape.json") }

        assertTrue(deleted.isFailure)
    }

    @Test
    fun importsSplitApksIntoWorkspace() {
        val root = Files.createTempDirectory("limbus-container-test")
        val split = root.resolve("base.apk")
        split.writeText("apk")
        val runtime = LocalContainerRuntime(root.resolve("workspace"))

        val result = runtime.importGame(
            GameImportRequest(
                splitApkPaths = listOf(split.toString()),
                versionCode = 2026051501L,
                source = "installed"
            )
        )
        val status = runtime.status()

        assertEquals(LocalContainerRuntime.GAME_PACKAGE, result.packageName)
        assertEquals(1, result.importedSplits)
        assertEquals(2026051501L, result.versionCode)
        assertEquals("installed", result.source)
        assertTrue(status.gameImported)
        assertEquals(1, status.importedSplits)
        assertEquals(2026051501L, status.importedVersionCode)
        assertEquals("installed", status.importSource)
        assertTrue(root.resolve("workspace/splits/base.apk").exists())
        assertTrue(runtime.storage().probe().exists)
    }

    @Test
    fun replacesOldWorkspaceSplitsOnImport() {
        val root = Files.createTempDirectory("limbus-container-test")
        val first = root.resolve("split_old.apk")
        val second = root.resolve("base.apk")
        first.writeText("old")
        second.writeText("new")
        val runtime = LocalContainerRuntime(root.resolve("workspace"))

        runtime.importGame(GameImportRequest(splitApkPaths = listOf(first.toString()), source = "installed"))
        runtime.importGame(GameImportRequest(splitApkPaths = listOf(second.toString()), source = "installed"))

        assertFalse(root.resolve("workspace/splits/split_old.apk").exists())
        assertEquals("new", root.resolve("workspace/splits/base.apk").readText())
    }

    private fun manifest(fileName: String): PatchManifest =
        PatchManifest(
            requestedVersion = "2026050702",
            archiveVersion = 2026050702,
            archiveSha256 = "archive",
            archiveFileCount = 1,
            extractedFileCount = 1,
            entries = listOf(
                PatchFileEntry(
                    sourcePath = "LimbusCompany_Data/Lang/LLC_zh-CN/$fileName",
                    cachePath = "files/en/$fileName",
                    targetPath = "en/$fileName",
                    size = 1,
                    sha256 = "file"
                )
            )
        )
}
