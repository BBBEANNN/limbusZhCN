package com.lody.virtual.client.env;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;

import mirror.android.ddm.DdmHandleAppName;
import mirror.android.ddm.DdmHandleAppNameJBMR1;
import mirror.dalvik.system.VMRuntime;

/**
 * 维护虚拟应用进程的运行时身份、主线程 Handler 与位数信息。
 *
 * <p>进程名写入属于诊断增强功能，平台私有 API 不可用时不能阻断虚拟应用启动。</p>
 */
public class VirtualRuntime {

    private static final Handler sUIHandler = new Handler(Looper.getMainLooper());

    private static String sInitialPackageName;
    private static String sProcessName;

    /**
     * 获取虚拟进程主线程 Handler。
     *
     * @return 绑定主 Looper 的 Handler
     */
    public static Handler getUIHandler() {
        return sUIHandler;
    }

    /**
     * 获取当前虚拟应用声明的进程名。
     *
     * @return 虚拟进程名；尚未绑定应用时可能为 {@code null}
     */
    public static String getProcessName() {
        return sProcessName;
    }

    /**
     * 获取最先绑定到当前虚拟进程的包名。
     *
     * @return 初始包名；尚未绑定应用时可能为 {@code null}
     */
    public static String getInitialPackageName() {
        return sInitialPackageName;
    }

    /**
     * 初始化虚拟应用的进程身份。
     *
     * @param processName 虚拟应用声明的进程名
     * @param appInfo 虚拟应用信息，用于记录初始包名
     */
    public static void setupRuntime(String processName, ApplicationInfo appInfo) {
        if (sProcessName != null) {
            return;
        }
        sInitialPackageName = appInfo.packageName;
        sProcessName = processName;

        try {
            // Android 16 已移除旧版 Process.setArgV0 镜像。进程重命名只用于诊断，
            // 镜像不存在时保留系统分配的 :pN 名称即可。
            if (mirror.android.os.Process.setArgV0 != null) {
                mirror.android.os.Process.setArgV0.call(processName);
            }
        } catch (Throwable error) {
            VLog.w(VirtualRuntime.class.getSimpleName(),
                    "Unable to rename virtual process to %s: %s", processName, error);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (DdmHandleAppNameJBMR1.setAppName != null) {
                    DdmHandleAppNameJBMR1.setAppName.call(processName, 0);
                }
            } else if (DdmHandleAppName.setAppName != null) {
                DdmHandleAppName.setAppName.call(processName);
            }
        } catch (Throwable error) {
            VLog.w(VirtualRuntime.class.getSimpleName(),
                    "Unable to update DDMS app name to %s: %s", processName, error);
        }
    }

    /**
     * 把远程服务异常转换为统一的运行时异常。
     *
     * @param e 原始异常
     * @param <T> 调用点期望的返回类型
     * @return 该方法不会正常返回
     * @throws RuntimeException 始终抛出并包装原始异常
     */
    public static <T> T crash(Throwable e) throws RuntimeException {
        e.printStackTrace();
        throw new RuntimeException("transact remote server failed", e);
    }

    /**
     * 判断当前宿主进程是否为 64 位。
     *
     * @return 64 位进程返回 {@code true}
     */
    public static boolean is64bit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Process.is64Bit();
        }
        return VMRuntime.is64Bit.call(VMRuntime.getRuntime.call());

    }

    /**
     * 立即终止当前虚拟应用进程。
     */
    public static void exit() {
        VLog.d(VirtualRuntime.class.getSimpleName(), "Exit process : %s (%s).", getProcessName(), VirtualCore.get().getProcessName());
        Process.killProcess(android.os.Process.myPid());
    }


    /**
     * 判断当前运行时是否使用 ART。
     *
     * @return 使用 ART 时返回 {@code true}
     */
    public static boolean isArt() {
        return System.getProperty("java.vm.version").startsWith("2");
    }
}
