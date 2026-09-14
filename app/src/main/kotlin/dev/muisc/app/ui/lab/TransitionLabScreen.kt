package dev.muisc.app.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.app.R
import dev.muisc.app.data.db.Song
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.ArtworkImage
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.components.artUri
import dev.muisc.app.ui.components.formatBpm
import dev.muisc.app.ui.components.formatDouble
import dev.muisc.app.ui.components.formatDuration
import dev.muisc.app.ui.components.formatLufs
import dev.muisc.app.ui.components.formatScore
import dev.muisc.app.ui.library.LibraryTopBar
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.LabViewModel
import dev.muisc.transitions.AutomationLane
import dev.muisc.transitions.Marker
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RenderReport

private enum class Deck { A, B }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitionLabScreen(initialA: Long, initialB: Long, navigator: MuiscNavigator) {
    val vm: LabViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    val allSongs by vm.allSongs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<Deck?>(null) }

    LaunchedEffect(initialA, initialB) { vm.loadPair(initialA, initialB) }
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeMessage()
    }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = stringResource(R.string.nav_lab),
                navigator = navigator,
                showBack = true,
                showSearch = false,
                showQueue = true,
                extraActions = {
                    IconButton(onClick = vm::swap, enabled = state.a != null && state.b != null) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // --- Pair selection ---
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeckCard(
                        title = stringResource(R.string.lab_track_a),
                        song = state.a,
                        analysis = state.analysisA,
                        onPick = { picking = Deck.A },
                        modifier = Modifier.weight(1f),
                    )
                    DeckCard(
                        title = stringResource(R.string.lab_track_b),
                        song = state.b,
                        analysis = state.analysisB,
                        onPick = { picking = Deck.B },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = vm::useCurrentAndNext) { Text(stringResource(R.string.lab_use_current)) }
                    OutlinedButton(onClick = vm::plan, enabled = state.ready && state.analysisA != null && !state.busy) {
                        Text(stringResource(R.string.lab_plan))
                    }
                }
            }
            if (state.analysing) {
                item {
                    ProgressRow(
                        label = state.analysisProgress?.stage ?: stringResource(R.string.edge_analysing),
                        fraction = state.analysisProgress?.fraction,
                    )
                }
            }

            // --- Pair features ---
            val ranked = state.ranked
            if (ranked != null) {
                item { SectionHeader(title = "Pair") }
                item { PairFeaturesRow(ranked.features) }

                // --- Candidates ---
                item { SectionHeader(title = stringResource(R.string.lab_candidates)) }
                itemsIndexed(ranked.candidates, key = { i, c -> "${c.strategy.id}-$i" }) { index, candidate ->
                    CandidateRow(
                        candidate = candidate,
                        selected = index == state.selectedIndex,
                        onClick = { vm.selectCandidate(index) },
                    )
                }
            }

            // --- Parameters ---
            val selected = state.selected
            if (selected != null) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.lab_parameters) + " · " + selected.strategy.displayName,
                        actionLabel = "Reset",
                        onAction = vm::resetParams,
                    )
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        if (selected.strategy.description.isNotBlank()) {
                            Text(selected.strategy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected.modifiers.isNotEmpty()) {
                            Text(
                                "Modifiers: " + selected.modifiers.joinToString { it.displayName },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (selected.plan.notes.isNotEmpty()) {
                            selected.plan.notes.forEach { note ->
                                Text("• $note", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        ParamEditor(specs = selected.strategy.params, params = state.params, onChange = vm::setParam)
                    }
                }

                // --- Render ---
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = vm::render, enabled = state.ready && !state.busy) {
                                Text(stringResource(R.string.lab_render))
                            }
                            val rendered = state.rendered
                            if (rendered != null) {
                                if (state.auditioning) {
                                    FilledTonalButton(onClick = vm::stopAudition) {
                                        Icon(Icons.Rounded.Stop, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.lab_stop))
                                    }
                                } else {
                                    FilledTonalButton(onClick = vm::audition) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.lab_audition))
                                    }
                                }
                            }
                        }
                        if (state.rendering) {
                            ProgressRow(label = state.renderProgress?.stage ?: "", fraction = state.renderProgress?.fraction)
                        }
                    }
                }

                // --- Lanes / markers ---
                val rendered = state.rendered
                val lanes = rendered?.plan?.lanes ?: selected.plan.lanes
                val markers = rendered?.markers ?: emptyList()
                val lengthSec = rendered?.audio?.durationSec
                    ?: (selected.plan.expectedOutputFrames.toDouble() / (state.analysisA?.sampleRate ?: 44_100))
                if (lanes.isNotEmpty() || markers.isNotEmpty()) {
                    item { SectionHeader(title = stringResource(R.string.lab_lanes)) }
                    item {
                        LanePlot(
                            lanes = lanes,
                            markers = markers,
                            lengthSec = lengthSec,
                            sampleRate = rendered?.audio?.sampleRate ?: (state.analysisA?.sampleRate ?: 44_100),
                            auditionPosition = if (state.auditioning) state.auditionPosition else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                }

                // --- Report + feedback ---
                if (rendered != null) {
                    item { SectionHeader(title = stringResource(R.string.lab_report)) }
                    item { ReportChips(rendered.report, rendered.audio.durationSec) }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { vm.rate(true) }) {
                                Icon(
                                    Icons.Rounded.ThumbUp,
                                    contentDescription = stringResource(R.string.lab_thumbs_up),
                                    tint = if (state.rated == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.rate(false) }) {
                                Icon(
                                    Icons.Rounded.ThumbDown,
                                    contentDescription = stringResource(R.string.lab_thumbs_down),
                                    tint = if (state.rated == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilterChip(
                                selected = state.pinned,
                                onClick = vm::togglePin,
                                label = { Text(stringResource(if (state.pinned) R.string.lab_unpin else R.string.lab_pin)) },
                                leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                            AssistChip(
                                onClick = vm::export,
                                label = { Text(stringResource(R.string.lab_export)) },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
        }
    }

    val deck = picking
    if (deck != null) {
        SongPickerDialog(
            songs = allSongs,
            onPick = { song ->
                if (deck == Deck.A) vm.setA(song) else vm.setB(song)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ProgressRow(label: String, fraction: Float?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (fraction != null && fraction > 0f) {
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DeckCard(title: String, song: Song?, analysis: TrackAnalysis?, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(model = song?.artUri(), contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "—", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song?.artist ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (analysis != null) {
                AnalysisSummary(analysis)
            } else if (song != null) {
                Text(stringResource(R.string.lab_no_analysis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onPick, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.lab_pick)) }
        }
    }
}

@Composable
private fun AnalysisSummary(a: TrackAnalysis) {
    val key = a.key.key
    Column {
        Text(formatBpm(a.tempo.bpm) + "  · grid " + formatDouble(a.grid.confidence.toDouble(), 2), style = MaterialTheme.typography.bodySmall)
        Text("${key.shortName} · ${key.camelot.code} · strength ${formatDouble(a.key.strength.toDouble(), 2)}", style = MaterialTheme.typography.bodySmall)
        Text(formatLufs(a.loudness.integratedLufs) + " · " + formatDouble(a.loudness.truePeakDbtp.toDouble(), 1) + " dBTP", style = MaterialTheme.typography.bodySmall)
        Text("intro ${a.intro.name.lowercase()} · outro ${a.outro.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
        Text(formatDuration((a.durationSec * 1000).toLong()) + " · ${a.sections.size} sections", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PairFeaturesRow(f: PairFeatures) {
    val chips = listOf(
        "tempo ×" + formatDouble(f.tempoRatio, 3) + " (" + f.tempoRelation.name.lowercase() + ")",
        "stretch " + formatDouble(f.stretchPercent, 1) + " %",
        "camelot Δ" + f.camelotDistance + (if (f.bestPitchShiftSemitones != 0) " → Δ${f.camelotDistanceAfterShift} @ ${f.bestPitchShiftSemitones} st" else ""),
        "loudness " + formatDouble(f.loudnessDeltaLu, 1) + " LU",
        "energy " + formatDouble(f.energyDelta, 2),
        "vocal clash " + formatDouble(f.vocalClash, 2),
        "spectral sim " + formatDouble(f.spectralSimilarity, 2),
        if (f.beatMatchable) "beat-matchable" else "not beat-matchable",
    )
    ChipFlow(chips)
}

@Composable
private fun ChipFlow(labels: List<String>, error: Boolean = false) {
    // Simple wrapping: rows of up to three chips.
    Column(Modifier.padding(horizontal = 12.dp)) {
        labels.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { label ->
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            disabledLabelColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: PlanCandidate, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(candidate.strategy.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(formatScore(candidate.score), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            val reasons = candidate.applicability.reasons
            if (reasons.isNotEmpty()) {
                Text(reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (candidate.modifiers.isNotEmpty()) {
                Text("+ " + candidate.modifiers.joinToString { it.displayName }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun ReportChips(report: RenderReport, durationSec: Double) {
    val chips = buildList {
        add("length " + formatDouble(durationSec, 1) + " s")
        add("render " + report.renderMillis + " ms")
        add(formatLufs(report.integratedLufs))
        add("peak " + formatDouble(report.truePeakDbtp.toDouble(), 1) + " dBTP")
        report.metrics.forEach { (k, v) -> add("$k " + formatDouble(v, 2)) }
    }
    Column {
        ChipFlow(chips)
        if (report.warnings.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            ChipFlow(report.warnings, error = true)
        }
    }
}

private val laneColors = listOf(
    Color(0xFF4A90E2), Color(0xFFE94E77), Color(0xFF2ECC71), Color(0xFFF5A623), Color(0xFF9B59B6), Color(0xFF1ABC9C),
)

/** Canvas: one polyline per automation lane over the segment length, vertical dashed lines for markers. */
@Composable
fun LanePlot(
    lanes: List<AutomationLane>,
    markers: List<Marker>,
    lengthSec: Double,
    sampleRate: Int,
    auditionPosition: Float?,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val cursorColor = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            val w = size.width
            val h = size.height
            val len = if (lengthSec > 0) lengthSec else 1.0
            // Frame
            drawRect(gridColor, style = Stroke(width = 1f))
            for (i in 1 until 4) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            lanes.forEachIndexed { li, lane ->
                if (lane.points.isEmpty()) return@forEachIndexed
                val maxV = lane.points.maxOf { it.value }.let { if (it > 1.0) it else 1.0 }
                val minV = lane.points.minOf { it.value }.let { if (it < 0.0) it else 0.0 }
                val span = (maxV - minV).let { if (it <= 0.0) 1.0 else it }
                val path = Path()
                lane.points.forEachIndexed { pi, p ->
                    val x = ((p.outputSec / len).coerceIn(0.0, 1.0) * w).toFloat()
                    val y = (h - ((p.value - minV) / span * h)).toFloat()
                    if (pi == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, laneColors[li % laneColors.size], style = Stroke(width = 3f))
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            markers.forEach { m ->
                val x = ((m.frame.toDouble() / sampleRate / len).coerceIn(0.0, 1.0) * w).toFloat()
                drawLine(markerColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2f, pathEffect = dash)
            }
            if (auditionPosition != null) {
                val x = auditionPosition.coerceIn(0f, 1f) * w
                drawLine(cursorColor, Offset(x, 0f), Offset(x, h), strokeWidth = 3f)
            }
        }
        // Legend
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lanes.forEachIndexed { i, lane ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .padding(0.dp),
                    ) { Canvas(Modifier.fillMaxSize()) { drawRect(laneColors[i % laneColors.size]) } }
                    Spacer(Modifier.width(4.dp))
                    Text(lane.id, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (markers.isNotEmpty()) {
            Text(
                markers.joinToString(" · ") { m -> formatDouble(m.frame.toDouble() / sampleRate, 1) + " s " + m.label },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SongPickerDialog(songs: List<Song>, onPick: (Song) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(songs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) songs else songs.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lab_pick)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { song ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(song) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
