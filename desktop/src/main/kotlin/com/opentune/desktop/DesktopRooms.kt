package com.opentune.desktop

import android.content.Context
import com.opentune.data.model.Song
import com.opentune.data.together.RelayPool
import com.opentune.data.together.RoomCode
import com.opentune.data.together.RoomPlayer
import com.opentune.data.together.RoomProtocol
import com.opentune.data.together.RoomProtocol.Control
import com.opentune.data.together.RoomProtocol.Phase
import com.opentune.data.together.RoomProtocol.Room
import com.opentune.data.together.RoomProtocol.VoiceClip
import com.opentune.data.together.RoomSession
import com.opentune.data.together.TalkSink
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One desktop session using the same encrypted room protocol as Android. */
internal object DesktopRooms {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { appContext.getSharedPreferences("together", Context.MODE_PRIVATE) }
    private val state = MutableStateFlow<Room?>(null)
    val room = state.asStateFlow()
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = messages.asSharedFlow()
    private val pendingInvite = MutableStateFlow<RoomCode?>(null)
    val invite = pendingInvite.asStateFlow()
    private val picks = MutableStateFlow(false)
    val picksPlayNow = picks.asStateFlow()
    @Volatile private var session: RoomSession? = null

    private val voiceMode = MutableStateFlow<RoomVoiceMode?>(null)
    val recording = voiceMode.asStateFlow()
    private val micMuted = MutableStateFlow(false)
    val callMuted = micMuted.asStateFlow()
    private val micActive = MutableStateFlow(false)
    val speaking = micActive.asStateFlow()
    private val mutedTalk by lazy { MutableStateFlow(preferences.getBoolean("talk_muted", false)) }
    val talkMuted by lazy { mutedTalk.asStateFlow() }
    private val clipId = MutableStateFlow<String?>(null)
    val playingClip = clipId.asStateFlow()
    private var capture: RoomVoiceCapture? = null
    private var voiceNote: RoomVoicePlayback? = null
    private val liveVoice = mutableMapOf<String, RoomVoicePlayback>()

    val savedName: String get() = preferences.getString("name", "").orEmpty()
    val askToJoin: Boolean get() = preferences.getBoolean("ask_to_join", true)
    val isGuest: Boolean get() = state.value?.following == true
    private val player by lazy { DesktopRoomPlayer(scope) }

    init {
        scope.launch {
            state.collect { current ->
                if (current?.phase != Phase.Live) stopRoomVoice()
            }
        }
    }

    fun showInvite(value: String): Boolean {
        val code = RoomCode.parse(value) ?: return false
        pendingInvite.value = code
        return true
    }

    fun dismissInvite() { pendingInvite.value = null }

    fun create(name: String) = start(RoomCode.generate(), name, hosting = true)

    fun join(code: RoomCode, name: String) {
        if (session?.code == code && state.value?.phase != Phase.Ended) return
        start(code, name, hosting = false)
    }

