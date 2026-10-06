package com.ospfranco.trustmail.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.ospfranco.trustmail.mail.MailLabel
import com.ospfranco.trustmail.mail.senderName
import com.ospfranco.trustmail.security.ScannedLink
import com.ospfranco.trustmail.security.SecurityScanner
import com.ospfranco.trustmail.security.Verdict
import com.ospfranco.trustmail.settings.AppSettings

private fun openLink(context: Context, url: String, onFailure: () -> Unit) {
    try {
        if (Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (e: ActivityNotFoundException) {
        onFailure()
    } catch (e: SecurityException) {
        onFailure()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(model: MessageViewModel, settings: AppSettings, session: SessionViewModel, onBack: () -> Unit, onReply: () -> Unit) {
    val colors = Theme.colors
    val context = LocalContext.current
    var pendingLink by remember { mutableStateOf<ScannedLink?>(null) }
    var unopenableLink by remember { mutableStateOf<String?>(null) }
    var moveTargets by remember { mutableStateOf<List<MailLabel>?>(null) }
    val scope = rememberCoroutineScope()

    /** Runs an action and, when it worked, leaves the email: it is no longer in the inbox. */
    fun perform(action: MessageAction) {
        scope.launch {
            if (model.perform(action)) {
                session.removeMessage(model.id, action.notice)
                onBack()
            } else {
                session.showNotice(action.failureNotice)
            }
        }
    }

    fun chooseMoveTarget() {
        scope.launch {
            try {
                moveTargets = model.loadMoveTargets()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.showNotice("Couldn't load your labels")
            }
        }
    }

    fun open(url: String) = openLink(context, url) { unopenableLink = url }

    fun handleLinkPress(url: String) {
        val link = model.links?.firstOrNull { it.url == url }
        if (link == null || link.verdict == Verdict.Safe) open(url) else pendingLink = link
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
        )

        val message = model.message
        when {
            model.error != null -> Box(Modifier.fillMaxSize().padding(Spacing.four), contentAlignment = Alignment.Center) {
                AppText(model.error.orEmpty(), TextKind.Small)
            }
            message == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.three),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.three),
                    ) {
                        Text(message.subject, color = colors.text, style = MaterialTheme.typography.headlineSmall)
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.three),
                        ) {
                            Avatar(message.from)
                            Column {
                                Text(senderName(message.from), color = colors.text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                AppText(message.from, TextKind.Small, color = colors.textSecondary, maxLines = 1)
                                Text(message.date, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                        if (model.evaluation == null) AppText("Scanning links…", TextKind.Small, color = colors.textSecondary)
                    }
                    HorizontalDivider(color = colors.backgroundSelected)
                    EmailBodyView(
                        body = message.body,
                        bodyType = message.bodyType,
                        scannedLinks = model.links,
                        onLinksFound = { model.onLinksFound(it, settings.ignoreFooterLinks) },
                        onLinkPress = ::handleLinkPress,
                        allowJavaScript = settings.allowEmailJavaScript,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    ActionBar(
                        isBusy = model.isBusy,
                        onDelete = { perform(MessageAction.Trash) },
                        onArchive = { perform(MessageAction.Archive) },
                        onMove = ::chooseMoveTarget,
                        onReply = onReply,
                    )
                }
                val evaluation = model.evaluation
                val links = model.links
                // The tab is there from the start, with a spinner until the scan is done.
                SecurityPanel(evaluation, links.orEmpty())
            }
        }
    }

    pendingLink?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(if (link.verdict == Verdict.Danger) "This link looks unsafe" else "Be careful with this link") },
            text = {
                Text(SecurityScanner.linkWarning(link))
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    open(link.url)
                }) { Text("Open anyway", color = Verdict.Danger.color) }
            },
        )
    }

    moveTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { moveTargets = null },
            title = { Text("Move to") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (label in targets) {
                        Text(
                            label.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    moveTargets = null
                                    perform(MessageAction.Move(label))
                                }
                                .padding(vertical = Spacing.three),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveTargets = null }) { Text("Cancel") } },
        )
    }

    unopenableLink?.let { url ->
        AlertDialog(
            onDismissRequest = { unopenableLink = null },
            title = { Text("Can't open this link") },
            text = { Text(url) },
            confirmButton = { TextButton(onClick = { unopenableLink = null }) { Text("OK") } },
        )
    }
}
