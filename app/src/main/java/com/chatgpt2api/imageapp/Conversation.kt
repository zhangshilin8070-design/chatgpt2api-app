package com.chatgpt2api.imageapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 本地会话模型。会话/轮次完全由客户端维护并持久化，
 * 后端不存储会话，只依赖 client_task_id 去重（与 Web 创作台一致）。
 */

/** 一轮交互的模式。 */
enum class TurnMode { Chat, Generate, Edit }

/** 单轮交互（一次提交）。包含用户输入与该轮产出的图片/文本结果。 */
data class Turn(
    val id: String = UUID.randomUUID().toString(),
    /** 提交给后端的 client_task_id，用于轮询回填与幂等。 */
    val taskId: String = UUID.randomUUID().toString(),
    val prompt: String,
    val mode: TurnMode,
    val model: String,
    val size: String,
    val quality: String,
    val n: Int,
    val outputFormat: String,
    val imageResolution: String,
    val visibility: String,
    /** 该轮使用的参考图（编辑模式）；持久化时仅保留可重建的数据 URL。 */
    val referenceImages: List<ReferencePayload> = emptyList(),
    /** 任务状态：queued/running/success/error/cancelled。 */
    val status: String = "queued",
    /** success 时若为 text 表示文本回复。 */
    val outputType: String = "",
    val outputStatuses: List<String> = emptyList(),
    val results: List<ImageResult> = emptyList(),
    val error: String = "",
    val progress: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 参考图的可序列化载荷。
 *
 * 多轮编辑时，上一轮结果图会被转成 data URL 存入这里，下一轮重新以二进制上传，
 * 与 Web 端"重新上传参考图"的机制保持一致（后端不接受 URL 引用）。
 */
data class ReferencePayload(
    val name: String,
    val mimeType: String,
    /** 形如 data:image/png;base64,xxxx。 */
    val dataUrl: String,
)

/** 一段会话，包含多轮。 */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val turns: List<Turn> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

// ===== JSON 序列化（org.json，无额外依赖） =====

internal object ConversationCodec {

    fun encodeList(conversations: List<Conversation>): String {
        val array = JSONArray()
        conversations.forEach { array.put(encode(it)) }
        return array.toString()
    }

    fun decodeList(raw: String?): List<Conversation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(decode(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(c: Conversation): JSONObject {
        val turns = JSONArray()
        c.turns.forEach { turns.put(encodeTurn(it)) }
        return JSONObject()
            .put("id", c.id)
            .put("title", c.title)
            .put("createdAt", c.createdAt)
            .put("updatedAt", c.updatedAt)
            .put("turns", turns)
    }

    private fun decode(o: JSONObject): Conversation {
        val turnsArray = o.optJSONArray("turns") ?: JSONArray()
        val turns = buildList {
            for (i in 0 until turnsArray.length()) {
                turnsArray.optJSONObject(i)?.let { add(decodeTurn(it)) }
            }
        }
        return Conversation(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title"),
            turns = turns,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private fun encodeTurn(t: Turn): JSONObject {
        val refs = JSONArray()
        // 关键：referenceImages 的 dataUrl（整张图 base64）不写盘，避免单条会话上百 MB JSON。
        // 仅保留 name + mime 让历史还能展示"含 N 张参考图"摘要；冷启动后无法基于历史 turn
        // 继续编辑——这是合理代价（同样地，浏览器关掉后再开 ChatGPT 也不能直接接着用上次的本地参考图）。
        t.referenceImages.forEach {
            refs.put(
                JSONObject()
                    .put("name", it.name)
                    .put("mimeType", it.mimeType)
                    .put("dataUrl", "")
            )
        }
        val results = JSONArray()
        t.results.forEach {
            results.put(
                JSONObject()
                    // b64Json 是整张 PNG 的 base64，单条几 MB；不写盘。
                    // 冷启动只剩 url（受保护链接） + 文本回复 + revisedPrompt。
                    .put("b64Json", "")
                    .put("url", it.url)
                    .put("revisedPrompt", it.revisedPrompt)
                    .put("textResponse", it.textResponse)
                    .put("width", it.width)
                    .put("height", it.height)
                    .put("sizeBytes", it.sizeBytes)
            )
        }
        val statuses = JSONArray()
        t.outputStatuses.forEach { statuses.put(it) }
        return JSONObject()
            .put("id", t.id)
            .put("taskId", t.taskId)
            .put("prompt", t.prompt)
            .put("mode", t.mode.name)
            .put("model", t.model)
            .put("size", t.size)
            .put("quality", t.quality)
            .put("n", t.n)
            .put("outputFormat", t.outputFormat)
            .put("imageResolution", t.imageResolution)
            .put("visibility", t.visibility)
            .put("referenceImages", refs)
            .put("status", t.status)
            .put("outputType", t.outputType)
            .put("outputStatuses", statuses)
            .put("results", results)
            .put("error", t.error)
            .put("progress", t.progress)
            .put("createdAt", t.createdAt)
    }

    private fun decodeTurn(o: JSONObject): Turn {
        val refsArray = o.optJSONArray("referenceImages") ?: JSONArray()
        val refs = buildList {
            for (i in 0 until refsArray.length()) {
                refsArray.optJSONObject(i)?.let {
                    add(
                        ReferencePayload(
                            name = it.optString("name"),
                            mimeType = it.optString("mimeType", "image/png"),
                            dataUrl = it.optString("dataUrl"),
                        )
                    )
                }
            }
        }
        val resultsArray = o.optJSONArray("results") ?: JSONArray()
        val results = buildList {
            for (i in 0 until resultsArray.length()) {
                resultsArray.optJSONObject(i)?.let {
                    add(
                        ImageResult(
                            b64Json = it.optString("b64Json"),
                            url = it.optString("url"),
                            revisedPrompt = it.optString("revisedPrompt"),
                            textResponse = it.optString("textResponse"),
                            width = it.optInt("width", 0),
                            height = it.optInt("height", 0),
                            sizeBytes = it.optLong("sizeBytes", 0),
                        )
                    )
                }
            }
        }
        val statusesArray = o.optJSONArray("outputStatuses") ?: JSONArray()
        val statuses = buildList {
            for (i in 0 until statusesArray.length()) add(statusesArray.optString(i))
        }
        return Turn(
            id = o.optString("id", UUID.randomUUID().toString()),
            taskId = o.optString("taskId", UUID.randomUUID().toString()),
            prompt = o.optString("prompt"),
            mode = runCatching { TurnMode.valueOf(o.optString("mode", "Generate")) }
                .getOrDefault(TurnMode.Generate),
            model = o.optString("model", "auto"),
            size = o.optString("size", "1:1"),
            quality = o.optString("quality", "auto"),
            n = o.optInt("n", 1),
            outputFormat = o.optString("outputFormat", "png"),
            imageResolution = o.optString("imageResolution", ""),
            visibility = o.optString("visibility", "private"),
            referenceImages = refs,
            status = o.optString("status", "queued"),
            outputType = o.optString("outputType", ""),
            outputStatuses = statuses,
            results = results,
            error = o.optString("error", ""),
            progress = o.optString("progress", ""),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}
