package mirror.android.view.inputmethod;

import android.os.IInterface;

import mirror.RefClass;
import mirror.RefStaticObject;

/**
 * 映射 Android 15/16 输入法全局调用器中的服务缓存。
 *
 * <p>该系统类不是公开 SDK，但新版 {@code InputMethodManager} 会从这里取得
 * {@code IInputMethodManager}。VirtualApp 需要同步替换此缓存，才能让 WebView 的
 * 输入连接继续经过包名修正代理。</p>
 */
public class IInputMethodManagerGlobalInvoker {
    /**
     * 对应真实系统类；旧系统不存在该类时为 {@code null}。
     */
    public static Class<?> TYPE = RefClass.load(IInputMethodManagerGlobalInvoker.class,
            "android.view.inputmethod.IInputMethodManagerGlobalInvoker");

    /**
     * 系统缓存的输入法 Binder 接口。
     */
    public static RefStaticObject<IInterface> sServiceCache;
}
