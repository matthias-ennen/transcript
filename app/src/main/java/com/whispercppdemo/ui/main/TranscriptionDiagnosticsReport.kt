package de.matthiasennen.transcript.ui.main

import android.os.Build
import de.matthiasennen.transcript.song.TranscriptionMode
import de.matthiasennen.transcript.transcription.VadProcessingSummary
import kotlin.math.round

internal fun buildTranscriptionDiagnosticsReport(state: TranscriptUiState): String = buildString {
    val timing = state.pipelineTiming
    val vadSummary = state.vadProcessingSummary
    val audioDurationMs = vadSummary?.originalDurationMs
        ?.takeIf { it > 0L }
        ?: state.audioDurationMs.takeIf { it > 0L }

    appendLine("Ergebnis")
    state.completedModel?.modelLabel?.let { model ->
        val language = state.detectedLanguage?.let(::whisperLanguageDisplayName)
        appendLine(
            if (language != null) {
                "Whisper: $model · Sprache: $language"
            } else {
                "Whisper: $model"
            }
        )
    }
    if (state.completedModel == null) {
        state.detectedLanguage?.let { appendLine("Sprache: ${whisperLanguageDisplayName(it)}") }
    }

    appendLine(
        if (timing.mode == TranscriptionMode.SONG) {
            "Stimmisolierung: ${timing.voiceIsolationModelLabel ?: "Modell unbekannt"}"
        } else {
            "Stimmisolierung: Aus"
        }
    )
    appendLine("VAD: ${vadSummary?.let(::diagnosticsVadLabel) ?: "keine Messdaten"}")
    state.transcriptionDurationSeconds?.let { appendLine("Dauer: ${diagnosticsClock(it)}") }
    appendLine("Textabschnitte: ${state.segments.count { it.text.isNotBlank() }}")

    appendLine()
    appendLine("Diagnose")
    appendLine(
        "Gerät: ${Build.MANUFACTURER} ${Build.MODEL} · " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    )
    appendLine("Transkriptions-Pipeline")

    if (timing.totalSeconds <= 0L) {
        appendLine("Für dieses gespeicherte Ergebnis sind keine Pipeline-Laufzeiten verfügbar.")
    } else {
        if (timing.mode == TranscriptionMode.SONG) {
            appendLine("Audioaufbereitung · in Stimmisolierung enthalten")
            appendLine(
                "Stimmisolierung · ${timing.voiceIsolationModelLabel ?: "Modell unbekannt"} · " +
                    diagnosticsClock(timing.voiceIsolationSeconds)
            )
        } else {
            appendLine("Audioaufbereitung · ${diagnosticsClock(timing.audioPreparationSeconds)}")
            appendLine("Stimmisolierung · Aus")
        }

        when (vadSummary?.requestedMode) {
            WhisperVadMode.OFF -> appendLine("VAD / Segmentierung · Aus")
            WhisperVadMode.AUTOMATIC -> appendLine(
                "VAD / Segmentierung · ${diagnosticsClock(timing.vadSeconds)} · Automatik · " +
                    if (vadSummary.usedVad) "verwendet" else "vollständiges Audio"
            )
            WhisperVadMode.ON -> appendLine("VAD / Segmentierung · Ein · in Whisper integriert")
            null -> appendLine("VAD / Segmentierung · keine Messdaten")
        }

        appendLine(
            "Whisper · ${state.completedModel?.modelLabel ?: "Modell unbekannt"} · " +
                diagnosticsClock(timing.whisperSeconds)
        )
        appendLine("Gesamt · ${diagnosticsClock(timing.totalSeconds)}")

        if (audioDurationMs != null) {
            appendLine("Geschwindigkeit")
            appendLine(
                "Audio: ${diagnosticsClock(audioDurationMs / 1_000L)} · " +
                    "Verarbeitung: ${diagnosticsClock(timing.totalSeconds)}"
            )
            appendLine("Echtzeitfaktor: ${diagnosticsRealtimeFactor(timing.totalSeconds, audioDurationMs)}")
            timing.bottleneckLabel()?.let { appendLine("Engpass: $it") }
        }
    }

    vadSummary?.let { summary ->
        appendLine("VAD-Details")
        if (summary.measurementsAvailable) {
            appendLine(
                "Audio: ${diagnosticsClock(summary.originalDurationMs / 1_000L)} original · " +
                    "${diagnosticsClock(summary.processedDurationMs / 1_000L)} verarbeitet · " +
                    "${diagnosticsClock(summary.skippedDurationMs / 1_000L)} übersprungen"
            )
            val skippedPercent = if (summary.originalDurationMs > 0L) {
                (summary.skippedDurationMs * 100L / summary.originalDurationMs)
                    .coerceIn(0L, 100L)
            } else {
                0L
            }
            appendLine("Pauseneinsparung: $skippedPercent % · ${summary.speechRegionCount} Sprachbereiche")
        } else {
            appendLine("Audioeinsparung: In diesem Modus nicht separat vorgemessen.")
        }
        appendLine("VAD-Entscheidung: ${summary.reason}")
    }
}.trimEnd()

private fun diagnosticsVadLabel(summary: VadProcessingSummary): String = when (summary.requestedMode) {
    WhisperVadMode.OFF -> "Aus"
    WhisperVadMode.ON -> "Ein"
    WhisperVadMode.AUTOMATIC ->
        "Automatisch · ${if (summary.usedVad) "verwendet" else "vollständiges Audio"}"
}

private fun diagnosticsClock(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val minutes = safeSeconds / 60L
    val remainder = safeSeconds % 60L
    return "%02d:%02d".format(minutes, remainder)
}

private fun diagnosticsRealtimeFactor(totalSeconds: Long, audioDurationMs: Long): String {
    if (totalSeconds <= 0L || audioDurationMs <= 0L) return "–"
    val factor = totalSeconds * 1_000.0 / audioDurationMs.toDouble()
    val tenths = round(factor * 10.0).toLong().coerceAtLeast(0L)
    return "${tenths / 10},${tenths % 10}×"
}
