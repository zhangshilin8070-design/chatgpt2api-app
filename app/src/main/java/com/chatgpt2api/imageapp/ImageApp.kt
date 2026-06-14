package com.chatgpt2api.imageapp

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import coil.request.CachePolicy

/**
 * Application 入口，统一初始化 Coil 全局 ImageLoader。
 *
 * - OkHttp 复用 [HttpClientProvider]，让受保护的 /images/ 资源自动携带 Bearer token。
 * - 仅启用内存缓存：受保护资源依赖会话 token，磁盘缓存会让 token 失效后的 401 响应被复用，
 *   或在切换账号后让别的账号看到上一个账号的图。运行时内存缓存够用且足够安全。
 * - [HttpClientProvider] 的拦截器会把 401/403 响应改成 no-store，防止任何缓存层（包括 OkHttp
 *   的 HTTP cache、Coil 的内存缓存键值）误把鉴权失败响应当作有效图片。
 */
class ImageApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(HttpClientProvider.okHttp())
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .respectCacheHeaders(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
}
