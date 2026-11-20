package com.opentune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomanizedLyricsTest {
    @Test fun showsLatinReadingsAlongsideTheOriginalLyrics() {
        val results = romanizeLyrics(listOf("Already in Latin", "사랑해", "आजा पिया"))
        assertNull(results[0])
        assertEquals("saranghae", results[1])
        assertEquals("aja piya", results[2])
    }
}
