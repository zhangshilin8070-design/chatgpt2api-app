package com.chatgpt2api.imageapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Composer(
    state: ComposerState,
    actions: ImageAppViewModel,
    onOpenParams: () -> Unit,
    onOpenPromptMarket: () -> Unit,
) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) actions.addReferences(uris)
    }
    // Composer 的 IME / 导航栏 padding 由外层 ConversationScreen 的底部组统一处理；
    // 此处只关心内部 padding，避免双层抬升。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Glass.Paper),
    ) {
        // 顶部 hairline 与上方对话区分隔
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Glass.GlassBorder))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 一排：模式切换 + 右侧次级动作（刷新模型 / 参数）；参数仅作画模式可见。
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeChip("对话", Icons.Default.Chat, state.mode == ComposerMode.Chat) { actions.setComposerMode(ComposerMode.Chat) }
                Spacer(Modifier.width(6.dp))
                ModeChip("作画", Icons.Default.Image, state.mode == ComposerMode.Image) { actions.setComposerMode(ComposerMode.Image) }
                Spacer(Modifier.weight(1f))
                if (state.refreshingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp, color = Glass.Ink)
                } else {
                    IconButton(onClick = actions::refreshModels, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新模型", tint = Glass.Ink, modifier = Modifier.size(16.dp))
                    }
                }
                // 清空输入：仅当 prompt 非空时出现，避免视觉干扰
                if (state.prompt.isNotEmpty()) {
                    IconButton(onClick = { actions.updatePrompt("") }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "清空输入", tint = Glass.Ink, modifier = Modifier.size(16.dp))
                    }
                }
                if (state.mode == ComposerMode.Image) {
                    IconButton(onClick = onOpenParams, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = "参数", tint = Glass.Ink, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // 参考图：横向滚动条而不是 FlowRow 自动换行，避免多张图把 Composer 顶高。
            if (state.mode == ComposerMode.Image && state.industryOptions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                IndustryChipRow(
                    options = state.industryOptions,
                    selected = state.industryKey,
                    onSelect = actions::updateIndustryKey,
                )
            }
            if (state.pendingReferences.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    items(state.pendingReferences.size) { index ->
                        ReferenceThumb(state.pendingReferences[index]) { actions.removeReference(index) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // 文本输入：用更小的 minHeight，让短消息时 Composer 矮一点；长内容仍能滚动。
            OutlinedTextField(
                value = state.prompt,
                onValueChange = actions::updatePrompt,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 120.dp),
                placeholder = {
                    Text(
                        when {
                            state.mode == ComposerMode.Chat -> "输入消息与AI聊天"
                            state.pendingReferences.isNotEmpty() -> "描述你希望如何修改参考图"
                            else -> "输入你想要生成的画面"
                        },
                        fontSize = 13.sp,
                        color = Glass.TextSecondary,
                    )
                },
                shape = RoundedCornerShape(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Glass.TextPrimary),
                colors = glassFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            // 工具行：左侧紧凑图标按钮（参考图 / 优化），右侧主按钮（发送/编辑/生成）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.mode == ComposerMode.Image) {
                    IconActionButton(
                        icon = Icons.Default.AddPhotoAlternate,
                        contentDescription = "参考图",
                        onClick = { imagePicker.launch("image/*") },
                    )
                    Spacer(Modifier.width(6.dp))
                }
                IconActionButton(
                    icon = Icons.Default.AutoFixHigh,
                    contentDescription = if (state.optimizing) "再点取消优化" else "优化提示词",
                    onClick = actions::optimizePrompt,
                    // 优化中点一下取消，所以 optimizing 时仍可点；只有空 prompt 才禁用
                    enabled = state.optimizing || state.prompt.isNotBlank(),
                    loading = state.optimizing,
                )
                Spacer(Modifier.width(6.dp))
                IconActionButton(
                    icon = Icons.Default.Storefront,
                    contentDescription = "提示词市场",
                    onClick = onOpenPromptMarket,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = actions::submit,
                    enabled = !state.busy && state.prompt.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Glass.Ink,
                        contentColor = Color.White,
                        disabledContainerColor = Glass.Ink.copy(alpha = 0.3f),
                        disabledContentColor = Color.White.copy(alpha = 0.6f),
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                state.mode == ComposerMode.Chat -> "发送"
                                state.pendingReferences.isNotEmpty() -> "编辑"
                                else -> "生成"
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** 工具栏圆角小图标按钮：白底 + 1dp 描边，比 SecondaryButton 更紧凑。 */
@Composable
private fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val borderColor = if (enabled) Glass.GlassBorder else Glass.GlassBorder.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Glass.Surface)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Glass.Ink)
        } else {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) Glass.Ink else Glass.TextSecondary,
            )
        }
    }
}

/** 行业提示词选择行：单行滚动 chip 列表；点击切换选中状态，再点一次取消。 */
@Composable
private fun IndustryChipRow(
    options: List<IndustryPromptOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(options.size) { index ->
            val opt = options[index]
            val active = opt.industryKey == selected
            val bg = if (active) Glass.Ink else Glass.Surface
            val fg = if (active) Color.White else Glass.Ink
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(bg)
                    .border(1.dp, if (active) Glass.Ink else Glass.GlassBorder, RoundedCornerShape(999.dp))
                    .pointerInput(active) {
                        detectTapGestures(onTap = {
                            onSelect(if (active) "" else opt.industryKey)
                        })
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (opt.hasOverride) "${opt.label} · 我的" else opt.label,
                    fontSize = 12.sp,
                    color = fg,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReferenceThumb(ref: ReferenceBytes, onRemove: () -> Unit) {
    var bitmap by remember(ref) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(ref) {
        bitmap = BitmapDecoders.decodeBytes(ref.bytes, targetMaxEdge = 192, useThumbnailConfig = true)
    }
    Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Glass.SurfaceMuted).border(1.dp, Glass.GlassBorder, RoundedCornerShape(8.dp))) {
        val bm = bitmap
        if (bm != null) {
            Image(bitmap = bm, contentDescription = ref.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(20.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(11.dp), tint = Glass.TextSecondary)
        }
    }
}
