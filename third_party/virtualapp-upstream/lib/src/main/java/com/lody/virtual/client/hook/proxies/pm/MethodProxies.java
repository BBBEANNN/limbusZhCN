package com.lody.virtual.client.hook.proxies.pm;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.IInterface;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.lody.virtual.GmsSupport;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;
import com.lody.virtual.client.fixer.ComponentFixer;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.helper.compat.ParceledListSliceCompat;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.server.IPackageInstaller;
import com.lody.virtual.server.pm.installer.SessionInfo;
import com.lody.virtual.server.pm.installer.SessionParams;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import mirror.android.content.pm.ParceledListSlice;

/**
 * @author Lody
 */
@SuppressWarnings("unused")
class MethodProxies {
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";

    private static boolean shouldHidePackageFromCurrentApp(String packageName) {
        return LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                && VClient.get().shouldHideHostPackageForLimbus()
                && MethodProxy.getHostPkg().equals(packageName);
    }

    private static boolean isVisiblePackageForCurrentApp(ApplicationInfo info) {
        return info != null
                && !shouldHidePackageFromCurrentApp(info.packageName)
                && (shouldUseHostGooglePackageInfo(info.packageName) || MethodProxy.isVisiblePackage(info));
    }

    private static boolean isVisiblePackageForCurrentApp(String packageName) {
        return !shouldHidePackageFromCurrentApp(packageName) && MethodProxy.isVisiblePackage(packageName);
    }

    private static final ThreadLocal<Boolean> sResolvingHostGooglePackage = new ThreadLocal<>();

    private static boolean shouldExposeHostGooglePackageToLimbus(String packageName) {
        return false;
    }

    private static boolean shouldUseHostGooglePackageInfo(String packageName) {
        if (Boolean.TRUE.equals(sResolvingHostGooglePackage.get())) {
            return false;
        }
        String appPackage = MethodProxy.getAppPkg();
        return false;
    }

