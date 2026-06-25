package com.chatgpt2api.imageapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ParamsSheet(state: ComposerState, actions: ImageAppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val scrollState = rememberScrollState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Glass.Surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(20.dp),
        ) {
            SheetHeader("生成参数", "PARAMETERS")
            Spacer(Modifier.height(18.dp))

            ParamGroupTitle("模型 / 尺寸")
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = Glass.GlassBorder)
            SelectChip("模型", state.model, state.models, actions::updateModel)
            HorizontalDivider(color = Glass.GlassBorder)
            SelectChip(
                "尺寸", state.size,
                listOf("auto", "1:1", "3:2", "2:3", "16:9", "9:16", "21:9", "4:3", "3:4", "1024x1024", "1024x1536", "1536x1024"),
                actions::updateSize,
            )
            HorizontalDivider(color = Glass.GlassBorder)
            SelectChip("分辨率", state.imageResolution.ifBlank { "Auto" }, listOf("Auto", "1080p", "2k", "4k")) {
                actions.updateImageResolution(if (it == "Auto") "" else it)
            }
            HorizontalDivider(color = Glass.GlassBorder)

            Spacer(Modifier.height(18.dp))
            ParamGroupTitle("画质 / 输出")
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = Glass.GlassBorder)
            // gemini-3.1-flash-image 后端不接受 quality 参数，UI 上同步隐藏整行
            // （含其后紧跟的分隔线），避免出现连续两条分隔线。
            if (state.model != "gemini-3.1-flash-image") {
                SelectChip("质量", state.quality, listOf("auto", "low", "medium", "high"), actions::updateQuality)
                HorizontalDivider(color = Glass.GlassBorder)
            }
            SelectChip("格式", state.outputFormat, listOf("png", "jpeg", "webp"), actions::updateOutputFormat)
            HorizontalDivider(color = Glass.GlassBorder)
            SelectChip("数量", state.n.toString(), listOf("1", "2", "3", "4")) { actions.updateN(it.toIntOrNull() ?: 1) }
            HorizontalDivider(color = Glass.GlassBorder)
            SelectChip("可见性", if (state.visibility == "public") "公开" else "私密", listOf("私密", "公开")) {
                actions.updateVisibility(if (it == "公开") "public" else "private")
            }
            HorizontalDivider(color = Glass.GlassBorder)

            Spacer(Modifier.height(20.dp))
            PrimaryButton("完成", onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ParamGroupTitle(text: String) {
    Text(text.uppercase(), fontSize = 9.sp, color = Glass.TextSecondary, letterSpacing = 2.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(convo: ConversationState, actions: ImageAppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Glass.Surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SheetHeader("历史对话", "ARCHIVE")
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { actions.newConversation(); onDismiss() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.Ink)
                    Spacer(Modifier.width(4.dp))
                    Text("新对话", color = Glass.Ink, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (convo.conversations.isEmpty()) {
                Text(
                    "还没有历史对话",
                    color = Glass.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                convo.conversations.forEachIndexed { index, c ->
                    if (index > 0) HorizontalDivider(color = Glass.GlassBorder)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (c.id == convo.selectedId) Glass.SurfaceMuted else Color.Transparent)
                            .pointerInput(c.id) {
                                detectTapGestures(onTap = { actions.selectConversation(c.id); onDismiss() })
                            }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                    ) {
                        if (c.id == convo.selectedId) {
                            Box(modifier = Modifier.width(2.dp).height(28.dp).background(Glass.Accent))
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                c.title.ifBlank { "未命名对话" },
                                color = Glass.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Text(
                                "${c.turns.size} 轮 · ${formatTimestamp(c.updatedAt)}",
                                color = Glass.TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        IconButton(onClick = { actions.deleteConversation(c.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Glass.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

internal fun formatTimestamp(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> {
            val format = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            format.format(java.util.Date(epochMillis))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSheet(auth: AuthState, actions: ImageAppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Glass.Surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            SheetHeader("个人中心", "PROFILE")
            Spacer(Modifier.height(14.dp))
            Text(
                "服务端：${actions.effectiveBaseUrl().ifBlank { "未配置" }}",
                fontSize = 11.sp,
                color = Glass.TextSecondary,
                maxLines = 1,
            )

            Spacer(Modifier.height(18.dp))
            ProfileGroup("账号") {
                ProfileRow("昵称", auth.displayName.ifBlank { "—" })
                ProfileRow("用户名", auth.username.ifBlank { "—" })
                ProfileRow(
                    "角色",
                    buildString {
                        append(auth.roleName.ifBlank { auth.role.ifBlank { "—" } })
                        if (auth.role == "admin") append("（管理员）")
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            ProfileGroup("使用限额") {
                ProfileRow("并发额度", creationConcurrentLimitLabel(auth))
                ProfileRow("RPM 限制", creationRpmLimitLabel(auth))
            }
            Spacer(Modifier.height(14.dp))
            ProfileGroup("gpt-image-2 配额") {
                BillingBucketProfileRows(auth.billing, auth.billing.bucketA)
            }
            Spacer(Modifier.height(14.dp))
            ProfileGroup("codex / gemini 配额") {
                BillingBucketProfileRows(auth.billing, auth.billing.bucketB)
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton("关闭", onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title.uppercase(), fontSize = 9.sp, color = Glass.TextSecondary, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Glass.GlassBorder))
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Glass.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, color = Glass.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun creationConcurrentLimitLabel(auth: AuthState): String =
    if (auth.role == "admin" || auth.creationConcurrentLimit == 0) "不限制" else "${auth.creationConcurrentLimit} 个"

private fun creationRpmLimitLabel(auth: AuthState): String =
    if (auth.role == "admin" || auth.creationRpmLimit == 0) "不限制" else "${auth.creationRpmLimit} 次/分"

/**
 * 在 ProfileSheet 里渲染单个计费桶的明细行：计费类型 / 主额度 / 订阅扩展行。
 * 桶不存在时（[bucket.present]=false）只显示一行占位。
 */
@Composable
private fun BillingBucketProfileRows(billing: BillingSnapshot, bucket: BillingBucket) {
    if (billing.unlimited) {
        ProfileRow("计费类型", "无限额度")
        return
    }
    if (!bucket.present) {
        ProfileRow("计费类型", "--")
        return
    }
    ProfileRow("计费类型", billingTypeLabel(bucket))
    ProfileRow(billingPrimaryLabel(bucket), billingPrimaryValue(bucket))
    if (bucket.type == "subscription" && !bucket.unlimited) {
        ProfileRow("当期已用", bucket.quotaUsed.toString())
        if (bucket.quotaPeriodEndsAt.isNotBlank()) {
            ProfileRow("下次重置", bucket.quotaPeriodEndsAt)
        }
    } else if (bucket.type == "standard" && !bucket.unlimited) {
        ProfileRow("当前余额", bucket.balance.toString())
    }
}

private fun billingTypeLabel(bucket: BillingBucket): String = when {
    bucket.unlimited -> "无限额度"
    bucket.type == "subscription" -> "订阅配额制"
    bucket.type == "standard" -> "标准余额制"
    else -> "—"
}

private fun billingPrimaryLabel(bucket: BillingBucket): String =
    if (bucket.type == "subscription" && !bucket.unlimited) "剩余 / 上限" else "可用余额"

private fun billingPrimaryValue(bucket: BillingBucket): String = when {
    bucket.unlimited -> "不限制"
    bucket.type == "subscription" -> "${bucket.available} / ${bucket.quotaLimit}"
    bucket.type == "standard" -> bucket.availableBalance.toString()
    else -> bucket.available.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(actions: ImageAppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val configured = actions.effectiveBaseUrl()
    val theme by actions.theme.collectAsStateWithLifecycle()
    var themeMenuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Glass.Surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            SheetHeader("设置", "SETTINGS")
            Spacer(Modifier.height(14.dp))

            // 服务端地址行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { actions.openBaseUrlConfig() }) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("服务端地址", fontSize = 13.sp, color = Glass.TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        configured.ifBlank { "未配置" },
                        fontSize = 11.sp,
                        color = Glass.TextSecondary,
                        maxLines = 1,
                    )
                }
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = Glass.TextSecondary)
            }
            HorizontalDivider(color = Glass.GlassBorder)

            // 主题
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) { detectTapGestures(onTap = { themeMenuOpen = true }) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("主题", fontSize = 13.sp, color = Glass.TextPrimary, fontWeight = FontWeight.Medium)
                        Text(themeLabel(theme), fontSize = 11.sp, color = Glass.TextSecondary)
                    }
                    Icon(Icons.Default.UnfoldMore, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.TextSecondary)
                }
                DropdownMenu(
                    expanded = themeMenuOpen,
                    onDismissRequest = { themeMenuOpen = false },
                    modifier = Modifier.background(Glass.Surface),
                ) {
                    listOf(ThemePref.System, ThemePref.Light, ThemePref.Dark).forEach { value ->
                        val selected = value == theme
                        DropdownMenuItem(
                            text = {
                                Text(
                                    themeLabel(value),
                                    color = if (selected) Glass.Accent else Glass.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = { actions.setTheme(value); themeMenuOpen = false },
                        )
                    }
                }
            }
            HorizontalDivider(color = Glass.GlassBorder)

            // 清缓存
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { actions.clearImageCache() }) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("清除图片缓存", fontSize = 13.sp, color = Glass.TextPrimary, fontWeight = FontWeight.Medium)
                    Text("仅清理本地内存缓存", fontSize = 11.sp, color = Glass.TextSecondary)
                }
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Glass.TextSecondary)
            }
            HorizontalDivider(color = Glass.GlassBorder)

            // 加入 QQ 群
            val joinGroupContext = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/wAvLW3ejKi"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                joinGroupContext.startActivity(intent)
                            }
                        })
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("加入 QQ 群", fontSize = 13.sp, color = Glass.TextPrimary, fontWeight = FontWeight.Medium)
                    Text("折页 · 群号 441035011", fontSize = 11.sp, color = Glass.TextSecondary)
                }
                Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(16.dp), tint = Glass.TextSecondary)
            }
            HorizontalDivider(color = Glass.GlassBorder)

            // 关于
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("关于", fontSize = 13.sp, color = Glass.TextPrimary, fontWeight = FontWeight.Medium)
                Text("折页 · Folio  ${BuildConfigCompat.versionName()} (build ${BuildConfigCompat.versionCode()})", fontSize = 11.sp, color = Glass.TextSecondary)
                Text("配套服务端：chatgpt2api", fontSize = 11.sp, color = Glass.TextSecondary)
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton("关闭", onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun themeLabel(t: ThemePref): String = when (t) {
    ThemePref.System -> "跟随系统"
    ThemePref.Light -> "浅色"
    ThemePref.Dark -> "暗色"
}

private object BuildConfigCompat {
    /** versionName / versionCode 通过反射读取 BuildConfig 类，避免硬编码。 */
    private val klass: Class<*>? = runCatching { Class.forName("com.chatgpt2api.imageapp.BuildConfig") }.getOrNull()
    fun versionName(): String = klass?.getDeclaredField("VERSION_NAME")?.get(null)?.toString() ?: "—"
    fun versionCode(): String = klass?.getDeclaredField("VERSION_CODE")?.get(null)?.toString() ?: "—"
}
