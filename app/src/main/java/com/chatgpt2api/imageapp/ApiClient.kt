package com.chatgpt2api.imageapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ChatGPT2API（ZyphrZero 版）服务端客户端。
 *
 * 接口：
 *  - 鉴权：POST /auth/login、/auth/register、GET /auth/session、POST /auth/logout
 *  - 模型：GET /v1/models（连通性 + 鉴权探测）
 *  - 提示词优化 / 对话：POST /v1/chat/completions
 *  - 异步创作任务：/api/creation-tasks/image-generations | image-edits | chat-completions
 *  - 任务查询/取消：GET /api/creation-tasks?ids= | POST /api/creation-tasks/{id}/cancel
 *
 * 多轮上下文与 Web 创作台一致：messages 由客户端线性拼装；编辑模式参考图以二进制重新上传，
 * 后端不接受 URL 引用。
 */

private val IMAGE_MODEL_WHITELIST = setOf("auto", "gpt-image-2", "codex-gpt-image-2", "gemini-3.1-flash-image")
private val SUPPORTED_QUALITIES = setOf("low", "medium", "high")
private val SUPPORTED_RESOLUTIONS = setOf("1080p", "2k", "4k")
private val SUPPORTED_OUTPUT_FORMATS = setOf("png", "jpeg", "webp")
private val SUPPORTED_VISIBILITIES = setOf("private", "public")

/** 创建图片任务的请求参数集合。 */
data class ImageRequestOptions(
    val model: String = "auto",
    val size: String = "1:1",
    val quality: String = "auto",
    val n: Int = 1,
    val outputFormat: String = "png",
    val imageResolution: String = "",
    val visibility: String = "private",
    val industryKey: String = "",
)

/** 多轮上下文中的一条消息。content 为纯文本（与 Web 常规路径一致）。 */
data class ChatMessage(val role: String, val content: String)

/** 待上传的参考图二进制（来源：用户选图或上一轮结果图）。 */
data class ReferenceBytes(val name: String, val mimeType: String, val bytes: ByteArray)

data class IndustryPromptOption(
    val industryKey: String,
    val label: String,
    val description: String = "",
    val hasOverride: Boolean = false,
    val resolvedPrompt: String = "",
)

data class CurrentIndustry(
    val industryKey: String,
    val effective: Boolean,
)

data class AppConfig(
    val baseUrl: String = "",
    val token: String = "",
)

data class AuthIdentity(
    val token: String,
    val name: String,
    val role: String,
    val username: String,
    val billing: BillingSnapshot = BillingSnapshot(),
    val creationConcurrentLimit: Int = 0,
    val creationRpmLimit: Int = 0,
    val roleName: String = "",
)

/**
 * 用户某一桶的计费快照。后端 `/auth/login` / `/api/profile` 把 `billing` 拆成
 * `bucket_a`（gpt-image-2 系列）与 `bucket_b`（codex / gemini 系列）两个独立桶。
 *
 * - [present]=false 表示后端没返回此桶（账号未绑定该模型分组），UI 应显示 "--"。
 * - [unlimited]=true 时其余字段无意义。
 * - [type]="subscription" 时关注 [quotaLimit]/[quotaUsed]/[quotaPeriodEndsAt]。
 * - [type]="standard" 时关注 [availableBalance]/[balance]。
 */
data class BillingBucket(
    val type: String = "",
    val unlimited: Boolean = false,
    val available: Long = 0,
    val quotaLimit: Long = 0,
    val quotaUsed: Long = 0,
    val quotaPeriodEndsAt: String = "",
    val availableBalance: Long = 0,
    val balance: Long = 0,
    /** 后端没返回该桶时为 false，UI 显示为 "--"。 */
    val present: Boolean = false,
)

/**
 * 用户计费快照，对齐 web 端 `StoredAuthSession.billing`。
 *
 * 双桶设计：
 *  - [bucketA]：gpt-image-2 配额。
 *  - [bucketB]：codex / gemini 配额。
 *
 * 顶层 [unlimited]=true 时整个账号对所有桶都是无限额度，UI 不展示具体桶。
 */
