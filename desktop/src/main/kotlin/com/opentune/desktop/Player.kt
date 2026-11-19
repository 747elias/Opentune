package com.opentune.desktop

import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.subsonic.Subsonic
import java.util.concurrent.ConcurrentHashMap
import javafx.application.Platform
import javafx.scene.media.EqualizerBand
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.Locale

/**
 * The queue and the song playing. A song starts from a local file, YouTube or your own
 * server; meanwhile the
 * audio sources are asked for something better, as on the phone: a verified
 * FLAC from an add-on, played bit for bit by [FlacEngine], or JioSaavn's
 * 320 kbps AAC. Whichever answers takes over at the same moment in the song,
 * and the next song's sources are asked ahead, so it can start in its best
 * quality. When the queue runs out it carries on with YouTube Music's
 * up-next, when autoplay is on. The phone's speed, balance and equalizer
 * settings apply to JavaFX's playback; a lossless file plays untouched.
 */
object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isLinux = System.getProperty("os.name").orEmpty().equals("Linux", ignoreCase = true)

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
    private val _stopped = MutableStateFlow(false)
    internal val stopped = _stopped.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _volume = MutableStateFlow(Library.volume())
    val volume: StateFlow<Double> = _volume.asStateFlow()
    private val _quality = MutableStateFlow<Quality?>(null)
    val quality: StateFlow<Quality?> = _quality.asStateFlow()

    /** The song's id and its exact length once the player knows it, for lyrics to match on. */
    private val _durationOf = MutableStateFlow("" to 0L)
    val durationOf: StateFlow<Pair<String, Long>> = _durationOf.asStateFlow()
    private val _seeks = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    internal val seeks = _seeks.asSharedFlow()

    val current: Song? get() = _queue.value.getOrNull(_index.value)

    private val _repeat = MutableStateFlow(Repeat.OFF)
    val repeat: StateFlow<Repeat> = _repeat.asStateFlow()
    private val _shuffled = MutableStateFlow(false)
    val shuffled: StateFlow<Boolean> = _shuffled.asStateFlow()
    /** The queue's order before shuffling, to go back to. */
    private var unshuffled: List<Song>? = null

    fun cycleRepeat() = setRepeat(_repeat.value.next())

    internal fun setRepeat(mode: Repeat) { _repeat.value = mode }

    /** Shuffles what's after the song playing, or puts the queue back in its order. */
    fun toggleShuffle() {
        val q = _queue.value
        val now = current
        val original = unshuffled
        if (_shuffled.value && original != null) {
            _queue.value = original
            _index.value = now?.let { s -> original.indexOfFirst { it.videoId == s.videoId } }?.takeIf { it >= 0 } ?: 0
            unshuffled = null
            _shuffled.value = false
        } else if (q.isNotEmpty()) {
            unshuffled = q
            val at = _index.value.coerceIn(q.indices)
            _queue.value = listOf(q[at]) + q.filterIndexed { i, _ -> i != at }.shuffled()
            _index.value = 0
            _shuffled.value = true
        }
    }

    private val _sleep = MutableStateFlow<Sleep?>(null)
    val sleep: StateFlow<Sleep?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null
    /** 1 normally; less while the sleep timer winds down. */
    @Volatile private var fade = 1.0

    /** Guards the engines and which song they belong to; callbacks come from several threads. */
    private val gate = Any()
    private var engine: Engine? = null
    /** A better source taking over, until its first sound. */
    private var incoming: Engine? = null
    /** Counts songs opened, so an engine left over from an earlier one is ignored. */
    private var generation = 0
    /** Whether the listener wants sound: true from play until pause. */
    @Volatile private var wanted = false
    private var upgradeJob: Job? = null
    private var aheadJob: Job? = null

    init {
        // A change in Settings reaches the song already playing.
        scope.launch {
            combine(AppSettings.sound, AppSettings.equalizer) { _, _ -> }.collect {
                when (val e = synchronized(gate) { engine }) {
                    is FxEngine -> onFx { e.player?.let(::applyEffects) }
                    is FfmpegEngine -> e.refreshEffects()
                    else -> Unit
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
        unshuffled = null
        _shuffled.value = false
        _queue.value = songs
        open(start.coerceIn(songs.indices))
    }

    /** Loads [songs] without playing them, for pictures of the window (DesktopShots). */
    internal fun cue(songs: List<Song>, at: Int) {
        _queue.value = songs
        _index.value = at
    }

    fun playAt(i: Int) {
        if (i in _queue.value.indices) open(i)
    }

    fun toggle() {
        val e = synchronized(gate) { engine } ?: return current?.let { open(_index.value) } ?: Unit
        if (wanted) pauseAll() else resume(e)
    }

    internal fun play() {
        val e = synchronized(gate) { engine }
        if (e == null || _error.value != null) {
            if (!_loading.value) open(_index.value)
        } else if (!wanted) resume(e)
    }

    private fun resume(e: Engine) {
        wanted = true
        _stopped.value = false
        e.play()
        synchronized(gate) { incoming }?.play()
        _playing.value = true
    }

    internal fun pause() = pauseAll()

    internal fun stop() {
        pauseAll()
        seekTo(0)
        _stopped.value = true
    }

    private fun pauseAll() {
        wanted = false
        synchronized(gate) { listOfNotNull(engine, incoming) }.forEach { it.pause() }
        _playing.value = false
    }

    fun next() {
        val i = _index.value + 1
        when {
            i in _queue.value.indices -> open(i)
            _repeat.value == Repeat.ALL && _queue.value.isNotEmpty() -> open(0)
            AppSettings.playback.value.autoplay -> extendThen { open(i) }
            else -> pauseAll()
        }
    }

    /** Stops playback in [minutes], the last few turning the volume down; null turns the timer off. */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        setFade(1.0)
        if (minutes == null) {
            _sleep.value = null
            return
        }
        val total = minutes * 60_000L
        val at = System.currentTimeMillis() + total
        _sleep.value = Sleep.At(at, minutes)
        sleepJob = scope.launch {
            // The last third, at most three minutes, fades out, as on the phone.
            val window = minOf(180_000L, total / 3)
            while (true) {
                val left = at - System.currentTimeMillis()
                if (left <= 0) break
                setFade(if (left < window) left.toDouble() / window else 1.0)
                delay(1_000)
            }
            pauseAll()
            setFade(1.0)
            _sleep.value = null
        }
    }

    /** Stops playback when the song playing ends. */
    fun sleepAtEndOfSong() {
        sleepJob?.cancel()
        setFade(1.0)
        _sleep.value = Sleep.EndOfSong
    }

    private fun setFade(f: Double) {
        fade = f
        applyVolume()
    }

    private fun applyVolume() {
        val v = _volume.value * fade
        synchronized(gate) { listOfNotNull(engine, incoming) }.forEach { it.setVolume(v) }
    }

    /** Back to the start of the song a few seconds in; before that, the song before. */
    fun previous() {
        if (positionMs() > 4_000) seekTo(0)
        else if (_index.value > 0) open(_index.value - 1)
    }

    fun seekTo(ms: Long) {
        val e = synchronized(gate) { engine } ?: return
        e.seek(ms)
        _seeks.tryEmit(ms)
    }

    fun setVolume(v: Double) {
        _volume.value = v.coerceIn(0.0, 1.0)
        Library.setVolume(_volume.value)
        applyVolume()
    }

    fun positionMs(): Long = synchronized(gate) { engine }?.positionMs() ?: 0L
    fun durationMs(): Long = synchronized(gate) { engine }?.durationMs() ?: 0L

    private fun open(i: Int) {
        val song = _queue.value.getOrNull(i) ?: return
        _index.value = i
        _error.value = null
        _loading.value = true
        _quality.value = null
        wanted = true
        _stopped.value = false
        _playing.value = false
        Library.played(song)
        val gen = synchronized(gate) {
            upgradeJob?.cancel()
            aheadJob?.cancel()
            incoming?.dispose(); incoming = null
            engine?.dispose(); engine = null
            ++generation
        }
        val ready = upgrades[song.videoId]?.takeIf { System.currentTimeMillis() - it.at < UPGRADE_KEEP_MS }
        if (ready != null) start(song, gen, ready, takeover = false) else startBase(song, gen)
        upgradeJob = scope.launch {
            findUpgrades(song, gen)
            // Then the next song's, so it can start in its best quality.
            _queue.value.getOrNull(i + 1)?.let { following -> aheadJob = scope.launch { lookAhead(following) } }
        }
    }

    // ---- The song's first source -------------------------------------------------

    /** Linux decodes with the bundled FFmpeg, including AAC and the server's original file. */
    private fun startBase(song: Song, gen: Int, atMs: Long = 0, preferOpus: Boolean = true, replacing: Engine? = null) {
        val id = song.videoId
        val local = LocalAudio.addressOf(id)
        if ((id.startsWith("local:file:") && local == null) || (local != null && !Ffmpeg.available)) return fail(song, gen)
        val subsonic = Subsonic.isSubsonic(id)
        val opus = preferOpus && isYouTubeId(id) && Ffmpeg.available
        val kind = if (opus) Streams.Kind.OPUS else Streams.Kind.AAC
        val bundled = local != null || opus || (isLinux && Ffmpeg.available)
        val serverAddress = if (subsonic) Subsonic.streamUrl(id) ?: return fail(song, gen) else null
        val direct = serverAddress?.takeIf { bundled }?.let(Relay::direct)
        val address = local ?: direct?.address ?: serverAddress?.replace("format=raw", "format=mp3&maxBitRate=320")
            ?: Relay.addressOf(id, kind)

        lateinit var e: Engine
        val codec = { (e as? FfmpegEngine)?.codec?.uppercase(Locale.ROOT) }
        val onReady: (Long) -> Unit = { ms ->
            if (current(gen, e)) {
                if (ms > 0) _durationOf.value = id to ms
                if (_quality.value == null) {
                    _quality.value = when {
                        local != null -> Quality("Local file", codec() ?: "Original file")
                        subsonic -> Quality("Your server", if (bundled) codec() ?: "Original stream" else "MP3 320 kbps")
                        else -> {
                            val format = if (opus) "Opus" else "AAC"
                            Quality("YouTube", Streams.known(id, kind)?.let { "$format ${it.kbps} kbps" } ?: format)
                        }
                    }
                }
                if (!bundled || !wanted) _loading.value = false
            }
        }
        val onStarted: () -> Unit = { if (current(gen, e)) _loading.value = false }
        val onPlaying: (Boolean) -> Unit = { on -> if (current(gen, e)) { _playing.value = on; _loading.value = false } }
        val onEnd: () -> Unit = { ended(gen, e) }
        val onError: (String?) -> Unit = { message ->
            if (current(gen, e)) {
                val at = runCatching { e.positionMs() }.getOrDefault(atMs)
                e.dispose()
                if (opus) {
                    System.err.println("Player: $id: Opus through FFmpeg failed ($message), trying AAC")
                    startBase(song, gen, at, preferOpus = false, replacing = e)
                } else {
                    if (!subsonic && local == null) System.err.println("Player: $id: $message")
                    fail(song, gen)
                }
            }
        }
        e = if (bundled) {
            FfmpegEngine(address, { atMs }, wanted, onReady, onStarted, onPlaying, onEnd, onError, input = direct, local = local != null)
        } else {
            FxEngine(address, { atMs }, wanted, onReady, onStarted, onPlaying, onEnd, onError)
        }
        synchronized(gate) {
            if (gen != generation || (replacing != null && engine !== replacing)) return e.dispose()
            if (replacing != null) _quality.value = null
            engine = e
            e.setVolume(_volume.value * fade)
        }
        e.start()
    }

    private fun current(gen: Int, e: Engine) = synchronized(gate) { gen == generation && engine === e }

    private fun fail(song: Song, gen: Int) {
        if (synchronized(gate) { gen != generation }) return
        _loading.value = false
        _playing.value = false
        _error.value = "Couldn't play ${song.title}"
    }

    private fun ended(gen: Int, finished: Engine) {
        synchronized(gate) {
            if (gen != generation || engine !== finished) return
            engine = null
            wanted = false
            _playing.value = false
        }
        finished.dispose()
        if (_sleep.value == Sleep.EndOfSong) {
            _sleep.value = null
            pauseAll()
        } else if (_repeat.value == Repeat.ONE) {
            open(_index.value)
        } else {
            next()
        }
    }

    // ---- Better sources ------------------------------------------------------------

    /** A better source found for a song: what plays it and how it's labelled. */
    private data class Upgrade(val flac: Addons.Flac?, val saavn: String?, val at: Long = System.currentTimeMillis()) {
        val rank get() = if (flac != null) 2 else 1
    }

    /** Found sources by song, for a song played again or coming next. Add-on links expire, so not for long. */
    private val upgrades = ConcurrentHashMap<String, Upgrade>()
    private const val UPGRADE_KEEP_MS = 10 * 60_000L

    /** How good what's playing is: 0 the starting source, 1 JioSaavn, 2 FLAC. */
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
    private suspend fun findUpgrades(song: Song, gen: Int) {
        if (!isYouTubeId(song.videoId) || (!Addons.lossless.value && !Addons.jioSaavn.value)) return
        Addons.syncPublished()
        val wanted = wantedOf(song) ?: return
        coroutineScope {
            val saavn = async { if (Addons.jioSaavn.value) JioSaavn.find(wanted) else null }
            val flac = async { runCatching { Addons.findFlac(wanted) }.getOrNull() }
            saavn.await()?.let { url -> offer(song, gen, Upgrade(null, url)) }
            flac.await()?.let { found -> offer(song, gen, Upgrade(found, null)) }
        }
    }

    /** Finds the next song's sources before it plays. */
    private suspend fun lookAhead(song: Song) {
        if (!isYouTubeId(song.videoId) || upgrades[song.videoId]?.let { System.currentTimeMillis() - it.at < UPGRADE_KEEP_MS } == true) return
        val listed = song.durationText?.let(::seconds) ?: return
        val wanted = JioSaavn.Wanted(song.title, song.artist, song.albumName, listed)
        val flac = runCatching { Addons.findFlac(wanted) }.getOrNull()
        val found = if (flac != null) Upgrade(flac, null) else if (Addons.jioSaavn.value) JioSaavn.find(wanted)?.let { Upgrade(null, it) } else null
        found?.let { upgrades[song.videoId] = it }
    }

    private fun offer(song: Song, gen: Int, upgrade: Upgrade) {
        val known = upgrades[song.videoId]
        if (known == null || known.rank <= upgrade.rank) upgrades[song.videoId] = upgrade
        if (synchronized(gate) { gen == generation } && upgrade.rank > rankNow()) start(song, gen, upgrade, takeover = true)
    }

    /**
     * Plays [upgrade]: taking over from what's playing at its position when
     * [takeover], else from the start. The old engine plays on until the new
     * one's first sound; if the new one fails, the old one simply carries on.
     */
    private fun start(song: Song, gen: Int, upgrade: Upgrade, takeover: Boolean) {
        val old = synchronized(gate) { engine.takeIf { takeover } }
        val startAt: () -> Long = { old?.positionMs() ?: 0L }
        lateinit var e: Engine
        val quality: () -> Quality = {
            upgrade.flac?.let { f ->
                val sixteen = (e as? FlacEngine)?.output?.sampleSizeInBits == 16 && f.bits > 16
                Quality(f.provider, "FLAC ${f.bits}-bit / ${Addons.khz(f.rate)}" + if (sixteen) " · sound card at 16-bit" else "", lossless = true)
            } ?: Quality("JioSaavn", "AAC 320 kbps")
        }
        val onStarted: () -> Unit = {
            val replaced = synchronized(gate) {
                if (gen != generation || (incoming !== e && engine !== e)) return@synchronized null
                val before = engine.takeIf { it !== e }
                incoming = null
                engine = e
                before ?: e
            }
            if (replaced != null) {
                if (replaced !== e) replaced.dispose()
                _quality.value = quality()
                _loading.value = false
                _playing.value = wanted
                e.durationMs().takeIf { it > 0 }?.let { _durationOf.value = song.videoId to it }
            }
        }
        val onError: (String?) -> Unit = { message ->
            System.err.println("Player: ${song.videoId}: upgrade failed: $message")
            upgrades.remove(song.videoId)
            val fallBack = synchronized(gate) {
                when {
                    gen != generation -> false
                    incoming === e -> { incoming = null; false }
                    engine === e -> { engine = null; true }
                    else -> false
                }
            }
            val at = runCatching { e.positionMs() }.getOrDefault(0L)
            e.dispose()
            if (fallBack) { _quality.value = null; startBase(song, gen, at) }
        }
        val onEnd: () -> Unit = { ended(gen, e) }
        val onPlaying: (Boolean) -> Unit = { on -> if (current(gen, e)) _playing.value = on }
        e = if (upgrade.flac != null) {
            val f = upgrade.flac
            FlacEngine(
                source = { download(f.url, f.headers) }, startAtMs = startAt, autoplay = wanted,
                onStarted = onStarted, onEnd = onEnd, onError = onError,
            )
        } else if (isLinux && Ffmpeg.available) {
            val direct = Relay.direct(upgrade.saavn!!)
            FfmpegEngine(direct.address, startAt, wanted, {}, onStarted, onPlaying, onEnd, onError, input = direct)
        } else {
            FxEngine(upgrade.saavn!!, startAt, wanted, {}, onStarted, onPlaying, onEnd, onError)
        }
        e.setVolume(_volume.value * fade)
        synchronized(gate) {
            if (gen != generation) return e.dispose()
            if (takeover) { incoming?.dispose(); incoming = e } else engine = e
        }
        e.start()
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
        @Volatile var player: MediaPlayer? = null
        @Volatile private var disposed = false
        @Volatile private var started = false
        @Volatile private var playing = autoplay
        @Volatile private var volume = 1.0

        override fun start() {
            onFx {
                if (disposed) return@onFx
                val m = runCatching { MediaPlayer(Media(address)) }.getOrElse { onError(it.message); return@onFx }
                player = m
                m.volume = volume
                applyEffects(m)
                m.setOnReady {
                    if (disposed) return@setOnReady
                    onReady(m.totalDuration?.toMillis()?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0L)
                    startAtMs().takeIf { it > 500 }?.let { m.seek(Duration.millis(it.toDouble())) }
                    if (playing) m.play() else { started = true; onStarted() }
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
            playing = true
            if (player != null) onFx { player?.play() }
        }
        override fun pause() {
            playing = false
            if (player != null) onFx { player?.pause() }
        }
        override fun seek(ms: Long) = onFx { player?.seek(Duration.millis(ms.toDouble())) }
        override fun positionMs(): Long = player?.currentTime?.toMillis()?.takeIf { it.isFinite() }?.toLong() ?: startAtMs()
        override fun durationMs(): Long = player?.totalDuration?.toMillis()?.takeIf { it.isFinite() }?.toLong() ?: 0L
        override fun setVolume(v: Double) {
            volume = v
            if (player != null) onFx { player?.volume = v }
        }
        override fun dispose() {
            disposed = true
            if (player != null) onFx { player?.dispose(); player = null }
        }
    }

    /**
     * Speed, balance and the equalizer, from the phone's settings. JavaFX
     * has no preamp, so the curve is lowered by its biggest boost instead,
     * as the phone takes that boost off the input to keep from clipping;
     * bass and treble go on the bands below 120 Hz and above 8 kHz.
     */
    private fun applyEffects(m: MediaPlayer) {
        val sound = AppSettings.sound.value
        val eq = AppSettings.equalizer.value
        m.rate = sound.speed.toDouble().coerceIn(0.25, 4.0)
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

    /** Adds YouTube Music's up-next for the last song, then runs [then]. */
    private fun extendThen(then: () -> Unit) {
        // Only YouTube songs have an up-next to follow.
        val last = _queue.value.lastOrNull()?.takeIf { isYouTubeId(it.videoId) } ?: run {
            pauseAll()
            return
        }
        scope.launch {
            val more = runCatching { InnertubeParser.parseWatchQueue(Innertube.next(last.videoId)) }.getOrDefault(emptyList())
            val have = _queue.value.mapTo(HashSet()) { it.videoId }
            val fresh = more.filter { it.videoId !in have }
            if (fresh.isEmpty()) {
                _playing.value = false
                return@launch
            }
            _queue.value = _queue.value + fresh
            then()
        }
    }
}
