package com.chatgpt2api.imageapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationScreen(
    auth: AuthState,
    composer: ComposerState,
    convo: ConversationState,
    actions: ImageAppViewModel,
) {
    var showHistory by remember { mutableStateOf(false) }
    var showParams by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPromptMarket by remember { mutableStateOf(false) }
    var lightboxState by remember { mutableStateOf<ImageResult?>(null) }
    val listState = rememberLazyListState()
    val turns = convo.selected?.turns.orEmpty()
    val online by actions.online.collectAsStateWithLifecycle()
    // 自动跟随底部：触发条件是
    //   - 轮次数变化（用户发送新消息 / 切换会话）
    //   - 最后一轮 id 变化（防止首次列表初始化时漏滚）
    //   - 最后一轮状态/结果数/错误/进度文本变化（流式推进时跟随）
    val lastTurnSignature = remember(turns) {
        val last = turns.lastOrNull()
        if (last == null) "0:" else {
            // 流式 Chat 的内容只长在 results[0].textResponse，专门把它的长度纳入签名
            // 让 LaunchedEffect 在每个 token 到达时都触发跟随滚动。
            val streamLen = last.results.firstOrNull()?.textResponse?.length ?: 0
            "${turns.size}:${last.id}:${last.status}:${last.results.size}:$streamLen:${last.progress.length}:${last.error.length}"
        }
    }
    LaunchedEffect(lastTurnSignature) {
        if (turns.isNotEmpty()) {
            scrollToConversationBottom(listState, turns.size - 1)
        }
    }
    // 键盘弹起时也跟随到底，避免最后一条被键盘挡住
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && turns.isNotEmpty()) {
            scrollToConversationBottom(listState, turns.size - 1)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ConversationTopBar(
                auth, convo, actions,
                onOpenHistory = { showHistory = true },
                onOpenProfile = { showProfile = true },
                onOpenSettings = { showSettings = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!online) OfflineBar()
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (turns.isEmpty()) {
                    item { EmptyConversation(composer.mode) }
                } else {
                    items(turns, key = { it.id }) { turn ->
                        TurnBubbles(
                            turn = turn,
                            baseUrl = actions.effectiveBaseUrl(),
                            onCancel = { convo.selectedId?.let { actions.cancelTurn(it, turn.id) } },
                            onContinueEdit = { actions.continueEditFrom(it) },
                            onDownload = { actions.downloadResult(it) },
                            onPreview = { lightboxState = it },
                            onRegenerate = { convo.selectedId?.let { actions.regenerateTurn(it, turn.id) } },
                            shareTextResolver = { result -> actions.shareResultUrlText(result) },
                            onCopied = { actions.consumeCopySignal(it) },
                        )
                    }
                }
                if (composer.error.isNotBlank()) {
                    item { Banner(composer.error, Glass.Error, Glass.ErrorBg) }
                }
            }
            // 底部组（计费 + Composer）单独跟随 IME 与导航栏；
            // 键盘弹起时只这一组被顶起，LazyColumn 因 weight(1f) 自动收缩，最后一条始终贴在输入框上方。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                BillingBar(auth) { showProfile = true }
                Composer(
                    composer,
                    actions,
                    onOpenParams = { showParams = true },
                    onOpenPromptMarket = { showPromptMarket = true },
                )
            }
        }
    }

    if (showHistory) HistorySheet(convo, actions, onDismiss = { showHistory = false })
    if (showParams) ParamsSheet(composer, actions, onDismiss = { showParams = false })
    if (showPromptMarket) {
        PromptMarketSheet(
            onPick = actions::applyPromptPreset,
            onDismiss = { showPromptMarket = false },
        )
    }
    if (showProfile) ProfileSheet(auth, actions, onDismiss = { showProfile = false })
    if (showSettings) SettingsSheet(actions, onDismiss = { showSettings = false })
    lightboxState?.let { ImageLightbox(it, actions.effectiveBaseUrl(), { actions.downloadResult(it) }, { lightboxState = null }) }
}

/**
 * 把对话列表滚动到最末尾。
 * - 先 scrollToItem 到最后一项的开头，确保 LazyColumn 已构建该项。
 * - 然后用 layoutInfo 拿到该项相对于视口的偏移，scrollBy 把它的底部对齐到视口底部。
 *   这样定位结果是「AI 回答的最后一行」，而不是「用户提问那条 bubble」。
 */
