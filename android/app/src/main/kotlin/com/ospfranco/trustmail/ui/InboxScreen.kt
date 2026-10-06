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
import com.ospfranco.trustmail.mail.senderName

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
        session.error?.let { AppText(it, TextKind.Small, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Spacing.three)) }

        PullToRefreshBox(isRefreshing = session.isLoading, onRefresh = session::refresh, modifier = Modifier.fillMaxSize()) {
            // Room at the bottom so the last email can scroll clear of the new message button.
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(session.messages, key = { it.id }) { message ->
                    MessageRow(message, onClick = { onOpenMessage(message.id) })
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = Theme.colors.backgroundSelected)
                }
            }
        }
    }
}

/** One email in the inbox, laid out like Gmail: avatar, sender, subject and a two-line preview; unread ones in bold. */
@Composable
private fun MessageRow(message: MessageSummary, onClick: () -> Unit) {
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
                Text(message.subject, fontWeight = weight, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(message.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}
