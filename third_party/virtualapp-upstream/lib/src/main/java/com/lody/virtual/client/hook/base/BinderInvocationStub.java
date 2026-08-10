package com.lody.virtual.client.hook.base;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.lody.virtual.client.core.ServiceLocalManager;
import com.lody.virtual.client.core.VirtualCore;

import java.io.FileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import mirror.RefStaticMethod;
import mirror.android.os.ServiceManager;

/**
 * 把真实系统 Binder 包装为可插入方法代理的 Binder，并保持底层 Binder 能力完整透传。
 *
 * <p>系统服务缓存会保存本对象，因此除 AIDL 事务外，系统厂商直接调用的 Binder 扩展能力
 * 也必须转发给真实 Binder。否则 ColorOS 等系统在读取应用图标或多开标记时会调用
 * {@code IBinder.getExtension()}，并因接口默认实现抛出异常。</p>
 *
 * @author Lody
 */
@SuppressWarnings("unchecked")
public class BinderInvocationStub extends MethodInvocationStub<IInterface> implements IBinder {

    private static final String TAG = BinderInvocationStub.class.getSimpleName();
    private IBinder mBaseBinder;

    /**
     * 使用给定的隐藏 AIDL {@code asInterface} 方法创建 Binder 代理。
     *
     * @param asInterfaceMethod 隐藏 AIDL Stub 的 {@code asInterface} 映射
     * @param binder 真实系统服务 Binder
     */
    public BinderInvocationStub(RefStaticMethod<IInterface> asInterfaceMethod, IBinder binder) {
        this(asInterface(asInterfaceMethod, binder));
    }

    /**
     * 使用运行时解析到的 AIDL Stub 类创建 Binder 代理。
     *
     * @param stubClass 包含静态 {@code asInterface(IBinder)} 方法的 AIDL Stub 类
     * @param binder 真实系统服务 Binder
     */
    public BinderInvocationStub(Class<?> stubClass, IBinder binder) {
        this(asInterface(stubClass, binder));
    }

    /**
     * 使用已经解析的真实服务接口创建 Binder 代理。
     *
     * @param mBaseInterface 真实系统服务的 AIDL 接口
     */
    public BinderInvocationStub(IInterface mBaseInterface) {
        super(mBaseInterface);
        mBaseBinder = getBaseInterface() != null ? getBaseInterface().asBinder() : null;
        addMethodProxy(new AsBinder());
    }

    private static IInterface asInterface(RefStaticMethod<IInterface> asInterfaceMethod, IBinder binder) {
        if (asInterfaceMethod == null || binder == null) {
            return null;
        }
        return asInterfaceMethod.call(binder);
    }

    private static IInterface asInterface(Class<?> stubClass, IBinder binder) {
        try {
            if (stubClass == null) {
                return null;
            }
            if (binder == null) {
                Log.w(TAG, "Could not create stub because binder = null, stubClass=" + stubClass);
                return null;
            }
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return (IInterface) asInterface.invoke(null, binder);
        } catch (Exception e) {
            Log.d(TAG, "Could not create stub " + stubClass.getName() + ". Cause: " + e);
            return null;
        }
    }

    /**
     * 把当前代理写入系统服务缓存和 VirtualApp 本地服务缓存。
     *
     * @param name 要替换的系统服务名称
     */
    public void replaceService(String name) {
        if (mBaseBinder != null) {
            ServiceManager.sCache.get().put(name, this);
            ServiceLocalManager.addService(name, this);
        }
    }

    private final class AsBinder extends MethodProxy {

        @Override
        public String getMethodName() {
            return "asBinder";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return BinderInvocationStub.this;
        }
    }


