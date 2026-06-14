package com.chatgpt2api.imageapp

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地持久化存储。
 *
 * 职责：
 *  - 服务端地址（baseUrl）：隐藏配置项，未配置时回落到 [DEFAULT_BASE_URL]。
 *  - 会话凭证：用户名、登录 token、展示名。严格不存密码（登出/失效需重新输入）。
 *  - 会话历史：多段对话的 JSON 序列化（按 Web 创作台机制本地维护）。
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Snapshot = Snapshot(
        baseUrl = effectiveBaseUrl(),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
    )

    // ===== baseUrl 隐藏配置 =====

    /** 返回用户配置的地址；未配置时回落默认地址。 */
    fun effectiveBaseUrl(): String {
        val configured = prefs.getString(KEY_BASE_URL, "").orEmpty().trim()
        return configured.ifBlank { DEFAULT_BASE_URL }
    }

    /** 返回用户实际配置的原始值（用于配置弹框回显，空表示走默认）。 */
    fun configuredBaseUrl(): String = prefs.getString(KEY_BASE_URL, "").orEmpty()

    fun saveBaseUrl(value: String) {
        prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()
    }

    // ===== 会话凭证 =====

    fun saveSession(username: String, token: String, displayName: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_TOKEN)
            .remove(KEY_DISPLAY_NAME)
            .apply()
    }

    // ===== 会话历史 =====

    fun loadConversations(): List<Conversation> =
        ConversationCodec.decodeList(prefs.getString(KEY_CONVERSATIONS, null))

    fun saveConversations(conversations: List<Conversation>) {
        prefs.edit().putString(KEY_CONVERSATIONS, ConversationCodec.encodeList(conversations)).apply()
    }

    // ===== 主题偏好 =====

    fun loadTheme(): ThemePref =
        runCatching { ThemePref.valueOf(prefs.getString(KEY_THEME, ThemePref.System.name).orEmpty()) }
            .getOrDefault(ThemePref.System)

    fun saveTheme(value: ThemePref) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    data class Snapshot(
        val baseUrl: String,
        val username: String,
        val token: String,
        val displayName: String,
    )

    companion object {
        /**
         * 默认服务端地址。开源仓库保持为空字符串，用户首次启动需在 App 内
         * 配置自己部署的 chatgpt2api 服务端地址（长按 logo / 设置页填写）。
         */
        const val DEFAULT_BASE_URL = ""

        private const val PREFS_NAME = "image_app"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_TOKEN = "token"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_THEME = "theme_pref"
    }
}