    private static boolean shouldIsolateHostPackagesForLimbus() {
        return LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg());
    }

    private static PackageInfo getHostPackageInfoForLimbus(Object who, Method method, Object[] args, String packageName, int flags) throws Throwable {
        if (!shouldUseHostGooglePackageInfo(packageName)) {
            return null;
        }
        sResolvingHostGooglePackage.set(true);
        try {
            PackageInfo info = (PackageInfo) method.invoke(who, sanitizeLimbusHostPackageArgs(args));
            Log.i("LimbusVA", "Return host Google PackageInfo to Limbus: " + packageName
                    + " flags=" + flags
                    + " versionCode=" + (info == null ? null : info.versionCode)
                    + " signatures=" + (info == null || info.signatures == null ? 0 : info.signatures.length));
            return info;
        } finally {
            sResolvingHostGooglePackage.remove();
        }
    }

    private static ApplicationInfo getHostApplicationInfoForLimbus(Object who, Method method, Object[] args, String packageName, int flags) throws Throwable {
        if (!shouldUseHostGooglePackageInfo(packageName)) {
            return null;
        }
        sResolvingHostGooglePackage.set(true);
        try {
            ApplicationInfo info = (ApplicationInfo) method.invoke(who, sanitizeLimbusHostPackageArgs(args));
            Log.i("LimbusVA", "Return host Google ApplicationInfo to Limbus: " + packageName
                    + " flags=" + flags
                    + " sourceDir=" + (info == null ? null : info.sourceDir));
            return info;
        } finally {
            sResolvingHostGooglePackage.remove();
        }
    }

    private static Object[] sanitizeLimbusHostPackageArgs(Object[] args) {
        if (args == null) {
            return null;
        }
        Object[] copy = args.clone();
        for (int i = 0; i < copy.length; i++) {
            if (LIMBUS_PACKAGE.equals(copy[i])) {
                copy[i] = MethodProxy.getHostPkg();
            } else if (i > 0
                    && GmsSupport.GMS_PKG.equals(MethodProxy.getAppPkg())
                    && GmsSupport.GMS_PKG.equals(copy[i])) {
                // Keep args[0] as the queried GMS package, but make binder
                // attribution strings agree with the real host UID.
                copy[i] = MethodProxy.getHostPkg();
            }
        }
        return copy;
    }

    private static int readPackageFlags(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            return (int) ((Long) value).longValue();
        }
        if (value != null) {
            try {
                Object flags = value.getClass().getMethod("getValue").invoke(value);
                if (flags instanceof Number) {
                    return (int) ((Number) flags).longValue();
                }
            } catch (Throwable ignored) {
                // Android 13 may pass flag wrapper types; fall back to no flags if unknown.
            }
        }
        return 0;
    }

    private static Object writePackageFlags(Object originalValue, int flags) {
        if (originalValue instanceof Long) {
            return (long) flags;
        }
        return flags;
    }

    private static void fixLimbusOwnApplicationInfo(ApplicationInfo info) {
        if (info != null
                && LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                && LIMBUS_PACKAGE.equals(info.packageName)) {
            info.uid = VirtualCore.get().myUid();
            VClient.applyLimbusPlayCoreLocalTesting(info);
        }
    }

    private static void fixLimbusOwnPackageInfo(PackageInfo info) {
        if (info != null) {
            fixLimbusOwnApplicationInfo(info.applicationInfo);
        }
    }

    static class FreeStorage extends MethodProxy {

        @Override
        public String getMethodName() {
            return "freeStorage";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IntentSender sender = ArrayUtils.getFirst(args, IntentSender.class);
            if (sender != null) {
                sender.sendIntent(getHostContext(), 0, null, null, null);
            }
            return 0;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class FreeStorageAndNotify extends MethodProxy {

        @Override
        public String getMethodName() {
            return "freeStorageAndNotify";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IPackageDataObserver observer = ArrayUtils.getFirst(args, IPackageDataObserver.class);
            if (observer != null) {
                observer.onRemoveCompleted(getAppPkg(), true);
            }
            return 0;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class CheckPackageStartable extends MethodProxy {

        @Override
        public String getMethodName() {
            return "checkPackageStartable";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            if (isAppPkg(pkg)) {
                return 0;
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class GetUidForSharedUser extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getUidForSharedUser";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String sharedUserName = (String) args[0];
            return VirtualCore.get().getUidForSharedUser(sharedUserName);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class CanForwardTo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "canForwardTo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int sourceUserId = (int) args[2];
            int targetUserId = (int) args[3];
            return sourceUserId == targetUserId;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class IsPackageAvailable extends MethodProxy {

        @Override
        public String getMethodName() {
            return "isPackageAvailable";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkgName = (String) args[0];
            if (isAppPkg(pkgName)) {
                return true;
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetInstallerPackageName extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getInstallerPackageName";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return "com.android.vending";
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class GetPreferredActivities extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPreferredActivities";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            return method.invoke(who, args);
        }
    }


    static class GetComponentEnabledSetting extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getComponentEnabledSetting";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName component = (ComponentName) args[0];
            return VPackageManager.get().getComponentEnabledSetting(component, getAppUserId());
        }
    }


    static class RemovePackageFromPreferred extends MethodProxy {

        @Override
        public String getMethodName() {
            return "removePackageFromPreferred";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    /**
     * @author Lody
     * <p>
     * public ActivityInfo getServiceInfo(ComponentName className, int
     * flags, int userId)
     */
    static class GetServiceInfo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getServiceInfo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = readPackageFlags(args[1]);
            int userId = VUserHandle.myUserId();
            ServiceInfo info = VPackageManager.get().getServiceInfo(componentName, flags, userId);
            if (info != null) {
                return info;
            }
            if (shouldIsolateHostPackagesForLimbus()) {
                return null;
            }
            info = (ServiceInfo) method.invoke(who, args);
            if (info == null || !isVisiblePackageForCurrentApp(info.applicationInfo)) {
                return null;
            }
            ComponentFixer.fixOutsideComponentInfo(info);
            return info;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPackageUid extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPackageUid";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkgName = (String) args[0];
            if (LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg()) && LIMBUS_PACKAGE.equals(pkgName)) {
                return VirtualCore.get().myUid();
            }
            if (pkgName.equals(getHostPkg()) && !shouldHidePackageFromCurrentApp(pkgName)) {
                return method.invoke(who, args);
            }
            if (isAppPkg(pkgName)) {
                int uid = VPackageManager.get().getPackageUid(pkgName, 0);
                return VUserHandle.getAppId(uid);
            }
            if (isVisiblePackageForCurrentApp(pkgName)) {
                return method.invoke(who, args);
            }
            return -1;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

    }

    /**
     * @author Lody
     * <p>
     * <p>
     * public ActivityInfo getActivityInfo(ComponentName className, int
     * flags, int userId)
     */
    static class GetActivityInfo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getActivityInfo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            if (getHostPkg().equals(componentName.getPackageName())
                    && !shouldHidePackageFromCurrentApp(componentName.getPackageName())) {
                return method.invoke(who, args);
            }
            int userId = VUserHandle.myUserId();
            int flags = readPackageFlags(args[1]);
            ActivityInfo info = VPackageManager.get().getActivityInfo(componentName, flags, userId);
            if (info == null) {
                if (shouldIsolateHostPackagesForLimbus()) {
                    return null;
                }
                info = (ActivityInfo) method.invoke(who, args);
                if (info == null || !isVisiblePackageForCurrentApp(info.applicationInfo)) {
                    return null;
                }
                ComponentFixer.fixOutsideComponentInfo(info);
            }
            return info;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class GetPackageUidEtc extends GetPackageUid {
        @Override
        public String getMethodName() {
            return super.getMethodName() + "Etc";
        }
    }

    static class GetPackageInstaller extends MethodProxy {

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

        @Override
        public String getMethodName() {
            return "getPackageInstaller";
        }


        @Override
        public Object call(final Object who, Method method, Object... args) throws Throwable {
            final IInterface installer = (IInterface) method.invoke(who, args);
            final IPackageInstaller vInstaller = VPackageManager.get().getPackageInstaller();
            return Proxy.newProxyInstance(installer.getClass().getClassLoader(), installer.getClass().getInterfaces(),
                    new InvocationHandler() {

                        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                        private Object createSession(Object proxy, Method method, Object[] args) throws RemoteException {
                            SessionParams params = SessionParams.create((PackageInstaller.SessionParams) args[0]);
                            String installerPackageName = (String) args[1];
                            return vInstaller.createSession(params, installerPackageName, VUserHandle.myUserId());
                        }

                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            switch (method.getName()) {
                                case "createSession": {
                                    return createSession(proxy, method, args);
                                }
                                case "updateSessionAppIcon": {
                                    int sessionId = (int) args[0];
                                    Bitmap appIcon = (Bitmap) args[1];
                                    vInstaller.updateSessionAppIcon(sessionId, appIcon);
                                    return 0;
                                }
                                case "updateSessionAppLabel": {
                                    int sessionId = (int) args[0];
                                    String appLabel = (String) args[1];
                                    vInstaller.updateSessionAppLabel(sessionId, appLabel);
                                    return 0;
                                }
                                case "abandonSession": {
                                    vInstaller.abandonSession((Integer) args[0]);
                                    return 0;
                                }
                                case "openSession": {
                                    return vInstaller.openSession((Integer) args[0]);
                                }
                                case "getSessionInfo": {
                                    SessionInfo info = vInstaller.getSessionInfo((Integer) args[0]);
                                    if (info != null) {
                                        return info.alloc();
                                    }
                                    return null;
                                }
                                case "getAllSessions": {
                                    int userId = (int) args[0];
                                    List<SessionInfo> infos = vInstaller.getAllSessions(userId).getList();
                                    List<PackageInstaller.SessionInfo> sysInfos = new ArrayList<>(infos.size());
                                    for (SessionInfo info : infos) {
                                        sysInfos.add(info.alloc());
                                    }
                                    return ParceledListSliceCompat.create(sysInfos);
                                }
                                case "getMySessions": {
                                    String installerPackageName = (String) args[0];
                                    int userId = (int) args[1];
                                    List<SessionInfo> infos = vInstaller.getMySessions(installerPackageName, userId).getList();
                                    List<PackageInstaller.SessionInfo> sysInfos = new ArrayList<>(infos.size());
                                    for (SessionInfo info : infos) {
                                        sysInfos.add(info.alloc());
                                    }
                                    return ParceledListSliceCompat.create(sysInfos);
                                }
                                case "registerCallback": {
                                    IPackageInstallerCallback callback = (IPackageInstallerCallback) args[0];
                                    vInstaller.registerCallback(callback, VUserHandle.myUserId());
                                    return 0;
                                }
                                case "unregisterCallback": {
                                    IPackageInstallerCallback callback = (IPackageInstallerCallback) args[0];
                                    vInstaller.unregisterCallback(callback);
                                    return 0;
                                }
                                case "setPermissionsResult": {
                                    int sessionId = (int) args[0];
                                    boolean accepted = (boolean) args[1];
                                    vInstaller.setPermissionsResult(sessionId, accepted);
                                    return 0;
                                }
                                case "toString": {
                                    return "VPackageInstaller";
                                }
                            }
                            throw new RuntimeException("Not support PackageInstaller method : " + method.getName());
                        }
                    });
        }
    }

    static class GetPackageGids extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPackageGids";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

    }


    static class RevokeRuntimePermission extends MethodProxy {

        @Override
        public String getMethodName() {
            return "revokeRuntimePermission";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class ClearPackagePreferredActivities extends MethodProxy {

        @Override
        public String getMethodName() {
            return "clearPackagePreferredActivities";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }


    static class ResolveContentProvider extends MethodProxy {

        @Override
        public String getMethodName() {
            return "resolveContentProvider";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String name = (String) args[0];
            int flags = readPackageFlags(args[1]);
            int userId = VUserHandle.myUserId();
            ProviderInfo info = VPackageManager.get().resolveContentProvider(name, flags, userId);
            if (info == null) {
                if (shouldIsolateHostPackagesForLimbus()) {
                    return null;
                }
                info = (ProviderInfo) method.invoke(who, args);
                if (info != null && isVisiblePackageForCurrentApp(info.applicationInfo)) {
                    return info;
                }
            }
            return info;
        }
    }


    @SuppressWarnings("unchecked")
    static class QueryIntentServices extends MethodProxy {

        @Override
        public String getMethodName() {
            return "queryIntentServices";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            int userId = VUserHandle.myUserId();
            List<ResolveInfo> appResult = VPackageManager.get().queryIntentServices((Intent) args[0],
                    (String) args[1], readPackageFlags(args[2]), userId);
            if (shouldIsolateHostPackagesForLimbus()) {
                if (slice) {
                    return ParceledListSliceCompat.create(appResult);
                }
                return appResult;
            }
            Object _hostResult = method.invoke(who, args);
            if (_hostResult != null) {
                List<ResolveInfo> hostResult = slice ? (List<ResolveInfo>) ParceledListSlice.getList.call(_hostResult)
                        : (List) _hostResult;
                if (hostResult != null) {
                    Iterator<ResolveInfo> iterator = hostResult.iterator();
                    while (iterator.hasNext()) {
                        ResolveInfo info = iterator.next();
                        if (info == null || info.serviceInfo == null || !isVisiblePackageForCurrentApp(info.serviceInfo.applicationInfo)) {
                            iterator.remove();
                        }
                    }
                    appResult.addAll(hostResult);
                }
            }
            if (slice) {
                return ParceledListSliceCompat.create(appResult);
            }
            return appResult;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPermissions extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPermissions";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    static class IsPackageForzen extends MethodProxy {

        @Override
        public String getMethodName() {
            return "isPackageForzen";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return false;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPackageGidsEtc extends GetPackageGids {

        @Override
        public String getMethodName() {
            return super.getMethodName() + "Etc";
        }

    }

    @SuppressWarnings("unchecked")
    static class QueryIntentActivities extends MethodProxy {

        @Override
        public String getMethodName() {
            return "queryIntentActivities";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            int userId = VUserHandle.myUserId();
            List<ResolveInfo> appResult = VPackageManager.get().queryIntentActivities((Intent) args[0],
                    (String) args[1], readPackageFlags(args[2]), userId);

            //xdja
            Intent intent = (Intent)args[0];
            if((intent != null) && ("android.intent.action.SEND".equals(intent.getAction()) || "android.intent.action.SEND_MULTIPLE".equals(intent.getAction()) || "android.intent.action.SENDTO".equals(intent.getAction()))){
                if (slice) {
                    return ParceledListSliceCompat.create(appResult);
                }
                return appResult;
            }

            if (shouldIsolateHostPackagesForLimbus()) {
                if (slice) {
                    return ParceledListSliceCompat.create(appResult);
                }
                return appResult;
            }

            Object _hostResult = method.invoke(who, args);
            if (_hostResult != null) {
                List<ResolveInfo> hostResult = slice ? (List<ResolveInfo>) ParceledListSlice.getList.call(_hostResult)
                        : (List) _hostResult;
                if (hostResult != null) {
                    Iterator<ResolveInfo> iterator = hostResult.iterator();
                    while (iterator.hasNext()) {
                        ResolveInfo info = iterator.next();
                        if (info == null || info.activityInfo == null || !isVisiblePackageForCurrentApp(info.activityInfo.applicationInfo)) {
                            iterator.remove();
                        } else {
                            ComponentFixer.fixOutsideComponentInfo(info.activityInfo);
                        }
                    }
                    appResult.addAll(hostResult);
                }
            }
            if (slice) {
                return ParceledListSliceCompat.create(appResult);
            }
            return appResult;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class ResolveService extends MethodProxy {

        @Override
        public String getMethodName() {
            return "resolveService";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = readPackageFlags(args[2]);
            int userId = VUserHandle.myUserId();
            ResolveInfo resolveInfo = VPackageManager.get().resolveService(intent, resolvedType, flags, userId);
            if (resolveInfo != null) {
                return resolveInfo;
            }
            if (shouldIsolateHostPackagesForLimbus()) {
                return null;
            }
            ResolveInfo info = (ResolveInfo) method.invoke(who, args);
            if (info != null && isVisiblePackageForCurrentApp(info.serviceInfo.applicationInfo)) {
                ComponentFixer.fixOutsideComponentInfo(info.serviceInfo);
                return info;
            }
            return null;
        }
    }


    static class ClearPackagePersistentPreferredActivities extends MethodProxy {

        @Override
        public String getMethodName() {
            return "clearPackagePersistentPreferredActivities";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    static class GetPermissionGroupInfo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPermissionGroupInfo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String name = (String) args[0];
            int flags = readPackageFlags(args[1]);
            PermissionGroupInfo info = VPackageManager.get().getPermissionGroupInfo(name, flags);
            if (info != null) {
                return info;
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class GetPermissionInfo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPermissionInfo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String name = (String) args[0];
            int flags = readPackageFlags(args[args.length - 1]);
            PermissionInfo info = VPackageManager.get().getPermissionInfo(name, flags);
            if (info != null) {
                return info;
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static final class GetPackageInfo extends MethodProxy {
        /**
         * @see android.content.pm.PackageManager #MATCH_FACTORY_ONLY
         */
        private static final int MATCH_FACTORY_ONLY = 0x00200000;

        private static final int MATCH_ANY_USER = 0x00400000;

        @Override
        public String getMethodName() {
            return "getPackageInfo";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            return args != null && args[0] != null;
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            int flags = readPackageFlags(args[1]);
            int userId = VUserHandle.myUserId();
            if (shouldHidePackageFromCurrentApp(pkg)) {
                Log.i("LimbusVA", "Hide host package from Limbus getPackageInfo: " + pkg);
                return null;
            }
            if ((flags & MATCH_ANY_USER) != 0) {
                flags &=~ MATCH_ANY_USER;
                args[1] = writePackageFlags(args[1], flags);
            }
            if ((flags & MATCH_FACTORY_ONLY) != 0) {
                return method.invoke(who, args);
            }
            PackageInfo hostGooglePackageInfo = getHostPackageInfoForLimbus(who, method, args, pkg, flags);
            if (hostGooglePackageInfo != null) {
                return hostGooglePackageInfo;
            }
            PackageInfo packageInfo = VPackageManager.get().getPackageInfo(pkg, flags, userId);
            if (packageInfo != null) {
                fixLimbusOwnPackageInfo(packageInfo);
                return packageInfo;
            }
            if (shouldIsolateHostPackagesForLimbus()) {
                return null;
            }
            packageInfo = (PackageInfo) method.invoke(who, args);
            if (packageInfo != null) {
                if (isVisiblePackageForCurrentApp(packageInfo.applicationInfo) || "com.xdja.safekeyservice".equals(pkg)) {
                    fixLimbusOwnPackageInfo(packageInfo);
                    return packageInfo;
                }
            }
            return null;
        }

    }

    static class DeleteApplicationCacheFiles extends MethodProxy {

        @Override
        public String getMethodName() {
            return "deleteApplicationCacheFiles";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            IPackageDataObserver observer = (IPackageDataObserver) args[1];
            if (pkg.equals(getAppPkg())) {
                ApplicationInfo info = VPackageManager.get().getApplicationInfo(pkg, 0, getAppUserId());
                if (info != null) {
                    File dir = new File(info.dataDir);
                    FileUtils.deleteDir(dir);
                    dir.mkdirs();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dir = new File(info.deviceProtectedDataDir);
                        FileUtils.deleteDir(dir);
                        dir.mkdirs();
                    }
                    if (observer != null) {
                        observer.onRemoveCompleted(pkg, true);
                    }
                    return 0;
                }
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class SetApplicationBlockedSettingAsUser extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setApplicationBlockedSettingAsUser";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetApplicationEnabledSetting extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getApplicationEnabledSetting";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int pkgIndex = MethodParameterUtils.getIndex(args, String.class);
            if (pkgIndex >= 0) {
                String pkg = (String) args[pkgIndex];
                if (isAppPkg(pkg)) {
                    return PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                }
                if (isVisiblePackageForCurrentApp(pkg)) {
                    args[1] = 0;
                    return method.invoke(who, args);
                }
                return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            }
            return false;//method.invoke(who, args);
        }
    }

    static class CanRequestPackageInstalls extends MethodProxy {
        @Override
        public String getMethodName() {
            return "canRequestPackageInstalls";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (!VirtualCore.getConfig().isNeedRealRequestInstall(getAppPkg())) {
                //内部安装
                return true;
            }
            MethodParameterUtils.replaceFirstAppPkg(args);
            return super.call(who, method, args);
        }
    }

    static class AddPackageToPreferred extends MethodProxy {

        @Override
        public String getMethodName() {
            return "addPackageToPreferred";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return 0;
        }
    }

    static class CheckPermission extends MethodProxy {

        @Override
        public String getMethodName() {
            return "checkPermission";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String permName = (String) args[0];
            String pkgName = (String) args[1];
            int userId = VUserHandle.myUserId();
            return VPackageManager.get().checkPermission(permName, pkgName, userId);
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            return super.afterCall(who, method, args, result);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPackagesForUid extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPackagesForUid";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int uid = (int) args[0];
            if (uid == getRealUid()) {
                VLog.e("VPackageManager", "uid = real uid");
                uid = VBinder.getCallingUid();
            }
            String[] pkgs = VPackageManager.get().getPackagesForUid(uid);
            if (pkgs == null) {
                if (shouldIsolateHostPackagesForLimbus()) {
                    return new String[]{LIMBUS_PACKAGE};
                }
                args[0] = uid;
                return method.invoke(who, args);
            }
            return pkgs;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class QuerySliceContentProviders extends QueryContentProviders {
        @Override
        public String getMethodName() {
            return "querySliceContentProviders";
        }
    }

    static class GetPersistentApplications extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPersistentApplications";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (ParceledListSliceCompat.isReturnParceledListSlice(method)) {
                return ParceledListSliceCompat.create(new ArrayList<ApplicationInfo>(0));
            } else {
                return new ArrayList<ApplicationInfo>(0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static class QueryContentProviders extends MethodProxy {

        @Override
        public String getMethodName() {
            return "queryContentProviders";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            String processName = (String) args[0];
            int vuid = (int) args[1];
            int flags = readPackageFlags(args[2]);
            List<ProviderInfo> infos = VPackageManager.get().queryContentProviders(processName, vuid, 0);
            if (shouldIsolateHostPackagesForLimbus()) {
                if (slice) {
                    return ParceledListSliceCompat.create(infos);
                }
                return infos;
            }
            Object _hostResult = method.invoke(who, args);
            if (_hostResult != null) {
                List<ProviderInfo> hostResult = slice ? (List<ProviderInfo>) ParceledListSlice.getList.call(_hostResult)
                        : (List) _hostResult;
                Iterator<ProviderInfo> it = hostResult.iterator();
                while (it.hasNext()) {
                    ProviderInfo info = it.next();
                    if (!isVisiblePackageForCurrentApp(info.applicationInfo)) {
                        it.remove();
                        continue;
                    }
                    ComponentFixer.fixOutsideComponentInfo(info);
                }
                infos.addAll(hostResult);
            }

            if (slice) {
                return ParceledListSliceCompat.create(infos);
            }
            return infos;
        }

    }

    static class SetApplicationEnabledSetting extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setApplicationEnabledSetting";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    @SuppressLint("PackageManagerGetSignatures")
    static class CheckSignatures extends MethodProxy {

        @Override
        public String getMethodName() {
            return "checkSignatures";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (args.length == 2 && args[0] instanceof String && args[1] instanceof String) {
                String pkgNameOne = (String) args[0], pkgNameTwo = (String) args[1];
                if (TextUtils.equals(pkgNameOne, pkgNameTwo)) {
                    return PackageManager.SIGNATURE_MATCH;
                }
                return VPackageManager.get().checkSignatures(pkgNameOne, pkgNameTwo);
            }
            return method.invoke(who, args);
        }
    }

    static class checkUidSignatures extends MethodProxy {

        @Override
        public String getMethodName() {
            return "checkUidSignatures";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int uid1 = (int) args[0];
            int uid2 = (int) args[1];
            if (uid1 == uid2) {
                return PackageManager.SIGNATURE_MATCH;
            }
            if (uid1 == Constants.OUTSIDE_APP_UID || uid2 == Constants.OUTSIDE_APP_UID) {
                return PackageManager.SIGNATURE_MATCH;
            }
            String[] pkgs1 = VirtualCore.getPM().getPackagesForUid(uid1);
            String[] pkgs2 = VirtualCore.getPM().getPackagesForUid(uid2);
            if (pkgs1 == null || pkgs1.length == 0) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            if (pkgs2 == null || pkgs2.length == 0) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return VPackageManager.get().checkSignatures(pkgs1[0], pkgs2[0]);
        }
    }

    static class getNameForUid extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getNameForUid";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int uid = (int) args[0];
            if (uid == Constants.OUTSIDE_APP_UID) {
                uid = getVUid();
            }
            return VPackageManager.get().getNameForUid(uid);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class DeletePackage extends MethodProxy {

        @Override
        public String getMethodName() {
            return "deletePackage";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkgName = (String) args[0];
            try {
                VirtualCore.get().uninstallPackage(pkgName);
                IPackageDeleteObserver2 observer = (IPackageDeleteObserver2) args[1];
                if (observer != null) {
                    observer.onPackageDeleted(pkgName, 0, "done.");
                }
            } catch (Throwable e) {
                // Ignore
            }
            return 0;
        }

    }


    static class ActivitySupportsIntent extends MethodProxy {
        @Override
        public String getMethodName() {
            return "activitySupportsIntent";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName component = (ComponentName) args[0];
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            return VPackageManager.get().activitySupportsIntent(component, intent, resolvedType);
        }
    }


    static class ResolveIntent extends MethodProxy {

        @Override
        public String getMethodName() {
            return "resolveIntent";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = readPackageFlags(args[2]);
            int userId = VUserHandle.myUserId();
            ResolveInfo resolveInfo = VPackageManager.get().resolveIntent(intent, resolvedType, flags, userId);
            if (resolveInfo == null) {
                if (shouldIsolateHostPackagesForLimbus()) {
                    return null;
                }
                ResolveInfo info = (ResolveInfo) method.invoke(who, args);
                if (info != null && isVisiblePackageForCurrentApp(info.activityInfo.applicationInfo)) {
                    ComponentFixer.fixOutsideComponentInfo(info.activityInfo);
                    return info;
                }
            }
            return resolveInfo;
        }
    }


    static class GetApplicationInfo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getApplicationInfo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            int flags = readPackageFlags(args[1]);
            //get provider uri
            if ("com.xdja.engine.provider".equalsIgnoreCase(pkg) && flags==PackageManager.GET_META_DATA){
                args[0] = VirtualCore.get().getHostPkg();
                return method.invoke(who, args);
            }

            if (shouldHidePackageFromCurrentApp(pkg)) {
                Log.i("LimbusVA", "Hide host package from Limbus getApplicationInfo: " + pkg);
                return null;
            }
            if (getHostPkg().equals(pkg)) {
                return method.invoke(who, args);
            }
            ApplicationInfo hostGoogleInfo = getHostApplicationInfoForLimbus(who, method, args, pkg, flags);
            if (hostGoogleInfo != null) {
                return hostGoogleInfo;
            }
            int userId = VUserHandle.myUserId();
            ApplicationInfo info = VPackageManager.get().getApplicationInfo(pkg, flags, userId);
            if (info != null) {
                fixLimbusOwnApplicationInfo(info);
                return info;
            }
            if (shouldIsolateHostPackagesForLimbus()) {
                return null;
            }
            info = (ApplicationInfo) method.invoke(who, args);
            if (info == null || !isVisiblePackageForCurrentApp(info)) {
                return null;
            }
            ComponentFixer.fixOutsideApplicationInfo(info);
            fixLimbusOwnApplicationInfo(info);
            return info;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetProviderInfo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getProviderInfo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = readPackageFlags(args[1]);
            if (getHostPkg().equals(componentName.getPackageName())
                    && !shouldHidePackageFromCurrentApp(componentName.getPackageName())) {
                return method.invoke(who, args);
            }
            int userId = VUserHandle.myUserId();
            ProviderInfo info = VPackageManager.get().getProviderInfo(componentName, flags, userId);
            if (info == null) {
                if (shouldIsolateHostPackagesForLimbus()) {
                    return null;
                }
                info = (ProviderInfo) method.invoke(who, args);
                if (info == null || !isVisiblePackageForCurrentApp(info.applicationInfo)) {
                    return null;
                }
                ComponentFixer.fixOutsideComponentInfo(info);
            }
            return info;
        }

    }

    static class SetComponentEnabledSetting extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setComponentEnabledSetting";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int newState = (int) args[1];
            int flags = readPackageFlags(args[2]);
            VPackageManager.get().setComponentEnabledSetting(componentName, newState, flags, getAppUserId());
            return 0;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    @SuppressWarnings({"unchecked", "WrongConstant"})
    static class GetInstalledApplications extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getInstalledApplications";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            int flags = readPackageFlags(args[0]);
            int userId = VUserHandle.myUserId();
            List<ApplicationInfo> appInfos = VPackageManager.get().getInstalledApplications(flags, userId);
            if (shouldIsolateHostPackagesForLimbus()) {
                if (slice) {
                    return ParceledListSliceCompat.create(appInfos);
                }
                return appInfos;
            }
            Object _hostResult = method.invoke(who, args);
            List<ApplicationInfo> hostResult = slice ? (List<ApplicationInfo>) ParceledListSlice.getList.call(_hostResult)
                    : (List) _hostResult;
            Iterator<ApplicationInfo> it = hostResult.iterator();
            while (it.hasNext()) {
                ApplicationInfo info = it.next();
                if (VirtualCore.get().isAppInstalled(info.packageName) || !isVisiblePackageForCurrentApp(info.packageName)) {
                    it.remove();
                    if (info != null && shouldHidePackageFromCurrentApp(info.packageName)) {
                        Log.i("LimbusVA", "Hide host package from Limbus getInstalledApplications: " + info.packageName);
                    }
                    continue;
                }
                ComponentFixer.fixOutsideApplicationInfo(info);
            }
            appInfos.addAll(hostResult);
            if (slice) {
                return ParceledListSliceCompat.create(appInfos);
            }
            return appInfos;
        }
    }

    @SuppressWarnings({"unchecked", "WrongConstant"})
    static class GetInstalledPackages extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getInstalledPackages";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            int flags = readPackageFlags(args[0]);
            int userId = VUserHandle.myUserId();
            List<PackageInfo> packageInfos = VPackageManager.get().getInstalledPackages(flags, userId);
            if (shouldIsolateHostPackagesForLimbus()) {
                if (slice) {
                    return ParceledListSliceCompat.create(packageInfos);
                }
                return packageInfos;
            }
            Object _hostResult = method.invoke(who, args);
            List<PackageInfo> hostResult = slice ? (List<PackageInfo>) ParceledListSlice.getList.call(_hostResult)
                    : (List) _hostResult;
            Iterator<PackageInfo> it = hostResult.iterator();
            while (it.hasNext()) {
                PackageInfo info = it.next();
                if (VirtualCore.get().isAppInstalled(info.packageName) || !isVisiblePackageForCurrentApp(info.packageName)) {
                    it.remove();
                    if (info != null && shouldHidePackageFromCurrentApp(info.packageName)) {
                        Log.i("LimbusVA", "Hide host package from Limbus getInstalledPackages: " + info.packageName);
                    }
                    continue;
                }
                ComponentFixer.fixOutsideApplicationInfo(info.applicationInfo);
            }
            packageInfos.addAll(hostResult);
            if (ParceledListSliceCompat.isReturnParceledListSlice(method)) {
                return ParceledListSliceCompat.create(packageInfos);
            } else {
                return packageInfos;
            }
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    @SuppressWarnings("unchecked")
    static class QueryIntentReceivers extends MethodProxy {

        @Override
        public String getMethodName() {
            return "queryIntentReceivers";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            int userId = VUserHandle.myUserId();
            List<ResolveInfo> appResult = VPackageManager.get().queryIntentReceivers((Intent) args[0], (String) args[1],
                    readPackageFlags(args[2]), userId);
            if (shouldIsolateHostPackagesForLimbus()) {
                if (slice) {
                    return ParceledListSliceCompat.create(appResult);
                }
                return appResult;
            }
            Object _hostResult = method.invoke(who, args);
            List<ResolveInfo> hostResult = slice ? (List<ResolveInfo>) ParceledListSlice.getList.call(_hostResult)
                    : (List) _hostResult;
            if (hostResult != null) {
                Iterator<ResolveInfo> iterator = hostResult.iterator();
                while (iterator.hasNext()) {
                    ResolveInfo info = iterator.next();
                    if (info == null || info.activityInfo == null || !isVisiblePackageForCurrentApp(info.activityInfo.applicationInfo)) {
                        iterator.remove();
                    } else {
                        ComponentFixer.fixOutsideComponentInfo(info.activityInfo);
                    }
                }
                appResult.addAll(hostResult);
            }
            if (slice) {
                return ParceledListSliceCompat.create(appResult);
            }
            return appResult;
        }
    }


    static class GetReceiverInfo extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getReceiverInfo";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            if (getHostPkg().equals(componentName.getPackageName())
                    && !shouldHidePackageFromCurrentApp(componentName.getPackageName())) {
                return method.invoke(who, args);
            }
            int flags = readPackageFlags(args[1]);
            ActivityInfo info = VPackageManager.get().getReceiverInfo(componentName, flags, 0);
            if (info == null) {
                if (shouldIsolateHostPackagesForLimbus()) {
                    return null;
                }
                info = (ActivityInfo) method.invoke(who, args);
                if (info == null || !isVisiblePackageForCurrentApp(info.applicationInfo)) {
                    return null;
                }
                ComponentFixer.fixOutsideComponentInfo(info);
            }
            return info;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    static class GetPermissionFlags extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPermissionFlags";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String name = (String) args[0];
            String packageName = (String) args[1];
            int userId = (int) args[2];
            PermissionInfo info = VPackageManager.get().getPermissionInfo(name, 0);
            if (info != null) {
                return 0;
            }
            // force userId to 0
            args[2] = 0;
            return method.invoke(who, args);
        }

    }


    static class SetPackageStoppedState extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setPackageStoppedState";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    @SuppressWarnings("unchecked")
    @TargetApi(Build.VERSION_CODES.KITKAT)
    static class QueryIntentContentProviders extends MethodProxy {

        @Override
        public String getMethodName() {
            return "queryIntentContentProviders";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            int userId = VUserHandle.myUserId();
            List<ResolveInfo> appResult = VPackageManager.get().queryIntentContentProviders((Intent) args[0], (String) args[1],
                    readPackageFlags(args[2]), userId);
            if (shouldIsolateHostPackagesForLimbus()) {
                if (slice) {
                    return ParceledListSliceCompat.create(appResult);
                }
                return appResult;
            }
            Object _hostResult = method.invoke(who, args);
            List<ResolveInfo> hostResult = slice ? (List<ResolveInfo>) ParceledListSlice.getList.call(_hostResult)
                    : (List) _hostResult;
            if (hostResult != null) {
                Iterator<ResolveInfo> iterator = hostResult.iterator();
                while (iterator.hasNext()) {
                    ResolveInfo info = iterator.next();
                    if (info == null || info.providerInfo == null || !isVisiblePackageForCurrentApp(info.providerInfo.applicationInfo)) {
                        iterator.remove();
                    } else {
                        ComponentFixer.fixOutsideComponentInfo(info.providerInfo);
                    }
                }
                appResult.addAll(hostResult);
            }
            if (ParceledListSliceCompat.isReturnParceledListSlice(method)) {
                return ParceledListSliceCompat.create(appResult);
            }
            return appResult;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetApplicationBlockedSettingAsUser extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getApplicationBlockedSettingAsUser";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

}
