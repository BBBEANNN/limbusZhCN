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
    private static final String FIREBASE_SESSION_LIFECYCLE_SERVICE =
            "com.google.firebase.sessions.SessionLifecycleService";

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
     * 判断是否应跳过 Limbus 内非关键的 Firebase 会话统计服务绑定。
     *
     * <p>Limbus v461 首次装载受 AppSealing 保护的 Unity native 库时，主线程可能持续执行
     * XZ 解压超过系统的 Service 执行时限。Firebase Sessions 恰在这段窗口绑定
     * {@code SessionLifecycleService}，VirtualApp 会为它启动宿主 {@code ShadowService}；
     * 该启动消息无法越过正在解压的主线程，最终让系统按“executing service”判定 ANR。
     * Sessions 只负责 Crashlytics / Performance 的会话关联，不参与 Firebase Auth，
     * 因此这里只拒绝游戏包内这一项精确组件，避免影响其他 Firebase 服务。</p>
     *
     * @param currentPackage 当前虚拟客户端绑定的包名
     * @param componentPackage 显式 Service 组件的包名
     * @param className 显式 Service 组件的完整类名
     * @return 仅命中 Limbus 自身的 Firebase Sessions 服务时返回 {@code true}
     */
    public static boolean shouldSkipNonCriticalFirebaseSessionService(
            String currentPackage,
            String componentPackage,
            String className) {
        return LIMBUS_PACKAGE.equals(currentPackage)
                && LIMBUS_PACKAGE.equals(componentPackage)
                && FIREBASE_SESSION_LIFECYCLE_SERVICE.equals(className);
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
