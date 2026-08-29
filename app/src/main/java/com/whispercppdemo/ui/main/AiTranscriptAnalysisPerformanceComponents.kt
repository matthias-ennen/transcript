package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisPerformanceStore
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisResult
import de.matthiasennen.transcript.ai.aiTranscriptAnalysisPerformanceLines

@Composable
internal fun AiTranscriptAnalysisPerformanceDetails(
    result: AiTranscriptAnalysisResult
) {
    var expanded by remember(
        result.sourceFingerprint,
        result.action,
        result.totalDurationMs
    ) { mutableStateOf(false) }

    val snapshot = remember(
        result.sourceFingerprint,
        result.action,
        result.totalDurationMs
    ) {
        AiTranscriptAnalysisPerformanceStore.snapshotFor(result)
    }

    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (expanded) "Leistungsdaten ausblenden" else "Leistungsdaten anzeigen")
    }
    if (expanded) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (snapshot == null) {
                Text(
                    "Für diesen Lauf sind keine eingefrorenen Leistungsdaten verfügbar.",
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                aiTranscriptAnalysisPerformanceLines(snapshot).forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
