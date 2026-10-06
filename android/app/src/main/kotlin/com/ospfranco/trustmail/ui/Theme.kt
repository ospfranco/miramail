package com.ospfranco.trustmail.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.semantics.testTag
import androidx.compose.foundation.layout.size
import com.ospfranco.trustmail.mail.SenderAvatar
import com.ospfranco.trustmail.security.SenderCheckLabel
import com.ospfranco.trustmail.security.Verdict

/** The colors screens use, all taken from the Material color scheme so they follow dynamic color and dark mode. */
class AppColors(
    val text: Color,
    val background: Color,
    val backgroundElement: Color,
    val backgroundSelected: Color,
    val textSecondary: Color,
    val linkPrimary: Color,
    /** The app's primary color (the blue of the app icon, unless dynamic color replaces it). */
    val brand: Color,
)

/** The blue of the app icon. */
private val Brand = Color(0xFF208AEF)

// Material 3 schemes generated from the brand blue, used where dynamic color isn't available (before Android 12).
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
    outlineVariant = Color(0xFFC3C7CF),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E8),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    outlineVariant = Color(0xFF43474E),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
)

private val LocalAppColors = staticCompositionLocalOf {
    AppColors(Color.Black, Color.White, Color.LightGray, Color.Gray, Color.DarkGray, Brand, Brand)
}

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

/** Material 3 with dynamic color (Android 12+): the app takes its colors from the user's wallpaper, like the system apps. */
@Composable
fun TrustmailTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    val colors = AppColors(
        text = scheme.onSurface,
        background = scheme.surface,
        backgroundElement = scheme.surfaceContainerHigh,
        backgroundSelected = scheme.outlineVariant,
        textSecondary = scheme.onSurfaceVariant,
        linkPrimary = scheme.primary,
        brand = scheme.primary,
    )
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private val Type = Typography()

/** Text styles shared by every screen, on the Material 3 type scale. */
enum class TextKind(val style: TextStyle) {
    Regular(Type.bodyLarge),
    Small(Type.bodyMedium),
    SmallBold(Type.titleSmall),
    Subtitle(Type.headlineLarge),
    Link(Type.labelLarge),
}

/** The sender's initial on a colored circle. */
@Composable
fun Avatar(from: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val avatar = SenderAvatar.of(from)
    Box(
        modifier = modifier.size(size).background(Color(0xFF000000 or avatar.hexColor.toLong(16)), CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(avatar.initial, color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
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

/** The sender's address when a sender check failed: the domain in red with the unsafe marker after it, like an unsafe link. */
@Composable
fun FailedSenderLine(address: String) {
    val at = address.lastIndexOf('@') + 1
    Row(
        // One element for accessibility; the tag is set inside, since clearing the semantics would drop it.
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$address, sender verification failed"
            testTag = "sender-warning"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.one),
    ) {
        Text(
            text = buildAnnotatedString {
                append(address.substring(0, at))
                withStyle(SpanStyle(color = Verdict.Danger.color, fontWeight = FontWeight.SemiBold)) { append(address.substring(at)) }
            },
            style = TextKind.Small.style,
            color = Theme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        VerdictDot(Verdict.Danger, 14.dp)
    }
}

/** A yellow marker for an email from someone you've never exchanged email with. */
@Composable
fun FirstMessageLine() {
    Row(
        modifier = Modifier.testTag("first-message"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.one),
    ) {
        VerdictDot(Verdict.Warning, 14.dp)
        AppText(SenderCheckLabel.FirstMessage.text, TextKind.Small, color = Theme.colors.textSecondary, maxLines = 1)
    }
}
