package com.lody.virtual.client.hook.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebSettings;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.fixer.ActivityFixer;
import com.lody.virtual.client.fixer.ContextFixer;
import com.lody.virtual.client.hook.proxies.am.HCallbackStub;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.client.interfaces.IInjector;
import com.lody.virtual.helper.compat.ActivityManagerCompat;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.remote.StubActivityRecord;
import com.xdja.zs.IUiCallback;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

import mirror.android.app.ActivityThread;
import mirror.android.app.LoadedApk;

/**
 * <p>负责在虚拟进程中接管 Android Instrumentation，并在目标 Activity 创建前恢复其包、
 * 主题、调用身份和厂商兼容环境。</p>
 *
 * @author Lody
 */
public final class AppInstrumentation extends InstrumentationDelegate implements IInjector {

    private static final String TAG = AppInstrumentation.class.getSimpleName();
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";
    // compileSdk 35 尚未提供 Android 16 的常量，因此在这里明确保留 API 级别。
    private static final int ANDROID_16_API_LEVEL = 36;
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String MICROG_ASSISTED_SIGN_IN_ACTIVITY =
            "org.microg.gms.auth.signin.AssistedSignInActivity";
    private static final String MICROG_AUTH_SIGN_IN_ACTIVITY =
            "org.microg.gms.auth.signin.AuthSignInActivity";
    private static final String MICROG_LOGIN_ACTIVITY =
            "org.microg.gms.auth.login.LoginActivity";
    private static Object sActivityClientControllerBase;
    private static Object sActivityClientControllerProxy;
    private static boolean sOplusActivityManagerFallbackAttempted;

    private static AppInstrumentation gDefault;
    private final WeakHashMap<Activity, StubActivityRecord> pendingStubRecords = new WeakHashMap<>();

    private AppInstrumentation(Instrumentation base) {
        super(base);
    }

    public static AppInstrumentation getDefault() {
        if (gDefault == null) {
            synchronized (AppInstrumentation.class) {
                if (gDefault == null) {
                    gDefault = create();
                }
            }
        }
        return gDefault;
    }

    private static AppInstrumentation create() {
        Instrumentation instrumentation = ActivityThread.mInstrumentation.get(VirtualCore.mainThread());
        if (instrumentation instanceof AppInstrumentation) {
            return (AppInstrumentation) instrumentation;
        }
        return new AppInstrumentation(instrumentation);
    }


    @Override
    public void inject() {
        base = ActivityThread.mInstrumentation.get(VirtualCore.mainThread());
        Log.i("LimbusVA", "AppInstrumentation inject base=" + base);
        ActivityThread.mInstrumentation.set(VirtualCore.mainThread(), this);
        Log.i("LimbusVA", "AppInstrumentation inject done current="
                + ActivityThread.mInstrumentation.get(VirtualCore.mainThread()));
    }

