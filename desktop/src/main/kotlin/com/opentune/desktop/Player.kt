package com.opentune.desktop

import com.opentune.data.Http
import com.opentune.data.audiobooks.Audiobooks
import com.opentune.data.library.ResumePoints
import com.opentune.data.local.LocalMusic
import com.opentune.data.podcasts.Podcasts
import com.opentune.data.radio.Radio
import com.opentune.data.together.RoomProtocol
import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.PlaybackSettings
import com.opentune.data.subsonic.Subsonic
import com.opentune.playback.SleepTimer
import com.opentune.playback.mix.MixPlan
import com.opentune.playback.mix.automixPlan
import com.opentune.playback.mix.bassCuts
import com.opentune.playback.mix.blendGains
import com.opentune.playback.mix.crossfadePlan
import com.opentune.playback.mix.levelTrims
import java.util.concurrent.ConcurrentHashMap
import javafx.application.Platform
import javafx.scene.media.EqualizerBand
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/** Owns the current track and the audio engines used for source upgrades and transitions. */
object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** When the sleep timer stops playback: at a time, or when the song ends. */
    sealed interface Sleep {
        data class At(val epochMs: Long, val minutes: Int) : Sleep
        data object EndOfSong : Sleep
    }

    /** What happens at the end: stop (or autoplay on), go round the queue again, or the song again. */
    enum class Repeat { OFF, ALL, ONE; fun next() = entries[(ordinal + 1) % entries.size] }

    /** What the song is playing from and in what form, shown under it. */
    data class Quality(val source: String, val detail: String, val lossless: Boolean = false)

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _volume = MutableStateFlow(Library.volume())
    val volume: StateFlow<Double> = _volume.asStateFlow()
    private val _quality = MutableStateFlow<Quality?>(null)
    val quality: StateFlow<Quality?> = _quality.asStateFlow()

    internal data class QualityCheck(val message: String, val busy: Boolean = false)
    private val _qualityCheck = MutableStateFlow<QualityCheck?>(null)
    internal val qualityCheck = _qualityCheck.asStateFlow()

    internal fun dismissQualityCheck(check: QualityCheck) {
        _qualityCheck.compareAndSet(check, null)
    }

    internal data class SignalPath(val decoder: String, val codec: String?, val route: DesktopAudioOutput.Route?)
    private val _signalPath = MutableStateFlow<SignalPath?>(null)
    internal val signalPath = _signalPath.asStateFlow()

    /** The song's id and its exact length once the player knows it, for lyrics to match on. */
    private val _durationOf = MutableStateFlow("" to 0L)
    val durationOf: StateFlow<Pair<String, Long>> = _durationOf.asStateFlow()

    val current: Song? get() = _queue.value.getOrNull(_index.value)

    private val _repeat = MutableStateFlow(Repeat.OFF)
    val repeat: StateFlow<Repeat> = _repeat.asStateFlow()
    enum class Shuffle { OFF, ON, SMART }
    private val _shuffle = MutableStateFlow(Shuffle.OFF)
    val shuffle = _shuffle.asStateFlow()
    /** The queue's order before shuffling, to go back to. */
    private var unshuffled: List<Song>? = null

    fun cycleRepeat() = synchronized(gate) { _repeat.value = _repeat.value.next() }

    /** Shuffles what's after the song playing, or puts the queue back in its order. */
    fun toggleShuffle() = synchronized(gate) {
        if (DesktopRooms.isGuest) return@synchronized
        val q = _queue.value
        val now = current
        val original = unshuffled
        if (_shuffle.value == Shuffle.ON && AppSettings.playback.value.smartShuffle && q.size > 4 && q.any { musicTrack(it) }) {
            _shuffle.value = Shuffle.SMART
            val at = _index.value
            val seed = q.getOrNull(at)?.takeIf(::musicTrack) ?: q.first(::musicTrack)
            scope.launch {
                val found = DesktopRecommendations.after(seed)
                synchronized(gate) {
                    if (_shuffle.value != Shuffle.SMART || _queue.value !== q || _index.value != at) return@synchronized
                    val have = q.mapTo(HashSet()) { it.videoId }
                    val picks = DesktopHidden.suggestions(found).filter { musicTrack(it) && Pages.allowed(it) && have.add(it.videoId) }.iterator()
                    _queue.value = q.take(at + 1) + buildList {
                        q.drop(at + 1).forEachIndexed { i, song ->
                            add(song)
                            if ((i + 1) % 4 == 0 && picks.hasNext()) add(picks.next().copy(smartShuffle = true))
                        }
                    }
                }
            }
        } else if (_shuffle.value != Shuffle.OFF && original != null) {
            val originalIds = original.mapTo(HashSet()) { it.videoId }
            val currentIds = q.mapTo(HashSet()) { it.videoId }
            val restored = original.filter { it.videoId in currentIds } + q.filter { it.videoId !in originalIds && (!it.smartShuffle || it == now) }
            _queue.value = restored
            _index.value = now?.let { s -> restored.indexOfFirst { it.videoId == s.videoId } }?.takeIf { it >= 0 } ?: 0
            unshuffled = null
            _shuffle.value = Shuffle.OFF
        } else if (q.isNotEmpty()) {
            unshuffled = q
            val at = _index.value.coerceIn(q.indices)
            _queue.value = listOf(q[at]) + q.filterIndexed { i, _ -> i != at }.shuffled()
            _index.value = 0
            _shuffle.value = Shuffle.ON
        }
        Unit
    }

    private val _sleep = MutableStateFlow<Sleep?>(null)
    val sleep: StateFlow<Sleep?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null
    /** 1 normally; less while the sleep timer winds down. */
    @Volatile private var fade = 1.0

    private enum class Decoder { FFMPEG, JAVA_FX, FLAC, CAST }

    private class Source(
        val address: String,
        val decoder: Decoder,
        val quality: () -> Quality,
        val loudness: () -> Double? = { null },
        val readFlac: (() -> java.io.InputStream)? = null,
        val fallback: (() -> Source?)? = null,
        val cast: DesktopCast.Connection? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    private class Deck(val song: Song, val source: Source) {
        lateinit var engine: Engine
        var ready = false
        var started = false
        var completed = false
        var mixGain = 1.0
        var requestedUpgrade = false
    }

    private data class MixKey(val generation: Int, val next: Int, val songId: String, val durationMs: Long, val mode: Int)

    private class Cue(val key: MixKey, var plan: MixPlan, val song: Song, val source: Source) {
        var analysis: Job? = null
        var deck: Deck? = null
        var failed = false
    }

    private class Blend(val tail: Deck, val plan: MixPlan, val from: Long, val end: Long)

    /** Audio callbacks run on the FX or decoding threads. The lock keeps each deck with its own track. */
    private val gate = Any()
    private var main: Deck? = null
    private var incoming: Deck? = null
    private var cue: Cue? = null
    @Volatile private var blend: Blend? = null
    private var generation = 0
    private var opening = false
    private var mutedByVolume = false
    private var roomRate = 1f
    private var restoredPosition = 0L
    private val _wanted = MutableStateFlow(false)
    private var wanted: Boolean
        get() = _wanted.value
        set(value) { _wanted.value = value }
    internal val roomPlayWhenReady = _wanted.asStateFlow()
    private val _roomSeeks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val roomSeeks = _roomSeeks.asSharedFlow()
    private var upgradeJob: Job? = null
    private var aheadJob: Job? = null
    private val playedInSession = HashSet<String>()
    private var lastSaved = 0L

    init {
        scope.launch {
            combine(AppSettings.sound, AppSettings.equalizer, AppSettings.playback) { _, _, _ -> }.collect {
                synchronized(gate) {
                    listOfNotNull(main, incoming, blend?.tail, cue?.deck).forEach { d ->
                        when (val e = d.engine) {
                            is FxEngine -> e.refreshEffects()
                            is FfmpegEngine -> e.refreshEffects()
                        }
                        applyNormalization(d)
                    }
                    val current = main
                    val engine = current?.engine
                    val requiresReopen = when (engine) {
                        is FlacEngine -> filteredOutput() && Ffmpeg.available
                        is FfmpegEngine -> engine.floatOutput != AppSettings.playback.value.floatOutput
                        else -> false
                    }
                    if (requiresReopen && current != null && !current.completed && blend == null) {
                        open(_index.value, current.engine.positionMs(), wanted, recordPlay = false)
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(if (blend != null) 40 else if (wanted) 120 else 500)
                synchronized(gate) {
                    if (wanted && _playing.value && System.nanoTime() - lastSaved >= 5_000_000_000L) saveProgress()
                    if (_sleep.value == Sleep.EndOfSong) fadeAtEnd()
                    tickMix()
                }
            }
        }
    }

    private val fx by lazy {
        // JavaFX's toolkit, started once, with no window of its own.
        val ready = java.util.concurrent.CountDownLatch(1)
        runCatching { Platform.startup { ready.countDown() } }.onFailure { ready.countDown() }
        Platform.setImplicitExit(false)
        ready.await()
    }

    internal fun onFx(block: () -> Unit) {
        fx
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }

    fun playAll(songs: List<Song>, start: Int) {
        if (songs.isEmpty()) return
        val at = start.coerceIn(songs.indices)
        if (DesktopRooms.suggest(songs[at])) return
        synchronized(gate) {
            setQueue(songs)
            open(at)
        }
    }

    private fun setQueue(songs: List<Song>) {
        unshuffled = null
        _shuffle.value = Shuffle.OFF
        _queue.value = songs
    }

    /** Loads [songs] without playing them, for pictures of the window (DesktopShots). */
    internal fun cue(songs: List<Song>, at: Int) = synchronized(gate) {
        _queue.value = songs
        _index.value = at
    }

    internal fun restore(songs: List<Song>, index: Int, atMs: Long) = synchronized(gate) {
        if (index !in songs.indices || main != null || wanted) return@synchronized
        setQueue(songs)
        _index.value = index
        restoredPosition = atMs.coerceAtLeast(0)
    }

    fun playAt(i: Int) {
        val song = _queue.value.getOrNull(i) ?: return
        if (!DesktopRooms.suggest(song)) synchronized(gate) { open(i) }
    }

    internal fun dropHiddenRecommendations() = synchronized(gate) {
        if (!DesktopRooms.isGuest) {
            val hidden = DesktopHidden.list.value
            filterUpcoming { it.smartShuffle && hidden.hides(it) }
        }
    }

    internal fun dropDisliked(id: String) = synchronized(gate) {
        val isCurrent = current?.videoId == id
        if (!DesktopRooms.isGuest) filterUpcoming { it.videoId == id }
        if (isCurrent && !DesktopRooms.intercept(RoomProtocol.Control.Next)) {
            val next = nextIndex(automatic = false)
                ?.takeIf { _queue.value.getOrNull(it)?.videoId != id }
            if (next != null) open(next) else setPlayState(false)
        }
    }

    private fun filterUpcoming(remove: (Song) -> Boolean) {
        val q = _queue.value
        val split = (_index.value + 1).coerceIn(0, q.size)
        val upcoming = q.drop(split)
        val allowed = upcoming.filterNot(remove)
        if (upcoming != allowed) {
            cancelCue()
            _queue.value = q.take(split) + allowed
        }
    }

    fun toggle() = setPlaying(!wanted)

    fun setPlaying(play: Boolean) {
        if (play == wanted || DesktopRooms.intercept(RoomProtocol.Control.PlayPause)) return
        synchronized(gate) { setPlayState(play) }
    }

    private fun setPlayState(play: Boolean, fromVolume: Boolean = false) {
        if (!fromVolume) mutedByVolume = false
        if (!play) saveProgress()
        wanted = play
        val d = main
        if (play && (d == null || d.completed) && !opening) {
            if (current == null) {
                wanted = false
                return
            }
            open(_index.value, position = restoredPosition.takeIf { it > 0 })
            return
        }
        listOfNotNull(d, incoming, blend?.tail).forEach { if (play) it.engine.play() else it.engine.pause() }
        _playing.value = play && d?.started == true
    }

    fun next() {
        if (!DesktopRooms.intercept(RoomProtocol.Control.Next)) nextFromRoom()
    }

    internal fun nextFromRoom() = synchronized(gate) { advance(automatic = false) }

    private fun advance(automatic: Boolean) {
        val i = _index.value + 1
        val next = nextIndex(automatic)
        when {
            next != null -> open(next)
            !DesktopRooms.isGuest && AppSettings.playback.value.autoplay && current?.let(::musicTrack) == true ->
                extendThen(i)
            else -> setPlayState(false)
        }
    }

    private fun nextIndex(automatic: Boolean): Int? = when {
        automatic && _repeat.value == Repeat.ONE -> _index.value.takeIf { it in _queue.value.indices }
        _index.value + 1 in _queue.value.indices -> _index.value + 1
        _repeat.value == Repeat.ALL && _queue.value.isNotEmpty() -> 0
        else -> null
    }

    /** Stops playback in [minutes], the last few turning the volume down; null turns the timer off. */
    fun sleepIn(minutes: Int?): Unit = synchronized(gate) {
        setWindDown(0f)
        if (minutes == null) {
            sleepJob?.cancel()
            sleepJob = null
            _sleep.value = null
        } else {
            scheduleSleep(Sleep.At(System.currentTimeMillis() + minutes * 60_000L, minutes))
        }
    }

    fun extendSleep() = synchronized(gate) {
        val timer = _sleep.value as? Sleep.At ?: return@synchronized
        scheduleSleep(Sleep.At(timer.epochMs + 5 * 60_000L, timer.minutes + 5))
    }

    private fun scheduleSleep(timer: Sleep.At) {
        sleepJob?.cancel()
        _sleep.value = timer
        sleepJob = scope.launch {
            val window = SleepTimer.fadeMsFor(timer.minutes * 60_000L)
            while (true) {
                val nextCheck = synchronized(gate) {
                    if (_sleep.value !== timer) return@launch
                    val left = timer.epochMs - System.currentTimeMillis()
                    if (left <= 0) {
                        setPlayState(false)
                        setWindDown(0f)
                        _sleep.value = null
                        sleepJob = null
                        return@launch
                    }
                    val windingDown = AppSettings.playback.value.sleepWindDown && left < window
                    setWindDown(if (windingDown) 1f - left.toFloat() / window else 0f)
                    minOf(left, if (windingDown) 200L else 1_000L)
                }
                delay(nextCheck)
            }
        }
    }

    /** Stops playback when the song playing ends. */
    fun sleepAtEndOfSong() = synchronized(gate) {
        sleepJob?.cancel()
        sleepJob = null
        setWindDown(0f)
        _sleep.value = Sleep.EndOfSong
    }

    private fun fadeAtEnd() {
        val left = main?.engine?.let { engine ->
            engine.durationMs().takeIf { it > 0 }?.minus(engine.positionMs())
        } ?: -1L
        val fadeWindow = 15_000L
        val windingDown = AppSettings.playback.value.sleepWindDown && left in 0 until fadeWindow
        setWindDown(if (windingDown) 1f - left.toFloat() / fadeWindow else 0f)
    }

    private fun setWindDown(fraction: Float) {
        SleepTimer.setWindDown(fraction)
        val gain = SleepTimer.gainAt(fraction).toDouble()
        if (fade == gain) return
        fade = gain
        applyVolume()
    }

    private fun applyVolume() {
        val v = _volume.value * fade
        listOfNotNull(main, incoming, blend?.tail, cue?.deck).forEach { it.engine.setVolume(v * it.mixGain) }
    }

    /** Back to the start of the song a few seconds in; before that, the song before. */
    fun previous() {
        if (!DesktopRooms.intercept(RoomProtocol.Control.Previous)) previousFromRoom()
    }

    internal fun previousFromRoom() = synchronized(gate) {
        if (positionMs() > 4_000) seekInternal(0)
        else if (_index.value > 0) open(_index.value - 1)
    }

    fun seekTo(ms: Long) {
        if (!DesktopRooms.intercept(RoomProtocol.Control.Seek(ms))) seekFromRoom(ms)
    }

    internal fun seekFromRoom(ms: Long) = synchronized(gate) { seekInternal(ms) }

    private fun seekInternal(ms: Long) {
        val d = main ?: return
        if (Radio.isRadio(d.song.videoId) || d.completed) return
        resetMix()
        val end = d.engine.durationMs()
        val at = if (end > 0) ms.coerceIn(0, end) else ms.coerceAtLeast(0)
        d.engine.seek(at)
        incoming?.engine?.seek(at)
        _roomSeeks.tryEmit(Unit)
    }

    fun setVolume(v: Double) = synchronized(gate) {
        _volume.value = v.coerceIn(0.0, 1.0)
        Library.setVolume(_volume.value)
        applyVolume()
        if (_volume.value == 0.0 && wanted && AppSettings.playback.value.pauseAtZeroVolume && !DesktopRooms.isGuest) {
            mutedByVolume = true
            setPlayState(false, fromVolume = true)
        } else if (_volume.value > 0.0 && mutedByVolume) {
            mutedByVolume = false
            setPlayState(true, fromVolume = true)
        }
    }

    fun positionMs(): Long = synchronized(gate) { main?.engine?.positionMs() ?: restoredPosition }
    fun durationMs(): Long = synchronized(gate) {
        main?.engine?.durationMs()?.takeIf { it > 0 }
            ?: current?.durationText?.let(::seconds)?.times(1_000)?.toLong() ?: 0L
    }

    internal fun playFromRoom(songs: List<Song>, at: Int, seekMs: Long) = synchronized(gate) {
        setQueue(songs)
        open(at, seekMs)
    }

    internal fun seekToItemFromRoom(index: Int, ms: Long) = synchronized(gate) {
        if (index == _index.value) seekInternal(ms) else open(index, ms)
    }

    internal fun setPlayingFromRoom(play: Boolean) = synchronized(gate) { setPlayState(play) }

    internal fun replaceUpcomingFromRoom(songs: List<Song>) = synchronized(gate) {
        _queue.value = _queue.value.take(_index.value + 1) + songs
    }

    internal fun queueFromRoom(song: Song) = synchronized(gate) { _queue.value = _queue.value + song }

    internal fun playNowFromRoom(song: Song) = synchronized(gate) {
        val at = _index.value + 1
        val q = _queue.value
        _queue.value = q.take(at) + song + q.drop(at)
        open(at, position = 0)
    }

    internal fun setRoomRate(rate: Float): Unit = synchronized(gate) {
        roomRate = rate.coerceIn(0.96f, 1.04f)
        main?.engine?.setRateFactor(roomRate)
        incoming?.engine?.setRateFactor(roomRate)
    }

    private fun saveProgress() {
        lastSaved = System.nanoTime()
        val d = main ?: return
        if (!d.started || d.completed) return
        DesktopListeningProgress.save(d.song, d.engine.positionMs(), d.engine.durationMs())
    }

    private fun open(i: Int, position: Long? = null, play: Boolean = true, recordPlay: Boolean = true) {
        val song = _queue.value.getOrNull(i) ?: return
        saveProgress()
        resetMix()
        main?.engine?.dispose()
        main = null
        startSong(i, song, recordPlay)
        wanted = play
        val source = firstSource(song)
        if (source == null) {
            fail(song)
            return
        }
        val at = position ?: Audiobooks.resumeAt(song.videoId) ?: Podcasts.resumeAt(song.videoId) ?: ResumePoints.resumeAt(song.videoId) ?: 0
        val d = makeDeck(song, source, { at }, wanted)
        main = d
        playDeck(d)
        startBackgroundWork(song)
    }

    internal fun outputChanged(localOnly: Boolean = false) = synchronized(gate) {
        if (localOnly && (DesktopCast.active.value != null || main?.completed == true)) return@synchronized
        if (current != null && (main != null || wanted)) open(_index.value, positionMs(), wanted, recordPlay = false)
    }

    private fun startSong(i: Int, song: Song, recordPlay: Boolean = true) {
        upgradeJob?.cancel()
        aheadJob?.cancel()
        incoming?.engine?.dispose()
        incoming = null
        generation++
        opening = false
        restoredPosition = 0
        _index.value = i
        _error.value = null
        _loading.value = true
        _quality.value = null
        _qualityCheck.value = null
        _signalPath.value = null
        _durationOf.value = song.videoId to 0L
        wanted = true
        _playing.value = false
        playedInSession += song.videoId
        if (recordPlay && !AppSettings.playback.value.pauseListeningHistory) Library.played(song)
    }

    private fun playDeck(d: Deck) {
        d.engine.setRateFactor(roomRate)
        applyNormalization(d)
        applyVolume()
        d.engine.begin()
    }

    private fun firstSource(song: Song): Source? {
        DesktopCast.active.value?.let { connection ->
            return Source("", Decoder.CAST, { Quality(connection.target.name, connection.target.kind.name) }, cast = connection)
        }
        val local = OfflineDownloads.audio(song.videoId)
        if (local != null && (song.localUri != null || !AppSettings.playback.value.streamDownloadsOnline)) return downloaded(local)
        val upgrade = if (AppSettings.playback.value.qualityUpgrade) knownUpgrade(song) else null
        return if (upgrade != null) upgradeSource(song, upgrade) else baseSource(song)
    }

    private fun downloaded(audio: OfflineDownloads.Audio): Source {
        val quality = { Quality("Download", audio.detail, audio.lossless) }
        val loudness = { if (audio.lossless) null else audio.loudnessDb }
        if (audio.format.equals("FLAC", true) && !filteredOutput()) {
            return Source(audio.file.absolutePath, Decoder.FLAC, quality, readFlac = { audio.file.inputStream() })
        }
        return standardSource(audio.file.absolutePath, audio.file.toURI().toASCIIString(), quality, loudness)
    }

    private fun filteredOutput(): Boolean = AppSettings.equalizer.value.let { it.enabled || it.headphone != null } ||
        !AppSettings.sound.value.isDefault || AppSettings.playback.value.let { it.clarity || it.spatialAudio || it.skipSilence }

    private fun standardSource(
        address: String,
        fxAddress: String?,
        quality: () -> Quality,
        loudness: () -> Double? = { null },
        fallback: (() -> Source?)? = null,
        preferFfmpeg: Boolean = true,
    ): Source {
        fun fx() = fxAddress?.let { Source(it, Decoder.JAVA_FX, quality, loudness, fallback = fallback) }
        return if (preferFfmpeg && Ffmpeg.available) Source(address, Decoder.FFMPEG, quality, loudness, fallback = { fx() ?: fallback?.invoke() })
        else fx() ?: Source(address, Decoder.FFMPEG, quality, loudness, fallback = fallback)
    }

    private fun baseSource(song: Song): Source? {
        val id = song.videoId
        return when {
            Radio.isRadio(id) -> Radio.streamUrl(id)?.let { address ->
                standardSource(address, address, { Quality("Radio", Radio.station(id)?.details ?: "Live") })
            }
            Audiobooks.isChapter(id) -> Audiobooks.urlOf(id).let { address ->
                standardSource(address, address, { Quality("LibriVox", "MP3") })
            }
            LocalMusic.isLocal(id) -> DesktopLocalMusic.fileOf(song)?.let { file ->
                val quality = { DesktopLocalMusic.qualityOf(file) }
                if (file.extension.equals("flac", true) && !filteredOutput()) Source(file.absolutePath, Decoder.FLAC, quality, readFlac = { file.inputStream() })
                else standardSource(file.absolutePath, file.toURI().toASCIIString(), quality)
            }
            Subsonic.isSubsonic(id) -> Subsonic.streamUrl(id)?.replace("format=raw", "format=mp3&maxBitRate=320")?.let { address ->
                standardSource(address, address, { Quality("Your server", "MP3 320 kbps") })
            }
            isYouTubeId(id) -> {
                val maxKbps = AppSettings.playback.value.wifiQuality.maxKbps
                val loudness = { Streams.loudnessDbFor(id) }
                val aac = { Quality("YouTube", Streams.known(id, maxKbps = maxKbps)?.let { "AAC ${it.kbps} kbps" } ?: "AAC") }
                val fallback = { OfflineDownloads.audio(id)?.let(::downloaded) }
                val aacSource = { Source(Relay.addressOf(id, maxKbps = maxKbps), Decoder.JAVA_FX, aac, loudness, fallback = fallback) }
                if (Ffmpeg.available) Source(Relay.addressOf(id, Streams.Kind.OPUS, maxKbps), Decoder.FFMPEG,
                    { Quality("YouTube", Streams.known(id, Streams.Kind.OPUS, maxKbps)?.let { "Opus ${it.kbps} kbps" } ?: "Opus") },
                    loudness, fallback = aacSource)
                else aacSource()
            }
            else -> null
        }
    }

    private fun applyNormalization(d: Deck) {
        val settings = AppSettings.playback.value
        val measured = if (settings.loudnessNormalization) d.source.loudness() else null
        val level = settings.volumeLevel
        val gain = measured?.let { (-it + level.offsetDb).coerceIn(-30.0, level.maxGainDb.toDouble()) } ?: 0.0
        d.engine.setNormalizationDb(gain)
    }

    private fun makeDeck(song: Song, source: Source, from: () -> Long, play: Boolean): Deck {
        val d = Deck(song, source)
        val ready: (Long) -> Unit = { duration -> synchronized(gate) { sourceReady(d, duration) } }
        val started: () -> Unit = { synchronized(gate) { sourceStarted(d) } }
        val playing: (Boolean) -> Unit = { value -> synchronized(gate) { if (main === d) _playing.value = value && wanted } }
        val ended: () -> Unit = { synchronized(gate) { sourceEnded(d) } }
        val failure: (String?) -> Unit = { message -> synchronized(gate) { sourceFailed(d, message) } }
        d.engine = when (source.decoder) {
            Decoder.FFMPEG -> FfmpegEngine(source.address, from, play, ready, started, playing, ended, failure,
                out = SoundCard(120), startImmediately = false, headers = source.headers,
                floatOutput = AppSettings.playback.value.floatOutput)
            Decoder.JAVA_FX -> FxEngine(source.address, from, play, ready, started, playing, ended, failure)
            Decoder.FLAC -> FlacEngine(source.readFlac!!, from, play, started, ended, failure,
                out = SoundCard(160), onReady = ready, startImmediately = false)
            Decoder.CAST -> CastEngine(requireNotNull(source.cast), song, from(), play, ready, started, playing, ended, failure)
        }
        return d
    }

    private fun sourceReady(d: Deck, duration: Long) {
        d.ready = true
        if (main !== d) return
        _quality.value = qualityOf(d)
        val decoder = when (d.source.decoder) {
            Decoder.FFMPEG -> "FFmpeg"
            Decoder.JAVA_FX -> "JavaFX media"
            Decoder.FLAC -> "FLAC decoder"
            Decoder.CAST -> "Remote playback"
        }
        val codec = (d.engine as? FfmpegEngine)?.codec ?: "FLAC".takeIf { d.source.decoder == Decoder.FLAC }
        _signalPath.value = SignalPath(decoder, codec, d.engine.audioRoute())
        _loading.value = false
        if (duration > 0) _durationOf.value = d.song.videoId to duration
        applyNormalization(d)
    }

    private fun qualityOf(d: Deck): Quality {
        val quality = d.source.quality()
        val format = (d.engine as? FlacEngine)?.output
        val depth = (d.engine as? FlacEngine)?.bits ?: 0
        return if (format != null && format.sampleSizeInBits < depth)
            quality.copy(detail = quality.detail + " · sound card at ${format.sampleSizeInBits}-bit")
        else quality
    }

    private fun sourceStarted(d: Deck) {
        d.started = true
        if (incoming === d) {
            val old = main
            if (old == null) return
            incoming = null
            main = d
            cancelCue()
            d.mixGain = old.mixGain
            old.engine.dispose()
            applyVolume()
            if (d.requestedUpgrade) {
                _qualityCheck.value = QualityCheck("Switched to ${qualityOf(d).detail}")
            }
        }
        if (main !== d) return
        sourceReady(d, d.engine.durationMs())
        _playing.value = wanted
    }

    private fun sourceEnded(d: Deck) {
        d.completed = true
        if (blend?.tail === d) {
            finishBlend()
            return
        }
        if (main !== d) return
        DesktopListeningProgress.finish(d.song.videoId)
        if (_sleep.value == Sleep.EndOfSong || DesktopRooms.isGuest) {
            setPlayState(false)
            if (_sleep.value == Sleep.EndOfSong) {
                _sleep.value = null
                setWindDown(0f)
            }
        } else {
            advance(automatic = true)
        }
    }

    private fun sourceFailed(d: Deck, message: String?) {
        if (main !== d && incoming !== d && cue?.deck !== d && blend?.tail !== d) return
        val diagnostic = if (d.requestedUpgrade) "audio unavailable" else message ?: "audio unavailable"
        System.err.println("Player: ${d.song.videoId}: $diagnostic")
        val position = d.engine.positionMs()
        d.engine.dispose()
        if (incoming === d) {
            incoming = null
            upgrades.remove(d.song.videoId)
            if (d.requestedUpgrade) {
                _qualityCheck.value = QualityCheck("Couldn't switch streams. The previous source is still selected.")
            }
        } else if (blend?.tail === d) {
            finishBlend()
        } else if (cue?.deck === d) {
            cue?.deck = null
            cue?.failed = true
        } else if (main === d) {
            val fallback = d.source.fallback?.invoke()
            if (fallback == null) {
                main = null
                resetMix()
                fail(d.song)
            } else {
                val next = makeDeck(d.song, fallback, { position }, wanted)
                next.mixGain = d.mixGain
                main = next
                cancelCue()
                playDeck(next)
            }
        }
    }

    private fun fail(song: Song) {
        wanted = false
        _loading.value = false
        _playing.value = false
        _signalPath.value = null
        _error.value = "Couldn't play ${song.title}"
    }

    private fun startBackgroundWork(song: Song) {
        if (main?.source?.decoder == Decoder.CAST) return
        val gen = generation
        upgradeJob = scope.launch {
            if (AppSettings.playback.value.qualityUpgrade) findUpgrades(song, gen)
        }
        if (AppSettings.playback.value.preloadUpcoming) {
            val next = _queue.value.getOrNull(_index.value + 1)
            if (next != null) aheadJob = scope.launch { lookAhead(next) }
        }
        if (AppSettings.playback.value.loudnessNormalization && isYouTubeId(song.videoId) &&
            Streams.loudnessDbFor(song.videoId) == null) scope.launch {
            delay(1_500)
            Streams.loadLoudness(song.videoId)
            synchronized(gate) { main?.takeIf { it.song.videoId == song.videoId }?.let(::applyNormalization) }
        }
    }

    // ---- Automatic track transitions ------------------------------------------------

    private fun tickMix() {
        val active = blend
        if (active != null) {
            val d = main ?: return finishBlend()
            if (!wanted) return
            val position = active.tail.engine.positionMs()
            val p = ((position - active.from).toDouble() / (active.end - active.from)).toFloat().coerceIn(0f, 1f)
            if (p >= 1f) return finishBlend()
            val plan = active.plan
            val (tail, head) = blendGains(p, plan.outFades, plan.inEases)
            val (outTrim, inTrim) = if (active.tail.source.loudness() != null && d.source.loudness() != null &&
                AppSettings.playback.value.loudnessNormalization) 1f to 1f else levelTrims(plan.levelDb, p)
            active.tail.mixGain = (tail * outTrim).toDouble()
            d.mixGain = (head * inTrim).toDouble()
            if (plan.bassSwap) {
                val (outBass, inBass) = bassCuts(p, plan.swapAt, plan.swapWidth)
                active.tail.engine.setBassCut(outBass)
                d.engine.setBassCut(inBass)
            }
            applyVolume()
            return
        }
        val d = main ?: return cancelCue()
        if (d.source.decoder == Decoder.CAST) return cancelCue()
        val settings = AppSettings.playback.value
        val mode = if (settings.automix) -settings.automixPerformance.ordinal - 1 else settings.crossfadeSeconds.coerceIn(0, 12)
        val at = nextIndex(automatic = true)
        val upcoming = at?.let { _queue.value.getOrNull(it) }
        val length = d.engine.durationMs()
        if (!d.ready || length !in 3_000..<ResumePoints.LONG_MS || mode == 0 || _sleep.value == Sleep.EndOfSong || DesktopRooms.isGuest ||
            !mixable(d.song) || at == null || at == _index.value || upcoming == null || !mixable(upcoming)) return cancelCue()
        val key = MixKey(generation, at, upcoming.videoId, length, mode)
        if (!wanted || !d.started) return
        val next = cue?.takeIf { it.key == key } ?: planTransition(key, d, upcoming, settings) ?: return
        if (next.failed) return
        val position = d.engine.positionMs()
        if (position >= next.plan.outEndMs) return
        if (next.deck == null && position >= next.plan.mixAtMs - 5_000) {
            val prepared = makeDeck(next.song, next.source, { next.plan.inFromMs }, false)
            prepared.mixGain = 0.0
            next.deck = prepared
            playDeck(prepared)
        }
        val prepared = next.deck?.takeIf { it.ready } ?: return
        // A seek into the final milliseconds gives the current track its full ending.
        if (position >= next.plan.mixAtMs && next.plan.outEndMs - position >= 200) promote(next, d, prepared, position)
    }

    private fun planTransition(key: MixKey, current: Deck, song: Song, settings: PlaybackSettings): Cue? {
        cancelCue()
        val source = firstSource(song) ?: return null
        val plan = if (settings.automix) automixPlan(key.durationMs, null, null)
            else crossfadePlan(key.durationMs, settings.crossfadeSeconds.coerceIn(1, 12) * 1000L, null, null)
        val next = Cue(key, plan, song, source)
        cue = next
        // A FLAC add-on may require request headers. Keep its native playback
        // path and use the default transition when its audio cannot be reread.
        if (Ffmpeg.available && current.source.readFlac == null && source.readFlac == null) {
            next.analysis = scope.launch {
                val result = DesktopMixAnalysis.plan(current.source.address, source.address, key.durationMs,
                    settings.crossfadeSeconds.coerceIn(1, 12), settings.automix, settings.automixPerformance)
                synchronized(gate) { if (cue === next && next.deck == null) next.plan = result }
            }
        }
        return next
    }

    private fun promote(next: Cue, old: Deck, d: Deck, position: Long) {
        cue = null
        next.analysis?.cancel()
        next.deck = null
        val end = minOf(next.plan.outEndMs, position + next.plan.blendMs)
        startSong(next.key.next, d.song)
        blend = Blend(old, next.plan, position, end)
        main = d
        old.engine.setRateFactor(next.plan.outSpeed)
        sourceReady(d, d.engine.durationMs())
        _playing.value = true
        d.engine.play()
        startBackgroundWork(d.song)
    }

    private fun cancelCue() {
        val next = cue ?: return
        cue = null
        next.analysis?.cancel()
        next.deck?.engine?.dispose()
    }

    private fun finishBlend() {
        val active = blend ?: return
        blend = null
        active.tail.engine.dispose()
        main?.let {
            it.mixGain = 1.0
            it.engine.setBassCut(0f)
        }
        applyVolume()
    }

    private fun resetMix() {
        cancelCue()
        finishBlend()
    }

    private fun musicTrack(song: Song): Boolean =
        isYouTubeId(song.videoId) && !Podcasts.isEpisode(song.videoId)

    private fun mixable(song: Song): Boolean =
        !Radio.isRadio(song.videoId) && !Podcasts.isEpisode(song.videoId) && !Audiobooks.isChapter(song.videoId) &&
            song.durationText?.let(::seconds)?.let { it < 1_200 } != false

    // ---- Better sources ------------------------------------------------------------

    internal fun requestQualityUpgrade(id: String) = synchronized(gate) {
        val current = main?.takeIf { it.song.videoId == id && !it.completed } ?: return@synchronized
        if (!musicTrack(current.song) || _qualityCheck.value?.busy == true) return@synchronized
        val reason = when {
            current.source.decoder == Decoder.CAST -> "Quality is controlled by the cast receiver."
            current.source.quality().source == "Download" -> "This track is playing from a download."
            _quality.value?.lossless == true -> "This track is already playing lossless audio."
            !wanted -> "Resume playback to upgrade the stream."
            !Addons.lossless.value && !Addons.jioSaavn.value -> "Enable a higher-quality source in Settings."
            else -> null
        }
        if (reason != null) {
            _qualityCheck.value = QualityCheck(reason)
            return@synchronized
        }

        val gen = generation
        val checking = QualityCheck("Looking for a higher-quality stream…", busy = true)
        _qualityCheck.value = checking
        upgradeJob?.cancel()
        incoming?.engine?.dispose()
        incoming = null
        upgradeJob = scope.launch {
            val offered = try {
                withTimeoutOrNull(45_000) { findUpgrades(current.song, gen, requested = true) } == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!offered) synchronized(gate) {
                if (generation == gen) _qualityCheck.compareAndSet(checking, QualityCheck("No higher-quality stream found."))
            }
        }
    }

    /** A better source found for a song: what plays it and how it's labelled. */
    private data class Upgrade(val flac: Addons.Flac?, val saavn: String?, val at: Long = System.currentTimeMillis()) {
        val rank get() = if (flac != null) 2 else 1
    }

    /** Found sources by song, for a song played again or coming next. Add-on links expire, so not for long. */
    private val upgrades = ConcurrentHashMap<String, Upgrade>()
    private const val UPGRADE_KEEP_MS = 10 * 60_000L

    private fun knownUpgrade(song: Song): Upgrade? =
        upgrades[song.videoId]?.takeIf { System.currentTimeMillis() - it.at < UPGRADE_KEEP_MS }

    private fun rankNow(): Int = when {
        _quality.value?.lossless == true -> 2
        _quality.value?.source == "JioSaavn" -> 1
        else -> 0
    }

    private suspend fun wantedOf(song: Song): JioSaavn.Wanted? {
        val listed = song.durationText?.let(::seconds)
        val length = listed ?: withTimeoutOrNull(15_000) {
            durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second / 1000.0
        } ?: return null
        return JioSaavn.Wanted(song.title, song.artist, song.albumName, length)
    }

    /** Asks JioSaavn and the add-ons at once; each better answer takes over as it comes. */
    private suspend fun findUpgrades(song: Song, gen: Int, requested: Boolean = false): Boolean {
        if (!musicTrack(song) || (!Addons.lossless.value && !Addons.jioSaavn.value)) return false
        Addons.syncPublished()
        val wanted = wantedOf(song) ?: return false
        return coroutineScope {
            val saavn = async { if (Addons.jioSaavn.value) JioSaavn.find(wanted) else null }
            val flac = async { runCatching { Addons.findFlac(wanted) }.getOrNull() }
            if (requested) {
                val best = flac.await()?.let { Upgrade(it, null) } ?: saavn.await()?.let { Upgrade(null, it) }
                saavn.cancel()
                currentCoroutineContext().ensureActive()
                return@coroutineScope best != null && offer(song, gen, best, requested = true)
            }
            saavn.await()?.let { url -> offer(song, gen, Upgrade(null, url)) }
            flac.await()?.let { found -> offer(song, gen, Upgrade(found, null)) }
            false
        }
    }

    private suspend fun lookAhead(song: Song) {
        if (!musicTrack(song) || knownUpgrade(song) != null) return
        val listed = song.durationText?.let(::seconds)
        val wanted = listed?.let { JioSaavn.Wanted(song.title, song.artist, song.albumName, it) }
        val flac = wanted?.let { runCatching { Addons.findFlac(it) }.getOrNull() }
        val found = if (flac != null) Upgrade(flac, null) else if (wanted != null && Addons.jioSaavn.value)
            JioSaavn.find(wanted)?.let { Upgrade(null, it) }
        else null
        if (found != null) upgrades[song.videoId] = found
        else runCatching { Streams.resolve(song.videoId, kind = if (Ffmpeg.available) Streams.Kind.OPUS else Streams.Kind.AAC) }
    }

    private fun offer(song: Song, gen: Int, upgrade: Upgrade, requested: Boolean = false): Boolean = synchronized(gate) {
        val known = upgrades[song.videoId]
        if (known == null || known.rank <= upgrade.rank) upgrades[song.videoId] = upgrade
        val old = main ?: return@synchronized false
        if (gen != generation || upgrade.rank <= rankNow() || old.source.quality().source == "Download" ||
            blend != null || old.completed || (!requested && !AppSettings.playback.value.qualityUpgrade)) return@synchronized false
        incoming?.engine?.dispose()
        val better = makeDeck(song, upgradeSource(song, upgrade), { old.engine.positionMs() }, wanted)
        better.mixGain = 0.0
        better.requestedUpgrade = requested
        incoming = better
        playDeck(better)
        if (requested) scope.launch {
            delay(25_000)
            synchronized(gate) { if (incoming === better) sourceFailed(better, null) }
        }
        true
    }

    private fun upgradeSource(song: Song, upgrade: Upgrade): Source {
        val fallback = { upgrades.remove(song.videoId); baseSource(song) }
        val flac = upgrade.flac
        if (flac != null && filteredOutput() && Ffmpeg.available) {
            return Source(flac.url, Decoder.FFMPEG, { Quality(flac.provider, "FLAC · processed PCM", lossless = true) },
                fallback = fallback, headers = flac.headers)
        }
        return if (flac != null) Source(flac.url, Decoder.FLAC,
            { Quality(flac.provider, "FLAC ${flac.bits}-bit / ${Addons.khz(flac.rate)}", lossless = true) },
            readFlac = { download(flac.url, flac.headers) }, fallback = fallback)
        else standardSource(upgrade.saavn!!, upgrade.saavn, { Quality("JioSaavn", "AAC 320 kbps") }, fallback = fallback)
    }

    private val files = Http.client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    /** A FLAC file's bytes as they arrive. */
    private fun download(url: String, headers: Map<String, String>): java.io.InputStream {
        val response = files.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("its file server returned HTTP ${response.code}")
        }
        return response.body!!.byteStream()
    }

    /** "3:45" or "1:02:03" in seconds. */
    internal fun seconds(text: String): Double? {
        val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0) { total, p -> total * 60 + p }.toDouble().takeIf { it > 0 }
    }

    // ---- JavaFX -------------------------------------------------------------------

    /** JavaFX's media player for one address: YouTube through the relay, a server, or JioSaavn. */
    private class FxEngine(
        private val address: String,
        private val startAtMs: () -> Long,
        autoplay: Boolean,
        private val onReady: (Long) -> Unit,
        private val onStarted: () -> Unit,
        private val onPlaying: (Boolean) -> Unit,
        private val onEnd: () -> Unit,
        private val onError: (String?) -> Unit,
    ) : Engine {
        @Volatile private var player: MediaPlayer? = null
        @Volatile private var disposed = false
        @Volatile private var started = false
        @Volatile private var paused = !autoplay
        @Volatile private var volume = 1.0
        @Volatile private var normalization = 1.0
        @Volatile private var speed = 1f
        @Volatile private var pendingSeek: Long? = null

        override fun begin() {
            onFx {
                if (disposed) return@onFx
                val m = runCatching { MediaPlayer(Media(address)) }.getOrElse { onError(it.message); return@onFx }
                player = m
                m.volume = (volume * normalization).coerceIn(0.0, 1.0)
                applyEffects(m, speed)
                m.setOnReady {
                    if (disposed) return@setOnReady
                    (pendingSeek ?: startAtMs()).takeIf { it > 0 }?.let { m.seek(Duration.millis(it.toDouble())) }
                    pendingSeek = null
                    onReady(m.totalDuration?.toMillis()?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0L)
                    if (!paused) m.play()
                }
                m.setOnPlaying {
                    if (!started) { started = true; onStarted() }
                    onPlaying(true)
                }
                m.setOnPaused { onPlaying(false) }
                m.setOnStopped { onPlaying(false) }
                m.setOnEndOfMedia { onEnd() }
                m.setOnError { onError(m.error?.message) }
            }
        }

        override fun play() {
            paused = false
            if (player != null) onFx { player?.play() }
        }
        override fun pause() {
            paused = true
            if (player != null) onFx { player?.pause() }
        }
        override fun seek(ms: Long) {
            if (!started) pendingSeek = ms
            if (player != null) onFx { player?.seek(Duration.millis(ms.toDouble())) }
        }
        override fun positionMs(): Long = player?.currentTime?.toMillis()?.takeIf { it.isFinite() }?.toLong() ?: startAtMs()
        override fun durationMs(): Long = player?.totalDuration?.toMillis()?.takeIf { it.isFinite() }?.toLong() ?: 0L
        override fun setVolume(v: Double) {
            volume = v
            if (player != null) onFx { player?.volume = (volume * normalization).coerceIn(0.0, 1.0) }
        }
        override fun setNormalizationDb(db: Double) {
            normalization = 10.0.pow(db / 20.0)
            setVolume(volume)
        }
        override fun setRateFactor(factor: Float) {
            speed = factor
            if (player != null) onFx { player?.rate = (AppSettings.sound.value.speed * speed).toDouble().coerceIn(0.25, 4.0) }
        }
        fun refreshEffects() {
            if (player != null) onFx { player?.let { applyEffects(it, speed) } }
        }
        override fun dispose() {
            disposed = true
            onFx { player?.dispose(); player = null }
        }
    }

    /**
     * Speed, balance and the equalizer, from the phone's settings. JavaFX
     * has no preamp, so the curve is lowered by its biggest boost instead,
     * as the phone takes that boost off the input to keep from clipping;
     * bass and treble go on the bands below 120 Hz and above 8 kHz.
     */
    private fun applyEffects(m: MediaPlayer, speed: Float) {
        val sound = AppSettings.sound.value
        val eq = AppSettings.equalizer.value
        m.rate = (sound.speed * speed).toDouble().coerceIn(0.25, 4.0)
        m.balance = eq.balance.toDouble().coerceIn(-1.0, 1.0)
        val equalizer = m.audioEqualizer
        equalizer.bands.clear()
        equalizer.isEnabled = eq.enabled
        if (!eq.enabled) return
        val gains = EQ_BANDS_HZ.mapIndexed { i, hz ->
            (eq.bands.getOrNull(i) ?: 0f) + eq.preampDb +
                (if (hz <= 120f) eq.bassDb else 0f) + (if (hz >= 8_000f) eq.trebleDb else 0f)
        }
        val headroom = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
        // Each band two-thirds of an octave wide, centred on its frequency.
        val width = 2.0.pow(1.0 / 3) - 2.0.pow(-1.0 / 3)
        EQ_BANDS_HZ.forEachIndexed { i, hz ->
            val gain = (gains[i] - headroom).toDouble().coerceIn(EqualizerBand.MIN_GAIN, EqualizerBand.MAX_GAIN)
            equalizer.bands += EqualizerBand(hz.toDouble(), hz * width, gain)
        }
    }

    /** Adds related songs only while the original queue is still active. */
    private fun extendThen(at: Int) {
        if (opening) return
        val last = _queue.value.lastOrNull()?.takeIf(::musicTrack) ?: return setPlayState(false)
        val gen = generation
        opening = true
        _loading.value = true
        scope.launch {
            val more = DesktopRecommendations.after(last)
            synchronized(gate) {
                if (gen != generation) return@synchronized
                opening = false
                val have = _queue.value.mapTo(HashSet()) { it.videoId }
                if (AppSettings.playback.value.noRepeatInSession) have.addAll(playedInSession)
                val fresh = DesktopHidden.suggestions(more).filter { Pages.allowed(it) && have.add(it.videoId) }
                if (fresh.isEmpty() || !wanted) {
                    _loading.value = false
                    setPlayState(false)
                    return@synchronized
                }
                _queue.value = _queue.value + fresh
                open(at)
            }
        }
    }
}
