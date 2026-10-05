package com.ospfranco.trustmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import com.ospfranco.trustmail.security.Verdict

class AppColors(
    val text: Color,
    val background: Color,
    val backgroundElement: Color,
    val backgroundSelected: Color,
    val textSecondary: Color,
    val linkPrimary: Color = Color(0xFF3C87F7),
    /** The blue of the app icon. */
    val brand: Color = Color(0xFF208AEF),
)

private val LightColors = AppColors(
    text = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    backgroundElement = Color(0xFFF0F0F3),
    backgroundSelected = Color(0xFFE0E1E6),
    textSecondary = Color(0xFF60646C),
)

private val DarkColors = AppColors(
    text = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    backgroundElement = Color(0xFF212225),
    backgroundSelected = Color(0xFF2E3135),
    textSecondary = Color(0xFFB0B4BA),
)

private val LocalAppColors = staticCompositionLocalOf { LightColors }

object Theme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current

    val maxContentWidth: Dp = 800.dp
}

/** Spacing scale shared by every screen (in dp). */
object Spacing {
    val half = 2.dp
    val one = 4.dp
    val two = 8.dp
    val three = 16.dp
    val four = 24.dp
    val five = 32.dp
    val six = 64.dp
}

val Verdict.color: Color get() = Color(0xFF000000 or hexColor.toLong(16))

@Composable
fun TrustmailTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(background = colors.background, surface = colors.background, onSurface = colors.text, onBackground = colors.text)
    } else {
        lightColorScheme(background = colors.background, surface = colors.background, onSurface = colors.text, onBackground = colors.text)
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Text styles shared by every screen. */
enum class TextKind(val style: TextStyle) {
    Regular(TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)),
    Small(TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)),
    SmallBold(TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)),
    Subtitle(TextStyle(fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)),
    Link(TextStyle(fontSize = 14.sp, lineHeight = 30.sp)),
}

@Composable
fun AppText(
    text: String,
    kind: TextKind = TextKind.Regular,
    modifier: Modifier = Modifier,
    color: Color = Theme.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    Text(text = text, style = kind.style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = modifier)
}

@Composable
fun VerdictDot(verdict: Verdict, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).background(verdict.color, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = verdict.glyph,
            color = Color.White,
            fontSize = (size.value * 0.6f).sp,
            lineHeight = size.value.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
