package com.chatgpt2api.imageapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主线程禁解码：所有 base64/byte → Bitmap 的过程都走这里。
 *
 * 使用 [BitmapFactory.Options.inSampleSize] 让缩略图只解码必要像素，
 * 例如 64dp 的 [ReferenceThumb] 解 128px 即可，避免一张 4K 原图被
 * 主线程解码出 4096×4096 的 Bitmap（256MB ARGB_8888）。
 *
 * - [targetMaxEdge] 0 表示不缩小（用于全屏 lightbox）
 * - 默认 [Bitmap.Config.RGB_565] 给缩略图省一半内存
 */
object BitmapDecoders {

    /**
     * 异步把 base64 字符串解码为 [ImageBitmap]，按目标边缩放采样。
     * 若解析失败返回 null（不抛）。
     */
    suspend fun decodeBase64(
        b64: String,
        targetMaxEdge: Int = 0,
        useThumbnailConfig: Boolean = false,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            decodeBytes(bytes, targetMaxEdge, useThumbnailConfig)
        }.getOrNull()
    }

    /**
     * 异步把字节数组解码为 [ImageBitmap]。
     */
    suspend fun decodeBytes(
        bytes: ByteArray,
        targetMaxEdge: Int = 0,
        useThumbnailConfig: Boolean = false,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val sample = if (targetMaxEdge > 0) calcInSampleSize(opts, targetMaxEdge) else 1
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (useThumbnailConfig) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.asImageBitmap()
        }.getOrNull()
    }

    /** 仅读 base64 图片的真实像素尺寸（不解码完整 Bitmap）。失败返回 (0, 0)。 */
    suspend fun measureBase64(b64: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
        }.getOrDefault(0 to 0)
    }

    /**
     * 仅读远程 URL 图片的真实像素尺寸（不下载完整文件、不解码完整 Bitmap）。
     *
     * 使用 `BitmapFactory.decodeStream + inJustDecodeBounds=true` 读 PNG/JPEG/
     * WEBP 等格式的文件头即可拿到 outWidth/outHeight。配合 [HttpClientProvider]
     * 的共享 OkHttpClient 自动注入鉴权头，对受保护资源仍能解析。
     *
     * 失败返回 (0, 0)；本函数与 [measureBase64] 等价，专门给 url 形态结果使用。
     */
    suspend fun measureUrl(url: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext 0 to 0
        runCatching {
            val request = okhttp3.Request.Builder().url(url).get().build()
            HttpClientProvider.okHttp().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use 0 to 0
                val body = resp.body ?: return@use 0 to 0
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                body.byteStream().use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
            }
        }.getOrDefault(0 to 0)
    }

    private fun calcInSampleSize(opts: BitmapFactory.Options, targetEdge: Int): Int {
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0 || targetEdge <= 0) return 1
        var sample = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / sample >= targetEdge && halfH / sample >= targetEdge) {
            sample *= 2
        }
        return sample
    }
}
