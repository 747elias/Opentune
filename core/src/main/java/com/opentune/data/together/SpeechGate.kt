package com.opentune.data.together

/**
 * Says, frame by frame, whether someone is speaking: louder than the room's
 * own noise for two frames in a row opens it, and it stays open for
 * [hangFrames] after the voice stops, so the gaps between words don't cut
 * a sentence up. The noise floor follows the quiet parts, so a fan or
 * traffic doesn't hold it open.
 */
internal class SpeechGate(private val hangFrames: Int = 10) {
    private var floor = 200.0
    private var loudRun = 0
    private var hang = 0
    var open = false
        private set

    /** The next frame's loudness, as RMS of 16-bit samples; whether the gate is open after it. */
    fun next(rms: Double): Boolean {
        val loud = rms > maxOf(floor * 3.0, 350.0)
        // Down quickly, up slowly, and slower still while someone talks.
        floor = when {
            rms < floor -> floor * 0.8 + rms * 0.2
            open -> floor * 0.998 + rms * 0.002
            else -> floor * 0.98 + rms * 0.02
        }.coerceIn(30.0, 6_000.0)
        if (loud) {
            loudRun++
            if (loudRun >= 2) { open = true; hang = hangFrames }
        } else {
            loudRun = 0
            if (open && --hang <= 0) open = false
        }
        return open
    }

    companion object {
        fun rms(pcm: ByteArray, bytes: Int): Double {
            var sum = 0.0
            val n = bytes / 2
            if (n == 0) return 0.0
            for (i in 0 until n) {
                val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
                sum += v * v
            }
            return kotlin.math.sqrt(sum / n)
        }
    }
}
