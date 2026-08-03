package com.ggufserver.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 「小模型轮椅」同款克制暖色：米白底、白卡片细边框、赤陶色点缀。
 * 字段名对应其 style.css 里的 CSS 变量（bg/side/line/ink/muted/faint/accent…）。
 */
@Immutable
data class WarmTones(
    val bg: Color,          // --bg 页面底色
    val side: Color,        // --side 次级底（日志、思考过程等）
    val line: Color,        // --line 分隔线 / 卡片描边
    val line2: Color,       // --line-2 输入框描边
    val ink: Color,         // --ink 正文
    val ink2: Color,        // --ink-2 次级正文
    val muted: Color,       // --muted 弱化文字
    val faint: Color,       // --faint 最淡文字
    val faintIcon: Color,   // .act-row .ic 淡图标
    val accent: Color,      // --accent 赤陶色
    val accentHover: Color, // --accent-hover
    val accentSoft: Color,  // rgba(accent,.08) 类浅赤陶底
    val card: Color,        // 卡片 / 输入卡白底
    val ok: Color,          // .s-dot.ok 绿
    val warn: Color,        // 警示黄
    val bad: Color,         // .s-dot.bad 红
    val userAvatar: Color,  // .avatar.user 底色
)

private val LightTones = WarmTones(
    bg = Color(0xFFFAF9F5),
    side = Color(0xFFF0EEE6),
    line = Color(0xFFE7E3D8),
    line2 = Color(0xFFE3DFD3),
    ink = Color(0xFF2D2A26),
    ink2 = Color(0xFF4A463D),
    muted = Color(0xFF6B6557),
    faint = Color(0xFFA8A293),
    faintIcon = Color(0xFFC9C3B3),
    accent = Color(0xFFC96442),
    accentHover = Color(0xFFB5563A),
    accentSoft = Color(0xFFFBEEE8),
    card = Color(0xFFFFFFFF),
    ok = Color(0xFF57995F),
    warn = Color(0xFFB5803A),
    bad = Color(0xFFD2483A),
    userAvatar = Color(0xFFE2DDCF),
)

private val DarkTones = WarmTones(
    bg = Color(0xFF211E1B),
    side = Color(0xFF2B2823),
    line = Color(0xFF3A362F),
    line2 = Color(0xFF413C34),
    ink = Color(0xFFECE8DE),
    ink2 = Color(0xFFCFC9BC),
    muted = Color(0xFFA39D8D),
    faint = Color(0xFF807A66),
    faintIcon = Color(0xFF5F594B),
    accent = Color(0xFFD97757),
    accentHover = Color(0xFFE2825F),
    accentSoft = Color(0xFF462E23),
    card = Color(0xFF292521),
    ok = Color(0xFF6FAF77),
    warn = Color(0xFFC99A5B),
    bad = Color(0xFFE06B57),
    userAvatar = Color(0xFF3D382F),
)

val LocalWarmTones = staticCompositionLocalOf { LightTones }

private fun schemeFrom(t: WarmTones, dark: Boolean) =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = t.accent,
        onPrimary = Color.White,
        primaryContainer = t.accentSoft,
        onPrimaryContainer = t.ink,
        secondary = t.muted,
        onSecondary = t.bg,
        secondaryContainer = t.userAvatar,
        onSecondaryContainer = t.ink2,
        tertiary = t.ok,
        onTertiary = Color.White,
        background = t.bg,
        onBackground = t.ink,
        surface = t.bg,
        onSurface = t.ink,
        surfaceVariant = t.side,
        onSurfaceVariant = t.muted,
        surfaceContainerLowest = t.card,
        surfaceContainerLow = t.card,
        surfaceContainer = t.card,
        surfaceContainerHigh = t.card,
        surfaceContainerHighest = t.side,
        outline = t.line2,
        outlineVariant = t.line,
        error = t.bad,
        onError = Color.White,
    )

@Composable
fun GgufTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val tones = if (dark) DarkTones else LightTones
    CompositionLocalProvider(LocalWarmTones provides tones) {
        MaterialTheme(colorScheme = schemeFrom(tones, dark), content = content)
    }
}

/** 轮椅风格卡片：白底、1dp 暖灰描边、14dp 圆角、零投影。 */
@Composable
fun WarmCard(
    modifier: Modifier = Modifier,
    container: Color = LocalWarmTones.current.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, LocalWarmTones.current.line),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}
