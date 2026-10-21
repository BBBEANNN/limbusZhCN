package com.example.limbuszhcn.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.limbuszhcn.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

/**
 * 保存已下载并校验、可以交给系统安装器继续确认的汉化器更新。
 *
 * @property file 宿主私有缓存中的固定 APK 文件，不包含游戏资源。
 * @property versionName 从 APK 本身读取的实际显示版本。
 * @property versionCode 从 APK 本身读取的实际安装版本号。
 */
internal data class PreparedAppUpdate(
    val file: File,
    val versionName: String,
    val versionCode: Long
)

/**
 * 按玩家请求检查官方汉化器版本，下载和验证同签名升级包供系统安装器安装。
 *
 * @param context 获取宿主私有缓存、当前包信息和 Android 包解析服务的上下文。
 * @param transport 可替换的公开 GitHub 传输实现，不携带 Token 或账号信息。
 */
internal class AppUpdateManager(
    context: Context,
    private val transport: HttpTransport = UrlConnectionHttpTransport()
) {
    private val appContext = context.applicationContext
    private val updateDirectory = File(appContext.cacheDir, "app-updates")
    private val updateFile = File(updateDirectory, "update.apk")
    private var preparedIntegrity: PreparedIntegrity? = null

    /**
     * 查询官方稳定版并准备完整升级包；网络和文件工作应由调用方放在后台线程。
     *
     * @param onProgress 接收检查、下载和校验阶段的中文进度，可由调用方转到主线程显示。
     * @return 官方版本较新且 APK 可安全覆盖安装时返回升级包；当前已是最新版本时返回空。
     */
    fun prepareUpdate(onProgress: (String) -> Unit = {}): PreparedAppUpdate? = withCacheLock {
        preparedIntegrity = null
        checkInterrupted()
        onProgress("正在检查汉化器版本…")
        val release = fetchRelease()
        // BuildConfig 版本常量可能已被旧调用点内联；始终使用系统当前安装信息判定更新。
        if (!AppUpdatePolicy.isNewerVersion(release.versionName, installedVersionName())) return@withCacheLock null
        if (!updateDirectory.isDirectory && !updateDirectory.mkdirs()) {
            throw IOException("无法创建汉化器更新缓存目录")
        }
        // 每次复用仍核对整包与签名，防止上次未完成的下载或损坏缓存进入安装界面。
        if (updateFile.isFile) {
            val cached = try {
                val hash = verifyReleaseFile(updateFile, release)
                inspectApk(updateFile, release.versionName) to hash
            } catch (error: InterruptedIOException) {
                throw error
            } catch (_: Exception) {
                // 不立即删除旧缓存；新文件完成校验后再原子替换，下载失败可保留原有文件。
                null
            }
            if (cached != null) {
                val (prepared, hash) = cached
                // 同版不是损坏缓存，直接结束检查，不能落入异常分支再次下载同一个 APK。
                // 系统版本读取异常也向上报告，不把安装状态不明误判成需要重下。
                if (!isUpdateNewerThanInstalled(prepared)) return@withCacheLock null
                rememberPrepared(prepared, hash)
                onProgress("汉化器 ${prepared.versionName} 已准备好")
                return@withCacheLock prepared
            }
        }
        val available = updateDirectory.usableSpace
        if (available > 0 && available < release.sizeBytes + 8L * 1024 * 1024) {
            throw IOException("手机剩余空间不足，无法下载汉化器更新包")
        }
        val temporary = File(updateDirectory, "update.apk.part")
        try {
            if (temporary.exists() && !temporary.delete()) throw IOException("无法清理未完成的汉化器更新包")
            download(release, temporary, onProgress)
            onProgress("正在校验汉化器更新包…")
            val hash = verifyReleaseFile(temporary, release)
            val inspected = inspectApk(temporary, release.versionName)
            checkInterrupted()
            // 同目录重命名保证 FileProvider 只会看到完成校验后的完整 APK。
            if (!temporary.renameTo(updateFile)) throw IOException("无法保存已校验的汉化器更新包")
            val prepared = inspected.copy(file = updateFile)
            // 下载期间可能已在别处完成升级；保留验证后的缓存，清空本次待安装会话。
            if (!isUpdateNewerThanInstalled(prepared)) return@withCacheLock null
            rememberPrepared(prepared, hash)
            onProgress("汉化器 ${prepared.versionName} 已准备好")
            return@withCacheLock prepared
        } finally {
            temporary.delete()
        }
    }

    /**
     * 在跳转系统安装器前重新核对文件、签名与版本，防止等待权限授权期间缓存失效。
     *
     * @param update 当前管理器先前准备并返回的更新包。
     * @return 文件仍有效且严格高于系统当前版本时为真；已安装同版或更高时清除会话并返回假。
     */
    fun validatePreparedUpdate(update: PreparedAppUpdate): Boolean = withCacheLock {
        // 权限设置或安装界面可能停留较久；已完成的更新不再进入文件校验和安装流程。
        if (!isUpdateNewerThanInstalled(update)) {
            preparedIntegrity = null
            return@withCacheLock false
        }
        val integrity = preparedIntegrity ?: throw IOException("更新包状态已变化，请重新检查汉化器版本")
        if (update.file.canonicalFile != updateFile.canonicalFile || update != integrity.update
            || !update.file.isFile || update.file.length() != integrity.sizeBytes
        ) throw IOException("汉化器更新包已失效，请重新检查更新")
        if (fileSha256(update.file) != integrity.sha256) throw IOException("汉化器更新包校验失败，请重新下载")
        val current = inspectApk(update.file, update.versionName)
        if (current != update) throw IOException("汉化器更新包版本已变化，请重新检查更新")
        // 完整哈希读取期间同样可能发生安装变化，因此交付安装器前再读一次系统状态。
        if (!isUpdateNewerThanInstalled(current)) {
            preparedIntegrity = null
            return@withCacheLock false
        }
        true
    }

    /**
     * 读取 Android 当前安装的汉化器显示版本，不回退到可能过期的编译期常量。
     *
     * @return PackageManager 当前报告的非空版本名称；读取失败时抛出异常供界面显示未知状态。
     */
    fun installedVersionName(): String = packageVersionName(readInstalledPackageInfo())

    /**
     * 只用本地实际安装信息判断待安装 APK 是否仍属于升级，不联网也不读取 APK 正文。
     *
     * @param update 先前准备的 APK 版本信息。
     * @return 版本名称和安装版本号均高于当前安装时为真，同版或任一维度较低时为假。
     */
    fun isUpdateNewerThanInstalled(update: PreparedAppUpdate): Boolean {
        val installed = readInstalledPackageInfo()
        return AppUpdatePolicy.isInstallableUpgrade(
            candidateVersionName = update.versionName,
            candidateVersionCode = update.versionCode,
            installedVersionName = packageVersionName(installed),
            installedVersionCode = packageVersionCode(installed)
        )
    }

    /**
     * 只查询固定官方 latest API，并在消费或拒绝响应时关闭底层连接。
     *
     * @return 已验证来源和资产约束的稳定版发布。
     */
    private fun fetchRelease(): AppUpdateRelease {
        val response = try {
            transport.execute(
                HttpRequest(
                    url = AppUpdatePolicy.RELEASE_API_URL,
                    method = "GET",
                    headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to USER_AGENT),
                    redirectPolicy = GitHubRedirectPolicy.NONE,
                    readTimeoutMillis = 15_000
                )
            )
        } catch (error: Exception) {
            checkInterrupted()
            throw IOException("无法连接 GitHub 检查汉化器更新，请稍后重试", error)
        }
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            response.body.close()
            val message = if (response.statusCode == 403 || response.statusCode == 429) {
                "GitHub 更新检查暂时受限，请稍后再试"
            } else {
                "GitHub 汉化器更新检查失败（HTTP ${response.statusCode}）"
            }
            throw IOException(message)
        }
        return try {
            AppUpdatePolicy.parseRelease(response.bodyText())
        } catch (error: Exception) {
            checkInterrupted()
            throw IOException("无法读取官方汉化器更新信息：${error.message.orEmpty()}", error)
        }
    }

    /**
     * 流式下载 APK，长度受元数据及固定上限共同限制，并定期响应线程取消。
     *
     * @param release 当前版本声明的官方文件信息。
     * @param destination 不对外共享的临时下载文件。
     * @param onProgress 接收下载百分比的中文回调。
     */
    private fun download(release: AppUpdateRelease, destination: File, onProgress: (String) -> Unit) {
        val response = try {
            transport.execute(
                HttpRequest(
                    url = release.assetUrl,
                    method = "GET",
                    headers = mapOf("Accept" to "application/octet-stream", "User-Agent" to USER_AGENT),
                    redirectPolicy = GitHubRedirectPolicy.RELEASE_ASSET,
                    readTimeoutMillis = 60_000
                )
            )
        } catch (error: Exception) {
            checkInterrupted()
            throw IOException("无法连接 GitHub 下载汉化器更新包", error)
        }
        response.body.use { input ->
            if (response.statusCode != HttpURLConnection.HTTP_OK) {
                throw IOException("汉化器更新包下载失败（HTTP ${response.statusCode}）")
            }
            if (response.contentLength >= 0 && response.contentLength != release.sizeBytes) {
                throw IOException("汉化器更新包下载长度与官方声明不一致")
            }
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                var lastPercent = -1
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > release.sizeBytes || total >= AppUpdatePolicy.MAX_APK_BYTES) {
                        throw IOException("汉化器更新包下载超过允许长度")
                    }
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / release.sizeBytes).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress("正在下载汉化器 ${release.versionName}：$percent%")
                    }
                }
                if (total != release.sizeBytes) throw IOException("汉化器更新包下载不完整，请稍后重试")
                output.fd.sync()
            }
        }
    }

    /**
     * 核对缓存或下载文件的实际长度及可选官方摘要，同时生成安装前复查用摘要。
     *
     * @param file 完成下载或待复用的 APK。
     * @param release 该 APK 对应的官方发布声明。
     * @return 实际文件的 SHA-256 摘要。
     */
    private fun verifyReleaseFile(file: File, release: AppUpdateRelease): String {
        if (!file.isFile || file.length() != release.sizeBytes) throw IOException("汉化器更新包长度校验失败")
        val hash = fileSha256(file)
        if (release.sha256 != null && hash != release.sha256) throw IOException("汉化器更新包 SHA-256 校验失败")
        return hash
    }

    /**
     * 以 Android 实际解析结果验证包身份、系统要求、标签版本和当前证书完整集合。
     *
     * @param file 已完成长度和摘要检查的 APK。
     * @param expectedVersion 官方标签对应的数字版本名。
     * @return 已确认身份与完整性的 APK 信息；是否仍需升级由最新系统安装状态单独判断。
     */
    @Suppress("DEPRECATION")
    private fun inspectApk(file: File, expectedVersion: String): PreparedAppUpdate {
        checkInterrupted()
        val manager = appContext.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("无法解析汉化器更新 APK，请重新下载")
        if (archive.packageName != BuildConfig.APPLICATION_ID) throw IOException("更新 APK 不是当前汉化器，已拒绝安装")
        val application = archive.applicationInfo ?: throw IOException("汉化器更新 APK 缺少应用信息")
        if (application.minSdkVersion > Build.VERSION.SDK_INT) throw IOException("此汉化器新版本不支持当前 Android 系统")
        val versionCode = packageVersionCode(archive)
        val versionName = archive.versionName ?: throw IOException("汉化器更新 APK 缺少版本名称")
        val versionMatches = try {
            !AppUpdatePolicy.isNewerVersion(versionName, expectedVersion)
                && !AppUpdatePolicy.isNewerVersion(expectedVersion, versionName)
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!versionMatches) throw IOException("汉化器更新 APK 的实际版本与官方标签不一致")
        val installed = readInstalledPackageInfo(flags)
        // 比较当前签名者全集；不把证书历史或仅有一个共同证书当成完全相同的签名。
        if (signerDigests(archive) != signerDigests(installed)) {
            throw IOException("更新包签名与当前汉化器不同，无法保留数据直接升级；已停止安装")
        }
        return PreparedAppUpdate(file, versionName, versionCode)
    }

    /**
     * 每次从系统重新读取宿主包信息，避免更新等待期间沿用旧的安装版本快照。
     *
     * @param flags 包信息查询标志；校验签名时传入当前系统支持的签名标志。
     * @return 当前已安装宿主的包信息；无法读取时终止更新而不猜测版本。
     */
    @Suppress("DEPRECATION")
    private fun readInstalledPackageInfo(flags: Int = 0): PackageInfo = try {
        appContext.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
    } catch (error: PackageManager.NameNotFoundException) {
        throw IOException("无法读取系统已安装的汉化器版本，请稍后重试", error)
    }

    /**
     * 取得系统包信息中的有效显示版本，缺失时保持失败关闭，禁止改用编译期常量猜测。
     *
     * @param info PackageManager 当前返回的包信息。
     * @return 不为空白的实际版本名称。
     */
    private fun packageVersionName(info: PackageInfo): String = info.versionName?.takeIf { it.isNotBlank() }
        ?: throw IOException("系统已安装的汉化器缺少版本名称，已停止更新")

    /**
     * 按当前系统能力读取完整安装版本号，保持 Android 8/8.1 兼容。
     *
     * @param info 已安装包或候选 APK 的系统解析结果。
     * @return Android 9 起包含 major 分量的长版本号，旧系统返回原整数版本号。
     */
    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }

    /**
     * 获取当前实际签名者的稳定摘要序列，兼容 Android 8/8.1 的旧签名接口。
     *
     * @param info 系统已解析的已安装包或 APK 信息。
     * @return 排序后的完整签名者 SHA-256 序列，保留数量以便精确比较。
     */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        if (signatures.isNullOrEmpty()) throw IOException("无法验证汉化器 APK 签名，已停止安装")
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }.sorted()
    }

    /**
     * 计算整包摘要并在每个读取块之间响应取消，避免退出页面后继续长期读取。
     *
     * @param file 需要校验完整性的本地 APK。
     * @return 小写十六进制 SHA-256。
     */
    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * 保存本次校验依据，用户授权安装后仍需用同一完整摘要复查。
     *
     * @param update 已校验并放入固定位置的更新包。
     * @param hash 准备阶段计算出的整包摘要。
     */
    private fun rememberPrepared(update: PreparedAppUpdate, hash: String) {
        preparedIntegrity = PreparedIntegrity(update, update.file.length(), hash)
    }

    /** 检测后台任务取消，并通过可识别的 IO 异常终止当前下载或校验。 */
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("汉化器更新检查已取消")
    }

    /**
     * 串行保护进程内所有 Activity 实例共享的固定缓存文件，等待时同样支持任务取消。
     *
     * @param action 持锁期间执行的准备或最终校验工作。
     * @return 本次工作返回的结果。
     */
    private fun <T> withCacheLock(action: () -> T): T {
        try {
            CACHE_LOCK.lockInterruptibly()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("汉化器更新检查已取消").apply { initCause(error) }
        }
        try {
            return action()
        } finally {
            CACHE_LOCK.unlock()
        }
    }

    /**
     * 将摘要字节转为固定长度的小写十六进制文本。
     *
     * @return 每个输入字节对应两个十六进制字符的结果。
     */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * 保存仅在当前准备会话有效的完整性依据，防止外部构造更新信息跳过准备阶段。
     *
     * @property update 当前会话已返回的固定更新包信息。
     * @property sizeBytes 准备时的完整文件长度。
     * @property sha256 准备时的实际整包 SHA-256。
     */
    private data class PreparedIntegrity(val update: PreparedAppUpdate, val sizeBytes: Long, val sha256: String)

    private companion object {
        private const val USER_AGENT = "limbuszhcn-android-app-updater"
        private const val BUFFER_BYTES = 64 * 1024
        // 同一进程的多个入口可能持有不同管理器，实例锁不能保护同一个 update.apk。
        private val CACHE_LOCK = ReentrantLock()
    }
}
