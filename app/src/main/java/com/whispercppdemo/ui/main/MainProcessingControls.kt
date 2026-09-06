package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MainProcessingControls(
    vadMode: WhisperVadMode,
    voiceIsolationEnabled: Boolean,
    enabled: Boolean,
    onVadModeSelected: (WhisperVadMode) -> Unit,
    onVoiceIsolationEnabledChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProcessingChoice(
            title = "VAD",
            selected = vadMode,
            options = listOf(
                WhisperVadMode.OFF to "Aus",
                WhisperVadMode.AUTOMATIC to "Automatisch",
                WhisperVadMode.ON to "Ein"
            ),
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onSelected = onVadModeSelected
        )
        ProcessingChoice(
            title = "Stimmisolierung",
            selected = voiceIsolationEnabled,
            options = listOf(false to "Aus", true to "Ein"),
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onSelected = onVoiceIsolationEnabledChanged
        )
    }
}

@Composable
private fun <T> ProcessingChoice(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    modifier: Modifier,
    onSelected: (T) -> Unit
) {
    var expanded by remember(title) { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected.toString()

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(title, style = MaterialTheme.typography.labelSmall)
                Text(selectedLabel, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "$title auswählen")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(if (value == selected) "✓  $label" else label) },
                    onClick = {
                        expanded = false
                        if (value != selected) onSelected(value)
                    }
                )
            }
        }
    }
}
