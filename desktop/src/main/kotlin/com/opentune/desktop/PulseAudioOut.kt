package com.opentune.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import javax.sound.sampled.AudioFormat

/** The PipeWire-Pulse or PulseAudio default sink, including systems without a raw ALSA device. */
internal class PulseAudioOut(private val pulse: PulseSimple) : PcmOut {
    private val error = IntByReference()
    private var stream: Pointer? = null
    private var buffer: Memory? = null
    private var frameSize = 0
    private var rate = 0L
    private var written = 0L
    private var played = 0L

    override fun open(format: AudioFormat): AudioFormat {
        frameSize = format.frameSize
        rate = format.sampleRate.toLong()
        val sample = SampleSpec().apply {
            encoding = when (format.sampleSizeInBits) {
                16 -> 3 // PA_SAMPLE_S16LE
                24 -> 9 // PA_SAMPLE_S24LE
                else -> throw IOException("Unsupported PCM depth ${format.sampleSizeInBits}")
            }
            rate = format.sampleRate.toInt()
            channels = format.channels.toByte()
        }
        val attributes = BufferAttributes().apply {
            maximum = (frameSize * rate / 10).toInt()
            target = (frameSize * rate / 20).toInt()
            minimumRequest = (frameSize * rate / 100).toInt()
        }
        stream = pulse.pa_simple_new(null, "OpenTune", 1, null, "Playback", sample, null, attributes, error)
            ?: throw failure("Couldn't open the default audio output")
        buffer = Memory(1024L * frameSize)
        return format
    }

    @Synchronized
    override fun write(bytes: ByteArray, off: Int, len: Int): Int {
        val handle = stream ?: return 0
        val data = checkNotNull(buffer)
        val count = minOf(len, data.size().toInt())
        data.write(0, bytes, off, count)
        if (pulse.pa_simple_write(handle, data, NativeLong(count.toLong()), error) < 0) throw failure("Couldn't write audio")
        written += count / frameSize
        return count
    }

    override fun start() = Unit
    override fun stop() = flush()

    @Synchronized
    override fun flush() {
        val handle = stream ?: return
        val before = framePosition
        if (pulse.pa_simple_flush(handle, error) == 0) written = before
    }

    @Synchronized
    override fun drain() {
        val handle = stream ?: return
        if (pulse.pa_simple_drain(handle, error) == 0) played = written
    }

    @Synchronized
    override fun close() {
        stream?.let(pulse::pa_simple_free)
        stream = null
        buffer?.close()
        buffer = null
    }

    override val framePosition: Long
        @Synchronized get() {
            val handle = stream ?: return played
            val latency = pulse.pa_simple_get_latency(handle, error)
            if (latency >= 0) played = maxOf(played, written - latency * rate / 1_000_000)
            return played
        }

    override fun setGainDb(db: Float) = false

    private fun failure(message: String) = IOException("$message (PulseAudio error ${error.value})")

    companion object {
        private val pulse by lazy {
            if (System.getProperty("os.name").orEmpty().equals("Linux", ignoreCase = true)) {
                runCatching { Native.load("pulse-simple", PulseSimple::class.java) }.getOrNull()
            } else {
                null
            }
        }

        fun create(): PulseAudioOut? = pulse?.let(::PulseAudioOut)
    }

    @Structure.FieldOrder("encoding", "rate", "channels")
    class SampleSpec : Structure() {
        @JvmField var encoding = 0
        @JvmField var rate = 0
        @JvmField var channels: Byte = 0
    }

    @Structure.FieldOrder("maximum", "target", "prebuffer", "minimumRequest", "fragment")
    class BufferAttributes : Structure() {
        @JvmField var maximum = -1
        @JvmField var target = -1
        @JvmField var prebuffer = -1
        @JvmField var minimumRequest = -1
        @JvmField var fragment = -1
    }

    interface PulseSimple : Library {
        fun pa_simple_new(server: String?, name: String, direction: Int, device: String?, description: String,
                          sample: SampleSpec, channelMap: Pointer?, buffer: BufferAttributes, error: IntByReference): Pointer?
        fun pa_simple_write(stream: Pointer, data: Pointer, count: NativeLong, error: IntByReference): Int
        fun pa_simple_get_latency(stream: Pointer, error: IntByReference): Long
        fun pa_simple_flush(stream: Pointer, error: IntByReference): Int
        fun pa_simple_drain(stream: Pointer, error: IntByReference): Int
        fun pa_simple_free(stream: Pointer)
    }
}
