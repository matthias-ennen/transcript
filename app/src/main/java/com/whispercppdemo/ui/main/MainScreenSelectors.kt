package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Reusable model and language selection controls used by the main workflow. */
@Composable
internal fun ModelSelector(
    selected: WhisperModel,
    installations: List<ModelInstallation>,
    enabled: Boolean,
    onSelected: (WhisperModel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val selectorWidth = maxWidth
        LabeledSelectorButton(
            label = "Qualitätsstufe",
            value = selected.qualityLabel,
            enabled = enabled,
            onClick = { expanded = true },
            contentDescription = if (expanded) "Modellliste schließen" else "Modellliste öffnen"
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(selectorWidth)
        ) {
            installations.forEach { installation ->
                val model = installation.model
                DropdownMenuItem(
                    text = { Text(model.qualityLabel) },
                    onClick = {
                        onSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

internal val TranscriptUiState.isModelSelectionEnabled: Boolean
    get() = !isBusy && !isRecording

@Composable
internal fun LanguageSelector(
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    WhisperLanguageSelector(selected = selected, enabled = enabled, onSelected = onSelected)
}

@Composable
private fun LabeledSelectorButton(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Default.ArrowDropDown, contentDescription = contentDescription)
    }
}

internal fun formatDownloadSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}
