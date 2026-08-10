package com.lody.virtual.client.ipc;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.server.ServiceCache;
import com.lody.virtual.server.interfaces.IServiceFetcher;

/**
 * 管理宿主进程与 VirtualApp 服务进程之间的 Binder 服务连接。
 *
 * <p>服务进程可能被系统内存管理器回收。连接失效时清除缓存，后续服务访问即可通过
 * {@link com.lody.virtual.server.BinderProvider} 重新拉起服务进程，而不是在 Binder
 * 死亡回调线程中抛异常。</p>
 */
public class ServiceManagerNative {
    /** 虚拟包管理服务名称。 */
    public static final String PACKAGE = "package";
    /** 虚拟 Activity 管理服务名称。 */
    public static final String ACTIVITY = "activity";
    /** 虚拟用户管理服务名称。 */
    public static final String USER = "user";
    /** 虚拟应用管理服务名称。 */
    public static final String APP = "app";
    /** 虚拟账户管理服务名称。 */
    public static final String ACCOUNT = "account";
    /** 虚拟内容与同步管理服务名称。 */
    public static final String CONTENT = "content";
    /** 虚拟任务调度服务名称。 */
    public static final String JOB = "job";
    /** 虚拟通知管理服务名称。 */
    public static final String NOTIFICATION = "notification";
    /** 虚拟存储服务名称。 */
    public static final String VS = "vs";
    /** 虚拟设备信息服务名称。 */
    public static final String DEVICE = "device";
    /** 虚拟定位服务名称。 */
    public static final String VIRTUAL_LOC = "virtual-loc";

    /** 悬浮图标服务名称。 */
    public static final String FLOATICONBALL = "floaticonball";
    /** 应用权限服务名称。 */
    public static final String APPPERMISSION = "app-permission";
    /** 容器控制器服务名称。 */
    public static final String CONTROLLER = "controller";
    /** 水印服务名称。 */
    public static final String WATERMARK = "watermakr";
    /** 水印对话框服务名称。 */
    public static final String WATERMARK_DIALOG = "watermark-dialog";
    /** 安全密钥服务名称。 */
    public static final String SAFEKEY = "safekey";
    /** CKMS 安全密钥服务名称。 */
    public static final String CKMSSAFEKEY = "ckms-safekey";
    /** 虚拟服务保活管理服务名称。 */
    public static final String KEEPALIVE = "keepalive";
    /** 安装来源设置服务名称。 */
    public static final String INSTALLERSETTING = "installersetting";

    /** BinderProvider 的默认 authority 后缀。 */
    public static final String SERVICE_DEF_AUTH = "virtual.service.BinderProvider";
    private static final String TAG = ServiceManagerNative.class.getSimpleName();
    /** 当前使用的 BinderProvider authority 后缀。 */
    public static String SERVICE_CP_AUTH = "virtual.service.BinderProvider";

    private static IServiceFetcher sFetcher;

    private static String getAuthority() {
        return VirtualCore.getConfig().getBinderProviderAuthority();
    }

    private static IServiceFetcher getServiceFetcher() {
        if (sFetcher == null || !sFetcher.asBinder().isBinderAlive()) {
            synchronized (ServiceManagerNative.class) {
                if (sFetcher == null || !sFetcher.asBinder().isBinderAlive()) {
                    Context context = VirtualCore.get().getContext();
                    Bundle response = new ProviderCall.Builder(context, getAuthority()).methodName("@").callSafely();
                    if (response != null) {
                        IBinder binder = BundleCompat.getBinder(response, "_VA_|_binder_");
                        linkBinderDied(binder);
                        sFetcher = IServiceFetcher.Stub.asInterface(binder);
                    }

                }
            }
        }
        return sFetcher;
    }

    /**
     * 主动触发 BinderProvider 创建，以便提前启动 VirtualApp 服务进程。
     */
    public static void ensureServerStarted() {
        new ProviderCall.Builder(VirtualCore.get().getContext(), getAuthority()).methodName("ensure_created").callSafely();
    }

    /**
     * 清除已经缓存的服务抓取器，使下一次访问重新连接服务进程。
     */
    public static void clearServerFetcher() {
        sFetcher = null;
    }

    private static void linkBinderDied(final IBinder binder) {

        IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                try {
                    binder.unlinkToDeath(this, 0);
                }catch (Throwable e){
                    //ignore
                }
                
                onServerDied();
            }
        };

        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private static void onServerDied() {
        // 系统可能在内存紧张时合法回收前台服务进程。死亡回调只负责使连接失效，
        // 不能从 Binder 线程抛异常，否则宿主进程会被旧版 VirtualApp 的策略连带终止。
        clearServerFetcher();
        VLog.w(TAG, "VirtualApp server process died; cached service fetcher was cleared.");
    }


    /**
     * 获取指定名称的 VirtualApp Binder 服务。
     *
     * @param name 服务注册名称
     * @return 对应 Binder；服务进程暂不可用时返回 {@code null}
     */
    public static IBinder getService(String name) {
        if (VirtualCore.get().isServerProcess()) {
            return ServiceCache.getService(name);
        }
        IServiceFetcher fetcher = getServiceFetcher();
        if (fetcher != null) {
            try {
                return fetcher.getService(name);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        VLog.e(TAG, "GetService(%s) return null.", name);
        return null;
    }

    /**
     * 向 VirtualApp 服务缓存注册 Binder。
     *
     * @param name 服务注册名称
     * @param service 要注册的 Binder 实例
     */
    public static void addService(String name, IBinder service) {
        IServiceFetcher fetcher = getServiceFetcher();
        if (fetcher != null) {
            try {
                fetcher.addService(name, service);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 从 VirtualApp 服务缓存移除 Binder。
     *
     * @param name 要移除的服务注册名称
     */
    public static void removeService(String name) {
        IServiceFetcher fetcher = getServiceFetcher();
        if (fetcher != null) {
            try {
                fetcher.removeService(name);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

}
