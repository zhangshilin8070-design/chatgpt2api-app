package com.chatgpt2api.imageapp

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内更新流程状态机。
 *
 * Idle 表示空闲；Downloading 携带 0..100 进度；Failed 携带可显示的错误描述。
 * 流程在 [AppUpdateOverlay] 内部维护，不抬到 ViewModel，因为下载是一次性
 * 操作，且只有在弹窗可见时才有意义。
 */
private sealed class DownloadUiState {
    object Idle : DownloadUiState()
    data class Downloading(val percent: Int) : DownloadUiState()
    data class Failed(val message: String) : DownloadUiState()
}

@Composable
internal fun AppUpdateOverlay(state: AppUpdateState, onDismissOptional: () -> Unit) {
    if (!state.visible || state.info == null) return
    val info = state.info
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadState by remember { mutableStateOf<DownloadUiState>(DownloadUiState.Idle) }

    /**
     * 触发下载 → 安装的统一入口。
     * 优先走应用内 OkHttp 下载 + FileProvider + ACTION_VIEW；任何一步失败
     * 均回退到浏览器打开 downloadUrl。下载进度以 percent 显示给用户。
     */
    val startUpdate: () -> Unit = {
        if (downloadState is DownloadUiState.Downloading) {
            // 已在下载，忽略二次点击。
        } else {
            downloadState = DownloadUiState.Downloading(0)
            scope.launch {
                runUpdateFlow(
                    context = context,
                    downloadUrl = info.downloadUrl,
                    versionName = info.versionName,
                    onProgress = { percent ->
                        downloadState = DownloadUiState.Downloading(percent)
                    },
                    onFailed = { message ->
                        downloadState = DownloadUiState.Failed(message)
                    },
                )
            }
        }
    }

    when (state.mode) {
        UpdateMode.Optional -> AlertDialog(
            onDismissRequest = {
                // 下载进行中不允许误点空白处关闭，避免后台下载丢失感知。
                if (downloadState !is DownloadUiState.Downloading) onDismissOptional()
            },
            title = { Text("发现新版本 v${info.versionName}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        info.releaseNotes.ifBlank { "建议更新到最新版本以获得更稳定的体验。" },
                        color = Glass.TextSecondary,
                        fontSize = 13.sp,
                    )
                    DownloadStatusRow(downloadState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = startUpdate,
                    enabled = downloadState !is DownloadUiState.Downloading,
                ) {
                    Text(if (downloadState is DownloadUiState.Failed) "重试" else "立即下载")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissOptional,
                    enabled = downloadState !is DownloadUiState.Downloading,
                ) { Text("下次再说") }
            },
            containerColor = Glass.Surface,
        )
        UpdateMode.Force -> ForceUpdateBlocker(info, startUpdate, downloadState, context)
    }
}

@Composable
private fun DownloadStatusRow(state: DownloadUiState) {
    when (state) {
        DownloadUiState.Idle -> Unit
        is DownloadUiState.Downloading -> {
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            Text("正在下载 ${state.percent}%", fontSize = 12.sp, color = Glass.TextSecondary)
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (state.percent.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        is DownloadUiState.Failed -> {
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            Text(state.message, fontSize = 12.sp, color = Color(0xFFB91C1C))
            Text("已自动改用浏览器下载。", fontSize = 11.sp, color = Glass.TextSecondary)
        }
    }
}

@Composable
private fun ForceUpdateBlocker(
    info: AppVersionInfo,
    onDownload: () -> Unit,
    downloadState: DownloadUiState,
    context: Context,
) {
    // 强制更新模式下拦截系统返回键，防止用户绕过。
    BackHandler(enabled = true) { /* 阻塞返回 */ }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            // 接管全部点击事件以阻塞下层业务首屏交互（Requirement 5.6 / 8.5）。
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp),
            shape = RoundedCornerShape(20.dp),
            color = Glass.Surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "需要更新到 v${info.versionName}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Glass.TextPrimary,
                )
                Text(
                    info.releaseNotes.ifBlank { "当前版本已不再受支持，请下载最新版继续使用。" },
                    color = Glass.TextSecondary,
                    fontSize = 13.sp,
                )
                DownloadStatusRow(downloadState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = { (context as? Activity)?.finishAffinity() },
                        enabled = downloadState !is DownloadUiState.Downloading,
                    ) { Text("退出 App") }
                    FilledTonalButton(
                        onClick = onDownload,
                        enabled = downloadState !is DownloadUiState.Downloading,
                    ) { Text(if (downloadState is DownloadUiState.Failed) "重试" else "立即下载") }
                }
            }
        }
    }
}

/**
 * 在协程内完成下载 → 安装；任何阶段失败都自动回退浏览器并通过 [onFailed]
 * 把用户可见的错误描述写回 UI。本函数不阻塞主线程：下载发生在 IO 调度器。
 */
private suspend fun runUpdateFlow(
    context: Context,
    downloadUrl: String,
    versionName: String,
    onProgress: (Int) -> Unit,
    onFailed: (String) -> Unit,
) {
    if (!ApkInstaller.canInstallApks(context)) {
        ApkInstaller.openDownloadInBrowser(context, downloadUrl)
        withContext(Dispatchers.Main) {
            onFailed("当前系统未授予「安装未知应用」权限")
        }
        return
    }
    var apk: File? = null
    var errorMessage: String? = null
    ApkInstaller.downloadApk(context, downloadUrl, versionName) { event ->
        when (event) {
            is ApkInstaller.DownloadEvent.Progress -> onProgress(event.percent)
            is ApkInstaller.DownloadEvent.Completed -> apk = event.file
            is ApkInstaller.DownloadEvent.Failed -> errorMessage = event.message
        }
    }
    withContext(Dispatchers.Main) {
        val file = apk
        if (file != null && file.exists() && file.length() > 0) {
            val launched = ApkInstaller.installApk(context, file)
            if (!launched) {
                ApkInstaller.openDownloadInBrowser(context, downloadUrl)
                onFailed("调起安装器失败")
            }
        } else {
            ApkInstaller.openDownloadInBrowser(context, downloadUrl)
            onFailed(errorMessage ?: "下载失败")
        }
    }
}
