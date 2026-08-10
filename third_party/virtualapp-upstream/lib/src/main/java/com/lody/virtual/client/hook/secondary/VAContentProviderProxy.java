package com.lody.virtual.client.hook.secondary;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Build;
import android.os.IInterface;
import android.os.Process;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class VAContentProviderProxy {

    public static IInterface wrapper(final IInterface contentProviderProxy, final int uid, final int pid, final String appPkg) {
        Class[] classes = contentProviderProxy.getClass().getInterfaces();
        final long fakeIdentity = (long) uid << 32 | pid;
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
                    rewriteAttributionSource(args, uid, appPkg, method.getName());
                    Binder.restoreCallingIdentity(fakeIdentity);
                    return method.invoke(contentProviderProxy, args);
                } finally {
                    Binder.restoreCallingIdentity(clearCallingIdentity);
                }
            }
        });
    }

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
