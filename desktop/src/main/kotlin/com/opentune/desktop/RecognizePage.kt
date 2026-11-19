package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.recognize.Shazam
import com.opentune.data.recognize.Signature
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object DesktopRecognition {
    private const val sampleRate = 16_000
    private const val secondsToCapture = 10
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    private val prefs by lazy { appContext.getSharedPreferences("recognized", 0) }
    private val _found = MutableStateFlow(runCatching {
        Json.decodeFromString<List<Match>>(prefs.getString("history", "[]") ?: "[]")
    }.getOrDefault(emptyList()))
    val found = _found.asStateFlow()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()
    private var capture: Capture? = null

    @Serializable
    data class Match(val title: String, val artist: String, val cover: String?, val foundAt: Long)

    sealed interface State {
        data object Idle : State
        data class Listening(val progress: Float, val level: Float) : State
        data object Matching : State
        data object Found : State
        data object NoMatch : State
        data class Error(val message: String) : State
    }

    private class Capture {
        var job: Job? = null
        var line: TargetDataLine? = null
    }

    @Synchronized
    fun start() {
        if (capture != null) return
        val active = Capture()
        capture = active
        _state.value = State.Listening(0f, 0f)
        active.job = scope.launch {
            try {
                val signature = record(active)
                ensureActive()
                show(active, State.Matching)
                val match = if (signature.peakCount > 0) Shazam.lookup(signature.encode(), signature.durationMs) else null
                ensureActive()
                if (match == null) show(active, State.NoMatch)
                else remember(active, Match(match.title, match.artist, match.coverUrl, match.foundAt))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                show(active, State.Error(error.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "Microphone or recognition service unavailable."))
            } finally {
                synchronized(this@DesktopRecognition) { if (capture === active) capture = null }
            }
        }
    }

    private fun record(active: Capture): Signature {
        val line = AudioSystem.getTargetDataLine(format)
        val signature = Signature()
        try {
            line.open(format)
            synchronized(this) {
                if (capture !== active) throw CancellationException()
                active.line = line
                line.start()
            }
            val bytes = ByteArray(sampleRate / 5)
            val samples = ShortArray(bytes.size / 2)
            while (signature.sampleCount < sampleRate * secondsToCapture) {
                val length = line.read(bytes, 0, bytes.size) / 2
                check(length > 0) { "The microphone stopped responding." }
                var sum = 0.0
                for (i in 0 until length) {
                    val sample = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                    samples[i] = sample
                    sum += sample * sample.toDouble()
                }
                signature.add(samples, length)
                show(active, State.Listening(
                    (signature.durationMs / (secondsToCapture * 1000f)).coerceIn(0f, 1f),
                    (sqrt(sum / length) / 6000).toFloat().coerceIn(0f, 1f),
                ))
            }
            return signature
        } finally {
            line.close()
            synchronized(this) { if (active.line === line) active.line = null }
        }
    }

    fun stop() {
        val active = synchronized(this) {
            val active = capture ?: return
            capture = null
            _state.value = State.Idle
            active
        }
        active.job?.cancel()
        active.line?.close()
    }

    @Synchronized
    private fun show(active: Capture, value: State) {
        if (capture === active) _state.value = value
    }

    @Synchronized
    private fun remember(active: Capture, match: Match) {
        if (capture !== active) return
        save((listOf(match) + _found.value.filterNot { it.title == match.title && it.artist == match.artist }).take(30))
        _state.value = State.Found
    }

    @Synchronized
    fun forget(match: Match) = save(_found.value - match)

    private fun save(values: List<Match>) {
        prefs.edit().putString("history", Json.encodeToString(values)).apply()
        _found.value = values
    }
}

@Composable
internal fun RecognizePage() {
    val state by DesktopRecognition.state.collectAsState()
    val found by DesktopRecognition.found.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val busy = state is DesktopRecognition.State.Listening || state == DesktopRecognition.State.Matching
    DisposableEffect(Unit) { onDispose { DesktopRecognition.stop() } }
    ScrollPage("What's this song?", "Use your microphone to identify music playing nearby.") {
        item {
            Column(Modifier.padding(horizontal = GUTTER), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val current = state) {
                    is DesktopRecognition.State.Listening -> {
                        Text("Listening…")
                        LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                        LinearProgressIndicator(progress = { current.level }, modifier = Modifier.fillMaxWidth())
                    }
                    DesktopRecognition.State.Matching -> Text("Looking for a match…")
                    DesktopRecognition.State.NoMatch -> Text("No match found. Try again closer to the music.")
                    is DesktopRecognition.State.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                if (busy) OutlinedButton(onClick = DesktopRecognition::stop) { Text("Cancel") }
                else Button(onClick = { status = null; DesktopRecognition.start() }) { Text("Listen") }
                status?.let { Text(it) }
            }
        }
        if (found.isNotEmpty()) item {
            Text("Found lately", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = GUTTER))
        }
        items(found.size) { index ->
            val match = found[index]
            Row(Modifier.fillMaxWidth().padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cover(match.cover, 44.dp, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(match.title, style = MaterialTheme.typography.titleMedium)
                    Text(match.artist, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = {
                    status = "Finding ${match.title} on YouTube Music…"
                    scope.launch {
                        val songs = runCatching { Pages.search("${match.title} ${match.artist}") }.getOrDefault(emptyList())
                        val song = songs.firstOrNull { it.title.equals(match.title, true) } ?: songs.firstOrNull()
                        if (song == null) status = "No playable match found for ${match.title}."
                        else {
                            val id = Library.playlists.value.firstOrNull { it.name == "Recognized" }?.id ?: Library.createPlaylist("Recognized")
                            Library.addToPlaylist(id, song)
                            Player.playAll(listOf(song), 0)
                            status = null
                        }
                    }
                }) { Text("Play") }
                OutlinedButton(onClick = { DesktopRecognition.forget(match) }) { Text("Remove") }
            }
        }
    }
}
