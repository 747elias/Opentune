package com.opentune.data.lyrics

/** The line selection and output sizes shared by Android and desktop lyric cards. */
object LyricCardFormat {
    const val WIDTH = 1080
    const val POST_HEIGHT = 1350
    const val STORY_HEIGHT = 1920
    const val MAX_LINES = 6

    fun height(story: Boolean) = if (story) STORY_HEIGHT else POST_HEIGHT

    fun selectedLines(lines: List<String>, positions: Iterable<Int>): List<String> =
        positions.distinct().sorted().mapNotNull { lines.getOrNull(it) }.take(MAX_LINES)
}
