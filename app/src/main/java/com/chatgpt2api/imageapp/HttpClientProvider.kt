package com.chatgpt2api.imageapp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端工厂。
 *
 * 单例 OkHttpClient 同时被 [ApiClient] 与 Coil ImageLoader 使用，
 * 通过 [AuthCredentialSource] 共享当前会话凭证，从而让图片加载请求自动携带
 * `Authorization: Bearer <token>`。仅对配置中的 baseUrl 同 host 的请求注入凭证，
 * 避免向无关域名（包括 OpenAI 上游或第三方图床）泄漏 token。
 */
object HttpClientProvider {

    /** 调用方实现该接口提供当前会话凭证（baseUrl + token）。 */
    interface AuthCredentialSource {
        fun current(): Credentials?
        data class Credentials(val baseUrl: String, val token: String)
    }

    @Volatile
    private var source: AuthCredentialSource = NoCredentials

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor { source })
            .build()
    }

    /** 应用启动时注册凭证来源；ViewModel/SessionStore 是天然的来源。 */
    fun bindCredentialSource(s: AuthCredentialSource) {
        source = s
    }

    fun okHttp(): OkHttpClient = client

    private object NoCredentials : AuthCredentialSource {
        override fun current(): AuthCredentialSource.Credentials? = null
    }

    /**
     * 鉴权拦截器：
     *  1. 仅当请求 host 等于当前 baseUrl host、且请求未自带 Authorization 时注入 Bearer token；
     *  2. 对于受保护资源（/images/、/api/ 等）的 401/403 响应，强制覆盖 Cache-Control 为 no-store
     *     并去掉 ETag/Last-Modified，避免 Coil 把鉴权失败响应当作有效图片缓存到磁盘，
     *     导致 token 失效后再打开 App 一直显示空白。
     */
    private class AuthInterceptor(
        private val sourceProvider: () -> AuthCredentialSource,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val needsInject = original.header("Authorization") == null
            val request = if (!needsInject) {
                original
            } else {
                val creds = sourceProvider().current()
                val baseHost = creds?.baseUrl?.toHttpUrlOrNull()?.host
                if (creds == null || creds.token.isBlank() || baseHost == null ||
                    !original.url.host.equals(baseHost, ignoreCase = true)
                ) {
                    original
                } else {
                    original.newBuilder()
                        .header("Authorization", "Bearer ${creds.token.trim()}")
                        .build()
                }
            }
            val response = chain.proceed(request)
            if (response.code == 401 || response.code == 403) {
                return response.newBuilder()
                    .header("Cache-Control", "no-store, no-cache, must-revalidate")
                    .removeHeader("ETag")
                    .removeHeader("Last-Modified")
                    .removeHeader("Expires")
                    .build()
            }
            return response
        }
    }
}
