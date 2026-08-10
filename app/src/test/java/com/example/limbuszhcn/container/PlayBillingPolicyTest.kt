package com.example.limbuszhcn.container

import com.lody.virtual.client.hook.secondary.ServiceConnectionDelegate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证边狱公司 Play Billing Binder 包装策略只命中精确的计费服务连接。
 */
class PlayBillingPolicyTest {
    /**
     * 验证边狱公司连接真实 Play Store 计费 Binder 时启用包装。
     */
    @Test
    fun wrapsLimbusPlayBillingBinder() {
        assertTrue(
            ServiceConnectionDelegate.shouldWrapLimbusPlayBillingService(
                "com.android.vending",
                "com.ProjectMoon.LimbusCompany",
                "com.android.vending.billing.IInAppBillingService",
            ),
        )
    }

    /**
     * 验证其他虚拟应用、其他 Play Store 服务或错误描述符不会被计费包装器影响。
     */
    @Test
    fun ignoresNonBillingConnections() {
        assertFalse(
            ServiceConnectionDelegate.shouldWrapLimbusPlayBillingService(
                "com.android.vending",
                "com.example.other",
                "com.android.vending.billing.IInAppBillingService",
            ),
        )
        assertFalse(
            ServiceConnectionDelegate.shouldWrapLimbusPlayBillingService(
                "com.android.vending",
                "com.ProjectMoon.LimbusCompany",
                "com.google.android.play.core.assetpacks.protocol.IAssetModuleService",
            ),
        )
        assertFalse(
            ServiceConnectionDelegate.shouldWrapLimbusPlayBillingService(
                "com.example.store",
                "com.ProjectMoon.LimbusCompany",
                "com.android.vending.billing.IInAppBillingService",
            ),
        )
    }
}
