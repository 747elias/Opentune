package com.opentune.desktop

import com.opentune.data.settings.AppSettings
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber

/** Whether FFmpeg's libraries load on this computer; the engine below needs them. */
internal object Ffmpeg {
    val available: Boolean by lazy {
        runCatching {
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            FFmpegLogCallback.set()
        }.onFailure { System.err.println("FFmpeg: not available: $it") }.isSuccess
    }
}

/**
 * A song decoded by FFmpeg and sent to the sound card, for what JavaFX can't
 * play: YouTube's Opus stream, the best it has, and any other format a source
 * gives. The equalizer, bass and treble, balance and speed go through FFmpeg's
 * audio filters, as JavaFX applies them on its own streams.
 *
 * Its callbacks follow the JavaFX engine's: [onReady] with the length once
 * it's known, [onStarted] at the first sound, [onPlaying] as it plays and
 * pauses, [onEnd] at the end and [onError] when it can't.
 */
internal class FfmpegEngine(
    private val address: String,
    private val startAtMs: () -> Long,
    autoplay: Boolean,
    private val onReady: (Long) -> Unit,
    private val onStarted: () -> Unit,
    private val onPlaying: (Boolean) -> Unit,
    private val onEnd: () -> Unit,
    private val onError: (String?) -> Unit,
    private val out: PcmOut = SoundCard(),
    startImmediately: Boolean = true,
    private val headers: Map<String, String> = emptyMap(),
) : Engine {
    private val lock = Object()
    @Volatile private var disposed = false
    @Volatile private var paused = !autoplay
    @Volatile private var seekRequest: Long? = null
    @Volatile private var seekingTo: Long? = null
    @Volatile private var started = false
    @Volatile private var rate = 0
    @Volatile private var duration = 0L
    /** Where the current run started, in frames, and the line's position then. */
    @Volatile private var runStart = 0L
    @Volatile private var lineBase = 0L
    @Volatile private var volume = 1.0
    @Volatile private var softGain = 1.0
    @Volatile private var normalization = 1.0
    @Volatile private var rateFactor = 1f
    @Volatile private var bassCut = 0f
    /** Played frames per second of the song: above one when sped up. */
    @Volatile private var speed = 1.0
    /** Set when the sound settings change, so the filters are built again with them. */
    @Volatile private var effectsChanged = false

    private val player = Thread({ run() }, "ffmpeg-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }

    init { if (startImmediately) begin() }

    override fun begin() = player.start()

    private fun run() {
        var grabber: FFmpegFrameGrabber? = null
        var filter: FFmpegFrameFilter? = null
        try {
            if (!Ffmpeg.available) throw IllegalStateException("FFmpeg isn't available")
            val g = FFmpegFrameGrabber(address).also { grabber = it }
            g.sampleMode = FrameGrabber.SampleMode.SHORT
            g.audioChannels = 2
            g.setOption("reconnect", "1")
            g.setOption("reconnect_streamed", "1")
            g.setOption("rw_timeout", "15000000")
            if (headers.isNotEmpty()) {
                val requestHeaders = headers.filter { (name, value) -> name.matches(Regex("[A-Za-z0-9-]+")) && '\r' !in value && '\n' !in value }
                g.setOption("headers", requestHeaders.entries.joinToString("") { (name, value) -> "$name: $value\r\n" })
            }
            g.start()
            rate = g.sampleRate.takeIf { it > 0 } ?: 48_000
            duration = (g.lengthInTime / 1000).coerceAtLeast(0)
            val opened = out.open(AudioFormat(rate.toFloat(), 16, 2, true, false))
            if (opened.sampleSizeInBits != 16) throw IllegalStateException("the sound card won't take 16-bit")
            applyVolume()
            if (!paused) out.start()
            filter = effects(rate)
            val first = startAtMs().takeIf { it > 0 }
                ?.let { if (duration > 0) it.coerceAtMost(duration) else it }
            if (first != null) seekTo(g, first)
            onReady(duration)
            var at = msToFrame(first ?: 0)
            val space = com.opentune.playback.dsp.Space3D(rate)
            val stereo = FloatArray(2)
            var emitting = false
            var bytes = ByteArray(0)
            val low = DoubleArray(2)
            while (!disposed) {
                if (effectsChanged) {
                    effectsChanged = false
                    filter?.let { f -> runCatching { f.stop(); f.release() } }
                    runStart = msToFrame(positionMs())
                    lineBase = out.framePosition
                    filter = effects(rate)
                }
                seekRequest?.let { request ->
                    seekRequest = null
                    val target = if (duration > 0) request.coerceAtMost(duration) else request
                    out.flush()
                    seekTo(g, target)
                    filter?.let { f -> runCatching { f.stop(); f.release() }; filter = effects(rate) }
                    at = msToFrame(target)
                    emitting = false
                }
                val frame: Frame = g.grabSamples() ?: break
                val pieces = filter?.let { f -> f.push(frame); generateSequence { f.pullSamples() }.toList() } ?: listOf(frame)
                for (piece in pieces) {
                    val samples = piece.samples?.firstOrNull() as? ShortBuffer ?: continue
                    val count = samples.remaining()
                    if (count == 0) continue
                    if (!emitting) {
                        emitting = true
                        out.flush()
                        runStart = at
                        lineBase = out.framePosition
                        seekingTo = null
                    }
                    if (bytes.size < count * 2) bytes = ByteArray(count * 2)
                    val volume = softGain
                    val gain = normalization
                    val orbit = AppSettings.sound.value.space.periodSeconds
                    val alpha = 1 - exp(-2 * PI * bassCut / rate)
                    if (alpha == 0.0) low.fill(0.0)
                    for (i in 0 until count) {
                        val channel = i % 2
                        val raw = if (orbit > 0 && count % 2 == 0) {
                            if (channel == 0) {
                                stereo[0] = samples.get(samples.position() + i) / 32768f
                                stereo[1] = samples.get(samples.position() + i + 1) / 32768f
                                space.process(stereo, orbit)
                            }
                            (stereo[channel] * 32768).roundToInt().coerceIn(-32768, 32767)
                        } else samples.get(samples.position() + i).toInt()
                        val s = if (alpha == 0.0 && gain == 1.0 && volume == 1.0) raw else {
                            low[channel] += alpha * (raw - low[channel])
                            val scaled = (raw - low[channel]) * gain / 32768.0
                            // Keep boosts linear below the limiter's knee, with room for transients.
                            val limited = if (gain > 1.0 && abs(scaled) > 0.90)
                                Math.copySign(0.90 + 0.10 * tanh((abs(scaled) - 0.90) / 0.10), scaled)
                            else scaled
                            (limited * 32768 * volume).roundToInt().coerceIn(-32768, 32767)
                        }
                        bytes[i * 2] = s.toByte()
                        bytes[i * 2 + 1] = (s shr 8).toByte()
                    }
                    at += (count / 2 * speed).toLong()
                    if (!write(bytes, count * 2)) break
                    if (!started) {
                        started = true
                        onStarted()
                        onPlaying(!paused)
                    }
                }
            }
            if (!disposed) {
                out.drain()
                if (!disposed) onEnd()
            }
        } catch (e: Exception) {
            if (!disposed) onError(e.message ?: "FFmpeg couldn't play it")
        } finally {
            runCatching { filter?.stop(); filter?.release() }
            runCatching { grabber?.stop(); grabber?.release() }
            out.close()
        }
    }

    private fun seekTo(g: FFmpegFrameGrabber, ms: Long) {
        g.setAudioTimestamp(ms.coerceAtLeast(0) * 1000)
    }

    /**
     * The phone's sound settings as FFmpeg filters, or null when they'd change
     * nothing, so a plain song goes straight through.
     */
    private fun effects(rate: Int): FFmpegFrameFilter? {
        val requested = (AppSettings.sound.value.speed * rateFactor).coerceIn(0.5f, 2f)
        val chain = filterChain(requested, AppSettings.equalizer.value, rate, AppSettings.sound.value, AppSettings.playback.value)
        speed = requested.toDouble()
        if (chain == null) return null
        return FFmpegFrameFilter(chain, 2).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            sampleRate = rate
            start()
        }
    }

    /** Writes it all, waiting through a pause; false when a seek or the end cut it short. */
    private fun write(bytes: ByteArray, len: Int): Boolean {
        var at = 0
        var left = len
        while (left > 0) {
            if (disposed || seekRequest != null) return false
            if (paused) {
                synchronized(lock) { while (paused && !disposed && seekRequest == null) lock.wait(200) }
                continue
            }
            val n = out.write(bytes, at, left)
            at += n
            left -= n
        }
        return true
    }

    private fun msToFrame(ms: Long): Long = ms.coerceAtLeast(0) * rate / 1000

    override fun play() {
        paused = false
        if (rate > 0) out.start()
        synchronized(lock) { lock.notifyAll() }
        if (started) onPlaying(true)
    }

    override fun pause() {
        paused = true
        out.stop()
        if (started) onPlaying(false)
    }

    override fun seek(ms: Long) {
        val to = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        seekingTo = to
        seekRequest = to
        out.flush()
        synchronized(lock) { lock.notifyAll() }
    }

    override fun positionMs(): Long {
        seekingTo?.let { return it }
        if (rate == 0 || !started) return startAtMs()
        val frames = runStart + ((out.framePosition - lineBase) * speed).toLong()
        return frames * 1000 / rate
    }

    override fun durationMs(): Long = duration

    /** The equalizer or speed changed in Settings: the song carries on with the new ones. */
    fun refreshEffects() {
        effectsChanged = true
    }

    override fun setRateFactor(factor: Float) {
        if (abs(rateFactor - factor) < 0.005f) return
        rateFactor = factor
        refreshEffects()
    }

    override fun setNormalizationDb(db: Double) {
        normalization = 10.0.pow(db / 20.0)
    }

    override fun setBassCut(frequencyHz: Float) {
        bassCut = frequencyHz
    }

    override fun setVolume(v: Double) {
        volume = v.coerceIn(0.0, 1.0)
        applyVolume()
    }

    /** The line's own gain where it has one; else the samples are scaled. */
    private fun applyVolume() {
        if (rate == 0) return
        val db = if (volume <= 0.0) -80f else (20 * log10(volume)).toFloat()
        val hardware = out.setGainDb(db)
        softGain = if (hardware && volume > 0.0) 1.0 else volume
    }

    override fun dispose() {
        disposed = true
        out.stop()
        out.flush()
        synchronized(lock) { lock.notifyAll() }
    }

    internal companion object {
        /**
         * The FFmpeg filter chain for [speed] and the equalizer [eq]: a peaking
         * filter two-thirds of an octave wide per band (lowered by the biggest
         * boost so it can't clip, as the phone does), bass and treble on the
         * bands below 120 Hz and above 8 kHz, balance as a pan, and tempo
         * without a change of pitch. Null when none of it would change a thing.
         */
        fun filterChain(
            speed: Float,
            eq: com.opentune.data.settings.EqualizerSettings,
            rate: Int = 48_000,
            sound: com.opentune.data.settings.SoundSettings = com.opentune.data.settings.SoundSettings(),
            playback: com.opentune.data.settings.PlaybackSettings = com.opentune.data.settings.PlaybackSettings(),
        ): String? {
            val parts = mutableListOf<String>()
            fun number(value: Number) = "%.3f".format(java.util.Locale.ROOT, value.toDouble())
            eq.headphone?.let { profile ->
                parts += "volume=${number(profile.preampDb.coerceIn(-30f, 12f))}dB"
                profile.filters.filter { it.freqHz >= 10f && it.freqHz < rate / 2f && it.q in 0.05f..100f }.forEach { band ->
                    val filter = when (band.type) {
                        com.opentune.data.settings.FilterType.PEAK -> "equalizer"
                        com.opentune.data.settings.FilterType.LOW_SHELF -> "lowshelf"
                        com.opentune.data.settings.FilterType.HIGH_SHELF -> "highshelf"
                    }
                    parts += "$filter=f=${number(band.freqHz)}:t=q:w=${number(band.q)}:g=${number(band.gainDb.coerceIn(-24f, 24f))}"
                }
            }
            if (eq.enabled) {
                val gains = com.opentune.data.settings.EQ_BANDS_HZ.mapIndexed { i, hz ->
                    (eq.bands.getOrNull(i) ?: 0f) + eq.preampDb +
                        (if (hz <= 120f) eq.bassDb else 0f) + (if (hz >= 8_000f) eq.trebleDb else 0f)
                }
                val headroom = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
                com.opentune.data.settings.EQ_BANDS_HZ.forEachIndexed { i, hz ->
                    val g = gains[i] - headroom
                    if (kotlin.math.abs(g) >= 0.05f) parts += "equalizer=f=${hz.roundToInt()}:t=o:w=0.667:g=${"%.2f".format(java.util.Locale.ROOT, g)}"
                }
                val balance = eq.balance.coerceIn(-1f, 1f)
                if (kotlin.math.abs(balance) >= 0.01f) {
                    val left = if (balance > 0) 1f - balance else 1f
                    val right = if (balance < 0) 1f + balance else 1f
                    parts += "pan=stereo|c0=${"%.3f".format(java.util.Locale.ROOT, left)}*c0|c1=${"%.3f".format(java.util.Locale.ROOT, right)}*c1"
                }
            }
            if (playback.clarity) parts += listOf("volume=-2dB", "highpass=f=28", "equalizer=f=270:t=q:w=0.9:g=-1.8", "highshelf=f=6200:g=1.7")
            if (playback.spatialAudio) parts += "stereotools=mlev=0.90:slev=1.20"
            if (sound.bassBoost > 0) parts += "bass=f=105:g=${number(sound.bassBoost.coerceIn(0, 1000) * 0.006f)}"
            if (sound.eightD.periodSeconds > 0 && sound.space.periodSeconds == 0f) {
                parts += "apulsator=mode=sine:amount=0.8:offset_l=0:offset_r=0.5:timing=hz:hz=${number(1f / sound.eightD.periodSeconds)}"
            }
            if (sound.reverb.preset > 0) {
                val delay = 24 + sound.reverb.preset * 12
                parts += "aecho=0.85:0.90:$delay|${delay + 31}:0.22|0.12"
            }
            val pitch = sound.pitch.coerceIn(0.5f, 2f)
            if (kotlin.math.abs(pitch - 1f) >= 0.01f) parts += listOf("asetrate=${(rate * pitch).roundToInt()}", "aresample=$rate")
            var tempo = speed.coerceIn(0.5f, 2f) / pitch
            if (tempo < 0.5f) { parts += "atempo=0.5"; tempo *= 2 }
            if (tempo > 2f) { parts += "atempo=2"; tempo /= 2 }
            if (kotlin.math.abs(tempo - 1f) >= 0.01f) parts += "atempo=${number(tempo)}"
            return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    }
}
