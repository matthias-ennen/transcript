package de.matthiasennen.transcript.download

import org.junit.Assert.assertEquals
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
    fun combinesExistingAndRemainingBytes() {
        assertEquals(1_000L, totalDownloadBytes(250L, 750L))
        assertEquals(0L, totalDownloadBytes(250L, 0L))
    }
}
