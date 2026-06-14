package com.chatgpt2api.imageapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BaseUrlConfigDialog(state: BaseUrlConfigState, actions: ImageAppViewModel) {
    AlertDialog(
        onDismissRequest = actions::dismissBaseUrlConfig,
        title = { Text("服务端地址", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "请填写你自行部署的 chatgpt2api 服务端地址，例如 http://192.168.1.10:3000 或 https://your-domain.example",
                    color = Glass.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.value,
                    onValueChange = actions::updateBaseUrlConfigValue,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Base URL") },
                    colors = glassFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions::saveBaseUrlConfig,
                enabled = state.value.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = actions::dismissBaseUrlConfig) { Text("取消") } },
        containerColor = Glass.Surface,
    )
}

@Composable
internal fun AuthScreenContent(state: AuthState, actions: ImageAppViewModel) {
    val isRegister = state.screen == AuthScreen.Register
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
    ) {
        item { Spacer(Modifier.height(40.dp)) }
        // 杂志封面：上方左对齐的小标 + 朱红短分隔 + 大字 logo
        item {
            Column(
                modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures(onLongPress = { actions.openBaseUrlConfig() })
                },
            ) {
                Text(
                    "ISSUE · 01",
                    fontSize = 9.sp,
                    color = Glass.TextSecondary,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.width(28.dp).height(2.dp).background(Glass.Accent))
                Spacer(Modifier.height(28.dp))
                Text(
                    "折页",
                    fontWeight = FontWeight.Bold,
                    fontSize = 56.sp,
                    color = Glass.TextPrimary,
                    letterSpacing = 1.sp,
                    lineHeight = 56.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "AI EDITORIAL · MULTI-TURN PAINTING",
                    fontSize = 9.sp,
                    color = Glass.TextSecondary,
                    letterSpacing = 2.sp,
                )
            }
        }
        item { Spacer(Modifier.height(48.dp)) }

        // 输入区：下划线式输入栏，无卡片包裹，纯文字 + 1dp 黑色横线
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isRegister) "REGISTER" else "SIGN IN",
                    fontSize = 9.sp,
                    color = Glass.TextSecondary,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.height(20.dp))

                AuthLineField(
                    value = state.username,
                    onValueChange = actions::updateUsername,
                    label = "用户名",
                )
                Spacer(Modifier.height(18.dp))
                AuthLineField(
                    value = state.password,
                    onValueChange = actions::updatePassword,
                    label = "密码",
                    password = true,
                )
                if (isRegister) {
                    Spacer(Modifier.height(18.dp))
                    AuthLineField(
                        value = state.passwordConfirm,
                        onValueChange = actions::updatePasswordConfirm,
                        label = "确认密码",
                        password = true,
                    )
                }

                if (state.error.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Banner(state.error, Glass.Error, Glass.ErrorBg)
                }
                if (state.info.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Banner(state.info, Glass.Accent, Glass.AccentSoft)
                }

                Spacer(Modifier.height(28.dp))
                PrimaryButton(
                    text = if (isRegister) "注册并登录" else "登录",
                    onClick = { if (isRegister) actions.register() else actions.login() },
                    loading = state.busy,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        actions.switchAuthScreen(if (isRegister) AuthScreen.Login else AuthScreen.Register)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isRegister) "已有账号，返回登录" else "没有账号，注册一个",
                        color = Glass.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * 杂志风账号输入栏：标签上提 + 1dp 单线分隔。
 * 无外框、无填充、有节奏，比 OutlinedTextField 更克制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthLineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            color = Glass.TextSecondary,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Glass.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Glass.Ink),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Glass.Ink.copy(alpha = 0.6f)))
    }
}
