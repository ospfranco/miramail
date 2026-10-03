package com.ospfranco.safemail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun InboxScreen(session: SessionViewModel, onOpenMessage: (String) -> Unit, onOpenSettings: () -> Unit) {
    val colors = Theme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = Theme.maxContentWidth)) {
            TopBar(session, onOpenSettings)
            if (session.user == null) SignedOut(session) else SignedIn(session, onOpenMessage)
        }
    }
}

/** The account on the left (when signed in); sign out and the settings button on the right. */
@Composable
private fun TopBar(session: SessionViewModel, onOpenSettings: () -> Unit) {
    val colors = Theme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.three, vertical = Spacing.two),
        horizontalArrangement = Arrangement.spacedBy(Spacing.two),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val email = session.user?.email
        if (email != null) {
            AppText(email, TextKind.SmallBold, modifier = Modifier.weight(1f), maxLines = 1)
            AppText(
                "Sign out",
                TextKind.Link,
                color = colors.linkPrimary,
                modifier = Modifier.clickable { session.signOut() }.padding(Spacing.one).testTag("sign-out"),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings-button")) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = colors.text)
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
        AppText("safemail", TextKind.Subtitle)
        if (session.isLoading) {
            CircularProgressIndicator()
        } else {
            AppText(
                "Sign in with Google",
                TextKind.Regular,
                modifier = Modifier
                    .background(colors.backgroundElement, RoundedCornerShape(Spacing.three))
                    .clickable { session.signIn() }
                    .padding(horizontal = Spacing.four, vertical = Spacing.three)
                    .testTag("sign-in"),
            )
        }
        session.error?.let { AppText(it, TextKind.Small) }
    }
}

@Composable
private fun SignedIn(session: SessionViewModel, onOpenMessage: (String) -> Unit) {
    val colors = Theme.colors
    session.error?.let { AppText(it, TextKind.Small, modifier = Modifier.padding(horizontal = Spacing.three)) }

    PullToRefreshBox(isRefreshing = session.isLoading, onRefresh = session::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(session.messages, key = { it.id }) { message ->
                val kind = if (message.unread) TextKind.SmallBold else TextKind.Small
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMessage(message.id) }
                        .padding(horizontal = Spacing.three, vertical = Spacing.three)
                        .testTag("message-${message.id}"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.half),
                ) {
                    AppText(message.from, kind, maxLines = 1)
                    AppText(message.subject, kind, maxLines = 1)
                    AppText(message.snippet, TextKind.Small, color = colors.textSecondary, maxLines = 2)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.three), color = colors.backgroundSelected, thickness = 1.dp)
            }
        }
    }
}
