package dev.muisc.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.muisc.app.R
import dev.muisc.app.playback.EdgeState
import dev.muisc.app.ui.theme.EdgeFailed
import dev.muisc.app.ui.theme.EdgeGated
import dev.muisc.app.ui.theme.EdgeLive
import dev.muisc.app.ui.theme.EdgePlanned
import dev.muisc.app.ui.theme.EdgeReady
import dev.muisc.app.ui.theme.EdgeRendering

/** User-facing label for an edge state. */
@Composable
fun edgeLabel(edge: EdgeState): String = when (edge) {
    is EdgeState.Gated -> stringResource(R.string.edge_gated, edge.reason)
    EdgeState.Analysing -> stringResource(R.string.edge_analysing)
    is EdgeState.Planned -> stringResource(R.string.edge_planned, edge.displayName, formatScore(edge.score))
    is EdgeState.Rendering -> stringResource(R.string.edge_rendering, edge.strategyId)
    is EdgeState.Ready -> stringResource(R.string.edge_ready, edge.displayName)
    is EdgeState.Live -> stringResource(R.string.edge_live, edge.kind, edge.reason)
    is EdgeState.Failed -> stringResource(R.string.edge_failed, edge.reason)
    EdgeState.Unknown -> stringResource(R.string.edge_unknown)
}

fun edgeColor(edge: EdgeState): Color = when (edge) {
    is EdgeState.Gated -> EdgeGated
    EdgeState.Analysing -> EdgeRendering
    is EdgeState.Planned -> EdgePlanned
    is EdgeState.Rendering -> EdgeRendering
    is EdgeState.Ready -> EdgeReady
    is EdgeState.Live -> EdgeLive
    is EdgeState.Failed -> EdgeFailed
    EdgeState.Unknown -> EdgeGated
}

fun edgeIcon(edge: EdgeState): ImageVector = when (edge) {
    is EdgeState.Gated -> Icons.Rounded.Block
    EdgeState.Analysing -> Icons.Rounded.HourglassTop
    is EdgeState.Planned -> Icons.Rounded.Schedule
    is EdgeState.Rendering -> Icons.Rounded.HourglassTop
    is EdgeState.Ready -> Icons.Rounded.CheckCircle
    is EdgeState.Live -> Icons.Rounded.Bolt
    is EdgeState.Failed -> Icons.Rounded.ErrorOutline
    EdgeState.Unknown -> Icons.Rounded.HelpOutline
}

/** Compact chip describing the transition planned for one queue edge. */
@Composable
fun EdgeBadge(
    edge: EdgeState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val color = edgeColor(edge)
    AssistChip(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier,
        label = {
            Text(edgeLabel(edge), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        },
        leadingIcon = {
            if (edge is EdgeState.Rendering) {
                CircularProgressIndicator(
                    progress = { edge.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = color,
                )
            } else {
                Icon(edgeIcon(edge), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
            disabledLeadingIconContentColor = color,
        ),
    )
}
