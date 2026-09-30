package dev.muisc.app.ui.dj

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.playback.DjState
import dev.muisc.app.playback.LearnedInfo
import dev.muisc.app.playback.PinInfo
import dev.muisc.app.playback.PresetInfo
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.components.formatDouble
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.DjViewModel

// ---------------------------------------------------------------------------------------------------------------
// Shared scaffolding

/** Scaffold + top bar + snackbar for one DJ screen; [content] fills a LazyColumn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DjScaffold(
    title: String,
    navigator: MuiscNavigator,
    vm: DjViewModel,
    actions: @Composable () -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeMessage()
    }
    Scaffold(
        topBar = {
            LibraryTopBar(title = title, navigator = navigator, showBack = true, showSearch = false, showQueue = false, extraActions = actions)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
            content = content,
        )
    }
}

/** "Connecting…" bar while the engine (and so the customization) is not installed or the recipes are being read. */
internal fun androidx.compose.foundation.lazy.LazyListScope.loadingItem(state: DjState) {
    if (state.loading) {
        item(key = "loading") {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** A plain text line for empty lists (EmptyState fills the screen, which a LazyColumn item cannot). */
@Composable
internal fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Problems found while reading the customization files, shown at the top of the DJ screens. */
@Composable
internal fun ProblemsCard(problems: List<String>, modifier: Modifier = Modifier) {
    if (problems.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.dj_problems_title, problems.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            val shown = if (expanded) problems else problems.take(2)
            shown.forEach { p ->
                Text("• $p", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
            }
            if (!expanded && problems.size > 2) {
                Text(stringResource(R.string.dj_show_all), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Settings → DJ (the index)

private data class DjEntry(val titleRes: Int, val icon: ImageVector, val subtitle: (DjState) -> String, val open: (MuiscNavigator) -> Unit)

private val djEntries = listOf(
    DjEntry(R.string.dj_style, Icons.Rounded.Palette, { s -> s.styles.firstOrNull { it.id == s.currentStyleId }?.name ?: "None — your own settings" }, { it.toDjStyle() }),
    DjEntry(R.string.dj_recipes, Icons.Rounded.GraphicEq, { s -> "${s.recipes.count { it.inUse }} in use · ${s.recipes.size} found" + if (s.recipes.any { it.errorCount > 0 }) " · some with errors" else "" }, { it.toDjRecipes() }),
    DjEntry(R.string.dj_presets, Icons.Rounded.Tune, { s -> "${s.presets.count { !it.builtIn }} yours · ${s.presets.count { it.builtIn }} built in" }, { it.toDjPresets() }),
    DjEntry(R.string.dj_pins, Icons.Rounded.PushPin, { s -> if (s.pins.isEmpty()) "None yet — pin a pair in the Lab" else "${s.pins.size} pinned" }, { it.toDjPins() }),
    DjEntry(R.string.dj_learned, Icons.Rounded.TrendingUp, { s -> if (s.learned.isEmpty()) "Nothing learned yet" else learnedCounts(s.learned.sumOf { it.ratings }, s.learned.sumOf { it.skips }) }, { it.toDjLearned() }),
)

/** The body of Settings → DJ: one entry per customization screen, and the problems found. */
@Composable
fun DjSectionIndex(navigator: MuiscNavigator) {
    val vm: DjViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    Column {
        if (state.loading) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ProblemsCard(state.problems)
        djEntries.forEach { entry ->
            ListItem(
                headlineContent = { Text(stringResource(entry.titleRes)) },
                supportingContent = { Text(if (state.loading) stringResource(R.string.dj_connecting) else entry.subtitle(state)) },
                leadingContent = { Icon(entry.icon, contentDescription = null) },
                modifier = Modifier.clickable { entry.open(navigator) },
            )
        }
        Text(
            stringResource(R.string.dj_section_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        if (state.directory.isNotEmpty()) {
            Text(
                state.directory,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Style

@Composable
fun DjStyleScreen(navigator: MuiscNavigator) {
    val vm: DjViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    DjScaffold(stringResource(R.string.dj_style), navigator, vm) {
        loadingItem(state)
        item { ProblemsCard(state.problems) }
        item {
            Text(
                stringResource(R.string.dj_style_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "none") {
            StyleCard(
                name = stringResource(R.string.dj_style_none),
                description = stringResource(R.string.dj_style_none_body),
                settings = emptyList(),
                selected = state.currentStyleId == null,
                builtIn = true,
                onClick = { vm.chooseStyle(null) },
            )
        }
        items(state.styles, key = { it.id }) { style ->
            StyleCard(style.name, style.description, style.settings, state.currentStyleId == style.id, style.builtIn) { vm.chooseStyle(style.id) }
        }
    }
}

@Composable
private fun StyleCard(name: String, description: String, settings: List<String>, selected: Boolean, builtIn: Boolean, onClick: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Spacer(Modifier.width(8.dp))
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!builtIn) Text(stringResource(R.string.dj_yours), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (settings.isNotEmpty()) {
                TextButton(onClick = { showSettings = !showSettings }) {
                    Text(stringResource(if (showSettings) R.string.dj_hide_settings else R.string.dj_show_settings))
                }
                if (showSettings) {
                    settings.forEach { line ->
                        Text("• $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Presets

@Composable
fun DjPresetsScreen(navigator: MuiscNavigator) {
    val vm: DjViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<PresetInfo?>(null) }
    DjScaffold(stringResource(R.string.dj_presets), navigator, vm) {
        loadingItem(state)
        item { ProblemsCard(state.problems) }
        item {
            Text(
                stringResource(R.string.dj_presets_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val byStrategy = state.presets.groupBy { it.strategyId }
        byStrategy.forEach { (strategyId, presets) ->
            item(key = "h-$strategyId") { SectionHeader(title = presets.first().strategyName) }
            val anyActive = presets.any { it.active }
            // The chosen style's preset replaces the user's choice for this technique while the style is on.
            val byStyle = presets.any { it.activeByStyle }
            if (byStyle) {
                item(key = "style-$strategyId") {
                    Text(
                        stringResource(R.string.dj_preset_by_style),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item(key = "default-$strategyId") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dj_preset_defaults)) },
                    leadingContent = { RadioButton(selected = !anyActive, onClick = { vm.setActivePreset(strategyId, null) }, enabled = !byStyle) },
                    modifier = Modifier.clickable(enabled = !byStyle) { vm.setActivePreset(strategyId, null) },
                )
            }
            items(presets, key = { "p-${it.id}" }) { preset ->
                PresetRow(preset, selectable = !byStyle, onSelect = { vm.setActivePreset(strategyId, preset.id) }, onDelete = { deleting = preset })
            }
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.dj_delete_preset_title),
            body = stringResource(R.string.dj_delete_preset_body, p.name),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { vm.deletePreset(p.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PresetRow(preset: PresetInfo, selectable: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(preset.name) },
        supportingContent = {
            Column {
                val values = preset.params.values.entries.sortedBy { it.key }.joinToString(" · ") { (k, v) -> "$k $v" }
                if (values.isNotEmpty()) Text(values, style = MaterialTheme.typography.bodySmall)
                if (preset.note.isNotBlank()) Text(preset.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val badges = buildList {
                    add(if (preset.builtIn) "Built in" else "Yours")
                    if (preset.activeByStyle) add("Chosen by your style")
                }
                Text(badges.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        },
        leadingContent = { RadioButton(selected = preset.active, onClick = onSelect, enabled = selectable) },
        trailingContent = if (!preset.builtIn) {
            { IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete)) } }
        } else null,
        modifier = Modifier.clickable(enabled = selectable, onClick = onSelect),
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Pinned pairs

@Composable
fun DjPinsScreen(navigator: MuiscNavigator) {
    val vm: DjViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<PinInfo?>(null) }
    DjScaffold(stringResource(R.string.dj_pins), navigator, vm) {
        loadingItem(state)
        item { ProblemsCard(state.problems) }
        item {
            Text(
                stringResource(R.string.dj_pins_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (!state.loading && state.pins.isEmpty()) item { EmptyNote(stringResource(R.string.dj_pins_empty)) }
        items(state.pins, key = { it.aIdentity + ">" + it.bIdentity }) { pin ->
            ListItem(
                headlineContent = { Text(pinTitle(pin)) },
                supportingContent = {
                    Column {
                        Text(pin.strategyName + (pin.presetId?.let { " · preset $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        if (pin.note.isNotBlank()) Text(pin.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                leadingContent = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { removing = pin }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.dj_unpin)) }
                },
            )
        }
    }
    removing?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.dj_unpin),
            body = stringResource(R.string.dj_unpin_body, pinTitle(p)),
            confirm = stringResource(R.string.dj_unpin),
            onConfirm = { vm.removePin(p.aIdentity, p.bIdentity) },
            onDismiss = { removing = null },
        )
    }
}

private fun pinTitle(pin: PinInfo): String {
    val a = pin.aLabel.ifBlank { pin.aIdentity.take(10) + "…" }
    val b = pin.bLabel.ifBlank { pin.bIdentity.take(10) + "…" }
    return "$a → $b"
}

// ---------------------------------------------------------------------------------------------------------------
// Learned preferences

@Composable
fun DjLearnedScreen(navigator: MuiscNavigator) {
    val vm: DjViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    var resetting by remember { mutableStateOf<Pair<String?, String>?>(null) }
    DjScaffold(
        stringResource(R.string.dj_learned),
        navigator,
        vm,
        actions = {
            if (state.learned.isNotEmpty()) {
                TextButton(onClick = { resetting = null to "every technique" }) { Text(stringResource(R.string.dj_forget_all)) }
            }
        },
    ) {
        loadingItem(state)
        item { ProblemsCard(state.problems) }
        item {
            Text(
                stringResource(R.string.dj_learned_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (!state.loading && state.learned.isEmpty()) item { EmptyNote(stringResource(R.string.dj_learned_empty)) }
        state.learned.groupBy { it.strategyId }.forEach { (strategyId, rows) ->
            item(key = "h-$strategyId") {
                SectionHeader(
                    title = rows.first().strategyName,
                    actionLabel = stringResource(R.string.dj_forget),
                    onAction = { resetting = strategyId to rows.first().strategyName },
                )
            }
            items(rows, key = { "$strategyId-${it.bucketKey}" }) { row -> LearnedRow(row) }
        }
    }
    resetting?.let { (id, name) ->
        ConfirmDialog(
            title = stringResource(R.string.dj_forget_title),
            body = stringResource(R.string.dj_forget_body, name),
            confirm = stringResource(R.string.dj_forget),
            onConfirm = { vm.resetLearned(id) },
            onDismiss = { resetting = null },
        )
    }
}

/** `3 ratings`, `3 ratings · 2 skips`, `1 skip` (explicit ratings and skipped transitions, counted apart). */
private fun learnedCounts(ratings: Int, skips: Int): String {
    val r = if (ratings == 1) "1 rating" else "$ratings ratings"
    val k = if (skips == 1) "1 skip" else "$skips skips"
    return when {
        skips == 0 -> r
        ratings == 0 -> k
        else -> "$r · $k"
    }
}

@Composable
private fun LearnedRow(row: LearnedInfo) {
    val up = row.multiplier >= 1.0
    ListItem(
        headlineContent = { Text(row.bucketLabel.replaceFirstChar { it.uppercase() }) },
        supportingContent = { Text(learnedCounts(row.ratings, row.skips), style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Text(
                "×" + formatDouble(row.multiplier, 2),
                style = MaterialTheme.typography.titleSmall,
                color = if (up) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
    )
}