private suspend fun scrollToConversationBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    lastIndex: Int,
) {
    if (lastIndex < 0) return
    listState.scrollToItem(lastIndex)
    // 等待一帧布局完成，layoutInfo 才会包含最新尺寸
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val viewportBottom = info.viewportEndOffset - info.afterContentPadding
    val itemBottom = item.offset + item.size
    val delta = (itemBottom - viewportBottom).toFloat()
    if (delta > 0f) listState.animateScrollBy(delta)
}

@Composable
private fun ConversationTopBar(
    auth: AuthState,
    convo: ConversationState,
    actions: ImageAppViewModel,
    onOpenHistory: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().background(Glass.Paper).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { actions.openBaseUrlConfig() })
                }
            ) {
                BrandLogo(size = 32.dp, corner = 7.dp, fontSize = 16.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    convo.selected?.title ?: "折页",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Glass.TextPrimary,
                    maxLines = 1,
                    letterSpacing = 0.2.sp,
                )
                val subtitle = auth.displayName.ifBlank { auth.username }.uppercase()
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 9.sp,
                        color = Glass.TextSecondary,
                        letterSpacing = 1.5.sp,
                    )
                }
            }
            IconButton(onClick = actions::newConversation, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, contentDescription = "新对话", tint = Glass.Ink, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Chat, contentDescription = "历史对话", tint = Glass.Ink, modifier = Modifier.size(18.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Glass.Ink, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(Glass.Surface),
                ) {
                    DropdownMenuItem(
                        text = { Text("个人中心", color = Glass.Ink, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(16.dp), tint = Glass.Ink) },
                        onClick = { menuOpen = false; onOpenProfile() },
                    )
                    DropdownMenuItem(
                        text = { Text("设置", color = Glass.Ink, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp), tint = Glass.Ink) },
                        onClick = { menuOpen = false; onOpenSettings() },
                    )
                    HorizontalDivider(color = Glass.GlassBorder)
                    DropdownMenuItem(
                        text = { Text("退出登录", color = Glass.Error, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(16.dp), tint = Glass.Error) },
                        onClick = { menuOpen = false; actions.logout() },
                    )
                }
            }
        }
        // 杂志刊头分隔线
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Glass.GlassBorder))
    }
}

