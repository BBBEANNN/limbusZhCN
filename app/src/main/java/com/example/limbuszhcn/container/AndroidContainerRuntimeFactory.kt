package com.example.limbuszhcn.container

import android.content.Context
import java.nio.file.Path

object AndroidContainerRuntimeFactory {
    fun create(context: Context, workspaceRoot: Path): ContainerRuntime {
        val local = LocalContainerRuntime(workspaceRoot)
        return if (VirtualAppContainerRuntime.isEnginePackaged()) {
            VirtualAppContainerRuntime(context.applicationContext, local)
        } else {
            EngineMissingContainerRuntime(local)
        }
    }
}

private class EngineMissingContainerRuntime(
    private val local: LocalContainerRuntime
) : ContainerRuntime by local {
    override fun status(): ContainerStatus {
        val current = local.status()
        return current.copy(
            available = false,
            message = current.message + "; 真实容器引擎未打包,当前仅可测试本地工作区"
        )
    }

    override fun launchGame() {
        throw ContainerRuntimeUnavailableException("真实容器引擎未打包;需要先接入 VirtualApp lib 后才能启动游戏并让游戏下载文本")
    }
}
