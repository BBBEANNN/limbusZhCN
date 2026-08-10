package com.lody.virtual.client.hook.proxies.view;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import mirror.android.view.IAutoFillManager;

/**
 * 代理系统自动填充服务，并把虚拟 Activity 的组件和包名转换为宿主身份。
 *
 * <p>Android 16 会在 WebView 输入框获得焦点时同步等待自动填充会话结果。若仍提交
 * {@code com.google.android.gms} 等虚拟包名，系统不会完成回调，主线程每次会卡满五秒。
 * 本代理在服务调用前改写身份，同时避免在 Application 尚未创建时提前构造
 * {@code AutofillManager}。</p>
 *
 * @author 陈磊
 */
public class AutoFillManagerStub extends BinderInvocationProxy {

    private static final String TAG = "AutoFillManagerStub";
    private static final String AUTO_FILL_NAME = "autofill";

    /**
     * 创建系统自动填充服务代理。
     */
    public AutoFillManagerStub() {
        super(IAutoFillManager.Stub.asInterface, AUTO_FILL_NAME);
    }

    /**
     * 注册需要修正虚拟包名或 Activity 组件的方法。
     *
     * <p>方法代理必须在构造阶段注册，不能依赖实例字段注入成功；进程启动早期
     * Application 仍为空，但 ServiceManager 代理已经可以正常工作。</p>
     */
    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplacePkgAndComponentProxy("addClient"));
        addMethodProxy(new ReplacePkgAndComponentProxy("startSession"));
        addMethodProxy(new ReplacePkgAndComponentProxy("updateOrRestartSession"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("isServiceEnabled"));
    }

    /**
     * 注入 ServiceManager 代理，并在 Application 已就绪时同步更新现有实例字段。
     *
     * <p>VirtualCore 在 {@code Application.attach()} 内启动，此时强行调用
     * {@code getSystemService("autofill")} 会触发 Android 16 DeviceConfig 读取空
     * Application。该阶段只替换服务缓存，稍后创建的 AutofillManager 会自然取得代理。</p>
     *
     * @throws Throwable 替换系统服务缓存失败时抛出
     */
    @SuppressLint("WrongConstant")
    @Override
    public void inject() throws Throwable {
        super.inject();
        if (VClient.get().getCurrentApplication() == null) {
            Log.i(TAG, "Application is not ready; AutofillManager will use the replaced service cache");
            return;
        }
        try {
            Object autoFillManagerInstance = getContext().getSystemService(AUTO_FILL_NAME);
            if (autoFillManagerInstance == null) {
                throw new NullPointerException("AutoFillManagerInstance is null.");
            }
            Object autoFillManagerProxy = getInvocationStub().getProxyInterface();
            if (autoFillManagerProxy == null) {
                throw new NullPointerException("AutoFillManagerProxy is null.");
            }
            Field serviceField = autoFillManagerInstance.getClass().getDeclaredField("mService");
            serviceField.setAccessible(true);
            serviceField.set(autoFillManagerInstance, autoFillManagerProxy);
        } catch (Throwable error) {
            // ServiceManager 缓存已经完成替换；实例字段失败不应撤销可用的全局代理。
            Log.e(TAG, "AutoFillManagerStub instance-field inject error.", error);
        }
    }

    /**
     * 同时改写自动填充调用中的宿主包名和 Activity 组件。
     */
    static class ReplacePkgAndComponentProxy extends ReplaceLastPkgMethodProxy {

        /**
         * 创建指定自动填充方法的身份修正代理。
         *
         * @param name 需要代理的 AIDL 方法名
         */
        ReplacePkgAndComponentProxy(String name) {
            super(name);
        }

        /**
         * 在调用真实自动填充服务前修正组件与包名。
         *
         * @param who 真实自动填充服务接口
         * @param method 当前调用的方法
         * @param args 当前调用参数
         * @return 始终返回 {@code true}，继续调用真实服务
         */
        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            replaceLastAppComponent(args, getHostPkg());
            return super.beforeCall(who, method, args);
        }

        /**
         * 将最后一个虚拟 Activity 组件替换为相同类名的宿主组件。
         *
         * @param args 当前 Binder 调用参数
         * @param hostPkg 宿主包名
         */
        private void replaceLastAppComponent(Object[] args, String hostPkg) {
            int index = ArrayUtils.indexOfLast(args, ComponentName.class);
            if (index != -1) {
                ComponentName original = (ComponentName) args[index];
                args[index] = new ComponentName(hostPkg, original.getClassName());
            }
        }
    }
}
