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

    /** 验证只跳过会在 Unity 长耗时装载窗口触发 ShadowService ANR 的 Firebase Sessions 服务。 */
    @Test
    fun skipsOnlyLimbusFirebaseSessionLifecycleService() {
        assertTrue(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            "com.ProjectMoon.LimbusCompany",
            "com.ProjectMoon.LimbusCompany",
            "com.google.firebase.sessions.SessionLifecycleService"
        ))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            "com.ProjectMoon.LimbusCompany",
            "com.ProjectMoon.LimbusCompany",
            "com.google.firebase.auth.api.fallback.service.FirebaseAuthFallbackService"
        ))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            "com.google.android.gms",
            "com.ProjectMoon.LimbusCompany",
            "com.google.firebase.sessions.SessionLifecycleService"
        ))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            "com.ProjectMoon.LimbusCompany",
            "example.other",
            "com.google.firebase.sessions.SessionLifecycleService"
        ))
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
