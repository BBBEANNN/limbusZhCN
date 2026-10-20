package com.example.limbuszhcn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.exists

/** 验证 API 403/429 的官方公开页回退、强校验、响应上限与同仓库重定向边界。 */
class GitHubReleasePageFallbackTest {
    private val base = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases"
    private val api = "https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases?per_page=1"
    private val version = "2026101602"
    private val filename = "LimbusLocalize_$version.7z"
    private val asset = "$base/download/$version/$filename"
    private val fragment = "$base/expanded_assets/$version"
    private val digest = "cd4adcb366244c189a921749fcdc075f21c0edc087f0e38808219a753a8b50c8"

    /** 验证两个允许回退的状态都读取实际片段地址、同一资产行的真实下载链接及 SHA。 */
    @Test
    fun resolvesOfficialAssetAndDigestAfterApi403Or429() {
        for (status in listOf(403, 429)) {
            val transport = transport(status)
            val release = GitHubTranslationReleaseClient(base, transport).fetchRelease()

            assertEquals(version.toLong(), release.version)
            assertEquals(asset, release.assetUrl)
            assertEquals(digest, release.sha256)
            assertEquals(listOf(api, base, fragment), transport.requests.map { it.url })
            assertTrue(transport.requests.all { it.headers.keys.none { key -> key.equals("Authorization", true) } })
        }
    }

    /** 验证明确不存在、无权访问和其它服务错误不触发网页回退。 */
    @Test
    fun doesNotMaskMissingPrivateOrOtherApiErrors() {
        for (status in listOf(401, 404, 500)) {
            val transport = transport(status)
            assertThrows(TranslationHttpException::class.java) {
                GitHubTranslationReleaseClient(base, transport).fetchRelease()
            }
            assertEquals(listOf(api), transport.requests.map { it.url })
        }
        val transport = transport(403, pageStatus = 404)
        val error = assertThrows(TranslationHttpException::class.java) {
            GitHubTranslationReleaseClient(base, transport).fetchRelease()
        }
        assertTrue(error.message.orEmpty().contains("404"))
        assertEquals(listOf(api, base), transport.requests.map { it.url })
    }

    /** 验证只使用官方 include-fragment，普通正文、注释与脚本不能伪造发布版本。 */
    @Test
    fun ignoresBodyLinksCommentsAndScriptFragments() {
        val fake = "https://evil.example/releases/expanded_assets/999"
        val page = """
            <!-- <include-fragment src="$fake"></include-fragment> -->
            <script>const x = '<include-fragment src="$fake">';</script>
            <a href="$base/download/999/LimbusLocalize_999.7z">download</a>
            ${page()}
        """.trimIndent()
        val release = GitHubTranslationReleaseClient(base, transport(403, page = page)).fetchRelease()
        assertEquals(version.toLong(), release.version)
        assertThrows(TranslationProtocolException::class.java) {
            GitHubTranslationReleaseClient(base, transport(403, page = "<a href='$asset'>$filename</a>")).fetchRelease()
        }
    }

    /** 验证片段与资产均不能离开当前仓库、当前 tag、HTTPS 或精确归档路径。 */
    @Test
    fun rejectsForeignFragmentsAndAssets() {
        for (fragmentUrl in listOf(
            "https://evil.example/x", "$base/expanded_assets/2026101602?token=x",
            "https://github.com/other/project/releases/expanded_assets/$version"
        )) {
            val transport = transport(403, page = "<include-fragment src='$fragmentUrl'></include-fragment>")
            assertThrows(TranslationProtocolException::class.java) {
                GitHubTranslationReleaseClient(base, transport).fetchRelease()
            }
            assertEquals(2, transport.requests.size)
        }
        for (assetUrl in listOf(
            "https://evil.example/$filename", "$base/download/999/$filename",
            "https://github.com/other/project/releases/download/$version/$filename",
            "$asset?token=x", asset.replace("https:", "http:"), "$base/download/$version/Other.7z"
        )) {
            assertThrows(TranslationProtocolException::class.java) {
                GitHubTranslationReleaseClient(base, transport(403, assets = assets(assetUrl))).fetchRelease()
            }
        }
    }

    /** 验证网页不能借用 ZIP、相邻行或错误格式的摘要，缺失 SHA 时必须失败。 */
    @Test
    fun requiresHashBoundToExactArchiveRow() {
        for (assetHtml in listOf(
            assets(hash = "sha256:bad"),
            assets().replace("Copy to clipboard digest for $filename", "Copy to clipboard digest for other.zip"),
            "<li data-view-component='true' class='Box-row'><a href='$asset'>$filename</a></li>" + assets("$asset.zip"),
            "<a href='$asset'>$filename</a><clipboard-copy aria-label='Copy to clipboard digest for $filename' value='sha256:$digest'></clipboard-copy>"
        )) {
            assertThrows(TranslationProtocolException::class.java) {
                GitHubTranslationReleaseClient(base, transport(403, assets = assetHtml)).fetchRelease()
            }
        }
    }

