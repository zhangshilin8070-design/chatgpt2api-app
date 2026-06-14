package com.chatgpt2api.imageapp

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.UUID

/**
 * 参考图字节加载器：统一把"用户选图 URI""上一轮结果图（b64 或服务端 URL）"
 * 解析为可直接 multipart 上传的 [ReferenceBytes]。
 *
 * 与 Web 一致：多轮编辑通过重新上传完整图片二进制实现，后端不接受 URL 引用。
 */
class ImageBytesLoader(
    private val resolver: ContentResolver,
) {
    private val httpClient = HttpClientProvider.okHttp()

    /** 从用户相册选择的 URI 读取字节。 */
    suspend fun fromUri(uri: Uri): ReferenceBytes = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选图片")
        ReferenceBytes(
            name = displayName(uri),
            mimeType = resolver.getType(uri) ?: "image/png",
            bytes = bytes,
        )
    }

    /**
     * 从上一轮结果图构造参考图：优先用 b64_json，其次拉取服务端 URL。
     * 用于"继续编辑"多轮场景。
     */
    suspend fun fromResult(baseUrl: String, result: ImageResult): ReferenceBytes =
        withContext(Dispatchers.IO) {
            if (result.b64Json.isNotBlank()) {
                val bytes = Base64.decode(result.b64Json, Base64.DEFAULT)
                return@withContext ReferenceBytes(
                    name = "reference-${UUID.randomUUID()}.png",
                    mimeType = "image/png",
                    bytes = bytes,
                )
            }
            val absolute = absoluteImageUrl(baseUrl, result.url)
            require(absolute.isNotBlank()) { "结果图缺少可用数据" }
            val request = Request.Builder().url(absolute).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("拉取结果图失败：HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IllegalStateException("结果图为空")
                val mime = response.header("Content-Type") ?: "image/png"
                ReferenceBytes(
                    name = "reference-${UUID.randomUUID()}.${extensionForMime(mime)}",
                    mimeType = mime.substringBefore(';').trim().ifBlank { "image/png" },
                    bytes = bytes,
                )
            }
        }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
        return "reference-${UUID.randomUUID()}.png"
    }

    companion object {
        fun extensionForMime(mime: String): String = when {
            mime.contains("jpeg", true) || mime.contains("jpg", true) -> "jpg"
            mime.contains("webp", true) -> "webp"
            else -> "png"
        }

        /** 字节转 data URL，用于持久化参考图。 */
        fun toDataUrl(ref: ReferenceBytes): String {
            val b64 = Base64.encodeToString(ref.bytes, Base64.NO_WRAP)
            return "data:${ref.mimeType};base64,$b64"
        }

        /** data URL 还原为字节（持久化恢复用）。 */
        fun fromDataUrl(payload: ReferencePayload): ReferenceBytes {
            val comma = payload.dataUrl.indexOf(',')
            val b64 = if (comma >= 0) payload.dataUrl.substring(comma + 1) else payload.dataUrl
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            return ReferenceBytes(payload.name, payload.mimeType, bytes)
        }
    }
}
