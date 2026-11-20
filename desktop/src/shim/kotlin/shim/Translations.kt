package com.opentune.i18n

import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import org.json.JSONObject

object Tr {
    val TAGS = listOf(
        "ar", "bn", "cs", "de", "el", "es", "fa", "fr", "gu", "hi", "hu", "id", "it", "ja", "kn", "ko", "ml", "mr", "ms",
        "nl", "pa", "pl", "pt", "ro", "ru", "sv", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh-CN", "zh-TW",
    )

    private val translations = mutableStateOf<Map<String, String>>(emptyMap())

    fun use(tag: String) {
        val locale = if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
        val language = if (locale.language == "zh") {
            if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
        } else locale.language
        val table = if (language !in TAGS) emptyMap() else runCatching {
            val text = Tr::class.java.getResourceAsStream("/i18n/$language.json")?.bufferedReader()?.use { it.readText() }
                ?: return@runCatching emptyMap()
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        translations.value = table
    }

    fun t(english: String): String = translations.value[english]?.takeIf { it.isNotBlank() } ?: english

    /** [template] with `{0}`, `{1}`… replaced by [args] in order. */
    fun f(english: String, vararg args: Any?): String {
        val template = t(english)
        if (args.isEmpty() || '{' !in template) return template
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val close = template.indexOf('}', i + 1)
                val index = if (close > i + 1) template.substring(i + 1, close).toIntOrNull() else null
                if (index != null && index in args.indices) {
                    out.append(args[index]); i = close + 1; continue
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }
}
