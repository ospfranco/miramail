package com.ospfranco.safemail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.ospfranco.safemail.security.ScannedLink
import com.ospfranco.safemail.security.SecurityEvaluation

/** A tab on the right edge that opens a slide-in panel with every check and per-link result. */
@Composable
fun SecurityPanel(evaluation: SecurityEvaluation, links: List<ScannedLink>, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = Theme.colors

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelWidth = min(maxWidth * 0.85f, 360.dp)
        val panelOffset by animateDpAsState(if (open) 0.dp else panelWidth + 24.dp, tween(220), label = "panelOffset")
        val scrim by animateFloatAsState(if (open) 0.3f else 0f, tween(220), label = "scrim")

        BackHandler(enabled = open) { open = false }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = maxHeight * 0.4f)
                .shadow(4.dp, RoundedCornerShape(topStart = Spacing.three, bottomStart = Spacing.three))
                .background(evaluation.verdict.color, RoundedCornerShape(topStart = Spacing.three, bottomStart = Spacing.three))
                .clickable { open = true }
                .semantics { contentDescription = "Security: ${evaluation.summary}" }
                .testTag("security-tab")
                .width(32.dp)
                .padding(vertical = Spacing.three),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.one),
        ) {
            Text("🛡️", fontSize = 14.sp, lineHeight = 18.sp)
            Text(evaluation.verdict.glyph, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .then(
                    if (open) {
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = false }
                    } else {
                        Modifier
                    },
                ),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(panelWidth)
                .offset(x = panelOffset)
                .shadow(12.dp)
                .testTag("security-panel"),
            color = colors.background,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.three)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + Spacing.four),
                verticalArrangement = Arrangement.spacedBy(Spacing.three),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText("SECURITY", TextKind.SmallBold, color = colors.textSecondary)
                    AppText(
                        "Close",
                        TextKind.Link,
                        color = colors.linkPrimary,
                        modifier = Modifier.clickable { open = false }.padding(vertical = Spacing.two).testTag("security-close"),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.two)) {
                    VerdictDot(evaluation.verdict, 28.dp)
                    Text(evaluation.summary, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }

                for (check in evaluation.checks) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.backgroundElement, RoundedCornerShape(Spacing.three))
                            .padding(Spacing.three),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.two),
                    ) {
                        VerdictDot(check.verdict)
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.half)) {
                            AppText(check.label, TextKind.SmallBold)
                            AppText(check.detail, TextKind.Small, color = colors.textSecondary)
                        }
                    }
                }

                if (links.isNotEmpty()) {
                    AppText("LINKS", TextKind.SmallBold, color = colors.textSecondary, modifier = Modifier.padding(top = Spacing.two))
                    for (link in links) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.two)) {
                            VerdictDot(link.verdict, 16.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.half)) {
                                AppText(link.host.ifEmpty { link.url }, TextKind.Small, maxLines = 1)
                                for (check in link.checks) {
                                    Row(modifier = Modifier.padding(top = Spacing.half), horizontalArrangement = Arrangement.spacedBy(Spacing.one)) {
                                        VerdictDot(check.verdict, 12.dp, Modifier.padding(top = Spacing.half))
                                        Text(
                                            text = buildAnnotatedString {
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${check.kind.label}:") }
                                                append(" ${check.detail}")
                                            },
                                            style = TextKind.Small.style,
                                            color = colors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
