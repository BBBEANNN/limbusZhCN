package com.xdja.activitycounter;

import android.os.IBinder;
import android.os.RemoteException;

import com.lody.virtual.client.ipc.LocalProxyUtils;
import com.lody.virtual.client.ipc.ServiceManagerNative;
import com.lody.virtual.helper.utils.VLog;

/**
 * 管理客户端与 Activity 计数服务之间的 Binder 连接，并提供前后台状态计数接口。
 *
 * <p>计数服务属于辅助能力，系统回收 VirtualApp 服务进程后应自动重连；即使重连失败，
 * 也只能丢弃本次计数，不能让主线程或目标应用崩溃。</p>
 */
public class ActivityCounterManager {
    private static final String TAG = ActivityCounterManager.class.getSimpleName();
    private static final int MAX_CALL_ATTEMPTS = 2;
    private static final ActivityCounterManager sInstance = new ActivityCounterManager();

    private volatile IActivityCounterService mRemote;

    /**
     * 获取进程内共享的 Activity 计数管理器。
     *
     * @return Activity 计数管理器单例
     */
    public static ActivityCounterManager get() {
        return sInstance;
    }

    /**
     * 获取可用的 Activity 计数远端服务；旧 Binder 已死亡时会重新向服务端查询。
     *
     * @return 可用的计数服务；服务进程暂时无法启动时返回 {@code null}
     */
    public IActivityCounterService getRemote() {
        IActivityCounterService remote = mRemote;
        if (isRemoteAlive(remote)) {
            return remote;
        }

        synchronized (ActivityCounterManager.class) {
            remote = mRemote;
            if (!isRemoteAlive(remote)) {
                // Android 16 可能在游戏位于后台时回收 VirtualApp 服务进程。虚拟应用进程也必须
                // 重新获取 Binder；旧实现对 VApp 进程跳过重连，会在 Activity 恢复时抛出
                // DeadObjectException 并错误终止 Unity 主线程。
                Object stubInterface = getStubInterface();
                mRemote = stubInterface == null
                        ? null
                        : LocalProxyUtils.genProxy(IActivityCounterService.class, stubInterface);
            }
            return mRemote;
        }
    }

    /**
     * 记录一个 Activity 已进入 started 状态。
     *
     * @param pkg Activity 所属包名
     * @param name Activity 类名
     * @param pid Activity 所在进程 ID
     */
    public void activityCountAdd(String pkg, String name, int pid) {
        callRemote("activityCountAdd", null, remote -> {
            remote.activityCountAdd(pkg, name, pid);
            return null;
        });
    }

    /**
     * 记录一个 Activity 已离开 started 状态。
     *
     * @param pkg Activity 所属包名
     * @param name Activity 类名
     * @param pid Activity 所在进程 ID
     */
    public void activityCountReduce(String pkg, String name, int pid) {
        callRemote("activityCountReduce", null, remote -> {
            remote.activityCountReduce(pkg, name, pid);
            return null;
        });
    }

    /**
     * 清理指定进程的 Activity 计数。
     *
     * @param pid 要清理的进程 ID
     */
    public void cleanProcess(int pid) {
        callRemote("cleanProcess", null, remote -> {
            remote.cleanProcess(pid);
            return null;
        });
    }

    /**
     * 清理指定包名下所有进程的 Activity 计数。
     *
     * @param pkg 要清理的应用包名
     */
    public void cleanPackage(String pkg) {
        callRemote("cleanPackage", null, remote -> {
            remote.cleanPackage(pkg);
            return null;
        });
    }

    /**
     * 查询指定应用是否至少有一个前台 Activity。
     *
     * @param pkg 要查询的应用包名
     * @return 服务可用且应用处于前台时返回 {@code true}
     */
    public boolean isForeGroundApp(String pkg) {
        return callRemote("isForeGroundApp", false, remote -> remote.isForeGroundApp(pkg));
    }

    /**
     * 查询容器中是否存在前台 Activity。
     *
     * @return 服务可用且存在前台 Activity 时返回 {@code true}
     */
    public boolean isForeGround() {
        return callRemote("isForeGround", false, IActivityCounterService::isForeGround);
    }

    /**
     * 注册容器前后台状态变化回调。
     *
     * @param fibCallback 接收前后台状态变化的 Binder 回调
     */
    public void registerCallback(IForegroundInterface fibCallback) {
        callRemote("registerCallback", null, remote -> {
            remote.registerCallback(fibCallback);
            return null;
        });
    }

    /**
     * 注销当前容器前后台状态变化回调。
     */
    public void unregisterCallback() {
        callRemote("unregisterCallback", null, remote -> {
            remote.unregisterCallback();
            return null;
        });
    }

    private Object getStubInterface() {
        return IActivityCounterService.Stub.asInterface(
                ServiceManagerNative.getService(ServiceManagerNative.FLOATICONBALL));
    }

    private static boolean isRemoteAlive(IActivityCounterService remote) {
        if (remote == null) {
            return false;
        }
        IBinder binder = remote.asBinder();
        return binder != null && binder.isBinderAlive();
    }

    private synchronized void invalidateRemote(IActivityCounterService failedRemote) {
        if (mRemote == failedRemote) {
            mRemote = null;
        }
        // Binder 死亡回调可能尚未来得及清空 fetcher；主动失效可让下一次尝试通过
        // BinderProvider 拉起新的 VirtualApp 服务进程。
        ServiceManagerNative.clearServerFetcher();
    }

    private <T> T callRemote(String operationName, T fallbackValue, RemoteCall<T> call) {
        RemoteException lastError = null;
        for (int attempt = 0; attempt < MAX_CALL_ATTEMPTS; attempt++) {
            IActivityCounterService remote = getRemote();
            if (remote == null) {
                continue;
            }
            try {
                return call.execute(remote);
            } catch (RemoteException error) {
                lastError = error;
                invalidateRemote(remote);
            }
        }

        // Activity 计数只用于悬浮窗和前后台辅助判断。服务不可用时采用安全默认值，
        // 避免生命周期回调把 DeadObjectException 升级为目标应用的致命异常。
        VLog.w(TAG, "Skip %s because ActivityCounter service is unavailable: %s",
                operationName,
                lastError == null ? "service not found" : lastError.toString());
        return fallbackValue;
    }

    private interface RemoteCall<T> {
        T execute(IActivityCounterService remote) throws RemoteException;
    }
}
