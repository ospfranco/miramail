package com.ospfranco.trustmail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.ospfranco.trustmail.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, session: SessionViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = Theme.maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState())) {
                ToggleItem(
                    title = "Allow JavaScript in emails",
                    description = "Lets scripts inside an email run. Scripts can change what you see after Trustmail has scanned the links, so leave this off unless an email needs it. Applies the next time you open an email.",
                    checked = settings.allowEmailJavaScript,
                    onChange = settings::updateAllowEmailJavaScript,
                    tag = "javascript-toggle",
                )
                ToggleItem(
                    title = "Ignore footer links",
                    description = "Suspicious links in an email's footer (unsubscribe, privacy policy, social media) don't count toward the email's verdict. They still get a badge and a warning when you tap them, and an unsafe footer link still counts. Applies the next time you open an email.",
                    checked = settings.ignoreFooterLinks,
                    onChange = settings::updateIgnoreFooterLinks,
                    tag = "footer-links-toggle",
                )

                session.user?.email?.let { email ->
                    HorizontalDivider(Modifier.padding(vertical = Spacing.two))
                    SectionHeader("Account")
                    ListItem(
                        headlineContent = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text(email) },
                        modifier = Modifier
                            .clickable {
                                session.signOut()
                                onBack()
                            }
                            .testTag("settings-sign-out"),
                    )
                }
            }
        }
    }
}

/** A setting with its explanation underneath and a switch on the right; tapping anywhere on it flips the switch. */
@Composable
private fun ToggleItem(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.three, vertical = Spacing.two),
    )
}
