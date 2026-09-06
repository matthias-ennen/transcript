package de.matthiasennen.transcript.download

import de.matthiasennen.transcript.ui.main.WhisperModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDownloadServiceTest {
    @Test
    fun parsesContentRangeStart() {
        assertEquals(12_345L, contentRangeStart("bytes 12345-19999/20000"))
        assertEquals(0L, contentRangeStart("bytes 0-99/*"))
        assertNull(contentRangeStart("bytes */20000"))
        assertNull(contentRangeStart(null))
    }

    @Test
    fun parsesContentRangeTotal() {
        assertEquals(20_000L, contentRangeTotal("bytes */20000"))
        assertEquals(20_000L, contentRangeTotal("bytes 12345-19999/20000"))
        assertNull(contentRangeTotal("bytes 0-99/*"))
        assertNull(contentRangeTotal(null))
    }

    @Test
    fun combinesExistingAndRemainingBytes() {
        assertEquals(1_000L, totalDownloadBytes(250L, 750L))
        assertEquals(0L, totalDownloadBytes(250L, 0L))
    }

    @Test
    fun whisperModelsUseMinimumSizeAndChecksumInsteadOfExactByteCount() {
        val model = WhisperModel.SMALL_Q5_1
        val directory = File("build/test-whisper-models")
        val partial = File(directory, "${model.fileName}.part")

        val download = whisperVerifiedModelDownload(model, directory, partial)

        assertFalse(download.exactBytes)
        assertEquals(model.minimumBytes, download.minimumBytes)
        assertEquals(model.sha256, download.sha256)
    }
}
