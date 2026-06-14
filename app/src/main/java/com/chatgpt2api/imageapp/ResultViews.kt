package com.chatgpt2api.imageapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

internal fun turnStatusLabel(turn: Turn): String = when (turn.status) {
    "queued" -> "排队中"
    "running" -> turn.progress.ifBlank { "处理中" }
    "success" -> if (turn.outputType == "text") "文本回复" else "已完成"
    "error" -> "失败"
    "cancelled" -> "已终止"
    else -> turn.status
}

internal fun outputStatusLabel(value: String): String = when (value) {
    "queued" -> "排队中"
    "running" -> "处理中"
    "success" -> "已完成"
    "error" -> "失败"
    "cancelled" -> "已终止"
    else -> value
}

/** 全屏图片预览：双指缩放 + 单指拖拽。点空白处关闭，左下角"保存"。 */
@Composable
internal fun ImageLightbox(result: ImageResult, baseUrl: String, onSave: () -> Unit, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE0A0A0A))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClose() })
                },
            contentAlignment = Alignment.Center,
        ) {
            val transformableState = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                if (scale > 1f) {
                    offsetX += pan.x
                    offsetY += pan.y
                } else {
                    offsetX = 0f
                    offsetY = 0f
                }
            }
            val imageMod = Modifier
                .fillMaxWidth()
                .aspectRatio(1f, matchHeightConstraintsFirst = false)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .transformable(transformableState)

            if (result.b64Json.isNotBlank()) {
                var bitmap by remember(result.b64Json) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                LaunchedEffect(result.b64Json) {
                    // lightbox 允许双指放大，给屏幕宽度 ~3 倍的边长足够清晰
                    bitmap = BitmapDecoders.decodeBase64(result.b64Json, targetMaxEdge = 2560)
                }
                val bm = bitmap
                if (bm != null) {
                    Image(
                        bitmap = bm,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = imageMod,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = Color.White)
                }
            } else if (result.url.isNotBlank()) {
                AsyncImage(
                    model = absoluteImageUrl(baseUrl, result.url),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = imageMod,
                )
            }

            // 顶部右关闭按钮：距顶 16dp，正常的全屏 Dialog 不会延伸到状态栏内
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 16.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            // "保存到相册"按钮：屏幕下方 80dp 处，避开导航条且容易点
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .pointerInput(result) { detectTapGestures(onTap = { onSave() }) }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF111111), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "保存到相册",
                    color = Color(0xFF111111),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * 流式 token 到达时的节流壳：仅在 [throttle]=true 时把 [content] 用 ~80ms 频率
 * 投递给 [MarkdownText]，避免每个 token 都触发 markdown 解析与子树重组；
 * 流结束（throttle=false）时立刻把最新内容渲染出来。
 */
@Composable
internal fun ThrottledMarkdown(content: String, throttle: Boolean, color: Color) {
    var displayed by remember { mutableStateOf(content) }
    LaunchedEffect(content, throttle) {
        if (!throttle) {
            displayed = content
            return@LaunchedEffect
        }
        // 流式：等到下一帧节奏点再更新；80ms 既保留打字机感又压住重组频率
        kotlinx.coroutines.delay(80)
        displayed = content
    }
    MarkdownText(content = displayed, color = color, fontSize = 14.sp)
}

/**
 * 输入区上方的紧凑额度条：
 *  - 顶层 unlimited=true 时整体不显示，无限额度账号不浪费空间。
 *  - 否则同时展示两个桶的数字，左标签 `A · gpt-image-2`、右标签 `B · codex / gemini`。
 *  - 每个桶按其自身 unlimited / type / present 决定显示「∞」「剩余/上限」「可用余额」「--」。
 *  - 整行点击跳转个人中心。
 */
@Composable
internal fun BillingBar(auth: AuthState, onClick: () -> Unit) {
    val billing = auth.billing
    if (billing.unlimited) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Glass.Paper)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BillingBucketChip(
                eyebrow = "A · gpt-image-2",
                bucket = billing.bucketA,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(14.dp))
            BillingBucketChip(
                eyebrow = "B · codex / gemini",
                bucket = billing.bucketB,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Person,
                contentDescription = "个人中心",
                tint = Glass.TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Glass.GlassBorder))
    }
}

/**
 * 单个桶在 BillingBar 里的紧凑展示：eyebrow 标签 + 数字主体（订阅制带进度条 / 标准制只显示可用余额 /
 * 不存在显示 "--"）。
 */
