package dev.muisc.app.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.muisc.app.R
import dev.muisc.app.playback.PresetInfo
import dev.muisc.app.ui.viewmodel.AbTest

/** "Presets" menu for the selected technique: load one of its presets, or save the edited values as a new one. */
@Composable
fun PresetMenu(presets: List<PresetInfo>, onLoad: (PresetInfo) -> Unit, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.lab_presets))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (presets.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.lab_presets_none)) }, onClick = { open = false }, enabled = false)
            }
            presets.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.name)
                            val detail = listOfNotNull(if (p.builtIn) "Built in" else "Yours", if (p.active) "In use" else null)
                            Text(detail.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; onLoad(p) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.lab_save_preset)) }, onClick = { open = false; naming = true })
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.lab_save_preset)) },
            text = {
                Column {
                    Text(stringResource(R.string.lab_save_preset_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.lab_preset_name)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(name.trim()); naming = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * The blind A/B card: render progress, "Play X" / "Play Y" (the names stay hidden), and the vote, which is only
 * offered once both have been heard. After the vote it shows what X and Y were and what was recorded.
 */
@Composable
fun AbTestCard(test: AbTest, onPlay: (Char) -> Unit, onStop: () -> Unit, onVote: (Char?) -> Unit, onClose: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.lab_ab_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.lab_ab_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (test.rendering) {
                Text(
                    stringResource(R.string.lab_ab_rendering, test.progress?.stage ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val fraction = test.progress?.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            test.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf('X' to test.renderX, 'Y' to test.renderY).forEach { (slot, render) ->
                    val playing = test.playing == slot
                    FilledTonalButton(onClick = { if (playing) onStop() else onPlay(slot) }, enabled = render != null) {
                        Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(if (playing) R.string.lab_ab_stop else R.string.lab_ab_play, slot.toString()))
                    }
                }
            }
            if (test.playing != null) {
                LinearProgressIndicator(progress = { test.position }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            val reveal = test.reveal
            if (reveal == null) {
                if (test.ready && !(test.heardX && test.heardY)) {
                    Text(stringResource(R.string.lab_ab_listen_both), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onVote('X') }, enabled = test.canVote) { Text(stringResource(R.string.lab_ab_better, "X")) }
                    Button(onClick = { onVote('Y') }, enabled = test.canVote) { Text(stringResource(R.string.lab_ab_better, "Y")) }
                }
                TextButton(onClick = { onVote(null) }, enabled = test.canVote) { Text(stringResource(R.string.lab_ab_no_preference)) }
            } else {
                Text(reveal, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_close)) }
        }
    }
}
