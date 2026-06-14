package com.chatgpt2api.imageapp

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 鉴权阶段。 */
enum class AuthScreen { Login, Register, Authenticated }

/** 输入区当前模式：对话 / 生图（文/图生图由是否有参考图自动决定）。 */
enum class ComposerMode { Chat, Image }

/** 提示词优化结果反馈：UI 拿来弹一个 Dialog。 */
sealed interface OptimizeFeedback {
    val title: String
    data class Success(val originalLength: Int, val optimizedLength: Int) : OptimizeFeedback {
        override val title = "提示词已优化"
    }
    data class Failure(
        val reason: String,
        val httpCode: Int? = null,
        val rawBody: String? = null,
    ) : OptimizeFeedback {
        override val title = "优化失败"
    }
    data object Cancelled : OptimizeFeedback {
        override val title = "已取消优化"
    }
    data object Timeout : OptimizeFeedback {
        override val title = "优化超时"
    }
}

/** 主题偏好。System 跟随系统；Light/Dark 强制。 */
enum class ThemePref { System, Light, Dark }

/**
 * 强制更新等级。
 *
 * - [Optional]：本地版本已过期但仍受支持，弹出可关闭对话框；
 * - [Force]：本地版本低于 minSupportedVersionCode，必须更新或退出 App。
 */
enum class UpdateMode { Optional, Force }

/**
 * App 版本检查状态。对齐 web-app-parity-iteration Requirement 5.4 ~ 5.7：
 *
 * - [visible] = false 时不渲染任何 UI；
 * - [visible] = true 时由 [mode] 决定弹窗形态（Optional 对话框 / Force 阻塞遮罩）；
 * - [info] 为后端下发的版本元数据（versionName / downloadUrl / releaseNotes 等），
 *   只有在 [visible] = true 时才保证非空。
 */
data class AppUpdateState(
    val visible: Boolean = false,
    val mode: UpdateMode = UpdateMode.Optional,
    val info: AppVersionInfo? = null,
)

data class AuthState(
    val screen: AuthScreen = AuthScreen.Login,
    val username: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val displayName: String = "",
    val role: String = "",
    val roleName: String = "",
    val billing: BillingSnapshot = BillingSnapshot(),
    val creationConcurrentLimit: Int = 0,
    val creationRpmLimit: Int = 0,
    val busy: Boolean = false,
    val error: String = "",
    val info: String = "",
)

/** baseUrl 配置弹框状态。 */
data class BaseUrlConfigState(
    val visible: Boolean = false,
    val value: String = "",
)

data class ComposerState(
    val mode: ComposerMode = ComposerMode.Image,
    val prompt: String = "",
    val model: String = "auto",
    val models: List<String> = listOf("auto"),
    val size: String = "1:1",
    val quality: String = "auto",
    val n: Int = 1,
    val outputFormat: String = "png",
    val imageResolution: String = "",
    val visibility: String = "private",
    /** 待提交的参考图（用户选图或上一轮结果图）。 */
    val pendingReferences: List<ReferenceBytes> = emptyList(),
    val busy: Boolean = false,
    val optimizing: Boolean = false,
    val refreshingModels: Boolean = false,
    val error: String = "",
)

/** 会话列表 + 当前选中会话。 */
data class ConversationState(
    val conversations: List<Conversation> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: Conversation?
        get() = conversations.firstOrNull { it.id == selectedId }
}

class ImageAppViewModel(application: Application) : AndroidViewModel(application) {

    private val api = ApiClient()
    private val store = SessionStore(application)
    private val bytesLoader = ImageBytesLoader(application.contentResolver)
    private val imageDownloader = ImageDownloader(application)
    private val chatStream = ChatStreamClient()
    /** 每个流式 Chat turn 对应的 Job，便于用户取消时直接断开 SSE。 */
    private val streamingJobs = mutableMapOf<String, Job>()

    private val _auth = MutableStateFlow(AuthState())
    val auth: StateFlow<AuthState> = _auth

    private val _composer = MutableStateFlow(ComposerState())
    val composer: StateFlow<ComposerState> = _composer

    private val _conversations = MutableStateFlow(ConversationState())
    val conversations: StateFlow<ConversationState> = _conversations

    private val _baseUrlConfig = MutableStateFlow(BaseUrlConfigState())
    val baseUrlConfig: StateFlow<BaseUrlConfigState> = _baseUrlConfig

    private val _toasts = MutableStateFlow<List<String>>(emptyList())
    val toasts: StateFlow<List<String>> = _toasts

    /** 当前网络是否可用；用于在 UI 顶部显示离线提示条。 */
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    private val _theme = MutableStateFlow(store.loadTheme())
    val theme: StateFlow<ThemePref> = _theme

    /** 提示词优化结束后的反馈 dialog；UI 在 collect 到非 null 时弹窗，关闭时调 [consumeOptimizeFeedback]。 */
    private val _optimizeFeedback = MutableStateFlow<OptimizeFeedback?>(null)
    val optimizeFeedback: StateFlow<OptimizeFeedback?> = _optimizeFeedback

