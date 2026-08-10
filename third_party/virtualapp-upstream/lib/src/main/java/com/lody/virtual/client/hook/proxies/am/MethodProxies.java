package com.lody.virtual.client.hook.proxies.am;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.IServiceConnection;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.WallpaperManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.lody.virtual.GmsSupport;
import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.badger.BadgerManager;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;
import com.lody.virtual.client.env.SpecialComponentList;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.delegate.TaskDescriptionDelegate;
import com.lody.virtual.client.hook.providers.DocumentHook;
import com.lody.virtual.client.hook.providers.ProviderHook;
import com.lody.virtual.client.hook.secondary.ServiceConnectionDelegate;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.stub.UnInstallerActivity;
import com.lody.virtual.remote.AppRunningProcessInfo;
import com.xdja.zs.UacProxyActivity;
import com.xdja.zs.VAppPermissionManager;
import com.lody.virtual.client.ipc.VNotificationManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.client.service.ServiceManager;
import com.lody.virtual.client.service.ServiceRecord;
import com.lody.virtual.client.stub.ChooserActivity;
import com.lody.virtual.client.stub.InstallerActivity;
import com.lody.virtual.client.stub.InstallerSetting;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.compat.ActivityManagerCompat;
import com.lody.virtual.helper.compat.LimbusActivityCompat;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.helper.compat.IntentCompat;
import com.lody.virtual.helper.compat.ParceledListSliceCompat;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.helper.utils.BitmapUtils;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.DrawableUtils;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;
import com.lody.virtual.remote.AppTaskInfo;
import com.lody.virtual.remote.BroadcastIntentData;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.remote.IntentSenderData;
import com.lody.virtual.remote.IntentSenderExtData;
import com.lody.virtual.remote.StubActivityRecord;
import com.xdja.zs.controllerManager;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.WeakHashMap;

import mirror.android.app.IActivityManager;
import mirror.android.app.LoadedApk;
import mirror.android.content.ContentProviderHolderOreo;
import mirror.android.content.IIntentReceiverJB;
import mirror.android.content.pm.ParceledListSlice;
import mirror.android.content.pm.UserInfo;
import android.widget.Toast;

/**
 * @author Lody
 */
@SuppressWarnings("unused")
class MethodProxies {
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String PLAY_BILLING_SERVICE_ACTION = "com.android.vending.billing.InAppBillingService.BIND";

    private static boolean shouldExposeHostGoogleServiceToLimbus(String packageName) {
        return false;
    }

    private static boolean isLimbusPlayBillingService(Intent intent) {
        return LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                && intent != null
                && PLAY_BILLING_SERVICE_ACTION.equals(intent.getAction());
    }

    private static Intent createHostPlayBillingIntent(Intent virtualIntent) {
        Intent hostIntent = new Intent(virtualIntent);
        // 容器内 FakeStore 使用旧的兼容服务类名；宿主 Play Store 的实现类会随版本变化，
        // 因此必须清除虚拟组件并按 billing action 重新解析宿主服务。
        hostIntent.setComponent(null);
        hostIntent.setSelector(null);
        hostIntent.setPackage(PLAY_STORE_PACKAGE);
        return hostIntent;
    }

