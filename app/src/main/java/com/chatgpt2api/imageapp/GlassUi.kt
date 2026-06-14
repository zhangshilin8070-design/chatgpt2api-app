package com.chatgpt2api.imageapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 极简主义现代杂志风视觉体系（双主题）。
 *
 * 设计基调：白纸+油墨黑+朱红 / 暗夜+牛奶白+朱红。
 *
 * 实现策略：所有色值都从 [LocalPalette] 读取，[Glass] 是兼容层，
 * 在 Composable 上下文里自动返回当前主题对应的色。已有调用点（成百上千处）
 * 不需要改——读 `Glass.Ink` 在 light 下拿到 `#0E0E0E`，在 dark 下拿到 `#F2F0EB`。
 */
data class Palette(
    val paper: Color,
    val paperDeep: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val ink: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSoft: Color,
    val warm: Color, // 奶油黄，状态点缀
    val error: Color,
    val errorBg: Color,
    val infoBg: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassBorder: Color,
    val isDark: Boolean,
)

val LightPaletteForTheme = Palette(
    paper = Color(0xFFFAFAF7),
    paperDeep = Color(0xFFF1EFE9),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF5F4EF),
    ink = Color(0xFF0E0E0E),
    textPrimary = Color(0xFF0E0E0E),
    textSecondary = Color(0xFF7A7A7A),
    accent = Color(0xFFD6483B),
    accentSoft = Color(0x14D6483B),
    warm = Color(0xFFE8C547),
    error = Color(0xFFC23525),
    errorBg = Color(0x12C23525),
    infoBg = Color(0x0F0E0E0E),
    glassFill = Color(0xFFFFFFFF),
    glassFillStrong = Color(0xFFFFFFFF),
    glassBorder = Color(0x14000000),
    isDark = false,
)

val DarkPaletteForTheme = Palette(
    paper = Color(0xFF111111),
    paperDeep = Color(0xFF0A0A0A),
    surface = Color(0xFF181818),
    surfaceMuted = Color(0xFF1F1F1F),
    // dark 模式下 Ink 反而是接近牛奶白，做"主文字 / 主按钮底色"
    ink = Color(0xFFEFECE5),
    textPrimary = Color(0xFFEFECE5),
    textSecondary = Color(0xFF9A968D),
    accent = Color(0xFFE85B4F), // 暗色下朱红略提亮
    accentSoft = Color(0x1FE85B4F),
    warm = Color(0xFFE8C547),
    error = Color(0xFFE85B4F),
    errorBg = Color(0x1FE85B4F),
    infoBg = Color(0x14EFECE5),
    glassFill = Color(0xFF181818),
    glassFillStrong = Color(0xFF1A1A1A),
    glassBorder = Color(0x22FFFFFF),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPaletteForTheme }

@Composable
fun rememberPalette(): Palette = if (isSystemInDarkTheme()) DarkPaletteForTheme else LightPaletteForTheme

/** 兼容层：所有原来的 Glass.X 调用点零修改。 */
object Glass {
    val Mint: Color
        @Composable get() = LocalPalette.current.accent
    val MintDeep: Color
        @Composable get() = LocalPalette.current.accent
    val Sky: Color
        @Composable get() = LocalPalette.current.warm
    val Violet: Color
        @Composable get() = LocalPalette.current.ink

    val Paper: Color
        @Composable get() = LocalPalette.current.paper
    val PaperDeep: Color
        @Composable get() = LocalPalette.current.paperDeep
    val Surface: Color
        @Composable get() = LocalPalette.current.surface
    val SurfaceMuted: Color
        @Composable get() = LocalPalette.current.surfaceMuted

    val Ink: Color
        @Composable get() = LocalPalette.current.ink
    val TextPrimary: Color
        @Composable get() = LocalPalette.current.textPrimary
    val TextSecondary: Color
        @Composable get() = LocalPalette.current.textSecondary
    val TextOnGlass: Color
        @Composable get() = LocalPalette.current.textPrimary

    val Accent: Color
        @Composable get() = LocalPalette.current.accent
    val AccentSoft: Color
        @Composable get() = LocalPalette.current.accentSoft

    val Error: Color
        @Composable get() = LocalPalette.current.error
    val ErrorBg: Color
        @Composable get() = LocalPalette.current.errorBg
    val InfoBg: Color
        @Composable get() = LocalPalette.current.infoBg

    val GlassFill: Color
        @Composable get() = LocalPalette.current.glassFill
    val GlassFillStrong: Color
        @Composable get() = LocalPalette.current.glassFillStrong
    val GlassBorder: Color
        @Composable get() = LocalPalette.current.glassBorder
    val GlassHighlight: Color = Color.Transparent
}

@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize().background(Glass.Paper)) {
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    contentPadding: Dp = 16.dp,
    strong: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderColor = if (strong) {
        if (LocalPalette.current.isDark) Color(0x33FFFFFF) else Color(0x1F000000)
    } else Glass.GlassBorder
    Box(
        modifier = modifier
            .clip(shape)
            .background(Glass.Surface)
            .border(1.dp, borderColor, shape),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Glass.Surface)
            .border(1.dp, Glass.GlassBorder, shape),
        content = { content() },
    )
}
