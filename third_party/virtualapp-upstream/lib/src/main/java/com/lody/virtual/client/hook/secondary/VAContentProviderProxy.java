package com.lody.virtual.client.hook.secondary;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Build;
import android.os.IInterface;
import android.os.Process;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.compat.LimbusAuthenticationCompat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 为虚拟应用调用内部 ContentProvider 时恢复兼容的 Binder 与 AttributionSource 身份。
 *
 * <p>Android 12 起 Provider 会校验 AttributionSource UID 是否等于 Binder 看到的真实
 * 调用 UID。虚拟 UID 只属于容器内部语义，跨进程 Binder 实际仍由宿主 UID 发起，因此
 * 新系统必须在系统边界使用宿主物理身份，旧系统则继续保留原有虚拟身份行为。</p>
 */
public class VAContentProviderProxy {

    /**
     * 包装 ContentProvider Binder，并在每次调用前恢复与系统版本匹配的调用身份。
     *
     * @param contentProviderProxy 原始 ContentProvider Binder 接口
     * @param uid 虚拟调用方 UID
     * @param pid 虚拟调用方 PID
     * @param appPkg 虚拟调用方包名
     * @return 带身份改写逻辑的 ContentProvider 接口
     */
    public static IInterface wrapper(final IInterface contentProviderProxy, final int uid, final int pid, final String appPkg) {
        Class[] classes = contentProviderProxy.getClass().getInterfaces();
        int resolvedHostUid = VirtualCore.get().getContext().getApplicationInfo().uid;
        if (resolvedHostUid <= 0) {
            resolvedHostUid = VirtualCore.get().myUid();
        }
        final int hostUid = resolvedHostUid;
        final int hostPid = Process.myPid();
        final String hostPkg = VirtualCore.get().getHostPkg();
        final boolean usePhysicalIdentity =
                LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(Build.VERSION.SDK_INT);
        final int providerUid = usePhysicalIdentity ? hostUid : uid;
        final int providerPid = usePhysicalIdentity ? hostPid : pid;
        final String providerPkg = usePhysicalIdentity ? hostPkg : appPkg;
        final long providerIdentity = (long) providerUid << 32 | providerPid;
        return (IInterface) Proxy.newProxyInstance(contentProviderProxy.getClass().getClassLoader(), classes, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("asBinder".equals(method.getName())) {
                    return method.invoke(contentProviderProxy, args);
                }
                long clearCallingIdentity = Binder.clearCallingIdentity();
                try {
                    if (args != null && args.length > 0 && args[0] instanceof String) {
                        String pkg = (String) args[0];
                        if (VirtualCore.get().getHostPkg().equals(pkg)) {
                            args[0] = appPkg;
                        }
                    }
                    rewriteAttributionSource(args, providerUid, providerPkg, method.getName());
                    // 远程 Binder 由内核写入宿主 UID；本地接口也使用同一物理身份，避免两条路径行为分叉。
                    Binder.restoreCallingIdentity(providerIdentity);
                    return method.invoke(contentProviderProxy, args);
                } finally {
                    Binder.restoreCallingIdentity(clearCallingIdentity);
                }
            }
        });
    }

    /**
     * 将 Android 12 及以上的 AttributionSource 改写为 Binder 可验证的调用身份。
     *
     * @param args Provider 方法参数
     * @param uid 系统边界可验证的 UID
     * @param appPkg 与 UID 对应的包名
     * @param methodName 当前 Provider 方法名，仅用于安全诊断
     */
    private static void rewriteAttributionSource(Object[] args, int uid, String appPkg, String methodName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || args == null || args.length == 0) {
            return;
        }
        Object firstArg = args[0];
        if (!(firstArg instanceof AttributionSource)) {
            return;
        }
        AttributionSource original = (AttributionSource) firstArg;
        AttributionSource rewritten = new AttributionSource.Builder(uid)
                .setPackageName(appPkg)
                .build();
        Log.i("LimbusVA", "Rewrite provider AttributionSource method=" + methodName
                + " oldUid=" + original.getUid()
                + " oldPkg=" + original.getPackageName()
                + " newUid=" + rewritten.getUid()
                + " newPkg=" + rewritten.getPackageName());
        args[0] = rewritten;
    }
}
