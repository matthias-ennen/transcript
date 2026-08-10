package de.matthiasennen.transcript.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

data class AiNativeDevice(
    val index: Int,
    val name: String,
    val description: String,
    val type: String,
    val freeBytes: Long,
    val totalBytes: Long
)

data class AiHardwareSnapshot(
    val processorCount: Int,
    val supportedAbis: String,
    val coreMaximumFrequenciesKhz: List<Long>,
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val lowMemory: Boolean,
    val appPssBytes: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalStatus: Int,
    val kleidiAiCompiled: Boolean,
    val vulkanCompiled: Boolean,
    val neon: Boolean,
    val fp16Vector: Boolean,
    val dotProduct: Boolean,
    val int8Matrix: Boolean,
    val sve: Boolean,
    val sveBytes: Int,
    val sme: Boolean,
    val sme2: Boolean,
    val devices: List<AiNativeDevice>
) {
    val vulkanDevices: List<AiNativeDevice>
        get() = devices.filter { it.type == "GPU" || it.type == "Integrierte GPU" }
}

object AiHardwareProbe {
    fun read(context: Context): AiHardwareSnapshot {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val powerManager = context.getSystemService(PowerManager::class.java)
        val native = runCatching { JSONObject(LocalAiEngine.runtimeCapabilitiesJson()) }
            .getOrElse { JSONObject() }
        val cpu = native.optJSONObject("cpu") ?: JSONObject()
        val devicesJson = native.optJSONArray("devices")
        val devices = buildList {
            if (devicesJson != null) {
                for (index in 0 until devicesJson.length()) {
                    val device = devicesJson.optJSONObject(index) ?: continue
                    add(
                        AiNativeDevice(
                            index = device.optInt("index", index),
                            name = device.optString("name", "Unbekannt"),
                            description = device.optString("description", "Unbekannt"),
                            type = device.optString("type", "Unbekannt"),
                            freeBytes = device.optLong("freeBytes", 0L),
                            totalBytes = device.optLong("totalBytes", 0L)
                        )
                    )
                }
            }
        }
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return AiHardwareSnapshot(
            processorCount = processors,
            supportedAbis = Build.SUPPORTED_ABIS.joinToString(),
            coreMaximumFrequenciesKhz = (0 until processors).map { core ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toLong()
                }.getOrDefault(0L)
            },
            availableMemoryBytes = memory.availMem,
            totalMemoryBytes = memory.totalMem,
            lowMemory = memory.lowMemory,
            appPssBytes = Debug.getPss().toLong() * 1_024L,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = charging,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus
            } else {
                0
            },
            kleidiAiCompiled = native.optBoolean("kleidiAiCompiled", false),
            vulkanCompiled = native.optBoolean("vulkanCompiled", false),
            neon = cpu.optBoolean("neon", false),
            fp16Vector = cpu.optBoolean("fp16Vector", false),
            dotProduct = cpu.optBoolean("dotProduct", false),
            int8Matrix = cpu.optBoolean("int8Matrix", false),
            sve = cpu.optBoolean("sve", false),
            sveBytes = cpu.optInt("sveBytes", 0),
            sme = cpu.optBoolean("sme", false),
            sme2 = cpu.optBoolean("sme2", false),
            devices = devices
        )
    }

    fun checkMemory(
        context: Context,
        modelFile: File,
        configuration: LocalAiConfiguration
    ) {
        val snapshot = read(context)
        val reserveBytes = configuration.minimumFreeMemoryMb.toLong() * 1_048_576L
        val allowedBytes = snapshot.totalMemoryBytes * configuration.maximumMemoryPercent / 100L
        val estimatedBytes = modelFile.length() + reserveBytes
        require(snapshot.availableMemoryBytes >= reserveBytes) {
            "Die eingestellte RAM-Reserve von ${configuration.minimumFreeMemoryMb} MB ist nicht verfügbar."
        }
        require(estimatedBytes <= allowedBytes || configuration.loadMode == LocalAiLoadMode.MMAP) {
            "Modell und RAM-Reserve überschreiten die eingestellte Speichergrenze von ${configuration.maximumMemoryPercent} %."
        }
        if (configuration.backend == LocalAiBackend.VULKAN || configuration.backend == LocalAiBackend.HYBRID) {
            val device = snapshot.vulkanDevices.getOrNull(configuration.gpuDeviceIndex)
            if (device != null && device.totalBytes > 0L) {
                val layerPercent = when {
                    configuration.backend == LocalAiBackend.VULKAN || configuration.gpuLayers < 0 -> 100
                    configuration.gpuLayerPercent > 0 -> configuration.gpuLayerPercent
                    else -> 50
                }
                val estimatedGpuBytes = modelFile.length() * layerPercent / 100L
                val allowedGpuBytes = device.totalBytes * configuration.maximumVulkanMemoryPercent / 100L
                require(estimatedGpuBytes <= allowedGpuBytes) {
                    "Geschätzte GPU-Auslagerung überschreitet die Vulkan-Speichergrenze von ${configuration.maximumVulkanMemoryPercent} %."
                }
                if (device.freeBytes > 0L) {
                    require(estimatedGpuBytes <= device.freeBytes) {
                        "Für die gewählte GPU-Auslagerung ist nicht genügend freier Vulkan-Speicher verfügbar."
                    }
                }
            }
        }
    }
}

fun thermalStatusLabel(status: Int): String = when (status) {
    0 -> "Keine Drosselung"
    1 -> "Leicht"
    2 -> "Mittel"
    3 -> "Stark"
    4 -> "Kritisch"
    5 -> "Notfall"
    6 -> "Abschaltung"
    else -> "Unbekannt ($status)"
}
