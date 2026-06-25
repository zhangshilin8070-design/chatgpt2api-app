package com.chatgpt2api.imageapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 应用内更新下载与安装。
 *
 * 流程：
 *  1. [downloadApk]：通过共享 [HttpClientProvider.okHttp] 把 APK 拉到内部
 *     cache 的 `updates/<versionName>.apk`，下载进度通过回调上报。
 *  2. [installApk]：用 FileProvider 把 cache 文件转换为 content:// URI，再
 *     发起 ACTION_VIEW + MIME `application/vnd.android.package-archive`
 *     调起系统安装器。
 *
 * 触发安装需要 Android 8.0+ 用户授予 REQUEST_INSTALL_PACKAGES；未授予时
 * 调用方应回退到浏览器下载（[openDownloadInBrowser]）。
 *
 * 文件路径必须挂在 res/xml/file_provider_paths.xml 的 cache-path "updates" 下。
 */
internal object ApkInstaller {

    /** 下载状态回调；progress 范围 0..100；failed.message 已本地化简要描述。 */
    sealed class DownloadEvent {
        data class Progress(val percent: Int) : DownloadEvent()
        data class Completed(val file: File) : DownloadEvent()
        data class Failed(val message: String) : DownloadEvent()
    }

    /**
     * 异步下载 APK 到 cache。已存在的同名文件会被覆盖。
     *
     * 调用方应在 IO 协程作用域里调用本函数；onEvent 在调用线程上回调，
     * 调用方负责切回主线程更新 UI。
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        versionName: String,
        onEvent: (DownloadEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
        val target = File(targetDir, "folio-v${safeName}.apk")
        if (target.exists()) target.delete()

        val client = HttpClientProvider.okHttp()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onEvent(DownloadEvent.Failed("下载失败：HTTP ${resp.code}"))
                    return@withContext
                }
                val body = resp.body ?: run {
                    onEvent(DownloadEvent.Failed("下载失败：响应为空"))
                    return@withContext
                }
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onEvent(DownloadEvent.Progress(percent))
                                }
                            }
                        }
                    }
                }
                onEvent(DownloadEvent.Completed(target))
            }
        }.onFailure { err ->
            if (err is IOException) {
                onEvent(DownloadEvent.Failed("下载失败：${err.message ?: "网络错误"}"))
            } else {
                onEvent(DownloadEvent.Failed("下载失败：${err.message ?: err::class.simpleName ?: "未知错误"}"))
            }
        }
    }

    /**
     * 启动系统安装器安装下载到 cache 的 APK。
     *
     * 调用方必须保证 [apk] 是 cache 目录下、对应 FileProvider 已挂载的 path。
     * Android 8.0+ 在未授予 REQUEST_INSTALL_PACKAGES 权限时会先弹"允许安装
     * 未知应用"提示，授予后回到本应用需要重新点更新；本函数不主动检查
     * canRequestPackageInstalls()，把这个交给系统对话框去引导。
     */
    fun installApk(context: Context, apk: File): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 检查当前进程是否有权限调起 APK 安装器。
     * Android 7.x 不需要此权限始终返回 true；Android 8.0+ 取决于用户授权。
     */
    fun canInstallApks(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * 当应用内安装链路失败时（权限不足 / FileProvider 异常）回退到浏览器
     * 打开下载 URL，让用户自行下载安装。
     */
    fun openDownloadInBrowser(context: Context, url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
