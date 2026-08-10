package com.example.limbuszhcn.update

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream

/**
 * 通过公开的 GitHub Releases 检查、下载并解压最新汉化包。
 *
 * @param transport HTTP 传输实现；测试可注入内存响应，生产环境默认使用 HTTPS 连接。
 * @param extractor 负责安全解压和生成补丁清单的归档处理器。
 */
internal class TranslationUpdateManager(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val extractor: TranslationArchiveExtractor = TranslationArchiveExtractor()
) {
    /**
     * 查询 GitHub 最新 Release，校验并复用缓存归档，然后解压为统一补丁目录。
     *
     * @param request Releases 地址、缓存目录和可选的本地版本信息。
     * @return 已验证归档的解压结果和补丁清单。
     */
    fun installFromGitHub(request: InstallGitHubReleaseRequest): ExtractionResult {
        request.downloadCacheRoot.createDirectories()
        request.patchCacheRoot.createDirectories()

        val releaseClient = GitHubTranslationReleaseClient(request.releaseUrl, transport)
        logInfo("Fetching GitHub translation release url=${request.releaseUrl}")
        val release = releaseClient.fetchRelease()
        if (request.installedVersion != null && release.version <= request.installedVersion) {
            throw NoTranslationUpdateException(
                "No newer translation package: remote=${release.version}, installed=${request.installedVersion}"
            )
        }

        logInfo("Downloading GitHub translation package version=${release.version}")
        val archive = TranslationPackageDownloader(transport).download(
            DownloadRequest(
                url = release.assetUrl,
                version = release.version.toString(),
                expectedSha256 = release.sha256,
                destinationDirectory = request.downloadCacheRoot
            )
        )

        logInfo("Extracting GitHub translation package file=${archive.file}, fromCache=${archive.fromCache}")
        return extractor.extract(
            ExtractionRequest(
                archive = archive.file,
                cacheRoot = request.patchCacheRoot,
                version = release.version.toString(),
                expectedSha256 = release.sha256
            )
        )
    }

    companion object {
        private const val TAG = "LimbusZhCN"

        private fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }
    }
}

/**
 * 将公开的 GitHub Releases 页面转换为 API 请求，并解析最新汉化归档资产。
 *
 * @param releaseUrl GitHub Releases 页面或受支持的 GitHub API 地址。
 * @param transport 发起 GitHub API 请求的传输实现。
 */
