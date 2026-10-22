package com.ospfranco.trustmail.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.ospfranco.trustmail.R
import com.ospfranco.trustmail.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, session: SessionViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = Theme.maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState())) {
                ToggleItem(
                    title = stringResource(R.string.settings_allow_javascript),
                    description = stringResource(R.string.settings_allow_javascript_description),
                    checked = settings.allowEmailJavaScript,
                    onChange = settings::updateAllowEmailJavaScript,
                    tag = "javascript-toggle",
                )
                ToggleItem(
                    title = stringResource(R.string.settings_ignore_footer_links),
                    description = stringResource(R.string.settings_ignore_footer_links_description),
                    checked = settings.ignoreFooterLinks,
                    onChange = settings::updateIgnoreFooterLinks,
                    tag = "footer-links-toggle",
                )

                session.user?.email?.let { email ->
                    HorizontalDivider(Modifier.padding(vertical = Spacing.two))
                    SectionHeader(stringResource(R.string.settings_account))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text(email) },
                        modifier = Modifier
                            .clickable {
                                session.signOut()
                                onBack()
                            }
                            .testTag("settings-sign-out"),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = Spacing.two))
                SectionHeader(stringResource(R.string.settings_about))
                LinkItem(stringResource(R.string.settings_privacy_policy), tag = "privacy-policy") {
                    openWebPage(context, AppSettings.PRIVACY_POLICY_URL)
                }
                LinkItem(stringResource(R.string.settings_terms_of_use), tag = "terms-of-use") {
                    openWebPage(context, AppSettings.TERMS_OF_USE_URL)
                }
            }
        }
    }
}

/** Opens one of our web pages in a Custom Tab (the browser, if no browser supports them). Without any browser nothing happens. */
private fun openWebPage(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    } catch (e: ActivityNotFoundException) {
        // No browser installed: nothing we can show.
    }
}

/** A row that opens a web page, in the link color. */
@Composable
private fun LinkItem(title: String, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
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
