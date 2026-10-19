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
    /** 验证新系统的 Provider 系统边界使用物理身份，旧系统保留虚拟身份。 */
    @Test
    fun usesPhysicalProviderIdentityFromAndroid12() {
        assertFalse(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(30))
        assertTrue(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(31))
        assertTrue(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(33))
        assertTrue(LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(36))
    }

    /** 验证 Google 登录父子页面均继承 microG 应用主题，并保留 Activity 显式主题的优先级。 */
    @Test
    fun resolvesBothMicrogSignInThemesWithApplicationFallback() {
        for (activityClass in listOf(
            "org.microg.gms.auth.signin.AssistedSignInActivity",
            "org.microg.gms.auth.signin.AuthSignInActivity"
        )) {
            assertEquals(100, LimbusAuthenticationCompat.resolveMicrogSignInTheme(
                "com.google.android.gms", "com.google.android.gms", activityClass, 0, 100))
            assertEquals(200, LimbusAuthenticationCompat.resolveMicrogSignInTheme(
                "com.google.android.gms", "com.google.android.gms", activityClass, 200, 100))
            assertEquals(0, LimbusAuthenticationCompat.resolveMicrogSignInTheme(
                "com.google.android.gms", "com.google.android.gms", activityClass, 0, 0))
        }
    }

    /** 验证主题重建不会命中其它进程、目标包、同名前缀页面或一般 microG 页面。 */
    @Test
    fun keepsThemeRepairScopedToExactMicrogSignInActivities() {
        val authActivity = "org.microg.gms.auth.signin.AuthSignInActivity"
        assertEquals(0, LimbusAuthenticationCompat.resolveMicrogSignInTheme(
            "com.ProjectMoon.LimbusCompany", "com.google.android.gms", authActivity, 200, 100))
        assertEquals(0, LimbusAuthenticationCompat.resolveMicrogSignInTheme(
            "com.google.android.gms", "example.other", authActivity, 200, 100))
        assertEquals(0, LimbusAuthenticationCompat.resolveMicrogSignInTheme(
            "com.google.android.gms", "com.google.android.gms", "$authActivity.Unexpected", 200, 100))
        assertEquals(0, LimbusAuthenticationCompat.resolveMicrogSignInTheme(
            "com.google.android.gms", "com.google.android.gms",
            "org.microg.gms.auth.login.LoginActivity", 200, 100))
    }

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

    /** 验证统计服务策略精确限定调用包、组件包和完整类名，不能影响认证和未知服务。 */
    @Test
    fun skipsOnlyLimbusFirebaseSessionLifecycleService() {
        val game = "com.ProjectMoon.LimbusCompany"
        val sessionService = "com.google.firebase.sessions.SessionLifecycleService"
        assertTrue(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            game, game, sessionService))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            game, game, "com.google.firebase.auth.api.fallback.service.FirebaseAuthFallbackService"))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            "com.google.android.gms", game, sessionService))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            game, "example.other", sessionService))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            game, game, "$sessionService.Other"))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            game, game, ".SessionLifecycleService"))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            null, game, sessionService))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            game, null, sessionService))
        assertFalse(LimbusAuthenticationCompat.shouldSkipNonCriticalFirebaseSessionService(
            game, game, null))
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
