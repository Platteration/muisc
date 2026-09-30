package dev.muisc.app.ui.dj

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muisc.app.R
import dev.muisc.app.playback.RecipeInfo
import dev.muisc.app.ui.MuiscNavigator
import dev.muisc.app.ui.components.SectionHeader
import dev.muisc.app.ui.components.formatDouble
import dev.muisc.app.ui.viewmodel.AppViewModelFactory
import dev.muisc.app.ui.viewmodel.DjViewModel
import dev.muisc.app.ui.viewmodel.ImportDialog
import dev.muisc.transitions.Params
import dev.muisc.transitions.recipe.LaneKind
import dev.muisc.transitions.recipe.RecipeException
import dev.muisc.transitions.recipe.RecipeOrigin
import dev.muisc.transitions.recipe.RecipeProblem
import dev.muisc.transitions.recipe.RecipeResolver
import dev.muisc.transitions.recipe.RecipeStatus
import dev.muisc.transitions.recipe.ResolvedDeck
import dev.muisc.transitions.recipe.ResolvedLane
import dev.muisc.transitions.recipe.ResolvedRecipe
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** MIME types offered by the import picker: file managers label `.json` files in all three ways. */
private val IMPORT_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

// ---------------------------------------------------------------------------------------------------------------
// Recipes list

