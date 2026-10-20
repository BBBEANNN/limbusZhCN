package com.example.limbuszhcn.update

import java.net.URI

/** 公开元数据不自动跳转；网页与资产分别使用互不混用的有限重定向白名单。 */
internal enum class GitHubRedirectPolicy {
    /** API 失败直接报告，不跟随到其它仓库或登录页。 */
    NONE,
    /** 仅允许当前仓库的官方 Releases 页面/资产片段。 */
    RELEASE_PAGE,
    /** 仅允许原 GitHub 资产路径及显式列出的官方资产 CDN。 */
    RELEASE_ASSET
}

/**
 * 按请求用途验证每一跳，不让 HTTPS 自动重定向绕过仓库与官方资产主机边界。
 *
 * @param originalUrl 最初已经验证的 GitHub 地址
 * @param targetUrl 解析 Location 后的完整目标 URL
 * @param policy 当前请求允许的重定向类别
 * @return 目标仍在该请求的明确边界内时返回真
 */
internal fun allowsGitHubRedirect(originalUrl: String, targetUrl: String, policy: GitHubRedirectPolicy): Boolean {
    val original = runCatching { githubHttpsUri(originalUrl) }.getOrNull() ?: return false
    val target = runCatching { githubHttpsUri(targetUrl) }.getOrNull() ?: return false
    // DNS 主机名不区分大小写；只规范化比较值，保留原 URI 的路径编码与凭据检查。
    val originalHost = original.host.lowercase()
    val targetHost = target.host.lowercase()
    if (originalHost != "github.com" || target.rawFragment != null) return false
    if (policy == GitHubRedirectPolicy.RELEASE_ASSET) {
        if (targetHost in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) {
            return true
        }
        return targetHost == "github.com" && target.rawPath == original.rawPath && target.rawQuery == null
    }
    if (policy != GitHubRedirectPolicy.RELEASE_PAGE || targetHost != "github.com" || target.rawQuery != null) return false
    val originParts = original.rawPath.trim('/').split('/')
    val parts = target.rawPath.trim('/').split('/')
    if (originParts.size < 3 || parts.size < 3 || !parts[0].equals(originParts[0], true)
        || !parts[1].equals(originParts[1], true) || parts[2] != "releases"
    ) return false
    return parts.size == 3 || (parts.size == 4 && parts[3] == "latest")
        || (parts.size == 5 && parts[3] in setOf("tag", "expanded_assets") && parts[4].matches(Regex("[0-9]+")))
}

/**
 * 保存已验证的公开 GitHub 仓库与可选数字 tag，供 API 和网页回退共享同一来源边界。
 *
 * @property owner 仓库所有者，不含路径分隔符或凭据
 * @property repository 仓库名称，不含路径分隔符
 * @property tag 指定的数字 Release tag；为空时查询发布列表
 * @property latest 是否使用 GitHub 明确的 latest 发布语义
 */
