package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.WhisperVadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptionStateStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `running state roundtrip preserves progress diagnostics and checkpoint segments`() {
        val store = newStore()
        val envelope = PersistedTranscriptionState(
            workerStartedAtEpochMs = 1_700_000_000_000L,
            updatedAtEpochMs = 1_700_000_001_000L,
            state = TranscriptionState.Running(
                fileName = "Gespräch.m4a",
                model = WhisperModel.LARGE_V3_Q5_0,
                progress = 0.42f,
                sectionNumber = 2,
                sectionCount = 5,
                startedAtEpochMs = 1_700_000_000_100L,
                elapsedSeconds = 901L,
                status = "Abschnitt 2 wird transkribiert",
                activityDetail = "Whisper läuft",
                diagnostics = listOf("Decoder freigegeben", "Modell geladen"),
                committedSegments = listOf(WhisperSegment(10L, 500L, "Hallo")),
                detectedLanguage = "de"
            )
        )

        store.write(envelope)

        assertEquals(envelope, store.read())
    }

    @Test
    fun `all terminal states survive process boundary`() {
        val store = newStore()
        val states = listOf(
            TranscriptionState.Completed(
                "Datei.wav", WhisperModel.BASE,
                listOf(WhisperSegment(0L, 1_000L, "Text")), "de", 12L,
                VadProcessingSummary(
                    requestedMode = WhisperVadMode.AUTOMATIC,
                    usedVad = true,
                    originalDurationMs = 20_000L,
                    processedDurationMs = 15_000L,
                    skippedDurationMs = 5_000L,
                    speechRegionCount = 4,
                    reason = "klare Pausen"
                )
            ),
            TranscriptionState.Cancelled("Datei.wav"),
            TranscriptionState.Failed(
                "Datei.wav", "Nativer Fehler", true,
                listOf(WhisperSegment(0L, 1_000L, "Gesichert"))
            )
        )

        states.forEachIndexed { index, state ->
            val envelope = PersistedTranscriptionState(100L, 200L + index, state)
            store.write(envelope)
            assertEquals(envelope, store.read())
        }
    }

    @Test
    fun `corrupt state is rejected and removed`() {
        val file = temporaryFolder.newFile("state.bin")
        file.writeText("ungültig")
        val store = TranscriptionStateStore(file)

        assertNull(store.read())
        assertFalse(file.exists())
    }

    private fun newStore(): TranscriptionStateStore {
        val file = temporaryFolder.newFile("state-${System.nanoTime()}.bin")
        assertTrue(file.delete())
        return TranscriptionStateStore(file)
    }
}
