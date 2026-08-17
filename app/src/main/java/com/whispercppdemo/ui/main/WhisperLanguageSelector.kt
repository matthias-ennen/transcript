package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One shared source for the transcription language selection on both pages. */
internal val whisperLanguageOptions: List<Pair<String, String>> = listOf(
    "auto" to "Automatisch – empfohlen",
    "de" to "Deutsch",
    "en" to "Englisch",
    "sq" to "Albanisch",
    "eu" to "Baskisch",
    "be" to "Belarussisch",
    "bs" to "Bosnisch",
    "br" to "Bretonisch",
    "bg" to "Bulgarisch",
    "da" to "Dänisch",
    "et" to "Estnisch",
    "fo" to "Färöisch",
    "fi" to "Finnisch",
    "fr" to "Französisch",
    "gl" to "Galicisch",
    "el" to "Griechisch",
    "is" to "Isländisch",
    "it" to "Italienisch",
    "ca" to "Katalanisch",
    "hr" to "Kroatisch",
    "lv" to "Lettisch",
    "lt" to "Litauisch",
    "lb" to "Luxemburgisch",
    "mt" to "Maltesisch",
    "mk" to "Mazedonisch",
    "nl" to "Niederländisch",
    "no" to "Norwegisch",
    "pl" to "Polnisch",
    "pt" to "Portugiesisch",
    "ro" to "Rumänisch",
    "ru" to "Russisch",
    "sv" to "Schwedisch",
    "sr" to "Serbisch",
    "sk" to "Slowakisch",
    "sl" to "Slowenisch",
    "es" to "Spanisch",
    "cs" to "Tschechisch",
    "tr" to "Türkisch",
    "uk" to "Ukrainisch",
    "hu" to "Ungarisch",
    "cy" to "Walisisch"
)

internal fun whisperLanguageLabel(code: String): String =
    whisperLanguageOptions.firstOrNull { it.first == code }?.second
        ?: "Automatisch – empfohlen"

@Composable
internal fun WhisperLanguageSelector(
    selected: String,
    enabled: Boolean = true,
    onSelected: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = whisperLanguageLabel(selected)

    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Sprache")
            Text(selectedLabel)
        }
    }

    if (open) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(query) {
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty()) whisperLanguageOptions
            else whisperLanguageOptions.filter { it.second.lowercase().contains(normalized) }
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Transkriptionssprache") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Sprache suchen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filtered, key = { it.first }) { (code, name) ->
                            TextButton(
                                onClick = {
                                    onSelected(code)
                                    open = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (code == selected) "✓ $name" else name,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Abbrechen") }
            }
        )
    }
}
