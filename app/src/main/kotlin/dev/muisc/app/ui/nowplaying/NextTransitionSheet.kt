package dev.muisc.app.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muisc.app.R
import dev.muisc.app.playback.TechniqueOption
import dev.muisc.app.playback.UpcomingChoice
import dev.muisc.app.ui.components.formatScore
import dev.muisc.app.ui.viewmodel.DjViewModel

/**
 * "Next transition" sheet: every technique the planner can use from the current song into the next one, with its
 * reasons, best first. Picking one makes it the transition for this pair only (a one-off override, cleared once the
 * pair has played); "Let Muisc choose" goes back to the planner's pick. The Lab stays one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextTransitionSheet(vm: DjViewModel, onDismiss: () -> Unit, onOpenLab: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadUpcoming() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.next_transition),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            val choice = upcoming.choice
            when {
                upcoming.loading -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.dj_next_planning), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                upcoming.error != null -> Text(
                    upcoming.error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                choice != null -> ChoiceBody(choice, upcoming.applying, onPick = vm::chooseUpcoming)
            }
            OutlinedButton(
                onClick = { onDismiss(); onOpenLab() },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.open_in_lab))
            }
        }
    }
}

@Composable
private fun ChoiceBody(choice: UpcomingChoice, applying: String?, onPick: (String?) -> Unit) {
    val locked = choice.inTransition || choice.gatedReason != null
    val overridden = choice.options.any { it.chosen }
    Text(
        "${choice.a.title} → ${choice.b.title}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
    when {
        choice.inTransition -> Note(stringResource(R.string.dj_next_in_transition))
        choice.gatedReason != null -> Note(stringResource(R.string.dj_next_gated, choice.gatedReason))
        else -> Note(stringResource(R.string.dj_next_body))
    }
    if (applying != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    var showUnavailable by remember { mutableStateOf(false) }
    LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item(key = "auto") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !locked && overridden) { onPick(null) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = !overridden, onClick = if (!locked && overridden) ({ onPick(null) }) else null, enabled = !locked)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.dj_next_auto), style = MaterialTheme.typography.titleSmall)
                    val planned = choice.options.firstOrNull { it.planned }?.displayName
                    Text(
                        if (planned != null) stringResource(R.string.dj_next_auto_planned, planned) else stringResource(R.string.dj_next_auto_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(choice.options, key = { it.strategyId }) { option ->
            OptionRow(option, enabled = !locked && applying == null, onPick = { onPick(option.strategyId) })
        }
        if (choice.unavailable.isNotEmpty()) {
            item(key = "unavailable") {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                TextButton(onClick = { showUnavailable = !showUnavailable }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(if (showUnavailable) R.string.dj_next_hide_unavailable else R.string.dj_next_show_unavailable, choice.unavailable.size))
                }
            }
            if (showUnavailable) {
                items(choice.unavailable, key = { "u-" + it.strategyId }) { u ->
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                        Text(u.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(u.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (choice.notes.isNotEmpty()) {
            item(key = "notes") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    choice.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Composable
private fun OptionRow(option: TechniqueOption, enabled: Boolean, onPick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onPick() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = option.chosen, onClick = if (enabled) onPick else null, enabled = enabled)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(option.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(formatScore(option.score), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            val tags = buildList {
                if (option.isRecipe) add("Recipe")
                if (option.planned) add("Planned now")
                if (option.chosen) add("Your pick")
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            val reasons = if (expanded) option.reasons else option.reasons.take(2)
            reasons.forEach { r -> Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (option.reasons.size > 2) {
                Text(
                    stringResource(if (expanded) R.string.dj_less else R.string.dj_why),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}
