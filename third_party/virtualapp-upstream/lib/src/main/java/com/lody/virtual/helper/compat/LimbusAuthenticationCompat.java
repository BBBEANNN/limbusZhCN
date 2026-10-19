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
    private static final String MICROG_ASSISTED_SIGN_IN_ACTIVITY =
            "org.microg.gms.auth.signin.AssistedSignInActivity";
    private static final String MICROG_AUTH_SIGN_IN_ACTIVITY =
            "org.microg.gms.auth.signin.AuthSignInActivity";

    private LimbusAuthenticationCompat() {
    }

    /**
     * 判断 Provider 的系统边界是否必须采用 Binder 可验证的宿主物理身份。
     *
     * @param apiLevel 当前 Android API 级别
     * @return Android 12（API 31）及以上返回 {@code true}，旧系统保留虚拟身份
     */
    public static boolean shouldUsePhysicalProviderIdentity(int apiLevel) {
        return apiLevel >= 31;
    }

    /**
     * 为两层已确认的 microG 登录页面选择目标主题，避免继承宿主 Stub 的主题缓存。
     *
     * <p>Activity 没有单独声明主题时应继承应用主题。其它包或页面返回零，由原有
     * Activity 处理路径负责，避免把登录兼容扩大到未知组件。</p>
     *
     * @param currentPackage 当前虚拟进程绑定的包名
     * @param activityPackage 目标 Activity 所属包名
     * @param activityClass 目标 Activity 完整类名
     * @param activityTheme Activity 自身声明的主题资源 ID，零表示未声明
     * @param applicationTheme 目标应用声明的主题资源 ID，零表示未声明
     * @return 应重建的目标主题 ID；不属于精确登录页面或无可用主题时返回零
     */
    public static int resolveMicrogSignInTheme(
            String currentPackage,
            String activityPackage,
            String activityClass,
            int activityTheme,
            int applicationTheme) {
        if (!GMS_PACKAGE.equals(currentPackage)
                || !GMS_PACKAGE.equals(activityPackage)
                || !(MICROG_ASSISTED_SIGN_IN_ACTIVITY.equals(activityClass)
                || MICROG_AUTH_SIGN_IN_ACTIVITY.equals(activityClass))) {
            return 0;
        }
        return activityTheme != 0 ? activityTheme : applicationTheme;
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
     * 判断是否应拒绝 Limbus 自身非关键的 Firebase 会话统计服务绑定。
     *
     * <p>该统计服务经容器启动宿主 ShadowService，会增加 Unity 原生库同步装载期间的
     * 主线程服务执行消息。本策略沿用 Issue #2 的定向测试方案，只拒绝这一项统计组件，
     * 避免把其服务超时风险带入游戏启动；Issue #2 的最终根因仍需原设备回归确认。
     * Firebase Auth 和其他业务服务不会命中此条件。</p>
     *
     * @param currentPackage 当前虚拟客户端绑定的包名，可为空
     * @param componentPackage 显式 Service 组件所属包名，可为空
     * @param className 显式 Service 组件的完整类名，可为空
     * @return 三项均精确匹配 Limbus 自身会话统计组件时返回 {@code true}
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
