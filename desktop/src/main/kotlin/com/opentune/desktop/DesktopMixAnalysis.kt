package com.opentune.desktop

import com.opentune.data.settings.AutomixPerformance
import com.opentune.playback.mix.Ending
import com.opentune.playback.mix.Features
import com.opentune.playback.mix.MixPlan
import com.opentune.playback.mix.Opening
import com.opentune.playback.mix.VoiceBand
import com.opentune.playback.mix.analyzeEnding
import com.opentune.playback.mix.analyzeOpening
import com.opentune.playback.mix.automixPlan
import com.opentune.playback.mix.chroma
import com.opentune.playback.mix.crossfadePlan
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Decodes analysis windows and feeds the same transition planner as the phone. */
internal object DesktopMixAnalysis {
    private val worker = Dispatchers.IO.limitedParallelism(2)
    private data class Decoded(val features: Features, val notes: FloatArray?)
    private data class EndingKey(val address: String, val length: Long, val span: Long)
    private data class OpeningKey(val address: String, val span: Long)
    private val endings = LinkedHashMap<EndingKey, Ending?>()
    private val openings = LinkedHashMap<OpeningKey, Opening?>()

    suspend fun plan(
        outgoing: String,
        incoming: String,
        durationMs: Long,
        seconds: Int,
        automix: Boolean,
        performance: AutomixPerformance,
    ): MixPlan = coroutineScope {
        val long = performance == AutomixPerformance.PERFORMANCE
        val endingSpan = if (long) 48_000L else 26_000L
        val openingSpan = if (long) 26_000L else 15_000L
        val tail = EndingKey(outgoing, durationMs, endingSpan)
        val head = OpeningKey(incoming, openingSpan)
        val ending: Ending?
        val opening: Opening?
        if (performance == AutomixPerformance.EFFICIENT) {
            ending = ending(tail)
            opening = opening(head)
        } else {
            val out = async { ending(tail) }
            val next = async { opening(head) }
            ending = out.await()
            opening = next.await()
        }
        if (automix) automixPlan(durationMs, ending, opening)
        else crossfadePlan(durationMs, seconds * 1000L, ending, opening)
    }

    private suspend fun ending(key: EndingKey): Ending? {
        synchronized(endings) { endings[key]?.let { return it } }
        val decoded = decode(key.address, (key.length - key.span).coerceAtLeast(0), key.length) ?: return null
        return analyzeEnding(decoded.features, key.length, decoded.notes)?.also {
            synchronized(endings) {
                endings[key] = it
                if (endings.size > 24) endings.remove(endings.keys.first())
            }
        }
    }

    private suspend fun opening(key: OpeningKey): Opening? {
        synchronized(openings) { openings[key]?.let { return it } }
        val decoded = decode(key.address, 0, key.span) ?: return null
        return analyzeOpening(decoded.features, decoded.notes)?.also {
            synchronized(openings) {
                openings[key] = it
                if (openings.size > 24) openings.remove(openings.keys.first())
            }
        }
    }

    private suspend fun decode(address: String, fromMs: Long, toMs: Long): Decoded? = withContext(worker) {
        if (!Ffmpeg.available) return@withContext null
        try {
            org.bytedeco.javacv.FFmpegFrameGrabber(address).use { source ->
                source.setOption("rw_timeout", "7000000")
                source.audioChannels = 1
                source.sampleMode = org.bytedeco.javacv.FrameGrabber.SampleMode.SHORT
                source.start()
                val rate = source.sampleRate.takeIf { it > 0 } ?: return@withContext null
                if (fromMs > 0) source.setAudioTimestamp(fromMs * 1000)
                val hop = (rate / 100.0).roundToInt().coerceAtLeast(1)
                val decimation = (rate / 11_025.0).roundToInt().coerceAtLeast(1)
                val bins = ((toMs - fromMs) / 10).toInt() + 2
                val energy = FloatArray(bins)
                val bass = FloatArray(bins)
                val mid = FloatArray(bins)
                val slow = FloatArray(bins * hop / decimation + 1)
                val voice = VoiceBand(rate)
                val alpha = (1 - exp(-2 * PI * 150 / rate)).toFloat()
                var index = 0
                var count = 0
                var sampleCount = 0
                var firstUs = -1L
                var low1 = 0f
                var low2 = 0f
                var slowSum = 0f
                var slowCount = 0
                while (index < bins) {
                    ensureActive()
                    val frame = source.grabSamples() ?: break
                    val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                    if (frame.timestamp >= toMs * 1000) break
                    val start = ((fromMs * 1000 - frame.timestamp).coerceAtLeast(0) * rate / 1_000_000).toInt()
                    val end = minOf(samples.remaining(), ((toMs * 1000 - frame.timestamp) * rate / 1_000_000).toInt())
                    for (i in start until end) {
                        if (firstUs < 0) firstUs = frame.timestamp + i * 1_000_000L / rate
                        val value = samples.get(samples.position() + i) / 32768f
                        low1 += alpha * (value - low1)
                        low2 += alpha * (low1 - low2)
                        val band = voice.next(value)
                        energy[index] += value * value
                        bass[index] += low2 * low2
                        mid[index] += band * band
                        slowSum += value
                        if (++slowCount == decimation && sampleCount < slow.size) {
                            slow[sampleCount++] = slowSum / slowCount
                            slowCount = 0
                            slowSum = 0f
                        }
                        if (++count == hop) {
                            energy[index] /= count.toFloat()
                            bass[index] /= count.toFloat()
                            mid[index] /= count.toFloat()
                            count = 0
                            if (++index == bins) break
                        }
                    }
                }
                if (index < 100) return@withContext null
                val features = Features.of(firstUs / 1000, hop * 1000.0 / rate, energy.copyOf(index), bass.copyOf(index), mid.copyOf(index))
                Decoded(features, chroma(slow, sampleCount, (rate.toDouble() / decimation).roundToInt()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
