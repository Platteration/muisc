package dev.muisc.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.muisc.app.data.prefs.NowPlayingLayout
import dev.muisc.app.data.prefs.ThemeMode
import dev.muisc.app.data.prefs.UiPrefs
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.components.formatDouble
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.SettingsViewModel
import dev.muisc.app.ui.viewmodel.strategyDisplayName
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.roundToInt

object SettingsSections {
    const val LOOK = "look"
    const val AUDIO = "audio"
    const val TRANSITIONS = "transitions"
    const val LIBRARY = "library"
    const val ABOUT = "about"
}

private data class SectionEntry(val id: String, val labelRes: Int, val icon: ImageVector)

private val sections = listOf(
    SectionEntry(SettingsSections.LOOK, R.string.settings_look, Icons.Rounded.Palette),
    SectionEntry(SettingsSections.AUDIO, R.string.settings_audio, Icons.Rounded.Tune),
    SectionEntry(SettingsSections.TRANSITIONS, R.string.settings_transitions, Icons.Rounded.SwapHoriz),
    SectionEntry(SettingsSections.LIBRARY, R.string.settings_library, Icons.Rounded.LibraryMusic),
    SectionEntry(SettingsSections.ABOUT, R.string.settings_about, Icons.Rounded.Info),
)

/** `section == null` shows the index; otherwise one section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(section: String?, navigator: MuiscNavigator) {
    val vm: SettingsViewModel = viewModel(factory = AppViewModelFactory)
    val uiPrefs by vm.uiPrefs.collectAsStateWithLifecycle()
    val transitionPrefs by vm.transitionPrefs.collectAsStateWithLifecycle()

    val title = sections.firstOrNull { it.id == section }?.let { stringResource(it.labelRes) } ?: stringResource(R.string.nav_settings)

    Scaffold(
        topBar = { LibraryTopBar(title = title, navigator = navigator, showBack = true, showSearch = false, showQueue = false) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            when (section) {
                null -> items(sections, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(stringResource(entry.labelRes)) },
                        leadingContent = { Icon(entry.icon, contentDescription = null) },
                        modifier = Modifier.clickable { navigator.toSettingsSection(entry.id) },
                    )
                }
                SettingsSections.LOOK -> item { LookSection(uiPrefs, vm) }
                SettingsSections.AUDIO -> item { AudioSection(transitionPrefs, vm) }
                SettingsSections.TRANSITIONS -> item { TransitionsSection(transitionPrefs, uiPrefs, vm) }
                SettingsSections.LIBRARY -> item { LibrarySection(uiPrefs, vm) }
                SettingsSections.ABOUT -> item { AboutSection() }
                else -> item { AboutSection() }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Building blocks

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChecked(!checked) },
    )
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
    valueText: (Float) -> String,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local.coerceIn(range.start, range.endInclusive),
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Sections

@Composable
private fun LookSection(prefs: UiPrefs?, vm: SettingsViewModel) {
    if (prefs == null) return
    Column {
        ChoiceRow(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = prefs.theme,
            label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = { mode -> vm.updateUi { it.copy(theme = mode) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            checked = prefs.dynamicColor,
            onChecked = { on -> vm.updateUi { it.copy(dynamicColor = on) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_now_playing_layout),
            options = NowPlayingLayout.entries,
            selected = prefs.nowPlayingLayout,
            label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = { layout -> vm.updateUi { it.copy(nowPlayingLayout = layout) } },
        )
    }
}

@Composable
private fun AudioSection(prefs: TransitionPrefs, vm: SettingsViewModel) {
    Column {
        SliderRow(
            title = stringResource(R.string.settings_target_lufs),
            value = prefs.targetLufs.toFloat(),
            range = -23f..-6f,
            steps = 16,
            onCommit = { v -> vm.updateTransition { it.copy(targetLufs = v.roundToInt().toDouble()) } },
            valueText = { "${it.roundToInt()} LUFS" },
        )
        SwitchRow(
            title = stringResource(R.string.settings_key_lock),
            checked = prefs.keyLock,
            onChecked = { on -> vm.updateTransition { it.copy(keyLock = on) } },
        )
    }
}

@Composable
private fun TransitionsSection(prefs: TransitionPrefs, uiPrefs: UiPrefs?, vm: SettingsViewModel) {
    Column {
        SwitchRow(
            title = stringResource(R.string.settings_transitions_enabled),
            checked = prefs.enabled,
            onChecked = { on -> vm.updateTransition { it.copy(enabled = on) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_allow_in_albums),
            checked = prefs.allowInAlbums,
            onChecked = { on -> vm.updateTransition { it.copy(allowInAlbums = on) } },
            enabled = prefs.enabled,
        )
        SwitchRow(
            title = stringResource(R.string.settings_keep_album_flow),
            subtitle = "Shuffle and playlists keep consecutive tracks of one album gapless",
            checked = prefs.keepAlbumFlowInShuffle,
            onChecked = { on ->
                vm.updateTransition { it.copy(keepAlbumFlowInShuffle = on) }
                if (uiPrefs != null) vm.updateUi { it.copy(keepAlbumFlowInPlaylists = on) }
            },
            enabled = prefs.enabled,
        )
        SliderRow(
            title = stringResource(R.string.settings_max_stretch),
            value = prefs.maxStretchPercent.toFloat(),
            range = 0f..16f,
            steps = 15,
            onCommit = { v -> vm.updateTransition { it.copy(maxStretchPercent = v.roundToInt().toDouble()) } },
            valueText = { "±${it.roundToInt()} %" },
            enabled = prefs.enabled,
        )
        SliderRow(
            title = stringResource(R.string.settings_max_pitch),
            value = prefs.maxPitchShiftSemitones.toFloat(),
            range = 0f..3f,
            steps = 5,
            onCommit = { v -> vm.updateTransition { it.copy(maxPitchShiftSemitones = (v * 2).roundToInt() / 2.0) } },
            valueText = { formatDouble(((it * 2).roundToInt() / 2.0), 1) + " st" },
            enabled = prefs.enabled,
        )
        SliderRow(
            title = stringResource(R.string.settings_overlap_bars),
            value = prefs.preferredOverlapBars.toFloat(),
            range = 4f..32f,
            steps = 6,
            onCommit = { v -> vm.updateTransition { it.copy(preferredOverlapBars = (v / 4f).roundToInt() * 4) } },
            valueText = { "${(it / 4f).roundToInt() * 4} bars" },
            enabled = prefs.enabled,
        )
        SliderRow(
            title = stringResource(R.string.settings_energy),
            value = prefs.energy.toFloat(),
            range = 0f..1f,
            onCommit = { v -> vm.updateTransition { it.copy(energy = v.toDouble()) } },
            valueText = { formatDouble(it.toDouble(), 2) },
            enabled = prefs.enabled,
        )
        SliderRow(
            title = "Variety penalty",
            value = prefs.varietyPenalty.toFloat(),
            range = 0f..1f,
            onCommit = { v -> vm.updateTransition { it.copy(varietyPenalty = v.toDouble()) } },
            valueText = { formatDouble(it.toDouble(), 2) },
            enabled = prefs.enabled,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader(title = stringResource(R.string.settings_strategy_weights))
        Text(
            "Weight multiplies a strategy's score (1.0 = neutral). Switch a strategy off to never use it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        vm.strategyIds.forEach { id ->
            key(id) {
            val enabled = id !in prefs.disabledStrategies
            val weight = prefs.strategyWeights[id] ?: 1.0
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strategyDisplayName(id), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatDouble(weight, 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Switch(
                    checked = enabled,
                    onCheckedChange = { vm.setStrategyEnabled(id, it) },
                    modifier = Modifier.padding(start = 12.dp),
                    enabled = id != "crossfade",
                )
            }
            var local by remember(id, weight) { mutableStateOf(weight.toFloat()) }
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { vm.setStrategyWeight(id, ((local * 10).roundToInt() / 10.0)) },
                valueRange = 0f..2f,
                steps = 19,
                enabled = enabled && prefs.enabled,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            }
        }
    }
}

@Composable
private fun LibrarySection(prefs: UiPrefs?, vm: SettingsViewModel) {
    if (prefs == null) return
    var adding by remember { mutableStateOf(false) }
    Column {
        SliderRow(
            title = stringResource(R.string.settings_min_duration),
            value = prefs.minDurationSec.toFloat(),
            range = 0f..120f,
            steps = 23,
            onCommit = { v -> vm.updateUi { it.copy(minDurationSec = ((v / 5f).roundToInt() * 5)) } },
            valueText = { "${(it / 5f).roundToInt() * 5} s" },
        )
        SwitchRow(
            title = stringResource(R.string.settings_analyse_charging),
            checked = prefs.analyseOnlyWhileCharging,
            onChecked = { on -> vm.updateUi { it.copy(analyseOnlyWhileCharging = on) } },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_rescan)) },
            leadingContent = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
            modifier = Modifier.clickable { vm.rescan(full = true) },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader(
            title = stringResource(R.string.settings_blacklist),
            actionLabel = stringResource(R.string.action_create),
            onAction = { adding = true },
        )
        val blacklist = prefs.blacklist.toList()
        if (blacklist.isEmpty()) {
            Text(
                stringResource(R.string.empty_generic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        blacklist.forEach { folder ->
            ListItem(
                headlineContent = { Text(folder, style = MaterialTheme.typography.bodyMedium) },
                trailingContent = {
                    IconButton(onClick = { vm.updateUi { it.copy(blacklist = it.blacklist - folder) } }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove))
                    }
                },
            )
        }
    }
    if (adding) {
        var path by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.settings_blacklist)) },
            text = {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Folder path") },
                    placeholder = { Text("/storage/emulated/0/Podcasts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = path.trim().trimEnd('/')
                        if (p.isNotEmpty()) vm.updateUi { it.copy(blacklist = it.blacklist + p) }
                        adding = false
                    },
                    enabled = path.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AboutSection() {
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text("0.1.0", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}
