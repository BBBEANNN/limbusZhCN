package com.example.limbuszhcn.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证汉化器自身更新的版本选择和官方 Release 资产边界，防止错误安装、降级或下载地址被替换。
 */
class AppUpdatePolicyTest {
    private val tag = "v1.4"
    private val assetName = "LimbusZhCN-$tag.apk"
    private val releasesUrl = "https://github.com/BBBEANNN/limbusZhCN/releases"
    private val assetUrl = "$releasesUrl/download/$tag/$assetName"
    private val digest = "abcdef0123456789".repeat(4)

    /** 验证真实发布资产的 tag、展示版本、下载地址、大小和摘要可以一并保留。 */
    @Test
    fun parsesStableOfficialRelease() {
        val release = AppUpdatePolicy.parseRelease(releaseJson().toString())

        assertEquals(tag, release.tag)
        assertEquals("1.4", release.versionName)
        assertEquals(assetName, release.assetName)
        assertEquals(assetUrl, release.assetUrl)
        assertEquals(131_359_631L, release.sizeBytes)
        assertEquals(digest, release.sha256)
    }

    /** 验证按数字分量比较版本，避免字符串排序把 1.10 错判成低于 1.9。 */
    @Test
    fun comparesNumericComponentsAndOptionalVPrefix() {
        assertTrue(AppUpdatePolicy.isNewerVersion("v1.10", "1.9"))
        assertTrue(AppUpdatePolicy.isNewerVersion("2.0", "v1.99"))
        assertTrue(AppUpdatePolicy.isNewerVersion("1.5.1", "1.5"))
        assertTrue(AppUpdatePolicy.isNewerVersion("1.5.0.1", "v1.5"))
    }

    /** 验证尾部零分量不构成升级，已安装版本较新时也不能启动降级安装。 */
    @Test
    fun rejectsEquivalentVersionsAndDowngrades() {
        assertFalse(AppUpdatePolicy.isNewerVersion("1.5.0", "v1.5"))
        assertFalse(AppUpdatePolicy.isNewerVersion("v1.5", "1.5.0.0"))
        assertFalse(AppUpdatePolicy.isNewerVersion("v1.4", "1.5"))
        assertFalse(AppUpdatePolicy.isNewerVersion("1.9", "1.10"))
        assertFalse(AppUpdatePolicy.isNewerVersion("1.5", "1.5.1"))
    }

