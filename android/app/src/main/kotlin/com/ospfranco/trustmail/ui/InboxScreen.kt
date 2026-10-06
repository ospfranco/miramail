package com.ospfranco.trustmail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.mail.bareAddress
import com.ospfranco.trustmail.mail.senderName
import com.ospfranco.trustmail.security.senderFailed

/** Material's "cloud off" icon, for the note shown when the inbox is the saved copy. */
private val CloudOffIcon = icon(
    "cloud_off",
    "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19" +
        "c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96z" +
        "M3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27z" +
        "M7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(session: SessionViewModel, onOpenMessage: (String) -> Unit, onOpenSettings: () -> Unit, onNewMessage: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val email = session.user?.email
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // The account under the title (when signed in) and the settings button.
            TopAppBar(
                title = {
                    if (email != null) {
                        Column {
                            Text("Inbox")
                            Text(email, style = MaterialTheme.typography.bodySmall, color = Theme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings-button")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // Like Gmail: an extended button for a new email (when signed in).
            if (email != null) {
                ExtendedFloatingActionButton(
                    onClick = onNewMessage,
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    text = { Text("New message") },
                    modifier = Modifier.testTag("new-message"),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxSize().widthIn(max = Theme.maxContentWidth)) {
                if (session.user == null) SignedOut(session) else SignedIn(session, onOpenMessage)
            }
        }
    }
}

@Composable
private fun SignedOut(session: SessionViewModel) {
    val colors = Theme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.three),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.four, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
        AppText("Trustmail", TextKind.Subtitle)
        if (session.isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { session.signIn() },
                contentPadding = PaddingValues(horizontal = Spacing.five, vertical = Spacing.three),
                modifier = Modifier.testTag("sign-in"),
            ) { Text("Sign in with Google") }
        }
        session.error?.let { AppText(it, TextKind.Small, color = colors.textSecondary) }
    }
}

@Composable
private fun SignedIn(session: SessionViewModel, onOpenMessage: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // With the saved copy on screen, the note says what happened; the raw error would only repeat it.
        if (session.showsSavedInbox) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.three, vertical = Spacing.two).testTag("saved-inbox"),
                horizontalArrangement = Arrangement.spacedBy(Spacing.two),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CloudOffIcon, contentDescription = null, tint = Theme.colors.textSecondary, modifier = Modifier.size(18.dp))
                AppText("Couldn't refresh your inbox. Showing the copy saved on this device.", TextKind.Small, color = Theme.colors.textSecondary)
            }
        } else {
            session.error?.let { AppText(it, TextKind.Small, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Spacing.three)) }
        }

        PullToRefreshBox(isRefreshing = session.isLoading, onRefresh = session::refresh, modifier = Modifier.fillMaxSize()) {
            // Room at the bottom so the last email can scroll clear of the new message button.
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(session.messages, key = { it.id }) { message ->
                    MessageRow(message, isFirstMessage = message.id in session.firstMessages, onClick = { onOpenMessage(message.id) })
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = Theme.colors.backgroundSelected)
                }
            }
        }
    }
}

/**
 * One email in the inbox, laid out like Gmail: avatar, sender, subject and a two-line preview; unread ones in bold.
 * Under the sender: the domain in red when the sender failed Gmail's checks, and a yellow marker when they are new to you
 * ([isFirstMessage]).
 */
@Composable
private fun MessageRow(message: MessageSummary, isFirstMessage: Boolean, onClick: () -> Unit) {
    val weight = if (message.unread) FontWeight.Bold else FontWeight.Normal
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("message-${message.id}"),
        leadingContent = { Avatar(message.from) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.two)) {
                Text(senderName(message.from), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (message.unread) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        },
        supportingContent = {
            Column {
                if (message.senderFailed) FailedSenderLine(bareAddress(message.from))
                if (isFirstMessage) FirstMessageLine()
                Text(message.subject, fontWeight = weight, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(message.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}
