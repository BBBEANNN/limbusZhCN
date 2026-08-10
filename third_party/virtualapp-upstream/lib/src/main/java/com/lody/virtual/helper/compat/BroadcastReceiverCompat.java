package com.lody.virtual.helper.compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/**
 * 为动态广播接收器提供跨 Android 版本的安全注册入口。
 *
 * <p>Android 13 及以上版本要求应用为非纯系统广播显式声明接收器是否导出。
 * VirtualApp 同时包含宿主内部广播和系统广播，因此必须按发送方身份选择不同标志，
 * 不能统一使用同一种导出策略。</p>
 */
public final class BroadcastReceiverCompat {

    private BroadcastReceiverCompat() {
        // 工具类不保存状态，禁止实例化以避免误用。
    }

    /**
     * 注册仅接收当前宿主应用及其同 UID 进程广播的动态接收器。
     *
     * @param context 负责注册接收器的宿主上下文
     * @param receiver 要注册的广播接收器
     * @param filter 接收器关注的广播过滤器
     * @return 注册时命中的粘性广播；没有粘性广播时返回 {@code null}
     */
    public static Intent registerInternalReceiver(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter) {
        return registerReceiver(
                context,
                receiver,
                filter,
                null,
                null,
                Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * 注册仅接收当前宿主应用及其同 UID 进程广播的动态接收器，并指定调度线程。
     *
     * @param context 负责注册接收器的宿主上下文
     * @param receiver 要注册的广播接收器
     * @param filter 接收器关注的广播过滤器
     * @param broadcastPermission 发送方必须持有的权限；不限制时传入 {@code null}
     * @param scheduler 分发回调使用的处理器；使用主线程时传入 {@code null}
     * @return 注册时命中的粘性广播；没有粘性广播时返回 {@code null}
     */
    public static Intent registerInternalReceiver(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter,
            String broadcastPermission,
            Handler scheduler) {
        return registerReceiver(
                context,
                receiver,
                filter,
                broadcastPermission,
                scheduler,
                Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * 注册需要接收 Android 系统或其他高权限 UID 广播的动态接收器。
     *
     * @param context 负责注册接收器的宿主上下文
     * @param receiver 要注册的广播接收器
     * @param filter 接收器关注的系统广播过滤器
     * @return 注册时命中的粘性广播；没有粘性广播时返回 {@code null}
     */
    public static Intent registerSystemReceiver(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter) {
        return registerReceiver(
                context,
                receiver,
                filter,
                null,
                null,
                Context.RECEIVER_EXPORTED);
    }

    /**
     * 根据系统版本选择带导出标志或旧式签名的注册接口。
     *
     * @param context 负责注册接收器的宿主上下文
     * @param receiver 要注册的广播接收器
     * @param filter 接收器关注的广播过滤器
     * @param broadcastPermission 发送方必须持有的权限
     * @param scheduler 分发回调使用的处理器
     * @param receiverFlags Android 13 及以上版本使用的接收器导出标志
     * @return 注册时命中的粘性广播；没有粘性广播时返回 {@code null}
     */
    private static Intent registerReceiver(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter,
            String broadcastPermission,
            Handler scheduler,
            int receiverFlags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.registerReceiver(
                    receiver,
                    filter,
                    broadcastPermission,
                    scheduler,
                    receiverFlags);
        }

        // Android 12 及以下没有导出标志参数，沿用原有动态接收器注册行为。
        return context.registerReceiver(receiver, filter, broadcastPermission, scheduler);
    }
}
