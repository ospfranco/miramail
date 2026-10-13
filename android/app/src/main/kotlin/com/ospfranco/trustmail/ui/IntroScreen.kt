package com.ospfranco.trustmail.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ospfranco.trustmail.R

private val steps = listOf(
    "Open an email" to "Trustmail checks who really sent it and scans every link in it.",
    "See the verdict" to "A green, yellow or red mark sits next to each link, and anything wrong is explained at the top of the email.",
    "Tap with confidence" to "Risky links ask you to confirm before they open.",
)

/** Shown once, before the first sign-in: what Trustmail does, and that Google sign-in comes next. */
@Composable
fun IntroScreen(onContinue: () -> Unit) {
    val colors = Theme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = Theme.maxContentWidth).padding(Spacing.three),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.four),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.two)) {
                    Text("Welcome to Trustmail", color = colors.text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    AppText(
                        "Trustmail checks every email for phishing before you act on it.",
                        TextKind.Regular,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Image(
                    painter = painterResource(R.drawable.intro_preview),
                    contentDescription = "Preview: an email with a safety mark next to each link and a warning at the top",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(Spacing.three))
                        .border(1.dp, colors.backgroundSelected, RoundedCornerShape(Spacing.three))
                        .testTag("intro-preview"),
                )

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.three)) {
                    steps.forEachIndexed { index, (title, text) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.three)) {
                            Box(
                                modifier = Modifier.size(28.dp).background(colors.brand, CircleShape).semantics { contentDescription = "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.half)) {
                                AppText(title, TextKind.SmallBold)
                                AppText(text, TextKind.Small, color = colors.textSecondary)
                            }
                        }
                    }
                }

                AppText(
                    "On the next step you'll sign in with your Google account, so Trustmail can read your Gmail.",
                    TextKind.Small,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("intro-google-note"),
                )
            }
        }

        Button(
            onClick = onContinue,
            contentPadding = PaddingValues(vertical = Spacing.three),
            modifier = Modifier
                .padding(horizontal = Spacing.three)
                .padding(bottom = Spacing.two)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = Theme.maxContentWidth)
                .fillMaxWidth()
                .testTag("intro-continue"),
        ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
    }
}
