package com.chatgpt2api.imageapp

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject

/**
 * /v1/chat/completions 的 SSE 流式客户端，仅供"对话"模式使用。
 *
 * 协议：OpenAI 兼容 chunk
 *   data: {"choices":[{"delta":{"role":"assistant","content":"..."}}]}
 *   data: {"choices":[{"delta":{"content":"..."}}]}
 *   ...
 *   data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
 *   data: [DONE]
 *
 * "作画"任务保持 /api/creation-tasks 异步轮询路径，与本类无关。
 */
class ChatStreamClient {

    private val client = HttpClientProvider.okHttp()
    private val factory = EventSources.createFactory(client)

    /** 单事件输出。consumer 通过 [Event.Done] / [Event.Error] 终止流。 */
    sealed interface Event {
        /** 增量 token；可能为空字符串（首条 role chunk）。 */
        data class Delta(val text: String) : Event
        /** 流正常结束（收到 finish_reason 或 [DONE]）。 */
        data object Done : Event
        /** 错误终止（HTTP 失败、JSON 异常、网络断开等）。 */
        data class Error(val message: String, val unauthorized: Boolean = false) : Event
    }

    /**
     * 启动一次流式请求。返回 Flow 在协程取消时自动断开 SSE 连接。
     *
     * @param config baseUrl + token，用于构造 /v1/chat/completions 请求
     * @param model "auto" 或具体文本模型名
     * @param messages 多轮上下文（与 web 一致：user/assistant 交替）
     */
    fun stream(config: AppConfig, model: String, messages: List<ChatMessage>): Flow<Event> = callbackFlow {
        val base = config.baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "服务端地址未配置" }
        require(config.token.isNotBlank()) { "未登录，请先完成登录" }

        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
        val request = Request.Builder()
            .url(base + "/v1/chat/completions")
            .header("Authorization", "Bearer ${config.token.trim()}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(Event.Done)
                    close()
                    return
                }
                val parsed = runCatching { JSONObject(data) }.getOrNull()
                if (parsed == null) {
                    trySend(Event.Error("收到非 JSON 流式片段"))
                    close()
                    return
                }
                if (parsed.has("error")) {
                    val message = parsed.optJSONObject("error")?.optString("message")
                        ?: parsed.optString("error", "上游返回错误")
                    trySend(Event.Error(message))
                    close()
                    return
                }
                val delta = parsed.optJSONArray("choices")
                    ?.optJSONObject(0)
                val finishReason = delta?.optString("finish_reason").orEmpty()
                val content = delta?.optJSONObject("delta")?.optString("content").orEmpty()
                if (content.isNotEmpty()) {
                    trySend(Event.Delta(content))
                }
                if (finishReason.isNotBlank() && finishReason != "null") {
                    trySend(Event.Done)
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val code = response?.code
                val rawBody = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                val message = when {
                    code == 401 || code == 403 -> "登录已过期，请重新登录"
                    rawBody.isNotBlank() -> rawBody.take(300)
                    t != null -> t.message ?: "流式连接失败"
                    code != null -> "请求失败：HTTP $code"
                    else -> "流式连接失败"
                }
                trySend(Event.Error(message, unauthorized = code == 401 || code == 403))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                // 服务端正常关闭但没下发 [DONE]/finish_reason 时也终止流
                trySend(Event.Done)
                close()
            }
        }

        val source = factory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
