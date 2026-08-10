package com.lody.virtual.client.core;

import android.os.Build;

import com.lody.virtual.client.hook.base.MethodInvocationProxy;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.delegate.AppInstrumentation;
import com.lody.virtual.client.hook.proxies.accessibility.AccessibilityManagerStub;
import com.lody.virtual.client.hook.proxies.account.AccountManagerStub;
import com.lody.virtual.client.hook.proxies.alarm.AlarmManagerStub;
import com.lody.virtual.client.hook.proxies.am.ActivityManagerStub;
import com.lody.virtual.client.hook.proxies.am.HCallbackStub;
import com.lody.virtual.client.hook.proxies.app.WallpaperManagerStub;
import com.lody.virtual.client.hook.proxies.appops.AppOpsManagerStub;
import com.lody.virtual.client.hook.proxies.appops.FlymePermissionServiceStub;
import com.lody.virtual.client.hook.proxies.appops.SmtOpsManagerStub;
import com.lody.virtual.client.hook.proxies.appwidget.AppWidgetManagerStub;
import com.lody.virtual.client.hook.proxies.am.ActivityTaskManagerStub;
import com.lody.virtual.client.hook.proxies.audio.AudioManagerStub;
import com.lody.virtual.client.hook.proxies.backup.BackupManagerStub;
import com.lody.virtual.client.hook.proxies.battery_stats.BatteryStatsHub;
import com.lody.virtual.client.hook.proxies.bluetooth.BluetoothStub;
import com.lody.virtual.client.hook.proxies.clipboard.ClipBoardStub;
import com.lody.virtual.client.hook.proxies.connectivity.ConnectivityStub;
import com.lody.virtual.client.hook.proxies.content.ContentServiceStub;
import com.lody.virtual.client.hook.proxies.context_hub.ContextHubServiceStub;
import com.lody.virtual.client.hook.proxies.credential.CredentialManagerStub;
import com.lody.virtual.client.hook.proxies.dev_identifiers_policy.DeviceIdentifiersPolicyServiceHub;
import com.lody.virtual.client.hook.proxies.devicepolicy.DevicePolicyManagerStub;
import com.lody.virtual.client.hook.proxies.display.DisplayStub;
import com.lody.virtual.client.hook.proxies.dropbox.DropBoxManagerStub;
import com.lody.virtual.client.hook.proxies.fingerprint.FingerprintManagerStub;
import com.lody.virtual.client.hook.proxies.graphics.GraphicsStatsStub;
import com.lody.virtual.client.hook.proxies.imms.MmsStub;
import com.lody.virtual.client.hook.proxies.input.InputMethodManagerStub;
import com.lody.virtual.client.hook.proxies.isms.ISmsStub;
import com.lody.virtual.client.hook.proxies.isub.ISubStub;
import com.lody.virtual.client.hook.proxies.job.JobServiceStub;
import com.lody.virtual.client.hook.proxies.libcore.LibCoreStub;
import com.lody.virtual.client.hook.proxies.location.LocationManagerStub;
import com.lody.virtual.client.hook.proxies.media.router.MediaRouterServiceStub;
import com.lody.virtual.client.hook.proxies.media.session.SessionManagerStub;
import com.lody.virtual.client.hook.proxies.mount.MountServiceStub;
import com.lody.virtual.client.hook.proxies.network.NetworkManagementStub;
import com.lody.virtual.client.hook.proxies.nfc.NfcAdapterStub;
import com.lody.virtual.client.hook.proxies.notification.NotificationManagerStub;
import com.lody.virtual.client.hook.proxies.persistent_data_block.PersistentDataBlockServiceStub;
import com.lody.virtual.client.hook.proxies.phonesubinfo.PhoneSubInfoStub;
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub;
import com.lody.virtual.client.hook.proxies.power.PowerManagerStub;
import com.lody.virtual.client.hook.proxies.restriction.RestrictionStub;
import com.lody.virtual.client.hook.proxies.role.RoleStub;
import com.lody.virtual.client.hook.proxies.search.SearchManagerStub;
import com.lody.virtual.client.hook.proxies.pm.ShortcutServiceStub;
import com.lody.virtual.client.hook.proxies.storage_stats.StorageStatsStub;
import com.lody.virtual.client.hook.proxies.system.LockSettingsStub;
import com.lody.virtual.client.hook.proxies.system.SystemUpdateStub;
import com.lody.virtual.client.hook.proxies.system.WifiScannerStub;
import com.lody.virtual.client.hook.proxies.telecom.TelecomManagerStub;
import com.lody.virtual.client.hook.proxies.telephony.HwTelephonyStub;
import com.lody.virtual.client.hook.proxies.telephony.TelephonyRegistryStub;
import com.lody.virtual.client.hook.proxies.telephony.TelephonyStub;
import com.lody.virtual.client.hook.proxies.usage.UsageStatsManagerStub;
import com.lody.virtual.client.hook.proxies.usb.UsbManagerStub;
import com.lody.virtual.client.hook.proxies.user.UserManagerStub;
import com.lody.virtual.client.hook.proxies.vibrator.VibratorStub;
import com.lody.virtual.client.hook.proxies.view.AutoFillManagerStub;
import com.lody.virtual.client.hook.proxies.wifi.WifiManagerStub;
import com.lody.virtual.client.hook.proxies.window.WindowManagerStub;
import com.lody.virtual.client.interfaces.IInjector;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.VLog;

