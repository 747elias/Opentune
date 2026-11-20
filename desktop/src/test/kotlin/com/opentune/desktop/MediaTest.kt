package com.opentune.desktop

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.ShortBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AutomixPerformance
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.utils.JavaScript

/** FFmpeg decoding Opus, and GraalJS running YouTube-style player code. */
class MediaTest {
    @Test fun automixFindsTheMusicAfterIntroAndBeforeTrailingSilence() = runBlocking {
        val directory = Files.createTempDirectory("opentune-mix").toFile()
        try {
            val rate = 16_000
            val length = 12
            fun write(track: String, silenceAtStart: Int, silenceAtEnd: Int): String {
                val file = directory.resolve(track)
                val pcm = ByteArray(rate * length * 2)
                for (sample in rate * silenceAtStart until rate * (length - silenceAtEnd)) {
                    val value = (Math.sin(2 * Math.PI * 440 * sample / rate) * 6000).toInt()
                    pcm[sample * 2] = value.toByte()
                    pcm[sample * 2 + 1] = (value shr 8).toByte()
                }
                AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(rate.toFloat(), 16, 1, true, false),
                    (rate * length).toLong()).use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, file) }
                return file.absolutePath
            }
            val ending = write("ending.wav", 0, 2)
            val opening = write("opening.wav", 2, 0)
            val plan = DesktopMixAnalysis.plan(
                ending, opening, length * 1_000L, 5, true, AutomixPerformance.EFFICIENT,
            )
            assertTrue("end ${plan.outEndMs}", plan.outEndMs in 9_500L..10_800L)
            assertTrue("opening ${plan.inFromMs}", plan.inFromMs in 1_500L..2_500L)
            assertTrue("positive overlap", plan.mixAtMs < plan.outEndMs && plan.blendMs > 0)
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun ffmpegPlaysAnOpusFileToTheEnd() {
        assertTrue("FFmpeg should load", Ffmpeg.available)
        // Two seconds of a 440 Hz tone, encoded to Opus in WebM, YouTube's best audio format.
        val file = Files.createTempFile("tone", ".webm").toFile().apply { deleteOnExit() }
        FFmpegFrameRecorder(file, 2).apply {
            format = "webm"
            audioCodec = avcodec.AV_CODEC_ID_OPUS
            sampleRate = 48_000
            audioBitrate = 128_000
            start()
            val samples = ShortArray(48_000 * 2 * 2)
            for (i in 0 until 48_000 * 2) {
                val v = (Math.sin(2 * Math.PI * 440 * i / 48_000.0) * 12_000).toInt().toShort()
                samples[i * 2] = v
                samples[i * 2 + 1] = v
            }
            recordSamples(48_000, 2, ShortBuffer.wrap(samples))
            stop()
            release()
        }
        val out = Capture()
        val ended = CountDownLatch(1)
        var length = 0L
        var problem: String? = null
        val engine = FfmpegEngine(
            file.absolutePath, startAtMs = { 0 }, autoplay = true,
            onReady = { length = it }, onStarted = {}, onPlaying = {},
            onEnd = { ended.countDown() }, onError = { problem = it; ended.countDown() }, out = out,
        )
        assertTrue("it should reach the end", ended.await(20, TimeUnit.SECONDS))
        engine.dispose()
        assertEquals(null, problem)
        // The length comes from the file's header, which FFmpeg's own recorder only estimates; YouTube's is exact.
        assertTrue("length $length", length > 0)
        assertEquals(48_000f, out.format!!.sampleRate)
        // Exactly the two seconds put in, as 16-bit stereo, and actual sound in it, not silence.
        val pcm = out.bytes.toByteArray()
        assertEquals(48_000 * 2 * 4, pcm.size)
        var loudest = 0
        for (i in 0 until pcm.size / 2) loudest = maxOf(loudest, kotlin.math.abs(((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toInt()))
        assertTrue("peak $loudest", loudest in 8_000..16_000)
    }

    @Test fun ffmpegTrimsSilenceWithoutShorteningTheMediaClock() {
        val file = Files.createTempFile("opentune-silence", ".wav").toFile()
        val skipSilence = AppSettings.playback.value.skipSilence
        val rate = 16_000
        val sourceFrames = rate * 4
        val samples = ByteArray(sourceFrames * 2)
        for (frame in 0 until sourceFrames) {
            if (frame in rate until rate * 3) continue
            samples[frame * 2 + 1] = 16
        }
        val out = Capture()
        val ended = CountDownLatch(1)
        var problem: String? = null
        val engine = FfmpegEngine(
            file.absolutePath, startAtMs = { 0 }, autoplay = true,
            onReady = {}, onStarted = {}, onPlaying = {},
            onEnd = { ended.countDown() }, onError = { problem = it; ended.countDown() }, out = out,
            startImmediately = false, floatOutput = true,
        )
        try {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            AudioInputStream(ByteArrayInputStream(samples), format, sourceFrames.toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
            }
            AppSettings.updatePlayback { it.copy(skipSilence = true) }
            engine.begin()
            assertTrue("it should reach the end", ended.await(20, TimeUnit.SECONDS))
            assertEquals(null, problem)
            assertEquals(AudioFormat.Encoding.PCM_FLOAT, out.format?.encoding)
            assertEquals((rate * 3).toLong(), out.framePosition)
            assertEquals(4_000L, engine.positionMs())
        } finally {
            engine.dispose()
            AppSettings.updatePlayback { it.copy(skipSilence = skipSilence) }
            file.delete()
        }
    }

    @Test fun graalJsRunsCurrentJavaScript() {
        // The shape of YouTube's throttling function: a named function over a string.
        assertEquals("cba", JavaScript.run("function f(a){return a.split('').reverse().join('')}", "f", "abc"))
        // Syntax Rhino can't parse: optional chaining, nullish coalescing, Array.at.
        val modern = "var g = (a) => { const b = a?.split(''); return (b?.at(-1) ?? 'x') + b.length; };"
        JavaScript.compileOrThrow(modern)
        assertEquals("c3", JavaScript.run(modern, "g", "abc"))
        try {
            JavaScript.compileOrThrow("function (")
            fail("broken code should be refused")
        } catch (expected: RuntimeException) {
        }
    }

    @Test fun listeningRoomVoiceUsesAndroidCompatibleAacPackets() {
        val pieces = mutableListOf<RoomVoiceCapture.Piece>()
        val encoder = RoomVoiceCapture.AacEncoder { pieces += it }
        for (block in 0 until 24) {
            val pcm = ShortArray(1024) { i ->
                (Math.sin((block * 1024 + i) * 2 * Math.PI * 440 / 16_000) * 8_000).toInt().toShort()
            }
            encoder.write(ShortBuffer.wrap(pcm), 16_000, 1)
        }
        // Live voice must send a piece before the microphone stops.
        assertTrue(pieces.isNotEmpty() && !pieces.last().last)
        encoder.finish()
        val config = requireNotNull(pieces.first().config)
        assertTrue(config.contentEquals(byteArrayOf(0x14, 0x08)))
        assertTrue(pieces.last().last && pieces.drop(1).all { it.config == null })

        val audio = Capture()
        val playback = RoomVoicePlayback(config, audio)
        pieces.forEach { playback.receive(it.frames, it.last) }
        playback.play()
        assertEquals(16_000f, audio.format!!.sampleRate)
        assertEquals(1, audio.format!!.channels)
        assertTrue(audio.bytes.size() >= 16_000 * 2)
    }

    /** A sound card that keeps what it's given, as fast as it's given. */
    private class Capture : PcmOut {
        var format: AudioFormat? = null
        val bytes = ByteArrayOutputStream()
        private var frames = 0L
        override fun open(format: AudioFormat): AudioFormat = format.also { this.format = it }
        override fun write(bytes: ByteArray, off: Int, len: Int): Int {
            this.bytes.write(bytes, off, len)
            frames += len / requireNotNull(format).frameSize
            return len
        }
        override fun start() = Unit
        override fun stop() = Unit
        override fun flush() = Unit
        override fun drain() = Unit
        override fun close() = Unit
        override val framePosition: Long get() = frames
        override fun setGainDb(db: Float) = true
    }
}
