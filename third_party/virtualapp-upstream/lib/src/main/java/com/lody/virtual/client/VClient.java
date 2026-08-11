package com.lody.virtual.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;

import com.lody.virtual.GmsSupport;
import com.lody.virtual.client.core.CrashHandler;
import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.core.SettingConfig;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.fixer.ContextFixer;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.delegate.AppInstrumentation;
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub;
import com.lody.virtual.client.hook.providers.ProviderHook;
import com.lody.virtual.client.hook.proxies.am.HCallbackStub;
import com.lody.virtual.client.hook.secondary.ProxyServiceFactory;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VDeviceManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.client.ipc.VirtualStorageManager;
import com.lody.virtual.client.service.ServiceManager;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.NativeLibraryHelperCompat;
import com.lody.virtual.helper.compat.StorageManagerCompat;
import com.lody.virtual.helper.compat.StrictModeCompat;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.remote.PendingResultData;
import com.lody.virtual.remote.VDeviceConfig;
import com.lody.virtual.server.pm.PackageSetting;
import com.xdja.activitycounter.ActivityCounterManager;
import com.xdja.zs.VAppPermissionManager;
import com.xdja.zs.controllerManager;
import com.xdja.zs.exceptionRecorder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.KeyStore;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import mirror.android.app.ActivityManagerNative;
import mirror.android.app.ActivityThread;
import mirror.android.app.ActivityThreadNMR1;
import mirror.android.app.ActivityThreadQ;
import mirror.android.app.ContextImpl;
import mirror.android.app.ContextImplKitkat;
import mirror.android.app.IActivityManager;
import mirror.android.app.LoadedApk;
import mirror.android.app.LoadedApkICS;
import mirror.android.app.LoadedApkKitkat;
import mirror.android.content.ContentProviderHolderOreo;
import mirror.android.content.pm.ApplicationInfoL;
import mirror.android.content.pm.ApplicationInfoN;
import mirror.android.content.res.CompatibilityInfo;
import mirror.android.providers.Settings;
import mirror.android.renderscript.RenderScriptCacheDir;
import mirror.android.security.net.config.NetworkSecurityConfigProvider;
import mirror.android.view.CompatibilityInfoHolder;
import mirror.android.view.DisplayAdjustments;
import mirror.android.view.HardwareRenderer;
import mirror.android.view.RenderScript;
import mirror.android.view.ThreadedRenderer;
import mirror.com.android.internal.content.ReferrerIntent;
import mirror.dalvik.system.VMRuntime;
import mirror.java.lang.ThreadGroupN;
import mirror.oem.HwApiCacheManagerEx;
import mirror.oem.HwFrameworkFactory;

import static com.lody.virtual.client.core.VirtualCore.getConfig;
import static com.lody.virtual.os.VUserHandle.getUserId;
import static com.lody.virtual.remote.InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK;

/**
 * 管理 VirtualApp 容器客户端进程、应用绑定与系统组件回调。
 *
 * @author Lody
 */

public final class VClient extends IVClient.Stub {

    private static final int NEW_INTENT = 11;
    private static final int RECEIVER = 12;
    private static final int FINISH_ACTIVITY = 13;

    private static final String TAG = VClient.class.getSimpleName();

    @SuppressLint("StaticFieldLeak")
    private static final VClient gClient = new VClient();

    private final H mH = new H();
    private Instrumentation mInstrumentation = AppInstrumentation.getDefault();
    /**
     * 当前虚拟进程的服务端配置。
     *
     * <p>该字段由 {@link com.lody.virtual.client.stub.ShadowContentProvider} 的 Binder
     * 线程写入，并会在 Activity 主线程创建目标组件时读取。必须使用 {@code volatile}
     * 建立跨线程可见性，否则 Android 16 的快速启动链路可能在配置已经写入后仍读取到
     * 旧的 {@code null}，进而误报 “Unrecorded process”。</p>
     */
    private volatile ClientConfig clientConfig;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private CrashHandler crashHandler;
    private InstalledAppInfo mAppInfo;
    private int mTargetSdkVersion;
    private ConditionVariable mBindingApplicationLock;
    private boolean mEnvironmentPrepared = false;
    private volatile boolean mHideHostPackageForLimbus = false;
    private static final ThreadLocal<Boolean> sApplyingLimbusPlayCoreLocalTesting = new ThreadLocal<>();
    private static final HashSet<String> sLoggedLimbusPlayCoreDirs = new HashSet<>();
    private static final String LIMBUS_PLAYCORE_LOCAL_TESTING_PROP = "debug.limbus.playcore.local_testing";
    private static final String LIMBUS_APPSEALING_IP_SERVICE_PROP = "debug.limbus.appsealing_ip_service";
    private static final String LIMBUS_TRANSLATION_REDIRECT_GATE_FILE = "redirect-gate.pid";
    private static final String LIMBUS_TRANSLATION_FONT_FILE = "ChineseFont-6541a94a.ttf";
    private static final long LIMBUS_TRANSLATION_FONT_SIZE = 24047784L;
    private static final String LIMBUS_PACKAGE_NAME = "com.ProjectMoon.LimbusCompany";
    private static final String FRAMEWORK_CREDENTIAL_SERVICE_NAME = "credential";
    private static final String MICROG_PACKAGE_NAME = "com.google.android.gms";
    private static final String MICROG_UI_PROCESS_NAME = "com.google.android.gms:ui";
    private static final String MICROG_SETTINGS_PROVIDER_CLASS_NAME =
            "org.microg.gms.settings.SettingsProvider";
    private static final String MICROG_SETTINGS_PROVIDER_AUTHORITY =
            "com.google.android.gms.microg.settings";
    private static final AtomicBoolean sLimbusTranslationRedirectGateStarted = new AtomicBoolean(false);
    private static final AtomicBoolean sLimbusFrameworkCredentialServiceDisabled = new AtomicBoolean(false);
    private ServiceConnection mLimbusAppSealingIpServiceConnection;
    private int systemPid;

    public InstalledAppInfo getAppInfo() {
        return mAppInfo;
    }

    public static VClient get() {
        return gClient;
    }

    public boolean isEnvironmentPrepared() {
        return mEnvironmentPrepared;
    }

    public boolean isAppUseOutsideAPK() {
        InstalledAppInfo appInfo = getAppInfo();
        return appInfo != null && appInfo.appMode == MODE_APP_USE_OUTSIDE_APK;
    }

    public VDeviceConfig getDeviceConfig() {
        return VDeviceManager.get().getDeviceConfig(getUserId(getVUid()));
    }

    public Application getCurrentApplication() {
        return mInitialApplication;
    }

    public String getCurrentPackage() {
        return mBoundApplication != null ?
                mBoundApplication.appInfo.packageName : VPackageManager.get().getNameForUid(getVUid());
    }

    public ApplicationInfo getCurrentApplicationInfo() {
        return mBoundApplication != null ? mBoundApplication.appInfo : null;
    }

    public Object getCurrentLoadedApk() {
        return mBoundApplication != null ? mBoundApplication.info : null;
    }

    public boolean shouldHideHostPackageForLimbus() {
        return mHideHostPackageForLimbus;
    }

    public int getCurrentTargetSdkVersion() {
        return mTargetSdkVersion == 0 ?
                VirtualCore.get().getTargetSdkVersion()
                : mTargetSdkVersion;
    }

    public CrashHandler getCrashHandler() {
        return crashHandler;
    }

    public void setCrashHandler(CrashHandler crashHandler) {
        this.crashHandler = crashHandler;
    }

    public int getSystemPid() {
        return systemPid;
    }

    public int getVUid() {
        if (clientConfig == null) {
            return 0;
        }
        return clientConfig.vuid;
    }

    /**
     * $Px
     * 0-99
     */
    public int getVpid() {
        if (clientConfig == null) {
            return 0;
        }
        return clientConfig.vpid;
    }

    public int getBaseVUid() {
        if (clientConfig == null) {
            return 0;
        }
        return VUserHandle.getAppId(clientConfig.vuid);
    }

    public int getCallingVUid() {
        return VActivityManager.get().getCallingUid();
    }

    public ClassLoader getClassLoader(ApplicationInfo appInfo) {
        Context context = createPackageContext(appInfo);
        return context.getClassLoader();
    }

    private void sendMessage(int what, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.obj = obj;
        mH.sendMessage(msg);
    }

    @Override
    public IBinder getAppThread() {
        return ActivityThread.getApplicationThread.call(VirtualCore.mainThread());
    }

    @Override
    public IBinder getToken() {
        if (clientConfig == null) {
            return null;
        }
        return clientConfig.token;
    }

    public ClientConfig getClientConfig() {
        return clientConfig;
    }

    @Override
    public boolean isAppRunning() {
        return mBoundApplication != null;
    }
    //xdja
    int countOfActivity = 0;
    @Override
    public boolean isAppForeground(){
        return countOfActivity > 0;
    }
    /**
     * 初始化容器进程配置，或在 VirtualApp 服务端被系统重启后重新连接同一进程。
     *
     * <p>客户端游戏进程可能比 VAMS 服务端存活更久。此时新服务端会为相同的
     * vpid/vuid/进程名重建 token，应刷新 token 而不是拒绝启动。任何虚拟身份不一致的
     * 配置仍会被拒绝，避免把正在运行的进程错误切换给其他应用。</p>
     *
     * @param newClientConfig 服务端下发的虚拟进程配置。
     */
    public synchronized void initProcess(ClientConfig newClientConfig) {
        if (newClientConfig == null) {
            throw new IllegalArgumentException("clientConfig == null");
        }
        if (this.clientConfig != null) {
            if (isSameVirtualProcess(this.clientConfig, newClientConfig)) {
                Log.i("LimbusVA", "Reconnect existing virtual process: " + newClientConfig.processName
                        + " vpid=" + newClientConfig.vpid + " vuid=" + newClientConfig.vuid);
                // 刷新已失效的服务端 token，保留已绑定的 Application 和 Unity 运行时。
                this.clientConfig = newClientConfig;
                return;
            }
            throw new RuntimeException("reject init process: " + newClientConfig.processName
                    + ", this process is : " + this.clientConfig.processName);
        }
        this.clientConfig = newClientConfig;
    }

    /**
     * 校验新旧配置是否指向同一个虚拟进程槽位。
     *
     * @param current 客户端正在使用的配置。
     * @param incoming 新服务端尝试恢复的配置。
     * @return 虚拟 UID、进程槽、位数、进程名和包名都一致时返回 {@code true}。
     */
    private static boolean isSameVirtualProcess(ClientConfig current, ClientConfig incoming) {
        return current.vpid == incoming.vpid
                && current.vuid == incoming.vuid
                && current.is64Bit == incoming.is64Bit
                && TextUtils.equals(current.processName, incoming.processName)
                && TextUtils.equals(current.packageName, incoming.packageName);
    }

