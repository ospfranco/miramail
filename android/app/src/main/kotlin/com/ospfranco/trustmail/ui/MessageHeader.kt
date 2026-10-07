package com.ospfranco.trustmail.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.senderName
import com.ospfranco.trustmail.security.SecurityEvaluation

/** When the email's header gives way to a compact one: once the body is scrolled down, back at the top. */
object HeaderCollapse {
    /** How far the body must be scrolled before the header shrinks (dp). */
    const val COLLAPSE_OFFSET = 48f

    /** Near the top again, the full header returns (dp). The gap to [COLLAPSE_OFFSET] keeps it from flickering. */
    const val EXPAND_OFFSET = 8f

    /**
     * The body must scroll at least this far for the header to shrink (dp). Shrinking frees up to about this much
     * height, so a shorter email could be pushed back to the top by it and bounce between the two headers.
     */
    const val MINIMUM_SCROLLABLE = 240f

    fun collapsed(current: Boolean, offset: Float, scrollable: Float): Boolean =
        if (current) offset > EXPAND_OFFSET else offset > COLLAPSE_OFFSET && scrollable > MINIMUM_SCROLLABLE
}

/**
 * The top of an email: subject, sender and date, or, once the body is scrolled, one compact row. Both keep the
 * sender's failed-check marker, so the warning never scrolls out of view.
 */
@Composable
fun MessageHeader(message: MessageDetail, evaluation: SecurityEvaluation?, collapsed: Boolean) {
    val colors = Theme.colors
    val senderFailed = evaluation?.senderFailed == true
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (collapsed) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.three, vertical = Spacing.two).testTag("message-header-compact"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.two),
            ) {
                Avatar(message.from, 28.dp)
                Column {
                    Text(message.subject, color = colors.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (senderFailed) {
                        FailedSenderLine(message.fromAddress)
                    } else {
                        Text(senderName(message.from), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(Spacing.three).testTag("message-header"),
                verticalArrangement = Arrangement.spacedBy(Spacing.three),
            ) {
                Text(message.subject, color = colors.text, style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.three)) {
                    Avatar(message.from)
                    Column {
                        Text(senderName(message.from), color = colors.text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        if (senderFailed) {
                            FailedSenderLine(message.fromAddress)
                        } else {
                            AppText(message.from, TextKind.Small, color = colors.textSecondary, maxLines = 1)
                        }
                        Text(message.date, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                if (evaluation == null) AppText("Scanning links…", TextKind.Small, color = colors.textSecondary)
            }
        }
    }
}