data class BillingSnapshot(
    val unlimited: Boolean = true,
    val bucketA: BillingBucket = BillingBucket(),
    val bucketB: BillingBucket = BillingBucket(),
)

/**
 * Android 客户端最新可用版本元数据，对齐后端 `GET /api/app/latest-version` 响应。
 *
 * 字段语义见 web-app-parity-iteration Requirement 5.2，与
 * `internal/httpapi/app_version.go::AppVersionMetadata` 一一对应：
 *  - [versionCode] / [versionName]：发布版本号；
 *  - [downloadUrl]：APK 公开下载地址（zheye-v{versionName}.apk）；
 *  - [releaseNotes]：版本说明，允许为空字符串；
 *  - [minSupportedVersionCode]：低于此值的客户端必须强制更新（Force_Update）。
 */
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val minSupportedVersionCode: Int,
)

data class ImageTask(
    val id: String,
    val status: String,
    val mode: String,
    val outputType: String = "",
    val outputStatuses: List<String> = emptyList(),
    val data: List<ImageResult> = emptyList(),
    val error: String = "",
    val progress: String = "",
)

data class ImageResult(
    val b64Json: String = "",
    val url: String = "",
    val revisedPrompt: String = "",
    val textResponse: String = "",
    /** 服务端汇报的图片像素宽度，0 表示未知。 */
    val width: Int = 0,
    /** 服务端汇报的图片像素高度，0 表示未知。 */
    val height: Int = 0,
    /** 服务端汇报的图片字节大小，0 表示未知。 */
    val sizeBytes: Long = 0,
)

class UnauthorizedException(message: String) : RuntimeException(message)

/**
 * 后端返回非 2xx 时抛出，把 HTTP code 与服务端摘要保留下来供 UI 诊断使用。
 * [rawBody] 截断到 1KB，避免大响应反向把 OOM 引回来。
 */
class UpstreamException(
    val httpCode: Int,
    val summary: String,
    val rawBody: String,
) : RuntimeException(if (summary.isNotBlank()) summary else "请求失败：HTTP $httpCode")

class ApiClient {
    /** 复用全局 OkHttp，与 Coil 共享鉴权拦截器以加载受保护图片。 */
    private val client = HttpClientProvider.okHttp()

    // ===== 鉴权 =====

    suspend fun login(baseUrl: String, username: String, password: String): AuthIdentity =
        withContext(Dispatchers.IO) {
            postAuth(baseUrl, "/auth/login",
                JSONObject().put("username", username).put("password", password), username)
        }

    suspend fun register(baseUrl: String, username: String, password: String): AuthIdentity =
        withContext(Dispatchers.IO) {
            // 决策 2A：name 字段由后端用 username 兜底。
            postAuth(baseUrl, "/auth/register",
                JSONObject().put("username", username).put("password", password).put("name", username),
                username)
        }

    suspend fun verifySession(config: AppConfig): AuthIdentity = withContext(Dispatchers.IO) {
        val request = baseRequest(config, "/auth/session").get().build()
        val json = executeJson(request)
        identityFromJson(json, fallbackUsername = json.optString("name"), token = config.token)
    }

    suspend fun logout(config: AppConfig) = withContext(Dispatchers.IO) {
        val request = baseRequest(config, "/auth/logout").post("{}".toRequestBody(JSON)).build()
        runCatching { client.newCall(request).execute().use { } }
        Unit
    }

    /**
     * 拉取当前用户的最新身份与计费快照。
     *
     * 与 `verifySession` 的差异：`/api/profile` 是 web-app-parity-iteration
     * Requirement 7 中 App 在生图成功后用来"立即刷新双桶 billing"的入口，
     * 字段名 / 桶语义与 web 端 `fetchProfile` 完全一致——此处复用现有的
     * [identityFromJson] 解析双桶（bucket_a / bucket_b）billing，禁止为旧
     * 字段保留 fallback（NFR 1.1 / Requirement 7.6）。
     *
     * 鉴权失败抛出 [UnauthorizedException]；网络 / 5xx / JSON 解析错误由调用方
     * 静默处理（参见 Requirement 7.4）。
     */
    suspend fun fetchProfile(config: AppConfig): AuthIdentity = withContext(Dispatchers.IO) {
        val request = baseRequest(config, "/api/profile").get().build()
        val json = executeJson(request)
        val fallbackUsername = json.optString("username").ifBlank { json.optString("name") }
        identityFromJson(json, fallbackUsername = fallbackUsername, token = config.token)
    }