    private void handleNewIntent(NewIntentData data) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            intent = ReferrerIntent.ctor.newInstance(data.intent, data.creator);
        } else {
            intent = data.intent;
        }
        if (ActivityThread.performNewIntents != null) {
            ActivityThread.performNewIntents.call(
                    VirtualCore.mainThread(),
                    data.token,
                    Collections.singletonList(intent)
            );
        } else if (ActivityThreadNMR1.performNewIntents != null){
            ActivityThreadNMR1.performNewIntents.call(
                    VirtualCore.mainThread(),
                    data.token,
                    Collections.singletonList(intent),
                    true);
        } else if(ActivityThreadQ.handleNewIntent != null){
            ActivityThreadQ.handleNewIntent.call(VirtualCore.mainThread(), data.token, Collections.singletonList(intent));
        }
        if("com.tencent.mm".equals(getCurrentPackage())){
            //xdja 修复微信按2次返回键退出
            //第二次是因为是在顶层activity，微信用了Process.kill导致重启。
            if(intent.getComponent() != null && intent.getComponent().getClassName().endsWith(".ui.LauncherUI")){
                if(intent.getBooleanExtra("can_finish", false)){
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        VirtualCore.get().getContext().startActivity(home);
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        if (clientConfig == null) {
            throw new RuntimeException("Unrecorded process: " + processName);
        }
        if (isAppRunning()) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (mBindingApplicationLock != null) {
                mBindingApplicationLock.block();
                mBindingApplicationLock = null;
            } else {
                mBindingApplicationLock = new ConditionVariable();
            }
            VirtualRuntime.getUIHandler().post(new Runnable() {
                @Override
                public void run() {
                    bindApplicationNoCheck(packageName, processName);
                    ConditionVariable lock = mBindingApplicationLock;
                    mBindingApplicationLock = null;
                    if (lock != null) {
                        lock.open();
                    }
                }
            });
            if (mBindingApplicationLock != null) {
                mBindingApplicationLock.block();
            }
        } else {
            bindApplicationNoCheck(packageName, processName);
        }
    }


    private void bindApplicationNoCheck(String packageName, String processName) {
        if (isAppRunning()) {
            return;
        }
        if (processName == null) {
            processName = packageName;
        }
        systemPid = VActivityManager.get().getSystemPid();
        try {
            setupUncaughtHandler();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        final int userId = getUserId(getVUid());
        try {
            fixInstalledProviders();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        VDeviceConfig deviceConfig = getDeviceConfig();
        VDeviceManager.get().applyBuildProp(deviceConfig);
        final boolean isSubRemote = VirtualCore.get().isPluginEngine();
        // Fix: com.loafwallet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if("com.loafwallet".equals(packageName)) {
                try {
                    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                    keyStore.load(null);
                    Enumeration<String> aliases = keyStore.aliases();
                    while (aliases.hasMoreElements()) {
                        String entry = aliases.nextElement();
                        VLog.w(TAG, "remove entry: " + entry);
                        keyStore.deleteEntry(entry);
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        ActivityThread.mInitialApplication.set(
                VirtualCore.mainThread(),
                null
        );
        AppBindData data = new AppBindData();
        InstalledAppInfo info = VirtualCore.get().getInstalledAppInfo(packageName, 0);
        if (info == null) {
            new Exception("app not exist").printStackTrace();
            Process.killProcess(0);
            System.exit(0);
        }
        mAppInfo = info;
        data.appInfo = VPackageManager.get().getApplicationInfo(packageName, 0, userId);

        data.processName = processName;
        data.providers = VPackageManager.get().queryContentProviders(
                processName,
                getVUid(),
                PackageManager.GET_META_DATA
        );
        data.providers = includeMicrogSettingsProviderIfMissing(
                packageName,
                processName,
                userId,
                data.providers
        );
        mTargetSdkVersion = data.appInfo.targetSdkVersion;
        VLog.i(TAG, "Binding application %s (%s [%d])", data.appInfo.packageName, data.processName, Process.myPid());
        mBoundApplication = data;
        VirtualRuntime.setupRuntime(data.processName, data.appInfo);
        if (VirtualCore.get().isPluginEngine()) {
            File apkFile = new File(info.getApkPath());
            File libDir = new File(data.appInfo.nativeLibraryDir);
            if (!apkFile.exists()) {
                VirtualCore.get().requestCopyPackage64(packageName);
            }
            if (!libDir.exists()) {
                NativeLibraryHelperCompat.copyNativeBinaries(apkFile, libDir);
            }
        }
        int targetSdkVersion = data.appInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (VirtualCore.get().getTargetSdkVersion() >= Build.VERSION_CODES.N
                    && targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && targetSdkVersion < Build.VERSION_CODES.LOLLIPOP) {
            mirror.android.os.Message.updateCheckRecycle.call(targetSdkVersion);
        }
        AlarmManager alarmManager = (AlarmManager) VirtualCore.get().getContext().getSystemService(Context.ALARM_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            if (mirror.android.app.AlarmManager.mTargetSdkVersion != null) {
                try {
                    mirror.android.app.AlarmManager.mTargetSdkVersion.set(alarmManager, targetSdkVersion);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        //tmp dir
        File tmpDir;
        if (isSubRemote) {
            tmpDir = new File(VEnvironment.getDataUserPackageDirectory64(userId, info.packageName), "cache");
        } else {
            tmpDir = new File(VEnvironment.getDataUserPackageDirectory(userId, info.packageName), "cache");
        }
        if(!tmpDir.exists()){
            tmpDir.mkdirs();
        }
        System.setProperty("java.io.tmpdir", tmpDir.getAbsolutePath());

        fixForEmui10();

        if (getConfig().isEnableIORedirect()) {
            if (VirtualCore.get().isIORelocateWork()) {
                startIORelocater(info, isSubRemote);
            } else {
                VLog.w(TAG, "IO Relocate verify fail.");
            }
        }
        NativeEngine.launchEngine();
        mEnvironmentPrepared = true;
        Object mainThread = VirtualCore.mainThread();
        NativeEngine.startDexOverride();
        initDataStorage(isSubRemote, userId, packageName);
        if (LIMBUS_PACKAGE_NAME.equals(packageName)) {
            applyLimbusVisibleApplicationInfo(data.appInfo, null);
        }
        disableFrameworkCredentialServiceForLimbus(packageName);
        Context context = createPackageContext(data.appInfo);
        applyLimbusVisibleContextDirs(context, packageName);
        File codeCacheDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            codeCacheDir = context.getCodeCacheDir();
        } else {
            codeCacheDir = context.getCacheDir();
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            if (HardwareRenderer.setupDiskCache != null) {
                HardwareRenderer.setupDiskCache.call(codeCacheDir);
            }
        } else {
            if (ThreadedRenderer.setupDiskCache != null) {
                ThreadedRenderer.setupDiskCache.call(codeCacheDir);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (RenderScriptCacheDir.setupDiskCache != null) {
                RenderScriptCacheDir.setupDiskCache.call(codeCacheDir);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (RenderScript.setupDiskCache != null) {
                RenderScript.setupDiskCache.call(codeCacheDir);
            }
        }
        mBoundApplication.info = ContextImpl.mPackageInfo.get(context);
        applyLimbusVisibleLoadedApkDirs(mBoundApplication.info, packageName);
        ApplicationInfo applicationInfo = LoadedApk.mApplicationInfo.get(mBoundApplication.info);
        if(applicationInfo.nativeLibraryDir == null){
            Log.w("kk-test", "applicationInfo.nativeLibraryDir = null,"+context.getApplicationInfo().nativeLibraryDir, new Exception());
        }
        if ("com.ProjectMoon.LimbusCompany".equals(packageName)) {
            applyLimbusVisibleApplicationInfo(data.appInfo, applicationInfo);
        } else {
            applicationInfo.nativeLibraryDir = data.appInfo.nativeLibraryDir;
        }
        Object thread = VirtualCore.mainThread();
        Object boundApp = mirror.android.app.ActivityThread.mBoundApplication.get(thread);
        if (mirror.android.app.ActivityThread.AppBindData.appInfo != null) {
            mirror.android.app.ActivityThread.AppBindData.appInfo.set(boundApp, data.appInfo);
        }
        if (mirror.android.app.ActivityThread.AppBindData.processName != null) {
            mirror.android.app.ActivityThread.AppBindData.processName.set(boundApp, data.processName);
        }
        if (mirror.android.app.ActivityThread.AppBindData.instrumentationName != null) {
            mirror.android.app.ActivityThread.AppBindData.instrumentationName.set(
                    boundApp,
                    new ComponentName(data.appInfo.packageName, Instrumentation.class.getName())
            );
        }
        if (mirror.android.app.ActivityThread.AppBindData.info != null) {
            mirror.android.app.ActivityThread.AppBindData.info.set(boundApp, data.info);
        }
        if (ActivityThread.AppBindData.providers != null) {
            ActivityThread.AppBindData.providers.set(boundApp, data.providers);
        }
        if (LoadedApk.mSecurityViolation != null) {
            LoadedApk.mSecurityViolation.set(mBoundApplication.info, false);
        }
        if (VMRuntime.getRuntime != null && VMRuntime.setTargetSdkVersion != null) {
            VMRuntime.setTargetSdkVersion.call(VMRuntime.getRuntime.call(), data.appInfo.targetSdkVersion);
        }
        Configuration configuration = context.getResources().getConfiguration();
        boolean is64Bit = VirtualRuntime.is64bit();
        if (!isSubRemote && info.flag == PackageSetting.FLAG_RUN_BOTH_32BIT_64BIT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<String> supportAbiList = new LinkedList<>();
            for (String abi : Build.SUPPORTED_ABIS) {
                if(is64Bit) {
                    if (NativeLibraryHelperCompat.is64bitAbi(abi)) {
                        supportAbiList.add(abi);
                    }
                } else {
                    if (NativeLibraryHelperCompat.is32bitAbi(abi)) {
                        supportAbiList.add(abi);
                    }
                }
            }
            String[] supportAbis = supportAbiList.toArray(new String[0]);
            Reflect.on(Build.class).set("SUPPORTED_ABIS", supportAbis);
        }
        Object compatInfo = null;
        if (CompatibilityInfo.ctor != null) {
            compatInfo = CompatibilityInfo.ctor.newInstance(data.appInfo, configuration.screenLayout, configuration.smallestScreenWidthDp, false);
        }
        if (CompatibilityInfo.ctorLG != null) {
            compatInfo = CompatibilityInfo.ctorLG.newInstance(data.appInfo, configuration.screenLayout, configuration.smallestScreenWidthDp, false, 0);
        }

        if (compatInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    DisplayAdjustments.setCompatibilityInfo.call(ContextImplKitkat.mDisplayAdjustments.get(context), compatInfo);
                }
                DisplayAdjustments.setCompatibilityInfo.call(LoadedApkKitkat.mDisplayAdjustments.get(mBoundApplication.info), compatInfo);
            } else {
                CompatibilityInfoHolder.set.call(LoadedApkICS.mCompatibilityInfo.get(mBoundApplication.info), compatInfo);
            }
        }
		//ssl适配
		if (NetworkSecurityConfigProvider.install != null) {
            Security.removeProvider("AndroidNSSP");
            NetworkSecurityConfigProvider.install.call(context);
        }

        if(data.appInfo != null && "com.tencent.mm".equals(data.appInfo.packageName)
                && "com.tencent.mm".equals(data.appInfo.processName)){
            ClassLoader originClassLoader = context.getClassLoader();
            fixWeChatTinker(context, data.appInfo, originClassLoader);
        }

        VirtualCore.get().getAppCallback().beforeStartApplication(packageName, processName, context);
        ApplicationInfo loadedApkInfo = LoadedApk.mApplicationInfo.get(data.info);
        if ("com.ProjectMoon.LimbusCompany".equals(packageName)) {
            applyLimbusVisibleApplicationInfo(data.appInfo, loadedApkInfo);
            applyLimbusVisibleLoadedApkDirs(data.info, packageName);
            applyLimbusThreadContextClassLoader(data.info, "bindApplication");
        }
        boolean limbusAppSealing = shouldBypassLimbusAppSealingApplication(data.appInfo, loadedApkInfo);
        mHideHostPackageForLimbus = limbusAppSealing;
        Application existingApplication = LoadedApk.mApplication.get(data.info);
        Log.i("LimbusVA", "VClient.bindApplication appInfo package=" + data.appInfo.packageName
                + " className=" + data.appInfo.className
                + " processName=" + data.appInfo.processName
                + " sourceDir=" + data.appInfo.sourceDir
                + " publicSourceDir=" + data.appInfo.publicSourceDir
                + " nativeLibraryDir=" + data.appInfo.nativeLibraryDir
                + " dataDir=" + data.appInfo.dataDir
                + " targetSdk=" + data.appInfo.targetSdkVersion);
        Log.i("LimbusVA", "VClient.bindApplication loadedApkInfo package="
                + (loadedApkInfo == null ? null : loadedApkInfo.packageName)
                + " className=" + (loadedApkInfo == null ? null : loadedApkInfo.className)
                + " processName=" + (loadedApkInfo == null ? null : loadedApkInfo.processName)
                + " sourceDir=" + (loadedApkInfo == null ? null : loadedApkInfo.sourceDir)
                + " nativeLibraryDir=" + (loadedApkInfo == null ? null : loadedApkInfo.nativeLibraryDir)
                + " dataDir=" + (loadedApkInfo == null ? null : loadedApkInfo.dataDir)
                + " existingApplication=" + existingApplication);
        final String shutdownPackageName = packageName;
        final String shutdownProcessName = processName;
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                Log.e("LimbusVA", "Process shutdown during virtual bind package=" + shutdownPackageName
                        + " process=" + shutdownProcessName
                        + " pid=" + Process.myPid()), "limbus-va-shutdown"));
        Log.i("LimbusVA", "VClient.bindApplication before makeApplication package=" + packageName
                + " process=" + processName + " info=" + data.info);

        try {
            if (limbusAppSealing) {
                logLimbusAppSealingEnvironment(context, data.appInfo, loadedApkInfo);
                if (isLimbusAppSealingNativeLibMissing(data.appInfo)) {
                    Log.w("LimbusVA", "VClient.bindApplication bypass AppSealing Application because native libs are missing");
                    data.appInfo.className = Application.class.getName();
                    if (loadedApkInfo != null) {
                        loadedApkInfo.className = Application.class.getName();
                    }
                } else {
                    Log.i("LimbusVA", "VClient.bindApplication allow AppSealing Application to load sealed dex");
                }
            }
            if(LoadedApk.mApplication != null) {
                LoadedApk.mApplication.set(data.info, null);
            }
            AtomicBoolean limbusScannerRunning = null;
            Thread limbusScanner = null;
            /*
             * AppSealing 加载后会很快使用不经过 Java 层的结束进程路径。后台线程需要在
             * Application 初始化期间持续寻找刚加载完成的代码，并尽早接管这些固定位置。
             * Android 16 的失效回溯地址已由原生信号保护单独处理，因此这里不能停用扫描。
             */
            if (limbusAppSealing) {
                limbusScannerRunning = new AtomicBoolean(true);
                AtomicBoolean scannerRunning = limbusScannerRunning;
                String nativeLibraryDir = data.appInfo.nativeLibraryDir;
                limbusScanner = new Thread(() -> {
                    long deadline = System.currentTimeMillis() + 4000L;
                    int scans = 0;
                    while (scannerRunning.get() && System.currentTimeMillis() < deadline) {
                        NativeEngine.scanLimbusNativeSyscalls(nativeLibraryDir);
                        scans++;
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    Log.i("LimbusVA", "VClient.bindApplication AppSealing syscall scanner stopped scans=" + scans);
                }, "limbus-appsealing-syscall-scan");
                limbusScanner.setDaemon(true);
                limbusScanner.start();
            }
            try {
                mInitialApplication = LoadedApk.makeApplication.call(data.info, false, null);
            } finally {
                if (limbusScannerRunning != null) {
                    limbusScannerRunning.set(false);
                }
                if (limbusScanner != null) {
                    limbusScanner.interrupt();
                }
            }
            Log.i("LimbusVA", "VClient.bindApplication makeApplication success app=" + mInitialApplication);
            if (limbusAppSealing) {
                NativeEngine.scanLimbusNativeSyscalls(data.appInfo.nativeLibraryDir);
                startLimbusAppSealingPatchMaintainer();
            }
        } catch (Throwable e) {
            Log.e("LimbusVA", "VClient.bindApplication makeApplication failed", e);
            throw new RuntimeException("Unable to makeApplication", e);
        }
        Log.e("kk", data.info+" mInitialApplication set  " + LoadedApk.mApplication.get(data.info));
        mirror.android.app.ActivityThread.mInitialApplication.set(mainThread, mInitialApplication);
        Log.i("LimbusVA", "VClient.bindApplication before ContextFixer app=" + mInitialApplication);
        ContextFixer.fixContext(mInitialApplication);
        Log.i("LimbusVA", "VClient.bindApplication after ContextFixer app=" + mInitialApplication);
        if (Build.VERSION.SDK_INT >= 24 && "com.tencent.mm:recovery".equals(processName)) {
            fixWeChatRecovery(mInitialApplication);
        }
        if (GmsSupport.VENDING_PKG.equals(packageName)) {
            try {
                context.getSharedPreferences("vending_preferences", 0)
                        .edit()
                        .putBoolean("notify_updates", false)
                        .putBoolean("notify_updates_completion", false)
                        .apply();
                context.getSharedPreferences("finsky", 0)
                        .edit()
                        .putBoolean("auto_update_enabled", false)
                        .apply();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        /*
         * Support Atlas plugin framework
         * see:
         * https://github.com/alibaba/atlas/blob/master/atlas-core/src/main/java/android/taobao/atlas/bridge/BridgeApplicationDelegate.java
         */
        List<ProviderInfo> providers = ActivityThread.AppBindData.providers.get(boundApp);
        Log.i("LimbusVA", "VClient.bindApplication providers=" + (providers == null ? null : providers.size()));
        if (providers != null && !providers.isEmpty()) {
            Log.i("LimbusVA", "VClient.bindApplication before installContentProviders");
            installContentProviders(mInitialApplication, providers);
            Log.i("LimbusVA", "VClient.bindApplication after installContentProviders");
        }
        VirtualCore.get().getAppCallback().beforeApplicationCreate(packageName, processName, mInitialApplication);
        try {
            Log.i("LimbusVA", "VClient.bindApplication before callApplicationOnCreate app=" + mInitialApplication);
            mInstrumentation.callApplicationOnCreate(mInitialApplication);
            Log.i("LimbusVA", "VClient.bindApplication after callApplicationOnCreate app=" + mInitialApplication);
            if (limbusAppSealing
                    && packageName.equals(processName)
                    && isLimbusAppSealingIpServiceExperimentEnabled()) {
                bindLimbusAppSealingIpService(mInitialApplication);
            }
            InvocationStubManager.getInstance().checkEnv(HCallbackStub.class);
            Log.i("LimbusVA", "VClient.bindApplication after HCallback check");
            Application createdApp = ActivityThread.mInitialApplication.get(mainThread);
            if (createdApp != null) {
                if (TextUtils.equals(VirtualCore.get().getHostPkg(), createdApp.getPackageName())) {
                    VLog.w("kk", "mInitialApplication is host!!");
                    ActivityThread.mInitialApplication.set(mainThread, mInitialApplication);
                    //reset mInitialApplication
                } else {
                    mInitialApplication = createdApp;
                }
            }
            //reset
            if(LoadedApk.mApplication != null) {
                Application application = LoadedApk.mApplication.get(data.info);
                if (application != null && TextUtils.equals(VirtualCore.get().getHostPkg(), application.getPackageName())) {
                    VLog.w("kk", "LoadedApk's mApplication is host!");
                    LoadedApk.mApplication.set(data.info, mInitialApplication);
                }
            }
        } catch (Exception e) {
            Log.e("LimbusVA", "VClient.bindApplication callApplicationOnCreate failed", e);
            if (!mInstrumentation.onException(mInitialApplication, e)) {
                throw new RuntimeException("Unable to create application " + data.appInfo.name + ": " + e.toString(), e);
            }
        }
        Log.i("LimbusVA", "VClient.bindApplication before register lifecycle");
        mInitialApplication.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle bundle) { }
            @Override
            public void onActivityStarted(Activity activity) {
                ActivityCounterManager.get().activityCountAdd(activity.getPackageName(),activity.getClass().getName(), android.os.Process.myPid());
                countOfActivity++;
            }
            @Override
            public void onActivityResumed(Activity activity) {
                //检测截屏权限
                boolean screenShort = VAppPermissionManager.get().getAppPermissionEnable(
                        activity.getPackageName(), VAppPermissionManager.PROHIBIT_SCREEN_SHORT_RECORDER);
                Log.e(TAG, "screenShort: " + screenShort);
                if (screenShort) {
                    activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE
                            , WindowManager.LayoutParams.FLAG_SECURE);
                }
            }
            @Override
            public void onActivityPaused(Activity activity) { }
            @Override
            public void onActivityStopped(Activity activity) {
                ActivityCounterManager.get().activityCountReduce(activity.getPackageName(),activity.getClass().getName(),android.os.Process.myPid());
                countOfActivity--;
            }
            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle bundle) { }
            @Override
            public void onActivityDestroyed(Activity activity) {

            }
        });
        Log.i("LimbusVA", "VClient.bindApplication after register lifecycle");
        VirtualCore.get().getAppCallback().afterApplicationCreate(packageName, processName, mInitialApplication);
        Log.i("LimbusVA", "VClient.bindApplication after afterApplicationCreate");
        VActivityManager.get().appDoneExecuting(info.packageName);
        Log.i("LimbusVA", "VClient.bindApplication done package=" + packageName + " process=" + processName);

        //xdja
       /* context.getCacheDir();
        List<InstalledAppInfo> modules = VirtualCore.get().getInstalledApps(0);
        for (InstalledAppInfo module : modules) {
            String libPath = VEnvironment.getAppLibDirectory(module.packageName).getAbsolutePath();
            LoadModules.loadModule(module.getApkPath(), module.getOdexFile().getParent(), libPath, mInitialApplication);
        }*/
    }

    private void initDataStorage(boolean is64bit, int userId, String pkg) {
        // ensure dir created
        if (is64bit) {
            VEnvironment.getDataUserPackageDirectory64(userId, pkg);
            VEnvironment.getDeDataUserPackageDirectory64(userId, pkg);
        } else {
            VEnvironment.getDataUserPackageDirectory(userId, pkg);
            VEnvironment.getDeDataUserPackageDirectory(userId, pkg);
        }
    }


    private void fixForEmui10() {
        if (BuildCompat.isQ() && BuildCompat.isEMUI()) {
            if (HwApiCacheManagerEx.getDefault != null && HwApiCacheManagerEx.mPkg != null) {
                HwApiCacheManagerEx.mPkg.set(HwApiCacheManagerEx.getDefault.call(), VirtualCore.get().getPM());
            } else if (HwFrameworkFactory.getHwApiCacheManagerEx != null) {
                Object hwmgr = HwFrameworkFactory.getHwApiCacheManagerEx.call();
                if (hwmgr != null) {
                    try {
                        Reflect.on(hwmgr).call("apiPreCache", VirtualCore.get().getPM());
                    } catch (Throwable e) {
                        //ignore
                    }
                }
            }
        }
    }

    private void fixWeChatRecovery(Application app) {
        try {
            Field field = app.getClassLoader().loadClass("com.tencent.recovery.Recovery").getField("context");
            field.setAccessible(true);
            if (field.get(null) != null) {
                return;
            }
            field.set(null, app.getBaseContext());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void fixWeChatTinker(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader)
    {
        String dataDir = applicationInfo.dataDir;
        File tinker = new File(dataDir, "tinker");
        if(tinker.exists()){
            Log.e("wxd", " deleteWechatTinker " + tinker.getPath());
            FileUtils.deleteDir(tinker);
        }
        File tinker_temp = new File(dataDir, "tinker_temp");
        if(tinker_temp.exists()){
            Log.e("wxd", " deleteWechatTinker " + tinker_temp.getPath());
            FileUtils.deleteDir(tinker_temp);
        }
        File tinker_server = new File(dataDir, "tinker_server");
        if(tinker_server.exists()){
            Log.e("wxd", " deleteWechatTinker " + tinker_server.getPath());
            FileUtils.deleteDir(tinker_server);
        }
    }

    private void setupUncaughtHandler() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        ThreadGroup newRoot = new RootThreadGroup(root);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            final List<ThreadGroup> groups = mirror.java.lang.ThreadGroup.groups.get(root);
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (groups) {
                List<ThreadGroup> newGroups = new ArrayList<>(groups);
                newGroups.remove(newRoot);
                mirror.java.lang.ThreadGroup.groups.set(newRoot, newGroups);
                groups.clear();
                groups.add(newRoot);
                mirror.java.lang.ThreadGroup.groups.set(root, groups);
                for (ThreadGroup group : newGroups) {
                    if (group == newRoot) {
                        continue;
                    }
                    mirror.java.lang.ThreadGroup.parent.set(group, newRoot);
                }
            }
        } else {
            final ThreadGroup[] groups = ThreadGroupN.groups.get(root);
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (groups) {
                ThreadGroup[] newGroups = groups.clone();
                ThreadGroupN.groups.set(newRoot, newGroups);
                ThreadGroupN.groups.set(root, new ThreadGroup[]{newRoot});
                for (Object group : newGroups) {
                    if (group == null) {
                        continue;
                    }
                    if (group == newRoot) {
                        continue;
                    }
                    ThreadGroupN.parent.set(group, newRoot);
                }
                ThreadGroupN.ngroups.set(root, 1);
            }
        }
    }


    @SuppressLint("SdCardPath")
    private void startIORelocater(InstalledAppInfo info, boolean is64bit) {
        String packageName = info.packageName;
        int userId = VUserHandle.myUserId();
        String dataDir, de_dataDir, libPath;
        if (is64bit) {
            dataDir = VEnvironment.getDataUserPackageDirectory64(userId, packageName).getPath();
            de_dataDir = VEnvironment.getDeDataUserPackageDirectory64(userId, packageName).getPath();
            libPath = VEnvironment.getAppLibDirectory64(packageName).getAbsolutePath();
        } else {
            dataDir = VEnvironment.getDataUserPackageDirectory(userId, packageName).getPath();
            de_dataDir = VEnvironment.getDeDataUserPackageDirectory(userId, packageName).getPath();
            libPath = VEnvironment.getAppLibDirectory(packageName).getAbsolutePath();
        }
        VDeviceConfig deviceConfig = getDeviceConfig();
        if (deviceConfig.enable) {
            File wifiMacAddressFile = getDeviceConfig().getWifiFile(userId, is64bit);
            if (wifiMacAddressFile != null && wifiMacAddressFile.exists()) {
                String wifiMacAddressPath = wifiMacAddressFile.getPath();
                NativeEngine.redirectFile("/sys/class/net/wlan0/address", wifiMacAddressPath);
                NativeEngine.redirectFile("/sys/class/net/eth0/address", wifiMacAddressPath);
                NativeEngine.redirectFile("/sys/class/net/wifi/address", wifiMacAddressPath);
            }
        }
        LinuxCompat.forgeProcDriver(is64bit);
        forbidHost();
        boolean autoFixPath = userId > 0;//防止多开的应用写死路径
        String cache = new File(dataDir, "cache").getAbsolutePath();
        NativeEngine.redirectDirectory("/tmp/", cache);
        // /data/data/{packageName}/ -> /data/data/va/.../data/user/{userId}/{packageName}/
        NativeEngine.redirectDirectory("/data/data/" + packageName, dataDir);
        // /data/user/{userId}/{packageName}/ -> /data/data/va/.../data/user/{userId}/{packageName}/
        NativeEngine.redirectDirectory("/data/user/" + userId + "/" + packageName, dataDir);
        if(autoFixPath) {
            // /data/user/0/{packageName}/ -> /data/data/va/.../data/user/{userId}/{packageName}/
            NativeEngine.redirectDirectory("/data/user/0/" + packageName, dataDir);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NativeEngine.redirectDirectory("/data/user_de/" + userId + "/" + packageName, de_dataDir);
            if(autoFixPath) {
                // /data/user_de/0/{packageName}/ -> /data/data/va/.../data/user_de/{userId}/{packageName}/
                NativeEngine.redirectDirectory("/data/user_de/0/" + packageName, de_dataDir);
            }
        }
        SettingConfig.AppLibConfig appLibConfig = getConfig().getAppLibConfig(packageName);

        if (appLibConfig == SettingConfig.AppLibConfig.UseRealLib) {
            if (info.appMode != MODE_APP_USE_OUTSIDE_APK
                    || !VirtualCore.get().isOutsideInstalled(info.packageName)) {
                appLibConfig = SettingConfig.AppLibConfig.UseOwnLib;
            }
        }
        NativeEngine.whitelist(libPath);
        if (appLibConfig == SettingConfig.AppLibConfig.UseOwnLib) {
            NativeEngine.redirectDirectory("/data/data/" + packageName + "/lib/", libPath);
            NativeEngine.redirectDirectory("/data/user/" + userId + "/" + packageName + "/lib/", libPath);
            if (autoFixPath) {
                // /data/user_de/0/{packageName}/lib -> /data/data/va/.../data/user_de/{userId}/{packageName}/lib
                NativeEngine.redirectDirectory("/data/user/0/" + packageName + "/lib/", libPath);
            }
        } else {
            NativeEngine.whitelist("/data/user/" + userId + "/" + packageName + "/lib/");
        }
        // /data/data/va/.../data/user/{userId}/{packageName}/lib
        File userLibDir = VEnvironment.getUserAppLibDirectory(userId, packageName);
        //libPath=/data/data/va/.../data/app/{packageName}/lib
        //改为link，防止其他进程读取这个lib目录，报找不到文件错误
        try {
            if(userLibDir.exists() && !FileUtils.isSymlink(userLibDir)){
                FileUtils.deleteDir(userLibDir);
            }
            if(!userLibDir.exists()) {
                FileUtils.createSymlink(libPath, userLibDir.getPath());
            }
        } catch (Exception e) {
            NativeEngine.redirectDirectory(userLibDir.getPath(), libPath);
        }

        //xdja safekey adapter
        String subPathData = "/Android/data/" + info.packageName;
        String prefix = "/emulated/" + VUserHandle.realUserId() + "/";
        File[] efd = VEnvironment.getTFRoots();
        for (File f : efd) {
            if (f == null)
                continue;
            String filename = f.getAbsolutePath();
            if(filename.contains(prefix))
                continue;
            String tfRoot = VEnvironment.getTFRoot(f.getAbsolutePath()).getAbsolutePath();
            NativeEngine.redirectDirectory(tfRoot+subPathData
                    ,VEnvironment.getTFVirtualRoot(tfRoot,subPathData).getAbsolutePath());
        }

        VirtualStorageManager vsManager = VirtualStorageManager.get();
        //xdja
        vsManager.setVirtualStorage(info.packageName, userId, VEnvironment.getExternalStorageDirectory(userId).getAbsolutePath());
        String vsPath = vsManager.getVirtualStorage(info.packageName, userId);
        boolean enable = vsManager.isVirtualStorageEnable(info.packageName, userId);
        if (enable && vsPath != null) {
            File vsDirectory = new File(vsPath);
            if (vsDirectory.exists() || vsDirectory.mkdirs()) {
                HashSet<String> mountPoints = getMountPoints();
                boolean isLimbus = "com.ProjectMoon.LimbusCompany".equals(info.packageName);
                for (String mountPoint : mountPoints) {
                    //xdja
                    boolean skipRemovableProbe = isLimbus && "/mnt/sdcard/".equals(mountPoint);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !skipRemovableProbe) {
                        try {
                            if (Environment.isExternalStorageRemovable(new File(mountPoint))) {
                                if (isLimbus) {
                                    Log.i("LimbusVA", "Skip removable mount redirect mountPoint=" + mountPoint);
                                }
                                continue;
                            }
                        } catch (IllegalArgumentException e) {
                            VLog.d(TAG, e.toString());
                        } catch (SecurityException e) {
                            VLog.d(TAG, e.toString());
                        }
                    }
                    if (isLimbus) {
                        Log.i("LimbusVA", "Redirect mountPoint=" + mountPoint + " -> " + vsPath
                                + " skipRemovableProbe=" + skipRemovableProbe);
                    }
                    NativeEngine.redirectDirectory(mountPoint, vsPath);
                }

            }
        }

        //xdja 放开异常记录路径
        NativeEngine.whitelist(exceptionRecorder.getExceptionRecordPath());
        if ("com.ProjectMoon.LimbusCompany".equals(packageName)) {
            try {
                ApplicationInfo outsideInfo = VirtualCore.get().getUnHookPackageManager().getApplicationInfo(packageName, 0);
                if (!TextUtils.isEmpty(outsideInfo.nativeLibraryDir)) {
                    Os.setenv("V_LIMBUS_REAL_LIB_DIR", outsideInfo.nativeLibraryDir, true);
                    Log.i("LimbusVA", "Limbus real nativeLibraryDir=" + outsideInfo.nativeLibraryDir);
                }
            } catch (Throwable e) {
                Log.w("LimbusVA", "Unable to export Limbus real nativeLibraryDir", e);
            }
            File activeIndexPointer = new File(
                    VirtualCore.get().getContext().getFilesDir(),
                    "translation-cache/translation-index/active-index.path");
            prepareLimbusTranslationFont(VirtualCore.get().getContext());
            NativeEngine.configureLimbusTranslationRuntime(activeIndexPointer.getAbsolutePath());
            Log.i("LimbusVA", "Configured Limbus translation runtime pointer=" + activeIndexPointer);
            applyLimbusTranslationRedirects();
        }
        if (VAppPermissionManager.get().getEncryptConfig() != null) {
            NativeEngine.nativeConfigEncryptPkgName(VAppPermissionManager.get().getEncryptConfig());
        }
        NativeEngine.enableIORedirect();
        boolean isLimbusPackage = "com.ProjectMoon.LimbusCompany".equals(packageName);
        if (controllerManager.getNetworkState() && isLimbusPackage) {
            Log.i("LimbusVA", "Skip VirtualApp network strategy for Limbus");
        }
        if (controllerManager.getNetworkState() && !isLimbusPackage) {
            NativeEngine.nativeConfigNetworkState(controllerManager.getNetworkState());
            NativeEngine.nativeConfigWhiteOrBlack(controllerManager.isWhiteList());
            NativeEngine.nativeConfigNetStrategy(controllerManager.get().getIpStrategy(), 1);
            NativeEngine.nativeConfigNetStrategy(controllerManager.get().getDomainStrategy(), 2);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (controllerManager.get().getDomainStrategy() != null
                            && controllerManager.get().getDomainStrategy().length > 0) {
                        NativeEngine.nativeConfigDomainToIp();
                    }
                }
            }).start();
        }
    }

    /**
     * 将内置的 Sarasa Gothic SC Regular 字体发布到宿主私有缓存。
     *
     * <p>文件名包含字体 SHA-256 前缀，确保升级后不会继续复用旧的 Bold 缓存；长度校验
     * 可在 native 创建动态 TMP 字体前发现 APK 资源截断。</p>
     *
     * @param context 宿主应用上下文，用于读取 assets 和私有文件目录。
     */
    private void prepareLimbusTranslationFont(Context context) {
        File fontDirectory = new File(context.getFilesDir(), "translation-cache/runtime-font");
        File fontFile = new File(fontDirectory, LIMBUS_TRANSLATION_FONT_FILE);
        if (fontFile.isFile() && fontFile.length() == LIMBUS_TRANSLATION_FONT_SIZE) {
            return;
        }
        if (!fontDirectory.isDirectory() && !fontDirectory.mkdirs()) {
            Log.w("LimbusVA", "Unable to create translation font directory=" + fontDirectory);
            return;
        }
        File staging = new File(fontDirectory, fontFile.getName() + ".tmp");
        try (InputStream input = context.getAssets().open("runtime/ChineseFont.ttf");
             FileOutputStream output = new FileOutputStream(staging)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
            if (staging.length() != LIMBUS_TRANSLATION_FONT_SIZE) {
                throw new IOException("Unexpected ChineseFont.ttf size=" + staging.length());
            }
            if (fontFile.exists() && !fontFile.delete()) {
                throw new IOException("Unable to replace translation font=" + fontFile);
            }
            if (!staging.renameTo(fontFile)) {
                throw new IOException("Unable to activate translation font=" + fontFile);
            }
            Log.i("LimbusVA", "Prepared bundled translation font=" + fontFile);
        } catch (Throwable e) {
            Log.w("LimbusVA", "Unable to prepare bundled translation font", e);
        }
    }

    private void forbidHost() {
        final List<String> hostProcesses;
        if (StubManifest.PACKAGE_NAME_64BIT != null) {
            hostProcesses = Arrays.asList(StubManifest.PACKAGE_NAME,
                    StubManifest.PACKAGE_NAME_64BIT,
                    StubManifest.PACKAGE_NAME + ":x",
                    StubManifest.PACKAGE_NAME_64BIT + ":x");
        } else {
            hostProcesses = Arrays.asList(StubManifest.PACKAGE_NAME,
                    StubManifest.PACKAGE_NAME + ":x");
        }
        ActivityManager am = (ActivityManager) VirtualCore.get().getContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == Process.myPid()) {
                continue;
            }
            if (info.uid != VirtualCore.get().myUid()) {
                continue;
            }
            if(hostProcesses.contains(info.processName)){
                //is host
                NativeEngine.forbid("/proc/" + info.pid, false);//直接不允许读取host的任何proc信息
                NativeEngine.forbid("/proc/" + info.pid + "/maps", true);
                NativeEngine.forbid("/proc/" + info.pid + "/cmdline", true);
            }
        }
    }

    private void applyLimbusTranslationRedirects() {
        File translationRoot = new File(VirtualCore.get().getContext().getFilesDir(), "translation-cache");
        if (!isLimbusTranslationRedirectGateOpen(translationRoot)) {
            Log.i("LimbusVA", "Delay Limbus translation redirects until one-shot pid gate"
                    + " pid=" + Process.myPid() + " root=" + translationRoot.getAbsolutePath());
            startLimbusTranslationRedirectGate(translationRoot);
            return;
        }
        consumeLimbusTranslationRedirectGate(translationRoot);
        applyLimbusTranslationRedirectsNow(translationRoot);
    }

    private boolean isLimbusTranslationRedirectGateOpen(File translationRoot) {
        File gateFile = new File(translationRoot, LIMBUS_TRANSLATION_REDIRECT_GATE_FILE);
        if (gateFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(gateFile))) {
                if (String.valueOf(Process.myPid()).equals(reader.readLine())) {
                    return true;
                }
            } catch (Throwable e) {
                Log.w("LimbusVA", "Unable to read Limbus translation redirect gate="
                        + gateFile.getAbsolutePath(), e);
            }
        }
        return false;
    }

    private void consumeLimbusTranslationRedirectGate(File translationRoot) {
        File gateFile = new File(translationRoot, LIMBUS_TRANSLATION_REDIRECT_GATE_FILE);
        if (gateFile.isFile() && !gateFile.delete()) {
            Log.w("LimbusVA", "Unable to consume Limbus translation redirect gate="
                    + gateFile.getAbsolutePath());
        }
    }

    private void startLimbusTranslationRedirectGate(final File translationRoot) {
        if (!sLimbusTranslationRedirectGateStarted.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int attempt = 0; attempt < 1800; attempt++) {
                    if (isLimbusTranslationRedirectGateOpen(translationRoot)) {
                        Log.i("LimbusVA", "Open Limbus translation redirect gate after attempts=" + attempt
                                + " pid=" + Process.myPid());
                        consumeLimbusTranslationRedirectGate(translationRoot);
                        applyLimbusTranslationRedirectsNow(translationRoot);
                        return;
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                Log.i("LimbusVA", "Stop waiting for Limbus translation redirect gate pid="
                        + Process.myPid());
            }
        }, "LimbusZhCN-RedirectGate");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyLimbusTranslationRedirectsNow(File translationRoot) {
        File redirectFile = new File(translationRoot, "active-redirects.tsv");
        if (!redirectFile.isFile()) {
            Log.i("LimbusVA", "No active Limbus translation redirects: " + redirectFile.getAbsolutePath());
        } else {
            applyLimbusActiveTranslationRedirects(redirectFile);
        }
        Log.i("LimbusVA", "Runtime pending Limbus translation redirects are disabled; refresh merged redirects before launch");
    }

    private void applyLimbusActiveTranslationRedirects(File redirectFile) {
        int count = 0;
        boolean supportedMode = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(redirectFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#")) {
                    if (line.contains("mode=merged-json-v1")) {
                        supportedMode = true;
                    }
                    if (line.contains("mode=direct-json-v1")) {
                        Log.i("LimbusVA", "Skip direct Limbus translation redirects; merged pending redirects are required");
                        return;
                    }
                    continue;
                }
                if (line.length() == 0) {
                    continue;
                }
                if (!supportedMode) {
                    Log.w("LimbusVA", "Ignore legacy Limbus translation redirect file without supported mode: "
                            + redirectFile.getAbsolutePath());
                    return;
                }
                int split = line.indexOf('=');
                if (split <= 0 || split >= line.length() - 1) {
                    Log.w("LimbusVA", "Skip invalid Limbus translation redirect line=" + line);
                    continue;
                }
                String original = line.substring(0, split);
                String translated = line.substring(split + 1);
                File translatedFile = new File(translated);
                if (!translatedFile.isFile()) {
                    Log.w("LimbusVA", "Skip missing Limbus translation file=" + translated);
                    continue;
                }
                NativeEngine.redirectFile(original, translated);
                count++;
            }
            Log.i("LimbusVA", "Applied Limbus translation redirects count=" + count
                    + " file=" + redirectFile.getAbsolutePath());
        } catch (Throwable e) {
            Log.w("LimbusVA", "Unable to apply Limbus translation redirects", e);
        }
    }

    @SuppressLint("SdCardPath")
    private HashSet<String> getMountPoints() {
        HashSet<String> mountPoints = new HashSet<>(3);
        mountPoints.add("/mnt/sdcard/");
        mountPoints.add("/sdcard/");
        mountPoints.add("/storage/emulated/" + VUserHandle.realUserId() +"/");
        String[] points = StorageManagerCompat.getAllPoints(VirtualCore.get().getContext());
        if (points != null) {
            Collections.addAll(mountPoints, points);
        }
        return mountPoints;

    }

    private static boolean shouldBypassLimbusAppSealingApplication(ApplicationInfo appInfo, ApplicationInfo loadedApkInfo) {
        if (appInfo == null || !LIMBUS_PACKAGE_NAME.equals(appInfo.packageName)) {
            return false;
        }
        String className = appInfo.className;
        if (className == null && loadedApkInfo != null) {
            className = loadedApkInfo.className;
        }
        return "com.inka.appsealing.AppSealingApplication".equals(className);
    }

    /**
     * 在《边狱公司》的虚拟进程中关闭 Android 14 及以上的框架 CredentialManager。
     *
     * <p>系统 CredentialManager 会按 Binder 调用 UID 校验调用包。虚拟应用与汉化器宿主
     * 共用宿主 UID，因此既不能把原游戏包名提交给系统，也不能让 Google 以宿主包身份完成
     * 游戏账户登录。游戏 APK 已自带 AndroidX Credentials 的 Play Services Provider；在
     * Application 创建前让框架服务查询返回 {@code null}，AndroidX 会回退到该兼容实现，
     * 继续使用容器已有的 HiddenActivity/PendingIntent 登录桥接链路。</p>
     *
     * <p>SystemServiceRegistry 是进程内静态表，而每个虚拟槽位都是独立进程，所以该修改只
     * 影响当前承载《边狱公司》的进程，不会改变系统或其他应用的凭据服务。</p>
     *
     * @param packageName 当前正在绑定的虚拟应用包名
     */
    private static void disableFrameworkCredentialServiceForLimbus(String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || !LIMBUS_PACKAGE_NAME.equals(packageName)
                || !sLimbusFrameworkCredentialServiceDisabled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> registryClass = Class.forName("android.app.SystemServiceRegistry");
            Field fetchersField = registryClass.getDeclaredField("SYSTEM_SERVICE_FETCHERS");
            fetchersField.setAccessible(true);
            Object fetchersValue = fetchersField.get(null);
            if (!(fetchersValue instanceof Map)) {
                throw new IllegalStateException("Unexpected SystemServiceRegistry fetcher table: "
                        + fetchersValue);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> serviceFetchers = (Map<String, Object>) fetchersValue;
            Object removedFetcher = serviceFetchers.remove(FRAMEWORK_CREDENTIAL_SERVICE_NAME);
            Log.i("LimbusVA", "Disabled framework CredentialManager for Limbus; "
                    + "bundled Play Services provider will be used, removed="
                    + (removedFetcher != null));
        } catch (Throwable error) {
            // 失败时恢复状态，允许应用绑定流程中的后续调用再次尝试，而不是永久吞掉故障。
            sLimbusFrameworkCredentialServiceDisabled.set(false);
            Log.e("LimbusVA", "Unable to disable framework CredentialManager for Limbus", error);
        }
    }

    private static void startLimbusAppSealingPatchMaintainer() {
        Thread maintainer = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 30_000L;
            int checks = 0;
            int restores = 0;
            int result = 0;
            while (System.currentTimeMillis() < deadline) {
                result = NativeEngine.maintainLimbusAppSealingPatches();
                checks++;
                if (result == 2) {
                    restores++;
                } else if (result < 0) {
                    break;
                }
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Log.i("LimbusVA", "AppSealing precise patch maintainer stopped checks="
                    + checks + " restores=" + restores + " result=" + result);
        }, "limbus-appsealing-patch-maintainer");
        maintainer.setDaemon(true);
        maintainer.start();
    }

    private static boolean isLimbusAppSealingNativeLibMissing(ApplicationInfo appInfo) {
        return appInfo == null
                || appInfo.nativeLibraryDir == null
                || !new File(appInfo.nativeLibraryDir, "libcovault-appsec.so").isFile();
    }

    private static boolean isLimbusAppSealingIpServiceExperimentEnabled() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties
                    .getMethod("getBoolean", String.class, boolean.class)
                    .invoke(null, LIMBUS_APPSEALING_IP_SERVICE_PROP, false);
            return Boolean.TRUE.equals(value);
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to read " + LIMBUS_APPSEALING_IP_SERVICE_PROP, error);
            return false;
        }
    }

    private void bindLimbusAppSealingIpService(Context context) {
        if (context == null || mLimbusAppSealingIpServiceConnection != null) {
            return;
        }
        Intent intent = new Intent();
        intent.setClassName("com.ProjectMoon.LimbusCompany", "com.inka.appsealing.AppSealingIPService");
        mLimbusAppSealingIpServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i("LimbusVA", "AppSealingIPService connected name=" + name + " binder=" + service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i("LimbusVA", "AppSealingIPService disconnected name=" + name);
            }
        };
        try {
            boolean bound = context.bindService(intent, mLimbusAppSealingIpServiceConnection, Context.BIND_AUTO_CREATE);
            Log.i("LimbusVA", "Bind AppSealingIPService requested bound=" + bound + " intent=" + intent);
            if (!bound) {
                mLimbusAppSealingIpServiceConnection = null;
            }
        } catch (Throwable error) {
            mLimbusAppSealingIpServiceConnection = null;
            Log.w("LimbusVA", "Unable to bind AppSealingIPService", error);
        }
    }

    private static void applyLimbusVisibleApplicationInfo(ApplicationInfo appInfo, ApplicationInfo loadedApkInfo) {
        if (appInfo == null || !"com.ProjectMoon.LimbusCompany".equals(appInfo.packageName)) {
            return;
        }
        try {
            ApplicationInfo outsideInfo = VirtualCore.get().getUnHookPackageManager()
                    .getApplicationInfo(appInfo.packageName, 0);
            String realDataDir = "/data/user/0/" + appInfo.packageName;
            String realSourceDir = outsideInfo.sourceDir;
            String realPublicSourceDir = outsideInfo.publicSourceDir;
            String[] realSplitSourceDirs = outsideInfo.splitSourceDirs;
            String[] realSplitPublicSourceDirs = outsideInfo.splitPublicSourceDirs;
            String realNativeLibraryDir = outsideInfo.nativeLibraryDir;
            if (TextUtils.isEmpty(realNativeLibraryDir)) {
                File arm64Dir = new File(outsideInfo.sourceDir).getParentFile();
                if (arm64Dir != null) {
                    File candidate = new File(arm64Dir, "lib/arm64");
                    if (candidate.isDirectory()) {
                        realNativeLibraryDir = candidate.getAbsolutePath();
                    }
                }
            }
            Log.i("LimbusVA", "Apply visible Limbus appInfo dataDir=" + realDataDir
                    + " sourceDir=" + realSourceDir
                    + " splitSourceDirs=" + Arrays.toString(realSplitSourceDirs)
                    + " nativeLibraryDir=" + realNativeLibraryDir);
            applyLimbusPlayCoreLocalTesting(appInfo);
            applyLimbusPlayCoreLocalTesting(loadedApkInfo);
            applyVisibleApplicationInfoPaths(
                    appInfo,
                    realDataDir,
                    realSourceDir,
                    realPublicSourceDir,
                    realSplitSourceDirs,
                    realSplitPublicSourceDirs,
                    realNativeLibraryDir
            );
            applyVisibleApplicationInfoPaths(
                    loadedApkInfo,
                    realDataDir,
                    realSourceDir,
                    realPublicSourceDir,
                    realSplitSourceDirs,
                    realSplitPublicSourceDirs,
                    realNativeLibraryDir
            );
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to apply visible Limbus appInfo", error);
        }
    }

    public static void applyLimbusPlayCoreLocalTesting(ApplicationInfo appInfo) {
        if (appInfo == null || !"com.ProjectMoon.LimbusCompany".equals(appInfo.packageName)) {
            return;
        }
        if (!isLimbusPlayCoreLocalTestingEnabled()) {
            Log.i("LimbusVA", "Skip PlayCore local_testing_dir for Limbus; use real Play Store asset module service");
            if (appInfo.metaData != null) {
                appInfo.metaData.remove("local_testing_dir");
            }
            return;
        }
        if (Boolean.TRUE.equals(sApplyingLimbusPlayCoreLocalTesting.get())) {
            return;
        }
        sApplyingLimbusPlayCoreLocalTesting.set(true);
        try {
            ApplicationInfo outsideInfo = VirtualCore.get().getUnHookPackageManager()
                    .getApplicationInfo(appInfo.packageName, 0);
            File localTestingDir = new File(
                    VirtualCore.get().getContext().getFilesDir(),
                    "playcore-local-testing/" + appInfo.packageName
            );
            if (!localTestingDir.isDirectory() && !localTestingDir.mkdirs()) {
                Log.w("LimbusVA", "Unable to create PlayCore local testing dir=" + localTestingDir);
                return;
            }
            linkPlayCoreLocalTestingApk(outsideInfo.sourceDir, new File(localTestingDir, "UnityStreamingAssetsPack-master.apk"));
            if (outsideInfo.splitSourceDirs != null) {
                for (String splitSourceDir : outsideInfo.splitSourceDirs) {
                    String splitName = new File(splitSourceDir).getName();
                    if (!splitName.startsWith("split_") || !splitName.endsWith(".apk")) {
                        continue;
                    }
                    String packName = splitName.substring("split_".length(), splitName.length() - ".apk".length());
                    if (packName.startsWith("config.")) {
                        continue;
                    }
                    linkPlayCoreLocalTestingApk(splitSourceDir, new File(localTestingDir, packName + "-master.apk"));
                }
            }
            Bundle metaData = appInfo.metaData == null ? new Bundle() : new Bundle(appInfo.metaData);
            metaData.putString("local_testing_dir", localTestingDir.getAbsolutePath());
            metaData.putBoolean("firebase_analytics_collection_deactivated", true);
            metaData.putBoolean("firebase_analytics_collection_enabled", false);
            metaData.putBoolean("google_analytics_adid_collection_enabled", false);
            metaData.putBoolean("google_analytics_default_allow_analytics_storage", false);
            metaData.putBoolean("google_analytics_default_allow_ad_storage", false);
            appInfo.metaData = metaData;
            logLimbusPlayCoreLocalTestingOnce(appInfo.packageName, outsideInfo, localTestingDir);
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to apply PlayCore local testing metadata", error);
        } finally {
            sApplyingLimbusPlayCoreLocalTesting.remove();
        }
    }

    private static boolean isLimbusPlayCoreLocalTestingEnabled() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties
                    .getMethod("getBoolean", String.class, boolean.class)
                    .invoke(null, LIMBUS_PLAYCORE_LOCAL_TESTING_PROP, true);
            return Boolean.TRUE.equals(value);
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to read " + LIMBUS_PLAYCORE_LOCAL_TESTING_PROP
                    + "; default to PlayCore local testing", error);
            return true;
        }
    }

    private static void linkPlayCoreLocalTestingApk(String sourcePath, File link) {
        if (TextUtils.isEmpty(sourcePath) || link == null) {
            return;
        }
        try {
            File source = new File(sourcePath);
            if (!source.isFile()) {
                Log.w("LimbusVA", "Skip PlayCore local testing missing source=" + sourcePath);
                return;
            }
            if (link.exists() || pathExists(link)) {
                String currentTarget = null;
                try {
                    currentTarget = Os.readlink(link.getAbsolutePath());
                } catch (Throwable ignored) {
                    // Existing regular files are replaced below.
                }
                if (sourcePath.equals(currentTarget)) {
                    return;
                }
                if (!link.delete()) {
                    Log.w("LimbusVA", "Unable to delete stale PlayCore local testing link=" + link);
                    return;
                }
            }
            Os.symlink(sourcePath, link.getAbsolutePath());
            Log.i("LimbusVA", "Linked PlayCore local testing apk " + link + " -> " + sourcePath);
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to link PlayCore local testing apk=" + link + " source=" + sourcePath, error);
        }
    }

    private static void logLimbusPlayCoreLocalTestingOnce(String packageName, ApplicationInfo outsideInfo, File localTestingDir) {
        String key = localTestingDir.getAbsolutePath();
        synchronized (sLoggedLimbusPlayCoreDirs) {
            if (!sLoggedLimbusPlayCoreDirs.add(key)) {
                return;
            }
        }
        Log.i("LimbusVA", "PlayCore local testing outside sourceDir=" + outsideInfo.sourceDir
                + " splitSourceDirs=" + Arrays.toString(outsideInfo.splitSourceDirs));
        Log.i("LimbusVA", "Applied PlayCore local_testing_dir=" + localTestingDir
                + " appInfo=" + packageName
                + " files=" + describePlayCoreLocalTestingDir(localTestingDir));
    }

    private static String describePlayCoreLocalTestingDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return "[]";
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "[]";
        }
        Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(file.getName()).append("->");
            String target = null;
            try {
                target = Os.readlink(file.getAbsolutePath());
            } catch (Throwable ignored) {
                // Regular files are reported by absolute path below.
            }
            File targetFile = TextUtils.isEmpty(target) ? file : new File(target);
            builder.append(TextUtils.isEmpty(target) ? file.getAbsolutePath() : target)
                    .append(":exists=").append(targetFile.exists())
                    .append(":size=").append(targetFile.isFile() ? targetFile.length() : -1);
        }
        builder.append(']');
        return builder.toString();
    }

    private static boolean pathExists(File path) {
        if (path == null) {
            return false;
        }
        try {
            Os.lstat(path.getAbsolutePath());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyVisibleApplicationInfoPaths(
            ApplicationInfo appInfo,
            String dataDir,
            String sourceDir,
            String publicSourceDir,
            String[] splitSourceDirs,
            String[] splitPublicSourceDirs,
            String nativeLibraryDir
    ) {
        if (appInfo == null) {
            return;
        }
        appInfo.uid = VirtualCore.get().myUid();
        if (!TextUtils.isEmpty(sourceDir)) {
            appInfo.sourceDir = sourceDir;
            if (ApplicationInfoL.scanSourceDir != null) {
                ApplicationInfoL.scanSourceDir.set(appInfo, sourceDir);
            }
        }
        if (!TextUtils.isEmpty(publicSourceDir)) {
            appInfo.publicSourceDir = publicSourceDir;
            if (ApplicationInfoL.scanPublicSourceDir != null) {
                ApplicationInfoL.scanPublicSourceDir.set(appInfo, publicSourceDir);
            }
        }
        if (splitSourceDirs != null && splitSourceDirs.length > 0) {
            appInfo.splitSourceDirs = splitSourceDirs;
            if (ApplicationInfoL.splitSourceDirs != null) {
                ApplicationInfoL.splitSourceDirs.set(appInfo, splitSourceDirs);
            }
        }
        if (splitPublicSourceDirs != null && splitPublicSourceDirs.length > 0) {
            appInfo.splitPublicSourceDirs = splitPublicSourceDirs;
            if (ApplicationInfoL.splitPublicSourceDirs != null) {
                ApplicationInfoL.splitPublicSourceDirs.set(appInfo, splitPublicSourceDirs);
            }
        }
        if (!TextUtils.isEmpty(dataDir)) {
            appInfo.dataDir = dataDir;
            if (ApplicationInfoN.credentialProtectedDataDir != null) {
                ApplicationInfoN.credentialProtectedDataDir.set(appInfo, dataDir);
            }
            if (ApplicationInfoN.deviceProtectedDataDir != null) {
                ApplicationInfoN.deviceProtectedDataDir.set(appInfo, dataDir);
            }
        }
        if (!TextUtils.isEmpty(nativeLibraryDir)) {
            appInfo.nativeLibraryDir = nativeLibraryDir;
        }
    }

    private static void applyLimbusVisibleLoadedApkDirs(Object loadedApk, String packageName) {
        if (loadedApk == null || !"com.ProjectMoon.LimbusCompany".equals(packageName)) {
            return;
        }
        File dataDir = new File("/data/user/0/" + packageName);
        try {
            setLoadedApkObjectDir(loadedApk, LoadedApk.mDataDir, dataDir.getAbsolutePath());
            setLoadedApkFileDir(loadedApk, LoadedApk.mCredentialProtectedDataDirFile, dataDir);
            setLoadedApkFileDir(loadedApk, LoadedApk.mDeviceProtectedDataDirFile, dataDir);
            Log.i("LimbusVA", "Applied visible Limbus loadedApk dirs dataDir=" + dataDir);
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to apply visible Limbus loadedApk dirs", error);
        }
    }

    /**
     * 修正《边狱公司》在虚拟进程中可见的内部与外部应用目录。
     *
     * <p>内部目录保持原游戏包名，以满足 Unity 与 AppSealing 的路径校验；外部目录同样固定为
     * 原游戏包名，避免 Android 16 的 {@code ContextImpl} 缓存宿主包目录后让 Unity 把已导入资源
     * 判断为全部缺失。真正的文件访问随后由 NativeEngine 映射到宿主可读写的共享资源目录。</p>
     *
     * @param context 当前虚拟应用或组件的上下文
     * @param packageName 当前虚拟应用包名
     */
    public void applyLimbusVisibleContextDirs(Context context, String packageName) {
        if (context == null || !"com.ProjectMoon.LimbusCompany".equals(packageName)) {
            return;
        }
        Context baseContext = unwrapBaseContext(context);
        if (baseContext == null) {
            return;
        }
        File dataDir = new File("/data/user/0/" + packageName);
        File externalPackageDir = new File(
                "/storage/emulated/0/Android/data/" + packageName);
        File externalFilesDir = new File(externalPackageDir, "files");
        File externalCacheDir = new File(externalPackageDir, "cache");
        String hostPackageName = VirtualCore.get().getHostPkg();
        try {
            setContextFileDir(baseContext, ContextImpl.mDataDir, dataDir);
            setContextFileDir(baseContext, ContextImpl.mFilesDir, new File(dataDir, "files"));
            setContextFileDir(baseContext, ContextImpl.mNoBackupFilesDir, new File(dataDir, "no_backup"));
            setContextFileDir(baseContext, ContextImpl.mCacheDir, new File(dataDir, "cache"));
            setContextFileDir(baseContext, ContextImpl.mCodeCacheDir, new File(dataDir, "code_cache"));
            setContextFileDir(baseContext, ContextImpl.mPreferencesDir, new File(dataDir, "shared_prefs"));
            if (ContextImplKitkat.mExternalFilesDirs != null) {
                ContextImplKitkat.mExternalFilesDirs.set(
                        baseContext, new File[]{externalFilesDir});
            }
            if (ContextImplKitkat.mExternalCacheDirs != null) {
                ContextImplKitkat.mExternalCacheDirs.set(
                        baseContext, new File[]{externalCacheDir});
            }
            if (ContextImpl.mBasePackageName != null) {
                ContextImpl.mBasePackageName.set(baseContext, packageName);
            }
            if (ContextImplKitkat.mOpPackageName != null) {
                // The base package is intentionally virtual so package-visible checks still see
                // Limbus. AppOps calls, however, cross into the real Android system under the
                // host UID and must therefore use the host package identity. Android 13 rejects
                // the virtual package here before ConnectivityManager can report reachability.
                ContextImplKitkat.mOpPackageName.set(baseContext, hostPackageName);
            }
            applyLimbusConnectivityServiceProxy(baseContext, packageName, hostPackageName);
            Log.i("LimbusVA", "Applied visible Limbus context dirs dataDir=" + dataDir
                    + " externalFilesDir=" + externalFilesDir
                    + " externalCacheDir=" + externalCacheDir
                    + " context=" + baseContext.getClass().getName()
                    + " packageName=" + packageName
                    + " opPackageName=" + hostPackageName);
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to apply visible Limbus context dirs", error);
        }
    }

    private static Context unwrapBaseContext(Context context) {
        Context current = context;
        int depth = 0;
        while (current instanceof ContextWrapper && depth < 10) {
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == null || base == current) {
                break;
            }
            current = base;
            depth++;
        }
        return current;
    }

    private static void applyLimbusConnectivityServiceProxy(Context context, String packageName,
                                                              String hostPackageName) {
        if (context == null || !"com.ProjectMoon.LimbusCompany".equals(packageName)
                || mirror.android.net.ConnectivityManager.mService == null
                || mirror.android.net.IConnectivityManager.TYPE == null) {
            return;
        }
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            IInterface service = mirror.android.net.ConnectivityManager.mService.get(manager);
            if (service == null) {
                return;
            }
            Object proxy = Proxy.newProxyInstance(
                    mirror.android.net.IConnectivityManager.TYPE.getClassLoader(),
                    new Class<?>[]{mirror.android.net.IConnectivityManager.TYPE},
                    new LimbusConnectivityInvocationHandler(
                            service, packageName, hostPackageName, Process.myUid()));
            mirror.android.net.ConnectivityManager.mService.set(manager, (IInterface) proxy);
            Log.i("LimbusVA", "Applied Limbus ConnectivityManager context proxy service="
                    + service.getClass().getName() + " context=" + context.getClass().getName());
        } catch (Throwable error) {
            Log.w("LimbusVA", "Unable to apply Limbus ConnectivityManager context proxy", error);
        }
    }

    private static final class LimbusConnectivityInvocationHandler implements InvocationHandler {
        private final Object base;
        private final String virtualPackageName;
        private final String hostPackageName;
        private final int hostUid;

        private LimbusConnectivityInvocationHandler(Object base, String virtualPackageName,
                                                     String hostPackageName, int hostUid) {
            this.base = base;
            this.virtualPackageName = virtualPackageName;
            this.hostPackageName = hostPackageName;
            this.hostUid = hostUid;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean identityRewritten = false;
            if (args != null) {
                for (int index = 0; index < args.length; index++) {
                    Object arg = args[index];
                    if (virtualPackageName.equals(arg)) {
                        args[index] = hostPackageName;
                        identityRewritten = true;
                    } else if (arg instanceof AttributionSource) {
                        AttributionSource original = (AttributionSource) arg;
                        if (virtualPackageName.equals(original.getPackageName())
                                || original.getUid() != hostUid) {
                            args[index] = new AttributionSource.Builder(hostUid)
                                    .setPackageName(hostPackageName)
                                    .build();
                            identityRewritten = true;
                        }
                    } else if (arg != null
                            && "android.net.NetworkRequest".equals(arg.getClass().getName())) {
                        identityRewritten |= rewriteNetworkRequest(
                                arg, hostPackageName, hostUid);
                    }
                }
            }
            if (identityRewritten) {
                Log.i("LimbusVA", "Rewrite Limbus connectivity caller method="
                        + method.getName() + " hostPkg=" + hostPackageName
                        + " hostUid=" + hostUid);
            }
            try {
                return method.invoke(base, args);
            } catch (InvocationTargetException error) {
                throw error.getTargetException();
            }
        }
    }

    private static boolean rewriteNetworkRequest(Object request, String hostPackageName,
                                                 int hostUid) {
        boolean packageRewritten = setStringField(
                request, "requestorPackageName", hostPackageName)
                || setStringField(request, "mRequestorPackageName", hostPackageName);
        boolean uidRewritten = setIntField(request, "requestorUid", hostUid)
                || setIntField(request, "mRequestorUid", hostUid);
        if (packageRewritten || uidRewritten) {
            Log.i("LimbusVA", "Rewrite Limbus NetworkRequest requestorPackageName="
                    + hostPackageName + " requestorUid=" + hostUid);
        } else {
            Log.i("LimbusVA", "Unable to find Limbus NetworkRequest caller fields");
        }
        return packageRewritten || uidRewritten;
    }

    private static boolean setStringField(Object target, String fieldName, String value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setIntField(Object target, String fieldName, int value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setContextFileDir(Context context, mirror.RefObject<File> field, File value) {
        if (field != null && context != null && value != null) {
            field.set(context, value);
        }
    }

    private static void setLoadedApkFileDir(Object loadedApk, mirror.RefObject<File> field, File value) {
        if (field != null && loadedApk != null && value != null) {
            field.set(loadedApk, value);
        }
    }

    private static void setLoadedApkObjectDir(Object loadedApk, mirror.RefObject<Object> field, Object value) {
        if (field != null && loadedApk != null && value != null) {
            field.set(loadedApk, value);
        }
    }

    private static void logLimbusAppSealingEnvironment(Context context, ApplicationInfo appInfo, ApplicationInfo loadedApkInfo) {
        if (context == null || appInfo == null) {
            return;
        }
        Log.i("LimbusVA", "AppSealing env appInfo sourceDir=" + appInfo.sourceDir
                + " publicSourceDir=" + appInfo.publicSourceDir
                + " splitSourceDirs=" + Arrays.toString(appInfo.splitSourceDirs)
                + " nativeLibraryDir=" + appInfo.nativeLibraryDir
                + " dataDir=" + appInfo.dataDir
                + " className=" + appInfo.className);
        if (loadedApkInfo != null) {
            Log.i("LimbusVA", "AppSealing env loadedApkInfo sourceDir=" + loadedApkInfo.sourceDir
                    + " publicSourceDir=" + loadedApkInfo.publicSourceDir
                    + " splitSourceDirs=" + Arrays.toString(loadedApkInfo.splitSourceDirs)
                    + " nativeLibraryDir=" + loadedApkInfo.nativeLibraryDir
                    + " dataDir=" + loadedApkInfo.dataDir
                    + " className=" + loadedApkInfo.className);
        }
        try {
            PackageManager pm = context.getPackageManager();
            String installer = pm.getInstallerPackageName(appInfo.packageName);
            PackageInfo packageInfo = pm.getPackageInfo(appInfo.packageName, PackageManager.GET_SIGNATURES);
            int signatureCount = packageInfo.signatures == null ? 0 : packageInfo.signatures.length;
            Log.i("LimbusVA", "AppSealing env packageManager installer=" + installer
                    + " versionCode=" + packageInfo.versionCode
                    + " signatures=" + signatureCount
                    + " applicationInfo.sourceDir=" + packageInfo.applicationInfo.sourceDir
                    + " applicationInfo.splitSourceDirs=" + Arrays.toString(packageInfo.applicationInfo.splitSourceDirs));
        } catch (Throwable error) {
            Log.w("LimbusVA", "AppSealing env packageManager probe failed", error);
        }
        Log.i("LimbusVA", "AppSealing env files sourceExists=" + new File(appInfo.sourceDir).exists()
                + " nativeLibExists=" + (appInfo.nativeLibraryDir != null && new File(appInfo.nativeLibraryDir).exists())
                + " dataDirExists=" + (appInfo.dataDir != null && new File(appInfo.dataDir).exists()));
    }

    private Context createPackageContext(ApplicationInfo appInfo) {
        try {
            final String packageName = appInfo.packageName;
            Context hostContext = VirtualCore.get().getContext();
            Context appContext = hostContext.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            if (appContext != null) {
                if (appContext.getApplicationInfo().nativeLibraryDir == null) {
                    VLog.w(TAG, "fix nativeLibraryDir");
                    appContext.getApplicationInfo().nativeLibraryDir = appInfo.nativeLibraryDir;
                }
                if (appContext.getApplicationInfo().sharedLibraryFiles == null && appInfo.sharedLibraryFiles != null) {
                    VLog.w(TAG, "fix sharedLibraryFiles");
                    appContext.getApplicationInfo().sharedLibraryFiles = appInfo.sharedLibraryFiles;
                }
            }
            return appContext;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            VirtualRuntime.crash(e);
        }
        throw new RuntimeException();
    }

    private void installContentProviders(Context app, List<ProviderInfo> providers) {
        long origId = Binder.clearCallingIdentity();
        Object mainThread = VirtualCore.mainThread();
        try {
            for (ProviderInfo cpi : providers) {
                try {
                    ActivityThread.installProvider(mainThread, app, cpi, null);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * 确保 microG 主进程及登录 UI 进程启动时安装 SettingsProvider。
     *
     * <p>Android 16 会让 microG 主进程中已安装的 SettingsProvider 缺少可供 UI 进程复用的客户端记录，
     * 登录 UI 随后读取 check-in 数据时只能得到空 Cursor。这里使用已解析的精确组件名补齐启动列表，
     * 并在 {@code :ui} 进程安装一个共享相同虚拟数据目录的本地实例，从而避开失效的跨进程查询。</p>
     *
     * @param packageName 当前绑定的虚拟包名。
     * @param processName 当前绑定的虚拟进程名。
     * @param userId 当前虚拟用户编号。
     * @param providers 包管理器原始返回的 Provider 列表。
     * @return 已确保包含 microG SettingsProvider 的列表。
     */
    private List<ProviderInfo> includeMicrogSettingsProviderIfMissing(
            String packageName,
            String processName,
            int userId,
            List<ProviderInfo> providers) {
        boolean isMicrogMainProcess = MICROG_PACKAGE_NAME.equals(processName);
        boolean isMicrogUiProcess = MICROG_UI_PROCESS_NAME.equals(processName);
        if (!MICROG_PACKAGE_NAME.equals(packageName)
                || (!isMicrogMainProcess && !isMicrogUiProcess)) {
            return providers;
        }

        if (providers != null) {
            for (ProviderInfo provider : providers) {
                if (provider != null
                        && containsProviderAuthority(
                        provider.authority,
                        MICROG_SETTINGS_PROVIDER_AUTHORITY)) {
                    return providers;
                }
            }
        }

        ProviderInfo settingsProvider = VPackageManager.get().getProviderInfo(
                new ComponentName(
                        MICROG_PACKAGE_NAME,
                        MICROG_SETTINGS_PROVIDER_CLASS_NAME
                ),
                PackageManager.GET_META_DATA,
                userId
        );
        if (settingsProvider == null) {
            VLog.e(TAG, "microG SettingsProvider component is missing from parsed package");
            return providers;
        }

        // 复制原列表，避免修改 Binder 反序列化层可能返回的只读集合。
        List<ProviderInfo> completedProviders = providers == null
                ? new ArrayList<ProviderInfo>()
                : new ArrayList<>(providers);
        ProviderInfo processLocalProvider = new ProviderInfo(settingsProvider);
        processLocalProvider.processName = processName;
        completedProviders.add(processLocalProvider);
        VLog.w(TAG, "Added microG SettingsProvider to process install list: %s", processName);
        return completedProviders;
    }

    /**
     * 获取当前虚拟进程中指定 ContentProvider 的 Binder 客户端。
     *
     * <p>Android 16 上，Provider 已由 {@code ActivityThread} 安装后，再通过宿主
     * {@link ContentResolver} 查询虚拟 authority 仍可能返回 {@code null}。因此优先从
     * {@code ActivityThread} 的已安装 Provider 记录中读取 Binder，旧版系统查询路径继续作为回退。</p>
     *
     * @param info 需要获取的虚拟 Provider 信息。
     * @return Provider 的 Binder；Provider 尚未安装或无法获取时返回 {@code null}。
     */
    @Override
    public IBinder acquireProviderClient(ProviderInfo info) {
        if (!isAppRunning()) {
            VClient.get().bindApplication(info.packageName, info.processName);
        }
        if (VClient.get().getCurrentApplication() == null) {
            return null;
        }
        IInterface installedProvider = findInstalledProviderClient(info);
        if (installedProvider != null) {
            VLog.i(TAG, "Reuse ActivityThread provider client: %s", info.authority);
            return installedProvider.asBinder();
        }

        IInterface provider = null;
        String authority = ComponentUtils.getFirstAuthority(info);
        ContentResolver resolver = VirtualCore.get().getContext().getContentResolver();
        ContentProviderClient client = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                client = resolver.acquireUnstableContentProviderClient(authority);
            } else {
                client = resolver.acquireContentProviderClient(authority);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if (client != null) {
            provider = mirror.android.content.ContentProviderClient.mContentProvider.get(client);
            client.release();
        }
        IBinder binder = provider != null ? provider.asBinder() : null;
        if (binder != null) {
            return binder;
        }
        return null;
    }

    /**
     * 从 ActivityThread 的本进程 Provider 表中查找与目标 authority 匹配的客户端。
     *
     * <p>这里按 {@link ProviderInfo} 中的 authority 精确匹配，而不依赖 Android 16
     * 已发生行为变化的系统级 Provider 再查询。遍历范围仅限当前虚拟 Provider 进程已经安装的记录。</p>
     *
     * @param targetInfo 目标虚拟 Provider 信息。
     * @return 已安装的 Provider 客户端；没有匹配项时返回 {@code null}。
     */
    private IInterface findInstalledProviderClient(ProviderInfo targetInfo) {
        String targetAuthority = ComponentUtils.getFirstAuthority(targetInfo);
        if (TextUtils.isEmpty(targetAuthority)) {
            return null;
        }

        try {
            // Provider 表可能在安装或卸载过程中变化，读取时同步该表以避免遍历期间结构被修改。
            Map<Object, Object> providerMap = ActivityThread.mProviderMap.get(VirtualCore.mainThread());
            synchronized (providerMap) {
                for (Object clientRecord : providerMap.values()) {
                    Object holder = ActivityThread.ProviderClientRecordJB.mHolder.get(clientRecord);
                    if (holder == null) {
                        continue;
                    }

                    ProviderInfo installedInfo;
                    if (BuildCompat.isOreo()) {
                        installedInfo = ContentProviderHolderOreo.info.get(holder);
                    } else {
                        installedInfo = IActivityManager.ContentProviderHolder.info.get(holder);
                    }
                    if (installedInfo == null
                            || !containsProviderAuthority(installedInfo.authority, targetAuthority)) {
                        continue;
                    }

                    IInterface provider = ActivityThread.ProviderClientRecordJB.mProvider.get(clientRecord);
                    if (provider != null) {
                        return provider;
                    }
                }
            }
        } catch (Throwable error) {
            // 反射字段随系统版本变化时保留旧 ContentResolver 路径，不让兼容分支阻断 Provider 获取。
            VLog.w(TAG, "Unable to reuse installed provider client for %s: %s",
                    targetAuthority, error);
        }
        return null;
    }

    /**
     * 判断以分号分隔的 Provider authority 列表是否包含指定 authority。
     *
     * @param authorities Provider 清单中声明的一个或多个 authority。
     * @param expectedAuthority 需要精确匹配的 authority。
     * @return 找到目标 authority 时返回 {@code true}，否则返回 {@code false}。
     */
    private boolean containsProviderAuthority(String authorities, String expectedAuthority) {
        if (TextUtils.isEmpty(authorities)) {
            return false;
        }
        for (String authority : authorities.split(";")) {
            if (expectedAuthority.equals(authority)) {
                return true;
            }
        }
        return false;
    }

    private void fixInstalledProviders() {
        clearSettingProvider();
        //noinspection unchecked
        Map<Object, Object> clientMap = ActivityThread.mProviderMap.get(VirtualCore.mainThread());
        for (Map.Entry<Object, Object> e : clientMap.entrySet()) {
            Object clientRecord = e.getValue();
            if (BuildCompat.isOreo()) {
                IInterface provider = ActivityThread.ProviderClientRecordJB.mProvider.get(clientRecord);
                Object holder = ActivityThread.ProviderClientRecordJB.mHolder.get(clientRecord);
                if (holder == null) {
                    continue;
                }
                ProviderInfo info = ContentProviderHolderOreo.info.get(holder);
                String name = ComponentUtils.getFirstAuthority(info);
                if (name != null && !name.startsWith(StubManifest.STUB_CP_AUTHORITY)) {
                    provider = ProviderHook.createProxy(true, name, provider);
                    ActivityThread.ProviderClientRecordJB.mProvider.set(clientRecord, provider);
                    ContentProviderHolderOreo.provider.set(holder, provider);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                IInterface provider = ActivityThread.ProviderClientRecordJB.mProvider.get(clientRecord);
                Object holder = ActivityThread.ProviderClientRecordJB.mHolder.get(clientRecord);
                if (holder == null) {
                    continue;
                }
                ProviderInfo info = IActivityManager.ContentProviderHolder.info.get(holder);
                String name = ComponentUtils.getFirstAuthority(info);
                if (name != null && !name.startsWith(StubManifest.STUB_CP_AUTHORITY)) {
                    provider = ProviderHook.createProxy(true, name, provider);
                    ActivityThread.ProviderClientRecordJB.mProvider.set(clientRecord, provider);
                    IActivityManager.ContentProviderHolder.provider.set(holder, provider);
                }
            } else {
                String authority = ActivityThread.ProviderClientRecord.mName.get(clientRecord);
                IInterface provider = ActivityThread.ProviderClientRecord.mProvider.get(clientRecord);
                if (provider != null && !authority.startsWith(StubManifest.STUB_CP_AUTHORITY)) {
                    provider = ProviderHook.createProxy(true, authority, provider);
                    ActivityThread.ProviderClientRecord.mProvider.set(clientRecord, provider);
                }
            }
        }
    }

    public void clearSettingProvider() {
        Object cache;
        cache = Settings.System.sNameValueCache.get();
        if (cache != null) {
            clearContentProvider(cache);
        }
        cache = Settings.Secure.sNameValueCache.get();
        if (cache != null) {
            clearContentProvider(cache);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && Settings.Global.TYPE != null) {
            cache = Settings.Global.sNameValueCache.get();
            if (cache != null) {
                clearContentProvider(cache);
            }
        }
    }

    private static void clearContentProvider(Object cache) {
        if (BuildCompat.isOreo()) {
            Object holder = Settings.NameValueCacheOreo.mProviderHolder.get(cache);
            if (holder != null) {
                Settings.ContentProviderHolder.mContentProvider.set(holder, null);
            }
        } else {
            Settings.NameValueCache.mContentProvider.set(cache, null);
        }
    }

    @Override
    public void finishActivity(IBinder token) {
        sendMessage(FINISH_ACTIVITY, token);
    }

    @Override
    public void closeAllLongSocket() throws RemoteException {
        NativeEngine.nativeCloseAllSocket();
    }

    @Override
    public void scheduleNewIntent(String creator, IBinder token, Intent intent) {
        NewIntentData data = new NewIntentData();
        data.creator = creator;
        data.token = token;
        data.intent = intent;
        sendMessage(NEW_INTENT, data);
    }

    @Override
    public void scheduleReceiver(String processName, ComponentName component, Intent intent, PendingResultData pendingResult) {
        ReceiverData receiverData = new ReceiverData();
        receiverData.pendingResult = pendingResult;
        receiverData.intent = intent;
        receiverData.component = component;
        receiverData.processName = processName;
        receiverData.stacktrace = new Exception();
        sendMessage(RECEIVER, receiverData);
    }

    private void handleReceiver(ReceiverData data) {
        if (!isAppRunning()) {
            bindApplication(data.component.getPackageName(), data.processName);
        }
        BroadcastReceiver.PendingResult result = data.pendingResult.build();
        try {
            Context context = mInitialApplication.getBaseContext();
            Context receiverContext = ContextImpl.getReceiverRestrictedContext.call(context);
            String className = data.component.getClassName();
            ClassLoader classLoader = LoadedApk.getClassLoader.call(mBoundApplication.info);
            BroadcastReceiver receiver = (BroadcastReceiver) classLoader.loadClass(className).newInstance();
            mirror.android.content.BroadcastReceiver.setPendingResult.call(receiver, result);
            data.intent.setExtrasClassLoader(classLoader);
            if (data.intent.getComponent() == null) {
                data.intent.setComponent(data.component);
            }
            receiver.onReceive(receiverContext, data.intent);
            if (mirror.android.content.BroadcastReceiver.getPendingResult.call(receiver) != null) {
                result.finish();
            }
        } catch (Exception e) {
            data.stacktrace.printStackTrace();
            throw new RuntimeException(
                    "Unable to start receiver " + data.component
                            + ": " + e.toString(), e);
        }
        VActivityManager.get().broadcastFinish(data.pendingResult);
    }

    public ClassLoader getClassLoader() {
        return LoadedApk.getClassLoader.call(mBoundApplication.info);
    }

    private void applyLimbusThreadContextClassLoader(Object loadedApk, String reason) {
        if (loadedApk == null) {
            return;
        }
        try {
            ClassLoader classLoader = LoadedApk.getClassLoader.call(loadedApk);
            if (classLoader == null) {
                return;
            }
            Thread thread = Thread.currentThread();
            ClassLoader oldLoader = thread.getContextClassLoader();
            if (oldLoader != classLoader) {
                thread.setContextClassLoader(classLoader);
            }
            Log.i("LimbusVA", "Set Limbus thread context classloader reason=" + reason
                    + " thread=" + thread.getName()
                    + " old=" + oldLoader
                    + " new=" + classLoader
                    + " firebaseApp=" + canLoadClass(classLoader, "com.google.firebase.FirebaseApp"));
        } catch (Throwable error) {
            Log.w("LimbusVA", "Failed to set Limbus thread context classloader reason=" + reason, error);
        }
    }

    private static boolean canLoadClass(ClassLoader classLoader, String className) {
        if (classLoader == null) {
            return false;
        }
        try {
            classLoader.loadClass(className);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public Service createService(ServiceInfo info, IBinder token) {
        if (!isAppRunning()) {
            bindApplication(info.packageName, info.processName);
        }
        ClassLoader classLoader = LoadedApk.getClassLoader.call(mBoundApplication.info);
        Service service;
        try {
            service = (Service) classLoader.loadClass(info.name).newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to instantiate service " + info.name
                            + ": " + e.toString(), e);
        }
        try {
            Context context = VirtualCore.get().getContext().createPackageContext(
                    info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            ContextImpl.setOuterContext.call(context, service);
            mirror.android.app.Service.attach.call(
                    service,
                    context,
                    VirtualCore.mainThread(),
                    info.name,
                    token,
                    mInitialApplication,
                    ActivityManagerNative.getDefault.call()
            );
            ContextFixer.fixContext(service);
            service.onCreate();
            return service;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to create service " + info.name
                            + ": " + e.toString(), e);
        }
    }

    @Override
    public IBinder createProxyService(ComponentName component, IBinder binder) {
        return ProxyServiceFactory.getProxyService(getCurrentApplication(), component, binder);
    }

    @Override
    public String getDebugInfo() {
        return VirtualRuntime.getProcessName();
    }

    @Override
    public void stopService(ComponentName component) {
        ServiceManager.get().stopService(component);
    }

    private static class RootThreadGroup extends ThreadGroup {

        RootThreadGroup(ThreadGroup parent) {
            super(parent, "VA");
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {

            VLog.e(TAG, "uncaughtException !!!!!!");
            //将异常记录在本地
            exceptionRecorder.recordException(e);

            Thread.UncaughtExceptionHandler threadHandler = null;
            try {
                //当前线程的handler
                threadHandler = Reflect.on(t).get("uncaughtExceptionHandler");
            } catch (Throwable ignore) {

            }
            if(threadHandler == null){
                //应用进程的Thread.class的ClassLoader是系统classloader，所以直接用静态方法
                threadHandler = Thread.getDefaultUncaughtExceptionHandler();
            }
            Thread.UncaughtExceptionHandler handler;
            if (threadHandler != null) {
                handler = threadHandler;
            } else {
                handler = VClient.gClient.crashHandler;
            }
            boolean isMainThread = Looper.getMainLooper() == Looper.myLooper();
            //要考虑下面几个情况：
            //1.defHandler.uncaughtException里面自己杀死当前进程，如果是top activity，则会无限重启
            //2.defHandler.uncaughtException里面没有杀死当前进程
            if(isMainThread){
                //如果是activity是最上层，可能会不断重启activity，或者保留一个白色无效的activity
                //返回主界面
                VirtualCore.get().gotoBackHome();
            }
            if (handler != null) {
                handler.uncaughtException(t, e);
            }

            //如果上面方法退出进程，则下面不会执行
            //主进程异常后，是无法响应后续事件，只能杀死
            if (isMainThread) {
                System.exit(0);
            }
        }
    }

    private final class NewIntentData {
        String creator;
        IBinder token;
        Intent intent;
    }

    private final class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
    }

    private final class ReceiverData {
        PendingResultData pendingResult;
        Intent intent;
        ComponentName component;
        String processName;
        Throwable stacktrace;
    }


    @SuppressLint("HandlerLeak")
    private class H extends Handler {

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case NEW_INTENT: {
                    handleNewIntent((NewIntentData) msg.obj);
                    break;
                }
                case RECEIVER: {
                    handleReceiver((ReceiverData) msg.obj);
                    break;
                }
                case FINISH_ACTIVITY: {
                    VActivityManager.get().finishActivity((IBinder) msg.obj);
                    break;
                }
            }
        }
    }
}