    private static boolean isVisibleServiceForCurrentApp(ServiceInfo info) {
        return info != null
                && info.applicationInfo != null
                && (MethodProxy.isVisiblePackage(info.applicationInfo)
                || (LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg()) && shouldExposeHostGoogleServiceToLimbus(info.packageName)));
    }

    private static boolean shouldIsolateHostActivitiesForLimbus() {
        return LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                && VClient.get().shouldHideHostPackageForLimbus();
    }

    private static boolean shouldExposeHostGoogleActivityToLimbus(String packageName) {
        return shouldExposeHostGoogleServiceToLimbus(packageName);
    }

    private static boolean shouldBlockHostActivityForLimbus(Intent intent, ActivityInfo info) {
        if (!shouldIsolateHostActivitiesForLimbus()) {
            return false;
        }
        String packageName = info == null ? null : info.packageName;
        ComponentName component = intent == null ? null : intent.getComponent();
        if (packageName == null && component != null) {
            packageName = component.getPackageName();
        }
        return packageName == null || !shouldExposeHostGoogleActivityToLimbus(packageName);
    }

    private static String describeIntentForLog(Intent intent) {
        if (intent == null) {
            return "null";
        }
        return "action=" + intent.getAction()
                + " data=" + intent.getDataString()
                + " type=" + intent.getType()
                + " pkg=" + intent.getPackage()
                + " cmp=" + intent.getComponent()
                + " flags=0x" + Integer.toHexString(intent.getFlags());
    }

    private static ComponentName getExplicitServiceComponent(Intent intent) {
        return intent == null ? null : intent.getComponent();
    }

    private static Object autoCreateBindFlag(Object original) {
        if (original instanceof Long) {
            return Long.valueOf(Context.BIND_AUTO_CREATE);
        }
        return Integer.valueOf(Context.BIND_AUTO_CREATE);
    }

    private static void rewriteCallingPackageForHostGoogleService(Object[] args, ServiceInfo info, int index, String methodName) {
        if (info == null
                || !LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                || !shouldExposeHostGoogleServiceToLimbus(info.packageName)
                || index < 0
                || index >= args.length
                || !(args[index] instanceof String)) {
            return;
        }
        String oldPackage = (String) args[index];
        String hostPackage = MethodProxy.getHostPkg();
        if (!hostPackage.equals(oldPackage)) {
            args[index] = hostPackage;
            Log.i("LimbusVA", methodName + " rewrite host Google callingPackage service="
                    + info.packageName + "/" + info.name
                    + " old=" + oldPackage + " new=" + hostPackage);
        }
    }

    static class FinishReceiver extends MethodProxy {
        @Override
        public String getMethodName() {
            return "finishReceiver";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
//            if (args[0] instanceof IBinder) {
//                IBinder token = (IBinder) args[0];
//            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class GetRecentTasks extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getRecentTasks";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Object _infos = method.invoke(who, args);
            boolean slice = ParceledListSliceCompat.isReturnParceledListSlice(method);
            //noinspection unchecked
            List<ActivityManager.RecentTaskInfo> infos = slice ? (List<ActivityManager.RecentTaskInfo>) ParceledListSlice.getList.call(_infos)
                    : (List) _infos;
            Iterator<ActivityManager.RecentTaskInfo> it = infos.iterator();
            while (it.hasNext()){
                ActivityManager.RecentTaskInfo info = it.next();
                AppTaskInfo taskInfo = VActivityManager.get().getTaskInfo(info.id);
                if (taskInfo == null) {
                    ComponentName cmp = ComponentUtils.getAppComponent(info.baseIntent);
                    if(cmp == null){
                        it.remove();
                    }
                    continue;
                }
                if (taskInfo.excludeRecent) {
                    it.remove();
                    continue;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        info.topActivity = taskInfo.topActivity;
                        info.baseActivity = taskInfo.baseActivity;
                    } catch (Throwable e) {
                        // ignore
                    }
                }
                try {
                    info.origActivity = taskInfo.baseActivity;
                    info.baseIntent = taskInfo.baseIntent;
                } catch (Throwable e) {
                    // ignore
                }
            }
            if(slice){
                return ParceledListSliceCompat.create(infos);
            }
            return _infos;
        }
    }

    static class GetHistoricalProcessExitReasons extends MethodProxy {

        private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";

        @Override
        public String getMethodName() {
            return "getHistoricalProcessExitReasons";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (isAppProcess()) {
                Log.i("LimbusVA", "Return empty historical process exit reasons for virtual app=" + getAppPkg());
                if (ParceledListSliceCompat.isReturnParceledListSlice(method)) {
                    return ParceledListSliceCompat.create(Collections.emptyList());
                }
                return Collections.emptyList();
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class ForceStopPackage extends MethodProxy {

        @Override
        public String getMethodName() {
            return "forceStopPackage";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String pkg = (String) args[0];
            int userId = VUserHandle.myUserId();
            VActivityManager.get().killAppByPkg(pkg, userId);
            return 0;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class CrashApplication extends MethodProxy {

        @Override
        public String getMethodName() {
            return "crashApplication";
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

    static class AddPackageDependency extends MethodProxy {

        @Override
        public String getMethodName() {
            return "addPackageDependency";
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

    static class GetPackageForToken extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPackageForToken";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
            String pkg = VActivityManager.get().getPackageForToken(token);
            if (pkg != null) {
                return pkg;
            }
            return super.call(who, method, args);
        }
    }

    static class UnbindService extends MethodProxy {

        @Override
        public String getMethodName() {
            return "unbindService";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IServiceConnection conn = (IServiceConnection) args[0];
            ServiceConnectionDelegate delegate = ServiceConnectionDelegate.getDelegate(conn);
            if (delegate != null) {
                args[0] = delegate;
                VActivityManager.get().unbindService(getAppUserId(), conn.asBinder());
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }

    static class GetContentProviderExternal extends GetContentProvider {

        @Override
        public String getMethodName() {
            return "getContentProviderExternal";
        }

        @Override
        public int getProviderNameIndex() {
            if (BuildCompat.isQ()) {
                return 1;
            }
            return 0;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class StartVoiceActivity extends StartActivity {
        @Override
        public String getMethodName() {
            return "startVoiceActivity";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return super.call(who, method, args);
        }
    }


    static class UnstableProviderDied extends MethodProxy {

        @Override
        public String getMethodName() {
            return "unstableProviderDied";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (args[0] == null) {
                return 0;
            }
            return method.invoke(who, args);
        }
    }


    static class PeekService extends MethodProxy {

        @Override
        public String getMethodName() {
            return "peekService";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
//            Intent service = (Intent) args[0];
//            String resolvedType = (String) args[1];
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPackageAskScreenCompat extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPackageAskScreenCompat";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (args.length > 0 && args[0] instanceof String) {
                    args[0] = getHostPkg();
                }
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetIntentSender extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getIntentSender";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return callWithIntentIndexes(who, method, args, 5, 6, 7);
        }

        protected Object callWithIntentIndexes(Object who, Method method, Object[] args, int intentsIndex, int resolvedTypesIndex, int flagsIndex) throws Throwable {
            String creator = (String) args[1];
            args[1] = getHostPkg();
            // force userId to 0
            if (args[args.length - 1] instanceof Integer) {
                args[args.length - 1] = 0;
            }
            String[] resolvedTypes = (String[]) args[resolvedTypesIndex];
            int indexOfFirst = ArrayUtils.indexOfFirst(args, IBinder.class);
            int type = (int) args[0];
            int flags = (int) args[flagsIndex];
            Intent[] intents = (Intent[]) args[intentsIndex];

            int fillInFlags = Intent.FILL_IN_ACTION
                    | Intent.FILL_IN_DATA
                    | Intent.FILL_IN_CATEGORIES
                    | Intent.FILL_IN_COMPONENT
                    | Intent.FILL_IN_PACKAGE
                    | Intent.FILL_IN_SOURCE_BOUNDS
                    | Intent.FILL_IN_SELECTOR
                    | Intent.FILL_IN_CLIP_DATA;

            if (intents.length > 0) {
                Intent intent = intents[intents.length - 1];
                /*
                 * Fix:
                 * android.os.BadParcelableException: ClassNotFoundException when unmarshalling: meri.pluginsdk.PluginIntent
                 */
                intent = new Intent(intent);
                if (resolvedTypes != null && resolvedTypes.length >= intents.length) {
                    intent.setDataAndType(intent.getData(), resolvedTypes[intents.length - 1]);
                }
                Intent targetIntent = ComponentUtils.redirectIntentSender(type, creator, intent);
                if (targetIntent == null) {
                    return null;
                }
                if (GmsSupport.GMS_PKG.equals(creator)
                        && isGoogleIdentityPendingUi(intent)) {
                    targetIntent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    targetIntent.removeFlags(IntentCompat.IMMUTABLE_FLAGS);
                    intent.removeFlags(IntentCompat.IMMUTABLE_FLAGS);
                    flags &= ~IntentCompat.IMMUTABLE_FLAGS;
                    Log.i("LimbusVA", "Cleared NEW_TASK/immutable flags from Google Identity pending UI intent action="
                            + intent.getAction() + " component=" + intent.getComponent());
                }
                String bridgeKey = creator + ":" + type + ":" + System.nanoTime();
                targetIntent.putExtra("_VA_|_sender_key_", bridgeKey);
                args[intentsIndex] = new Intent[]{targetIntent};
                args[resolvedTypesIndex] = new String[]{null};
                //xdja add FLAG_CANCEL_CURRENT cancle cache
                //如果应用全部进程死了，PendIngIntent应该全部取消
                args[flagsIndex] = (flags | PendingIntent.FLAG_UPDATE_CURRENT) & ~fillInFlags;
                IInterface sender = (IInterface) method.invoke(who, args);
                if (sender != null) {
                    IBinder token = sender.asBinder();
                    BundleCompat.putBinder(targetIntent, "_VA_|_sender_", token);
                    IntentSenderData data = new IntentSenderData(creator, token, intent, flags, type, VUserHandle.myUserId());
                    data.bridgeKey = bridgeKey;
                    VActivityManager.get().addOrUpdateIntentSender(data);
                }
                return sender;
            }
            return method.invoke(who, args);

        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

        private boolean isGoogleIdentityPendingUi(Intent intent) {
            if (intent == null) {
                return false;
            }
            String action = intent.getAction();
            return "com.google.android.gms.auth.api.credentials.GOOGLE_SIGN_IN".equals(action)
                    || "com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN".equals(action);
        }

    }

    static class GetIntentSenderWithFeature extends GetIntentSender {

        @Override
        public String getMethodName() {
            return "getIntentSenderWithFeature";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (args.length > 2 && args[2] instanceof String) {
                args[2] = null;
            }
            return callWithIntentIndexes(who, method, args, 6, 7, 8);
        }
    }

    static class CancelIntentSender extends MethodProxy{
        @Override
        public String getMethodName() {
            return "cancelIntentSender";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            try {
                IInterface sender = (IInterface) args[0];
                VActivityManager.get().removeIntentSender(sender.asBinder());
            }catch (Throwable ignore){

            }
            return super.beforeCall(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class StartActivity extends MethodProxy {

        private static final String SCHEME_FILE = "file";
        private static final String SCHEME_PACKAGE = "package";
        private static final String SCHEME_CONTENT = "content";

        @Override
        public String getMethodName() {
            return "startActivity";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {

            //xdja
          /*  if(!controllerManager.getActivitySwitch()){
                return 0;
            }*/
            int intentIndex = ArrayUtils.indexOfObject(args, Intent.class, 1);
            if (intentIndex < 0) {
                return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
            }
            int resultToIndex = ArrayUtils.indexOfObject(args, IBinder.class, 2);
            String resolvedType = (String) args[intentIndex + 1];
            Intent intent = (Intent) args[intentIndex];

            Log.e("lxf","startActivity app "+getAppPkg());
            Log.e("lxf","startActivity intent "+ intent.toString());
            String action = intent.getAction();
            if(Intent.ACTION_VIEW.equals(action)&&"*/*".equals(resolvedType)){
                String suffix = MimeTypeMap.getFileExtensionFromUrl(intent.getDataString());
                if(suffix!=null){
                    String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix);
                    if(type!=null)
                        resolvedType = type;
                }
            }else if("android.media.action.IMAGE_CAPTURE".equals(action)){
                intent.putExtra("IS_DECRYPT", NativeEngine.nativeGetDecryptState());
            }

            if (Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT.equals(action)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getHostPkg());
                } else {
                    intent.putExtra("package", getHostPkg());
                }
                return method.invoke(who, args);
            }

            if(WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER.equals(intent.getAction())){
                //屏蔽裁剪并且设置壁纸
                return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if(Intent.ACTION_VIEW.equals(action) && DocumentsContract.isDocumentUri(VirtualCore.get().getContext(),
                        intent.getData())) {
                    String docId = FileUtils.getFilePathByUri(VirtualCore.get().getContext(), Objects.requireNonNull(intent.getData()));
                    
                    File file = new File(docId);
                    intent.setData(Uri.fromFile(file));
                }
            }

            intent.setDataAndType(intent.getData(), resolvedType);
            if ("*/*".equals((String) args[intentIndex + 1])) {
                args[intentIndex + 1] = resolvedType;
            }
            final IBinder resultTo = resultToIndex >= 0 ? (IBinder) args[resultToIndex] : null;
            String resultWho = null;
            int requestCode = -1;
            Bundle options = ArrayUtils.getFirst(args, Bundle.class);
            if (resultTo != null) {
                resultWho = (String) args[resultToIndex + 1];
                requestCode = (int) args[resultToIndex + 2];
            }
            int userId = VUserHandle.myUserId();
            if ("android.intent.action.MAIN".equals(intent.getAction())
                    && intent.hasCategory("android.intent.category.HOME")) {
                Intent homeIntent = getConfig().onHandleLauncherIntent(intent);
                if (homeIntent != null) {
                    args[intentIndex] = homeIntent;
                }
                return method.invoke(who, args);
            }
            if("android.intent.action.SENDTO".equals(intent.getAction())
                    &&intent.getDataString()!=null
                    &&intent.getDataString().startsWith("smsto:")){
                return method.invoke(who,args);
            }

            if(!VirtualCore.getConfig().isNeedRealRequestInstall(getAppPkg())) {
                if (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(intent.getAction())) {
                    //不需要给安全盒申请安装apk权限，内部应用更新不走系统安装器
                    //canRequestPackageInstalls必须返回true
                    if (resultTo != null && requestCode > 0) {
                        final String finalResultWho = resultWho;
                        final int finalRequestCode = requestCode;
                        VirtualRuntime.getUIHandler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                VActivityManager.get().sendActivityResultLocal(resultTo, finalResultWho, finalRequestCode, null, Activity.RESULT_OK);
                            }
                        }, 1000);
                        return ActivityManagerCompat.START_SUCCESS;
                    }
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
            }

            if (isHostIntent(intent)) {
                return method.invoke(who, args);
            }

            //权限管控
            VAppPermissionManager vAppPermissionManager = VAppPermissionManager.get();
            //禁止使用照相机
            boolean cameraEnable = vAppPermissionManager.getAppPermissionEnable(getAppPkg()
                    , VAppPermissionManager.PROHIBIT_CAMERA);
            Log.e("geyao", getAppPkg() + " Camera Enable: " + cameraEnable);
            if (cameraEnable && MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())) {
                vAppPermissionManager.interceptorTriggerCallback(getAppPkg(), VAppPermissionManager.PROHIBIT_CAMERA);
                return 0;
            }
            //禁止对此应用进行截屏,录屏
            boolean screenShortRecorderEnable = vAppPermissionManager.getAppPermissionEnable(getAppPkg()
                    , VAppPermissionManager.PROHIBIT_SCREEN_SHORT_RECORDER);
            Log.e("geyao", getAppPkg() + " screenShortRecorderEnable Enable: " + screenShortRecorderEnable);
            ComponentName component = intent.getComponent();
            if (component != null && "com.android.systemui".equals(component.getPackageName())
                    && "com.android.systemui.media.MediaProjectionPermissionActivity".equals(component.getClassName())
                    && screenShortRecorderEnable) {
                Log.e("geyao", getAppPkg() + " prohibit screen short recorder interceptorTriggerCallback");
                vAppPermissionManager.interceptorTriggerCallback(getAppPkg(), VAppPermissionManager.PROHIBIT_SCREEN_SHORT_RECORDER);
                return 0;
            }
            //禁止使用蓝牙功能
            boolean bluetoothEnable = vAppPermissionManager.getAppPermissionEnable(getAppPkg(), VAppPermissionManager.PROHIBIT_BLUETOOTH);
            Log.e("geyao", getAppPkg() + " bluetoothEnable Enable: " + bluetoothEnable);
            if (bluetoothEnable && (BluetoothAdapter.ACTION_REQUEST_ENABLE.equals(intent.getAction()) ||
                    (component != null && "com.android.bluetooth".equals(component.getPackageName())))) {
                Log.e("geyao", getAppPkg() + " prohibit bluetooth interceptorTriggerCallback");
                vAppPermissionManager.interceptorTriggerCallback(getAppPkg(), VAppPermissionManager.PROHIBIT_BLUETOOTH);
                return 0;
            }
            //xdja swbg
//            if(getAppPkg() != null && getAppPkg().equals("com.xdja.swbg")
//                    &&Intent.ACTION_VIEW.equals(intent.getAction())
//                    &&Intent.FLAG_ACTIVITY_NEW_TASK==intent.getFlags()
//                    &&intent.getType()!=null&&intent.getType().equals("*/*")){
//                Log.d("StartActivity", "lxf "+"this is New Task.");
//
//                boolean hasWps = false;
//                List<PackageInfo> listInfos = VPackageManager.get().getInstalledPackages(0, VUserHandle.myUserId());
//                for (PackageInfo info : listInfos){
//                    if(info.packageName.equals("cn.wps.moffice_eng")){
//                        hasWps = true;
//                        break;
//                    }
//                }
//
//                Log.d("StartActivity", "lxf hasWps "+hasWps);
//                if(hasWps){
//                    intent.setClassName("cn.wps.moffice_eng",
//                            "cn.wps.moffice.documentmanager.PreStartActivity");
//                }else{
//                    CharSequence tips = "Not Have WPS!";
//                    android.widget.Toast toast = Toast.makeText.call(getHostContext(), R.string.noApplications,Toast.LENGTH_SHORT);
//
//                    Log.d("StartActivity", "lxf toast "+toast);
//                    toast.show();
//                    return 0;
//                }
//            }
            //xdja

            if (Intent.ACTION_INSTALL_PACKAGE.equals(intent.getAction())
                    || (Intent.ACTION_VIEW.equals(intent.getAction())
                    && "application/vnd.android.package-archive".equals(intent.getType()))) {
                /*if (handleInstallRequest(intent)) {
                    if (resultTo != null && requestCode > 0) {
                        VActivityManager.get().sendCancelActivityResult(resultTo, resultWho, requestCode);
                    }
                    return 0;
                }*/
                intent.putExtra("source_apk", VirtualRuntime.getInitialPackageName());
                intent.putExtra("installer_path", parseInstallRequest(intent));
                intent.setComponent(new ComponentName(getHostContext(), InstallerActivity.class));
                intent.setData(null);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                return method.invoke(who, args);
            } else if ((Intent.ACTION_UNINSTALL_PACKAGE.equals(intent.getAction())
                    || Intent.ACTION_DELETE.equals(intent.getAction()))
                    && "package".equals(intent.getScheme())) {
                /*if (handleUninstallRequest(intent)) {
                    return 0;
                }*/
                String pkg = "";
                Uri packageUri = intent.getData();
                if (SCHEME_PACKAGE.equals(packageUri.getScheme())) {
                    pkg = packageUri.getSchemeSpecificPart();
                }
                if(InstallerSetting.systemApps.contains(pkg)){
                    InstallerSetting.showToast(getHostContext(),"自带应用不可卸载", Toast.LENGTH_LONG);
                    intent.setData(null);
                    return method.invoke(who, args);
                }else if(VAppPermissionManager.get().getAppPermissionEnable(pkg,VAppPermissionManager.PROHIBIT_APP_UNINSTALL)){
                    InstallerSetting.showToast(getHostContext(),"企业策略限制，该应用不可卸载",Toast.LENGTH_LONG);
                    intent.setData(null);
                    return method.invoke(who, args);
                }
                intent.putExtra("uninstall_app",pkg);
                intent.setComponent(new ComponentName(getHostContext(), UnInstallerActivity.class));
                intent.setData(null);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                return method.invoke(who, args);
            }
            String pkg = intent.getPackage();
            if (pkg != null && !isAppPkg(pkg)) {
                if (shouldIsolateHostActivitiesForLimbus()
                        && LimbusActivityCompat.isRuntimePermissionRequest(intent)) {
                    MethodParameterUtils.replaceFirstAppPkg(args);
                    Log.i("LimbusVA", "Allow system permission controller for Limbus: "
                            + describeIntentForLog(intent));
                    return method.invoke(who, args);
                }
                if (shouldBlockHostActivityForLimbus(intent, null)) {
                    Log.i("LimbusVA", "Block explicit host activity package for Limbus: "
                            + pkg + " " + describeIntentForLog(intent));
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                return method.invoke(who, args);
            }

            if(Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getType() != null){
                if(VirtualCore.getConfig().onHandleView(intent, getAppPkg(), getAppUserId())){
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
            }
            // chooser
            if (intent.getPackage() == null && intent.getComponent() == null && ChooserActivity.check(intent)) {
                if (shouldIsolateHostActivitiesForLimbus()) {
                    Log.i("LimbusVA", "Block host chooser activity for Limbus: " + describeIntentForLog(intent));
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                intent = VirtualCore.getConfig().getChooserIntent(intent, resultTo, resultWho, requestCode, options, userId);
                args[intentIndex] = intent;
                return method.invoke(who, args);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                MethodParameterUtils.replaceFirstAppPkg(args);
            }
            if (intent.getScheme() != null && intent.getScheme().equals(SCHEME_PACKAGE) && intent.getData() != null) {
                if (intent.getAction() != null && intent.getAction().startsWith("android.settings.")) {
                    intent.setData(Uri.parse("package:" + getHostPkg()));
                }
            }
            ActivityInfo activityInfo = VirtualCore.get().resolveActivityInfo(intent, userId);
            if (activityInfo == null) {
                VLog.d("VActivityManager", "Unable to resolve activityInfo : %s", intent);
                if (intent.getPackage() != null && isAppPkg(intent.getPackage())) {
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                if (shouldIsolateHostActivitiesForLimbus()) {
                    Log.i("LimbusVA", "Block unresolved host activity lookup for Limbus: "
                            + describeIntentForLog(intent));
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }

                //xdja uac scheme
                if(Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null
                        && intent.getData().toString().startsWith(UacProxyActivity.IAM_URI)){
                    intent = UacProxyActivity.isHook(intent);
                }

                args[intentIndex] = ComponentUtils.processOutsideIntent(userId, VirtualCore.get().isPluginEngine(), intent);
                ResolveInfo resolveInfo = VirtualCore.get().getUnHookPackageManager().resolveActivity(intent, 0);
                if (resolveInfo == null || resolveInfo.activityInfo == null) {
                    //fix google phone 安通拨号的设置默认电话
                    if (InstallerSetting.DIALER_PKG.equals(getAppPkg())) {
                        if (intent.getComponent() != null && "com.android.settings".equals(intent.getComponent().getPackageName())) {
                            if (intent.getComponent().getClassName().contains("PreferredListSettingsActivity")
                                    || intent.getComponent().getClassName().contains("HomeSettingsActivity")) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    intent.setAction(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                                    intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getHostPkg());
                                    intent.setComponent(null);
                                    return method.invoke(who, args);
                                }
                            }
                        }
                    }
                    VLog.e("VActivityManager", "Unable to resolve activityInfo : %s in outside", intent);
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }

                if (shouldBlockHostActivityForLimbus(intent, resolveInfo.activityInfo)) {
                    Log.i("LimbusVA", "Block resolved host activity for Limbus: "
                            + resolveInfo.activityInfo.packageName + "/" + resolveInfo.activityInfo.name
                            + " " + describeIntentForLog(intent));
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                if (Intent.ACTION_DIAL.equals(intent.getAction())) {
                    return method.invoke(who, args);
                }

                if (!Intent.ACTION_VIEW.equals(intent.getAction())
                        && !isVisiblePackage(resolveInfo.activityInfo.applicationInfo)) {
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                return method.invoke(who, args);
            }
            if (isLimbusCredentialsHiddenActivity(intent)) {
                Log.i("LimbusVA", "Launch Limbus Credentials HiddenActivity through client-local stub");
                Intent stubIntent = buildClientLocalStubIntent(intent, activityInfo, userId, false);
                args[intentIndex] = stubIntent;
                MethodParameterUtils.replaceFirstAppPkg(args);
                return method.invoke(who, args);
            }
            if (isMicrogSignInActivity(intent, activityInfo)) {
                Log.i("LimbusVA", "Launch microG sign-in activity through client-local stub: "
                        + describeIntentForLog(intent));
                Intent stubIntent = buildClientLocalStubIntent(intent, activityInfo, userId, false);
                args[intentIndex] = stubIntent;
                MethodParameterUtils.replaceFirstAppPkg(args);
                return method.invoke(who, args);
            }
            int res = VActivityManager.get().startActivity(intent, activityInfo, resultTo, options, resultWho, requestCode, VUserHandle.myUserId());
            if (res != 0 && resultTo != null && requestCode > 0) {
                VActivityManager.get().sendCancelActivityResult(resultTo, resultWho, requestCode);
            }
            return res;
        }

        private Intent buildClientLocalStubIntent(Intent intent, ActivityInfo activityInfo, int userId, boolean dialog) {
            Intent stubIntent = new Intent();
            stubIntent.setClassName(
                    StubManifest.PACKAGE_NAME,
                    dialog
                            ? StubManifest.getStubDialogName(VClient.get().getVpid())
                            : StubManifest.getStubActivityName(VClient.get().getVpid()));
            ComponentName component = intent.getComponent();
            if (component != null) {
                stubIntent.setType(component.flattenToString());
            }
            stubIntent.setFlags(intent.getFlags());
            stubIntent.putExtra(HCallbackStub.CLIENT_LOCAL_ACTIVITY_EXTRA, true);
            new StubActivityRecord(intent, activityInfo, userId, null).saveToIntent(stubIntent);
            return stubIntent;
        }

        private boolean isLimbusCredentialsHiddenActivity(Intent intent) {
            ComponentName component = intent == null ? null : intent.getComponent();
            return LIMBUS_PACKAGE.equals(getAppPkg())
                    && component != null
                    && LIMBUS_PACKAGE.equals(component.getPackageName())
                    && "androidx.credentials.playservices.HiddenActivity".equals(component.getClassName());
        }

        private boolean isMicrogSignInActivity(Intent intent, ActivityInfo activityInfo) {
            ComponentName component = intent == null ? null : intent.getComponent();
            String className = component != null ? component.getClassName() : (activityInfo == null ? null : activityInfo.name);
            return "com.google.android.gms".equals(getAppPkg())
                    && activityInfo != null
                    && "com.google.android.gms".equals(activityInfo.packageName)
                    && className != null
                    && className.startsWith("org.microg.gms.auth.signin.");
        }

        private void logLimbusCredentialNativeMappings() {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("libil2cpp.so") || line.contains("libunity.so")
                            || line.contains("libmain.so") || line.contains("libcovault-appsec.so")
                            || line.contains("libv++_64.so")
                            || line.contains(" r-xp ") || line.contains(" rwxp ")) {
                        Log.i("LimbusVA", "Credentials native map " + line);
                    }
                }
            } catch (Throwable error) {
                Log.w("LimbusVA", "Unable to record credentials native mappings", error);
            }
        }

        private String parseInstallRequest(Intent intent){
            Uri packageUri = intent.getData();
            String path = null;
            if (SCHEME_FILE.equals(packageUri.getScheme())) {

                File sourceFile = new File(packageUri.getPath());
                path = NativeEngine.getRedirectedPath(sourceFile.getAbsolutePath());
                VLog.e("wxd", " parseInstallRequest path : " + path);
            }else if (SCHEME_CONTENT.equals(packageUri.getScheme())) {
                InputStream inputStream = null;
                OutputStream outputStream = null;
                File sharedFileCopy = new File(getHostContext().getCacheDir(), new Random().nextInt()+".apk");
                try {
                    inputStream = getHostContext().getContentResolver().openInputStream(packageUri);
                    outputStream = new FileOutputStream(sharedFileCopy);
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, count);
                    }
                    outputStream.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    FileUtils.closeQuietly(inputStream);
                    FileUtils.closeQuietly(outputStream);
                }
                path = sharedFileCopy.getPath();
                VLog.e("wxd", " parseInstallRequest sharedFileCopy path : " + path);
            }
            return path;
        }
        private boolean handleInstallRequest(Intent intent) {
            VirtualCore.AppRequestListener listener = VirtualCore.get().getAppRequestListener();
            if (listener != null) {
                Uri packageUri = intent.getData();
                if (SCHEME_FILE.equals(packageUri.getScheme())) {
                    File sourceFile = new File(packageUri.getPath());
                    String path = NativeEngine.getRedirectedPath(sourceFile.getAbsolutePath());
                    listener.onRequestInstall(path);
                    return true;
                } else if (SCHEME_CONTENT.equals(packageUri.getScheme())) {
                    InputStream inputStream = null;
                    OutputStream outputStream = null;
                    File sharedFileCopy = new File(getHostContext().getCacheDir(), packageUri.getLastPathSegment());
                    try {
                        inputStream = getHostContext().getContentResolver().openInputStream(packageUri);
                        outputStream = new FileOutputStream(sharedFileCopy);
                        byte[] buffer = new byte[1024];
                        int count;
                        while ((count = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, count);
                        }
                        outputStream.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        FileUtils.closeQuietly(inputStream);
                        FileUtils.closeQuietly(outputStream);
                    }
                    listener.onRequestInstall(sharedFileCopy.getPath());
                    sharedFileCopy.delete();
                    return true;
                }
            }
            return false;
        }

        private boolean handleUninstallRequest(Intent intent) {
            VirtualCore.AppRequestListener listener = VirtualCore.get().getAppRequestListener();
            if (listener != null) {
                Uri packageUri = intent.getData();
                if (SCHEME_PACKAGE.equals(packageUri.getScheme())) {
                    String pkg = packageUri.getSchemeSpecificPart();
                    listener.onRequestUninstall(pkg);
                    return true;
                }

            }
            return false;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class StartActivities extends MethodProxy {

        @Override
        public String getMethodName() {
            return "startActivities";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            new Exception().printStackTrace();
            Intent[] intents = ArrayUtils.getFirst(args, Intent[].class);
            String[] resolvedTypes = ArrayUtils.getFirst(args, String[].class);
            IBinder token = null;
            int tokenIndex = ArrayUtils.indexOfObject(args, IBinder.class, 2);
            if (tokenIndex != -1) {
                token = (IBinder) args[tokenIndex];
            }
            Bundle options = ArrayUtils.getFirst(args, Bundle.class);
            return VActivityManager.get().startActivities(intents, resolvedTypes, token, options, VUserHandle.myUserId());
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class ShouldUpRecreateTask extends MethodProxy {

        @Override
        public String getMethodName() {
            return "shouldUpRecreateTask";
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

    static class GetCallingPackage extends MethodProxy {
        private static final String LIMBUS_UNITY_ACTIVITY = "com.unity3d.player.UnityPlayerActivity";

        @Override
        public String getMethodName() {
            return "getCallingPackage";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
            if (LIMBUS_PACKAGE.equals(getAppPkg()) && HCallbackStub.isClientLocalActivityToken(token)) {
                Log.i("LimbusVA", "getCallingPackage client-local token=" + token
                        + " -> " + LIMBUS_PACKAGE + "/" + LIMBUS_UNITY_ACTIVITY);
                return LIMBUS_PACKAGE;
            }
            return VActivityManager.get().getCallingPackage(token);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPackageForIntentSender extends MethodProxy {
        @Override
        public String getMethodName() {
            return "getPackageForIntentSender";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IInterface sender = (IInterface) args[0];
            if (sender != null) {
                IntentSenderData data = VActivityManager.get().getIntentSender(sender.asBinder());
                if (data != null) {
                    return data.creator;
                }
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    @SuppressWarnings("unchecked")
    static class PublishContentProviders extends MethodProxy {

        @Override
        public String getMethodName() {
            return "publishContentProviders";
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


    static class GetServices extends MethodProxy {
        @Override
        public String getMethodName() {
            return "getServices";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int maxNum = (int) args[0];
            int flags = (int) args[1];
            return VActivityManager.get().getServices(maxNum, flags).getList();
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class GrantUriPermissionFromOwner extends MethodProxy {

        @Override
        public String getMethodName() {
            return "grantUriPermissionFromOwner";
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

    static class SetServiceForeground extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setServiceForeground";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName component = (ComponentName) args[0];
            if(getHostPkg().equals(component.getPackageName())){
                return method.invoke(who, args);
            }
            if (!getConfig().isAllowServiceStartForeground(getAppPkg())) {
                return 0;
            }
            IBinder token = (IBinder) args[1];
            int id = (int) args[2];
            Notification notification = (Notification) args[3];
            boolean removeNotification = false;
            if (args[4] instanceof Boolean) {
                removeNotification = (boolean) args[4];
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && args[4] instanceof Integer) {
                int flags = (int) args[4];
                removeNotification = (flags & Service.STOP_FOREGROUND_REMOVE) != 0;
            } else {
                VLog.e(getClass().getSimpleName(), "Unknown flag : " + args[4]);
                removeNotification = (id == 0);
            }
            if (removeNotification) {
                VActivityManager.get().setServiceForeground(component, getAppUserId(), 0, null, true);
                return 0;
            }

            fixSmallIcon(notification, component);
            VNotificationManager.Result result = VNotificationManager.get().dealNotification(id, notification, getAppPkg(), getAppUserId());
            if (result.mode == VNotificationManager.MODE_NONE) {
                notification = new Notification();
                notification.icon = getHostContext().getApplicationInfo().icon;
            } else if (result.mode == VNotificationManager.MODE_REPLACED) {
                notification = result.notification;
            }
            /**
             * `BaseStatusBar#updateNotification` aosp will use use
             * `new StatusBarIcon(...notification.getSmallIcon()...)`
             *  while in samsung SystemUI.apk ,the corresponding code comes as
             * `new StatusBarIcon(...pkgName,notification.icon...)`
             * the icon comes from `getSmallIcon.getResource`
             * which will throw an exception on :x process thus crash the application
             */
            if (notification != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    (Build.BRAND.equalsIgnoreCase("samsung") || Build.MANUFACTURER.equalsIgnoreCase("samsung"))) {
                notification.icon = getHostContext().getApplicationInfo().icon;
                Icon icon = Icon.createWithResource(getHostPkg(), notification.icon);
                Reflect.on(notification).call("setSmallIcon", icon);
            }
            id = VNotificationManager.get().dealNotificationId(id, component.getPackageName(), null, 0);
            String tag = VNotificationManager.get().dealNotificationTag(id, component.getPackageName(), null, 0);
            VNotificationManager.get().addNotification(id, tag, component.getPackageName(), getAppUserId());
            VActivityManager.get().setServiceForeground(component, getAppUserId(), id, tag, false);
            try {
                NotificationManager nm = (NotificationManager) VirtualCore.get().getContext()
                        .getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(tag, id, notification);
            } catch (Throwable e) {
                e.printStackTrace();
            }
//            VActivityManager.get().setServiceForeground(component, token, id, notification, removeNotification);
            return 0;
        }

        private void fixSmallIcon(Notification notification, ComponentName component) {
            if (notification != null) {
                Context appContext = null;
                try {
                    appContext = getHostContext().createPackageContext(component.getPackageName(),
                            Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    Icon icon = Icon.createWithResource(appContext.getPackageName(), appContext.getApplicationInfo().icon);
                    Reflect.on(notification).call("setSmallIcon", icon);
                }
            }
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class UpdateDeviceOwner extends MethodProxy {

        @Override
        public String getMethodName() {
            return "updateDeviceOwner";
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


    static class GetIntentForIntentSender extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getIntentForIntentSender";
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) {
            Intent intent = (Intent) result;
            if (intent != null) {
                Intent targetIntent = ComponentUtils.getIntentForIntentSender(intent);
                if (targetIntent != null) {
                    return targetIntent;
                }
            }
            return intent;
        }
    }


    static class UnbindFinished extends MethodProxy {

        @Override
        public String getMethodName() {
            return "unbindFinished";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
//            Intent service = (Intent) args[1];
//            boolean doRebind = (boolean) args[2];
            if (token instanceof ServiceRecord) {
                return 0;
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }

    static class StartActivityIntentSender extends MethodProxy {
        @Override
        public String getMethodName() {
            return "startActivityIntentSender";
        }

        /*
        public int startActivityIntentSender(IApplicationThread caller, IntentSender intent,
                    Intent fillInIntent, String resolvedType, IBinder resultTo, String resultWho,
                    int requestCode, int flagsMask, int flagsValues, Bundle options)

        public int startActivityIntentSender(IApplicationThread caller, IIntentSender target,
                    IBinder whitelistToken, Intent fillInIntent, String resolvedType, IBinder resultTo,
                    String resultWho, int requestCode, int flagsMask, int flagsValues, Bundle bOptions)
         */
        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int intentIndex;
            int resultToIndex;
            int resultWhoIndex;
            int optionsIndex;
            int requestCodeIndex;
            int flagsMaskIndex;
            int flagsValuesIndex;
            if (BuildCompat.isOreo()) {
                intentIndex = 3;
                resultToIndex = 5;
                resultWhoIndex = 6;
                requestCodeIndex = 7;
                flagsMaskIndex = 8;
                flagsValuesIndex = 9;
                optionsIndex = 10;
            } else {
                intentIndex = 2;
                resultToIndex = 4;
                resultWhoIndex = 5;
                requestCodeIndex = 6;
                flagsMaskIndex = 7;
                flagsValuesIndex = 8;
                optionsIndex = 9;
            }
            Object target = args[1];
            Intent originFillIn = (Intent) args[intentIndex];
            IBinder resultTo = (IBinder) args[resultToIndex];
            String resultWho = (String) args[resultWhoIndex];
            int requestCode = (int) args[requestCodeIndex];
            Bundle options = (Bundle) args[optionsIndex];
            int flagsMask = (int) args[flagsMaskIndex];
            int flagsValues = (int) args[flagsValuesIndex];
            if (LIMBUS_PACKAGE.equals(getAppPkg())) {
                Log.i("LimbusVA", "startActivityIntentSender app=" + getAppPkg()
                        + " callingPackageArg=" + (args.length > 2 ? args[2] : null)
                        + " fillIn=" + originFillIn
                        + " resultTo=" + resultTo
                        + " requestCode=" + requestCode);
            }
            Intent fillIn = new Intent();
            IInterface sender;
            if (target instanceof IInterface) {
                sender = (IInterface) target;
            } else {
                sender = mirror.android.content.IntentSender.mTarget.get(target);
            }
            IBinder senderToken = sender.asBinder();
            IntentSenderExtData ext = new IntentSenderExtData(senderToken, originFillIn, resultTo, resultWho, requestCode, options, flagsMask, flagsValues);
            fillIn.putExtra("_VA_|_ext_", ext);
            IntentSenderData data = VActivityManager.get().getIntentSender(senderToken);
            if (data != null) {
                data.updateResult(originFillIn, resultTo, resultWho, requestCode, options, flagsMask, flagsValues);
                VActivityManager.get().addOrUpdateIntentSender(data);
                if (LIMBUS_PACKAGE.equals(getAppPkg())) {
                    Log.i("LimbusVA", "Stored IntentSender result bridge sender=" + senderToken
                            + " resultTo=" + resultTo + " requestCode=" + requestCode
                            + " fillIn=" + originFillIn);
                }
            } else if (LIMBUS_PACKAGE.equals(getAppPkg())) {
                Log.w("LimbusVA", "Unable to store IntentSender result bridge; missing sender data sender="
                        + senderToken + " resultTo=" + resultTo + " requestCode=" + requestCode);
            }
            args[intentIndex] = fillIn;
            return super.call(who, method, args);
        }
    }

    static class SendIntentSender extends MethodProxy {

        @Override
        public String getMethodName() {
            return "sendIntentSender";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IInterface sender = (IInterface) args[0];
            // after [parameter] code
            int intentIndex = ArrayUtils.indexOfObject(args, Integer.class, 1) + 1;
            Intent fillIn = (Intent) args[intentIndex];
            Bundle options = (Bundle) args[args.length - 1];
            int permissionIndex = args.length - 2;
            if (args[permissionIndex] instanceof String) {
                args[permissionIndex] = null;
            }
            if (fillIn != null) {
                IntentSenderExtData ext = new IntentSenderExtData(sender.asBinder(), fillIn, null, null, 0, options, 0, 0);
                Intent newFillIn = new Intent();
                newFillIn.setExtrasClassLoader(IntentSenderExtData.class.getClassLoader());
                newFillIn.putExtra("_VA_|_ext_", ext);
                args[intentIndex] = newFillIn;
            }
            return super.call(who, method, args);
        }
    }

    static class GetAppTasks extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getAppTasks";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return super.call(who, method, args);
        }
    }

    static class BindServiceQ extends BindService {

        @Override
        public String getMethodName() {
            return "bindIsolatedService";
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }

    static class BindServiceInstance extends BindService {

        @Override
        public String getMethodName() {
            return "bindServiceInstance";
        }
    }

    static class BindService extends MethodProxy {

        @Override
        public String getMethodName() {
            return "bindService";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Intent service = (Intent) args[2];
            String resolvedType = (String) args[3];
            IServiceConnection conn = (IServiceConnection) args[4];
            int flags = ((Number) args[5]).intValue();
            int userId = VUserHandle.myUserId();
            // Every eventual call below crosses into the real ActivityManager under the host
            // Linux UID, including virtual services that are represented by a host stub Intent.
            // Android 13 bindServiceInstance inserts instanceName before callingPackage, so a
            // fixed args[6] rewrite misses Chromium/WebView's BIND_EXTERNAL_SERVICE path.
            String oldCallingPackage = MethodParameterUtils.replaceFirstAppPkg(args);
            if (oldCallingPackage != null && !MethodProxy.getHostPkg().equals(oldCallingPackage)) {
                Log.i("LimbusVA", getMethodName() + " rewrite system bind caller old="
                        + oldCallingPackage + " new=" + MethodProxy.getHostPkg()
                        + " app=" + MethodProxy.getAppPkg());
            }
            if (LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())) {
                // Install this before every early-return and resolution branch. Vendor ROMs may
                // rewrite the service Intent, while Android 14 renamed the instance entry point;
                // the delegate can still recognize IGmsServiceBroker from its Binder descriptor.
                args[4] = ServiceConnectionDelegate.getOrCreateDelegate(
                        conn,
                        getExplicitServiceComponent(service));
                Log.i("LimbusVA", getMethodName() + " intercept Limbus service="
                        + describeIntentForLog(service)
                        + " flags=" + args[5]
                        + " signature=" + method.toGenericString());
            }
            if (isHostIntent(service)) {
                return method.invoke(who, args);
            }
            if (isServerProcess()) {
                userId = service.getIntExtra("_VA_|_user_id_", VUserHandle.USER_NULL);
            }
            if (userId == VUserHandle.USER_NULL) {
                return method.invoke(who, args);
            }
            if (isLimbusPlayBillingService(service)) {
                Intent hostBillingIntent = createHostPlayBillingIntent(service);
                ResolveInfo hostBilling = VirtualCore.get().getUnHookPackageManager().resolveService(hostBillingIntent, 0);
                if (hostBilling != null
                        && hostBilling.serviceInfo != null
                        && PLAY_STORE_PACKAGE.equals(hostBilling.serviceInfo.packageName)) {
                    ComponentName component = new ComponentName(hostBilling.serviceInfo.packageName, hostBilling.serviceInfo.name);
                    Log.i("LimbusVA", getMethodName() + " route Limbus billing to host Play Store service="
                            + component + " flags=" + flags
                            + " virtualIntent=" + describeIntentForLog(service)
                            + " hostIntent=" + describeIntentForLog(hostBillingIntent));
                    // 系统绑定必须使用宿主重新解析出的真实组件，不能继续携带 FakeStore 的旧类名。
                    hostBillingIntent.setComponent(component);
                    args[2] = hostBillingIntent;
                    args[4] = ServiceConnectionDelegate.getOrCreateDelegate(conn, component);
                    if (args.length > 6 && args[6] instanceof String && !MethodProxy.getHostPkg().equals(args[6])) {
                        Log.i("LimbusVA", getMethodName() + " rewrite Limbus billing bind caller old="
                                + args[6] + " new=" + MethodProxy.getHostPkg());
                        args[6] = MethodProxy.getHostPkg();
                    }
                    return method.invoke(who, args);
                }
                Log.w("LimbusVA", getMethodName() + " host Play Store billing service unavailable; fallback to virtual service intent="
                        + describeIntentForLog(service));
            }
            ServiceInfo serviceInfo = VirtualCore.get().resolveServiceInfo(service, userId);
            if (serviceInfo != null) {
                if (LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                        || LIMBUS_PACKAGE.equals(serviceInfo.packageName)) {
                    Log.i("LimbusVA", getMethodName() + " resolved virtual service app=" + MethodProxy.getAppPkg()
                            + " service=" + serviceInfo.packageName + "/" + serviceInfo.name
                            + " process=" + serviceInfo.processName
                            + " flags=" + flags
                            + " intent=" + describeIntentForLog(service));
                }
                ComponentName component = new ComponentName(serviceInfo.packageName, serviceInfo.name);
                ServiceConnectionDelegate delegate = ServiceConnectionDelegate.getOrCreateDelegate(conn, component);
                Intent proxyIntent = VActivityManager.get().bindService(userId, service, serviceInfo, conn.asBinder(), flags);
                if (proxyIntent == null) {
                    if (LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                            || LIMBUS_PACKAGE.equals(serviceInfo.packageName)) {
                        Log.w("LimbusVA", getMethodName() + " virtual service proxy null service="
                                + serviceInfo.packageName + "/" + serviceInfo.name
                                + " process=" + serviceInfo.processName);
                    }
                    return 0;
                }
                if (LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                        || LIMBUS_PACKAGE.equals(serviceInfo.packageName)) {
                    Log.i("LimbusVA", getMethodName() + " proxy service intent="
                            + describeIntentForLog(proxyIntent));
                }
                args[2] = proxyIntent;
                args[4] = delegate;
                args[5] = autoCreateBindFlag(args[5]);
                return method.invoke(who, args);
            }
            ResolveInfo resolveInfo = VirtualCore.get().getUnHookPackageManager().resolveService(service, 0);
            if (resolveInfo == null || !isVisibleServiceForCurrentApp(resolveInfo.serviceInfo)) {
                return 0;
            }
            if (LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())
                    && shouldExposeHostGoogleServiceToLimbus(resolveInfo.serviceInfo.packageName)) {
                ComponentName component = new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
                args[4] = ServiceConnectionDelegate.getOrCreateDelegate(conn, component);
            }
            rewriteCallingPackageForHostGoogleService(args, resolveInfo.serviceInfo, 6, "bindService");
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }

    static class StartService extends MethodProxy {

        @Override
        public String getMethodName() {
            return "startService";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Intent service = (Intent) args[1];
            String resolvedType = (String) args[2];
            if (service == null) {
                return null;
            }
            if (isHostIntent(service)) {
                return method.invoke(who, args);
            }
            int userId = VUserHandle.myUserId();
            if (isServerProcess()) {
                userId = service.getIntExtra("_VA_|_user_id_", VUserHandle.USER_NULL);
            }
            service.setDataAndType(service.getData(), resolvedType);
            ServiceInfo serviceInfo = VirtualCore.get().resolveServiceInfo(service, VUserHandle.myUserId());
            if (serviceInfo != null) {
                return VActivityManager.get().startService(userId, service);
            }
            ResolveInfo resolveInfo = VirtualCore.get().getUnHookPackageManager().resolveService(service, 0);
            if (resolveInfo == null || !isVisibleServiceForCurrentApp(resolveInfo.serviceInfo)) {
                return null;
            }
            rewriteCallingPackageForHostGoogleService(args, resolveInfo.serviceInfo, 4, "startService");
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }

    static class StartActivityAndWait extends StartActivity {
        @Override
        public String getMethodName() {
            return "startActivityAndWait";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return super.call(who, method, args);
        }
    }


    static class PublishService extends MethodProxy {

        @Override
        public String getMethodName() {
            return "publishService";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
//            Intent intent = (Intent) args[1];
//            IBinder service = (IBinder) args[2];
            if (token instanceof ServiceRecord) {
                return 0;
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    @SuppressWarnings("unchecked")
    static class GetRunningAppProcesses extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getRunningAppProcesses";
        }

        @Override
        public synchronized Object call(Object who, Method method, Object... args) throws Throwable {
            if (!VClient.get().isEnvironmentPrepared()) {
                return method.invoke(who, args);
            }
            List<ActivityManager.RunningAppProcessInfo> _infoList = (List<ActivityManager.RunningAppProcessInfo>) method
                    .invoke(who, args);
            if (_infoList == null) {
                return null;
            }
            List<AppRunningProcessInfo> appProcessList = VActivityManager.get().getRunningAppProcesses(getAppPkg(), getAppUserId());
//            VLog.d("VActivityManager", "getRunningAppProcesses:%s", appProcessList);
            List<ActivityManager.RunningAppProcessInfo> infoList = new ArrayList<>(_infoList);
            Iterator<ActivityManager.RunningAppProcessInfo> it = infoList.iterator();
            while (it.hasNext()) {
                ActivityManager.RunningAppProcessInfo info = it.next();
                if (info.uid == getRealUid()) {
                    AppRunningProcessInfo target = null;
                    for (AppRunningProcessInfo process : appProcessList) {
                        if (process.pid == info.pid) {
                            target = process;
                            break;
                        }
                    }
                    if (target != null) {
                        List<String> pkgList = target.pkgList;
                        String processName = target.processName;
                        if (processName != null) {
                            info.importanceReasonCode = 0;
                            info.importanceReasonPid = 0;
                            info.importanceReasonComponent = null;
                            info.processName = processName;
                        }
                        info.pkgList = pkgList.toArray(new String[0]);
                        info.uid = target.vuid;
                    } else {
                        it.remove();
                    }
                }
            }
            return infoList;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class SetPackageAskScreenCompat extends MethodProxy {

        @Override
        public String getMethodName() {
            return "setPackageAskScreenCompat";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (args.length > 0 && args[0] instanceof String) {
                    args[0] = getHostPkg();
                }
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetCallingActivity extends MethodProxy {
        private static final String LIMBUS_UNITY_ACTIVITY = "com.unity3d.player.UnityPlayerActivity";

        @Override
        public String getMethodName() {
            return "getCallingActivity";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
            if (LIMBUS_PACKAGE.equals(getAppPkg()) && HCallbackStub.isClientLocalActivityToken(token)) {
                ComponentName caller = new ComponentName(LIMBUS_PACKAGE, LIMBUS_UNITY_ACTIVITY);
                Log.i("LimbusVA", "getCallingActivity client-local token=" + token
                        + " -> " + caller.flattenToString());
                return caller;
            }
            if ("com.google.android.gms".equals(getAppPkg()) && HCallbackStub.isClientLocalActivityToken(token)) {
                ComponentName caller = new ComponentName(
                        "com.google.android.gms",
                        "org.microg.gms.auth.signin.AssistedSignInActivity");
                Log.i("LimbusVA", "getCallingActivity microG client-local token=" + token
                        + " -> " + caller.flattenToString());
                return caller;
            }
            return VActivityManager.get().getCallingActivity(token);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetCurrentUser extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getCurrentUser";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            try {
                return UserInfo.ctor.newInstance(0, "user", VUserInfo.FLAG_PRIMARY);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return null;
        }
    }


    static class KillApplicationProcess extends MethodProxy {

        @Override
        public String getMethodName() {
            return "killApplicationProcess";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (args.length > 1 && args[0] instanceof String && args[1] instanceof Integer) {
                String processName = (String) args[0];
                int uid = (int) args[1];
                VActivityManager.get().killApplicationProcess(processName, uid);
                return 0;
            }
            return 0;//method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class KillBackgroundProcesses extends MethodProxy {
        @Override
        public String getMethodName() {
            return "killBackgroundProcesses";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (args[0] instanceof String) {
                String pkg = (String) args[0];
                VActivityManager.get().killAppByPkg(pkg, getAppUserId());
                return 0;
            }
            return super.call(who, method, args);
        }
    }


    static class StartActivityAsUser extends StartActivity {

        @Override
        public String getMethodName() {
            return "startActivityAsUser";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return super.call(who, method, args);
        }
    }


    static class CheckPermission extends MethodProxy {

        @Override
        public String getMethodName() {
            return "checkPermission";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String permission = (String) args[0];
            int pid = (int) args[1];
            int uid = (int) args[2];
            
            if ("com.android.providers.telephony".equals(getAppPkg())) {
                if ("android.permission.WRITE_APN_SETTINGS".equals(permission)) {
                    //
                    String pkg = VPackageManager.get().getNameForUid(uid);
                    if (pkg != null && (InstallerSetting.DIALER_PKG.equals(pkg)
                            || InstallerSetting.MESSAGING_PKG.equals(pkg)
                            || InstallerSetting.systemApps.contains(pkg))) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                }
            }
            return VActivityManager.get().checkPermission(permission, pid, uid);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

    }

    static class CheckPermissionWithToken extends MethodProxy {

        @Override
        public String getMethodName() {
            return "checkPermissionWithToken";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String permission = (String) args[0];
            int pid = (int) args[1];
            int uid = (int) args[2];
            return VActivityManager.get().checkPermission(permission, pid, uid);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

    }


    static class StartActivityAsCaller extends StartActivity {

        @Override
        public String getMethodName() {
            return "startActivityAsCaller";
        }
    }


    static class HandleIncomingUser extends MethodProxy {

        @Override
        public String getMethodName() {
            return "handleIncomingUser";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int lastIndex = args.length - 1;
            if (args[lastIndex] instanceof String) {
                args[lastIndex] = getHostPkg();
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

    }


    @SuppressWarnings("unchecked")
    static class GetTasks extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getTasks";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            List<ActivityManager.RunningTaskInfo> runningTaskInfos = (List<ActivityManager.RunningTaskInfo>) method
                    .invoke(who, args);
            for (ActivityManager.RunningTaskInfo info : runningTaskInfos) {
                AppTaskInfo taskInfo = VActivityManager.get().getTaskInfo(info.id);
                if (taskInfo != null) {
                    info.topActivity = taskInfo.topActivity;
                    info.baseActivity = taskInfo.baseActivity;
                }
            }
            return runningTaskInfos;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPersistedUriPermissions extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPersistedUriPermissions";
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


    static class RegisterReceiver extends MethodProxy {
        private static final int IDX_IIntentReceiver = Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                ? 2
                : 1;

        private static final int IDX_RequiredPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                ? 4
                : 3;
        private static final int IDX_IntentFilter = Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                ? 3
                : 2;

        private WeakHashMap<IBinder, IIntentReceiver> mProxyIIntentReceivers = new WeakHashMap<>();

        @Override
        public String getMethodName() {
            return "registerReceiver";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            replaceCallerPackageArgs(args);
            replaceFirstUserId(args);
            int intentFilterIndex = indexOfFirst(args, IntentFilter.class);
            int intentReceiverIndex = indexOfFirst(args, IIntentReceiver.class);
            int requiredPermissionIndex = intentFilterIndex >= 0 && args.length > intentFilterIndex + 1
                    && args[intentFilterIndex + 1] instanceof String ? intentFilterIndex + 1 : IDX_RequiredPermission;
            if (requiredPermissionIndex >= 0 && requiredPermissionIndex < args.length) {
                args[requiredPermissionIndex] = null;
            }
            int filterIndex = intentFilterIndex >= 0 ? intentFilterIndex : IDX_IntentFilter;
            IntentFilter filter = (IntentFilter) args[filterIndex];
            if (filter == null) {
                return method.invoke(who, args);
            }
            filter = new IntentFilter(filter);
            SpecialComponentList.protectIntentFilter(filter);
            args[filterIndex] = filter;
            int receiverIndex = intentReceiverIndex >= 0 ? intentReceiverIndex : IDX_IIntentReceiver;
            if (args.length > receiverIndex && IIntentReceiver.class.isInstance(args[receiverIndex])) {
                final IInterface old = (IInterface) args[receiverIndex];
                if (!IIntentReceiverProxy.class.isInstance(old)) {
                    final IBinder token = old.asBinder();
                    if (token != null) {
                        token.linkToDeath(new IBinder.DeathRecipient() {
                            @Override
                            public void binderDied() {
                                token.unlinkToDeath(this, 0);
                                mProxyIIntentReceivers.remove(token);
                            }
                        }, 0);
                        IIntentReceiver proxyIIntentReceiver = mProxyIIntentReceivers.get(token);
                        if (proxyIIntentReceiver == null) {
                            proxyIIntentReceiver = new IIntentReceiverProxy(old, filter);
                            mProxyIIntentReceivers.put(token, proxyIIntentReceiver);
                        }
                        WeakReference mDispatcher = LoadedApk.ReceiverDispatcher.InnerReceiver.mDispatcher.get(old);
                        if (mDispatcher != null) {
                            LoadedApk.ReceiverDispatcher.mIIntentReceiver.set(mDispatcher.get(), proxyIIntentReceiver);
                            args[receiverIndex] = proxyIIntentReceiver;
                        }
                    }
                }
            }
            return method.invoke(who, args);
        }

        protected void replaceCallerPackageArgs(Object... args) {
            MethodParameterUtils.replaceFirstAppPkg(args);
        }

        private int indexOfFirst(Object[] args, Class<?> type) {
            for (int i = 0; i < args.length; i++) {
                if (type.isInstance(args[i])) {
                    return i;
                }
            }
            return -1;
        }


        @Override
        public boolean isEnable() {
            return isAppProcess();
        }

        private static class IIntentReceiverProxy extends IIntentReceiver.Stub {

            IInterface mOld;
            IntentFilter mFilter;

            IIntentReceiverProxy(IInterface old, IntentFilter filter) {
                this.mOld = old;
                this.mFilter = filter;
            }

            public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered,
                                       boolean sticky, int sendingUser) {
                //解决税信灭屏幕启动Activity
                if(intent.getAction().equals(Intent.ACTION_SCREEN_OFF)){
                    controllerManager.setActivitySwitch(false);
                }else if(intent.getAction().equals(Intent.ACTION_SCREEN_ON)){
                    controllerManager.setActivitySwitch(true);
                }

                Bundle extraData = intent.getExtras();
                BroadcastIntentData intentData = null;
                if (extraData != null) {
                    extraData.setClassLoader(BroadcastIntentData.class.getClassLoader());
                    intentData = extraData.getParcelable("_VA_|_data_");
                    if(extraData.containsKey("_hasResult_")) {
                        resultCode = extraData.getInt("_VA_|_resultCode_", resultCode);
                        data = extraData.getString("_VA_|_resultData_", data);
                        extras = extraData.getBundle("_VA_|_resultExtras_");
                    }
                }
                if (intentData != null) {
                    if (intentData.userId >= 0 && intentData.userId != VUserHandle.myUserId()) {
                        return;
                    }
                    intent = intentData.intent;
                } else {
                    SpecialComponentList.unprotectIntent(intent);
                }
                if (isFakeLocationEnable()) {
                    if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                        intent.putExtra("resultsUpdated", false);
                    } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                    }
                }
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN) {
                    IIntentReceiverJB.performReceive.call(mOld, intent, resultCode, data, extras, ordered, sticky, sendingUser);
                } else {
                    mirror.android.content.IIntentReceiver.performReceive.call(mOld, intent, resultCode, data, extras, ordered, sticky);
                }
            }

            @SuppressWarnings("unused")
            public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered,
                                       boolean sticky) {
                this.performReceive(intent, resultCode, data, extras, ordered, sticky, 0);
            }

        }
    }

    static class RegisterReceiverWithFeature extends RegisterReceiver {
        @Override
        public String getMethodName() {
            return "registerReceiverWithFeature";
        }

        @Override
        protected void replaceCallerPackageArgs(Object... args) {
            if (args.length > 1 && args[1] instanceof String) {
                args[1] = getHostPkg();
            }
            if (args.length > 2 && args[2] instanceof String) {
                args[2] = null;
            }
            if (args.length > 3 && args[3] instanceof String) {
                args[3] = null;
            }
        }
    }


    static class StopService extends MethodProxy {

        @Override
        public String getMethodName() {
            return "stopService";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            intent.setDataAndType(intent.getData(), resolvedType);
            PackageManager pm = VirtualCore.getPM();
            ServiceInfo serviceInfo = null;
            ResolveInfo resolveInfo = pm.resolveService(intent, 0);
            if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                serviceInfo = resolveInfo.serviceInfo;
            }
            if (serviceInfo != null) {
                ComponentName component = ComponentUtils.toComponentName(serviceInfo);
                int res = VActivityManager.get().onServiceStop(getAppUserId(), component, -1);
                if (res < 0) {
                    return 1;
                } else if (res == 0) {
                    return 0;
                } else {
                    VActivityManager.get().stopService(getAppUserId(), serviceInfo);
                    return 1;
                }

            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }


    static class GetContentProvider extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getContentProvider";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int nameIdx = getProviderNameIndex();
            String name = (String) args[nameIdx];
            if (LIMBUS_PACKAGE.equals(getAppPkg()) && "com.google.android.gms.chimera".equals(name)) {
                Log.i("LimbusVA", "Allow host GMS chimera provider for Limbus dynamic service resolution");
            }

            if ((name.startsWith(StubManifest.STUB_CP_AUTHORITY)
                    || name.startsWith(StubManifest.STUB_CP_AUTHORITY_64BIT)
                    || name.equals(getConfig().get64bitHelperAuthority()))
                    || name.equals(getConfig().getBinderProviderAuthority())) {
                return method.invoke(who, args);
            }
            if (BuildCompat.isQ()) {
                int pkgIdx = nameIdx - 1;
                if (args[pkgIdx] instanceof String) {
                    args[pkgIdx] = getHostPkg();
                }
            }
            int userId = VUserHandle.myUserId();
            ProviderInfo info = VPackageManager.get().resolveContentProvider(name, 0, userId);

            if (info != null && info.enabled && isAppPkg(info.packageName)
                    &&!name.equals("com.android.externalstorage.documents")) {

                if("com.secspace.app.explorer".equals(getAppPkg()) && name.equals("media")) {
                    VLog.w("VActivityManger", "Prevent com.secspace.app.explorer to access media.");
                    return null;
                }

                ClientConfig config = VActivityManager.get().initProcess(info.packageName, info.processName, userId, VActivityManager.PROCESS_TYPE_PROVIDER);
                if (config == null) {
                    return null;
                }
                args[nameIdx] = StubManifest.getStubAuthority(config.vpid, config.is64Bit);
                Object holder = method.invoke(who, args);
                if (holder == null) {
                    return null;
                }
                if (BuildCompat.isOreo()) {
                    IInterface provider = ContentProviderHolderOreo.provider.get(holder);
                    if (provider != null) {
                        provider = VActivityManager.get().acquireProviderClient(userId, info, getVUid(), android.os.Process.myPid(), getAppPkg());
                    }
                    if (provider == null) {
                        VLog.e("VActivityManager", "acquireProviderClient fail: " + name);
                        return null;
                    }
                    ContentProviderHolderOreo.provider.set(holder, provider);
                    ContentProviderHolderOreo.info.set(holder, info);
                } else {
                    IInterface provider = IActivityManager.ContentProviderHolder.provider.get(holder);
                    if (provider != null) {
                        provider = VActivityManager.get().acquireProviderClient(userId, info, getVUid(), android.os.Process.myPid(), getAppPkg());
                    }
                    if (provider == null) {
                        VLog.e("VActivityManager", "acquireProviderClient fail: " + name);
                        return null;
                    }
                    IActivityManager.ContentProviderHolder.provider.set(holder, provider);
                    IActivityManager.ContentProviderHolder.info.set(holder, info);
                }
                return holder;
            }
            VLog.w("VActivityManger", "getContentProvider:%s", name);
            Object holder = method.invoke(who, args);
            if (holder != null) {
                if (BuildCompat.isOreo()) {
                    IInterface provider = ContentProviderHolderOreo.provider.get(holder);
                    info = ContentProviderHolderOreo.info.get(holder);
                    if (provider != null) {
                        provider = ProviderHook.createProxy(true, name, provider);
                    }
                    ContentProviderHolderOreo.provider.set(holder, provider);
                } else {
                    IInterface provider = IActivityManager.ContentProviderHolder.provider.get(holder);
                    info = IActivityManager.ContentProviderHolder.info.get(holder);
                    if (provider != null) {
                        provider = ProviderHook.createProxy(true, name, provider);
                    }
                    IActivityManager.ContentProviderHolder.provider.set(holder, provider);
                }
                return holder;
            }
            return null;
        }


        public int getProviderNameIndex() {
            if (BuildCompat.isQ()) {
                return 2;
            }
            return 1;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    static class SetTaskDescription extends MethodProxy {
        @Override
        public String getMethodName() {
            return "setTaskDescription";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            String label = td.getLabel();
            Bitmap icon = td.getIcon();

            // If the activity label/icon isn't specified, the application's label/icon is shown instead
            // Android usually does that for us, but in this case we want info about the contained app, not VIrtualApp itself
            if (label == null || icon == null) {
                Application app = VClient.get().getCurrentApplication();
                if (app != null) {
                    try {
                        if (label == null) {
                            label = app.getApplicationInfo().loadLabel(app.getPackageManager()).toString();
                        }
                        if (icon == null) {
                            Drawable drawable = app.getApplicationInfo().loadIcon(app.getPackageManager());
                            if (drawable != null) {
                                icon = DrawableUtils.drawableToBitMap(drawable);
                            }
                        }
                        td = new ActivityManager.TaskDescription(label, icon, td.getPrimaryColor());
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
            }

            TaskDescriptionDelegate descriptionDelegate = VirtualCore.get().getTaskDescriptionDelegate();
            if (descriptionDelegate != null) {
                td = descriptionDelegate.getTaskDescription(td);
            }

            args[1] = td;
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class StopServiceToken extends MethodProxy {

        @Override
        public String getMethodName() {
            return "stopServiceToken";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            ComponentName component = (ComponentName) args[0];
            IBinder token = (IBinder) args[1];
            int startId = (int) args[2];
            if (token instanceof ServiceRecord) {
                int res = VActivityManager.get().onServiceStop(getAppUserId(), component, startId);
                if (res < 0) {
                    return true;
                } else if (res == 0) {
                    return false;
                } else {
                    ServiceManager.get().stopService(component);
                    return true;
                }
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess() || isServerProcess();
        }
    }

    static class StartActivityWithConfig extends StartActivity {
        @Override
        public String getMethodName() {
            return "startActivityWithConfig";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return super.call(who, method, args);
        }
    }

    static class StartNextMatchingActivity extends StartActivity {
        @Override
        public String getMethodName() {
            return "startNextMatchingActivity";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return false;
        }
    }


    static class BroadcastIntent extends MethodProxy {

        @Override
        public String getMethodName() {
            return "broadcastIntent";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Intent intent = (Intent) args[1];
            String type = (String) args[2];
            intent.setDataAndType(intent.getData(), type);
            Intent newIntent = handleIntent(intent);
            if (newIntent != null) {
                args[1] = newIntent;
                VLog.v("kk", "send broadcast " + intent + "=>" + newIntent);
            } else {
                return 0;
            }
            if (args[7] instanceof String || args[7] instanceof String[]) {
                // clear the permission
                args[7] = null;
            }
            return method.invoke(who, args);
        }


        private Intent handleIntent(final Intent intent) {
            final String action = intent.getAction();
            if ("android.intent.action.CREATE_SHORTCUT".equals(action)
                    || "com.android.launcher.action.INSTALL_SHORTCUT".equals(action)
                    || "com.aliyun.homeshell.action.INSTALL_SHORTCUT".equals(action)) {

                return getConfig().isAllowCreateShortcut() ? handleInstallShortcutIntent(intent) : null;

            } else if ("com.android.launcher.action.UNINSTALL_SHORTCUT".equals(action)
                    || "com.aliyun.homeshell.action.UNINSTALL_SHORTCUT".equals(action)) {

                handleUninstallShortcutIntent(intent);

            } else if (BadgerManager.handleBadger(intent)) {
                return null;
                //已经加到SpecialComponentList#SYSTEM_BROADCAST_ACTION
//            } else if ("com.xdja.dialer.removecall".equals(action)) {
//                return intent;
            } else {
                return ComponentUtils.redirectBroadcastIntent(intent, VUserHandle.myUserId());
            }
            return intent;
        }

        private Intent handleMediaScannerIntent(Intent intent) {
            if (intent == null) {
                return null;
            }
            Uri data = intent.getData();
            if (data == null) {
                return intent;
            }
            String scheme = data.getScheme();
            if (!"file".equalsIgnoreCase(scheme)) {
                return intent;
            }
            String path = data.getPath();
            if (path == null) {
                return intent;
            }
            String newPath = NativeEngine.getRedirectedPath(path);
            File newFile = new File(newPath);
            if (!newFile.exists()) {
                return intent;
            }
            intent.setData(Uri.fromFile(newFile));
            return intent;
        }

        private Intent handleInstallShortcutIntent(Intent intent) {
            Intent shortcut = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
            if (shortcut != null) {
                ComponentName component = shortcut.resolveActivity(VirtualCore.getPM());
                if (component != null) {
                    String pkg = component.getPackageName();
                    Intent newShortcutIntent = new Intent();
                    newShortcutIntent.addCategory(Intent.CATEGORY_DEFAULT);
                    newShortcutIntent.setAction(Constants.ACTION_SHORTCUT);
                    newShortcutIntent.setPackage(getHostPkg());
                    newShortcutIntent.putExtra("_VA_|_intent_", shortcut);
                    newShortcutIntent.putExtra("_VA_|_uri_", shortcut.toUri(0));
                    newShortcutIntent.putExtra("_VA_|_user_id_", VUserHandle.myUserId());
                    intent.removeExtra(Intent.EXTRA_SHORTCUT_INTENT);
                    intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, newShortcutIntent);

                    Intent.ShortcutIconResource icon = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);
                    if (icon != null && !TextUtils.equals(icon.packageName, getHostPkg())) {
                        try {
                            Resources resources = VirtualCore.get().getResources(pkg);
                            int resId = resources.getIdentifier(icon.resourceName, "drawable", pkg);
                            if (resId > 0) {
                                //noinspection deprecation
                                Drawable iconDrawable = resources.getDrawable(resId);
                                Bitmap newIcon = BitmapUtils.drawableToBitmap(iconDrawable);
                                if (newIcon != null) {
                                    intent.removeExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);
                                    intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, newIcon);
                                }
                            }
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return intent;
        }

        private void handleUninstallShortcutIntent(Intent intent) {
            Intent shortcut = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
            if (shortcut != null) {
                ComponentName componentName = shortcut.resolveActivity(getPM());
                if (componentName != null) {
                    Intent newShortcutIntent = new Intent();
                    newShortcutIntent.putExtra("_VA_|_uri_", shortcut.toUri(0));
                    newShortcutIntent.setClassName(getHostPkg(), Constants.SHORTCUT_PROXY_ACTIVITY_NAME);
                    newShortcutIntent.removeExtra(Intent.EXTRA_SHORTCUT_INTENT);
                    intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, newShortcutIntent);
                }
            }
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetActivityClassForToken extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getActivityClassForToken";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
            return VActivityManager.get().getActivityForToken(token);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class GrantUriPermission extends MethodProxy {

        @Override
        public String getMethodName() {
            return "grantUriPermission";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            //add & change by lml@xdja.com
            {
                if (args.length > 2 && args[1] instanceof String) {
                    String targetPkg = (String)args[1];
                    //内部应用的uri，如果外部安装了同样的应用，会出现异常
                    //if (!isAppPkg(targetPkg)) {
                        for (int i = 0; i < args.length; i++) {
                            Object obj = args[i];
                            if (obj instanceof Uri) {
                                Uri uri = ComponentUtils.processOutsideUri(VUserHandle.myUserId(), VirtualCore.get().isPluginEngine(), (Uri) obj);
                                if (uri != null) {
                                    args[i] = uri;
                                }
                            }
                        }
                    //} else {
                        //return 0;
                    //}
                }
            }
			MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class CheckGrantUriPermission extends MethodProxy {

        @Override
        public String getMethodName() {
            return "checkGrantUriPermission";
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


    static class ServiceDoneExecuting extends MethodProxy {

        @Override
        public String getMethodName() {
            return "serviceDoneExecuting";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
//            int type = (int) args[1];
//            int startId = (int) args[2];
//            int res = (int) args[3];
            if (token instanceof ServiceRecord) {
                return 0;
            }
            return method.invoke(who, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class isUserRunning extends MethodProxy {
        @Override
        public String getMethodName() {
            return "isUserRunning";
        }

        @Override
        public Object call(Object who, Method method, Object... args) {
            int userId = (int) args[0];
            for (VUserInfo userInfo : VUserManager.get().getUsers()) {
                if (userInfo.id == userId) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }


    static class GetPackageProcessState extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPackageProcessState";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {

            return 4/*ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE*/;
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }
    //xdja
    static class OverridePendingTransition extends MethodProxy {
        @Override
        public String getMethodName() {
            return "overridePendingTransition";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return 0;
        }
    }
    //xdja

    static class TakePersistableUriPermission extends MethodProxy {
        @Override
        public String getMethodName() {
            return "takePersistableUriPermission";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Uri uri = (Uri) args[0];
            Uri newUri = DocumentHook.getOutsideUri(uri);
            if (uri != newUri) {
                args[0] = newUri;
            }
            return super.call(who, method, args);
        }
    }

    static class ActivityResumed extends MethodProxy{
        @Override
        public String getMethodName() {
            return "activityResumed";
        }
        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
            if (!HCallbackStub.isClientLocalActivityToken(token)) {
                VActivityManager.get().onActivityResumed(token);
            }
            return super.call(who, method, args);
        }
    }

    static class ActivityDestroyed extends MethodProxy{
        @Override
        public String getMethodName() {
            return "activityDestroyed";
        }
        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
            if (!HCallbackStub.removeClientLocalActivityToken(token)) {
                VActivityManager.get().onActivityDestroy(token);
            }
            return super.call(who, method, args);
        }
    }

    static class FinishActivity extends MethodProxy{
        @Override
        public String getMethodName() {
            return "finishActivity";
        }

        // add by lml@xdja.com
        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            int intentIndex = MethodParameterUtils.getIndex(args, Intent.class);
            if (intentIndex >= 0) {
                Intent intent = (Intent) args[intentIndex];
                if (intent != null && intent.getData() != null) {
                    Uri uri = intent.getData();
                    Uri newUri= null;
                    boolean needFix = true;
                    if("com.android.externalstorage.documents".equalsIgnoreCase(uri.getAuthority())){
                        newUri = DocumentHook.getOutsideUri(uri);
                        if(newUri != uri){
                            //外部sd卡的路径
                            needFix = false;
                        }
                    }
                    if(needFix) {
                        newUri = ComponentUtils.processOutsideUri(getAppUserId(), VirtualCore.get().isPluginEngine(), uri);
                    }
                    if (newUri != null && uri != newUri) {
                        //Log.i("kk-test", "newUri="+newUri);
                        intent.setDataAndType(newUri, intent.getType());
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                        }
                    }
//                    } else {
//                        ComponentUtils.processOutsideIntent(VUserHandle.myUserId(), VirtualCore.get().isPluginEngine(), intent);
//                    }
                }
            }
            return super.beforeCall(who, method, args);
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            IBinder token = (IBinder) args[0];
            boolean clientLocalActivity = HCallbackStub.isClientLocalActivityToken(token);
            if (!clientLocalActivity) {
                VActivityManager.get().onFinishActivity(token);
            }
            if (!clientLocalActivity && VActivityManager.get().includeExcludeFromRecentsFlag(token)) {
                //FINISH_TASK_WITH_ROOT_ACTIVITY
                args[3] = 1;
            }
            return super.call(who, method, args);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

    static class FinishActivityAffinity extends MethodProxy{
        @Override
        public String getMethodName() {
            return "finishActivityAffinity";
        }
        @Override
        public Object call(Object who, Method method, Object... args) {
            IBinder token = (IBinder) args[0];
            return VActivityManager.get().finishActivityAffinity(getAppUserId(), token);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }
}