@Composable
private fun BillingBucketChip(eyebrow: String, bucket: BillingBucket, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            eyebrow.uppercase(),
            color = Glass.TextSecondary,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        when {
            !bucket.present -> {
                Text("--", color = Glass.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            bucket.unlimited -> {
                Text("∞", color = Glass.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            bucket.type == "subscription" -> {
                val cap = bucket.quotaLimit
                val available = bucket.available
                val ratio = if (cap > 0) (available.toFloat() / cap.toFloat()).coerceIn(0f, 1f) else 0f
                val low = cap > 0 && ratio < 0.15f
                val accentColor = if (low) Glass.Accent else Glass.Ink
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$available", color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (cap > 0) {
                        Text(" / $cap", color = Glass.TextSecondary, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Glass.GlassBorder),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(2.dp)
                            .background(accentColor),
                    )
                }
            }
            bucket.type == "standard" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${bucket.availableBalance}",
                        color = Glass.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(" 可用", color = Glass.TextSecondary, fontSize = 11.sp)
                }
            }
            else -> {
                Text("--", color = Glass.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 顶部浅色掉线条：朱红 4dp accent + 单行说明，朱红呼应错误态。 */
@Composable
internal fun OfflineBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Glass.AccentSoft)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Glass.Accent))
        Spacer(Modifier.width(8.dp))
        Text("当前无网络连接，操作将失败", color = Glass.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 结果图下方的元数据行：尺寸 · 格式 · 大小 · 相对时间。任一段缺失则跳过。 */
@Composable
internal fun ResultMetaLine(turn: Turn, result: ImageResult, measuredWidth: Int = 0, measuredHeight: Int = 0) {
    val parts = buildList {
        // 优先使用：服务端返回的精确尺寸 → 客户端解码出的实际尺寸 → 任务里申请的尺寸字符串
        val w = if (result.width > 0) result.width else measuredWidth
        val h = if (result.height > 0) result.height else measuredHeight
        if (w > 0 && h > 0) {
            add("$w × $h")
        } else if (turn.size.isNotBlank() && turn.size != "auto") {
            add(turn.size)
        }
        val format = turn.outputFormat.takeIf { it.isNotBlank() }
            ?: result.url.substringAfterLast('.', "").takeIf { it.length in 1..5 }
        if (!format.isNullOrBlank()) add(format.uppercase())
        if (result.sizeBytes > 0) add(humanSize(result.sizeBytes))
        add(formatTimestamp(turn.createdAt))
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        color = Glass.TextSecondary,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
    )
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes}B" else String.format(java.util.Locale.ROOT, "%.1f%s", v, units[i])
}

/**
 * 提示词优化结果反馈：成功 / 失败 / 超时 / 已取消都给一个明确弹窗。
 * 失败时附 HTTP code 与服务端返回体（截断 1KB），用户能直接复制贴给我做诊断。
 */
@Composable
internal fun OptimizeFeedbackDialog(feedback: OptimizeFeedback, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (feedback) {
                    is OptimizeFeedback.Success -> Icons.Default.AutoFixHigh to Glass.Accent
                    is OptimizeFeedback.Cancelled -> Icons.Default.Cancel to Glass.TextSecondary
                    is OptimizeFeedback.Timeout -> Icons.Default.Refresh to Glass.Error
                    is OptimizeFeedback.Failure -> Icons.Default.Close to Glass.Error
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(feedback.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Glass.TextPrimary)
            }
        },
        text = {
            Column {
                when (feedback) {
                    is OptimizeFeedback.Success -> {
                        Text(
                            "原 ${feedback.originalLength} 字 → 优化后 ${feedback.optimizedLength} 字",
                            color = Glass.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    }
                    is OptimizeFeedback.Cancelled -> {
                        Text("已取消本次优化。", color = Glass.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    is OptimizeFeedback.Timeout -> {
                        Text(
                            "30 秒内未收到服务端返回，可能是服务端在排队或代理/上游不可用。请稍后重试。",
                            color = Glass.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    }
                    is OptimizeFeedback.Failure -> {
                        Text(feedback.reason, color = Glass.TextPrimary, fontSize = 13.sp, lineHeight = 20.sp)
                        if (feedback.httpCode != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "HTTP ${feedback.httpCode}",
                                color = Glass.TextSecondary,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                        if (!feedback.rawBody.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "服务端返回",
                                color = Glass.TextSecondary,
                                fontSize = 9.sp,
                                letterSpacing = 1.5.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .background(Glass.SurfaceMuted, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                            ) {
                                Text(
                                    feedback.rawBody,
                                    color = Glass.TextPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    lineHeight = 16.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(feedback.rawBody))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp), tint = Glass.Ink)
                                Spacer(Modifier.width(4.dp))
                                Text("复制错误详情", color = Glass.Ink, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好的", color = Glass.Ink) }
        },
    )
}
