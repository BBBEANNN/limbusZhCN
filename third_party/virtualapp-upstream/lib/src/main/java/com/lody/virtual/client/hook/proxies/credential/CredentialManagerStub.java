package com.lody.virtual.client.hook.proxies.credential;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 代理 Android 14 及更高版本的系统凭据服务，使容器内应用能通过 UID 归属校验。
 *
 * <p>CredentialManager 会把调用方包名显式传给 system_server。容器内的游戏实际
 * 使用宿主 UID 发起 Binder 请求，因此必须仅在进入系统服务前把最后一个调用包名
 * 改写为宿主包名。凭据请求本身和回调 Binder 均保持原样。</p>
 */
public final class CredentialManagerStub extends BinderInvocationProxy {

    private static final String TAG = "LimbusVA";
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";
    private static final String SERVICE_NAME = "credential";
    private static final int CALLBACK_INDEX = 1;

    /**
     * 创建凭据服务代理，并从当前系统动态解析隐藏 AIDL Stub。
     */
    public CredentialManagerStub() {
        super(resolveCredentialManagerStub(), SERVICE_NAME);
    }

    /**
     * 注册所有带调用包名的 CredentialManager Binder 方法。
     *
     * <p>登录、创建凭据、退出登录和凭据提供者管理使用同一套 UID 校验，
     * 所以在这里统一覆盖，避免仅修复启动时的登出调用。</p>
     */
    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ExecuteGetCredentialMethodProxy());
        addMethodProxy(new ReplaceLastPkgMethodProxy("executeCreateCredential"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("clearCredentialState"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("registerCredentialDescription"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("unregisterCredentialDescription"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("isEnabledCredentialProviderService"));
    }

    /**
     * 改写系统凭据请求的调用包名，并为 Limbus 的结果 Binder 增加无敏感数据的阶段日志。
     *
     * <p>系统凭据页关闭并不代表游戏已经收到最终结果。这里包装回调 Binder，只记录回调是
     * “准备显示界面”“成功”还是“失败”，随后把原始 Parcel 原样转发给框架创建的回调对象。
     * 不解析凭据响应，因此不会把账号、令牌或私有字段写入日志。</p>
     */
    private static final class ExecuteGetCredentialMethodProxy extends ReplaceLastPkgMethodProxy {

        private ExecuteGetCredentialMethodProxy() {
            super("executeGetCredential");
        }

        /**
         * 在调用真实 CredentialManager 前完成宿主包名改写与回调包装。
         *
         * @param who 系统 CredentialManager 的原始 Binder 代理
         * @param method 当前调用的方法
         * @param args Binder 方法参数
         * @return 始终返回 {@code true}，继续调用真实系统服务
         */
        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            boolean proceed = super.beforeCall(who, method, args);
            if (proceed && LIMBUS_PACKAGE.equals(MethodProxy.getAppPkg())) {
                wrapGetCredentialCallback(args);
            }
            return proceed;
        }
    }

    /**
     * 把框架生成的 {@code IGetCredentialCallback} 替换为带阶段日志的透明代理。
     *
     * @param args {@code executeGetCredential()} 的参数数组
     */
    private static void wrapGetCredentialCallback(Object[] args) {
        if (args == null || args.length <= CALLBACK_INDEX || !(args[CALLBACK_INDEX] instanceof IInterface)) {
            Log.w(TAG, "Credential callback is unavailable; skip callback diagnostics");
            return;
        }

        IInterface originalCallback = (IInterface) args[CALLBACK_INDEX];
        Class<?> callbackInterface = findCallbackInterface(originalCallback.getClass());
        if (callbackInterface == null) {
            Log.w(TAG, "Unable to find IGetCredentialCallback interface on "
                    + originalCallback.getClass().getName());
            return;
        }

        IBinder originalBinder = originalCallback.asBinder();
        CredentialCallbackBinder callbackBinder = new CredentialCallbackBinder(originalBinder);
        Object callbackProxy = Proxy.newProxyInstance(
                callbackInterface.getClassLoader(),
                new Class<?>[]{callbackInterface},
                (proxy, method, methodArgs) -> {
                    if ("asBinder".equals(method.getName()) && method.getParameterTypes().length == 0) {
                        return callbackBinder;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(originalCallback, methodArgs);
                    }
                    try {
                        return method.invoke(originalCallback, methodArgs);
                    } catch (InvocationTargetException error) {
                        throw error.getTargetException();
                    }
                });
        args[CALLBACK_INDEX] = callbackProxy;
        Log.i(TAG, "Wrapped CredentialManager get callback for result-stage diagnostics");
    }

    /**
     * 从框架内部回调实现上查找隐藏的 {@code IGetCredentialCallback} 接口。
     *
     * @param implementationClass 框架回调实现类
     * @return 匹配的回调接口；无法找到时返回 {@code null}
     */
    private static Class<?> findCallbackInterface(Class<?> implementationClass) {
        Class<?> current = implementationClass;
        while (current != null) {
            for (Class<?> interfaceClass : current.getInterfaces()) {
                if ("android.credentials.IGetCredentialCallback".equals(interfaceClass.getName())) {
                    return interfaceClass;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 在应用进程接收系统回调时记录事务阶段，并把原始 Parcel 无损转发给框架回调。
     */
    private static final class CredentialCallbackBinder extends Binder {

        private static final String DESCRIPTOR = "android.credentials.IGetCredentialCallback";
        private static final int TRANSACTION_PENDING_INTENT = IBinder.FIRST_CALL_TRANSACTION;
        private static final int TRANSACTION_RESPONSE = IBinder.FIRST_CALL_TRANSACTION + 1;
        private static final int TRANSACTION_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2;

        private final IBinder originalBinder;

        private CredentialCallbackBinder(IBinder originalBinder) {
            this.originalBinder = originalBinder;
            // owner 保持为空，确保本地查询也不会绕过这层事务转发与阶段日志。
            attachInterface(null, DESCRIPTOR);
        }

        /**
         * 记录不含凭据内容的回调阶段，并把事务交给 Android 框架原始回调处理。
         *
         * @param code AIDL 事务编号
         * @param data 系统传入的原始参数 Parcel
         * @param reply 同步调用的响应 Parcel；该回调通常为单向事务
         * @param flags Binder 调用标志
         * @return 原始回调 Binder 的处理结果
         * @throws RemoteException 原始回调无法接收事务时抛出
         */
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            Log.i(TAG, "CredentialManager callback stage=" + callbackStage(code));
            // 不读取 data，避免移动 Parcel 游标或把凭据内容暴露到日志；原始框架回调会按原 ABI 解析。
            return originalBinder.transact(code, data, reply, flags);
        }

        /**
         * 把 AIDL 事务编号映射为便于真机日志核对的阶段名称。
         *
         * @param transactionCode AIDL 事务编号
         * @return 不含敏感数据的阶段名称
         */
        private static String callbackStage(int transactionCode) {
            if (transactionCode == TRANSACTION_PENDING_INTENT) {
                return "pending_ui";
            }
            if (transactionCode == TRANSACTION_RESPONSE) {
                return "response";
            }
            if (transactionCode == TRANSACTION_ERROR) {
                return "error";
            }
            return "transaction_" + transactionCode;
        }
    }

    /**
     * 解析未向公开 SDK 暴露的 ICredentialManager.Stub 类。
     *
     * @return 当前系统的 CredentialManager AIDL Stub 类。
     * @throws IllegalStateException 当系统声明了凭据服务却无法加载其 Stub 时抛出。
     */
    private static Class<?> resolveCredentialManagerStub() {
        try {
            return Class.forName("android.credentials.ICredentialManager$Stub");
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("CredentialManager AIDL Stub is unavailable", error);
        }
    }
}
