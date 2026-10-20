package com.example.limbuszhcn.gamefs

import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ResourceTransferProgress(val phase: String, val bytes: Long, val total: Long)
data class ResourceTransferResult(val files: Int, val bytes: Long, val keptExisting: Int = 0, val sourceVersion: Long)

/** Resource-only, streaming migration. Existing resources and account data are never replaced. */
class GameResourceTransfer(
    storage: GameStorage,
    private val stagingRoot: Path,
    private val gameVersion: Long,
    private val cancelled: () -> Boolean = { false },
    private val progress: (ResourceTransferProgress) -> Unit = {}
) {
    private val roots = storage.resourceMigrationRoots().mapValues { it.value.toAbsolutePath().normalize() }
    private data class Entry(val path: String, val size: Long, val hash: String)
    private var lastProgress = 0L
    private var lastPhase = ""
    init { require(gameVersion > 0) { "请先同步已安装的游戏" } }

    fun exportResources(output: OutputStream): ResourceTransferResult = synchronized(LOCK) {
        recoverUnlocked()
        require(roots.isNotEmpty()) { "当前存储不支持资源迁移" }
        val files = mutableListOf<Pair<String, Path>>()
        roots.toSortedMap().forEach { (alias, root) ->
            require(alias.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "无效的资源目录标识" }
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@forEach
            Files.walk(root).use { paths -> paths.forEach { file ->
                checkCancelled()
                require(!Files.isSymbolicLink(file)) { "资源目录包含符号链接，不能迁出" }
                if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                    val name = "$alias/${root.relativize(file).toString().replace('\\', '/')}"
                    if (allowed(name)) files += name to file
                    require(files.size <= MAX_FILES) { "资源文件数量超过迁移上限" }
                }
            } }
        }
        files.sortBy { it.first }
        val total = files.sumOf { Files.size(it.second).also { size -> require(size <= MAX_FILE_BYTES) { "单个资源过大" } } }
        require(total in 1..MAX_BYTES && files.isNotEmpty()) { "没有可迁出的资源，或资源总量超过上限" }
        val entries = mutableListOf<Entry>()
        var copied = 0L
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry(HEADER))
            Properties().apply {
                setProperty("format", FORMAT)
                setProperty("package", PACKAGE)
                setProperty("versionCode", gameVersion.toString())
                setProperty("files", files.size.toString())
                setProperty("bytes", total.toString())
            }.store(zip, "Limbus resources only")
            zip.closeEntry()
            files.forEach { (name, file) ->
                val size = Files.size(file)
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry("data/$name"))
                val actual = Files.newInputStream(file).use { input -> copy(input, zip, size) { chunk, count ->
                    digest.update(chunk, 0, count)
                    copied += count
                    report("正在迁出资源", copied, total)
                } }
                require(actual == size && Files.size(file) == size) { "迁出期间资源发生变化，请退出游戏后重试" }
                zip.closeEntry()
                entries += Entry(name, actual, hex(digest.digest()))
            }
            require(copied == total) { "迁出期间资源发生变化，请重试" }
            zip.putNextEntry(ZipEntry(INDEX))
            val index = DataOutputStream(zip)
            index.writeInt(entries.size)
            entries.forEach { index.writeUTF(it.path); index.writeLong(it.size); index.writeUTF(it.hash) }
            zip.closeEntry()
        }
        progress(ResourceTransferProgress("迁出完成", copied, total))
        ResourceTransferResult(files.size, copied, sourceVersion = gameVersion)
    }

    fun importResources(input: InputStream): ResourceTransferResult = synchronized(LOCK) {
        recoverUnlocked()
        Files.createDirectories(stagingRoot)
        val stage = Files.createDirectory(stagingRoot.resolve("transfer-${UUID.randomUUID()}"))
        var committed = false
        try {
            var copied = 0L
            val actual = linkedMapOf<String, Entry>()
            var index: List<Entry>? = null
            var sourceVersion = 0L
            var total = 0L
            var count = 0
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                require(zip.nextEntry?.name == HEADER) { "不是有效的 Limbus 资源迁移包" }
                val metadata = Properties().apply { load(ByteArrayInputStream(readBounded(zip, 16 * 1024))) }
                require(metadata.getProperty("format") == FORMAT && metadata.getProperty("package") == PACKAGE) { "迁移包格式或游戏不匹配" }
                total = metadata.getProperty("bytes")?.toLongOrNull() ?: -1
                count = metadata.getProperty("files")?.toIntOrNull() ?: -1
                sourceVersion = metadata.getProperty("versionCode")?.toLongOrNull() ?: -1
                require(total in 1..MAX_BYTES && count in 1..MAX_FILES && sourceVersion > 0) { "迁移包大小或版本信息无效" }
                require(Files.getFileStore(stage).usableSpace > total + 64 * 1024 * 1024) { "空间不足：迁入前需要暂存并校验整份资源" }
                var entry = zip.nextEntry
                while (entry != null) {
                    checkCancelled()
                    require(!entry.isDirectory) { "迁移包包含非预期目录" }
                    when {
                        entry.name == INDEX -> {
                            require(index == null) { "迁移包校验清单重复" }
                            val data = DataInputStream(ByteArrayInputStream(readBounded(zip, MAX_INDEX_BYTES)))
                            val length = data.readInt()
                            require(length == count) { "迁移包文件数量不匹配" }
                            index = List(length) { Entry(data.readUTF(), data.readLong(), data.readUTF()) }
                            require(data.read() == -1) { "迁移包校验清单包含多余数据" }
                        }
                        entry.name.startsWith("data/") -> {
                            val name = entry.name.removePrefix("data/")
                            require(allowed(name) && actual.size < count && !actual.containsKey(name)) { "迁移包路径无效、重复或超出范围" }
                            val target = safeStagePath(stage, name)
                            Files.createDirectories(target.parent)
                            val digest = MessageDigest.getInstance("SHA-256")
                            val size = Files.newOutputStream(target).use { output ->
                                copy(zip, output, minOf(MAX_FILE_BYTES, total - copied)) { chunk, read ->
                                    digest.update(chunk, 0, read); copied += read
                                    report("正在暂存并校验资源", copied, total)
                                }
                            }
                            actual[name] = Entry(name, size, hex(digest.digest()))
                        }
                        else -> error("迁移包包含非资源内容")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val expected = requireNotNull(index) { "迁移包缺少 SHA-256 校验清单" }
            require(actual.size == count && copied == total && expected.size == actual.size &&
                expected.map { it.path }.toSet().size == expected.size && expected.all { actual[it.path] == it }) { "资源校验失败，未更改现有资源" }
            checkCancelled()
            var kept = 0
            var added = 0
            FileOutputStream(stage.resolve(JOURNAL).toFile()).use { journal ->
                expected.forEach { item ->
                    checkCancelled()
                    val target = destination(item.path)
                    Files.createDirectories(target.parent)
                    if (Files.exists(target, NOFOLLOW_LINKS)) {
                        require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "目标资源不是普通文件" }
                        kept++
                    } else {
                        // Persist the rollback intent before making the new file visible.
                        journal.write((item.path + "\n").toByteArray(Charsets.UTF_8))
                        journal.fd.sync()
                        Files.move(safeStagePath(stage, item.path), target)
                        added++
                    }
                    report("正在合并资源", added.toLong() + kept, expected.size.toLong())
                }
            }
            Files.createFile(stage.resolve(COMPLETE))
            committed = true
            ResourceTransferResult(added, copied, kept, sourceVersion)
        } finally {
            if (!committed) rollback(stage)
            deleteStage(stage)
        }
    }

    fun recoverInterruptedImports() = synchronized(LOCK) { recoverUnlocked() }

    private fun recoverUnlocked() {
        if (!Files.isDirectory(stagingRoot, NOFOLLOW_LINKS)) return
        Files.list(stagingRoot).use { paths -> paths.filter {
            it.fileName.toString().startsWith("transfer-") && Files.isDirectory(it, NOFOLLOW_LINKS)
        }.forEach { stage ->
            if (!Files.exists(stage.resolve(COMPLETE))) rollback(stage)
            deleteStage(stage)
        } }
    }

    private fun rollback(stage: Path) {
        val journal = stage.resolve(JOURNAL)
        if (!Files.isRegularFile(journal, NOFOLLOW_LINKS)) return
        require(Files.size(journal) <= MAX_INDEX_BYTES) { "资源恢复记录超过上限" }
        val completedLines = String(Files.readAllBytes(journal), Charsets.UTF_8)
            .substringBeforeLast('\n', "").lineSequence().filter(String::isNotBlank).toList()
        completedLines.asReversed().forEach { name ->
            require(allowed(name)) { "资源恢复记录无效" }
            Files.deleteIfExists(destination(name))
        }
    }

    private fun destination(name: String): Path {
        require(allowed(name)) { "资源路径不在允许范围内" }
        val root = requireNotNull(roots[name.substringBefore('/')])
        val target = root.resolve(name.substringAfter('/')).normalize()
        require(target.startsWith(root)) { "资源路径越界" }
        var part: Path? = target
        while (part != null && part.startsWith(root)) {
            require(!Files.isSymbolicLink(part)) { "资源路径包含符号链接" }
            part = part.parent
        }
        return target
    }

    private fun allowed(name: String): Boolean {
        if (name.toByteArray(Charsets.UTF_8).size > 512 || name.contains('\\') || name.contains(':') ||
            name.any { it.code < 32 } || name.startsWith('/')) return false
        val parts = name.split('/')
        if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." } || parts.first() !in roots) return false
        val denied = setOf("shared_prefs", "databases", "playerprefs", "accounts", "cookies", "temp")
        if (parts.any { it.lowercase() in denied || "playerprefs" in it.lowercase() }) return false
        return !parts.last().lowercase().let { it.endsWith(".apk") || it.endsWith(".dex") || it.endsWith(".so") || it.endsWith(".tmp") }
    }

    private fun safeStagePath(stage: Path, name: String): Path {
        require(allowed(name)) { "暂存资源路径无效" }
        return stage.resolve("data").resolve(name).normalize().also { require(it.startsWith(stage.resolve("data"))) }
    }

    private fun deleteStage(stage: Path) {
        require(stage.toAbsolutePath().normalize().startsWith(stagingRoot.toAbsolutePath().normalize()) &&
            stage.fileName.toString().startsWith("transfer-")) { "暂存目录越界" }
        Files.walk(stage).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long, onBytes: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= limit) { "资源数据超出声明大小或迁移上限" }
            output.write(buffer, 0, count)
            onBytes(buffer, count)
        }
        return size
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray = ByteArrayOutputStream().use { output ->
        copy(input, output, limit.toLong()) { _, _ -> }; output.toByteArray()
    }
    private fun checkCancelled() { if (cancelled()) throw CancellationException("资源迁移已取消") }
    private fun report(phase: String, bytes: Long, total: Long) {
        val now = System.nanoTime()
        if (phase != lastPhase || now - lastProgress >= 200_000_000L) {
            progress(ResourceTransferProgress(phase, bytes, total)); lastProgress = now; lastPhase = phase
        }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private val LOCK = Any()
        private const val PACKAGE = "com.ProjectMoon.LimbusCompany"
        private const val FORMAT = "limbus-resources-v1"
        private const val HEADER = "migration.properties"
        private const val INDEX = "checksums.bin"
        private const val JOURNAL = "journal.tsv"
        private const val COMPLETE = "complete"
        private const val MAX_FILES = 50_000
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024 * 1024
        private const val MAX_BYTES = 64L * 1024 * 1024 * 1024
        private const val MAX_INDEX_BYTES = 32 * 1024 * 1024
    }
}