import java.util.HashMap;
import java.util.Map;

import mirror.com.android.internal.app.ISmtOpsService;
import mirror.com.android.internal.telephony.IHwTelephony;

import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR2;
import static android.os.Build.VERSION_CODES.KITKAT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Build.VERSION_CODES.LOLLIPOP_MR1;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;

/**
 * 集中创建并注入 VirtualApp 各系统服务代理。
 *
 * <p>每个代理只在对应的进程和系统版本中注册，防止不存在的隐藏服务影响低版本启动。</p>
 *
 * @author Lody
 */
public final class InvocationStubManager {

    private static InvocationStubManager sInstance = new InvocationStubManager();
    private static boolean sInit;

    private Map<Class<?>, IInjector> mInjectors = new HashMap<>(13);

    private InvocationStubManager() {
    }

    /**
     * 获取全局唯一的代理管理器。
     *
     * @return 代理管理器单例。
     */
    public static InvocationStubManager getInstance() {
        return sInstance;
    }

    void injectAll() throws Throwable {
        for (IInjector injector : mInjectors.values()) {
            try {
                injector.inject();
            } catch (Throwable error) {
                VLog.e("InvocationStubManager", "Skip injector %s: %s", injector.getClass().getName(), error);
            }
        }
        if (VirtualCore.get().isVAppProcess()) {
            AppInstrumentation instrumentation = AppInstrumentation.getDefault();
            addInjector(instrumentation);
            try {
                instrumentation.inject();
            } catch (Throwable error) {
                VLog.e("InvocationStubManager", "Skip injector %s: %s", instrumentation.getClass().getName(), error);
            }
        }
    }

    /**
     * 判断代理管理器是否已完成初始化。
     *
     * @return 已初始化时返回 {@code true}。
     */
    public boolean isInit() {
        return sInit;
    }


    /**
     * 按当前进程类型和 Android 版本创建需要的系统服务代理。
     *
     * @throws Throwable 代理初始化失败时抛出，由上层统一记录并中止错误启动。
     */
    public void init() throws Throwable {
        if (isInit()) {
            throw new IllegalStateException("InvocationStubManager Has been initialized.");
        }
        injectInternal();
        sInit = true;

    }

