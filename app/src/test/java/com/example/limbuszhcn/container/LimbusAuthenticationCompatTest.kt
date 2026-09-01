package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusAuthenticationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证登录辅助进程和 Firebase Auth 浏览器回调只命中已确认的精确兼容边界。
 */
class LimbusAuthenticationCompatTest {
    /** 验证游戏、microG 与 FakeStore 均不会进入不兼容的旧式 ART VM Hook。 */
    @Test
    fun skipsLegacyVmHookOnlyForConfirmedAuthenticationPackages() {
        assertTrue(LimbusAuthenticationCompat.shouldSkipLegacyVmHook(
            "com.ProjectMoon.LimbusCompany", ""))
        assertTrue(LimbusAuthenticationCompat.shouldSkipLegacyVmHook(
            "com.google.android.gms", ""))
        assertTrue(LimbusAuthenticationCompat.shouldSkipLegacyVmHook(
            "com.android.vending", ""))
        assertTrue(LimbusAuthenticationCompat.shouldSkipLegacyVmHook(
            "example.other", "libhoudini.so"))
        assertFalse(LimbusAuthenticationCompat.shouldSkipLegacyVmHook(
            "example.other", ""))
    }

    /** 验证 Android 12 起 Provider 必须使用 Binder 可验证的宿主物理身份。 */
    @Test
    fun usesPhysicalProviderIdentityFromAndroid12() {
        assertFalse(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(30))
        assertTrue(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(31))
        assertTrue(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(33))
        assertTrue(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(36))
    }

    /** 验证只接受游戏 manifest 声明的两个 Firebase Auth 回调 URI。 */
    @Test
    fun resolvesOnlyExactFirebaseAuthRedirects() {
        assertEquals(
            "com.google.firebase.auth.internal.GenericIdpActivity",
            LimbusAuthenticationCompat.resolveFirebaseAuthRedirectActivity(
                "genericidp", "firebase.auth", "/")
        )
        assertEquals(
            "com.google.firebase.auth.internal.RecaptchaActivity",
            LimbusAuthenticationCompat.resolveFirebaseAuthRedirectActivity(
                "recaptcha", "firebase.auth", "/")
        )
        assertNull(LimbusAuthenticationCompat.resolveFirebaseAuthRedirectActivity(
            "https", "firebase.auth", "/"))
        assertNull(LimbusAuthenticationCompat.resolveFirebaseAuthRedirectActivity(
            "genericidp", "attacker.example", "/"))
        assertNull(LimbusAuthenticationCompat.resolveFirebaseAuthRedirectActivity(
            "genericidp", "firebase.auth", "/unexpected"))
    }
}
