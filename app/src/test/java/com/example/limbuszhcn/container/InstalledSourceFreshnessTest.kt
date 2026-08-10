package com.example.limbuszhcn.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证系统安装源与 VirtualApp APK 来源的新鲜度判断。
 */
class InstalledSourceFreshnessTest {
    /**
     * 相同的 Google Play base APK 路径不应触发重新同步。
     */
    @Test
    fun `matching source path stays fresh`() {
        val source = "/data/app/example/base.apk"

        assertFalse(installedSourceNeedsResync(source, source))
    }

    /**
     * VirtualApp 仍引用私有 base-1 APK 时必须触发重新同步。
     */
    @Test
    fun `private virtual copy requires resync`() {
        assertTrue(
            installedSourceNeedsResync(
                "/data/app/example/base.apk",
                "/data/user/0/com.example.limbuszhcn/virtual/data/app/com.ProjectMoon.LimbusCompany/base-1.apk"
            )
        )
    }

    /**
     * 缺少任一来源信息时不应因诊断失败而循环同步大型 APK。
     */
    @Test
    fun `missing source cannot prove mismatch`() {
        assertFalse(installedSourceNeedsResync(null, "/data/user/0/example/base-1.apk"))
        assertFalse(installedSourceNeedsResync("/data/app/example/base.apk", null))
    }
}
