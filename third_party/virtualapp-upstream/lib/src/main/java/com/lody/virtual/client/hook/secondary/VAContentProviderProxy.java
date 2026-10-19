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
 * 在虚拟应用调用 Provider 时恢复与系统版本一致的 Binder 和 AttributionSource 身份。
 *
 * <p>Android 12 起远程 Provider 会核对真实调用 UID，容器内部的虚拟 UID 无法通过此校验。
 * 系统边界使用宿主物理身份，旧系统保留原有虚拟身份。</p>
 */
public class VAContentProviderProxy {

    /**
     * 包装 Provider 接口，在调用期间恢复正确身份，并在退出时还原调用线程原身份。
     *
     * @param contentProviderProxy 原始 Provider Binder 接口
     * @param uid 容器内部调用方的虚拟 UID
     * @param pid 容器内部调用方的 PID
     * @param appPkg 容器内部调用方包名
     * @return 执行身份兼容处理的 Provider 接口
     */
    public static IInterface wrapper(final IInterface contentProviderProxy, final int uid, final int pid, final String appPkg) {
        Class[] classes = contentProviderProxy.getClass().getInterfaces();
        int resolvedHostUid = VirtualCore.get().getContext().getApplicationInfo().uid;
        if (resolvedHostUid <= 0) {
            resolvedHostUid = VirtualCore.get().myUid();
        }
        final boolean usePhysicalIdentity =
                LimbusAuthenticationCompat.shouldUsePhysicalProviderIdentity(Build.VERSION.SDK_INT);
        final int providerUid = usePhysicalIdentity ? resolvedHostUid : uid;
        final int providerPid = usePhysicalIdentity ? Process.myPid() : pid;
        final String hostPkg = VirtualCore.get().getHostPkg();
        final String providerPkg = usePhysicalIdentity ? hostPkg : appPkg;
        final long providerIdentity = (long) providerUid << 32 | (providerPid & 0xffffffffL);
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
                        if (hostPkg.equals(pkg)) {
                            args[0] = providerPkg;
                        }
                    }
                    rewriteAttributionSource(args, providerUid, providerPkg, method.getName());
                    // 远程调用由内核固定为宿主 UID，本地 Provider 也采用相同身份，避免分支行为不一致。
                    Binder.restoreCallingIdentity(providerIdentity);
                    return method.invoke(contentProviderProxy, args);
                } finally {
                    Binder.restoreCallingIdentity(clearCallingIdentity);
                }
            }
        });
    }

    /**
     * 改写 Android 12 以上 Provider 使用的调用来源，仅记录身份摘要而不读取请求内容。
     *
     * @param args Provider 方法参数
     * @param uid 与当前系统边界一致的调用 UID
     * @param appPkg 与调用 UID 对应的包名
     * @param methodName 当前方法名，用于定位身份改写发生的边界
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