internal data class GitHubReleaseSource(
    val owner: String,
    val repository: String,
    val tag: String? = null,
    val latest: Boolean = false
) {
    /** API 请求地址，列表查询只取第一项，不接受任意 API 子路径。 */
    val apiUrl: String
        get() = "https://api.github.com/repos/$owner/$repository/releases" + when {
            tag != null -> "/tags/$tag"
            latest -> "/latest"
            else -> "?per_page=1"
        }

    /** 同一仓库的公开发布页，只有 API 限流/拒绝后才访问。 */
    val pageUrl: String
        get() = "https://github.com/$owner/$repository/releases" + when {
            tag != null -> "/tag/$tag"
            latest -> "/latest"
            else -> ""
        }

    /**
     * 验证主归档链接属于当前仓库、当前 tag 和精确的数字版本文件。
     *
     * @param url API 或官方资产行中实际提供的 URL，不自行拼造下载地址
     * @param releaseTag 当前页面/API 声明的 tag
     * @param version 已解析的数字版本
     * @return 安全的公开 GitHub 下载地址
     */
    fun validatedAssetUrl(url: String, releaseTag: String, version: Long): String {
        val uri = githubHttpsUri(url)
        val expectedPath = "/$owner/$repository/releases/download/$releaseTag/LimbusLocalize_$version.7z"
        if (!uri.host.equals("github.com", ignoreCase = true) || !uri.rawPath.equals(expectedPath, ignoreCase = true)
            || uri.rawQuery != null || uri.rawFragment != null
        ) {
            throw TranslationProtocolException("GitHub asset does not match the selected repository, tag and archive")
        }
        return uri.toASCIIString()
    }

    /**
     * 解析官方发布页明确声明的 expanded_assets 地址，不从正文普通链接猜测版本。
     *
     * @param value include-fragment 的 src 属性
     * @return 同仓库数字 tag 和资产片段地址；其它片段返回空值
     */
    fun expandedAssets(value: String): Pair<String, String>? {
        val uri = runCatching { githubHttpsUri(URI(pageUrl).resolve(value).toString()) }.getOrNull()
            ?: return null
        val prefix = "/$owner/$repository/releases/expanded_assets/"
        if (!uri.host.equals("github.com", ignoreCase = true) || !uri.rawPath.startsWith(prefix, ignoreCase = true)
            || uri.rawQuery != null || uri.rawFragment != null
        ) return null
        val releaseTag = uri.rawPath.substring(prefix.length)
        if (!NUMERIC_TAG.matches(releaseTag) || releaseTag.toLongOrNull() == null) {
            throw TranslationProtocolException("GitHub public release tag is not numeric")
        }
        if (tag != null && tag != releaseTag) {
            throw TranslationProtocolException("GitHub public release page returned a different tag")
        }
        return releaseTag to uri.toASCIIString()
    }

    companion object {
        /**
         * 接受公开 GitHub Releases 页面或其等价 API 路径，拒绝凭据、旁路端点和多余路径。
         *
         * @param value 高级设置提供的公开 URL
         * @return 可安全用于 API 与官方网页查询的仓库来源
         */
        fun parse(value: String): GitHubReleaseSource {
            val uri = githubHttpsUri(value.trim())
            if (uri.rawFragment != null) throw TranslationProtocolException("GitHub release URL must not contain a fragment")
            val parts = uri.rawPath.trim('/').split('/')
            val api = uri.host.equals("api.github.com", ignoreCase = true)
            if ((api && parts.firstOrNull() != "repos") || uri.rawPath.contains("//")) {
                throw TranslationProtocolException("Unsupported GitHub release path")
            }
            val path = if (api && parts.firstOrNull() == "repos") parts.drop(1) else parts
            if ((!api && !uri.host.equals("github.com", ignoreCase = true)) || path.size < 3 || path[2] != "releases"
                || !OWNER.matches(path[0]) || !REPOSITORY.matches(path[1]) || path[1] in setOf(".", "..")
            ) throw TranslationProtocolException("Only exact public GitHub Releases URLs are supported")
            if (uri.rawQuery != null && !(api && path.size == 3 && uri.rawQuery == "per_page=1")) {
                throw TranslationProtocolException("Unsupported GitHub release query")
            }
            val tagPath = if (api) "tags" else "tag"
            val tag = when {
                path.size == 3 -> null
                path.size == 4 && path[3] == "latest" -> null
                path.size == 5 && path[3] == tagPath && NUMERIC_TAG.matches(path[4])
                    && path[4].toLongOrNull() != null -> path[4]
                else -> throw TranslationProtocolException("Unsupported GitHub release path or numeric tag")
            }
            return GitHubReleaseSource(path[0], path[1], tag, path.size == 4)
        }

        private val OWNER = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
        private val REPOSITORY = Regex("[A-Za-z0-9_.-]{1,100}")
        private val NUMERIC_TAG = Regex("[0-9]+")
    }
}

/**
 * 只读取 GitHub 官方发布页和其资产片段的有限回退，不执行 HTML、脚本或浏览器会话。
 *
 * @param source 已验证的公开仓库和 tag
 * @param transport 与 API/归档下载共享的 HTTPS 传输边界
 */
internal class GitHubReleasePageFallback(
    private val source: GitHubReleaseSource,
    private val transport: HttpTransport
) {
    /**
     * 从官方片段的同一资产行读取真实链接与强制 SHA-256，缺少任一项即失败。
     *
     * @return 数字版本、GitHub 实际发布的主归档 URL 和 SHA-256
     */
    fun fetchRelease(): GitHubTranslationRelease {
        val page = readPage(source.pageUrl, 2 * 1024 * 1024)
        val fragment = htmlTags(page)
            .filter { !it.closing && it.name == "include-fragment" }
            .mapNotNull { it.attributes["src"]?.let(source::expandedAssets) }
            .firstOrNull() ?: throw TranslationProtocolException("Missing official GitHub release asset fragment")
        val releaseTag = fragment.first
        val version = releaseTag.toLong()
        val expectedName = "LimbusLocalize_$version.7z"
        val assets = readPage(fragment.second, 512 * 1024)
        var rowDepth = 0
        var officialRow = false
        var assetUrl: String? = null
        var digest: String? = null
        for (tag in htmlTags(assets)) {
            if (tag.name == "li") {
                if (!tag.closing) {
                    if (rowDepth++ == 0) {
                        officialRow = tag.attributes["data-view-component"] == "true"
                            && tag.attributes["class"].orEmpty().split(Regex("\\s+")).contains("Box-row")
                        assetUrl = null
                        digest = null
                    }
                } else if (rowDepth > 0 && --rowDepth == 0 && officialRow && assetUrl != null) {
                    // 校验值必须来自这条资产行的官方复制按钮，不能误用 ZIP 或其它文件的摘要。
                    return GitHubTranslationRelease(version, assetUrl,
                        digest ?: throw TranslationProtocolException("Missing published SHA-256 for GitHub archive"))
                }
            } else if (rowDepth == 1 && officialRow && !tag.closing) {
                if (tag.name == "a") {
                    val href = tag.attributes["href"] ?: continue
                    val url = URI(fragment.second).resolve(href).toString()
                    val valid = runCatching { source.validatedAssetUrl(url, releaseTag, version) }.getOrNull()
                    if (valid != null) {
                        if (assetUrl != null && assetUrl != valid) throw TranslationProtocolException("Ambiguous GitHub asset row")
                        assetUrl = valid
                    }
                } else if (tag.name == "clipboard-copy"
                    && tag.attributes["aria-label"] == "Copy to clipboard digest for $expectedName"
                ) {
                    val value = tag.attributes["value"].orEmpty()
                    if (!SHA256.matches(value)) throw TranslationProtocolException("Invalid published GitHub SHA-256")
                    val hash = value.removePrefix("sha256:").lowercase()
                    if (digest != null && digest != hash) throw TranslationProtocolException("Conflicting GitHub SHA-256 values")
                    digest = hash
                }
            }
        }
        throw TranslationProtocolException("Official GitHub release does not contain $expectedName with SHA-256")
    }

    private fun readPage(url: String, maxBytes: Int): String {
        val response = transport.execute(HttpRequest(url, "GET", headers = mapOf(
            "Accept" to "text/html", "Accept-Language" to "en-US", "User-Agent" to "limbuszhcn-android"
        ), redirectPolicy = GitHubRedirectPolicy.RELEASE_PAGE, readTimeoutMillis = 30_000))
        if (response.statusCode != 200) {
            response.body.close()
            throw TranslationHttpException("HTTP ${response.statusCode} from public GitHub release page")
        }
        return response.bodyText(maxBytes)
    }

    companion object {
        private val SHA256 = Regex("sha256:[0-9a-fA-F]{64}")
    }
}

