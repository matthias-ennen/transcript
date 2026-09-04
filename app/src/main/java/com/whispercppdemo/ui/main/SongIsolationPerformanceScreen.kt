package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.song.SongSeparationBackend
import de.matthiasennen.transcript.song.SongSeparationModel
import de.matthiasennen.transcript.song.SongSeparationPerformanceConfiguration
import de.matthiasennen.transcript.song.SongSeparationPreferences

@Composable
internal fun SongIsolationPerformanceScreen(
    state: TranscriptUiState,
    onSelectSongModel: (SongSeparationModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember(context) { SongSeparationPreferences(context) }
    val processors = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    val threadOptions = remember(processors) { (1..processors.coerceAtMost(8)).toList() }
    var configurations by remember {
        mutableStateOf(
            SongSeparationModel.entries.associateWith(preferences::loadPerformance)
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LiveStatusLine(state)
        Text(
            "Stimmisolierungs-Leistung",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Hier kannst du jedes Stimmisolierungsmodell getrennt abstimmen. Die Einstellung wird beim Start einer neuen Transkription fest in den Auftrag übernommen, damit ein laufender Job nicht nachträglich verändert wird.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Erkannte CPU-Kerne: $processors. Für vergleichbare Geschwindigkeitstests immer dieselbe Audiodatei und dieselben übrigen Einstellungen verwenden.",
            style = MaterialTheme.typography.bodySmall
        )

        SongSeparationModel.entries.forEach { model ->
            val installation = state.songModelInstallations.firstOrNull { it.model == model }
            val configuration = configurations.getValue(model)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(model.qualityLabel, style = MaterialTheme.typography.titleLarge)
                    Text(model.modelLabel, style = MaterialTheme.typography.titleMedium)
                    Text(model.description, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            installation?.isInstalled == true && model == state.selectedSongSeparationModel ->
                                "Installiert · aktuell ausgewählt"
                            installation?.isInstalled == true -> "Installiert"
                            else -> "Nicht installiert"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

                    SongSettingSelector(
                        title = "CPU-Threads",
                        selected = configuration.threads,
                        options = threadOptions.map { it to "$it Thread${if (it == 1) "" else "s"}" },
                        onSelected = { threads ->
                            val saved = preferences.savePerformance(
                                model,
                                configuration.copy(threads = threads)
                            )
                            configurations = configurations + (model to saved)
                        }
                    )
                    Text(
                        "Mehr Threads können die CPU-Ausführung beschleunigen, erhöhen aber Last, Wärme und je nach Modell den Speicherbedarf.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (model == SongSeparationModel.NATIVE_GGUF) {
                        SongSettingSelector(
                            title = "Rechenweg",
                            selected = configuration.backend,
                            options = listOf(
                                SongSeparationBackend.AUTO to "Automatisch · Vulkan wenn verfügbar",
                                SongSeparationBackend.CPU to "CPU / OpenBLAS erzwingen"
                            ),
                            onSelected = { backend ->
                                val saved = preferences.savePerformance(
                                    model,
                                    configuration.copy(backend = backend)
                                )
                                configurations = configurations + (model to saved)
                            }
                        )
                        Text(
                            "Automatisch fordert den Vulkan-fähigen CrispASR-Pfad an und darf auf CPU zurückfallen. CPU erzwingen eignet sich als reproduzierbarer Vergleichswert.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Rechenweg: ONNX Runtime · CPU",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (model == SongSeparationModel.HIGH_QUALITY) {
                        Text(
                            "Hinweis: Dieses große Kim-ONNX-Modell ist auf Android speicherintensiv. Der Standard bleibt deshalb 1 Thread; höhere Werte sind ausdrücklich zum Testen gedacht und können auf kleineren Geräten den Speicherbedarf erhöhen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (installation?.isInstalled == true && model != state.selectedSongSeparationModel) {
                            Button(onClick = { onSelectSongModel(model) }) {
                                Text("Modell verwenden")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val reset = preferences.resetPerformance(model)
                                configurations = configurations + (model to reset)
                            }
                        ) {
                            Text("Standard")
                        }
                    }
                }
            }
        }

        Text(
            "Die Fenstergröße und Überlappung bleiben bewusst modellgebunden. So können wir Geschwindigkeit über Backend und Threads testen, ohne die Trennqualität durch ungeprüfte Modellparameter zu verändern.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun <T> SongSettingSelector(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit
) {
    var expanded by remember(title) { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedLabel, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = "$title auswählen")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(menuWidth)
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(if (value == selected) "✓  $label" else label) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        }
                    )
                }
            }
        }
    }
}
