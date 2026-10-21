package com.example.limbuszhcn.update

import org.json.JSONObject
import java.math.BigInteger
import java.net.URI

/**
 * 保存官方汉化器稳定版发布中已经过来源、大小与格式检查的 APK 信息。
 *
 * @property tag 官方 Release 的原始数字版本标签，例如 `v1.4`。
 * @property versionName 去除可选 `v` 前缀的数字版本名，供下载前比较。
 * @property assetName 此标签唯一的汉化器 APK 文件名。
 * @property assetUrl 同一官方仓库、标签和文件的公开下载地址。
 * @property sizeBytes GitHub 声明的完整 APK 字节数。
 * @property sha256 可选的整包 SHA-256，小写且不包含协议前缀。
 */
internal data class AppUpdateRelease(
    val tag: String,
    val versionName: String,
    val assetName: String,
    val assetUrl: String,
    val sizeBytes: Long,
    val sha256: String?
)

/**
 * 集中约束汉化器自身更新的官方来源与数字版本规则，不依赖 Android 环境。
 */
internal object AppUpdatePolicy {
    /** 每次打开汉化器查询的唯一官方稳定版元数据入口。 */
    const val RELEASE_API_URL = "https://api.github.com/repos/BBBEANNN/limbusZhCN/releases/latest"

    /** 自动下载必须严格小于此上限，防止异常响应耗尽手机存储。 */
    const val MAX_APK_BYTES = 512L * 1024 * 1024

    private const val RELEASE_ROOT = "https://github.com/BBBEANNN/limbusZhCN/releases"
    private val versionPattern = Regex("v?[0-9]+(?:\\.[0-9]+)*")
    private val digestPattern = Regex("sha256:[0-9a-fA-F]{64}")

    /**
     * 结构化读取官方稳定版及其唯一 APK，拒绝含糊资产或越过仓库的下载地址。
     *
     * @param json GitHub latest API 返回的完整 JSON 对象正文。
     * @return 已通过纯数据策略验证的稳定版下载信息。
     * @throws IllegalArgumentException 正文、版本、资产、地址或摘要不符合更新约定时抛出。
     */
    fun parseRelease(json: String): AppUpdateRelease {
        try {
            val release = JSONObject(json)
            require(release.opt("draft") == false && release.opt("prerelease") == false) {
                "汉化器更新仅接受正式发布版本"
            }
            val tag = requiredString(release, "tag_name")
            versionParts(tag)
            if (release.has("html_url")) {
                require(requiredString(release, "html_url") == "$RELEASE_ROOT/tag/$tag") {
                    "汉化器版本页面不属于官方仓库或当前版本"
                }
            }
            val assets = release.getJSONArray("assets")
            val apkAssets = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { requiredString(it, "name").endsWith(".apk", ignoreCase = true) }
            require(apkAssets.size == 1) { "官方版本必须包含且仅包含一个汉化器 APK" }
            val asset = apkAssets.single()
            val name = requiredString(asset, "name")
            require(name == "LimbusZhCN-$tag.apk") { "汉化器更新包名称与版本标签不一致" }
            val url = validateAssetUrl(requiredString(asset, "browser_download_url"), tag, name)
            // 不接受小数、字符串和负数，避免 JSON 数值截断后绕过长度限制。
            val sizeValue = asset.opt("size")
            require(sizeValue is Number && sizeValue.toString().matches(Regex("[0-9]+"))) {
                "汉化器更新包长度无效"
            }
            val size = sizeValue.toString().toLongOrNull()
            require(size != null && size > 0 && size < MAX_APK_BYTES) { "汉化器更新包长度超过允许范围" }
            val digest = when (val value = asset.opt("digest")) {
                null, JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotEmpty() }
                else -> throw IllegalArgumentException("汉化器更新包摘要格式无效")
            }
            require(digest == null || digestPattern.matches(digest)) { "汉化器更新包 SHA-256 格式无效" }
            return AppUpdateRelease(
                tag = tag,
                versionName = tag.removePrefix("v"),
                assetName = name,
                assetUrl = url,
                sizeBytes = size,
                sha256 = digest?.substringAfter(':')?.lowercase()
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("无法解析官方汉化器更新信息", error)
        }
    }

    /**
     * 按点分数字分量比较版本，缺少的尾部分量按零处理，避免按字符串误判 `1.10`。
     *
     * @param candidate 官方候选版本，允许小写 `v` 前缀。
     * @param installed 当前安装版本，允许小写 `v` 前缀。
     * @return 候选数字版本严格高于已安装版本时返回真。
     * @throws IllegalArgumentException 任一版本不是点分数字版本时抛出。
     */
    fun isNewerVersion(candidate: String, installed: String): Boolean {
        val candidateParts = versionParts(candidate)
        val installedParts = versionParts(installed)
        for (index in 0 until maxOf(candidateParts.size, installedParts.size)) {
            val comparison = (candidateParts.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(installedParts.getOrNull(index) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    /**
     * 将 APK 地址限制在官方仓库、当前标签与确切文件名，重定向由 HTTP 层另行约束。
     *
     * @param url Release 明确给出的公开 APK 地址。
     * @param tag 已验证格式的当前发布标签。
     * @param name 当前标签的唯一 APK 名称。
     * @return 保持原样且通过检查的 HTTPS 地址。
     * @throws IllegalArgumentException 地址含凭据、端口、查询参数或来源不匹配时抛出。
     */
    fun validateAssetUrl(url: String, tag: String, name: String): String {
        versionParts(tag)
        require(name == "LimbusZhCN-$tag.apk") { "汉化器更新包名称与版本标签不一致" }
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            throw IllegalArgumentException("汉化器更新包地址格式无效", error)
        }
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true)
            && uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
            && uri.rawPath == "/BBBEANNN/limbusZhCN/releases/download/$tag/$name"
        ) { "汉化器更新包地址不属于官方仓库或当前版本" }
        return url
    }

    /**
     * 读取明确的非空字符串，避免宽松 JSON 转换把其他类型当成版本或地址。
     *
     * @param json 当前被解析的对象。
     * @param field 必须存在且为字符串的字段名。
     * @return 字段原始字符串。
     */
    private fun requiredString(json: JSONObject, field: String): String {
        val value = json.opt(field)
        require(value is String && value.isNotEmpty()) { "汉化器更新信息缺少有效字段：$field" }
        return value
    }

    /**
     * 将版本拆为任意精度整数，避免较大版本分量溢出后变成错误的升级方向。
     *
     * @param version 未经过比较的数字版本名或标签。
     * @return 每个点分版本分量对应的整数列表。
     */
    private fun versionParts(version: String): List<BigInteger> {
        require(versionPattern.matches(version)) { "汉化器版本必须使用点分数字格式" }
        return version.removePrefix("v").split('.').map(::BigInteger)
    }
}
