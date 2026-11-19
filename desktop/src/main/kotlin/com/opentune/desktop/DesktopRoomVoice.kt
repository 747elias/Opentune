package com.opentune.desktop

import com.opentune.data.together.SpeechGate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber

internal enum class RoomVoiceMode { Note, Talk, Call }

private const val VOICE_RATE = 16_000
private const val FRAME_SAMPLES = 1_024
private const val CHUNK_FRAMES = 12

/** The same 16 kHz mono AAC-LC packets the Android room client uses. */
internal class RoomVoiceCapture(private val mode: RoomVoiceMode) {
    @Volatile var muted = false
    @Volatile private var stopped = false
    @Volatile private var input: TargetDataLine? = null

    data class Piece(val config: ByteArray?, val frames: List<ByteArray>, val last: Boolean)

    fun stop() {
        stopped = true
        input?.close()
    }

    fun record(speaking: (Boolean) -> Unit, onPiece: (Piece) -> Unit) {
        check(Ffmpeg.available) { "The room's audio codec isn't available." }
        if (stopped) return
        val format = sequenceOf(VOICE_RATE, 48_000, 44_100)
            .flatMap { rate -> sequenceOf(1, 2).map { channels -> AudioFormat(rate.toFloat(), 16, channels, true, false) } }
            .firstOrNull { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, it)) }
            ?: error("No compatible microphone was found.")
        val line = AudioSystem.getTargetDataLine(format)
        input = line
        val sourceRate = format.sampleRate.toInt()
        val samplesPerRead = FRAME_SAMPLES * sourceRate / VOICE_RATE
        val pcm = ByteArray(samplesPerRead * format.frameSize)
        val speech = SpeechGate()
        var encoder: AacEncoder? = null
        var readSamples = 0L
        val seconds = when (mode) {
            RoomVoiceMode.Note -> 60
            RoomVoiceMode.Talk -> 30
            RoomVoiceMode.Call -> 6 * 60 * 60
        }
        try {
            line.open(format)
            if (stopped) return
            line.start()
            while (!stopped && readSamples < seconds.toLong() * sourceRate) {
                val count = try {
                    line.read(pcm, 0, pcm.size)
                } catch (failure: IllegalStateException) {
                    if (stopped) break else throw failure
                }
                if (count <= 0) break
                readSamples += count / format.frameSize
                val send = mode != RoomVoiceMode.Call || (speech.next(SpeechGate.rms(pcm, count)) && !muted)
                if (send) {
                    val active = encoder ?: AacEncoder(onPiece).also {
                        encoder = it
                        speaking(mode != RoomVoiceMode.Note)
                    }
                    val samples = ByteBuffer.wrap(pcm, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    active.write(samples, sourceRate, format.channels)
                } else {
                    val active = encoder
                    encoder = null
                    if (active != null) {
                        active.finish()
                        speaking(false)
                    }
                }
            }
        } finally {
            input = null
            line.close()
            try {
                encoder?.finish()
            } finally {
                speaking(false)
            }
        }
    }

    internal class AacEncoder(private val onPiece: (Piece) -> Unit) {
        private val output = ByteArrayOutputStream()
        private val recorder = FFmpegFrameRecorder(output, 1).apply {
            format = "adts"
            audioCodec = avcodec.AV_CODEC_ID_AAC
            sampleRate = VOICE_RATE
            audioBitrate = 24_000
            setOption("flush_packets", "1")
        }
        private val pending = mutableListOf<ByteArray>()
        private var firstConfig: ByteArray? = null

        init {
            try {
                recorder.start()
            } catch (failure: Exception) {
                recorder.release()
                throw failure
            }
        }

        fun write(samples: ShortBuffer, rate: Int, channels: Int) {
            recorder.recordSamples(rate, channels, samples)
            readPackets()
        }

        fun finish() {
            try {
                recorder.stop()
                readPackets()
                onPiece(Piece(firstConfig, pending.toList(), last = true))
            } finally {
                recorder.release()
            }
        }

        private fun readPackets() {
            val bytes = output.toByteArray()
            output.reset()
            var offset = 0
            while (offset + 7 <= bytes.size) {
                check(bytes[offset].u8() == 255 && (bytes[offset + 1].u8() and 0xf0) == 0xf0) { "Invalid AAC packet" }
                val adts = if (bytes[offset + 1].u8() and 1 == 1) 7 else 9
                val size = ((bytes[offset + 3].u8() and 3) shl 11) or
                    (bytes[offset + 4].u8() shl 3) or (bytes[offset + 5].u8() ushr 5)
                check(size >= adts) { "Invalid AAC packet size" }
                if (offset + size > bytes.size) break
                if (pending.isEmpty() && firstConfig == null && offset == 0 && !sentConfig) {
                    val objectType = (bytes[offset + 2].u8() ushr 6) + 1
                    val rate = (bytes[offset + 2].u8() ushr 2) and 15
                    val channels = ((bytes[offset + 2].u8() and 1) shl 2) or (bytes[offset + 3].u8() ushr 6)
                    firstConfig = byteArrayOf(((objectType shl 3) or (rate ushr 1)).toByte(), (((rate and 1) shl 7) or (channels shl 3)).toByte())
                }
                pending += bytes.copyOfRange(offset + adts, offset + size)
                offset += size
                if (pending.size == CHUNK_FRAMES) {
                    onPiece(Piece(firstConfig, pending.toList(), last = false))
                    firstConfig = null
                    sentConfig = true
                    pending.clear()
                }
            }
            output.write(bytes, offset, bytes.size - offset)
        }

        private var sentConfig = false
    }
}