    @Override
    public boolean isEnvBad() {
        return !checkInstrumentation(ActivityThread.mInstrumentation.get(VirtualCore.mainThread()));
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            Field[] fields = clazz.getDeclaredFields();
            if (fields != null) {
                for (Field field : fields) {
                    if (Instrumentation.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object obj;
                        try {
                            obj = field.get(instrumentation);
                        } catch (IllegalAccessException e) {
                            return false;
                        }
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkActivityCallback() {
        InvocationStubManager.getInstance().checkEnv(HCallbackStub.class);
        InvocationStubManager.getInstance().checkEnv(AppInstrumentation.class);
    }

    /**
     * 在目标 Activity 执行 {@code onCreate()} 前恢复虚拟应用上下文，并应用必要的系统兼容修复。
     *
     * @param activity 即将创建的目标 Activity
     * @param icicle Android 传入的已保存实例状态，可为空
     */
    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivityCallback();
        ActivityInfo targetInfo = applyStubActivityRecord(activity);
        if (targetInfo == null) {
            targetInfo = getVirtualTargetActivityInfo(activity);
        }
        // Android 9+ HCallback may unwrap the host stub before Instrumentation.newActivity(), so
        // pendingStubRecords is empty even though this is still a client-local virtual Activity.
        restoreVirtualCallingIdentity(activity, targetInfo);
        ContextFixer.fixContext(activity);
        restoreTargetActivityContext(activity, targetInfo);
        applyLimbusThreadContextClassLoader(targetInfo, "callActivityOnCreate");
        ActivityFixer.fixActivity(activity);
        // Unity 会在 Activity.onCreate() 内创建 PhoneWindow。必须在进入目标 Activity 之前完成
        // ColorOS 服务兜底，否则厂商导航栏策略会直接解引用尚未创建的服务并终止主线程。
        installOplusActivityManagerFallbackIfNeeded(activity);
        ActivityInfo info = mirror.android.app.Activity.mActivityInfo.get(activity);
        if (info != null) {
            if (info.theme != 0) {
                activity.setTheme(info.theme);
            }
            if (activity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    && info.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                if (activity.getRequestedOrientation() != info.screenOrientation) {
                    ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
                    boolean needWait;
                    //set orientation
                    Configuration configuration = activity.getResources().getConfiguration();
                    if (isOrientationLandscape(info.screenOrientation)) {
                        needWait = configuration.orientation != Configuration.ORIENTATION_LANDSCAPE;
                        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
                    } else {
                        needWait = configuration.orientation != Configuration.ORIENTATION_PORTRAIT;
                        configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
                    }
                    if (needWait) {
                        try {
                            Thread.sleep(800);
                        } catch (Exception e) {
                            //ignore
                        }
                    }
                }
            }
        }
        super.callActivityOnCreate(activity, icicle);
        exposeMicrogLoginWebViewIfBridgeStalls(activity);
    }

    /**
     * 为容器内 microG 登录页安装 WebView 可见性兜底。
     *
     * <p>microG 默认先隐藏登录 WebView，等待 Google 页面通过 {@code mm.showView()} 显示内容。
     * 某些国产 WebView 在 VirtualApp 进程中会完成主文档加载，却不执行该桥接回调，界面因而永久停留在
     * “正在建立连接”。这里等待五秒；只有 WebView 仍不可见时才显示它，不干扰正常的登录流程。
     * 同时仅记录页面路径、加载状态和桥接对象是否存在，不采集账号、Cookie 或页面正文。</p>
     *
     * @param activity 已完成 {@code onCreate()} 的目标 Activity
     */
    private void exposeMicrogLoginWebViewIfBridgeStalls(Activity activity) {
        if (activity == null
                || !GMS_PACKAGE.equals(VClient.get().getCurrentPackage())
                || !MICROG_LOGIN_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }

        View content = activity.findViewById(android.R.id.content);
        WebView loginWebView = findDescendantWebView(content);
        if (loginWebView == null) {
            Log.w("LimbusVA", "Unable to find microG login WebView");
            return;
        }

        WebSettings settings = loginWebView.getSettings();
        int originalCacheMode = settings.getCacheMode();
        boolean originallyBlockingNetwork = settings.getBlockNetworkLoads();
        // 部分国产 WebView 会把虚拟进程误判为离线页面并退化为仅缓存模式，首次请求因此直接报 ERR_CACHE_MISS。
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        try {
            settings.setBlockNetworkLoads(false);
        } catch (SecurityException error) {
            // 宿主正常声明了 INTERNET 权限；若厂商 WebView 仍拒绝解除限制，保留日志用于后续诊断。
            Log.w("LimbusVA", "Unable to enable network loads for microG login WebView", error);
        }
        Log.i("LimbusVA", "Prepared microG login WebView network settings oldCacheMode="
                + originalCacheMode + " oldBlockNetworkLoads=" + originallyBlockingNetwork
                + " newCacheMode=" + settings.getCacheMode()
                + " newBlockNetworkLoads=" + settings.getBlockNetworkLoads());

        // 调试开关只作用于宿主的 debug 构建，便于在仍失败时读取 WebView 网络错误。
        WebView.setWebContentsDebuggingEnabled(true);
        monitorMicrogLoginNavigation(loginWebView, settings, 180);
    }

    /**
     * 等待用户确认登录并由 microG 真正发起 Google 页面导航。
     *
     * <p>确认页创建 WebView 时 URL 仍为空，WebView 也应保持隐藏。如果此时提前显示透明 WebView，
     * 它会覆盖底部“登录”按钮并吞掉触摸。这里最多轮询三分钟；只有检测到 Google 登录 URL 后，
     * 才等待页面正常桥接并执行错误恢复。</p>
     *
     * @param loginWebView microG 登录 WebView
     * @param settings 登录 WebView 的设置对象
     * @param remainingChecks 剩余的一秒轮询次数
     */
    private void monitorMicrogLoginNavigation(
            WebView loginWebView,
            WebSettings settings,
            int remainingChecks) {
        loginWebView.postDelayed(() -> {
            if (!loginWebView.isAttachedToWindow() || remainingChecks <= 0) {
                return;
            }
            String currentUrl = loginWebView.getUrl();
            if (currentUrl == null
                    || !currentUrl.startsWith("https://accounts.google.com/")) {
                monitorMicrogLoginNavigation(loginWebView, settings, remainingChecks - 1);
                return;
            }

            Log.i("LimbusVA", "Observed microG Google login navigation; scheduling bridge check");
            loginWebView.postDelayed(
                    () -> recoverMicrogLoginWebViewIfNeeded(loginWebView, settings),
                    5000L);
        }, 1000L);
    }

    /**
     * 在 Google 页面开始导航后检查桥接状态，并恢复被错误设置为仅缓存的 WebView。
     *
     * @param loginWebView 已开始加载 Google URL 的 WebView
     * @param settings 登录 WebView 的设置对象
     */
    private void recoverMicrogLoginWebViewIfNeeded(WebView loginWebView, WebSettings settings) {
        if (!loginWebView.isAttachedToWindow()) {
            return;
        }
        boolean bridgeStalled = loginWebView.getVisibility() != View.VISIBLE;
        if (bridgeStalled) {
            Log.w("LimbusVA", "microG login bridge did not reveal WebView; applying visibility fallback");
            loginWebView.setVisibility(View.VISIBLE);
        }
        int delayedCacheMode = settings.getCacheMode();
        boolean delayedBlockNetworkLoads = settings.getBlockNetworkLoads();
        // 首次设置后厂商 WebView 仍可能在导航阶段恢复仅缓存标志；此处改用显式不读缓存模式。
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        try {
            settings.setBlockNetworkLoads(false);
        } catch (SecurityException error) {
            Log.w("LimbusVA", "Unable to restore delayed microG WebView network loads", error);
        }
        Log.i("LimbusVA", "Rechecked microG login WebView network settings delayedCacheMode="
                + delayedCacheMode + " delayedBlockNetworkLoads=" + delayedBlockNetworkLoads
                + " forcedCacheMode=" + settings.getCacheMode()
                + " forcedBlockNetworkLoads=" + settings.getBlockNetworkLoads());

        String currentUrl = loginWebView.getUrl();
        if (bridgeStalled && currentUrl != null
                && currentUrl.startsWith("https://accounts.google.com/")) {
            // ERR_CACHE_MISS 发生时原始导航从未进入网络栈，重新 loadUrl 才能应用新的加载策略。
            Log.i("LimbusVA", "Retrying microG login URL with cache bypass");
            loginWebView.loadUrl(currentUrl);
        }
        loginWebView.evaluateJavascript(
                "(function(){return JSON.stringify({readyState:document.readyState,"
                        + "path:location.pathname,hash:location.hash,"
                        + "hasBridge:typeof window.mm!=='undefined',"
                        + "bodyChildren:document.body?document.body.childElementCount:-1});})()",
                value -> Log.i("LimbusVA", "microG login WebView state=" + value));
    }

    /**
     * 从 Activity 内容树中递归查找 microG 创建的登录 WebView。
     *
     * @param view 当前检查的视图节点
     * @return 找到的首个 WebView；视图树中不存在时返回 {@code null}
     */
    private WebView findDescendantWebView(View view) {
        if (view instanceof WebView) {
            return (WebView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            WebView childWebView = findDescendantWebView(group.getChildAt(index));
            if (childWebView != null) {
                return childWebView;
            }
        }
        return null;
    }

    /**
     * 为 Android 16 ColorOS 上无法从虚拟进程创建的 Oplus ActivityManager 服务安装无操作实现。
     *
     * <p>ColorOS 的 {@code isAppInDarkModeDataEntity()} 没有检查服务是否为空。VirtualApp 的
     * ActivityManager 代理不实现厂商扩展事务，因此其私有 Singleton 会返回空值。这里复用厂商
     * AIDL 随框架提供的 Default 实现，使只读的窗口外观查询返回安全默认值。除 Limbus
     * 目标进程外，Google 登录的 PendingIntent 桥接 Activity 运行在容器引擎进程，后者没有
     * 当前虚拟包身份；因此也必须按 Activity 类型识别该桥接场景，否则创建登录窗口时仍会
     * 在 {@code PhoneWindow.installDecor()} 中崩溃。桥接页随后启动的虚拟 GMS 身份选择页
     * 位于另一个槽位进程，也没有可用的厂商扩展服务，因此仅对两个明确的 Google 登录
     * Activity 应用相同兜底。</p>
     *
     * @param activity 即将进入 {@code onCreate()} 的 Activity，用于识别容器登录桥接页面
     */
    public static synchronized void installOplusActivityManagerFallbackIfNeeded(Activity activity) {
        String currentPackage = VClient.get().getCurrentPackage();
        String activityClassName = activity == null ? null : activity.getClass().getName();
        boolean isLimbusProcess = LIMBUS_PACKAGE.equals(currentPackage);
        boolean isPendingIntentBridge = activity != null
                && "com.lody.virtual.client.stub.ShadowPendingActivity"
                .equals(activityClassName);
        boolean isGoogleIdentityActivity = GMS_PACKAGE.equals(currentPackage)
                && (MICROG_ASSISTED_SIGN_IN_ACTIVITY.equals(activityClassName)
                || MICROG_AUTH_SIGN_IN_ACTIVITY.equals(activityClassName));
        if (sOplusActivityManagerFallbackAttempted
                || Build.VERSION.SDK_INT < ANDROID_16_API_LEVEL
                || (!isLimbusProcess && !isPendingIntentBridge && !isGoogleIdentityActivity)) {
            return;
        }
        sOplusActivityManagerFallbackAttempted = true;

        try {
            Class<?> managerClass = Class.forName("android.app.OplusActivityManager");
            Field singletonField = managerClass.getDeclaredField("IOplusActivityManagerSingleton");
            singletonField.setAccessible(true);
            Object singleton = singletonField.get(null);
            if (singleton == null || mirror.android.util.Singleton.mInstance == null) {
                Log.w("LimbusVA", "ColorOS ActivityManager singleton is unavailable; skip fallback");
                return;
            }

            Object currentService = mirror.android.util.Singleton.mInstance.get(singleton);
            if (currentService != null) {
                // 真正的厂商服务可用时保持原状，避免覆盖正常 binder 能力。
                return;
            }

            Class<?> defaultClass = Class.forName("android.app.IOplusActivityManager$Default");
            Object fallbackService = defaultClass.getDeclaredConstructor().newInstance();
            mirror.android.util.Singleton.mInstance.set(singleton, fallbackService);
            Log.i("LimbusVA", "Installed Android 16 ColorOS ActivityManager fallback");
        } catch (Throwable error) {
            // 不依赖容易被虚拟系统属性干扰的 ROM 标识；非 ColorOS 没有厂商类，会安全进入这里。
            Log.w("LimbusVA", "Unable to install ColorOS ActivityManager fallback", error);
        }
    }

    private ActivityInfo applyStubActivityRecord(Activity activity) {
        StubActivityRecord record = pendingStubRecords.remove(activity);
        if (record == null || record.intent == null || record.info == null) {
            return null;
        }
        try {
            activity.setIntent(record.intent);
            mirror.android.app.Activity.mActivityInfo.set(activity, record.info);
        } catch (Throwable error) {
            Log.e("LimbusVA", "AppInstrumentation failed to apply target activity info name="
                    + record.info.name, error);
        }
        return record.info;
    }

    private void restoreVirtualCallingIdentity(Activity activity, ActivityInfo info) {
        if (info == null || info.packageName == null || info.name == null) {
            return;
        }
        ComponentName callingActivity = null;
        String callingPackage = null;
        if (GMS_PACKAGE.equals(info.packageName)
                && MICROG_AUTH_SIGN_IN_ACTIVITY.equals(info.name)) {
            installActivityClientCallerProxyIfNeeded();
            callingPackage = GMS_PACKAGE;
            callingActivity = new ComponentName(
                    GMS_PACKAGE,
                    MICROG_ASSISTED_SIGN_IN_ACTIVITY);
        }
        if (callingPackage == null || callingActivity == null) {
            return;
        }
        try {
            if (mirror.android.app.Activity.mCallingPackage != null) {
                mirror.android.app.Activity.mCallingPackage.set(activity, callingPackage);
            }
            if (mirror.android.app.Activity.mCallingActivity != null) {
                mirror.android.app.Activity.mCallingActivity.set(activity, callingActivity);
            }
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to restore virtual calling identity activity="
                    + info.packageName + "/" + info.name, error);
        }
    }

    private static synchronized void installActivityClientCallerProxyIfNeeded() {
        if (sActivityClientControllerProxy != null) {
            return;
        }
        try {
            Class<?> activityClientClass = Class.forName("android.app.ActivityClient");
            Class<?> controllerInterface = Class.forName("android.app.IActivityClientController");
            Method getController = activityClientClass.getDeclaredMethod("getActivityClientController");
            getController.setAccessible(true);
            Object base = getController.invoke(null);
            if (base == null || base == sActivityClientControllerProxy) {
                sActivityClientControllerBase = base;
                sActivityClientControllerProxy = base;
                return;
            }
            sActivityClientControllerBase = base;
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (args != null && args.length > 0 && args[0] instanceof IBinder
                        && HCallbackStub.isClientLocalActivityToken((IBinder) args[0])) {
                    if ("getCallingPackage".equals(name)) {
                        return GMS_PACKAGE;
                    }
                    if ("getCallingActivity".equals(name)) {
                        ComponentName caller = new ComponentName(
                                GMS_PACKAGE,
                                MICROG_ASSISTED_SIGN_IN_ACTIVITY);
                        return caller;
                    }
                }
                return method.invoke(sActivityClientControllerBase, args);
            };
            Object proxy = Proxy.newProxyInstance(
                    controllerInterface.getClassLoader(),
                    new Class<?>[]{controllerInterface},
                    handler);
            Method setController = activityClientClass.getDeclaredMethod(
                    "setActivityClientController",
                    controllerInterface);
            setController.setAccessible(true);
            setController.invoke(null, proxy);
            sActivityClientControllerProxy = proxy;
            Log.i("LimbusVA", "Installed ActivityClient caller proxy for microG sign-in");
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to install ActivityClient caller proxy", error);
        }
    }

    private ActivityInfo getVirtualTargetActivityInfo(Activity activity) {
        ActivityInfo info = mirror.android.app.Activity.mActivityInfo.get(activity);
        if (info == null || info.packageName == null) {
            return null;
        }
        if (VirtualCore.get().getHostPkg().equals(info.packageName)) {
            return null;
        }
        if (VirtualCore.get().getInstalledAppInfo(info.packageName, 0) == null) {
            return null;
        }
        return info;
    }

    private void restoreTargetActivityContext(Activity activity, ActivityInfo targetInfo) {
        if (targetInfo == null || targetInfo.packageName == null || targetInfo.packageName.length() == 0) {
            return;
        }
        Context context = activity.getBaseContext();
        int deep = 0;
        while (context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
            deep++;
            if (deep >= 10) {
                return;
            }
        }
        try {
            Object loadedApk = VClient.get().getCurrentLoadedApk();
            Resources resources = getTargetActivityResources(activity, targetInfo);
            ClassLoader targetClassLoader = loadedApk == null ? null : LoadedApk.getClassLoader.call(loadedApk);
            if (loadedApk != null && mirror.android.app.ContextImpl.mPackageInfo != null) {
                mirror.android.app.ContextImpl.mPackageInfo.set(context, loadedApk);
            }
            if (targetClassLoader != null && mirror.android.app.ContextImpl.mClassLoader != null) {
                mirror.android.app.ContextImpl.mClassLoader.set(context, targetClassLoader);
            }
            if (resources != null && mirror.android.app.ContextImpl.mResources != null) {
                mirror.android.app.ContextImpl.mResources.set(context, resources);
            }
            if (resources != null && mirror.android.view.ContextThemeWrapper.mResources != null) {
                mirror.android.view.ContextThemeWrapper.mResources.set(activity, resources);
            }
            if (mirror.android.view.ContextThemeWrapper.mInflater != null) {
                mirror.android.view.ContextThemeWrapper.mInflater.set(activity, null);
            }
            if (resources != null && mirror.android.view.ContextThemeWrapper.mTheme != null) {
                mirror.android.view.ContextThemeWrapper.mTheme.set(activity, null);
            }
            if (resources != null && mirror.android.view.ContextThemeWrapper.mThemeResource != null) {
                mirror.android.view.ContextThemeWrapper.mThemeResource.set(activity, 0);
            }
            if (mirror.android.app.ContextImpl.mBasePackageName != null) {
                mirror.android.app.ContextImpl.mBasePackageName.set(context, targetInfo.packageName);
            }
            VClient.get().applyLimbusVisibleContextDirs(context, targetInfo.packageName);
        } catch (Throwable error) {
            Log.e("LimbusVA", "AppInstrumentation failed to restore activity context packageName="
                    + targetInfo.packageName, error);
        }
    }

    private Resources getTargetActivityResources(Activity activity, ActivityInfo targetInfo)
            throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getResourcesForApplication(targetInfo.applicationInfo);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        applyLimbusThreadContextClassLoader(mirror.android.app.Activity.mActivityInfo.get(activity), "callActivityOnResume");
        super.callActivityOnResume(activity);
        Intent intent = activity.getIntent();
        if (intent != null) {
            Bundle bundle = intent.getBundleExtra("_VA_|_sender_");
            if (bundle != null) {
                IBinder callbackToken = BundleCompat.getBinder(bundle, "_VA_|_ui_callback_");
                IUiCallback callback = IUiCallback.Stub.asInterface(callbackToken);
                if (callback != null) {
                    try {
                        callback.onAppOpened(VClient.get().getCurrentPackage(), VUserHandle.myUserId());
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                intent.removeExtra("_VA_|_sender_");
            }
        }
    }

    private boolean isOrientationLandscape(int requestedOrientation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                    || (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                    || (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
                    || (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        } else {
            return (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                    || (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                    || (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        }
    }


    @Override
    public void callActivityOnDestroy(Activity activity) {
        super.callActivityOnDestroy(activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        super.callActivityOnPause(activity);
    }


    @Override
    public void callApplicationOnCreate(Application app) {
        checkActivityCallback();
        super.callApplicationOnCreate(app);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder iBinder, IBinder iBinder2, Activity activity, Intent intent, int i, Bundle bundle) throws Throwable {
        return super.execStartActivity(context, iBinder, iBinder2, activity, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder iBinder, IBinder iBinder2, String str, Intent intent, int i, Bundle bundle) throws Throwable {
        return super.execStartActivity(context, iBinder, iBinder2, str, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder iBinder, IBinder iBinder2, Fragment fragment, Intent intent, int i) throws Throwable {
        return super.execStartActivity(context, iBinder, iBinder2, fragment, intent, i);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder iBinder, IBinder iBinder2, Activity activity, Intent intent, int i) throws Throwable {
        return super.execStartActivity(context, iBinder, iBinder2, activity, intent, i);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder iBinder, IBinder iBinder2, Fragment fragment, Intent intent, int i, Bundle bundle) throws Throwable {
        return super.execStartActivity(context, iBinder, iBinder2, fragment, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context context, IBinder iBinder, IBinder iBinder2, Activity activity, Intent intent, int i, Bundle bundle, UserHandle userHandle) throws Throwable {
        return super.execStartActivity(context, iBinder, iBinder2, activity, intent, i, bundle, userHandle);
    }
	
	public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Log.i("LimbusVA", "AppInstrumentation newActivity className=" + className
                + " intent=" + intent
                + " isStub=" + StubManifest.isStubActivity(className)
                + " appRunning=" + VClient.get().isAppRunning());
        if (intent != null && StubManifest.isStubActivity(className)) {
            StubActivityRecord record = new StubActivityRecord(intent);
            Log.i("LimbusVA", "AppInstrumentation stub record targetIntent=" + record.intent
                    + " info=" + record.info
                    + " userId=" + record.userId
                    + " token=" + record.virtualToken);
            if (record.intent != null && record.info != null) {
                // Android 16/ColorOS 可能在 ContentProvider 预热进程被回收后，直接用同名新进程
                // 承接已经排队的 Activity 事务。此时 Stub 记录仍然有效，但 VClient 配置已随旧
                // 进程丢失，必须先用记录中的准确虚拟用户重新向服务端登记当前进程。
                recoverClientConfigIfNeeded(record.info, record.userId);
                if (!VClient.get().isAppRunning()) {
                    Log.i("LimbusVA", "AppInstrumentation bind application before target activity package="
                            + record.info.packageName + " process=" + record.info.processName);
                    VClient.get().bindApplication(record.info.packageName, record.info.processName);
                }
                ClassLoader appClassLoader = getTargetActivityClassLoader(record.info.applicationInfo);
                record.intent.setExtrasClassLoader(appClassLoader);
                try {
                    appClassLoader.loadClass(record.info.name);
                } catch (ClassNotFoundException error) {
                    intent.putExtra("_VA_|_target_class_missing_", true);
                    Log.e("LimbusVA", "AppInstrumentation target activity class missing; keep stub for diagnostic className="
                            + record.info.name, error);
                    return super.newActivity(cl, className, intent);
                }
                setThreadContextClassLoader(appClassLoader, "newActivity");
                Activity activity = super.newActivity(appClassLoader, record.info.name, record.intent);
                pendingStubRecords.put(activity, record);
                return activity;
            }
        }
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return root.newActivity(cl, className, intent);
        }
    }

    /**
     * 在目标 Activity 实例化前恢复当前 Stub 进程的虚拟客户端配置。
     *
     * <p>服务端最初会通过 ShadowContentProvider 初始化进程，但部分 Android 16 厂商系统
     * 会在 Activity 已入队后回收该预热进程，并以相同进程名启动一个全新的 Activity
     * 进程。新进程没有旧进程内存中的 {@link ClientConfig}，因此这里使用 Stub 记录携带的
     * 虚拟用户 ID 重新登记，避免把正常的系统重启误判为 “Unrecorded process”。</p>
     *
     * @param activityInfo 即将实例化的虚拟目标 Activity 信息
     * @param userId Stub 启动记录中的虚拟用户 ID
     */
    private void recoverClientConfigIfNeeded(ActivityInfo activityInfo, int userId) {
        VClient client = VClient.get();
        if (client.getClientConfig() != null) {
            return;
        }

        String processName = activityInfo.processName != null
                ? activityInfo.processName
                : activityInfo.packageName;
        Log.w("LimbusVA", "Recovering missing client config before activity creation package="
                + activityInfo.packageName + " process=" + processName + " userId=" + userId);

        ClientConfig recovered = VActivityManager.get().initProcess(
                activityInfo.packageName,
                processName,
                userId,
                VActivityManager.PROCESS_TYPE_ACTIVITY
        );

        // 正常情况下 Provider 调用已在当前进程写入配置；此赋值只兜底处理厂商系统
        // 将 Provider 调用转交到其他线程或返回值先于本地状态可见的异常时序。
        if (client.getClientConfig() == null && recovered != null) {
            client.initProcess(recovered);
        }
        if (client.getClientConfig() == null) {
            throw new RuntimeException("Unable to recover virtual process: " + processName);
        }
    }

    private ClassLoader getTargetActivityClassLoader(android.content.pm.ApplicationInfo appInfo) {
        ClassLoader classLoader = null;
        if (VClient.get().isAppRunning()) {
            try {
                classLoader = VClient.get().getClassLoader();
            } catch (Throwable error) {
                Log.w("LimbusVA", "AppInstrumentation unable to use bound LoadedApk classloader", error);
            }
        }
        if (classLoader == null) {
            classLoader = VClient.get().getClassLoader(appInfo);
        }
        return classLoader;
    }

    private void applyLimbusThreadContextClassLoader(ActivityInfo activityInfo, String reason) {
        if (activityInfo == null || activityInfo.applicationInfo == null
                || !"com.ProjectMoon.LimbusCompany".equals(activityInfo.packageName)) {
            return;
        }
        ClassLoader classLoader = getTargetActivityClassLoader(activityInfo.applicationInfo);
        setThreadContextClassLoader(classLoader, reason);
    }

    private void setThreadContextClassLoader(ClassLoader classLoader, String reason) {
        if (classLoader == null) {
            return;
        }
        try {
            Thread thread = Thread.currentThread();
            ClassLoader oldLoader = thread.getContextClassLoader();
            if (oldLoader != classLoader) {
                thread.setContextClassLoader(classLoader);
            }
        } catch (Throwable error) {
            Log.w("LimbusVA", "Failed to set Limbus activity thread context classloader reason=" + reason, error);
        }
    }
}