@Composable
fun DjRecipesScreen(navigator: MuiscNavigator) {
    val vm: DjViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    val importDialog by vm.importDialog.collectAsStateWithLifecycle()
    // A cancelled picker returns null, which importRecipe ignores: nothing is imported and no dialog is shown.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> vm.importRecipe(uri?.toString()) }

    DjScaffold(
        stringResource(R.string.dj_recipes),
        navigator,
        vm,
        actions = {
            IconButton(onClick = { picker.launch(IMPORT_TYPES) }) {
                Icon(Icons.Rounded.FileOpen, contentDescription = stringResource(R.string.dj_import))
            }
        },
    ) {
        loadingItem(state)
        item { ProblemsCard(state.problems) }
        item {
            Text(
                stringResource(R.string.dj_recipes_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (!state.loading && state.recipes.isEmpty()) item { EmptyNote(stringResource(R.string.dj_recipes_empty)) }
        val mine = state.recipes.filter { it.origin == RecipeOrigin.USER }
        val builtIn = state.recipes.filter { it.origin == RecipeOrigin.BUILT_IN }
        if (mine.isNotEmpty()) {
            item(key = "h-mine") { SectionHeader(title = stringResource(R.string.dj_recipes_yours)) }
            items(mine, key = { "u-" + it.id + it.status }) { recipe ->
                RecipeRow(recipe, onOpen = { navigator.toDjRecipe(recipe.id) }, onToggle = { vm.setRecipeEnabled(recipe.id, it) })
            }
        }
        if (builtIn.isNotEmpty()) {
            item(key = "h-builtin") { SectionHeader(title = stringResource(R.string.dj_recipes_builtin)) }
            items(builtIn, key = { "b-" + it.id + it.status }) { recipe ->
                RecipeRow(recipe, onOpen = { navigator.toDjRecipe(recipe.id) }, onToggle = { vm.setRecipeEnabled(recipe.id, it) })
            }
        }
    }

    importDialog?.let { dialog -> ImportDialogView(dialog, vm) }
}

@Composable
private fun RecipeRow(recipe: RecipeInfo, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val usable = recipe.status == RecipeStatus.ACTIVE && recipe.skippedReason == null
    ListItem(
        headlineContent = { Text(recipe.name) },
        supportingContent = {
            Column {
                if (recipe.description.isNotBlank()) {
                    Text(recipe.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Text(statusLine(recipe), style = MaterialTheme.typography.labelSmall, color = statusColor(recipe))
            }
        },
        leadingContent = {
            when {
                recipe.errorCount > 0 -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                recipe.warningCount > 0 -> Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                else -> Spacer(Modifier.size(24.dp))
            }
        },
        trailingContent = if (usable) {
            { Switch(checked = recipe.enabled, onCheckedChange = onToggle) }
        } else null,
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

private fun statusLine(r: RecipeInfo): String {
    val base = when (r.status) {
        RecipeStatus.ACTIVE -> when {
            r.skippedReason != null -> "Not used: ${r.skippedReason}"
            !r.enabled -> "Off"
            else -> "In use"
        }
        RecipeStatus.INVALID -> "Not used: ${r.errorCount} error${if (r.errorCount == 1) "" else "s"}"
        RecipeStatus.SHADOWED -> "Replaced by your recipe with the same id"
        RecipeStatus.DUPLICATE -> "Not used: another file has the same id"
    }
    val warnings = if (r.warningCount > 0) " · ${r.warningCount} warning${if (r.warningCount == 1) "" else "s"}" else ""
    return base + warnings + " · recipe:${r.id}"
}

@Composable
private fun statusColor(r: RecipeInfo): Color = when {
    r.errorCount > 0 || r.status == RecipeStatus.DUPLICATE -> MaterialTheme.colorScheme.error
    r.inUse -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ImportDialogView(dialog: ImportDialog, vm: DjViewModel) {
    when (dialog) {
        ImportDialog.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.dj_importing)) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {},
        )
        is ImportDialog.ConfirmErrors -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text(stringResource(R.string.dj_import_errors_title, dialog.name)) },
            text = { ProblemList(dialog.problems, header = stringResource(R.string.dj_import_errors_body)) },
            confirmButton = { TextButton(onClick = { vm.importRecipe(dialog.uri, allowErrors = true) }) { Text(stringResource(R.string.dj_import_anyway)) } },
            dismissButton = { TextButton(onClick = vm::dismissImport) { Text(stringResource(R.string.action_cancel)) } },
        )
        is ImportDialog.ConfirmReplace -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text(stringResource(R.string.dj_import_replace_title)) },
            text = { ProblemList(dialog.problems, header = stringResource(R.string.dj_import_replace_body, dialog.existingName)) },
            confirmButton = {
                TextButton(onClick = { vm.importRecipe(dialog.uri, allowErrors = dialog.allowErrors, replace = true) }) { Text(stringResource(R.string.dj_replace)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissImport) { Text(stringResource(R.string.action_cancel)) } },
        )
        is ImportDialog.Report -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text(dialog.title) },
            text = { ProblemList(dialog.problems, header = if (dialog.ok) null else stringResource(R.string.dj_import_failed)) },
            confirmButton = { TextButton(onClick = vm::dismissImport) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

/** Problems with where they are (`line 3, column 5 · a.level[2]`), errors first. */
@Composable
private fun ProblemList(problems: List<RecipeProblem>, header: String? = null) {
    LazyColumn(Modifier.heightIn(max = 360.dp)) {
        if (header != null) item { Text(header, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        items(problems.sortedBy { if (it.isError) 0 else 1 }) { p -> ProblemRow(p) }
    }
}

@Composable
private fun ProblemRow(p: RecipeProblem) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Icon(
            if (p.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Warning,
            contentDescription = if (p.isError) "Error" else "Warning",
            tint = if (p.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            val where = buildList {
                if (p.line != null) add("line ${p.line}" + (p.column?.let { ", column $it" } ?: ""))
                if (p.path.isNotEmpty()) add(p.path)
                p.setting?.let { add("with $it") }
            }.joinToString(" · ")
            if (where.isNotEmpty()) Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Recipe detail

@Composable
fun DjRecipeDetailScreen(recipeId: String, navigator: MuiscNavigator) {
    val vm: DjViewModel = viewModel(factory = AppViewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val entries = state.recipes.filter { it.id == recipeId }
    val recipe = entries.firstOrNull { it.status == RecipeStatus.ACTIVE } ?: entries.firstOrNull()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        vm.exportRecipe(recipeId, uri?.toString())
    }

    DjScaffold(recipe?.name ?: recipeId, navigator, vm) {
        loadingItem(state)
        if (recipe == null) {
            if (!state.loading) item { EmptyNote(stringResource(R.string.dj_recipe_missing, recipeId)) }
            return@DjScaffold
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (recipe.description.isNotBlank()) Text(recipe.description, style = MaterialTheme.typography.bodyMedium)
                val r = recipe.recipe
                val meta = buildList {
                    add(if (recipe.origin == RecipeOrigin.BUILT_IN) "Built in" else "Yours")
                    if (r.author.isNotBlank()) add("by ${r.author}")
                    add("version ${r.version}")
                    add("tempo ${r.timing.tempo.name.lowercase()}")
                    add("ambition ${formatDouble(r.ambition, 1)}")
                    if (r.modifiers.isNotEmpty()) add("+ " + r.modifiers.joinToString())
                    if (r.tags.isNotEmpty()) add(r.tags.joinToString(" ") { "#$it" })
                }
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text(statusLine(recipe), style = MaterialTheme.typography.labelMedium, color = statusColor(recipe), modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (recipe.status == RecipeStatus.ACTIVE && recipe.skippedReason == null) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dj_recipe_enabled)) },
                    supportingContent = { Text(stringResource(R.string.dj_recipe_enabled_body)) },
                    trailingContent = { Switch(checked = recipe.enabled, onCheckedChange = { vm.setRecipeEnabled(recipe.id, it) }) },
                )
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { exporter.launch("${recipe.id}.json") },
                    label = { Text(stringResource(R.string.dj_export)) },
                    leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = {
                        scope.launch {
                            val text = vm.recipeText(recipe.id) ?: return@launch
                            val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, recipe.name))
                        }
                    },
                    label = { Text(stringResource(R.string.dj_share)) },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { vm.duplicateRecipe(recipe.id) },
                    label = { Text(stringResource(R.string.dj_duplicate)) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                if (recipe.origin == RecipeOrigin.USER) {
                    AssistChip(
                        onClick = { confirmDelete = true },
                        label = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }
        if (recipe.problems.isNotEmpty()) {
            item { SectionHeader(title = stringResource(R.string.dj_recipe_problems)) }
            items(recipe.problems.sortedBy { if (it.isError) 0 else 1 }) { p ->
                Box(Modifier.padding(horizontal = 16.dp)) { ProblemRow(p) }
            }
        }
        item { RecipeVariablesAndLanes(recipe) }
    }

    if (confirmDelete && recipe != null) {
        ConfirmDialog(
            title = stringResource(R.string.dj_delete_recipe_title),
            body = stringResource(R.string.dj_delete_recipe_body, recipe.name),
            confirm = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deleteRecipe(recipe.id)
                navigator.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The recipe's variables as sliders, and a plot of its lanes resolved at those values. */
@Composable
private fun RecipeVariablesAndLanes(info: RecipeInfo) {
    val recipe = info.recipe
    val values = remember(recipe) { mutableStateMapOf<String, Double>().apply { recipe.vars.forEach { (k, v) -> put(k, v.default) } } }
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (recipe.vars.isNotEmpty()) {
            SectionHeader(title = stringResource(R.string.dj_recipe_variables), modifier = Modifier.padding(start = 0.dp))
            recipe.vars.forEach { (name, v) ->
                val value = values[name] ?: v.default
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(v.label.ifBlank { name }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            (if (v.integer) value.roundToInt().toString() else formatDouble(value, 2)) + if (v.unit.isNotBlank()) " ${v.unit}" else "",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (v.max > v.min) {
                        Slider(
                            value = value.toFloat(),
                            onValueChange = { f -> values[name] = if (v.integer) f.roundToInt().toDouble() else f.toDouble() },
                            valueRange = v.min.toFloat()..v.max.toFloat(),
                            steps = if (v.integer) ((v.max - v.min).roundToInt() - 1).coerceIn(0, 100) else 0,
                        )
                    }
                    if (v.doc.isNotBlank()) Text(v.doc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionHeader(title = stringResource(R.string.dj_recipe_lanes), modifier = Modifier.padding(start = 0.dp))
        val params = Params(values.mapValues { (_, v) -> v.toString() })
        val resolved: Result<ResolvedRecipe> = remember(recipe, params) {
            try {
                Result.success(RecipeResolver.resolve(recipe, params))
            } catch (e: RecipeException) {
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
        }
        resolved.onSuccess { r ->
            Text(
                "Overlap ${formatDouble(r.lengthBars, 1)} bars · timeline ${formatDouble(r.totalBars, 1)} bars · tempo ${r.tempo.name.lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DeckPlot(stringResource(R.string.lab_track_a), r.a, r.totalBars)
            DeckPlot(stringResource(R.string.lab_track_b), r.b, r.totalBars)
        }.onFailure { e ->
            Text(stringResource(R.string.dj_recipe_unresolvable, e.message ?: e.javaClass.simpleName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private val deckLaneColors = listOf(
    Color(0xFF4A90E2), Color(0xFFE94E77), Color(0xFF2ECC71), Color(0xFFF5A623), Color(0xFF9B59B6), Color(0xFF1ABC9C),
    Color(0xFFB8860B), Color(0xFF607D8B), Color(0xFFD35400), Color(0xFF8E44AD),
)

/**
 * One deck's lanes over the recipe timeline (bars). Only lanes that move are drawn; each is normalised to its own
 * kind's range so they share one axis: level 0..2 (unity gain at mid-height), dB lanes −36..+12
 * (anything quieter sits on the floor), filter cutoffs on a log scale 20 Hz..20 kHz, sends and freeze 0..1.
 */
@Composable
private fun DeckPlot(title: String, deck: ResolvedDeck, totalBars: Double) {
    val lanes = deck.lanes().filter { (_, lane) -> !lane.isEmpty && !lane.isNeutral }
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (lanes.isEmpty()) {
            Text(stringResource(R.string.dj_recipe_deck_untouched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 4.dp),
        ) {
            val w = size.width
            val h = size.height
            val bars = if (totalBars > 0) totalBars else 1.0
            drawRect(grid, style = Stroke(width = 1f))
            // One vertical line per 4 bars.
            var b = 4.0
            while (b < bars) {
                val x = (b / bars * w).toFloat()
                drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                b += 4.0
            }
            lanes.forEachIndexed { i, (_, lane) ->
                val path = Path()
                val steps = 160
                for (s in 0..steps) {
                    val bar = bars * s / steps
                    val y = (h - normalised(lane, lane.valueAt(bar)) * h).toFloat()
                    val x = (s.toDouble() / steps * w).toFloat()
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, deckLaneColors[i % deckLaneColors.size], style = Stroke(width = 3f))
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            lanes.forEachIndexed { i, (name, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp)) { Canvas(Modifier.fillMaxSize()) { drawRect(deckLaneColors[i % deckLaneColors.size]) } }
                    Spacer(Modifier.width(4.dp))
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** A lane value mapped to 0..1 for plotting (see [DeckPlot]). */
internal fun normalised(lane: ResolvedLane, value: Double): Double = when (lane.kind) {
    LaneKind.LEVEL -> value / 2.0
    LaneKind.DB -> (value + 36.0) / 48.0
    LaneKind.HPF, LaneKind.LPF -> if (value <= 20.0) 0.0 else ln(value / 20.0) / ln(1000.0)
    LaneKind.SEND, LaneKind.FREEZE -> value
}.coerceIn(0.0, 1.0)
