package com.example.limbuszhcn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    /** 验证二进制脱敏保持 protobuf 结构字节、总长度和非敏感文本不变。 */
    @Test
    fun redactsBinaryCredentialsWithoutChangingFieldBoundaries() {
        val prefix = byteArrayOf(0x82.toByte(), 0x01, 0x12, 0x20)
        val secret = "account=user@example.com token=live-secret safe=kept"
            .toByteArray(Charsets.US_ASCII)
        val suffix = byteArrayOf(0x80.toByte(), 0x01, 0x7a)
        val input = prefix + secret + suffix

        val result = input.redactedBinaryCopy()

        assertEquals(input.size, result.size)
        assertArrayEquals(prefix, result.copyOfRange(0, prefix.size))
        assertArrayEquals(suffix, result.copyOfRange(result.size - suffix.size, result.size))
        val printable = String(result, Charsets.ISO_8859_1)
        assertFalse(printable.contains("user@example.com"))
        assertFalse(printable.contains("live-secret"))
        assertTrue(printable.contains("safe=kept"))
    }
}
