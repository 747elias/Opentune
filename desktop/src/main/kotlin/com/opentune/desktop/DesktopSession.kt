package com.opentune.desktop

import com.opentune.data.SavedFile
import com.opentune.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object DesktopSession {
    @Serializable
    private data class Playback(val queue: List<Song> = emptyList(), val index: Int = 0, val position: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val store by lazy { SavedFile(appContext.filesDir.resolve("queue.json"), "DesktopSession") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saved = Playback()

    fun start() {
        saved = store.read(Playback()) { json.decodeFromString<Playback>(it) }
        Player.restore(saved.queue, saved.index, saved.position)
        scope.launch {
            while (true) {
                delay(3_000)
                flush()
            }
        }
    }

    @Synchronized
    fun flush() {
        val queue = Player.queue.value
        val index = Player.index.value
        val position = Player.positionMs()
        if (Player.queue.value !== queue || Player.index.value != index) return
        val state = Playback(queue, index, position)
        if (state == saved) return
        runCatching { store.write(json.encodeToString(state)) }.onSuccess { saved = state }
    }
}
