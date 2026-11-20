package com.opentune.desktop

import kotlin.math.abs

/** Maps frames heard by the listener to the source clock after silence or tempo changes. */
internal class PcmTimeline {
    private data class Span(val output: Long, val source: Double, val speed: Double)
    private val spans = ArrayDeque<Span>()

    @Synchronized
    fun reset(output: Long, source: Double, speed: Double) {
        spans.clear()
        spans.addLast(Span(output, source, speed))
    }

    @Synchronized
    fun append(output: Long, source: Double, speed: Double) {
        val last = spans.lastOrNull()
        if (last != null) {
            if (last.speed == speed && abs(last.source + (output - last.output) * speed - source) < 0.01) return
            if (last.output == output) spans.removeLast()
        }
        spans.addLast(Span(output, source, speed))
    }

    @Synchronized
    fun sourceFrame(output: Long): Double {
        while (spans.size > 1 && spans[1].output <= output) spans.removeFirst()
        val first = spans.firstOrNull() ?: return 0.0
        return first.source + (output - first.output).coerceAtLeast(0) * first.speed
    }
}
