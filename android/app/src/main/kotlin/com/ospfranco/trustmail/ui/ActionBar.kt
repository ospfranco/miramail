package com.ospfranco.trustmail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Material icon outlines, built from path data so no icon library is needed. */
private fun icon(name: String, path: String, evenOdd: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(path),
            fill = SolidColor(Color.Black),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        )
        .build()

private val DeleteIcon = icon(
    "delete",
    "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z",
)
private val ArchiveIcon = icon(
    "archive",
    "M20.54,5.23l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.16,0.55L3.46,5.23C3.17,5.57 3,6.02 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.48 -0.17,-0.93 -0.46,-1.27zM12,17.5L6.5,12H10v-2h4v2h3.5L12,17.5zM5.12,5l0.81,-1h12l0.94,1H5.12z",
)
private val MoveIcon = icon(
    "move",
    // A folder with an arrow cut out of it.
    "M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM8,12H12V9.5L16,13L12,16.5V14H8Z",
    evenOdd = true,
)
private val ReplyIcon = icon("reply", "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z")

/** The floating bar at the bottom of an email: delete, archive, move and reply. */
@Composable
fun ActionBar(
    isBusy: Boolean,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onMove: () -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(horizontal = Spacing.three)
            .padding(bottom = Spacing.two)
            .fillMaxWidth()
            .alpha(if (isBusy) 0.5f else 1f),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = Spacing.two, vertical = Spacing.one), horizontalArrangement = Arrangement.SpaceEvenly) {
            Item("Delete", DeleteIcon, "action-delete", isBusy, onDelete)
            Item("Archive", ArchiveIcon, "action-archive", isBusy, onArchive)
            Item("Move", MoveIcon, "action-move", isBusy, onMove)
            Item("Reply", ReplyIcon, "action-reply", isBusy, onReply)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Item(
    title: String,
    icon: ImageVector,
    tag: String,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(CircleShape)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(vertical = Spacing.two)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

/** A short message that floats over the current screen ("Archived", "Message sent", ...), styled like a snackbar. */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.three, vertical = 14.dp).testTag("notice"))
    }
}
