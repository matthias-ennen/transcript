package de.matthiasennen.transcript.transcription

/** Guards the required decoder -> model -> decoder resource sequence. */
internal class SequentialTranscriptionResourceGuard {
    private enum class Phase { IDLE, DECODING, INFERENCE }
    private var phase = Phase.IDLE

    fun beginDecoding() {
        check(phase == Phase.IDLE) { "Decoder und Whisper-Modell dürfen nicht gleichzeitig aktiv sein." }
        phase = Phase.DECODING
    }

    fun endDecoding() {
        check(phase == Phase.DECODING) { "Der Decoder war nicht aktiv." }
        phase = Phase.IDLE
    }

    fun beginInference() {
        check(phase == Phase.IDLE) { "Whisper darf erst nach Freigabe des Decoders geladen werden." }
        phase = Phase.INFERENCE
    }

    fun endInference() {
        check(phase == Phase.INFERENCE) { "Das Whisper-Modell war nicht aktiv." }
        phase = Phase.IDLE
    }
}
