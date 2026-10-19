package com.example.limbuszhcn

import android.app.ApplicationExitInfo
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
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 64)
    }

    /** 验证零预算与提示附近的小预算仍遵守总上限，空间紧张时优先保留最新文本。 */
    @Test
    fun countsTruncationNoticeInsideEveryTextBudget() {
        val source = "old-data".repeat(100) + "RECENT-END"
        val notice = "[truncated to recent data]\n"
        assertEquals("", source.redactedAndBounded(0))
        assertEquals("", source.redactedAndBounded(-1))
        for (limit in 1..notice.length + 5) {
            val result = source.redactedAndBounded(limit)
            assertTrue("limit=$limit", result.toByteArray(Charsets.UTF_8).size <= limit)
            if (limit < notice.length + 4) {
                assertEquals(source.takeLast(limit), result)
            } else {
                assertTrue(result.startsWith(notice))
                assertTrue(source.endsWith(result.removePrefix(notice)))
            }
        }
    }

    /** 验证中文和四字节 emoji 的半字符不会转成 U+FFFD，尾部字符顺序也保持不变。 */
    @Test
    fun truncatesOnlyAtCompleteUtf8CharacterBoundaries() {
        val source = "历史记录".repeat(100) + "最新设置🙂"
        val notice = "[truncated to recent data]\n"
        for (limit in 0..80) {
            val result = source.redactedAndBounded(limit)
            assertTrue("limit=$limit", result.toByteArray(Charsets.UTF_8).size <= limit)
            assertFalse("limit=$limit", result.contains('\uFFFD'))
            assertTrue("limit=$limit", source.endsWith(result.removePrefix(notice)))
        }
        assertEquals("", source.redactedAndBounded(3))
        assertEquals("🙂", source.redactedAndBounded(4))
        assertEquals("置🙂", source.redactedAndBounded(7))
    }

    /** 验证 ANR 最终写入的字节含提示仍不超限，中文截断不会引入替换字符。 */
    @Test
    fun boundsEncodedAnrTraceIncludingNoticeAndMultibyteTail() {
        val source = ("UnityMain 历史等待锁\n".repeat(100) + "最新设置🙂").toByteArray(Charsets.UTF_8)
        for (limit in listOf(0, 1, 2, 3, 4, 7, 25, 26, 27, 29, 30, 64)) {
            val trace = prepareDiagnosticExitTrace(ApplicationExitInfo.REASON_ANR, source, limit)
            assertEquals("txt", trace.extension)
            assertTrue("limit=$limit", trace.bytes.size <= limit)
            assertFalse("limit=$limit", String(trace.bytes, Charsets.UTF_8).contains('\uFFFD'))
        }
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

    /** 验证 ANR 使用可直接阅读的脱敏文本，保留 Unicode 线程栈而不伪装为 protobuf。 */
    @Test
    fun exportsAnrTraceAsRedactedUtf8Text() {
        val source = "UnityMain 等待锁\naccount=user@example.com token=live-secret\n at 游戏设置.apply()"
        val trace = prepareDiagnosticExitTrace(
            ApplicationExitInfo.REASON_ANR, source.toByteArray(Charsets.UTF_8), 16 * 1024
        )
        val text = String(trace.bytes, Charsets.UTF_8)

        assertEquals("txt", trace.extension)
        assertTrue(text.contains("UnityMain 等待锁"))
        assertTrue(text.contains("游戏设置.apply()"))
        assertFalse(text.contains("user@example.com"))
        assertFalse(text.contains("live-secret"))
    }

    /** 验证已恢复 ANR 的文本即使随其他退出原因保留，也仍使用可读文本格式。 */
    @Test
    fun preservesRecoveredAnrTextForLaterSignalExit() {
        val trace = prepareDiagnosticExitTrace(
            ApplicationExitInfo.REASON_SIGNALED,
            "old-data".repeat(50).plus("\nUnityMain RECENT-END").toByteArray(), 64
        )

        assertEquals("txt", trace.extension)
        assertTrue(String(trace.bytes).endsWith("UnityMain RECENT-END"))
    }

    /** 验证 native tombstone 不会被 UTF-8 解码或截断破坏，凭据仍必须等长覆盖。 */
    @Test
    fun exportsNativeTraceAsLengthPreservingProtobuf() {
        val prefix = byteArrayOf(0x82.toByte(), 0x01, 0x80.toByte(), 0xff.toByte())
        val input = prefix + "token=live-secret safe=kept".toByteArray(Charsets.US_ASCII)
        val trace = prepareDiagnosticExitTrace(ApplicationExitInfo.REASON_CRASH_NATIVE, input, 8)

        assertEquals("pb", trace.extension)
        assertEquals(input.size, trace.bytes.size)
        assertArrayEquals(prefix, trace.bytes.copyOfRange(0, prefix.size))
        assertFalse(String(trace.bytes, Charsets.ISO_8859_1).contains("live-secret"))
        assertTrue(String(trace.bytes, Charsets.ISO_8859_1).contains("safe=kept"))
        assertArrayEquals(prefix, input.copyOfRange(0, prefix.size))
    }
}
