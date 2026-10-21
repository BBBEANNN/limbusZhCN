package com.example.limbuszhcn.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.limbuszhcn.BuildConfig

/**
 * 展示汉化器版本，并仅在宿主可交互且游戏准备任务结束后续接 Android 系统安装流程。
 *
 * @param model 跨配置变更保留的更新状态。
 * @param hostBusy 游戏同步、汉化安装或资源迁移是否正在执行。
 */
@Composable
internal fun AppUpdateCard(model: AppUpdateViewModel, hostBusy: Boolean) {
    val context = LocalContext.current
    val state = model.state
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // 成功覆盖会结束旧进程；能返回此处时，保留重试入口且不自动再次弹出安装。
        model.finishExternalFlow("已返回汉化器；如未完成更新，可点击安装新版")
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.finishExternalFlow("未允许安装新版，可稍后点击安装新版重试")
        if (context.packageManager.canRequestPackageInstalls()) model.requestInstallation()
    }

    LaunchedEffect(state.automaticInstallPending, model.hostResumed, hostBusy) {
        if (hostBusy || !model.hostResumed) return@LaunchedEffect
        val update = model.consumeInstallationRequest() ?: return@LaunchedEffect
        runCatching {
            if (!context.packageManager.canRequestPackageInstalls()) {
                model.beginExternalFlow("请在系统设置中允许汉化器安装应用，返回后继续更新")
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } else {
                // 只分享更新专用缓存的只读 URI，不把容器或游戏数据暴露给外部安装器。
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.selfupdate.fileprovider", update.file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("汉化器更新", uri)
                }
                model.beginExternalFlow("已打开系统安装界面，请确认安装新版 ${update.versionName}")
                installer.launch(intent)
            }
        }.onFailure { error ->
            model.finishExternalFlow("可稍后重试安装")
            model.reportFailure("无法打开系统安装界面", error)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("汉化器 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            if (state.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("发现新版会自动下载，最后需在系统界面确认安装。", style = MaterialTheme.typography.bodySmall)
            if (state.update != null) {
                OutlinedButton(onClick = model::requestInstallation, enabled = !hostBusy && !state.working) {
                    Text("安装新版 ${state.update.versionName}")
                }
            }
            OutlinedButton(onClick = model::checkForUpdates, enabled = !hostBusy && !state.working) {
                Text("重新检查汉化器版本")
            }
        }
    }
}
