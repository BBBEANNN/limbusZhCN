package com.lody.virtual.helper.compat;

import java.util.Locale;

/**
 * 为 Limbus 登录流程提供不依赖 Android 组件实例的精确兼容策略。
 *
 * <p>该类型只判断已确认的游戏、microG 包与 Firebase Auth 回调地址，便于 Java 层、
 * Activity 跳板和本地单元测试共享同一套边界，避免把登录特例扩展到未知应用或 URI。</p>
 */
public final class LimbusAuthenticationCompat {
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String FIREBASE_AUTH_HOST = "firebase.auth";
    private static final String FIREBASE_AUTH_PATH = "/";
    private static final String GENERIC_IDP_SCHEME = "genericidp";
    private static final String RECAPTCHA_SCHEME = "recaptcha";
    private static final String GENERIC_IDP_ACTIVITY =
            "com.google.firebase.auth.internal.GenericIdpActivity";
    private static final String RECAPTCHA_ACTIVITY =
            "com.google.firebase.auth.internal.RecaptchaActivity";

    private LimbusAuthenticationCompat() {
    }

    /**
     * 判断当前虚拟应用是否必须跳过旧式 ART 方法内存 Hook。
     *
     * <p>Limbus 与内置 microG/FakeStore 的 IO 重定向和 Binder 身份兼容均在独立层完成。
     * 继续扫描并改写现代 ART 的 {@code jmethodID} 会在 Android 13 的辅助进程中崩溃，
     * 因此只对这三个已确认包以及本来就不支持该 Hook 的 native bridge 运行时跳过。</p>
     *
     * @param packageName 当前绑定的虚拟应用包名，可为空
     * @param nativeBridge 系统声明的 native bridge 名称，可为空
     * @return 需要跳过旧式 VM Hook 时返回 {@code true}
     */
    public static boolean shouldSkipLegacyVmHook(String packageName, String nativeBridge) {
        if (LIMBUS_PACKAGE.equals(packageName)
                || GMS_PACKAGE.equals(packageName)
                || VENDING_PACKAGE.equals(packageName)) {
            return true;
        }
        String normalizedBridge = nativeBridge == null
                ? ""
                : nativeBridge.toLowerCase(Locale.ENGLISH);
        return normalizedBridge.contains("houdini") || normalizedBridge.contains("nb");
    }

    /**
     * 把受信任的 Firebase Auth 浏览器回调映射到游戏内目标 Activity。
     *
     * @param scheme 回调 URI 的 scheme
     * @param host 回调 URI 的 host
     * @param path 回调 URI 的 path
     * @return 精确匹配时返回目标 Activity 类名，否则返回 {@code null}
     */
    public static String resolveFirebaseAuthRedirectActivity(
            String scheme,
            String host,
            String path) {
        if (!FIREBASE_AUTH_HOST.equals(host) || !FIREBASE_AUTH_PATH.equals(path)) {
            return null;
        }
        if (GENERIC_IDP_SCHEME.equals(scheme)) {
            return GENERIC_IDP_ACTIVITY;
        }
        if (RECAPTCHA_SCHEME.equals(scheme)) {
            return RECAPTCHA_ACTIVITY;
        }
        return null;
    }
}
