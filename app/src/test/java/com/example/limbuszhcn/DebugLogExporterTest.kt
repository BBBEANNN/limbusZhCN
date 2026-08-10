package com.example.limbuszhcn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证公开诊断包写入前的敏感信息脱敏与长度限制。
 */
class DebugLogExporterTest {
    /** 验证凭据、账号标识和 JWT 形态字符串都会被替换。 */
    @Test
    fun redactsCredentialsAccountsAndJwtValues() {
        val input = """
            Authorization: Bearer live-secret
            token=token-secret&mode=fast
            url=https://user:password@example.com/path
            account=user@example.com
            jwt=abcdefghijklmnop.qrstuvwxyzABCDEF.ghijklmnopQRSTUV
            FirebaseAuth: Notifying id token listeners about user ( account-internal-id ).
        """.trimIndent()

        val result = input.redactedAndBounded(16 * 1024)

        assertFalse(result.contains("live-secret"))
        assertFalse(result.contains("token-secret"))
        assertFalse(result.contains("user:password"))
        assertFalse(result.contains("user@example.com"))
        assertFalse(result.contains("abcdefghijklmnop.qrstuvwxyzABCDEF.ghijklmnopQRSTUV"))
        assertFalse(result.contains("account-internal-id"))
        assertTrue(result.contains("<redacted>"))
        assertTrue(result.contains("<redacted-email>"))
        assertTrue(result.contains("<redacted-jwt>"))
        assertTrue(result.contains("<redacted-account>"))
    }

    /** 验证超长日志只保留更有诊断价值的最新尾部。 */
    @Test
    fun keepsRecentTailWhenTextExceedsLimit() {
        val result = ("old".repeat(1000) + "RECENT-END").redactedAndBounded(64)

        assertTrue(result.startsWith("[truncated to recent data]"))
        assertTrue(result.endsWith("RECENT-END"))
    }
}
