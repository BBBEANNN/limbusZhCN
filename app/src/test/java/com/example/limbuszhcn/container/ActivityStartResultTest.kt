package com.example.limbuszhcn.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防止启动被系统取消或中止后，界面仍把正数错误码当成成功。
 */
class ActivityStartResultTest {
    /**
     * 首次启动、恢复任务和投递到已有 Activity 均属于正常的请求结果。
     */
    @Test
    fun `accepts successful activity and task results`() {
        listOf(0, 1, 2, 3, 99).forEach { result ->
            assertTrue("应接受系统成功码 $result", isActivityStartAccepted(result))
        }
    }

    /**
     * 后台切换取消、锁定任务限制和 START_ABORTED 都是正数错误码。
     */
    @Test
    fun `rejects nonfatal positive errors and fatal errors`() {
        listOf(-100, -1, 100, 101, 102, Int.MAX_VALUE, Int.MIN_VALUE).forEach { result ->
            assertFalse("不得把系统拒绝码 $result 当成成功", isActivityStartAccepted(result))
        }
    }
}