/** 校验 HTTPS、无凭据和标准端口；主机与路径权限由每个请求的语义进一步限定。 */
internal fun githubHttpsUri(value: String): URI {
    val uri = runCatching { URI(value) }.getOrElse { throw TranslationProtocolException("Invalid GitHub HTTPS URL") }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host == null
        || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)
    ) throw TranslationProtocolException("GitHub requests require HTTPS without credentials or custom ports")
    return uri
}

/** 只保存 HTML 标签结构和属性，不解释正文、不执行脚本，也不访问其中引用的资源。 */
private data class HtmlTag(val name: String, val closing: Boolean, val attributes: Map<String, String>)

/**
 * 扫描官方 HTML 的少量结构标签，跳过注释和原始文本区域，避免把正文伪链接当作资产元数据。
 *
 * @param html 已通过响应大小限制的 HTML
 * @return 按页面顺序产生的标签；属性引号内的尖括号不会提前截断标签
 */
private fun htmlTags(html: String): Sequence<HtmlTag> = sequence {
    var offset = 0
    while (offset < html.length) {
        val start = html.indexOf('<', offset)
        if (start < 0) break
        if (html.startsWith("<!--", start)) {
            val end = html.indexOf("-->", start + 4)
            offset = if (end < 0) html.length else end + 3
            continue
        }
        var end = start + 1
        var quote: Char? = null
        while (end < html.length) {
            val char = html[end]
            if (quote != null) { if (char == quote) quote = null }
            else if (char == '\'' || char == '"') quote = char
            else if (char == '>') break
            end++
        }
        if (end == html.length) break
        offset = end + 1
        var raw = html.substring(start + 1, end).trim()
        val closing = raw.startsWith('/')
        if (closing) raw = raw.drop(1).trimStart()
        val name = raw.takeWhile { it.isLetterOrDigit() || it == '-' }.lowercase()
        if (name.isEmpty()) continue
        if (!closing && name in setOf("script", "style", "template", "textarea", "xmp")) {
            val close = html.indexOf("</$name", offset, ignoreCase = true)
            offset = if (close < 0) html.length else close
            continue
        }
        val attributes = if (!closing && name in setOf("include-fragment", "li", "a", "clipboard-copy")) {
            parseHtmlAttributes(raw.substring(name.length))
        } else emptyMap()
        yield(HtmlTag(name, closing, attributes))
    }
}

/** 解析带引号的官方属性；重复属性和不完整引号会失败，避免与浏览器产生不同 URL 解释。 */
private fun parseHtmlAttributes(raw: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < raw.length) {
        while (index < raw.length && (raw[index].isWhitespace() || raw[index] == '/')) index++
        val start = index
        while (index < raw.length && !raw[index].isWhitespace() && raw[index] !in "=/><") index++
        if (index == start) break
        val name = raw.substring(start, index).lowercase()
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index >= raw.length || raw[index] != '=') continue
        index++
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index >= raw.length || raw[index] !in "\"'") throw TranslationProtocolException("Unquoted GitHub HTML attribute")
        val quote = raw[index++]
        val valueStart = index
        while (index < raw.length && raw[index] != quote) index++
        if (index >= raw.length) throw TranslationProtocolException("Incomplete GitHub HTML attribute")
        val value = raw.substring(valueStart, index++).decodeHtmlAttribute()
        if (result.put(name, value) != null) throw TranslationProtocolException("Duplicate GitHub HTML attribute")
    }
    return result
}

/** 解码 URL/摘要属性中常见实体；最终仍必须通过精确的仓库、路径和 SHA-256 校验。 */
private fun String.decodeHtmlAttribute(): String =
    replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
