package com.lody.virtual.client.hook.proxies.input;

import android.view.inputmethod.EditorInfo;

import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Method;

/**
 * 输入法服务的方法代理集合。
 *
 * <p>虚拟应用与宿主共享同一个真实 UID，因此提交给系统输入法的
 * {@link EditorInfo#packageName} 必须使用宿主包名，否则 Android 会以包名和 UID
 * 不匹配为由拒绝建立输入连接。</p>
 *
 * @author Lody
 */

class MethodProxies {

    static class StartInput extends StartInputOrWindowGainedFocus {

        @Override
        public String getMethodName() {
            return "startInput";
        }
    }

    static class WindowGainedFocus extends StartInputOrWindowGainedFocus {

        @Override
        public String getMethodName() {
            return "windowGainedFocus";
        }


    }

    /**
     * 兼容 Android 15/16 的异步输入连接入口。
     *
     * <p>新系统可能通过此方法重新建立输入连接；如果只代理同步入口，WebView
     * 输入框会出现“首次弹出键盘、随后又被系统撤销”的间歇性问题。</p>
     */
    static class StartInputOrWindowGainedFocusAsync extends StartInputOrWindowGainedFocus {

        @Override
        public String getMethodName() {
            return "startInputOrWindowGainedFocusAsync";
        }
    }

    static class StartInputOrWindowGainedFocus extends MethodProxy {


        @Override
        public String getMethodName() {
            return "startInputOrWindowGainedFocus";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int editorInfoIndex = ArrayUtils.indexOfFirst(args, EditorInfo.class);
            if (editorInfoIndex != -1) {
                EditorInfo attribute = (EditorInfo) args[editorInfoIndex];
                // 系统会校验 EditorInfo 包名是否属于 Binder 调用者 UID；这里必须改成宿主包名。
                attribute.packageName = getHostPkg();
            }
            return method.invoke(who, args);
        }
    }
}