    private fun postAuth(baseUrl: String, path: String, body: JSONObject, username: String): AuthIdentity {
        val base = baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "服务端地址未配置" }
        val request = Request.Builder().url(base + path)
            .post(body.toString().toRequestBody(JSON)).build()
        val json = executeJson(request)
        val token = json.optString("token")
        if (token.isBlank()) throw IllegalStateException("服务端未返回登录凭证")
        return identityFromJson(json, fallbackUsername = username, token = token)
    }

    /**
     * 从 /auth/{login,register,session} 响应里抽取统一的身份与计费信息。
     * 字段名对齐 web 的 `StoredAuthSession`。
     */
    private fun identityFromJson(json: JSONObject, fallbackUsername: String, token: String): AuthIdentity {
        val name = json.optString("name").ifBlank { fallbackUsername }
        val username = json.optString("username").ifBlank { fallbackUsername }
        val billingJson = json.optJSONObject("billing")
        val billing = if (billingJson != null) {
            BillingSnapshot(
                unlimited = billingJson.optBoolean("unlimited", false),
                bucketA = parseBillingBucket(billingJson.optJSONObject("bucket_a")),
                bucketB = parseBillingBucket(billingJson.optJSONObject("bucket_b")),
            )
        } else BillingSnapshot()
        return AuthIdentity(
            token = token,
            name = name,
            role = json.optString("role"),
            username = username,
            billing = billing,
            creationConcurrentLimit = json.optInt("creationConcurrentLimit", 0),
            creationRpmLimit = json.optInt("creationRpmLimit", 0),
            roleName = json.optString("roleName"),
        )
    }

    /**
     * 解析单个计费桶。后端返回结构：
     * ```
     * { "type": "standard"|"subscription", "unlimited": bool, "available": n,
     *   "standard": { "balance": n, "available_balance": n, ... },
     *   "subscription": { "quota_limit": n, "quota_used": n, "quota_period_ends_at": "..." } }
     * ```
     * 当 [json] 为 null 时返回 [present]=false 的占位桶，UI 显示为 "--"。
     */
    private fun parseBillingBucket(json: JSONObject?): BillingBucket {
        if (json == null) return BillingBucket()
        val sub = json.optJSONObject("subscription")
        val std = json.optJSONObject("standard")
        return BillingBucket(
            type = json.optString("type"),
            unlimited = json.optBoolean("unlimited", false),
            available = json.optLong("available", 0),
            quotaLimit = sub?.optLong("quota_limit", 0) ?: 0,
            quotaUsed = sub?.optLong("quota_used", 0) ?: 0,
            quotaPeriodEndsAt = sub?.optString("quota_period_ends_at").orEmpty(),
            availableBalance = std?.optLong("available_balance", 0) ?: 0,
            balance = std?.optLong("balance", 0) ?: 0,
            present = true,
        )
    }

    // ===== 应用版本 =====

    /**
     * 拉取 Android 客户端最新版本元数据。
     *
     * 对应后端 `GET /api/app/latest-version`（注册见
     * `internal/httpapi/router.go`），与 announcements 同层匿名访问，因此
     * 不携带 Authorization 头、不依赖 [AppConfig.token]。
     *
     * "No compatibility layers"（Requirement 5.2 / NFR 6.1）：响应 JSON 任一
     * 字段缺失或类型不符直接抛 [IllegalStateException]，不回退默认值，让
     * `ImageAppViewModel` 在 Requirement 5.8 的"静默忽略"逻辑里把异常吞掉，
     * 而不是把错误数据写进版本检查状态机。
     */
    suspend fun fetchLatestAppVersion(config: AppConfig): AppVersionInfo = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "服务端地址未配置" }
        val request = Request.Builder()
            .url(base + "/api/app/latest-version")
            .header("Accept", "application/json")
            .get()
            .build()
        val json = executeJson(request)
        AppVersionInfo(
            versionCode = json.requireInt("versionCode"),
            versionName = json.requireString("versionName"),
            downloadUrl = json.requireString("downloadUrl"),
            releaseNotes = json.requireOptionalString("releaseNotes"),
            minSupportedVersionCode = json.requireInt("minSupportedVersionCode"),
        )
    }

    // ===== 模型 =====

    suspend fun fetchModels(config: AppConfig): List<String> = withContext(Dispatchers.IO) {
        val request = baseRequest(config, "/v1/models").get().build()
        val data = executeJson(request).optJSONArray("data") ?: JSONArray()
        val matched = mutableSetOf<String>()
        for (index in 0 until data.length()) {
            val id = data.optJSONObject(index)?.optString("id").orEmpty()
            if (id.isBlank()) continue
            IMAGE_MODEL_WHITELIST.firstOrNull { it.equals(id, ignoreCase = true) }?.let { matched.add(it) }
        }
        listOf("auto", "gpt-image-2", "codex-gpt-image-2", "gemini-3.1-flash-image").filter { it in matched }.ifEmpty { listOf("auto") }
    }

    // ===== 提示词优化 =====

    suspend fun optimizePrompt(
        config: AppConfig,
        prompt: String,
        hasReference: Boolean,
        size: String,
        quality: String,
    ): String = withContext(Dispatchers.IO) {
        val system = "你是专业的AI生图提示词优化器。保留用户核心意图，补充主体、构图、光线、材质、风格、色彩、背景与细节。只输出最终提示词，不要解释。"
        val user = buildString {
            appendLine("模式：${if (hasReference) "图生图/图片编辑" else "文生图"}")
            appendLine("尺寸：$size")
            appendLine("质量：$quality")
            appendLine("原始提示词：")
            append(prompt)
        }
        val body = JSONObject()
            .put("model", "auto")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString().toRequestBody(JSON)
        val request = baseRequest(config, "/v1/chat/completions").post(body).build()
        val json = executeJson(request)
        val content = json.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty().trim()
        val cleaned = cleanupPrompt(content)
        if (cleaned.isBlank()) {
            // 后端 HTTP 200 但内容为空：把这种"隐性失败"翻译成显式错误，让 UI 弹出诊断。
            // 常见原因：
            //   - 上游内容审查拒绝（提示词触发违规规则，completion_tokens=0）
            //   - 上游模型瞬时返回空 / 被截断
            //   - 上游账号配额耗尽但走的是 200 路径
            val errField = json.opt("error")
            val errSummary = when (errField) {
                is JSONObject -> errField.optString("message").ifBlank { errField.optString("type") }
                is String -> errField
                else -> ""
            }
            val finishReason = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optString("finish_reason").orEmpty()
            val baseReason = errSummary.ifBlank {
                when (finishReason) {
                    "content_filter" -> "提示词命中内容审查，请修改后重试"
                    "length" -> "上游模型在生成中被截断，请稍后重试"
                    else -> "提示词可能违规或服务端发生意外，请稍后重试"
                }
            }
            throw UpstreamException(
                httpCode = 200,
                summary = baseReason,
                rawBody = json.toString().take(1024),
            )
        }
        cleaned
    }

    // ===== 创作任务 =====

    suspend fun createGenerationTask(
        config: AppConfig,
        taskId: String,
        prompt: String,
        options: ImageRequestOptions,
        messages: List<ChatMessage>,
    ): ImageTask = withContext(Dispatchers.IO) {
        val json = baseTaskJson(taskId, prompt, options).apply {
            putMessages(this, messages)
        }
        val request = baseRequest(config, "/api/creation-tasks/image-generations")
            .post(json.toString().toRequestBody(JSON)).build()
        parseTask(executeJson(request))
    }

    suspend fun createEditTask(
        config: AppConfig,
        taskId: String,
        prompt: String,
        options: ImageRequestOptions,
        messages: List<ChatMessage>,
        references: List<ReferenceBytes>,
    ): ImageTask = withContext(Dispatchers.IO) {
        require(references.isNotEmpty()) { "请至少添加一张参考图" }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("client_task_id", taskId)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("model", options.model)
            .addFormDataPart("size", options.size)
            .addFormDataPart("n", normalizeN(options.n).toString())
            .addFormDataPart("output_format", normalizeOutputFormat(options.outputFormat))
            .addFormDataPart("visibility", normalizeVisibility(options.visibility))
        normalizeQuality(options.quality)?.let { form.addFormDataPart("quality", it) }
        normalizeResolution(options.imageResolution)?.let { form.addFormDataPart("image_resolution", it) }
        options.industryKey.trim().takeIf { it.isNotEmpty() }?.let { form.addFormDataPart("industry_key", it) }
        if (messages.isNotEmpty()) {
            form.addFormDataPart("messages", messagesToJson(messages).toString())
        }
        references.forEach { ref ->
            form.addFormDataPart("image", ref.name, ref.bytes.toRequestBody(ref.mimeType.toMediaType()))
        }
        val request = baseRequest(config, "/api/creation-tasks/image-edits").post(form.build()).build()
        parseTask(executeJson(request))
    }

    suspend fun createChatTask(
        config: AppConfig,
        taskId: String,
        prompt: String,
        model: String,
        messages: List<ChatMessage>,
    ): ImageTask = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("client_task_id", taskId)
            .put("prompt", prompt)
            .put("model", model)
        putMessages(json, messages)
        val request = baseRequest(config, "/api/creation-tasks/chat-completions")
            .post(json.toString().toRequestBody(JSON)).build()
        parseTask(executeJson(request))
    }

    suspend fun fetchTasks(config: AppConfig, ids: List<String>): List<ImageTask> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            val request = baseRequest(config, "/api/creation-tasks?ids=${ids.joinToString(",")}")
                .get().build()
            val items = executeJson(request).optJSONArray("items") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    items.optJSONObject(index)?.let { add(parseTask(it)) }
                }
            }
        }

    suspend fun cancelTask(config: AppConfig, taskId: String): ImageTask = withContext(Dispatchers.IO) {
        require(taskId.isNotBlank()) { "任务 ID 为空" }
        val request = baseRequest(config, "/api/creation-tasks/$taskId/cancel")
            .post("{}".toRequestBody(JSON)).build()
        parseTask(executeJson(request))
    }

    // ===== 行业提示词 =====

    suspend fun fetchIndustryPrompts(config: AppConfig): List<IndustryPromptOption> =
        withContext(Dispatchers.IO) {
            val request = baseRequest(config, "/api/profile/industry-prompts").get().build()
            val items = executeJson(request).optJSONArray("items") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val obj = items.optJSONObject(index) ?: continue
                    add(
                        IndustryPromptOption(
                            industryKey = obj.optString("industry_key"),
                            label = obj.optString("label"),
                            description = obj.optString("description"),
                            hasOverride = obj.optBoolean("has_override"),
                            resolvedPrompt = obj.optString("resolved_prompt"),
                        ),
                    )
                }
            }
        }

    suspend fun fetchCurrentIndustry(config: AppConfig): CurrentIndustry =
        withContext(Dispatchers.IO) {
            val request = baseRequest(config, "/api/profile/current-industry").get().build()
            val obj = executeJson(request)
            CurrentIndustry(
                industryKey = obj.optString("industry_key"),
                effective = obj.optBoolean("effective"),
            )
        }

    suspend fun setCurrentIndustry(config: AppConfig, industryKey: String): CurrentIndustry =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("industry_key", industryKey).toString().toRequestBody(JSON)
            val request = baseRequest(config, "/api/profile/current-industry").put(body).build()
            val obj = executeJson(request)
            CurrentIndustry(
                industryKey = obj.optString("industry_key"),
                effective = obj.optBoolean("effective"),
            )
        }

    suspend fun saveIndustryPromptOverride(
        config: AppConfig,
        industryKey: String,
        prompt: String,
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("prompt", prompt).toString().toRequestBody(JSON)
        val request = baseRequest(config, "/api/profile/industry-prompts/$industryKey").put(body).build()
        executeJson(request)
    }

    suspend fun deleteIndustryPromptOverride(config: AppConfig, industryKey: String): Unit =
        withContext(Dispatchers.IO) {
            val request = baseRequest(config, "/api/profile/industry-prompts/$industryKey").delete().build()
            executeJson(request)
        }

    // ===== 内部工具 =====

    private fun baseTaskJson(taskId: String, prompt: String, options: ImageRequestOptions): JSONObject {
        val json = JSONObject()
            .put("client_task_id", taskId)
            .put("prompt", prompt)
            .put("model", options.model)
            .put("size", options.size)
            .put("n", normalizeN(options.n))
            .put("output_format", normalizeOutputFormat(options.outputFormat))
            .put("visibility", normalizeVisibility(options.visibility))
        normalizeQuality(options.quality)?.let { json.put("quality", it) }
        normalizeResolution(options.imageResolution)?.let { json.put("image_resolution", it) }
        options.industryKey.trim().takeIf { it.isNotEmpty() }?.let { json.put("industry_key", it) }
        return json
    }

    private fun putMessages(json: JSONObject, messages: List<ChatMessage>) {
        if (messages.isNotEmpty()) json.put("messages", messagesToJson(messages))
    }

    private fun messagesToJson(messages: List<ChatMessage>): JSONArray {
        val array = JSONArray()
        messages.forEach {
            array.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        return array
    }

    private fun baseRequest(config: AppConfig, path: String): Request.Builder {
        val base = config.baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "服务端地址未配置" }
        require(config.token.isNotBlank()) { "未登录，请先完成登录" }
        return Request.Builder().url(base + path)
            .header("Authorization", "Bearer ${config.token.trim()}")
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException(extractError(text).ifBlank { "登录已过期，请重新登录" })
            }
            if (!response.isSuccessful) {
                val summary = extractError(text).ifBlank { "请求失败：HTTP ${response.code}" }
                throw UpstreamException(
                    httpCode = response.code,
                    summary = summary,
                    rawBody = text.take(1024),
                )
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun parseTask(json: JSONObject): ImageTask {
        val data = json.optJSONArray("data") ?: JSONArray()
        val results = buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.let {
                    add(
                        ImageResult(
                            b64Json = it.optString("b64_json"),
                            url = it.optString("url"),
                            revisedPrompt = it.optString("revised_prompt"),
                            textResponse = it.optString("text_response"),
                            width = it.optInt("width", 0),
                            height = it.optInt("height", 0),
                            sizeBytes = it.optLong("size", it.optLong("file_size", 0)),
                        )
                    )
                }
            }
        }
        val statuses = json.optJSONArray("output_statuses")?.let { array ->
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        }.orEmpty()
        return ImageTask(
            id = json.optString("id"),
            status = json.optString("status"),
            mode = json.optString("mode"),
            outputType = json.optString("output_type"),
            outputStatuses = statuses,
            data = results,
            error = json.optString("error"),
            progress = json.optString("progress"),
        )
    }

    private fun cleanupPrompt(value: String): String = value
        .removePrefix("```").removeSuffix("```")
        .removePrefix("优化后提示词：").removePrefix("提示词：").removePrefix("Prompt:")
        .trim().trim('"')

    private fun extractError(text: String): String = runCatching {
        val json = JSONObject(text)
        when (val detail = json.opt("detail")) {
            is String -> detail
            is JSONObject -> detail.optString("error")
            else -> {
                val error = json.opt("error")
                if (error is JSONObject) error.optString("message") else error?.toString().orEmpty()
            }
        }
    }.getOrDefault("")

    private fun normalizeQuality(quality: String): String? =
        quality.trim().lowercase().takeIf { it in SUPPORTED_QUALITIES }

    private fun normalizeResolution(value: String): String? =
        value.trim().lowercase().takeIf { it in SUPPORTED_RESOLUTIONS }

    private fun normalizeOutputFormat(value: String): String =
        value.trim().lowercase().takeIf { it in SUPPORTED_OUTPUT_FORMATS } ?: "png"

    private fun normalizeVisibility(value: String): String =
        value.trim().lowercase().takeIf { it in SUPPORTED_VISIBILITIES } ?: "private"

    private fun normalizeN(n: Int): Int = n.coerceIn(1, 4)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 把服务端图片 URL 拼成绝对地址（供 UI 加载）。
 *
 * 行为：
 *  - 相对路径：拼到 baseUrl 后面。
 *  - 已经是绝对 http(s) URL，且 host 是回环/内网 IP（127.0.0.1、localhost、10/172.16-31/192.168）
 *    或与 baseUrl host 相同：把 scheme/host/port 重写到 baseUrl，
 *    使图片请求统一走外部反代域名，能正确通过 [HttpClientProvider] 注入鉴权头。
 *  - 其它绝对 URL：原样返回（属于第三方 CDN 之类）。
 */
