package com.ospfranco.trustmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.ospfranco.trustmail.security.Verdict
import com.ospfranco.trustmail.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, session: SessionViewModel, onBack: () -> Unit) {
    val colors = Theme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text("Settings", color = colors.text) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.three)
                .widthIn(max = Theme.maxContentWidth),
            verticalArrangement = Arrangement.spacedBy(Spacing.two),
        ) {
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText("Allow JavaScript in emails", TextKind.Regular, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.allowEmailJavaScript,
                        onCheckedChange = settings::updateAllowEmailJavaScript,
                        modifier = Modifier.testTag("javascript-toggle"),
                    )
                }
                AppText(
                    "Lets scripts inside an email run. Scripts can change what you see after Trustmail has scanned the links, so leave this off unless an email needs it. Applies the next time you open an email.",
                    TextKind.Small,
                    color = colors.textSecondary,
                )
            }

            Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText("Ignore footer links", TextKind.Regular, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.ignoreFooterLinks,
                        onCheckedChange = settings::updateIgnoreFooterLinks,
                        modifier = Modifier.testTag("footer-links-toggle"),
                    )
                }
                AppText(
                    "Suspicious links in an email's footer (unsubscribe, privacy policy, social media) don't count toward the email's verdict. They still get a badge and a warning when you tap them, and an unsafe footer link still counts. Applies the next time you open an email.",
                    TextKind.Small,
                    color = colors.textSecondary,
                )
            }

            session.user?.email?.let { email ->
                Card {
                    AppText(email, TextKind.Small, color = colors.textSecondary)
                    AppText(
                        "Sign out",
                        TextKind.Regular,
                        color = Verdict.Danger.color,
                        modifier = Modifier
                            .clickable {
                                session.signOut()
                                onBack()
                            }
                            .padding(vertical = Spacing.one)
                            .testTag("settings-sign-out"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Theme.colors.backgroundElement, RoundedCornerShape(Spacing.three))
            .padding(Spacing.three),
        verticalArrangement = Arrangement.spacedBy(Spacing.two),
        content = content,
    )
}