@Composable
private fun EmptyConversation(mode: ComposerMode) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp, bottom = 40.dp, start = 32.dp, end = 32.dp),
    ) {
        Text(
            if (mode == ComposerMode.Chat) "CONVERSATION" else "EDITORIAL",
            fontSize = 10.sp,
            color = Glass.TextSecondary,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.width(28.dp).height(2.dp).background(Glass.Accent))
        Spacer(Modifier.height(20.dp))
        Icon(
            if (mode == ComposerMode.Chat) Icons.Default.Chat else Icons.Default.Image,
            contentDescription = null,
            tint = Glass.Ink,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (mode == ComposerMode.Chat) "开始一段对话" else "描述你想生成的画面",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Glass.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "支持多张参考图，可基于上一张结果继续编辑",
            fontSize = 12.sp,
            color = Glass.TextSecondary,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TurnBubbles(
    turn: Turn,
    baseUrl: String,
    onCancel: () -> Unit,
    onContinueEdit: (ImageResult) -> Unit,
    onDownload: (ImageResult) -> Unit,
    onPreview: (ImageResult) -> Unit,
    onRegenerate: () -> Unit,
    shareTextResolver: (ImageResult) -> String?,
    onCopied: (label: String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 用户气泡：长按复制原始 prompt
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 2.dp))
                    .background(Glass.Ink)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboard.setText(AnnotatedString(turn.prompt))
                            onCopied("提示词")
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    if (turn.referenceImages.isNotEmpty()) {
                        Text(
                            "含 ${turn.referenceImages.size} 张参考图",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(turn.prompt, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            GlassCard(modifier = Modifier.fillMaxWidth(0.92f), cornerRadius = 14.dp, contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(turnStatusLabel(turn), fontWeight = FontWeight.SemiBold, color = Glass.TextPrimary, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    if (turn.status == "queued" || turn.status == "running") {
                        TextButton(onClick = onCancel) {
                            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.Error)
                            Spacer(Modifier.width(4.dp))
                            Text("取消", color = Glass.Error, fontSize = 12.sp)
                        }
                    } else if (turn.status == "error" || turn.status == "cancelled") {
                        TextButton(onClick = onRegenerate) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.Ink)
                            Spacer(Modifier.width(4.dp))
                            Text("重试", color = Glass.Ink, fontSize = 12.sp)
                        }
                    } else if (turn.status == "success" && turn.outputType == "text") {
                        TextButton(onClick = onRegenerate) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.Ink)
                            Spacer(Modifier.width(4.dp))
                            Text("重新生成", color = Glass.Ink, fontSize = 12.sp)
                        }
                    }
                }
                if (turn.outputStatuses.isNotEmpty() && turn.outputType != "text") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        turn.outputStatuses.mapIndexed { i, s -> "#${i + 1} ${outputStatusLabel(s)}" }.joinToString("  ·  "),
                        color = Glass.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                if (turn.status == "queued" || turn.status == "running") {
                    val hasStreamText = turn.results.firstOrNull()?.textResponse?.isNotBlank() == true
                    if (!hasStreamText) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Glass.Ink)
                    }
                }
                if (turn.error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(turn.error, color = Glass.Error, fontSize = 13.sp, lineHeight = 18.sp)
                }
                turn.results.forEach { result ->
                    if (result.textResponse.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        clipboard.setText(AnnotatedString(result.textResponse))
                                        onCopied("回复")
                                    },
                                ),
                        ) {
                            // 流式 turn（status=running）期间用节流，避免每个 token 都重组整个 markdown 树
                            val streaming = turn.status == "running" && turn.outputType == "text"
                            ThrottledMarkdown(
                                content = result.textResponse,
                                throttle = streaming,
                                color = Glass.TextPrimary,
                            )
                        }
                    }
                    if (result.b64Json.isNotBlank() || result.url.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        // 本地测得的实际像素尺寸，由 ResultImage 解码/加载完后回填，作为 ResultMetaLine 的 fallback。
                        var localW by remember(result.b64Json, result.url) { mutableStateOf(0) }
                        var localH by remember(result.b64Json, result.url) { mutableStateOf(0) }
                        ResultImage(
                            result = result,
                            baseUrl = baseUrl,
                            onClick = { onPreview(result) },
                            onLongPress = {
                                shareTextResolver(result)?.let { url ->
                                    clipboard.setText(AnnotatedString(url))
                                    onCopied("图片链接")
                                }
                            },
                            onSizeMeasured = { w, h -> localW = w; localH = h },
                        )
                        // 元数据：尺寸 · 格式 · 大小 · 时间。所有字段都是缺则跳过。
                        Spacer(Modifier.height(6.dp))
                        ResultMetaLine(turn = turn, result = result, measuredWidth = localW, measuredHeight = localH)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onContinueEdit(result) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.Ink)
                                Spacer(Modifier.width(4.dp))
                                Text("继续编辑", color = Glass.Ink, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { onDownload(result) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.Ink)
                                Spacer(Modifier.width(4.dp))
                                Text("保存", color = Glass.Ink, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp), tint = Glass.Ink)
                                Spacer(Modifier.width(4.dp))
                                Text("重画", color = Glass.Ink, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResultImage(
    result: ImageResult,
    baseUrl: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSizeMeasured: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val shape = RoundedCornerShape(12.dp)
    val imageModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(shape)
        .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    if (result.b64Json.isNotBlank()) {
        var bitmap by remember(result.b64Json) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        LaunchedEffect(result.b64Json) {
            // 解码使用采样图（缩到 1024px 边长内）；为了拿真实分辨率单独读一次 outWidth/outHeight
            val (rawW, rawH) = BitmapDecoders.measureBase64(result.b64Json)
            if (rawW > 0 && rawH > 0) onSizeMeasured(rawW, rawH)
            bitmap = BitmapDecoders.decodeBase64(result.b64Json, targetMaxEdge = 1024)
        }
        val bm = bitmap
        if (bm != null) {
            Image(
                bitmap = bm,
                contentDescription = "生成结果",
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        } else {
            Box(modifier = imageModifier.background(Glass.SurfaceMuted), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Glass.Ink)
            }
        }
    } else if (result.url.isNotBlank()) {
        AsyncImage(
            model = absoluteImageUrl(baseUrl, result.url),
            contentDescription = "生成结果",
            contentScale = ContentScale.Crop,
            modifier = imageModifier,
            // Coil 加载完成后从 Painter intrinsic size 拿真实分辨率
            onState = { state ->
                val painter = (state as? coil.compose.AsyncImagePainter.State.Success)?.painter
                val size = painter?.intrinsicSize
                if (size != null && size.width > 0 && size.height > 0) {
                    onSizeMeasured(size.width.toInt(), size.height.toInt())
                }
            },
        )
    }
}
