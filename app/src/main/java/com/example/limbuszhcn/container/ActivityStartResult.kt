package com.example.limbuszhcn.container

/**
 * 按 Android 的成功码区间判断 VirtualApp 启动请求是否被接受。
 *
 * 正数不全是成功：100 起的非致命错误包含后台切换取消和启动中止，
 * 不能把它们显示成启动成功。被接受也只代表请求结果，后续仍可能发生游戏进程崩溃。
 *
 * @param result VActivityManager.startActivity 返回的系统启动结果码。
 * @return 结果位于 Android 保留的 0 至 99 成功区间时返回 true。
 */
internal fun isActivityStartAccepted(result: Int): Boolean = result in 0..99