    /** 验证旧代码仍处于 1.5 时，系统已安装的 1.5.1 不能再次被当成待安装新版。 */
    @Test
    fun actualInstalledVersionRejectsRepeatedSameVersionInstallation() {
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.5.1", 7L, "1.5.1", 7L))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("v1.5.1", 7L, "1.5.1.0", 7L))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.5.1", 7L, "1.5.2", 8L))
        assertTrue(AppUpdatePolicy.isInstallableUpgrade("1.5.2", 8L, "1.5.1", 7L))
    }

    /** 验证名称更新但安装号相同或较旧的错误发布不能进入系统安装器。 */
    @Test
    fun requiresBothDisplayAndInstallationVersionsToIncrease() {
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.6", 7L, "1.5.1", 7L))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.6", 6L, "1.5.1", 7L))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.5.1", 8L, "1.5.1", 7L))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.5", 8L, "1.5.1", 7L))
    }

    /** 验证下载或授权期间外部完成升级后，复查必须使用新的系统快照取消旧待安装项。 */
    @Test
    fun installingWhileWaitingInvalidatesPreparedUpgrade() {
        val candidateName = "1.5.2"
        val candidateCode = 8L
        assertTrue(AppUpdatePolicy.isInstallableUpgrade(candidateName, candidateCode, "1.5.1", 7L))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade(candidateName, candidateCode, "1.5.2", 8L))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade(candidateName, candidateCode, "1.6", 9L))
    }

    /** 验证 Android 9 起的长版本号不会被截断为 Int，导致错误允许覆盖同版或更高版本。 */
    @Test
    fun comparesFullLongInstallationVersionCodes() {
        val installedCode = Int.MAX_VALUE.toLong() + 1L
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.6", 9L, "1.5.1", installedCode))
        assertFalse(AppUpdatePolicy.isInstallableUpgrade("1.6", installedCode, "1.5.1", installedCode))
        assertTrue(AppUpdatePolicy.isInstallableUpgrade("1.6", installedCode + 1L, "1.5.1", installedCode))
    }

    /** 验证较长的合法数字分量不会溢出，从而把更新方向颠倒。 */
    @Test
    fun comparesVersionsWithoutLongOverflow() {
        val largeVersion = "1.9223372036854775808"
        assertTrue(AppUpdatePolicy.isNewerVersion(largeVersion, "1.9223372036854775807"))
        assertFalse(AppUpdatePolicy.isNewerVersion("1.9223372036854775807", largeVersion))
    }

    /** 验证预发布标记、缺失分量及路径内容不能被当成稳定版本解析。 */
    @Test
    fun rejectsMalformedOrUnstableVersionNames() {
        val invalidVersions = listOf("", "v", "V1.5", "v1..5", "1.5.", "1.5-rc.1", "1.5beta", "../v1.5")
        for (invalid in invalidVersions) {
            assertThrows("候选版本应拒绝：$invalid", IllegalArgumentException::class.java) {
                AppUpdatePolicy.isNewerVersion(invalid, "1.4")
            }
            assertThrows("已安装版本应拒绝：$invalid", IllegalArgumentException::class.java) {
                AppUpdatePolicy.isNewerVersion("1.5", invalid)
            }
            val json = releaseJson().put("tag_name", invalid)
            assertThrows("发布 tag 应拒绝：$invalid", IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(json.toString())
            }
        }
    }

    /** 验证草稿和预发布即使有完整 APK，也不能进入普通用户的稳定版更新流程。 */
    @Test
    fun rejectsDraftsAndPrereleases() {
        for (flag in listOf("draft", "prerelease")) {
            val json = releaseJson().put(flag, true)
            assertThrows("不应安装 $flag 发布", IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(json.toString())
            }
        }
    }

    /** 验证没有 APK 或存在多个 APK 时停止解析，避免自动挑错架构或错误变体。 */
    @Test
    fun requiresExactlyOneApkAsset() {
        val noApk = releaseJson().put("assets", JSONArray().put(JSONObject().put("name", "source.7z")))
        val secondApk = JSONObject()
            .put("name", "LimbusZhCN-other.apk")
            .put("browser_download_url", "$releasesUrl/download/$tag/LimbusZhCN-other.apk")
            .put("size", 1024)
        val multipleApks = releaseJson().apply { getJSONArray("assets").put(secondApk) }

        for (json in listOf(noApk, multipleApks, releaseJson().put("assets", JSONArray()))) {
            assertThrows(IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(json.toString())
            }
        }
    }

    /** 验证 APK 名称必须绑定当前 tag，不能用调试包、旧版包或同后缀的任意文件替代。 */
    @Test
    fun requiresExactReleaseApkName() {
        for (name in listOf("app-debug.apk", "LimbusZhCN-v1.3.apk", "LimbusZhCN-1.4.apk", "Other-$tag.apk")) {
            val json = releaseJson()
            json.getJSONArray("assets").getJSONObject(0)
                .put("name", name)
                .put("browser_download_url", "$releasesUrl/download/$tag/$name")

            assertThrows("资产名称应拒绝：$name", IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(json.toString())
            }
        }
    }

    /** 验证字段顺序和非 APK 附件不会影响结构化解析或把无关压缩包识别成安装包。 */
    @Test
    fun ignoresFieldOrderAndUnrelatedArchives() {
        val json = """
            {
              "assets":[
                {"name":"resources.7z","browser_download_url":"https://example.com/resources.7z"},
                {
                  "digest":"sha256:$digest",
                  "size":131359631,
                  "browser_download_url":"$assetUrl",
                  "name":"$assetName"
                }
              ],
              "body":"此发布同时提供资源附件",
              "prerelease":false,
              "html_url":"$releasesUrl/tag/$tag",
              "draft":false,
              "tag_name":"$tag"
            }
        """.trimIndent()

        assertEquals(assetUrl, AppUpdatePolicy.parseRelease(json).assetUrl)
    }

    /** 验证下载地址必须精确指向公开仓库当前 tag 的指定 APK。 */
    @Test
    fun validatesOfficialAssetUrl() {
        assertEquals(assetUrl, AppUpdatePolicy.validateAssetUrl(assetUrl, tag, assetName))
        val invalidUrls = listOf(
            assetUrl.replace("https://", "http://"),
            assetUrl.replace("github.com", "github.com.evil.example"),
            assetUrl.replace("BBBEANNN", "another-owner"),
            assetUrl.replace("/limbusZhCN/", "/another-repository/"),
            assetUrl.replace("/download/$tag/", "/download/v1.3/"),
            assetUrl.replace(assetName, "Other-$tag.apk"),
            assetUrl.replace("/download/", "/download/../download/"),
            "$assetUrl/extra",
            "$assetUrl?download=1",
            "$assetUrl#asset",
            assetUrl.replace("github.com", "github.com:443"),
            assetUrl.replace("github.com", "someone@github.com"),
            assetUrl.replace("github.com", "github.com@evil.example")
        )
        for (invalidUrl in invalidUrls) {
            assertThrows("下载地址应拒绝：$invalidUrl", IllegalArgumentException::class.java) {
                AppUpdatePolicy.validateAssetUrl(invalidUrl, tag, assetName)
            }
            val json = releaseJson()
            json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", invalidUrl)
            assertThrows("发布解析也必须验证下载地址：$invalidUrl", IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(json.toString())
            }
        }
    }

    /** 验证公开发布页若存在，也必须属于同一仓库和版本，避免混用不同发布的元数据。 */
    @Test
    fun rejectsReleasePageThatDoesNotMatchTagOrRepository() {
        for (url in listOf(
            "$releasesUrl/tag/v1.3",
            "$releasesUrl/tag/$tag?download=1",
            "https://github.com/another-owner/limbusZhCN/releases/tag/$tag",
            "https://evil.example/BBBEANNN/limbusZhCN/releases/tag/$tag"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(releaseJson().put("html_url", url).toString())
            }
        }
    }

    /** 验证下载大小必须为正且严格低于上限，防止空文件或超限响应进入安装阶段。 */
    @Test
    fun rejectsEmptyOrOversizedApks() {
        for (size in listOf(-1L, 0L, AppUpdatePolicy.MAX_APK_BYTES.toLong(), AppUpdatePolicy.MAX_APK_BYTES.toLong() + 1)) {
            val json = releaseJson()
            json.getJSONArray("assets").getJSONObject(0).put("size", size)
            assertThrows("安装包大小应拒绝：$size", IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(json.toString())
            }
        }
    }

    /** 验证摘要规范化为小写，便于与下载完成后的实际 SHA-256 稳定比较。 */
    @Test
    fun normalizesUppercaseDigest() {
        val json = releaseJson()
        json.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:${digest.uppercase()}")

        assertEquals(digest, AppUpdatePolicy.parseRelease(json.toString()).sha256)
    }

    /** 验证旧发布允许缺失摘要，但已有摘要不能是其他算法、错误长度或非十六进制内容。 */
    @Test
    fun rejectsMalformedNonemptyDigests() {
        for (value in listOf("sha256:", "sha1:$digest", "SHA256:$digest", "sha256:${digest.dropLast(1)}", "sha256:${"g".repeat(64)}")) {
            val json = releaseJson()
            json.getJSONArray("assets").getJSONObject(0).put("digest", value)
            assertThrows("摘要应拒绝：$value", IllegalArgumentException::class.java) {
                AppUpdatePolicy.parseRelease(json.toString())
            }
        }
    }

    /** 验证尚未提供 GitHub digest 的历史发布仍可解析，并交给安装阶段检查包名和签名。 */
    @Test
    fun allowsMissingNullOrEmptyDigest() {
        for (value in listOf(null, JSONObject.NULL, "")) {
            val json = releaseJson()
            // JSONObject.put(key, null) 会删除字段；JSON null 与空串分别覆盖另外两种历史响应。
            json.getJSONArray("assets").getJSONObject(0).put("digest", value)
            assertNull(AppUpdatePolicy.parseRelease(json.toString()).sha256)
        }
    }

    /**
     * 创建包含真实仓库和发布文件结构的响应，使异常用例只修改它需要验证的边界。
     *
     * @return 可独立修改的稳定发布 JSON 对象。
     */
    private fun releaseJson(): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("draft", false)
        .put("prerelease", false)
        .put("html_url", "$releasesUrl/tag/$tag")
        .put("assets", JSONArray().put(JSONObject()
            .put("name", assetName)
            .put("browser_download_url", assetUrl)
            .put("size", 131_359_631L)
            .put("digest", "sha256:$digest")))
}