fun absoluteImageUrl(baseUrl: String, value: String): String {
    val raw = value.trim()
    if (raw.isBlank()) return raw
    val base = baseUrl.trim().trimEnd('/')
    if (raw.startsWith("/")) return if (base.isBlank()) raw else base + raw
    val isAbsolute = raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)
    if (!isAbsolute) return if (base.isBlank()) raw else "$base/$raw"
    if (base.isBlank()) return raw
    val baseUri = base.toHttpUrlOrNull() ?: return raw
    val rawUri = raw.toHttpUrlOrNull() ?: return raw
    val rawHost = rawUri.host
    val shouldRewrite = rawHost.equals(baseUri.host, ignoreCase = true) || isPrivateOrLoopbackHost(rawHost)
    if (!shouldRewrite) return raw
    return baseUri.newBuilder()
        .encodedPath(rawUri.encodedPath)
        .encodedQuery(rawUri.encodedQuery)
        .encodedFragment(rawUri.encodedFragment)
        .build()
        .toString()
}

private fun isPrivateOrLoopbackHost(host: String): Boolean {
    if (host.isBlank()) return false
    if (host.equals("localhost", ignoreCase = true)) return true
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
    val (a, b, _, _) = parts.map { it.toInt() }.let { Quadruple(it[0], it[1], it[2], it[3]) }
    return a == 127 ||
        a == 10 ||
        (a == 192 && b == 168) ||
        (a == 172 && b in 16..31)
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * 严格读取一个必须存在的整型字段，缺失或类型不符时立即抛出 [IllegalStateException]。
 *
 * 用于解析 `/api/app/latest-version` 这类 fail-fast 协议——后端字段契约见
 * `internal/httpapi/app_version.go`，缺字段意味着发布事故而非业务可降级状态，
 * 因此不允许使用 `optInt(key, default)` 静默回退。
 */
private fun JSONObject.requireInt(key: String): Int {
    if (!has(key) || isNull(key)) {
        throw IllegalStateException("响应缺少必填字段 $key")
    }
    return runCatching { getInt(key) }
        .getOrElse { throw IllegalStateException("响应字段 $key 不是整数") }
}

/**
 * 严格读取一个必须存在且 trim 后非空的字符串字段。
 */
private fun JSONObject.requireString(key: String): String {
    if (!has(key) || isNull(key)) {
        throw IllegalStateException("响应缺少必填字段 $key")
    }
    val value = runCatching { getString(key) }
        .getOrElse { throw IllegalStateException("响应字段 $key 不是字符串") }
    if (value.trim().isEmpty()) {
        throw IllegalStateException("响应字段 $key 不能为空")
    }
    return value
}

/**
 * 严格读取一个必须存在但允许为空字符串的字符串字段（如 releaseNotes）。
 *
 * 与 [requireString] 的差异：key 不能缺失（缺失视为协议错误），但允许显式空字符串
 * （后端用空串表达"本次无版本说明"）。
 */
private fun JSONObject.requireOptionalString(key: String): String {
    if (!has(key) || isNull(key)) {
        throw IllegalStateException("响应缺少必填字段 $key")
    }
    return runCatching { getString(key) }
        .getOrElse { throw IllegalStateException("响应字段 $key 不是字符串") }
}
