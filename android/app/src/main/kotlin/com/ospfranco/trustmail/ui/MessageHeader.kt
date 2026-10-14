package com.ospfranco.trustmail.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ospfranco.trustmail.R
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.senderName
import com.ospfranco.trustmail.security.SecurityCheck
import com.ospfranco.trustmail.security.SecurityEvaluation
import com.ospfranco.trustmail.security.Verdict

/** When the email's header gives way to a compact one: once the body is scrolled down, back at the top. */
object HeaderCollapse {
    /** How far the body must be scrolled before the header shrinks (dp). */
    const val COLLAPSE_OFFSET = 48f

    /** Near the top again, the full header returns (dp). The gap to [COLLAPSE_OFFSET] keeps it from flickering. */
    const val EXPAND_OFFSET = 8f

    /**
     * The body must scroll at least this far for the header to shrink (dp), and more than the full header is tall.
     * Shrinking frees up to that much height, so a shorter email could be pushed back to the top by it and bounce
     * between the two headers.
     */
    const val MINIMUM_SCROLLABLE = 240f

    fun collapsed(current: Boolean, offset: Float, scrollable: Float, headerHeight: Float = 0f): Boolean =
        if (current) offset > EXPAND_OFFSET else offset > COLLAPSE_OFFSET && scrollable > maxOf(MINIMUM_SCROLLABLE, headerHeight)
}

/**
 * The top of an email: subject, sender, date and what the scan found, or, once the body is scrolled, one compact row.
 * Problems are shown as bright cards that open to explain them in plain words. The compact row keeps the sender's failed-check
 * marker and the verdict's color, so a warning never scrolls out of view.
 */
@Composable
fun MessageHeader(message: MessageDetail, evaluation: SecurityEvaluation?, collapsed: Boolean, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    val senderFailed = evaluation?.senderFailed == true
    Column(modifier.fillMaxWidth().animateContentSize()) {
        if (collapsed) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.three, vertical = Spacing.two).testTag("message-header-compact"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.two),
            ) {
                Avatar(message.from, 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(displaySubject(message.subject), color = colors.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (senderFailed) {
                        FailedSenderLine(message.fromAddress)
                    } else {
                        Text(senderName(message.from), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                if (evaluation != null && evaluation.verdict != Verdict.Safe) {
                    val summary = stringResource(evaluation.summary)
                    VerdictDot(evaluation.verdict, 22.dp, Modifier.semantics { contentDescription = summary })
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(Spacing.three).testTag("message-header"),
                verticalArrangement = Arrangement.spacedBy(Spacing.three),
            ) {
                Text(displaySubject(message.subject), color = colors.text, style = MaterialTheme.typography.headlineSmall)
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
                Findings(evaluation)
            }
        }
    }
}

/** While the email is checked, a quiet row; then a short green line, or one bright card per problem. */
@Composable
private fun Findings(evaluation: SecurityEvaluation?) {
    val colors = Theme.colors
    when {
        evaluation == null -> Row(
            modifier = Modifier.testTag("security-scanning"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.two),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            AppText(stringResource(R.string.checking_email), TextKind.Small, color = colors.textSecondary)
        }
        evaluation.findings.isEmpty() -> Row(
            modifier = Modifier.testTag("security-safe"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.one),
        ) {
            VerdictDot(Verdict.Safe, 14.dp)
            AppText(stringResource(evaluation.summary), TextKind.Small, color = Verdict.Safe.color)
        }
        else -> Column(Modifier.testTag("security-findings"), verticalArrangement = Arrangement.spacedBy(Spacing.two)) {
            for (check in evaluation.findings) FindingCard(check)
        }
    }
}

/**
 * One problem the scan found, on its verdict's color so it can't be missed. Only its title shows at first, so a few
 * of them still leave room for the email; tapping it opens the plain-words explanation.
 */
@Composable
private fun FindingCard(check: SecurityCheck) {
    val content = check.verdict.contentColor
    var expanded by rememberSaveable(check.label) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.three))
            .background(check.verdict.color)
            .clickable(onClickLabel = stringResource(if (expanded) R.string.hide_explanation else R.string.explain_warning)) { expanded = !expanded }
            .padding(horizontal = Spacing.three, vertical = Spacing.two)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.one),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.two)) {
            Text(check.verdict.glyph, color = content, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.clearAndSetSemantics {})
            Text(stringResource(check.label), color = content, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = content,
            )
        }
        if (expanded) Text(stringResource(check.detail), color = content, style = MaterialTheme.typography.bodySmall)
    }
}