internal class GitHubTranslationReleaseClient(
    private val releaseUrl: String,
    private val transport: HttpTransport = UrlConnectionHttpTransport()
) {
    /**
     * 获取目标仓库最新 Release 中的主汉化归档。
     *
     * @return 数字版本、公开下载地址和 GitHub 提供的可选 SHA-256。
     */
    fun fetchRelease(): GitHubTranslationRelease {
        val response = transport.execute(
            HttpRequest(
                url = releaseUrl.toGitHubApiReleaseUrl(),
                method = "GET",
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to DEFAULT_USER_AGENT
                )
            )
        )
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            throw TranslationHttpException("HTTP ${response.statusCode} from GitHub release API")
        }
        val body = response.bodyText().trim()
        val releaseJson = if (body.startsWith("[")) {
            body.firstJsonObjectInArray()
        } else {
            body
        }
        val tag = STRING_FIELD.find(releaseJson, "tag_name")
            ?: throw TranslationProtocolException("Missing tag_name in GitHub release response")
        val version = tag.toLongOrNull()
            ?: throw TranslationProtocolException("GitHub release tag is not numeric: $tag")
        val asset = pickMainAsset(releaseJson, version)
            ?: throw TranslationProtocolException("GitHub release does not contain LimbusLocalize_${version}.7z")
        return GitHubTranslationRelease(
            version = version,
            assetUrl = asset.url,
            sha256 = asset.sha256
        )
    }

    private fun pickMainAsset(json: String, version: Long): GitHubAsset? {
        val expected = "LimbusLocalize_${version}.7z"
        return ASSET_FIELDS.findAll(json)
            .mapNotNull { match ->
                val name = match.groupValues[1].jsonUnescape()
                if (!name.equals(expected, ignoreCase = true) && !name.endsWith(".7z", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val digest = match.groupValues[2].jsonUnescape()
                val url = match.groupValues[3].jsonUnescape()
                GitHubAsset(
                    name = name,
                    url = url,
                    sha256 = digest.removePrefix("sha256:").takeIf { it.isNotBlank() }
                )
            }
            .sortedWith(compareByDescending<GitHubAsset> { it.name.equals(expected, ignoreCase = true) })
            .firstOrNull()
    }

    private fun String.toGitHubApiReleaseUrl(): String {
        val trimmed = trim()
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw TranslationProtocolException("Invalid GitHub release URL")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw TranslationProtocolException("GitHub release URL must use HTTPS")
        }
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) {
            // Releases 地址属于公开配置，不允许嵌入用户名、口令或转发到自定义端口。
            throw TranslationProtocolException("GitHub release URL must not contain credentials or a custom port")
        }
        val host = uri.host?.lowercase()
            ?: throw TranslationProtocolException("GitHub release URL does not contain a host")
        if (host == "api.github.com" && uri.path.startsWith("/repos/") && uri.path.contains("/releases")) {
            return trimmed
        }
        if (host != "github.com") {
            throw TranslationProtocolException("Only github.com Releases URLs are supported")
        }
        val parts = uri.path
            .trim('/')
            .split('/')
        if (parts.size >= 5 && parts[2] == "releases" && parts[3] == "tag") {
            return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases/tags/${parts[4]}"
        }
        if (parts.size >= 3 && parts[2] == "releases") {
            return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases?per_page=1"
        }
        throw TranslationProtocolException("Unsupported GitHub release URL")
    }

    private fun String.firstJsonObjectInArray(): String {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && inString) {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"') {
                inString = !inString
                return@forEachIndexed
            }
            if (inString) {
                return@forEachIndexed
            }
            when (char) {
                '{' -> {
                    if (depth == 0) {
                        start = index
                    }
                    depth += 1
                }
                '}' -> {
                    if (depth > 0) {
                        depth -= 1
                        if (depth == 0 && start >= 0) {
                            return substring(start, index + 1)
                        }
                    }
                }
            }
        }
        throw TranslationProtocolException("GitHub releases response is empty")
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "limbuszhcn-android"
        private val ASSET_FIELDS = Regex(
            """"url"\s*:\s*"https://api\.github\.com/repos/[^"]+/releases/assets/[^"]+".*?""" +
                """"name"\s*:\s*"((?:\\.|[^"\\])*)".*?""" +
                """"digest"\s*:\s*"((?:\\.|[^"\\])*)".*?""" +
                """"browser_download_url"\s*:\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}

/**
 * 下载 GitHub Release 归档，并在可用时使用 SHA-256 校验和复用本地缓存。
 *
 * @param transport 归档下载使用的 HTTP 传输实现。
 * @param maxAttempts 短暂网络失败时允许的最大尝试次数。
 */
internal class TranslationPackageDownloader(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val maxAttempts: Int = 3
) {
    /**
     * 下载或复用指定版本的汉化归档。
     *
     * @param request 公开下载地址、版本、可选哈希和缓存目录。
     * @return 最终归档路径以及是否命中已验证缓存。
     */
    fun download(request: DownloadRequest): DownloadResult {
        request.destinationDirectory.createDirectories()
        val finalFile = request.destinationDirectory.resolve("LimbusLocalize_${request.version}.7z")
        if (finalFile.exists() && request.expectedSha256 != null && sha256(finalFile) == request.expectedSha256) {
            return DownloadResult(finalFile, fromCache = true)
        }

        val tempFile = request.destinationDirectory.resolve("${finalFile.fileName}.tmp")
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                tempFile.deleteIfExists()
                val response = transport.execute(
                    HttpRequest(
                        url = request.url,
                        method = "GET",
                        headers = mapOf("User-Agent" to "limbuszhcn-android")
                    )
                )
                if (response.statusCode != HttpURLConnection.HTTP_OK) {
                    throw TranslationHttpException("HTTP ${response.statusCode} while downloading GitHub package")
                }

                val bytes = copyResponse(response, tempFile)
                if (response.contentLength > 0 && bytes != response.contentLength) {
                    throw DownloadValidationException(
                        "Downloaded size mismatch: expected ${response.contentLength}, actual $bytes"
                    )
                }

                if (request.expectedSha256 != null) {
                    val actual = sha256(tempFile)
                    if (actual != request.expectedSha256) {
                        throw DownloadValidationException(
                            "Downloaded SHA-256 mismatch: expected ${request.expectedSha256}, actual $actual"
                        )
                    }
                }

                if (finalFile.exists()) {
                    finalFile.deleteIfExists()
                }
                tempFile.moveTo(finalFile)
                return DownloadResult(finalFile, fromCache = false)
            } catch (error: Exception) {
                lastError = error
                tempFile.deleteIfExists()
                if (attempt == maxAttempts - 1) {
                    throw error
                }
            }
        }

        throw lastError ?: DownloadValidationException("Download failed")
    }

    private fun copyResponse(response: HttpResponse, destination: Path): Long {
        var total = 0L
        response.body.use { input ->
            BufferedOutputStream(destination.outputStream()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                    total += read
                }
            }
        }
        return total
    }

    private fun sha256(path: Path): String = path.sha256()

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}

/**
 * 可替换的 HTTP 传输边界，用于隔离真实网络和单元测试响应。
 */
internal interface HttpTransport {
    /**
     * 执行一条 HTTP 请求。
     *
     * @param request 请求地址、方法、请求体和请求头。
     * @return 状态码、长度和可读取响应流。
     */
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * 基于 `HttpURLConnection` 的 GitHub HTTPS 传输实现。
 *
 * @param connectTimeoutMillis 建立连接的超时时间（毫秒）。
 * @param readTimeoutMillis 下载或读取 API 响应的超时时间（毫秒）。
 */
internal class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 300_000
) : HttpTransport {
    /**
     * 使用系统网络栈执行请求，并把响应流所有权交给调用方。
     *
     * @param request 待执行的 HTTP 请求。
     * @return 尚未消费的响应流及其元数据。
     */
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }

        val status = connection.responseCode
        val body = if (status in 200..299) connection.inputStream else connection.errorStream
        return HttpResponse(
            statusCode = status,
            contentLength = connection.contentLengthLong,
            body = body ?: ByteArray(0).inputStream()
        )
    }
}

/** 描述一次 GitHub Releases 汉化安装所需的公开地址与本地缓存目录。 */
internal data class InstallGitHubReleaseRequest(
    val releaseUrl: String,
    val downloadCacheRoot: Path,
    val patchCacheRoot: Path,
    val installedVersion: Long? = null
)

/** 描述 GitHub 归档下载地址、版本、校验值和缓存位置。 */
internal data class DownloadRequest(
    val url: String,
    val version: String,
    val expectedSha256: String?,
    val destinationDirectory: Path
)

/** 描述归档下载结果以及本次是否复用了已校验缓存。 */
internal data class DownloadResult(
    val file: Path,
    val fromCache: Boolean
)

/** 描述从 GitHub API 解析出的汉化 Release 主归档。 */
internal data class GitHubTranslationRelease(
    val version: Long,
    val assetUrl: String,
    val sha256: String?
)

private data class GitHubAsset(
    val name: String,
    val url: String,
    val sha256: String?
)

/** 描述传输边界可执行的 HTTP 请求。 */
internal data class HttpRequest(
    val url: String,
    val method: String,
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap()
)

/** 描述尚未被消费的 HTTP 响应。 */
internal data class HttpResponse(
    val statusCode: Int,
    val contentLength: Long,
    val body: java.io.InputStream
) {
    /**
     * 读取并关闭响应流。
     *
     * @return 使用 UTF-8 解码后的完整响应正文。
     */
    fun bodyText(): String = body.use { String(it.readBytes(), Charsets.UTF_8) }
}

/** 汉化更新流程可预期错误的共同基类。 */
internal open class TranslationUpdateException(message: String) : IOException(message)

/** GitHub Release 响应格式或地址不符合预期。 */
internal class TranslationProtocolException(message: String) : TranslationUpdateException(message)

/** GitHub API 或归档下载返回了非成功 HTTP 状态。 */
internal class TranslationHttpException(message: String) : TranslationUpdateException(message)

/** 下载长度或 SHA-256 校验失败。 */
internal class DownloadValidationException(message: String) : TranslationUpdateException(message)

/** GitHub 最新版本不高于调用方指定的本地版本。 */
internal class NoTranslationUpdateException(message: String) : TranslationUpdateException(message)

private object STRING_FIELD {
    fun find(json: String, field: String): String? =
        Regex(""""${Regex.escape(field)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.jsonUnescape()
}

internal fun Path.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 将字符串编码成可安全写入补丁清单的 JSON 字符串字面量。
 *
 * @return 包含双引号并完成控制字符转义的 JSON 值。
 */
internal fun String.jsonValue(): String {
    val escaped = buildString {
        this@jsonValue.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char < ' ') {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
    return "\"$escaped\""
}

private fun String.jsonUnescape(): String {
    val output = StringBuilder()
    var index = 0
    while (index < length) {
        val char = this[index++]
        if (char != '\\' || index >= length) {
            output.append(char)
            continue
        }

        when (val escaped = this[index++]) {
            '\\' -> output.append('\\')
            '"' -> output.append('"')
            '/' -> output.append('/')
            'b' -> output.append('\b')
            'f' -> output.append('\u000C')
            'n' -> output.append('\n')
            'r' -> output.append('\r')
            't' -> output.append('\t')
            'u' -> {
                val hex = substring(index, index + 4)
                output.append(hex.toInt(16).toChar())
                index += 4
            }
            else -> output.append(escaped)
        }
    }
    return output.toString()
}
