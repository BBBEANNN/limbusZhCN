package com.lody.virtual.client.hook.proxies.input;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.IInterface;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.annotations.Inject;

import mirror.com.android.internal.view.inputmethod.InputMethodManager;

/**
 * 代理系统输入法服务，并把虚拟应用的编辑器身份转换为宿主身份。
 *
 * <p>Android 15/16 的 {@code InputMethodManager} 主要通过静态全局调用器访问服务，
 * 仅替换旧版实例字段已经不足以覆盖 WebView 的输入连接，因此这里会同时维护旧实例字段、
 * 新版全局缓存和 ServiceManager 缓存。</p>
 *
 * @author Lody
 */
@Inject(MethodProxies.class)
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)

public class InputMethodManagerStub extends BinderInvocationProxy {
	private static final String TAG = InputMethodManagerStub.class.getSimpleName();

	/**
	 * 创建输入法服务代理。
	 */
	public InputMethodManagerStub() {
		super(
				resolveInputMethodManagerInterface(),
				Context.INPUT_METHOD_SERVICE);
	}

	/**
	 * 取得当前系统实际使用的输入法 Binder 接口。
	 *
	 * <p>先创建一次系统服务实例以初始化新版全局缓存，再优先读取该缓存；旧系统则回退到
	 * {@code InputMethodManager.mService}。</p>
	 *
	 * @return 可用于构建动态代理的真实输入法服务接口；读取失败时返回 {@code null}。
	 */
	private static IInterface resolveInputMethodManagerInterface() {
		Object inputMethodManager = VirtualCore.get().getContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (mirror.android.view.inputmethod.IInputMethodManagerGlobalInvoker.sServiceCache != null) {
			IInterface cachedService = mirror.android.view.inputmethod
					.IInputMethodManagerGlobalInvoker.sServiceCache.get();
			if (cachedService != null) {
				return cachedService;
			}
		}
		return InputMethodManager.mService != null
				? InputMethodManager.mService.get(inputMethodManager)
				: null;
	}

	/**
	 * 将代理同时注入旧版实例字段、新版全局缓存和系统服务缓存。
	 *
	 * @throws Throwable 反射写入或服务缓存替换失败时抛出，由统一注入器记录并隔离。
	 */
	@Override
	public void inject() throws Throwable {
		Object inputMethodManager = getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
		IInterface proxyInterface = getInvocationStub().getProxyInterface();
		if (InputMethodManager.mService != null) {
			InputMethodManager.mService.set(inputMethodManager, proxyInterface);
		}
		if (mirror.android.view.inputmethod.IInputMethodManagerGlobalInvoker.sServiceCache != null) {
			// Android 15/16 的日常输入调用从这里取服务，必须覆盖该静态缓存才能稳定拦截。
			mirror.android.view.inputmethod.IInputMethodManagerGlobalInvoker.sServiceCache
					.set(proxyInterface);
		}
		getInvocationStub().replaceService(Context.INPUT_METHOD_SERVICE);
	}

	/**
	 * 检查系统当前使用的输入法接口是否仍指向本代理。
	 *
	 * @return 代理丢失、需要重新注入时返回 {@code true}。
	 */
	@Override
	public boolean isEnvBad() {
		IInterface proxyInterface = getInvocationStub().getProxyInterface();
		if (mirror.android.view.inputmethod.IInputMethodManagerGlobalInvoker.sServiceCache != null) {
			return mirror.android.view.inputmethod.IInputMethodManagerGlobalInvoker.sServiceCache.get()
					!= proxyInterface;
		}
		Object inputMethodManager = getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
		return InputMethodManager.mService == null
				|| InputMethodManager.mService.get(inputMethodManager) != proxyInterface;
	}

}