    private void injectInternal() throws Throwable {
        if (VirtualCore.get().isMainProcess()) {
            return;
        }
        if (VirtualCore.get().isServerProcess()) {
            addInjector(new ActivityManagerStub());
            addInjector(new PackageManagerStub());
            return;
        }
        if (VirtualCore.get().isVAppProcess()) {
            addInjector(new LibCoreStub());
            addInjector(new ActivityManagerStub());
            addInjector(new PackageManagerStub());
            addInjector(HCallbackStub.getDefault());
            addInjector(new ISmsStub());
            addInjector(new ISubStub());
            addInjector(new DropBoxManagerStub());
            addInjector(new NotificationManagerStub());
            addInjector(new LocationManagerStub());
            addInjector(new WindowManagerStub());
            addInjector(new ClipBoardStub());
            addInjector(new MountServiceStub());
            addInjector(new BackupManagerStub());
            addInjector(new TelephonyStub());
            addInjector(new AccessibilityManagerStub());
            if (BuildCompat.isOreo()) {
                if (IHwTelephony.TYPE != null) {
                    addInjector(new HwTelephonyStub());
                }
            }
            addInjector(new TelephonyRegistryStub());
            addInjector(new PhoneSubInfoStub());
            addInjector(new PowerManagerStub());
            addInjector(new AppWidgetManagerStub());
            addInjector(new AccountManagerStub());
            addInjector(new AudioManagerStub());
            addInjector(new SearchManagerStub());
            addInjector(new ContentServiceStub());
            addInjector(new ConnectivityStub());
            addInjector(new BluetoothStub());

            if (Build.VERSION.SDK_INT >= JELLY_BEAN_MR2) {
                addInjector(new VibratorStub());
                addInjector(new WifiManagerStub());
                addInjector(new ContextHubServiceStub());
            }

            if (Build.VERSION.SDK_INT >= JELLY_BEAN_MR1) {
                addInjector(new UserManagerStub());
            }

            if (Build.VERSION.SDK_INT >= JELLY_BEAN_MR1) {
                addInjector(new DisplayStub());
            }
            if (Build.VERSION.SDK_INT >= LOLLIPOP) {
                addInjector(new PersistentDataBlockServiceStub());
                addInjector(new InputMethodManagerStub());
                addInjector(new MmsStub());
                addInjector(new SessionManagerStub());
                addInjector(new JobServiceStub());
                addInjector(new RestrictionStub());
                addInjector(new TelecomManagerStub());
            }
            if (Build.VERSION.SDK_INT >= KITKAT) {
                addInjector(new AlarmManagerStub());
                addInjector(new AppOpsManagerStub());
                addInjector(new MediaRouterServiceStub());
                if (ISmtOpsService.TYPE != null) {
                    addInjector(new SmtOpsManagerStub());
                }
            }
            if (Build.VERSION.SDK_INT >= LOLLIPOP_MR1) {
                addInjector(new GraphicsStatsStub());
                addInjector(new UsageStatsManagerStub());
            }
            if (Build.VERSION.SDK_INT >= M) {
                addInjector(new FingerprintManagerStub());
                addInjector(new NetworkManagementStub());
            }
            if (Build.VERSION.SDK_INT >= N) {
                addInjector(new WifiScannerStub());
                addInjector(new ShortcutServiceStub());
                addInjector(new DevicePolicyManagerStub());
                addInjector(new BatteryStatsHub());
                addInjector(new WallpaperManagerStub());
            }
            if (BuildCompat.isOreo()) {
                addInjector(new AutoFillManagerStub());
                addInjector(new StorageStatsStub());
            }
            if (BuildCompat.isPie() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                addInjector(new SystemUpdateStub());
            }
            if (BuildCompat.isPie() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                addInjector(new LockSettingsStub());
            }
            if (mirror.oem.IFlymePermissionService.TYPE != null) {
                addInjector(new FlymePermissionServiceStub());
            }
            if (BuildCompat.isQ()) {
                addInjector(new ActivityTaskManagerStub());
                addInjector(new DeviceIdentifiersPolicyServiceHub());
                addInjector(new RoleStub());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14 起 CredentialManager 在 system_server 中核对包名与 Binder UID。
                // 容器应用共享宿主 UID，必须在调用进入系统前改写调用包名。
                addInjector(new CredentialManagerStub());
            }
            addInjector(new NfcAdapterStub());
            addInjector(new UsbManagerStub());
        }
    }

    private void addInjector(IInjector IInjector) {
        mInjectors.put(IInjector.getClass(), IInjector);
    }

    /**
     * 按代理类型查找已注册实例。
     *
     * @param clazz 要查找的代理类型。
     * @param <T> 代理实例类型。
     * @return 已注册的代理；不存在时返回 {@code null}。
     */
    public <T extends IInjector> T findInjector(Class<T> clazz) {
        // noinspection unchecked
        return (T) mInjectors.get(clazz);
    }

    /**
     * 检查指定代理是否被系统或应用覆盖，必要时重新注入。
     *
     * @param clazz 需要检查的代理类型。
     * @param <T> 代理实例类型。
     */
    public <T extends IInjector> void checkEnv(Class<T> clazz) {
        IInjector IInjector = findInjector(clazz);
        if (IInjector != null && IInjector.isEnvBad()) {
            try {
                IInjector.inject();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 检查所有已注册代理，并恢复被后续代码替换的 Binder 服务。
     */
    public void checkAllEnv() {
        for (IInjector injector : mInjectors.values()) {
            if (injector.isEnvBad()) {
                try {
                    injector.inject();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 获取指定方法代理持有的底层调用 Stub。
     *
     * @param injectorClass 方法代理类型。
     * @param <T> 方法代理类型。
     * @param <H> 底层调用 Stub 类型。
     * @return 底层调用 Stub；代理不存在或类型不匹配时返回 {@code null}。
     */
    public <T extends IInjector, H extends MethodInvocationStub> H getInvocationStub(Class<T> injectorClass) {
        T injector = findInjector(injectorClass);
        if (injector instanceof MethodInvocationProxy) {
            // noinspection unchecked
            return (H) ((MethodInvocationProxy) injector).getInvocationStub();
        }
        return null;
    }

}
