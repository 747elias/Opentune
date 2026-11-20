package com.opentune.data.lyrics

import android.icu.text.Transliterator
import android.os.Build

/** Uses Android's system transliterator on Android 10 and later. */
object Romanizer {
    private val engine by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
        else runCatching {
            val any = Transliterator.getInstance("Any-Latin; Latin-ASCII")
            val han = runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
            Romanization({ synchronized(any) { any.transliterate(it) } }, { text ->
                han?.let { synchronized(it) { it.transliterate(text) } } ?: text
            })
        }.getOrNull()
    }

    val available: Boolean get() = engine != null

    fun lines(texts: List<String>): List<String?> = engine?.lines(texts) ?: texts.map { null }

    fun romanize(text: String, japanese: Boolean = Romanization.containsKana(text)): String? =
        engine?.romanize(text, japanese)

    internal fun plain(text: String): String = Romanization.plain(text)
}