    private fun start(code: RoomCode, name: String, hosting: Boolean) {
        leave()
        dismissInvite()
        val cleanName = name.trim().take(30).ifBlank { "Someone" }
        preferences.edit().putString("name", cleanName).apply()
        picks.value = preferences.getBoolean("picks_play_now", false)
        state.value = Room(code, hosting, cleanName, askToJoin = askToJoin)
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(RoomProtocol.KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(code.tag))))
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        session = RoomSession(
            code, cleanName, hosting, player, RelayPool(RoomProtocol.RELAYS, filter),
            state, ::notice, scope, askToJoin,
            TalkSink { id, _, cfg, frames, done -> receiveTalk(id, cfg, frames, done) },
        ).also { it.start() }
    }

    fun leave() {
        stopRoomVoice()
        val active = state.value?.phase != Phase.Ended
        session?.stop(saying = active)
        session = null
        state.value = null
    }

    fun setAskToJoin(on: Boolean) {
        preferences.edit().putBoolean("ask_to_join", on).apply()
        session?.setAskToJoin(on)
    }

    fun setPicksPlayNow(on: Boolean) {
        preferences.edit().putBoolean("picks_play_now", on).apply()
        picks.value = on
    }

    fun setOpen(on: Boolean) { session?.setOpen(on) }
    fun approve(id: String) { session?.approve(id) }
    fun reject(id: String) { session?.reject(id) }
    fun remove(id: String) { session?.remove(id) }
    fun say(text: String) { session?.say(text) }

    fun setTalkMuted(muted: Boolean) {
        mutedTalk.value = muted
        preferences.edit().putBoolean("talk_muted", muted).apply()
        if (muted) {
            liveVoice.values.forEach(RoomVoicePlayback::stop)
            liveVoice.clear()
        }
    }

    fun setCallMuted(muted: Boolean) {
        micMuted.value = muted
        capture?.muted = muted
    }

    fun startVoice(mode: RoomVoiceMode) {
        val active = session ?: return
        if (capture != null || state.value?.phase != Phase.Live) return
        val recorder = RoomVoiceCapture(mode)
        capture = recorder
        voiceMode.value = mode
        micMuted.value = false
        scope.launch {
            var id = UUID.randomUUID().toString().take(12)
            var sequence = 0
            var frames = 0
            var config: ByteArray? = null
            val note = mutableListOf<ByteArray>()
            val live = mode != RoomVoiceMode.Note
            try {
                withContext(Dispatchers.IO) {
                    recorder.record(
                        speaking = { on -> scope.launch { if (capture === recorder) micActive.value = on } },
                        onPiece = { piece ->
                            scope.launch piece@{
                                if (capture !== recorder || session !== active) return@piece
                                if (sequence == 0 && piece.frames.isEmpty()) return@piece
                                piece.config?.let { config = it }
                                frames += piece.frames.size
                                if (!live) note += piece.frames
                                active.sendVoice(
                                    id, sequence++, piece.config, piece.frames, frames * 64L, piece.last, live,
                                    if (!live && piece.last) note.toList() else emptyList(), config,
                                )
                                if (piece.last) {
                                    id = UUID.randomUUID().toString().take(12)
                                    sequence = 0
                                    frames = 0
                                    config = null
                                    note.clear()
                                }
                            }
                        },
                    )
                }
            } catch (failure: Exception) {
                if (capture === recorder) notice("Couldn't use the microphone: ${failure.message.orEmpty()}")
            } finally {
                if (capture === recorder) {
                    capture = null
                    voiceMode.value = null
                    micActive.value = false
                }
            }
        }
    }

    fun stopVoice() { capture?.stop() }

    fun playClip(clip: VoiceClip) {
        val cfg = clip.cfg ?: return
        val previous = clipId.value
        voiceNote?.stop()
        if (previous == clip.id) {
            clipId.value = null
            voiceNote = null
            return
        }
        val playback = RoomVoicePlayback(cfg)
        voiceNote = playback
        clipId.value = clip.id
        playback.receive(clip.frames, done = true)
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { playback.play() } }
            if (voiceNote === playback) {
                voiceNote = null
                clipId.value = null
                result.onFailure { notice("Couldn't play the voice note") }
            }
        }
    }

    private fun receiveTalk(id: String, cfg: ByteArray, frames: List<ByteArray>, done: Boolean) {
        if (mutedTalk.value || state.value?.phase != Phase.Live) return
        val existing = liveVoice[id]
        if (existing != null) {
            existing.receive(frames, done)
        } else if (frames.isNotEmpty() && liveVoice.size < 12) {
            val playback = RoomVoicePlayback(cfg)
            liveVoice[id] = playback
            playback.receive(frames, done)
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { playback.play() } }
                if (liveVoice[id] === playback) liveVoice.remove(id)
            }
        }
    }

    private fun stopRoomVoice() {
        capture?.stop()
        capture = null
        voiceMode.value = null
        micActive.value = false
        voiceNote?.stop()
        voiceNote = null
        clipId.value = null
        liveVoice.values.forEach(RoomVoicePlayback::stop)
        liveVoice.clear()
    }

    fun suggest(song: Song): Boolean = onGuest { it.suggest(song, now = picks.value) }
    fun intercept(control: Control): Boolean = onGuest { it.intercept(control) }

    private fun onGuest(action: (RoomSession) -> Unit): Boolean {
        val active = session ?: return false
        if (!isGuest) return false
        // Media keys can arrive off the Compose event thread.
        scope.launch { if (session === active && isGuest) action(active) }
        return true
    }

    private fun notice(text: String) { messages.tryEmit(text) }
}

private class DesktopRoomPlayer(scope: CoroutineScope) : RoomPlayer {
    override val connected = MutableStateFlow(true)
    override val queue = Player.queue
    override val currentIndex = Player.index
    override val currentSong = combine(queue, currentIndex) { songs, index -> songs.getOrNull(index) }
        .stateIn(scope, SharingStarted.Eagerly, Player.current)
    override val upNext = combine(queue, currentIndex) { songs, index -> indexAfter(index, songs.size) }
        .stateIn(scope, SharingStarted.Eagerly, indexAfter(currentIndex.value, queue.value.size))
    override val playWhenReady = Player.roomPlayWhenReady
    override val isBuffering = Player.loading
    override val seeks = Player.roomSeeks

    private fun indexAfter(index: Int, size: Int) = (index + 1 until size).toList()

    override fun currentPositionMs() = Player.positionMs()
    override fun setRate(rate: Float) = Player.setRoomRate(rate)
    override fun play() = Player.setPlayingFromRoom(true)
    override fun pause() = Player.setPlayingFromRoom(false)
    override fun seekTo(positionMs: Long) = Player.seekFromRoom(positionMs)
    override fun seekToItem(index: Int, positionMs: Long) = Player.seekToItemFromRoom(index, positionMs)
    override fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long) =
        Player.playFromRoom(songs, startIndex, startPositionMs)
    override fun replaceUpcoming(songs: List<Song>) = Player.replaceUpcomingFromRoom(songs)
    override fun addToQueue(song: Song) = Player.queueFromRoom(song)
    override fun playNow(song: Song) = Player.playNowFromRoom(song)
    override fun skipNext() = Player.nextFromRoom()
    override fun skipPrevious() = Player.previousFromRoom()
}
