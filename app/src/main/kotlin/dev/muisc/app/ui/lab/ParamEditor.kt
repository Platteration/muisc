package dev.muisc.app.ui.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.muisc.app.ui.components.formatDouble
import dev.muisc.transitions.ParamSpec
import dev.muisc.transitions.Params
import kotlin.math.roundToInt

/**
 * Generates one control per [ParamSpec]: DoubleSpec → Slider, IntSpec → stepped Slider, BoolSpec → Switch,
 * ChoiceSpec → exposed dropdown. Values are written back through [onChange] as strings (the Params contract).
 */
@Composable
fun ParamEditor(
    specs: List<ParamSpec>,
    params: Params,
    onChange: (id: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        specs.forEach { spec ->
            when (spec) {
                is ParamSpec.DoubleSpec -> DoubleParam(spec, params.double(spec), onChange)
                is ParamSpec.IntSpec -> IntParam(spec, params.int(spec), onChange)
                is ParamSpec.BoolSpec -> BoolParam(spec, params.bool(spec), onChange)
                is ParamSpec.ChoiceSpec -> ChoiceParam(spec, params.choice(spec), onChange)
            }
        }
    }
}

@Composable
private fun ParamLabel(label: String, valueText: String, doc: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (doc.isNotBlank()) {
        Text(doc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DoubleParam(spec: ParamSpec.DoubleSpec, value: Double, onChange: (String, String) -> Unit) {
    // Local slider position so dragging is smooth; committed on release.
    var local by remember(spec.id, value) { mutableStateOf(value.toFloat()) }
    val range = spec.min.toFloat()..spec.max.toFloat()
    val steps = if (spec.step > 0.0) (((spec.max - spec.min) / spec.step).roundToInt() - 1).coerceIn(0, 1000) else 0
    Column(Modifier.padding(vertical = 6.dp)) {
        ParamLabel(spec.label, formatDouble(local.toDouble(), if (spec.step >= 1.0) 0 else 2) + (if (spec.unit.isNotBlank()) " ${spec.unit}" else ""), spec.doc)
        Slider(
            value = local.coerceIn(range.start, range.endInclusive),
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(spec.id, local.toDouble().toString()) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun IntParam(spec: ParamSpec.IntSpec, value: Int, onChange: (String, String) -> Unit) {
    var local by remember(spec.id, value) { mutableStateOf(value.toFloat()) }
    val range = spec.min.toFloat()..spec.max.toFloat()
    val steps = (spec.max - spec.min - 1).coerceIn(0, 1000)
    Column(Modifier.padding(vertical = 6.dp)) {
        ParamLabel(spec.label, "${local.roundToInt()}" + (if (spec.unit.isNotBlank()) " ${spec.unit}" else ""), spec.doc)
        Slider(
            value = local.coerceIn(range.start, range.endInclusive),
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(spec.id, local.roundToInt().toString()) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun BoolParam(spec: ParamSpec.BoolSpec, value: Boolean, onChange: (String, String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(spec.label, style = MaterialTheme.typography.bodyMedium)
            if (spec.doc.isNotBlank()) {
                Text(spec.doc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = value, onCheckedChange = { onChange(spec.id, it.toString()) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceParam(spec: ParamSpec.ChoiceSpec, value: String, onChange: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 6.dp)) {
        if (spec.doc.isNotBlank()) {
            Text(spec.doc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text(spec.label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                spec.choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice) },
                        onClick = {
                            expanded = false
                            onChange(spec.id, choice)
                        },
                    )
                }
            }
        }
    }
}
