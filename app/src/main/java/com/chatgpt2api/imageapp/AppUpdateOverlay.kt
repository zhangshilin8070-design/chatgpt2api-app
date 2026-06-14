package com.chatgpt2api.imageapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppUpdateOverlay(state: AppUpdateState, onDismissOptional: () -> Unit) {
    if (!state.visible || state.info == null) return
    val info = state.info
    val context = LocalContext.current
    val openDownload = remember(info.downloadUrl) {
        {
            // 用浏览器打开下载链接，避免引入应用内下载器，符合 Requirement 5.5。
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            Unit
        }
    }
    when (state.mode) {
        UpdateMode.Optional -> AlertDialog(
            onDismissRequest = onDismissOptional,
            title = { Text("发现新版本 v${info.versionName}", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    info.releaseNotes.ifBlank { "建议更新到最新版本以获得更稳定的体验。" },
                    color = Glass.TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = { TextButton(onClick = openDownload) { Text("立即下载") } },
            dismissButton = { TextButton(onClick = onDismissOptional) { Text("下次再说") } },
            containerColor = Glass.Surface,
        )
        UpdateMode.Force -> ForceUpdateBlocker(info, openDownload, context)
    }
}

@Composable
private fun ForceUpdateBlocker(
    info: AppVersionInfo,
    onDownload: () -> Unit,
    context: android.content.Context,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = { (context as? Activity)?.finishAffinity() }) {
                        Text("退出 App")
                    }
                    FilledTonalButton(onClick = onDownload) { Text("立即下载") }
                }
            }
        }
    }
}
