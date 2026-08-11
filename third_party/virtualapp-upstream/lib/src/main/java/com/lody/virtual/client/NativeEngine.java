package com.lody.virtual.client;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import androidx.annotation.Keep;
import android.util.Pair;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.natives.NativeMethods;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.LimbusAuthenticationCompat;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.helper.compat.SystemPropertiesCompat;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.InstalledAppInfo;

import org.lsposed.hiddenapibypass.LSPass;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * 负责 VirtualApp 的原生 IO、进程身份、隐藏 API 与 Limbus 运行时兼容桥接。
 *
 * <p>该类型集中接收原生 Hook 回调，并按当前虚拟应用与进程决定哪些系统信息应保持真实值、
 * 哪些信息应映射为虚拟身份。</p>
 */
public class NativeEngine {

    private static final String TAG = NativeEngine.class.getSimpleName();

    private static final List<DexOverride> sDexOverrides = new ArrayList<>();
    private static ClassLoader sLimbusFirebaseCppClassLoader;

    private static boolean sFlag = false;
    private static boolean sEnabled = false;
    private static boolean sBypassedP = false;

    private static final String LIB_NAME = "v++";
    private static final String LIB_NAME_64 = "v++_64";

    static {
        try {
            if (VirtualRuntime.is64bit()) {
                System.loadLibrary(LIB_NAME_64);
            } else {
                System.loadLibrary(LIB_NAME);
            }
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }


    public static void startDexOverride() {
        List<InstalledAppInfo> installedApps = VirtualCore.get().getInstalledApps(0);
        for (InstalledAppInfo info : installedApps) {
            if (info.appMode != InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK) {
                String originDexPath = getCanonicalPath(info.getApkPath());
                DexOverride override = new DexOverride(originDexPath, null, null, info.getOdexPath());
                sDexOverrides.add(override);
            }
        }
        for (String framework : StubManifest.REQUIRED_FRAMEWORK) {
            File zipFile = VEnvironment.getFrameworkFile32(framework);
            File odexFile = VEnvironment.getOptimizedFrameworkFile32(framework);
            if (zipFile.exists() && odexFile.exists()) {
                String systemFilePath = "/system/framework/" + framework + ".jar";
                sDexOverrides.add(new DexOverride(systemFilePath, zipFile.getPath(), null, odexFile.getPath()));
            }
        }
    }

    public static String getRedirectedPath(String origPath) {
        try {
            return nativeGetRedirectedPath(origPath);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
        return origPath;
    }

    public static String resverseRedirectedPath(String origPath) {
        try {
            return nativeReverseRedirectedPath(origPath);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
        return origPath;
    }

    private static final List<Pair<String, String>> REDIRECT_LISTS = new LinkedList<>();


    public static void redirectDirectory(String origPath, String newPath) {
        if (!origPath.endsWith("/")) {
            origPath = origPath + "/";
        }
        if (!newPath.endsWith("/")) {
            newPath = newPath + "/";
        }
        REDIRECT_LISTS.add(new Pair<>(origPath, newPath));
    }

    public static void redirectFile(String origPath, String newPath) {
        if (origPath.endsWith("/")) {
            origPath = origPath.substring(0, origPath.length() - 1);
        }
        if (newPath.endsWith("/")) {
            newPath = newPath.substring(0, newPath.length() - 1);
        }
        REDIRECT_LISTS.add(new Pair<>(origPath, newPath));
        if (sEnabled) {
            try {
                nativeIORedirect(origPath, newPath);
            } catch (Throwable e) {
                VLog.e(TAG, VLog.getStackTraceString(e));
            }
        }
    }

    public static void readOnlyFile(String path) {
        try {
            nativeIOReadOnly(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void readOnly(String path) {
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        try {
            nativeIOReadOnly(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void whitelistFile(String path) {
        try {
            nativeIOWhitelist(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void whitelist(String path) {
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        try {
            nativeIOWhitelist(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void forbid(String path, boolean file) {
        if (!file && !path.endsWith("/")) {
            path = path + "/";
        }
        try {
            nativeIOForbid(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void enableIORedirect() {
        if (sEnabled) {
            return;
        }
        ApplicationInfo coreAppInfo;
        try {
            coreAppInfo = VirtualCore.get().getUnHookPackageManager().getApplicationInfo(VirtualCore.getConfig().getHostPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        Collections.sort(REDIRECT_LISTS, new Comparator<Pair<String, String>>() {
            @Override
            public int compare(Pair<String, String> o1, Pair<String, String> o2) {
                String a = o1.first;
                String b = o2.first;
                return compare(b.length(), a.length());
            }

            private int compare(int x, int y) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    return Integer.compare(x, y);
                }
                return (x < y) ? -1 : ((x == y) ? 0 : 1);
            }
        });
        for (Pair<String, String> pair : REDIRECT_LISTS) {
            try {
                nativeIORedirect(pair.first, pair.second);
            } catch (Throwable e) {
                VLog.e(TAG, VLog.getStackTraceString(e));
            }
        }
        try {
            String soPath = new File(coreAppInfo.nativeLibraryDir, "lib" + LIB_NAME + ".so").getAbsolutePath();
            String soPath64 = new File(coreAppInfo.nativeLibraryDir, "lib" + LIB_NAME_64 + ".so").getAbsolutePath();
            String nativePath = VEnvironment.getNativeCacheDir(VirtualCore.get().isPluginEngine()).getPath();//当前进程可读，可写的目录
            nativeEnableIORedirect(soPath, soPath64, nativePath, Build.VERSION.SDK_INT, BuildCompat.getPreviewSDKInt());
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
        sEnabled = true;
    }

    public static void launchEngine() {
        if (sFlag) {
            return;
        }
        if (shouldSkipVmHook()) {
            VLog.w(TAG, "Skip native VM hook: pkg=%s, nativeBridge=%s, supportedAbis=%s",
                    VClient.get().getCurrentPackage(),
                    SystemPropertiesCompat.get("ro.dalvik.vm.native.bridge", ""),
                    java.util.Arrays.toString(Build.SUPPORTED_ABIS));
            if ("com.ProjectMoon.LimbusCompany".equals(VClient.get().getCurrentPackage())) {
                nativeHookLimbusProcessExit();
                nativeHookLimbusFindClass();
            }
            sFlag = true;
            return;
        }
        Object[] methods = {NativeMethods.gOpenDexFileNative,
                NativeMethods.gCameraNativeSetup,
                NativeMethods.gAudioRecordNativeCheckPermission,
                NativeMethods.gMediaRecorderNativeSetup,
                NativeMethods.gAudioRecordNativeSetup,
                NativeMethods.gCameraStartPreview,
                NativeMethods.gCameraNativeTakePicture,
                NativeMethods.gAudioRecordStart,
                NativeMethods.gMediaRecordPrepare};
        try {
            nativeLaunchEngine(methods, VirtualCore.get().getHostPkg(), VirtualRuntime.isArt(), Build.VERSION.SDK_INT, NativeMethods.gCameraMethodType, NativeMethods.gAudioRecordMethodType);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
        sFlag = true;
    }

    public static void scanLimbusNativeSyscalls(String nativeLibraryDir) {
        try {
            nativeScanLimbusNativeSyscalls(nativeLibraryDir);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static int maintainLimbusAppSealingPatches() {
        try {
            return nativeMaintainLimbusAppSealingPatches();
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
            return -1;
        }
    }

    public static void configureLimbusTranslationRuntime(String activeIndexPointer) {
        try {
            nativeConfigureLimbusTranslationRuntime(activeIndexPointer);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    @Keep
    public static Class<?> findLimbusClassFromNative(String slashName) {
        if (slashName == null || !"com.ProjectMoon.LimbusCompany".equals(VClient.get().getCurrentPackage())) {
            return null;
        }
        String className = slashName.replace('/', '.');
        try {
            ClassLoader classLoader = VClient.get().getClassLoader();
            Class<?> clazz = loadLimbusClass(className, classLoader);
            VLog.i(TAG, "Limbus native FindClass fallback loaded %s with %s", className, clazz.getClassLoader());
            return clazz;
        } catch (Throwable e) {
            VLog.w(TAG, "Limbus native FindClass fallback failed for %s: %s", className, e.toString());
            return null;
        }
    }

    private static Class<?> loadLimbusClass(String className, ClassLoader gameClassLoader) throws ClassNotFoundException {
        try {
            return gameClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            if ("com.google.android.gms.common.GoogleApiAvailability".equals(className)
                    || "com.google.firebase.app.internal.cpp.GoogleApiAvailabilityHelper".equals(className)) {
                return NativeEngine.class.getClassLoader().loadClass(className);
            }
            if (!className.startsWith("com.google.firebase.")) {
                throw e;
            }
            return getLimbusFirebaseCppClassLoader(gameClassLoader).loadClass(className);
        }
    }

    private static synchronized ClassLoader getLimbusFirebaseCppClassLoader(ClassLoader gameClassLoader) {
        if (sLimbusFirebaseCppClassLoader != null) {
            return sLimbusFirebaseCppClassLoader;
        }
        File optimizedDir = new File(VirtualCore.get().getContext().getCodeCacheDir(), "limbus-firebase-cpp");
        //noinspection ResultOfMethodCallIgnored
        optimizedDir.mkdirs();
        String dexPath = buildExistingLimbusFirebaseDexPath();
        sLimbusFirebaseCppClassLoader = new DexClassLoader(
                dexPath,
                optimizedDir.getAbsolutePath(),
                null,
                gameClassLoader);
        VLog.i(TAG, "Created Limbus Firebase C++ DexClassLoader dexPath=%s parent=%s opt=%s",
                dexPath, gameClassLoader, optimizedDir.getAbsolutePath());
        return sLimbusFirebaseCppClassLoader;
    }

    private static String buildExistingLimbusFirebaseDexPath() {
        File cacheDir = new File(
                VEnvironment.getDataUserPackageDirectory(VUserHandle.myUserId(), "com.ProjectMoon.LimbusCompany"),
                "cache");
        String[] jarNames = {
                "app_resources_lib.jar",
                "auth_resources_lib.jar",
                "database_resources_lib.jar",
                "dynamic_links_resources_lib.jar",
                "functions_resources_lib.jar",
                "installations_resources_lib.jar",
                "messaging_resources_lib.jar",
                "remote_config_resources_lib.jar",
                "storage_resources_lib.jar"
        };
        StringBuilder dexPath = new StringBuilder();
        for (String jarName : jarNames) {
            File jarFile = new File(cacheDir, jarName);
            if (!jarFile.exists()) {
                continue;
            }
            if (dexPath.length() > 0) {
                dexPath.append(File.pathSeparator);
            }
            dexPath.append(jarFile.getAbsolutePath());
        }
        File hostApk = new File(VirtualCore.get().getContext().getApplicationInfo().sourceDir);
        if (hostApk.isFile()) {
            if (dexPath.length() > 0) {
                dexPath.append(File.pathSeparator);
            }
            dexPath.append(hostApk.getAbsolutePath());
        }
        if (dexPath.length() == 0) {
            dexPath.append(new File(cacheDir, "auth_resources_lib.jar").getAbsolutePath());
        }
        return dexPath.toString();
    }

    private static boolean shouldSkipVmHook() {
        String nativeBridge = SystemPropertiesCompat.get("ro.dalvik.vm.native.bridge", "");
        return LimbusAuthenticationCompat.shouldSkipLegacyVmHook(
                VClient.get().getCurrentPackage(),
                nativeBridge);
    }

    /**
     * 为 VirtualApp 运行时使用的 Android 隐藏成员添加访问豁免。
     *
     * <p>Android 16 会过滤通过 Java 元反射查找 {@code VMRuntime} 隐藏方法的结果，
     * 因此 Java 路径失败后还需要尝试 JNI 查找。两条路径使用完全相同的前缀，
     * 避免不同系统版本得到不一致的容器能力。</p>
     */
    public static void bypassHiddenAPI() {
        if (BuildCompat.isPie()) {
            String[] exemptions = createHiddenApiExemptions();
            boolean bypassed = false;

            /*
             * Android 16 已经会过滤原来的查找结果，因此先使用仍在维护的兼容入口。
             * 这里只在新系统优先使用它，避免改变旧系统上已经验证稳定的启动顺序。
             */
            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    bypassed = LSPass.setHiddenApiExemptions(exemptions);
                } catch (Throwable e) {
                    VLog.w(TAG, "Android 16 hidden-API compatibility failed: %s",
                            e.toString());
                }
            }

            // 旧系统继续使用原有办法；新办法失败时也保留这条退路，便于兼容不同厂商系统。
            if (!bypassed) {
                try {
                    Method forNameMethod = Class.class.getDeclaredMethod("forName", String.class);
                    Class<?> clazz = (Class<?>) forNameMethod.invoke(null, "dalvik.system.VMRuntime");
                    Method getMethodMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
                    Method getRuntime = (Method) getMethodMethod.invoke(clazz, "getRuntime", new Class[0]);
                    Method setHiddenApiExemptions = (Method) getMethodMethod.invoke(clazz, "setHiddenApiExemptions", new Class[]{String[].class});
                    Object runtime = getRuntime.invoke(null);
                    setHiddenApiExemptions.invoke(runtime, new Object[]{exemptions});
                    bypassed = true;
                } catch (Throwable e) {
                    VLog.w(TAG, "Legacy hidden-API exemption unavailable: %s", e.toString());
                }
            }
            if (bypassed) {
                VLog.i(TAG, "Hidden-API exemptions installed for SDK %d", Build.VERSION.SDK_INT);
            } else {
                VLog.e(TAG, "Unable to install hidden-API exemptions for SDK %d",
                        Build.VERSION.SDK_INT);
            }
        }
    }

    /**
     * 在当前系统首次使用 VirtualApp 前关闭隐藏 API 拒绝策略。
     *
     * <p>该方法按进程执行一次；重复修改 ART 策略或重复安装 native hook 都可能改变
     * AppSealing 的启动时序，因此成功或失败后都由调用方继续沿用同一进程结论。</p>
     */
    public static void bypassHiddenAPIEnforcementPolicyIfNeeded() {
        if (sBypassedP) {
            return;
        }
        if (BuildCompat.isQ()) {
            bypassHiddenAPI();
        } else if (BuildCompat.isPie()) {
            try {
                nativeBypassHiddenAPIEnforcementPolicy(Build.VERSION.SDK_INT, BuildCompat.getPreviewSDKInt());
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        sBypassedP = true;
    }

    /**
     * 生成 Java 与 JNI 两条隐藏 API 豁免路径共享的签名前缀。
     *
     * @return 当前 ROM 所需的类型签名前缀
     */
    private static String[] createHiddenApiExemptions() {
        if (BuildCompat.isEMUI()) {
            return new String[]{
                    "Landroid/",
                    "Lcom/android/",
                    "Ljava/lang/",
                    "Ldalvik/system/",
                    "Llibcore/io/",
                    "Lhuawei/"
            };
        }
        return new String[]{
                "Landroid/",
                "Lcom/android/",
                "Ljava/lang/",
                "Ldalvik/system/",
                "Llibcore/io/"
        };
    }

    public static boolean onKillProcess(int pid, int signal) {
        VLog.e(TAG, "killProcess: pid = %d, signal = %d.", pid, signal);
        if (pid == Process.myPid()) {
            VLog.e(TAG, VLog.getStackTraceString(new Throwable()));
        }
        return true;
    }

    public static int onGetCallingUid(int originUid) {
        if (!VClient.get().isAppRunning()) {
            return originUid;
        }
        int callingPid = Binder.getCallingPid();
        if (callingPid == Process.myPid()) {
            return VClient.get().getVUid();
        }
        if (callingPid == VClient.get().getSystemPid()) {
            return VActivityManager.get().getCallingUid();
        }
        int appId = VUserHandle.getAppId(originUid);
        if(appId > 0 && appId < Process.FIRST_APPLICATION_UID){
            //说明originUid要么系统应用，要么va内部应用,
            return originUid;
        }
        return VActivityManager.get().getUidByPid(callingPid);
    }

    private static DexOverride findDexOverride(String originDexPath) {
        for (DexOverride dexOverride : sDexOverrides) {
            if (dexOverride.originDexPath.equals(originDexPath)) {
                return dexOverride;
            }
        }
        return null;
    }

    public static void onOpenDexFileNative(String[] params) {
        String dexPath = params[0];
        String odexPath = params[1];
        if (dexPath != null) {
            String dexCanonicalPath = getCanonicalPath(dexPath);
            DexOverride override = findDexOverride(dexCanonicalPath);
            if (override != null) {
                if (override.newDexPath != null) {
                    params[0] = override.newDexPath;
                }
                odexPath = override.newDexPath;
                if (override.originOdexPath != null) {
                    String odexCanonicalPath = getCanonicalPath(odexPath);
                    if (odexCanonicalPath.equals(override.originOdexPath)) {
                        params[1] = override.newOdexPath;
                    }
                } else {
                    params[1] = override.newOdexPath;
                }
            }
        }
        VLog.i(TAG, "OpenDexFileNative(\"%s\", \"%s\")", dexPath, odexPath);
    }

    private static final String getCanonicalPath(String path) {
        File file = new File(path);
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file.getAbsolutePath();
    }

    private static native void nativeLaunchEngine(Object[] method, String hostPackageName, boolean isArt, int apiLevel, int cameraMethodType, int audioRecordMethodType);

    private static native void nativeHookLimbusProcessExit();

    private static native void nativeHookLimbusFindClass();

    private static native void nativeScanLimbusNativeSyscalls(String nativeLibraryDir);

    private static native int nativeMaintainLimbusAppSealingPatches();

    private static native void nativeConfigureLimbusTranslationRuntime(String activeIndexPointer);

    private static native void nativeMark();

    private static native String nativeReverseRedirectedPath(String redirectedPath);

    private static native String nativeGetRedirectedPath(String origPath);

    private static native void nativeIORedirect(String origPath, String newPath);

    private static native void nativeIOWhitelist(String path);

    private static native void nativeIOForbid(String path);

    public static native boolean nativeCloseAllSocket();

    public static native void nativeChangeDecryptState(boolean state);

    public static native boolean nativeConfigEncryptPkgName(String[] name);

    public static native void nativeAddEncryptPkgName(String name);

    public static native void nativeDelEncryptPkgName(String name);

    public static native boolean nativeGetDecryptState();

    private static native void nativeIOReadOnly(String path);

    private static native void nativeEnableIORedirect(String soPath, String soPath64, String cachePath, int apiLevel, int previewApiLevel);

    private static native void nativeBypassHiddenAPIEnforcementPolicy(int apiLevel, int previewApiLevel);

    public static native boolean nativeConfigNetStrategy(String[] netStrategy,int type);

    public static native void nativeConfigNetworkState(boolean netonOroff);

    public static native void nativeConfigWhiteOrBlack(boolean isWhiteOrBlack);

    public static native void nativeConfigDomainToIp();


    /**
     * 将底层 {@code getuid()} 结果映射为当前虚拟应用需要观察到的 UID。
     *
     * <p>Limbus 本体需要真实 UID 通过加固校验。microG 的登录 UI 进程同样必须看到真实宿主 UID，
     * 因为 Chromium WebView 会在构造时使用 {@code Process.myUid()} 向系统检查 INTERNET 权限；
     * 若返回不存在于系统包管理器中的虚拟 UID，WebView 会永久禁止网络加载并报 ERR_CACHE_MISS。
     * 其他虚拟进程继续返回虚拟 UID，保持原有隔离语义。</p>
     *
     * @param uid 操作系统返回的真实宿主 UID
     * @return 当前进程应观察到的真实或虚拟 UID
     */
    @Keep
    public static int onGetUid(int uid) {
        if (!VClient.get().isAppRunning()) {
            return uid;
        }
        String currentPackage = VClient.get().getCurrentPackage();
        String currentProcess = VirtualRuntime.getProcessName();
        boolean isLimbusProcess = "com.ProjectMoon.LimbusCompany".equals(currentPackage);
        boolean isMicrogLoginUiProcess = "com.google.android.gms".equals(currentPackage)
                && "com.google.android.gms:ui".equals(currentProcess);
        if (isLimbusProcess || isMicrogLoginUiProcess) {
            return uid;
        }
        return VClient.get().getBaseVUid();
    }

    @Keep
    public static void onSystemExit(int code) {
        if (!VClient.get().isAppRunning()) {
            return;
        }
        VLog.w("V++", "System.exit:" + code);
        if (VClient.get().countOfActivity > 0) {
            VirtualCore.get().gotoBackHome();
        }
    }

    @Keep
    public static boolean onSendSignal(int pid, int sig, int quiet) {
        if (!VClient.get().isAppRunning()) {
            return false;
        }
        VLog.w("V++", "onSendSignal:" + pid + ", " + sig + ", quiet=" + quiet
                + ", pkg=" + VClient.get().getCurrentPackage());
        if (shouldBlockLimbusSelfSigalrm(pid, sig)) {
            VLog.w("V++", "block Limbus self SIGALRM from android.os.Process");
            return true;
        }
        //返回主界面
        if (pid == android.os.Process.myPid() && sig == android.os.Process.SIGNAL_KILL) {
            if (VClient.get().countOfActivity > 0) {//有前台activity才返回桌面。
                //异常闪退的在VClient有处理
                VirtualCore.get().gotoBackHome();
            }
        }
        return false;
    }

    private static boolean shouldBlockLimbusSelfSigalrm(int pid, int sig) {
        String currentPackage = VClient.get().getCurrentPackage();
        return pid == android.os.Process.myPid()
                && sig == 14
                && "com.ProjectMoon.LimbusCompany".equals(currentPackage);
    }

}
