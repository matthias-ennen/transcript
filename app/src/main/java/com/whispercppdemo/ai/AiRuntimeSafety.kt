package de.matthiasennen.transcript.ai

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Applies the same local memory and thermal safeguards to every user-facing AI task.
 * The callback keeps service-specific diagnostics outside this shared runtime policy.
 */
internal fun guardedAiConfiguration(
    context: Context,
    modelFile: File,
    model: AiModel,
    cancellationRequested: () -> Boolean = { false },
    onDiagnostic: (String) -> Unit = {}
): LocalAiConfiguration {
    val stored = AiPerformancePreferences(context).load(model)
    val hardware = AiHardwareProbe.read(context)
    check(hardware.thermalStatus < stored.thermalStopStatus) {
        "Das Gerät ist für den KI-Start zu warm (${thermalStatusLabel(hardware.thermalStatus)})."
    }

    val effective = if (hardware.thermalStatus >= stored.thermalThrottleStatus) {
        if (stored.coolingPauseSeconds > 0) {
            onDiagnostic("Wärmeschutz: ${stored.coolingPauseSeconds} s Abkühlpause vor dem KI-Start.")
            SystemClock.sleep(stored.coolingPauseSeconds * 1_000L)
            if (cancellationRequested()) throw InterruptedException("Lokale KI wurde abgebrochen.")
        }
        val reducedGpuLayers = if (stored.backend == LocalAiBackend.HYBRID) {
            (stored.gpuLayers - stored.gpuLayersReducedPerStep).coerceAtLeast(0)
        } else {
            0
        }
        val reducedBackend = if (reducedGpuLayers > 0) LocalAiBackend.HYBRID else LocalAiBackend.CPU
        onDiagnostic(
            "Wärmeschutz aktiv: CPU-Threads reduziert; GPU-Schichten ${stored.gpuLayers} → $reducedGpuLayers."
        )
        stored.copy(
            generationThreads = stored.throttledThreads,
            promptThreads = stored.throttledThreads,
            backend = reducedBackend,
            gpuLayers = reducedGpuLayers,
            gpuLayerPercent = 0
        ).normalized()
    } else {
        stored
    }

    if (hardware.thermalStatus >= stored.thermalWarningStatus) {
        onDiagnostic("Wärmehinweis: ${thermalStatusLabel(hardware.thermalStatus)}.")
    }
    AiHardwareProbe.checkMemory(context, modelFile, effective)
    return effective
}

internal fun ensureAiRuntimeCanContinue(
    context: Context,
    configuration: LocalAiConfiguration
) {
    val thermalStatus = AiHardwareProbe.read(context).thermalStatus
    check(thermalStatus < configuration.thermalStopStatus) {
        "Wärmeschutz hat die KI bei Status ${thermalStatusLabel(thermalStatus)} beendet."
    }
}
