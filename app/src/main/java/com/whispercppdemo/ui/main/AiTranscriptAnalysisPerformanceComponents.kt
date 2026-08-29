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
import androidx.compose.ui.platform.LocalContext
import de.matthiasennen.transcript.ai.AiEngineSessionManager
import de.matthiasennen.transcript.ai.AiPerformancePreferences
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisPerformanceSnapshot
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisResult
import de.matthiasennen.transcript.ai.LocalAiEngine
import de.matthiasennen.transcript.ai.aiTranscriptAnalysisPerformanceLines
import java.io.File

@Composable
internal fun AiTranscriptAnalysisPerformanceDetails(
    result: AiTranscriptAnalysisResult
) {
    val context = LocalContext.current
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
        val configuration = AiPerformancePreferences(context.applicationContext).load(result.model)
        val modelFile = File(File(context.filesDir, "ai-models"), result.model.fileName)
        val runtimeReport = runCatching {
            AiEngineSessionManager.runtimeReport(
                model = result.model,
                file = modelFile,
                configuration = configuration
            )
        }.getOrNull()
        AiTranscriptAnalysisPerformanceSnapshot(
            modelLoadMs = result.modelLoadMs,
            totalInferenceMs = result.totalInferenceMs,
            totalDurationMs = result.totalDurationMs,
            generationCount = result.generationCount,
            sourceChunkCount = result.sourceChunkCount,
            configuration = configuration,
            runtimeReport = runtimeReport,
            lastGenerationMetrics = LocalAiEngine.lastGenerationMetricsSnapshot()
        )
    }

    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (expanded) "Leistungsdaten ausblenden" else "Leistungsdaten anzeigen")
    }
    if (expanded) {
        Column(modifier = Modifier.fillMaxWidth()) {
            aiTranscriptAnalysisPerformanceLines(snapshot).forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