    /**
     * App 版本检查状态，参见 [AppUpdateState] / [UpdateMode]。
     * 默认 [AppUpdateState.visible] = false，UI 不渲染任何提示。
     */
    private val _appUpdate = MutableStateFlow(AppUpdateState())
    val appUpdate: StateFlow<AppUpdateState> = _appUpdate

    fun consumeOptimizeFeedback() {
        _optimizeFeedback.value = null
    }

    /**
     * 关闭"可选更新"对话框。
     *
     * Force 模式下用户不应能关闭遮罩，因此只对 [UpdateMode.Optional] 生效；
     * Force 状态保持不变，由 UI 端继续渲染阻塞遮罩。
     */
    fun dismissOptionalUpdate() {
        val current = _appUpdate.value
        if (current.mode == UpdateMode.Optional) {
            _appUpdate.value = current.copy(visible = false)
        }
    }

    fun setTheme(value: ThemePref) {
        _theme.value = value
        store.saveTheme(value)
    }

    private val connectivityManager: android.net.ConnectivityManager? =
        application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { _online.value = true }
        override fun onLost(network: android.net.Network) {
            // 至少一个网络存在时仍认为在线（系统会再发 onAvailable）
            val active = connectivityManager?.activeNetwork
            if (active == null) _online.value = false
        }
        override fun onUnavailable() { _online.value = false }
    }

    private var pollJob: Job? = null

    private val credentialSource = object : HttpClientProvider.AuthCredentialSource {
        override fun current(): HttpClientProvider.AuthCredentialSource.Credentials? {
            val snap = store.load()
            if (snap.token.isBlank()) return null
            return HttpClientProvider.AuthCredentialSource.Credentials(snap.baseUrl, snap.token)
        }
    }

    init {
        HttpClientProvider.bindCredentialSource(credentialSource)
        val snapshot = store.load()
        _auth.update { it.copy(username = snapshot.username, displayName = snapshot.displayName) }
        _conversations.update { it.copy(conversations = store.loadConversations()) }
        if (store.effectiveBaseUrl().isBlank()) {
            // 没有用户配置也没有内置默认时才强制要求填写。
            _baseUrlConfig.update { it.copy(visible = true, value = "") }
        } else if (snapshot.token.isNotBlank()) {
            restoreSession(snapshot)
        }
        // 启动时异步检查最新可用版本（与会话恢复并行）。
        viewModelScope.launch { checkAppVersion() }
        // 注册网络监听
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            _online.value = connectivityManager?.activeNetwork != null
        }
    }

    override fun onCleared() {
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    // ===== baseUrl 配置弹框 =====

    fun openBaseUrlConfig() {
        _baseUrlConfig.update { it.copy(visible = true, value = store.configuredBaseUrl()) }
    }

    fun updateBaseUrlConfigValue(value: String) {
        _baseUrlConfig.update { it.copy(value = value) }
    }

    fun dismissBaseUrlConfig() {
        // 既无用户配置也无内置默认时不允许关闭，避免 App 进入无法用的状态。
        if (store.effectiveBaseUrl().isBlank()) return
        _baseUrlConfig.update { it.copy(visible = false) }
    }

    fun saveBaseUrlConfig() {
        val value = _baseUrlConfig.value.value.trim()
        if (value.isBlank()) {
            // 不允许保存空地址：必须显式填写一个 chatgpt2api 后端 URL。
            return
        }
        store.saveBaseUrl(value)
        _baseUrlConfig.update { it.copy(visible = false) }
        _auth.update { it.copy(info = "服务端地址已更新为 ${store.effectiveBaseUrl()}") }
    }

    /** 当前生效的服务端地址（供 UI 提示用）。 */
    fun effectiveBaseUrl(): String = store.effectiveBaseUrl()

    // ===== 登录态字段更新 =====

    fun updateUsername(value: String) = _auth.update { it.copy(username = value, error = "", info = "") }
    fun updatePassword(value: String) = _auth.update { it.copy(password = value, error = "", info = "") }
    fun updatePasswordConfirm(value: String) = _auth.update { it.copy(passwordConfirm = value, error = "", info = "") }

    fun switchAuthScreen(target: AuthScreen) {
        _auth.update { it.copy(screen = target, password = "", passwordConfirm = "", error = "", info = "") }
    }

    // ===== 鉴权动作 =====

    fun login() {
        val state = _auth.value
        if (!validateCommon(state)) return
        runAuth { current ->
            val identity = api.login(store.effectiveBaseUrl(), current.username.trim(), current.password)
            persistAndEnter(identity)
        }
    }

    fun register() {
        val state = _auth.value
        if (!validateCommon(state)) return
        if (state.password != state.passwordConfirm) {
            _auth.update { it.copy(error = "两次输入的密码不一致") }
            return
        }
        if (state.password.length < 8) {
            _auth.update { it.copy(error = "密码至少 8 位") }
            return
        }
        runAuth { current ->
            val identity = api.register(store.effectiveBaseUrl(), current.username.trim(), current.password)
            persistAndEnter(identity)
        }
    }

    fun logout() {
        val config = AppConfig(store.effectiveBaseUrl(), store.load().token)
        viewModelScope.launch {
            if (config.token.isNotBlank()) runCatching { api.logout(config) }
            handleSignedOut("已退出登录")
        }
    }

    /**
     * 启动期会话恢复（Requirement 6.1 ~ 6.5 / 6.7）。
     *
     * 调用方在 [init] 已确保 `snapshot.token` 非空，因此这里直接乐观登录：
     *  - 立即把本地缓存的 username / displayName 铺到 [AuthState] 上，并把
     *    `screen` 切到 [AuthScreen.Authenticated]，让首屏不被网络往返阻塞；
     *  - 同步触发 [bootstrapComposer] 拉取模型列表，离线场景下其内部已用
     *    `_composer.error` 自承担错误提示，不会反向把用户踢回登录页；
     *  - 后台异步调用 [Api.verifySession] 静默校验：
     *      * 成功 → 用响应覆盖身份字段（不动 token，token 始终复用本地值）；
     *      * [UnauthorizedException] → 走 [handleSignedOut] 清理凭证回登录页；
     *      * 其他网络 / IO / 5xx 异常 → 维持乐观状态，由 `online` flow 顶部
     *        离线条做用户感知，不在此处弹 toast 或写 `info` 文案。
     */
    private fun restoreSession(snapshot: SessionStore.Snapshot) {
        // 乐观登录：立即铺本地身份并切到主界面，不等 verifySession 回包。
        _auth.update {
            it.copy(
                screen = AuthScreen.Authenticated,
                username = snapshot.username,
                displayName = snapshot.displayName,
                password = "",
                passwordConfirm = "",
                busy = false,
                error = "",
                info = "",
            )
        }
        viewModelScope.launch { bootstrapComposer() }

        // 后台静默校验：成功覆盖身份字段，401 触发登出，其他异常静默。
        val config = AppConfig(snapshot.baseUrl, snapshot.token)
        viewModelScope.launch {
            try {
                val identity = api.verifySession(config)
                _auth.update {
                    it.copy(
                        username = identity.username.ifBlank { snapshot.username },
                        displayName = identity.name.ifBlank { snapshot.displayName },
                        role = identity.role,
                        roleName = identity.roleName,
                        billing = identity.billing,
                        creationConcurrentLimit = identity.creationConcurrentLimit,
                        creationRpmLimit = identity.creationRpmLimit,
                    )
                }
            } catch (e: UnauthorizedException) {
                handleSignedOut(e.message ?: "登录已过期，请重新登录")
            } catch (_: Exception) {
                // 网络超时 / IO / 5xx：维持乐观登录，离线条由 `online` flow 提示。
            }
        }
    }

    /**
     * 启动期版本检查：对比本地 [BuildConfig.VERSION_CODE] 与后端 `/api/app/latest-version`
     * 返回的 [AppVersionInfo]，决定是否弹出更新提示。
     *
     * 三段策略对齐 Requirement 5.5 ~ 5.7：
     *  - 本地 < `minSupportedVersionCode` → [UpdateMode.Force]，阻塞业务首屏；
     *  - 本地 < `versionCode` 且 ≥ `minSupportedVersionCode` → [UpdateMode.Optional]，可关闭对话框；
     *  - 本地 ≥ `versionCode` → 静默，不弹任何提示。
     *
     * 任何网络 / 解析 / 配置异常都按 Requirement 5.8 / 8.3 静默丢弃，
     * 不写错误状态、不打断业务流程，更不暴露 toast。
     */
    private suspend fun checkAppVersion() {
        val baseUrl = store.effectiveBaseUrl()
        if (baseUrl.isBlank()) return
        try {
            val info = api.fetchLatestAppVersion(AppConfig(baseUrl = baseUrl))
            val local = BuildConfig.VERSION_CODE
            when {
                local < info.minSupportedVersionCode ->
                    _appUpdate.value = AppUpdateState(visible = true, mode = UpdateMode.Force, info = info)
                local < info.versionCode ->
                    _appUpdate.value = AppUpdateState(visible = true, mode = UpdateMode.Optional, info = info)
                else -> Unit // 本地版本已是最新，保持默认 visible = false。
            }
        } catch (_: Exception) {
            // Requirement 5.8：网络 / 非 2xx / JSON 解析失败等一律静默忽略。
        }
    }

    private suspend fun persistAndEnter(identity: AuthIdentity, persistToken: Boolean = true) {
        if (persistToken) {
            store.saveSession(identity.username, identity.token, identity.name)
        }
        _auth.update {
            it.copy(
                screen = AuthScreen.Authenticated,
                username = identity.username,
                displayName = identity.name,
                role = identity.role,
                roleName = identity.roleName,
                billing = identity.billing,
                creationConcurrentLimit = identity.creationConcurrentLimit,
                creationRpmLimit = identity.creationRpmLimit,
                password = "",
                passwordConfirm = "",
                busy = false,
                error = "",
                info = "",
            )
        }
        bootstrapComposer()
    }

    private suspend fun bootstrapComposer() {
        val config = currentConfig() ?: return
        try {
            val models = api.fetchModels(config)
            _composer.update {
                it.copy(models = models, model = if (it.model in models) it.model else models.first(), error = "")
            }
        } catch (e: UnauthorizedException) {
            handleSignedOut(e.message ?: "登录已过期，请重新登录")
        } catch (e: Exception) {
            _composer.update { it.copy(error = e.message ?: "拉取模型失败") }
        }
    }

    // ===== 创作态字段更新 =====

    fun setComposerMode(mode: ComposerMode) = _composer.update { it.copy(mode = mode, error = "") }
    fun updatePrompt(value: String) = _composer.update { it.copy(prompt = value, error = "") }

    /**
     * 应用提示词市场（Prompt_Market）选中的预设：覆盖当前 prompt，并清空已选参考图，
     * 让用户回到一个干净的输入态再决定是否补充参考图（Requirement 8.4）。
     *
     * 与 [updatePrompt] 的区别：
     *  - 同时清空 [ComposerState.pendingReferences]，避免预设构图被旧参考图误用；
     *  - 同时清掉 [ComposerState.error]，让市场选择视作"重置一次输入"。
     *
     * 该函数由 [PromptMarketSheet] 的 `onPick` 直接调用，不做 trim / 长度校验：
     * 预设数据来自 [PROMPT_MARKET_CATEGORIES] 静态常量，已在文件构造时保证非空。
     */
    fun applyPromptPreset(prompt: String) {
        _composer.update { it.copy(prompt = prompt, pendingReferences = emptyList(), error = "") }
    }
    fun updateModel(value: String) = _composer.update { it.copy(model = value) }
    fun updateSize(value: String) = _composer.update { it.copy(size = value) }
    fun updateQuality(value: String) = _composer.update { it.copy(quality = value) }
    fun updateOutputFormat(value: String) = _composer.update { it.copy(outputFormat = value) }
    fun updateImageResolution(value: String) = _composer.update { it.copy(imageResolution = value) }
    fun updateVisibility(value: String) = _composer.update { it.copy(visibility = value) }
    fun updateN(value: Int) = _composer.update { it.copy(n = value.coerceIn(1, 4)) }

    fun addReferences(uris: List<Uri>) {
        viewModelScope.launch {
            try {
                val refs = uris.map { bytesLoader.fromUri(it) }
                _composer.update {
                    it.copy(pendingReferences = it.pendingReferences + refs, mode = ComposerMode.Image, error = "")
                }
            } catch (e: Exception) {
                _composer.update { it.copy(error = e.message ?: "读取参考图失败") }
            }
        }
    }

    fun removeReference(index: Int) {
        _composer.update { current ->
            current.copy(pendingReferences = current.pendingReferences.filterIndexed { i, _ -> i != index })
        }
    }

    /** 把某轮生成结果保存到系统相册。 */
    fun downloadResult(result: ImageResult) {
        viewModelScope.launch {
            when (val outcome = imageDownloader.saveResult(result, store.effectiveBaseUrl())) {
                is ImageDownloader.Outcome.Saved -> emitToast("已保存到相册：${outcome.displayName}")
                is ImageDownloader.Outcome.Failed -> emitToast(outcome.message)
            }
        }
    }

    /** 复制结果图的 URL（绝对地址）；base64 结果不支持复制。 */
    fun shareResultUrlText(result: ImageResult): String? {
        if (result.url.isBlank()) return null
        return absoluteImageUrl(store.effectiveBaseUrl(), result.url)
    }

    /** UI 层完成复制后回调，统一通过 toast 通道反馈。 */
    fun consumeCopySignal(label: String) {
        emitToast("已复制：$label")
    }

    /** 把指定 turn 重新发送一遍（仍按原 mode/参数），失败重试或主动重试都用它。 */
    fun regenerateTurn(conversationId: String, turnId: String) {
        val state = _conversations.value
        val conversation = state.conversations.firstOrNull { it.id == conversationId } ?: return
        val turn = conversation.turns.firstOrNull { it.id == turnId } ?: return
        // 用原始 prompt + 参考图（从 dataUrl 解码）+ 原参数，重新走 submit 流程
        _composer.update {
            it.copy(
                prompt = turn.prompt,
                model = turn.model,
                size = turn.size,
                quality = turn.quality,
                n = turn.n,
                outputFormat = turn.outputFormat,
                imageResolution = turn.imageResolution,
                visibility = turn.visibility,
                pendingReferences = turn.referenceImages.map { ImageBytesLoader.fromDataUrl(it) },
                mode = if (turn.mode == TurnMode.Chat) ComposerMode.Chat else ComposerMode.Image,
                error = "",
            )
        }
        submit()
    }

    /** 清掉本地内存图片缓存（设置页"清缓存"用）。 */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearImageCache() {
        runCatching {
            val app = getApplication<Application>()
            val loader = coil.Coil.imageLoader(app)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        emitToast("已清除图片缓存")
    }

    fun consumeToast(message: String) {
        _toasts.update { current -> current.filterNot { it == message } }
    }

    private fun emitToast(message: String) {
        if (message.isBlank()) return
        _toasts.update { it + message }
    }

    /** 以某轮结果图作为参考图继续编辑（多轮场景）。 */
    fun continueEditFrom(result: ImageResult) {
        viewModelScope.launch {
            try {
                val ref = bytesLoader.fromResult(store.effectiveBaseUrl(), result)
                _composer.update {
                    it.copy(pendingReferences = it.pendingReferences + ref, mode = ComposerMode.Image, error = "")
                }
            } catch (e: Exception) {
                _composer.update { it.copy(error = e.message ?: "无法将结果图加入参考") }
            }
        }
    }

    fun refreshModels() {
        if (_composer.value.refreshingModels) return
        viewModelScope.launch {
            _composer.update { it.copy(refreshingModels = true, error = "") }
            try {
                bootstrapComposer()
            } finally {
                _composer.update { it.copy(refreshingModels = false) }
            }
        }
    }

    // ===== 会话管理 =====

    fun selectConversation(id: String?) {
        _conversations.update { it.copy(selectedId = id) }
    }

    fun newConversation() {
        _conversations.update { it.copy(selectedId = null) }
        _composer.update { it.copy(pendingReferences = emptyList(), prompt = "", error = "") }
    }

    fun deleteConversation(id: String) {
        _conversations.update { state ->
            val next = state.conversations.filterNot { it.id == id }
            state.copy(conversations = next, selectedId = if (state.selectedId == id) null else state.selectedId)
        }
        store.saveConversations(_conversations.value.conversations)
    }

    // ===== 提示词优化 =====

    // 优化任务的引用，便于"再点一次取消"。
    private var optimizeJob: Job? = null

    fun optimizePrompt() {
        // 已经在优化中：再点一次直接取消
        if (_composer.value.optimizing) {
            optimizeJob?.cancel()
            return
        }
        val composer = _composer.value
        val raw = composer.prompt.trim()
        if (raw.isBlank()) {
            _optimizeFeedback.value = OptimizeFeedback.Failure(reason = "请先输入提示词")
            return
        }
        val config = currentConfig() ?: return
        optimizeJob = viewModelScope.launch {
            _composer.update { it.copy(optimizing = true, error = "") }
            try {
                val optimized = kotlinx.coroutines.withTimeout(30_000) {
                    api.optimizePrompt(
                        config = config,
                        prompt = raw,
                        hasReference = composer.pendingReferences.isNotEmpty(),
                        size = composer.size,
                        quality = composer.quality,
                    )
                }
                val before = raw.length
                _composer.update { it.copy(prompt = optimized, optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Success(
                    originalLength = before,
                    optimizedLength = optimized.length,
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                _composer.update { it.copy(optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Cancelled
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                _composer.update { it.copy(optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Timeout
            } catch (e: UnauthorizedException) {
                _composer.update { it.copy(optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Failure(
                    reason = e.message ?: "登录已过期，请重新登录",
                    httpCode = 401,
                )
                handleSignedOut(e.message ?: "登录已过期，请重新登录")
            } catch (e: UpstreamException) {
                _composer.update { it.copy(optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Failure(
                    reason = e.summary,
                    httpCode = e.httpCode,
                    rawBody = e.rawBody.ifBlank { null },
                )
            } catch (e: java.net.SocketTimeoutException) {
                _composer.update { it.copy(optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Timeout
            } catch (e: java.io.IOException) {
                _composer.update { it.copy(optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Failure(
                    reason = e.message ?: "网络异常",
                )
            } catch (e: Exception) {
                _composer.update { it.copy(optimizing = false) }
                _optimizeFeedback.value = OptimizeFeedback.Failure(
                    reason = e.message ?: e::class.java.simpleName ?: "未知错误",
                )
            }
        }
    }

    // ===== 提交一轮 =====

    fun submit() {
        val composer = _composer.value
        val prompt = composer.prompt.trim()
        if (prompt.isBlank()) {
            _composer.update { it.copy(error = "请先输入提示词") }
            return
        }
        val config = currentConfig() ?: return

        val mode = resolveTurnMode(composer)
        val references = composer.pendingReferences
        val turn = Turn(
            prompt = prompt,
            mode = mode,
            model = composer.model,
            size = composer.size,
            quality = composer.quality,
            n = composer.n,
            outputFormat = composer.outputFormat,
            imageResolution = composer.imageResolution,
            visibility = composer.visibility,
            referenceImages = references.map {
                ReferencePayload(it.name, it.mimeType, ImageBytesLoader.toDataUrl(it))
            },
            status = "queued",
        )

        // 追加到当前会话或新建会话。
        val convState = _conversations.value
        val target = convState.selected
        val conversation = if (target != null) {
            target.copy(turns = target.turns + turn, updatedAt = System.currentTimeMillis())
        } else {
            Conversation(title = buildTitle(prompt), turns = listOf(turn))
        }
        upsertConversation(conversation)
        _conversations.update { it.copy(selectedId = conversation.id) }

        // 拼装多轮 messages（截止到当前轮的 user 之前的历史）。
        val history = buildMessages(conversation, turn.id)

        _composer.update { it.copy(busy = true, prompt = "", pendingReferences = emptyList(), error = "") }

        viewModelScope.launch {
            try {
                if (mode == TurnMode.Chat) {
                    streamChatTurn(config, conversation.id, turn.id, composer.model, history)
                    return@launch
                }
                val options = ImageRequestOptions(
                    model = composer.model, size = composer.size, quality = composer.quality,
                    n = composer.n, outputFormat = composer.outputFormat,
                    imageResolution = composer.imageResolution, visibility = composer.visibility,
                )
                val created = when (mode) {
                    TurnMode.Generate -> api.createGenerationTask(config, turn.taskId, prompt, options, history)
                    TurnMode.Edit -> api.createEditTask(config, turn.taskId, prompt, options, history, references)
                    TurnMode.Chat -> error("unreachable")
                }
                applyTaskToTurn(conversation.id, turn.id, created)
                startPolling()
            } catch (e: UnauthorizedException) {
                handleSignedOut(e.message ?: "登录已过期，请重新登录")
            } catch (e: Exception) {
                updateTurn(conversation.id, turn.id) { it.copy(status = "error", error = e.message ?: "提交失败") }
            } finally {
                _composer.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * 启动一次流式对话：把后端 SSE chunks 实时累加到指定 turn 的 results[0].textResponse。
     * 与异步任务路径互不影响——本方法不写 taskId、不入 polling。
     */
    private fun streamChatTurn(
        config: AppConfig,
        conversationId: String,
        turnId: String,
        model: String,
        messages: List<ChatMessage>,
    ) {
        // 初始化为 running + 空文本，供 UI 立即显示气泡占位与跟随滚动
        // 这里写盘一次记录"该轮已开始"，避免应用立刻被杀时丢失 user prompt
        updateTurn(conversationId, turnId, persist = true) {
            it.copy(
                status = "running",
                outputType = "text",
                results = listOf(ImageResult(textResponse = "")),
                outputStatuses = listOf("running"),
                error = "",
                progress = "",
            )
        }
        val job = viewModelScope.launch {
            val builder = StringBuilder()
            var failed: String? = null
            var unauthorized = false
            try {
                chatStream.stream(config, model, messages).collect { event ->
                    when (event) {
                        is ChatStreamClient.Event.Delta -> {
                            builder.append(event.text)
                            val snapshot = builder.toString()
                            // 流式 token 中间态只更新内存，不写盘——否则
                            // 每个 token 都会触发整个 conversations 列表的 JSON 序列化，
                            // 短时间累加导致 Java 堆 OOM。
                            updateTurn(conversationId, turnId, persist = false) {
                                it.copy(results = listOf(ImageResult(textResponse = snapshot)))
                            }
                        }
                        is ChatStreamClient.Event.Done -> Unit
                        is ChatStreamClient.Event.Error -> {
                            failed = event.message
                            unauthorized = event.unauthorized
                        }
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 用户主动取消：标记 cancelled
                val partial = builder.toString()
                updateTurn(conversationId, turnId, persist = true) {
                    it.copy(
                        status = "cancelled",
                        outputStatuses = listOf("cancelled"),
                        results = if (partial.isBlank()) it.results else listOf(ImageResult(textResponse = partial)),
                    )
                }
                throw kotlinx.coroutines.CancellationException()
            } catch (e: Exception) {
                failed = e.message ?: "流式请求失败"
            }
            if (failed != null) {
                if (unauthorized) {
                    handleSignedOut(failed!!)
                } else {
                    updateTurn(conversationId, turnId, persist = true) {
                        it.copy(
                            status = "error",
                            outputStatuses = listOf("error"),
                            error = failed!!,
                        )
                    }
                }
            } else {
                updateTurn(conversationId, turnId, persist = true) {
                    it.copy(
                        status = "success",
                        outputStatuses = listOf("success"),
                        results = listOf(ImageResult(textResponse = builder.toString())),
                    )
                }
            }
        }
        streamingJobs[turnId] = job
        job.invokeOnCompletion {
            streamingJobs.remove(turnId)
            // 如果 composer 还在 busy 状态（例如下一条还没发），下流结束就释放
            _composer.update { it.copy(busy = false) }
        }
    }

    fun cancelTurn(conversationId: String, turnId: String) {
        val turn = findTurn(conversationId, turnId) ?: return
        // 流式 Chat：直接取消 SSE 协程，不调后端
        if (turn.mode == TurnMode.Chat) {
            streamingJobs[turnId]?.cancel()
            return
        }
        if (turn.taskId.isBlank()) return
        val config = currentConfig() ?: return
        viewModelScope.launch {
            try {
                val updated = api.cancelTask(config, turn.taskId)
                applyTaskToTurn(conversationId, turnId, updated)
            } catch (e: UnauthorizedException) {
                handleSignedOut(e.message ?: "登录已过期，请重新登录")
            } catch (e: Exception) {
                _composer.update { it.copy(error = e.message ?: "取消任务失败") }
            }
        }
    }

    // ===== 轮询 =====

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val config = currentConfig() ?: break
                val pendingTaskIds = collectPendingTaskIds()
                if (pendingTaskIds.isEmpty()) break
                try {
                    val tasks = api.fetchTasks(config, pendingTaskIds)
                    tasks.forEach { task -> applyTaskById(task) }
                } catch (e: UnauthorizedException) {
                    handleSignedOut(e.message ?: "登录已过期，请重新登录")
                    break
                } catch (e: Exception) {
                    _composer.update { it.copy(error = e.message ?: "轮询任务失败") }
                }
            }
        }
    }

    private fun collectPendingTaskIds(): List<String> {
        val state = _conversations.value
        return state.conversations.flatMap { c ->
            c.turns
                // 流式 Chat 不在异步任务体系内，跳过避免被错误轮询
                .filter { it.mode != TurnMode.Chat }
                .filter { it.status == "queued" || it.status == "running" }
                .map { it.taskId }
        }
    }

    // ===== 会话/轮次更新工具 =====

    private fun resolveTurnMode(composer: ComposerState): TurnMode = when {
        composer.mode == ComposerMode.Chat -> TurnMode.Chat
        composer.pendingReferences.isNotEmpty() -> TurnMode.Edit
        else -> TurnMode.Generate
    }

    /** 构造发给后端的 messages：每轮 user=prompt，assistant=图片摘要/文本，到当前轮前为止。 */
    private fun buildMessages(conversation: Conversation, currentTurnId: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        for (turn in conversation.turns) {
            val prompt = turn.prompt.trim()
            if (prompt.isNotEmpty()) messages.add(ChatMessage("user", prompt))
            if (turn.id == currentTurnId) break
            val assistantParts = turn.results.mapNotNull { r ->
                when {
                    turn.outputType == "text" && r.textResponse.isNotBlank() -> r.textResponse.trim()
                    r.revisedPrompt.isNotBlank() -> "Generated image: ${r.revisedPrompt.trim()}"
                    else -> null
                }
            }
            if (assistantParts.isNotEmpty()) {
                messages.add(ChatMessage("assistant", assistantParts.joinToString("\n\n")))
            }
        }
        return messages
    }

    private fun applyTaskToTurn(conversationId: String, turnId: String, task: ImageTask) {
        updateTurn(conversationId, turnId) { turn ->
            turn.copy(
                status = task.status.ifBlank { turn.status },
                outputType = task.outputType,
                outputStatuses = task.outputStatuses,
                results = task.data,
                error = task.error,
                progress = task.progress,
            )
        }
        // 生图 / 编辑任务即时回包就已是 success（同步路径）的极端场景下也要刷新双桶 billing。
        // mode 在 turn 创建时即固定，不会随 status 更新而变化，因此回查一次即可。
        if (task.status == "success") {
            val mode = findTurn(conversationId, turnId)?.mode
            if (mode == TurnMode.Generate || mode == TurnMode.Edit) {
                refreshBillingAfterSuccess()
            }
        }
    }

    private fun applyTaskById(task: ImageTask) {
        val state = _conversations.value
        var changed = false
        // 任务 id 在体系内唯一，匹配到的 turn 至多一个；记录其 mode 用于 success 后触发 billing 刷新。
        var matchedMode: TurnMode? = null
        val next = state.conversations.map { c ->
            c.copy(turns = c.turns.map { t ->
                if (t.taskId == task.id) {
                    matchedMode = t.mode
                    val nextStatus = task.status.ifBlank { t.status }
                    val nextOutputType = task.outputType
                    val nextStatuses = task.outputStatuses
                    val nextResults = task.data
                    val nextError = task.error
                    val nextProgress = task.progress
                    // 计算指纹避免无变化时也触发整个 conversations 列表的 JSON 序列化。
                    // 任务轮询每 2 秒触发一次，绝大多数时候 task 内容没变。
                    val sameStatus = t.status == nextStatus
                    val sameOutputType = t.outputType == nextOutputType
                    val sameStatuses = t.outputStatuses == nextStatuses
                    val sameResults = t.results.size == nextResults.size &&
                        t.results.zip(nextResults).all { (a, b) ->
                            a.url == b.url &&
                                a.b64Json == b.b64Json &&
                                a.revisedPrompt == b.revisedPrompt &&
                                a.textResponse == b.textResponse &&
                                a.width == b.width &&
                                a.height == b.height &&
                                a.sizeBytes == b.sizeBytes
                        }
                    val sameError = t.error == nextError
                    val sameProgress = t.progress == nextProgress
                    if (sameStatus && sameOutputType && sameStatuses && sameResults && sameError && sameProgress) {
                        t
                    } else {
                        changed = true
                        t.copy(
                            status = nextStatus,
                            outputType = nextOutputType,
                            outputStatuses = nextStatuses,
                            results = nextResults,
                            error = nextError,
                            progress = nextProgress,
                        )
                    }
                } else t
            })
        }
        if (!changed) return
        _conversations.update { it.copy(conversations = next) }
        store.saveConversations(next)
        // success 状态首次落盘时（changed=true 才会到此）刷新双桶 billing。
        // 后续轮询若 task 内容未变会被指纹比对短路，changed=false 直接 return，不会重复触发。
        if (task.status == "success" && (matchedMode == TurnMode.Generate || matchedMode == TurnMode.Edit)) {
            refreshBillingAfterSuccess()
        }
    }

    private fun updateTurn(
        conversationId: String,
        turnId: String,
        persist: Boolean = true,
        transform: (Turn) -> Turn,
    ) {
        val state = _conversations.value
        val next = state.conversations.map { c ->
            if (c.id != conversationId) c
            else c.copy(turns = c.turns.map { if (it.id == turnId) transform(it) else it })
        }
        _conversations.update { it.copy(conversations = next) }
        if (persist) store.saveConversations(next)
    }

    private fun upsertConversation(conversation: Conversation) {
        val state = _conversations.value
        val exists = state.conversations.any { it.id == conversation.id }
        val next = if (exists) {
            state.conversations.map { if (it.id == conversation.id) conversation else it }
        } else {
            listOf(conversation) + state.conversations
        }
        _conversations.update { it.copy(conversations = next) }
        store.saveConversations(next)
    }

    private fun findTurn(conversationId: String, turnId: String): Turn? =
        _conversations.value.conversations.firstOrNull { it.id == conversationId }
            ?.turns?.firstOrNull { it.id == turnId }

    /**
     * 生图 / 编辑任务成功完成后，立即拉一次 `/api/profile` 把双桶 billing 刷回 [AuthState]，
     * 让顶部 Billing_Bar 即时反映最新余额（对齐 web `fetchProfile` 行为）。
     *
     * Requirement 7.1 ~ 7.6 / NFR 1.1：
     *  - 仅在 `task.status == "success"` 且 `mode in {Generate, Edit}` 时由调用方触发；
     *  - 失败（网络 / 非 2xx / JSON 解析）由 [runCatching] 静默吞掉，不弹 toast、
     *    不写错误状态、不阻塞后续生图流程；
     *  - 不为旧 billing 字段保留 fallback：[ApiClient.fetchProfile] 内部已固定走双桶解析，
     *    本方法只负责把成功响应的 [AuthIdentity.billing] 整体替换到 `_auth.value.billing`。
     */
    private fun refreshBillingAfterSuccess() {
        val config = currentConfig() ?: return
        viewModelScope.launch {
            runCatching { api.fetchProfile(config) }
                .onSuccess { identity ->
                    _auth.update { it.copy(billing = identity.billing) }
                }
            // 失败由 runCatching 静默吞掉，符合 Requirement 7.4。
        }
    }

    private fun buildTitle(prompt: String): String =
        prompt.trim().take(18).ifBlank { "新对话" }

    // ===== 通用 =====

    private fun currentConfig(): AppConfig? {
        val snapshot = store.load()
        if (snapshot.token.isBlank()) {
            handleSignedOutSync("登录信息已失效，请重新登录")
            return null
        }
        return AppConfig(snapshot.baseUrl, snapshot.token)
    }

    private fun runAuth(block: suspend (AuthState) -> Unit) {
        viewModelScope.launch {
            _auth.update { it.copy(busy = true, error = "", info = "") }
            try {
                block(_auth.value)
            } catch (e: UnauthorizedException) {
                _auth.update { it.copy(busy = false, error = e.message ?: "用户名或密码错误") }
            } catch (e: Exception) {
                _auth.update { it.copy(busy = false, error = e.message ?: "请求失败") }
            } finally {
                _auth.update { it.copy(busy = false) }
            }
        }
    }

    private fun validateCommon(state: AuthState): Boolean {
        if (state.username.trim().isBlank()) {
            _auth.update { it.copy(error = "请填写用户名") }
            return false
        }
        if (state.password.isBlank()) {
            _auth.update { it.copy(error = "请填写密码") }
            return false
        }
        return true
    }

    private suspend fun handleSignedOut(message: String) = handleSignedOutSync(message)

    private fun handleSignedOutSync(message: String) {
        pollJob?.cancel()
        streamingJobs.values.forEach { it.cancel() }
        streamingJobs.clear()
        store.clearSession()
        _composer.value = ComposerState()
        _auth.update {
            it.copy(
                screen = AuthScreen.Login,
                password = "",
                passwordConfirm = "",
                displayName = "",
                role = "",
                roleName = "",
                billing = BillingSnapshot(),
                creationConcurrentLimit = 0,
                creationRpmLimit = 0,
                busy = false,
                error = message,
                info = "",
            )
        }
    }
}
