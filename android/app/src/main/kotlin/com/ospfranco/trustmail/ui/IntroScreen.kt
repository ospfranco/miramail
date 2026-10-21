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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ospfranco.trustmail.R

private val steps = listOf(
    R.string.intro_step_open_title to R.string.intro_step_open_text,
    R.string.intro_step_verdict_title to R.string.intro_step_verdict_text,
    R.string.intro_step_tap_title to R.string.intro_step_tap_text,
)

/** Shown once, before the first sign-in: what Miramail does, and that Google sign-in comes next. */
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
                    Text(stringResource(R.string.intro_welcome), color = colors.text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    AppText(
                        stringResource(R.string.intro_tagline),
                        TextKind.Regular,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Image(
                    painter = painterResource(R.drawable.intro_preview),
                    contentDescription = stringResource(R.string.intro_preview_description),
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
                                AppText(stringResource(title), TextKind.SmallBold)
                                AppText(stringResource(text), TextKind.Small, color = colors.textSecondary)
                            }
                        }
                    }
                }

                AppText(
                    stringResource(R.string.intro_google_note),
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
        ) { Text(stringResource(R.string.intro_continue), style = MaterialTheme.typography.titleMedium) }
    }
}
