package com.chatgpt2api.imageapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 把生成结果图保存到系统相册。
 *
 * 优先使用 `result.b64Json`，否则按 [absoluteImageUrl] 拼出最终下载地址，
 * 复用 [HttpClientProvider] 的 OkHttpClient（自动注入鉴权头）。
 *
 * Android 10+：写入 `MediaStore.Images.Media.RELATIVE_PATH=Pictures/落落生图`，
 * Android 9-：直接写到外部存储 `Pictures/落落生图/` 然后 [MediaStore.Images.Media.insertImage]。
 */
class ImageDownloader(private val context: Context) {

    private val httpClient = HttpClientProvider.okHttp()

    sealed interface Outcome {
        data class Saved(val displayName: String, val uri: Uri) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun saveResult(result: ImageResult, baseUrl: String): Outcome = withContext(Dispatchers.IO) {
        runCatching {
            val (bytes, mime) = obtainBytes(result, baseUrl)
            val displayName = buildDisplayName(mime)
            val uri = writeToGallery(bytes, displayName, mime)
                ?: return@runCatching Outcome.Failed("写入相册失败")
            Outcome.Saved(displayName, uri) as Outcome
        }.getOrElse { error -> Outcome.Failed(error.message ?: "保存失败") }
    }

    private fun obtainBytes(result: ImageResult, baseUrl: String): Pair<ByteArray, String> {
        if (result.b64Json.isNotBlank()) {
            return Base64.decode(result.b64Json, Base64.DEFAULT) to "image/png"
        }
        val absolute = absoluteImageUrl(baseUrl, result.url)
        require(absolute.isNotBlank()) { "结果图缺少可下载数据" }
        val request = Request.Builder().url(absolute).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: error("下载内容为空")
            val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("image/") } ?: "image/png"
            return bytes to mime
        }
    }

    private fun writeToGallery(bytes: ByteArray, displayName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/落落生图")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }

        // Android 9 及以下
        @Suppress("DEPRECATION")
        val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "落落生图")
        if (!picturesDir.exists() && !picturesDir.mkdirs()) error("无法创建保存目录")
        val file = File(picturesDir, displayName)
        FileOutputStream(file).use { it.write(bytes) }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun buildDisplayName(mime: String): String {
        val ext = when {
            mime.contains("jpeg", true) || mime.contains("jpg", true) -> "jpg"
            mime.contains("webp", true) -> "webp"
            else -> "png"
        }
        val ts = System.currentTimeMillis()
        val suffix = UUID.randomUUID().toString().take(8)
        return "luoluo-$ts-$suffix.$ext"
    }
}