    /**
     * 返回真实 Binder 的接口描述符。
     *
     * @return 真实服务声明的 AIDL 接口描述符
     * @throws RemoteException 无法访问真实 Binder 时抛出
     */
    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return mBaseBinder.getInterfaceDescriptor();
    }

    /**
     * 获取宿主应用上下文，供具体服务代理访问系统服务。
     *
     * @return VirtualApp 宿主上下文
     */
    public Context getContext() {
        return VirtualCore.get().getContext();
    }

    /**
     * 探测真实 Binder 是否仍可响应。
     *
     * @return 真实 Binder 可响应时返回 {@code true}
     */
    @Override
    public boolean pingBinder() {
        return mBaseBinder.pingBinder();
    }

    /**
     * 检查真实 Binder 是否仍然存活。
     *
     * @return 真实 Binder 存活时返回 {@code true}
     */
    @Override
    public boolean isBinderAlive() {
        return mBaseBinder.isBinderAlive();
    }

    /**
     * 返回经过方法拦截包装的本地接口。
     *
     * @param descriptor 调用方请求的接口描述符
     * @return 当前服务的动态代理接口
     */
    @Override
    public IInterface queryLocalInterface(String descriptor) {
        return getProxyInterface();
    }

    /**
     * 将同步诊断信息导出请求转发给真实 Binder。
     *
     * @param fd 诊断信息输出文件描述符
     * @param args 诊断命令参数
     * @throws RemoteException 真实 Binder 无法处理请求时抛出
     */
    @Override
    public void dump(FileDescriptor fd, String[] args) throws RemoteException {
        mBaseBinder.dump(fd, args);
    }

    /**
     * 将异步诊断信息导出请求转发给真实 Binder。
     *
     * @param fd 诊断信息输出文件描述符
     * @param args 诊断命令参数
     * @throws RemoteException 真实 Binder 无法处理请求时抛出
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    @Override
    public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
        mBaseBinder.dumpAsync(fd, args);
    }

    /**
     * 将原始 Binder 事务无损转发给真实服务。
     *
     * @param code Binder 事务编号
     * @param data 调用参数
     * @param reply 调用结果
     * @param flags Binder 事务标记
     * @return 真实 Binder 是否处理了该事务
     * @throws RemoteException 事务转发失败时抛出
     */
    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return mBaseBinder.transact(code, data, reply, flags);
    }

    /**
     * 将死亡监听器注册到真实 Binder。
     *
     * @param recipient Binder 死亡回调
     * @param flags 监听标记
     * @throws RemoteException 注册失败时抛出
     */
    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        mBaseBinder.linkToDeath(recipient, flags);
    }

    /**
     * 从真实 Binder 移除死亡监听器。
     *
     * @param recipient 要移除的 Binder 死亡回调
     * @param flags 监听标记
     * @return 成功移除时返回 {@code true}
     */
    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return mBaseBinder.unlinkToDeath(recipient, flags);
    }

    /**
     * 将厂商或平台附加的扩展 Binder 透传给调用方。
     *
     * <p>ColorOS 的多开管理器会在加载账号选择器图标时调用该方法。这里必须委托给
     * 真实 Binder，不能使用 {@link IBinder} 的“未实现”默认行为。</p>
     *
     * @return 真实服务提供的扩展 Binder；未提供时可能为 {@code null}
     * @throws RemoteException 读取扩展 Binder 失败时抛出
     */
    public IBinder getExtension() throws RemoteException {
        try {
            // 当前工程使用 API 35 编译，而 getExtension() 在 Android 16 的 IBinder 才公开；
            // 通过运行时反射调用可同时保持旧系统的二进制兼容性。
            Method getExtensionMethod = mBaseBinder.getClass().getMethod("getExtension");
            return (IBinder) getExtensionMethod.invoke(mBaseBinder);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getTargetException();
            if (cause instanceof RemoteException) {
                throw (RemoteException) cause;
            }
            RemoteException remoteException = new RemoteException(
                    "Unable to call the real Binder extension: " + cause);
            remoteException.initCause(cause);
            throw remoteException;
        } catch (ReflectiveOperationException error) {
            RemoteException remoteException = new RemoteException(
                    "The platform Binder extension is unavailable: " + error);
            remoteException.initCause(error);
            throw remoteException;
        }
    }

    /**
     * 获取未包装的真实 Binder，供需要直接访问底层服务的兼容代码使用。
     *
     * @return 真实系统服务 Binder
     */
    public IBinder getBaseBinder() {
        return mBaseBinder;
    }

}
