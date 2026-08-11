package com.example.limbuszhcn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证设备兼容报告不会把不满足系统版本或 ABI 的机型误报为可用。
 */
class DeviceCompatibilityReportTest {
    /** 验证 Android 8 arm64 设备满足游戏与容器的基础条件。 */
    @Test
    fun acceptsAndroidEightArm64Device() {
        val report = report(sdkInt = 26, hasRequiredAbi = true)

        assertTrue(report.isSupported)
        assertEquals("基础兼容", report.statusLabel)
    }

    /** 验证只有 32 位系统环境的设备会明确显示不兼容。 */
    @Test
    fun rejectsDeviceWithoutArm64Runtime() {
        val report = report(sdkInt = 35, hasRequiredAbi = false)

        assertFalse(report.isSupported)
        assertEquals("不兼容", report.statusLabel)
    }

    /** 验证基础条件通过但需要后台授权时显示“需设置”。 */
    @Test
    fun marksSupportedDeviceThatNeedsOemSettings() {
        val report = report(
            sdkInt = 35,
            hasRequiredAbi = true,
            warnings = listOf("需要允许后台运行")
        )

        assertTrue(report.isSupported)
        assertEquals("需设置", report.statusLabel)
    }

    /** 构造不依赖 Android 运行时的测试报告。 */
    private fun report(
        sdkInt: Int,
        hasRequiredAbi: Boolean,
        warnings: List<String> = emptyList()
    ): DeviceCompatibilityReport = DeviceCompatibilityReport(
        deviceLabel = "测试设备",
        androidLabel = "Android Test（API $sdkInt）",
        sdkInt = sdkInt,
        abiLabel = if (hasRequiredAbi) "arm64-v8a" else "armeabi-v7a",
        hasRequiredAbi = hasRequiredAbi,
        pageSizeBytes = 4096,
        is64BitProcess = hasRequiredAbi,
        isLowRamDevice = false,
        isIgnoringBatteryOptimizations = true,
        warnings = warnings
    )
}
