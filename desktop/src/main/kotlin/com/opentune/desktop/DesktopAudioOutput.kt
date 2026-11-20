package com.opentune.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Output devices the JVM can open on this computer. */
internal object DesktopAudioOutput {
    data class Device(val id: String, val name: String, val info: Mixer.Info)
    data class Route(val device: String?, val format: AudioFormat)

    private val preferences by lazy { appContext.getSharedPreferences("audio-output", 0) }
    private val _selected by lazy { MutableStateFlow(preferences.getString("device", null)) }
    val selected by lazy { _selected.asStateFlow() }

    fun devices(): List<Device> {
        val counts = HashMap<List<String>, Int>()
        return AudioSystem.getMixerInfo().mapNotNull { info ->
            // OpenJDK also lists a synthetic macOS mixer for the system default.
            if (info.name == "Default Audio Device" && info.description == info.name) return@mapNotNull null
            val playback = runCatching {
                AudioSystem.getMixer(info).sourceLineInfo.any { SourceDataLine::class.java.isAssignableFrom(it.lineClass) }
            }.getOrDefault(false)
            if (!playback) return@mapNotNull null
            val identity = listOf(info.name, info.vendor, info.description, info.version)
            val occurrence = (counts[identity] ?: 0) + 1
            counts[identity] = occurrence
            val id = (identity + occurrence.toString()).joinToString("\u001f")
            Device(id, if (occurrence == 1) info.name else "${info.name} ($occurrence)", info)
        }
    }

    fun select(device: String?) {
        if (device == _selected.value) return
        preferences.edit().putString("device", device).apply()
        _selected.value = device
        Player.outputChanged(localOnly = true)
    }
}