    /** 验证固定 tag 不能被页面偷偷替换为其它版本，latest 仍使用官方 latest 路径。 */
    @Test
    fun keepsPinnedAndLatestReleaseSelection() {
        val pinned = "$base/tag/$version"
        val pinnedApi = api.substringBefore('?') + "/tags/$version"
        val transport = PageTransport(mapOf(
            pinnedApi to { response(429, "") }, pinned to { response(200, page()) },
            fragment to { response(200, assets()) }
        ))
        assertEquals(version.toLong(), GitHubTranslationReleaseClient(pinned, transport).fetchRelease().version)
        val wrong = PageTransport(mapOf(
            pinnedApi to { response(403, "") },
            pinned to { response(200, page().replace(version, "2026101601")) }
        ))
        assertThrows(TranslationProtocolException::class.java) {
            GitHubTranslationReleaseClient(pinned, wrong).fetchRelease()
        }
        assertEquals("$base/latest", GitHubReleaseSource.parse("$base/latest").pageUrl)
        assertEquals(api.substringBefore('?') + "/latest", GitHubReleaseSource.parse("$base/latest").apiUrl)
    }

    /** 验证固定 tag 的 API 成功响应只能返回同一发布，不能静默采用其它 tag。 */
    @Test
    fun requiresApiResponseToMatchExplicitTag() {
        val pinnedApi = api.substringBefore('?') + "/tags/$version"
        val body = """{"tag_name":"$version","assets":[{
            "url":"https://api.github.com/repos/LocalizeLimbusCompany/LocalizeLimbusCompany/releases/assets/1",
            "name":"$filename","digest":"sha256:$digest","browser_download_url":"$asset"
        }]}""".trimIndent()
        val accepted = PageTransport(mapOf(pinnedApi to { response(200, body) }))
        assertEquals(version.toLong(), GitHubTranslationReleaseClient("$base/tag/$version", accepted).fetchRelease().version)
        for (source in listOf("$base/tag/$version", pinnedApi)) {
            val rejected = PageTransport(mapOf(pinnedApi to { response(200, body.replace(version, "2026101601")) }))
            val error = assertThrows(TranslationProtocolException::class.java) {
                GitHubTranslationReleaseClient(source, rejected).fetchRelease()
            }
            assertTrue(error.message.orEmpty().contains("different tag"))
            assertEquals(listOf(pinnedApi), rejected.requests.map { it.url })
        }
    }

    /** 验证 DNS 大小写兼容覆盖来源、资产、片段与重定向，同时不改写 rawPath 或放宽凭据。 */
    @Test
    fun acceptsMixedCaseHostsWithoutChangingRawUrlBoundaries() {
        val mixedBase = base.replace("github.com", "GiThUb.CoM")
        val mixedAsset = asset.replace("github.com", "GitHub.COM")
        val mixedFragment = fragment.replace("github.com", "GITHUB.com")
        val source = GitHubReleaseSource.parse(mixedBase)
        assertEquals(base, source.pageUrl)
        assertEquals(api, GitHubReleaseSource.parse(api.replace("api.github.com", "API.GitHub.COM")).apiUrl)
        assertEquals(mixedAsset, source.validatedAssetUrl(mixedAsset, version, version.toLong()))
        assertEquals(version to mixedFragment, source.expandedAssets(mixedFragment))
        assertTrue(allowsGitHubRedirect(mixedBase, "$mixedBase/tag/$version", GitHubRedirectPolicy.RELEASE_PAGE))
        assertTrue(allowsGitHubRedirect(mixedAsset, "https://Release-Assets.GitHubusercontent.COM/a?sig=x", GitHubRedirectPolicy.RELEASE_ASSET))
        val encoded = githubHttpsUri("https://GiThUb.CoM/a/b/releases/%2Fasset?x=%2f")
        assertEquals("/a/b/releases/%2Fasset", encoded.rawPath)
        assertEquals("x=%2f", encoded.rawQuery)
        assertThrows(TranslationProtocolException::class.java) {
            githubHttpsUri("https://user:pass@GiThUb.CoM/a/b/releases")
        }
        assertThrows(TranslationProtocolException::class.java) {
            GitHubReleaseSource.parse("https://GiThUb.CoM/a/b/releases/%74ag/1")
        }
        assertFalse(allowsGitHubRedirect(mixedAsset, "https://Release-Assets.GitHubusercontent.COM.evil.example/a", GitHubRedirectPolicy.RELEASE_ASSET))
    }

