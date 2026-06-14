package com.chatgpt2api.imageapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: ImageAppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让内容延伸到状态栏与导航栏后方；
        // 顶栏自己 statusBarsPadding 让出标题位置；Composer imePadding 跟随键盘。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val themePref by viewModel.theme.collectAsStateWithLifecycle()
            // 主题切换时同步状态栏图标深浅
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themePref) {
                ThemePref.Light -> false
                ThemePref.Dark -> true
                ThemePref.System -> systemDark
            }
            LaunchedEffect(isDark) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
            AppTheme(themePref = themePref) {
                val auth by viewModel.auth.collectAsStateWithLifecycle()
                val composer by viewModel.composer.collectAsStateWithLifecycle()
                val convo by viewModel.conversations.collectAsStateWithLifecycle()
                val baseUrlConfig by viewModel.baseUrlConfig.collectAsStateWithLifecycle()
                val toasts by viewModel.toasts.collectAsStateWithLifecycle()
                val context = LocalContext.current

                LaunchedEffect(toasts) {
                    val pending = toasts.firstOrNull() ?: return@LaunchedEffect
                    Toast.makeText(context, pending, Toast.LENGTH_SHORT).show()
                    viewModel.consumeToast(pending)
                }

                GlassBackground {
                    if (auth.screen == AuthScreen.Authenticated) {
                        ConversationScreen(auth, composer, convo, viewModel)
                    } else {
                        AuthScreenContent(auth, viewModel)
                    }
                }
                if (baseUrlConfig.visible) {
                    BaseUrlConfigDialog(baseUrlConfig, viewModel)
                }
                val optimizeFeedback by viewModel.optimizeFeedback.collectAsStateWithLifecycle()
                optimizeFeedback?.let {
                    OptimizeFeedbackDialog(it) { viewModel.consumeOptimizeFeedback() }
                }
                // App 版本检查覆盖层：Force 模式必须画在所有业务首屏（含 Authenticated）与
                // 其他对话框之上，因此放在 setContent lambda 的最后渲染（Compose 后绘制覆盖前者）。
                val appUpdate by viewModel.appUpdate.collectAsStateWithLifecycle()
                AppUpdateOverlay(appUpdate, viewModel::dismissOptionalUpdate)
            }
        }
    }
}
