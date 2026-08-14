package de.matthiasennen.transcript.transcription

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val HEARTBEAT_MAGIC = 0x54524842
private const val HEARTBEAT_VERSION = 1

internal data class WorkerHeartbeat(
    val jobId: String,
    val pid: Int,
    val workerStartedAtEpochMs: Long,
    val phase: String,
    val backend: String,
    val sectionNumber: Int,
    val heartbeatAtEpochMs: Long,
    val lastProgressAtEpochMs: Long
)

internal class WorkerHeartbeatStore(private val file: File) {
    private val temporary = File(file.parentFile, "${file.name}.tmp")

    fun read(): WorkerHeartbeat? = runCatching {
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            check(input.readInt() == HEARTBEAT_MAGIC)
            check(input.readInt() == HEARTBEAT_VERSION)
            WorkerHeartbeat(
                jobId = input.readUTF(),
                pid = input.readInt(),
                workerStartedAtEpochMs = input.readLong(),
                phase = input.readUTF(),
                backend = input.readUTF(),
                sectionNumber = input.readInt(),
                heartbeatAtEpochMs = input.readLong(),
                lastProgressAtEpochMs = input.readLong()
            )
        }
    }.getOrNull()

    @Synchronized
    fun write(value: WorkerHeartbeat) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(HEARTBEAT_MAGIC)
            output.writeInt(HEARTBEAT_VERSION)
            output.writeUTF(value.jobId)
            output.writeInt(value.pid)
            output.writeLong(value.workerStartedAtEpochMs)
            output.writeUTF(value.phase)
            output.writeUTF(value.backend)
            output.writeInt(value.sectionNumber)
            output.writeLong(value.heartbeatAtEpochMs)
            output.writeLong(value.lastProgressAtEpochMs)
        }
        try {
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear() {
        temporary.delete()
        file.delete()
    }
}

internal fun workerHeartbeatStore(contextFilesDirectory: File) =
    WorkerHeartbeatStore(File(contextFilesDirectory, "transcription-worker-heartbeat.bin"))