    /** 验证声明长度和实际响应流都受限制，不能通过 chunked/未知长度绕过上限。 */
    @Test
    fun rejectsOversizedPageAndFragmentBodies() {
        val limit = 2 * 1024 * 1024
        assertThrows(TranslationProtocolException::class.java) {
            response(200, "small", contentLength = limit.toLong() + 1).bodyText(limit)
        }
        assertThrows(TranslationProtocolException::class.java) {
            response(200, "x".repeat(limit + 1), contentLength = -1).bodyText(limit)
        }
        assertThrows(TranslationProtocolException::class.java) {
            GitHubTranslationReleaseClient(base, transport(403, assets = "x".repeat(512 * 1024 + 1))).fetchRelease()
        }
    }

    /** 验证网页 SHA 一直传入下载校验，坏包不会被发布为可用缓存。 */
    @Test
    fun keepsFallbackArchiveHashValidation() {
        val transport = transport(403)
        val release = GitHubTranslationReleaseClient(base, transport).fetchRelease()
        val wrongArchive = PageTransport(mapOf(asset to { response(200, "wrong archive") }))
        val directory = Files.createTempDirectory("github-page-fallback")
        assertThrows(DownloadValidationException::class.java) {
            TranslationPackageDownloader(wrongArchive, maxAttempts = 1).download(DownloadRequest(
                release.assetUrl, version, release.sha256, directory
            ))
        }
        assertFalse(directory.resolve(filename).exists())
        assertFalse(directory.resolve("$filename.tmp").exists())
    }

    /** 验证 metadata 只在同仓库 release 路径跳转，资产只允许明确官方 HTTPS 主机。 */
    @Test
    fun confinesEveryRedirectToItsPurpose() {
        val page = GitHubRedirectPolicy.RELEASE_PAGE
        val download = GitHubRedirectPolicy.RELEASE_ASSET
        assertTrue(allowsGitHubRedirect("$base/latest", "$base/tag/$version", page))
        assertFalse(allowsGitHubRedirect(base, "https://github.com/other/project/releases", page))
        assertFalse(allowsGitHubRedirect(base, "https://github.com/login", page))
        assertFalse(allowsGitHubRedirect(base, "https://evil.example/releases", page))
        assertTrue(allowsGitHubRedirect(asset, "https://release-assets.githubusercontent.com/asset?id=1", download))
        for (target in listOf("http://release-assets.githubusercontent.com/a", "https://evil.example/a",
            "https://release-assets.githubusercontent.com.evil.example/a", "https://user:pass@release-assets.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com:8443/a", "$base/download/999/$filename")) {
            assertFalse(allowsGitHubRedirect(asset, target, download))
        }
    }

    /** 验证来源只接受精确的公开 Releases 路径，不把下载页、任意 API 或凭据当更新源。 */
    @Test
    fun rejectsAmbiguousReleaseSourcesBeforeNetwork() {
        for (url in listOf("http://github.com/a/b/releases", "https://user:pass@github.com/a/b/releases",
            "https://github.com/a/b/releases/download/1/archive.7z", "https://api.github.com/a/b/releases",
            "https://api.github.com/repos/a/b/releases/assets/1", "https://github.com/a/b/releases?token=x")) {
            assertThrows(TranslationProtocolException::class.java) { GitHubReleaseSource.parse(url) }
        }
    }

    private fun page(): String = """<include-fragment loading="lazy" src="$fragment" data-view-component="true"></include-fragment>"""

    private fun assets(url: String = asset, hash: String = "sha256:$digest"): String = """
        <ul data-view-component="true"><li data-view-component="true" class="Box-row d-flex">
        <a href="$url" rel="nofollow" data-turbo="false"><span>$filename</span></a>
        <clipboard-copy aria-label="Copy to clipboard digest for $filename" value="$hash"></clipboard-copy>
        </li></ul>
    """.trimIndent()

    private fun transport(status: Int, page: String = page(), assets: String = assets(), pageStatus: Int = 200): PageTransport =
        PageTransport(mapOf(api to { response(status, "") }, base to { response(pageStatus, page) },
            fragment to { response(200, assets) }))

    private fun response(status: Int, body: String, contentLength: Long = -1): HttpResponse =
        HttpResponse(status, contentLength, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
}

/** 按完整 URL 记录测试请求，每次返回新响应流，避免跨请求复用已关闭的正文。 */
private class PageTransport(private val responses: Map<String, () -> HttpResponse>) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses[request.url]?.invoke() ?: error("Unexpected public request")
    }
}