/** Restores ADTS headers around raw AAC packets so FFmpeg can decode them. */
internal fun voiceAdts(config: ByteArray, frames: List<ByteArray>): ByteArray? {
    if (config.size < 2) return null
    val objectType = config[0].u8() ushr 3
    val rate = ((config[0].u8() and 7) shl 1) or (config[1].u8() ushr 7)
    val channels = (config[1].u8() ushr 3) and 15
    if (objectType != 2 || rate > 12 || channels !in 1..7) return null
    val out = ByteArrayOutputStream()
    for (frame in frames) {
        val size = frame.size + 7
        if (size > 0x1fff || frame.isEmpty()) return null
        out.write(0xff)
        out.write(0xf1)
        out.write(((objectType - 1) shl 6) or (rate shl 2) or (channels ushr 2))
        out.write(((channels and 3) shl 6) or (size ushr 11))
        out.write((size ushr 3) and 255)
        out.write(((size and 7) shl 5) or 0x1f)
        out.write(0xfc)
        out.write(frame)
    }
    return out.toByteArray()
}

private fun Byte.u8() = toInt() and 0xff

/** Plays one AAC note or live talk stream as its pieces arrive. */
internal class RoomVoicePlayback(private val config: ByteArray, private val audio: PcmOut = SoundCard()) {
    private val input = VoiceInput()
    @Volatile private var stopped = false

    fun receive(frames: List<ByteArray>, done: Boolean) {
        if (frames.isNotEmpty()) {
            val bytes = voiceAdts(config, frames)
            if (bytes == null || !input.packets.offer(bytes)) {
                stop()
                return
            }
        }
        if (done) input.packets.offer(ByteArray(0))
    }

    fun stop() {
        stopped = true
        input.close()
        audio.close()
    }

    fun play() {
        if (stopped) return
        check(Ffmpeg.available) { "The room's audio codec isn't available." }
        val grabber = FFmpegFrameGrabber(input, 0)
        try {
            grabber.format = "aac"
            grabber.sampleMode = FrameGrabber.SampleMode.SHORT
            grabber.audioChannels = 1
            grabber.start(false)
            val first = grabber.grabSamples() ?: return
            if (stopped) return
            audio.open(AudioFormat(grabber.sampleRate.toFloat(), 16, 1, true, false))
            audio.start()
            var frame: org.bytedeco.javacv.Frame? = first
            while (!stopped && frame != null) {
                val samples = frame.samples?.firstOrNull() as? ShortBuffer
                if (samples != null) {
                    val bytes = ByteBuffer.allocate(samples.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(samples)
                    audio.write(bytes.array(), 0, bytes.capacity())
                }
                frame = grabber.grabSamples()
            }
            if (!stopped) audio.drain()
        } finally {
            audio.close()
            grabber.close()
        }
    }

    private class VoiceInput : InputStream() {
        val packets = LinkedBlockingQueue<ByteArray>(32)
        private var buffer = ByteArray(0)
        private var offset = 0
        @Volatile private var ended = false

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].u8()
        }

        override fun read(destination: ByteArray, from: Int, length: Int): Int {
            if (length == 0) return 0
            if (ended) return -1
            if (offset == buffer.size) {
                buffer = packets.poll(4, TimeUnit.SECONDS) ?: ByteArray(0)
                offset = 0
                if (buffer.isEmpty()) {
                    ended = true
                    return -1
                }
            }
            val count = minOf(length, buffer.size - offset)
            buffer.copyInto(destination, from, offset, offset + count)
            offset += count
            return count
        }

        override fun close() {
            ended = true
            packets.clear()
            packets.offer(ByteArray(0))
        }
    }
}
