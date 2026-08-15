package de.matthiasennen.transcript.ui.main

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMediaImportStoreTest {
    @Test
    fun `accepts only audio and video mime types`() {
        assertTrue(isSupportedSharedMediaMime("audio/mpeg"))
        assertTrue(isSupportedSharedMediaMime("video/mp4"))
        assertFalse(isSupportedSharedMediaMime("text/plain"))
        assertFalse(isSupportedSharedMediaMime(null))
    }

    @Test
    fun `stages and commits one private media copy`() {
        val directory = Files.createTempDirectory("shared-import").toFile()
        try {
            val store = SharedMediaImportStore(directory)
            val payload = "lokale mediendaten".toByteArray()
            val staged = store.stage(
                input = ByteArrayInputStream(payload),
                requestedFileName = "../Referenz: Test.mp3",
                declaredSizeBytes = payload.size.toLong(),
                availableBytes = 128L * 1024L * 1024L
            )
            val committed = store.commit(staged)

            assertTrue(committed.isFile)
            assertTrue(committed.name.endsWith("Referenz_ Test.mp3"))
            assertArrayEquals(payload, committed.readBytes())
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".pending-") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `insufficient storage leaves no partial import`() {
        val directory = Files.createTempDirectory("shared-import-full").toFile()
        try {
            val store = SharedMediaImportStore(directory)
            runCatching {
                store.stage(
                    input = ByteArrayInputStream(ByteArray(32)),
                    requestedFileName = "probe.wav",
                    declaredSizeBytes = 32L,
                    availableBytes = 32L
                )
            }.onSuccess {
                throw AssertionError("Speichermangel muss den Import ablehnen.")
            }.onFailure {
                assertTrue(it is IOException)
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
