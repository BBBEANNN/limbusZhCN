package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameStorageInstaller
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import com.example.limbuszhcn.gamefs.PatchUninstallSummary
import com.example.limbuszhcn.update.InstallGitHubReleaseRequest
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.TranslationUpdateManager
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.LinkedHashSet
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * 将 GitHub 汉化包编译成运行时索引，并通过容器存储管理激活、刷新与卸载状态。
 *
 * @param cacheRoot 汉化下载、解压和运行时索引的宿主缓存根目录。
 * @param storage 容器内游戏本地化资源的访问边界。
 */
class ContainerPatchInstaller(
    private val cacheRoot: Path,
    private val storage: GameStorage
) {
    /**
     * 从指定 GitHub Releases 页面获取最新汉化包并激活完整运行时索引。
     *
     * @param releaseUrl 公开的 GitHub Releases 页面或兼容的 GitHub API 地址。
     * @param installedVersion 已知的本地汉化版本；非空时仅接受更高版本。
     * @return 本次索引编译、资源就绪度和激活状态摘要。
     */
    fun installFromGitHub(releaseUrl: String, installedVersion: Long? = null): PatchInstallSummary {
        val result = TranslationUpdateManager().installFromGitHub(
            InstallGitHubReleaseRequest(
                releaseUrl = releaseUrl,
                downloadCacheRoot = cacheRoot.resolve("downloads"),
                patchCacheRoot = cacheRoot.resolve("patch-cache"),
                installedVersion = installedVersion
            )
        )
        return activateRedirect(result.outputDirectory, result.manifest)
    }

    /**
     * 安装已经在本地准备好的补丁目录，主要用于离线测试和兼容旧补丁流程。
     *
     * @param patchDirectory 已解压且与清单匹配的补丁目录。
     * @param manifest 描述补丁版本、文件哈希和目标路径的清单。
     * @return 实际写入容器存储的文件摘要。
     */
    fun installPrepared(patchDirectory: Path, manifest: PatchManifest): PatchInstallSummary {
        saveBaseline(patchDirectory, manifest)
        return GameStorageInstaller(storage).install(patchDirectory, manifest)
    }

    /**
     * 读取当前已激活的运行时索引或旧版重定向清单状态。
     *
     * @return 存在有效激活状态时返回安装摘要，否则返回 `null`。
     */
    fun activeInstallSummary(): PatchInstallSummary? {
        if (activeIndexPointer().isRegularFile()) {
            val indexFile = runCatching {
                Path.of(activeIndexPointer().readText(Charsets.UTF_8).trim())
            }.getOrNull()
            val indexManifest = indexFile?.parent?.resolve(INDEX_MANIFEST_FILE)
            if (indexManifest != null && indexManifest.isRegularFile()) {
                val properties = java.util.Properties().apply {
                    indexManifest.inputStream().use(::load)
                }
                return PatchInstallSummary(
                    version = properties.getProperty("version", "unknown"),
                    writtenFiles = properties.getProperty("availableSourceFileCount")
                        ?.toIntOrNull() ?: 0,
                    indexActivated = true,
                    candidateSourceFiles = properties.getProperty("candidateSourceFileCount")
                        ?.toIntOrNull() ?: 0,
                    availableSourceFiles = properties.getProperty("availableSourceFileCount")
                        ?.toIntOrNull() ?: 0,
                    sourceRecordCount = properties.getProperty("sourceRecordCount")
                        ?.toIntOrNull() ?: 0
                )
            }
        }
        if (!activeRedirectFile().isRegularFile()) {
            return null
        }
        val patchDirectory = latestPatchDirectory() ?: return null
        if (!patchDirectory.resolve(ACTIVE_MARKER_FILE).isRegularFile()) {
            return null
        }
        val manifestFile = patchDirectory.resolve("manifest.json")
        if (!manifestFile.isRegularFile()) {
            return null
        }
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        return PatchInstallSummary(version = manifest.requestedVersion, writtenFiles = manifest.entries.size)
    }

    /**
     * 使用最近一次缓存的 GitHub 汉化包重新编译并激活运行时索引。
     *
     * @return 重新编译后的资源统计和索引激活摘要。
     */
    fun refreshActiveRedirects(): PatchInstallSummary {
        val patchDirectory = latestPatchDirectory()
            ?: throw IllegalStateException("没有可刷新重定向的汉化缓存，请先安装汉化包")
        val manifestFile = patchDirectory.resolve("manifest.json")
        require(manifestFile.isRegularFile()) {
            "汉化缓存缺少 manifest.json: $manifestFile"
        }
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        return activateRedirect(patchDirectory, manifest)
    }

    /**
     * 将汉化 JSON 与设备上的日文资源配对，生成只包含明确文本映射的运行时索引。
     *
     * @param patchDirectory 已解压的汉化包缓存目录。
     * @param manifest 汉化包文件清单和版本信息。
     * @return 候选资源、可读日文源文件和索引激活结果。
     */
    fun activateRedirect(patchDirectory: Path, manifest: PatchManifest): PatchInstallSummary {
        var activeFiles = 0
        var candidateFiles = 0
        var availableFiles = 0
        val indexCompiler = TranslationIndexCompiler()
        manifest.entries.forEach { entry ->
            val source = patchDirectory.resolve(entry.cachePath).toAbsolutePath().normalize()
            if (!source.isRegularFile() || !source.name.endsWith(".json", ignoreCase = true)) {
                return@forEach
            }
            candidateFiles += 1
            val target = storage.resolvePatchTarget(entry.targetPath)
            val japaneseSource = japaneseSourceFor(target)
            val japaneseJson = storage.read(japaneseSource) ?: return@forEach
            availableFiles += 1
            val records = collectTranslationIndexRecords(
                path = japaneseSource,
                originalJson = japaneseJson.toString(Charsets.UTF_8),
                translationJson = source.readText(Charsets.UTF_8)
            )
            if (records.isNotEmpty()) {
                records.forEach(indexCompiler::add)
                activeFiles += 1
            }
        }
        // The production path is runtime-only. Stale merged-file redirects must not survive an
        // upgrade from the earlier experiment.
        Files.deleteIfExists(activeRedirectFile())
        Files.deleteIfExists(pendingRedirectFile())
        val compiledIndex = indexCompiler.compile(
            version = manifest.requestedVersion,
            archiveSha256 = manifest.archiveSha256,
            candidateSourceFileCount = candidateFiles,
            availableSourceFileCount = availableFiles
        )
        val resourcesReady = hasSufficientJapaneseSources(availableFiles, candidateFiles)
        val previousIndexAvailable = activeIndexPointer().isRegularFile()
        val activated = resourcesReady && compiledIndex.uniqueEntries.isNotEmpty()
        if (activated) {
            val activeIndex = TranslationIndexStore(indexRoot()).activate(compiledIndex)
            android.util.Log.i(
                TAG,
                "Activated Japanese full-text translation index version=${manifest.requestedVersion}" +
                    " unique=${compiledIndex.uniqueEntries.size}" +
                    " terms=${compiledIndex.termEntries.size}" +
                    " sources=${compiledIndex.sourceRecordCount}" +
                    " conflicts=${compiledIndex.conflictCount}" +
                    " dominant=${compiledIndex.dominantEntryCount}" +
                    " path=$activeIndex"
            )
            patchDirectory.resolve(ACTIVE_MARKER_FILE).writeText("runtime-index-jp-ui\n", Charsets.UTF_8)
        } else if (!resourcesReady) {
            android.util.Log.w(
                TAG,
                "Japanese resources not ready version=${manifest.requestedVersion}" +
                    " available=$availableFiles candidate=$candidateFiles" +
                    " preserved=$previousIndexAvailable"
            )
        } else {
            android.util.Log.w(
                TAG,
                "Translation index has no unambiguous entries version=${manifest.requestedVersion}" +
                    " preserved=$previousIndexAvailable"
            )
        }
        return PatchInstallSummary(
            version = manifest.requestedVersion,
            writtenFiles = activeFiles,
            indexActivated = activated,
            candidateSourceFiles = candidateFiles,
            availableSourceFiles = availableFiles,
            sourceRecordCount = compiledIndex.sourceRecordCount,
            previousIndexPreserved = !activated && previousIndexAvailable
        )
    }

    /**
     * 停用当前汉化索引，并尽可能恢复旧版文件补丁留下的基线内容。
     *
     * @return 删除或恢复文件的卸载摘要。
     */
    fun uninstallLatest(): PatchUninstallSummary {
        val patchDirectory = latestPatchDirectory()
            ?: throw IllegalStateException("没有可卸载的汉化缓存，请先安装一次汉化")
        val manifestFile = patchDirectory.resolve("manifest.json")
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        if (activeRedirectFile().isRegularFile()) {
            Files.deleteIfExists(activeRedirectFile())
        }
        if (pendingRedirectFile().isRegularFile()) {
            Files.deleteIfExists(pendingRedirectFile())
        }
        TranslationIndexStore(indexRoot()).deactivate()
        removeLangOverlay(manifest)
        val restored = restoreBaseline(manifest)
        if (restored != null) {
            return restored
        }
        if (patchDirectory.resolve(ACTIVE_MARKER_FILE).isRegularFile()) {
            Files.deleteIfExists(patchDirectory.resolve(ACTIVE_MARKER_FILE))
            return PatchUninstallSummary(version = manifest.requestedVersion, deletedFiles = manifest.entries.size)
        }
        return GameStorageInstaller(storage).uninstall(manifest)
    }

    private fun activeRedirectFile(): Path =
        cacheRoot.resolve(ACTIVE_REDIRECT_FILE)

    private fun pendingRedirectFile(): Path =
        cacheRoot.resolve(PENDING_REDIRECT_FILE)

    private fun indexRoot(): Path =
        cacheRoot.resolve(TRANSLATION_INDEX_DIR)

    private fun activeIndexPointer(): Path =
        indexRoot().resolve(TranslationIndexStore.ACTIVE_POINTER)

    private fun japaneseSourceFor(resolvedTarget: String): String {
        val normalized = resolvedTarget.replace('\\', '/')
        val root = storage.rootPath().trimEnd('/').replace('\\', '/')
        val relative = normalized.removePrefix(root).removePrefix("/")
        val withoutLanguage = relative
            .removePrefix("en/")
            .removePrefix("kr/")
            .removePrefix("jp/")
        val slash = withoutLanguage.lastIndexOf('/')
        val directory = if (slash >= 0) withoutLanguage.substring(0, slash + 1) else ""
        val fileName = if (slash >= 0) withoutLanguage.substring(slash + 1) else withoutLanguage
        val baseName = LANGUAGE_PREFIX_REGEX.replace(fileName, "")
        return "$root/jp/${directory}JP_$baseName"
    }

    private fun collectTranslationIndexRecords(
        path: String,
        originalJson: String,
        translationJson: String
    ): List<TranslationIndexRecord> {
        return runCatching {
            val indexRecords = mutableListOf<TranslationIndexRecord>()
            collectPairedJson(
                original = JSONTokener(originalJson).nextValue(),
                translation = JSONTokener(translationJson).nextValue(),
                path = path,
                context = "$",
                output = indexRecords
            )
            indexRecords
        }.getOrElse {
            android.util.Log.w(TAG, "Skip invalid Japanese translation source path=$path", it)
            emptyList()
        }
    }

    private fun collectPairedJson(
        original: Any?,
        translation: Any?,
        path: String,
        context: String,
        output: MutableList<TranslationIndexRecord>
    ) {
        when {
            original is JSONObject && translation is JSONObject -> {
                val objectContext = stableObjectIdentity(original)?.let { "$context[$it]" } ?: context
                val keys = original.keys()
                while (keys.hasNext()) {
                    val field = keys.next()
                    if (!translation.has(field) || translation.isNull(field)) continue
                    val sourceValue = original.opt(field)
                    val translatedValue = translation.opt(field)
                    if (field in TEXT_FIELDS && sourceValue is String && translatedValue is String) {
                        output += TranslationIndexRecord(
                            path = path,
                            id = objectContext,
                            field = field,
                            source = sourceValue,
                            translation = translatedValue
                        )
                    } else if (
                        (sourceValue is JSONObject && translatedValue is JSONObject) ||
                        (sourceValue is JSONArray && translatedValue is JSONArray)
                    ) {
                        collectPairedJson(
                            original = sourceValue,
                            translation = translatedValue,
                            path = path,
                            context = "$objectContext.$field",
                            output = output
                        )
                    }
                }
            }
            original is JSONArray && translation is JSONArray -> {
                val translatedByIdentity = linkedMapOf<String, JSONObject>()
                for (index in 0 until translation.length()) {
                    val item = translation.optJSONObject(index) ?: continue
                    stableObjectIdentity(item)?.let { translatedByIdentity[it] = item }
                }
                for (index in 0 until original.length()) {
                    val sourceItem = original.opt(index)
                    val translatedItem = if (sourceItem is JSONObject) {
                        stableObjectIdentity(sourceItem)?.let(translatedByIdentity::get)
                            ?: translation.opt(index)
                    } else {
                        translation.opt(index)
                    }
                    if (
                        (sourceItem is JSONObject && translatedItem is JSONObject) ||
                        (sourceItem is JSONArray && translatedItem is JSONArray)
                    ) {
                        collectPairedJson(
                            original = sourceItem,
                            translation = translatedItem,
                            path = path,
                            context = "$context[$index]",
                            output = output
                        )
                    }
                }
            }
        }
    }

    private fun stableObjectIdentity(value: JSONObject): String? {
        ALIGNMENT_FIELDS.forEach { field ->
            if (value.has(field) && !value.isNull(field)) {
                val identity = value.opt(field)
                if (identity is String || identity is Number) {
                    return "$field=${java.lang.String.valueOf(identity)}"
                }
            }
        }
        return null
    }

    private fun saveBaseline(patchDirectory: Path, manifest: PatchManifest) {
        val baselineDirectory = patchDirectory.resolve(BASELINE_DIR)
        if (baselineDirectory.isDirectory()) {
            return
        }
        Files.createDirectories(baselineDirectory)
        manifest.entries.forEach { entry ->
            val target = storage.resolvePatchTarget(entry.targetPath)
            val snapshot = baselineDirectory.resolve(target.baselineName())
            val existing = storage.read(target)
            if (existing == null) {
                snapshot.resolveSibling("${snapshot.fileName}.missing").writeText(target, Charsets.UTF_8)
            } else {
                snapshot.outputStream().use { output -> output.write(existing) }
                snapshot.resolveSibling("${snapshot.fileName}.path").writeText(target, Charsets.UTF_8)
            }
        }
    }

    private fun restoreBaseline(manifest: PatchManifest): PatchUninstallSummary? {
        val baselineDirectory = latestPatchDirectory()?.resolve(BASELINE_DIR)
            ?: return null
        if (!baselineDirectory.isDirectory()) {
            return null
        }
        var restored = 0
        manifest.entries.forEach { entry ->
            val target = storage.resolvePatchTarget(entry.targetPath)
            val snapshot = baselineDirectory.resolve(target.baselineName())
            val missingMarker = snapshot.resolveSibling("${snapshot.fileName}.missing")
            if (missingMarker.isRegularFile()) {
                if (storage.exists(target) && storage.delete(target)) {
                    restored += 1
                }
            } else if (snapshot.isRegularFile()) {
                storage.write(target, snapshot)
                restored += 1
            }
        }
        return PatchUninstallSummary(version = manifest.requestedVersion, deletedFiles = restored)
    }

    private fun latestPatchDirectory(): Path? {
        val patchRoot = cacheRoot.resolve("patch-cache")
        if (!patchRoot.isDirectory()) {
            return null
        }
        return Files.list(patchRoot).use { paths ->
            paths
                .filter { it.isDirectory() && it.resolve("manifest.json").isRegularFile() }
                .sorted { left, right -> comparePatchDirectories(right, left) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun comparePatchDirectories(left: Path, right: Path): Int {
        val leftVersion = left.name.toLongOrNull()
        val rightVersion = right.name.toLongOrNull()
        if (leftVersion != null && rightVersion != null) {
            return leftVersion.compareTo(rightVersion)
        }
        return Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right))
    }

    private fun removeLangOverlay(manifest: PatchManifest) {
        storage.deleteResource("Lang/config.json")
        manifest.entries.forEach { entry ->
            val relative = entry.sourcePath.removePrefix(UPSTREAM_LANG_PREFIX)
            if (relative != entry.sourcePath && relative.isNotBlank() && !relative.startsWith("Info/")) {
                storage.deleteResource("Lang/LLC_zh-CN/$relative")
            }
        }
    }

    private fun String.baselineName(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    companion object {
        private const val BASELINE_DIR = "baseline"
        private const val ACTIVE_REDIRECT_FILE = "active-redirects.tsv"
        private const val PENDING_REDIRECT_FILE = "pending-redirects.tsv"
        private const val ACTIVE_MARKER_FILE = ".active-redirect"
        private const val TRANSLATION_INDEX_DIR = "translation-index"
        private const val INDEX_MANIFEST_FILE = "manifest.properties"
        private const val TAG = "LimbusZhCN"
        private const val UPSTREAM_LANG_PREFIX = "LimbusCompany_Data/Lang/LLC_zh-CN/"
        private val LANGUAGE_PREFIX_REGEX = Regex("^(EN|KR|JP)_")
        /**
         * Display text only. Resource identifiers, model/voice/icon keys and gameplay metadata
         * deliberately stay outside this list.
         */
        private val TEXT_FIELDS = setOf(
            "content", "dialog", "dlg", "teller",
            "name", "nameWithTitle", "nickName", "longName", "specialName",
            "shortName", "abName", "abnormalityName", "panicName", "skinItemTitle",
            "desc", "description", "simpleDesc", "summary", "flavor", "rawDesc", "behaveDesc",
            "eventDesc", "prevDesc", "subDesc", "successDesc", "failureDesc",
            "message", "messageDesc", "result", "panicDescription",
            "lowMoraleDescription", "skinItemDesc",
            "title", "place", "codeName", "clue", "sentence", "text", "subText", "mainText",
            "openCondition", "askLevelUp",
            "company", "area", "chapter", "chapterNumber", "chaptertitle", "parttitle",
            "timeline", "teacher", "add", "min", "variation", "variation2"
        )
        private val ALIGNMENT_FIELDS = listOf("id", "level", "index", "idx", "originalId")
    }

}

internal fun hasSufficientJapaneseSources(available: Int, candidate: Int): Boolean =
    candidate > 0 && available * 100L >= candidate * 90L
