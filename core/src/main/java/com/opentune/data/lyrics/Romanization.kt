package com.opentune.data.lyrics

/**
 * Lyrics in Latin letters, for following along in a script you don't read:
 * Hindi, Korean, Japanese kana, Russian and the rest become plain ASCII
 * ("आजा पिया" reads "aja piya"). Uses the system's ICU transliterator. Chinese characters become toneless pinyin, except
 * in Japanese lyrics, where kanji are left as they are: their reading
 * depends on the word, and pinyin would be wrong.
 */
internal class Romanization(
    private val any: (String) -> String,
    private val han: (String) -> String,
) {
    /** Each line in Latin letters, or null where it's already in them (or can't be). */
    fun lines(texts: List<String>): List<String?> {
        val japanese = texts.any(::containsKana)
        return texts.map { romanize(it, japanese) }
    }

    fun romanize(text: String, japanese: Boolean = containsKana(text)): String? {
        if (text.none(::needsIt)) return null
        val out = StringBuilder()
        // Runs of Chinese characters, of Korean, and of everything else, each read its own way.
        var i = 0
        while (i < text.length) {
            val kind = kindOf(text[i])
            var j = i
            while (j < text.length && kindOf(text[j]) == kind) j++
            val run = text.substring(i, j)
            out.append(
                when (kind) {
                    HANGUL -> Hangul.romanize(run)
                    HAN -> if (japanese) run else " " + han(run) + " "
                    else -> any(run)
                },
            )
            i = j
        }
        return plain(out.toString()).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it != text.trim() }
    }

    /** A letter outside the Latin alphabets. */
    private fun needsIt(c: Char) = Character.isLetter(c) && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN

    private fun kindOf(c: Char) = when {
        c in '\uAC00'..'\uD7A3' -> HANGUL
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> HAN
        else -> OTHER
    }

    companion object {
        private const val OTHER = 0
        private const val HAN = 1
        private const val HANGUL = 2

        fun containsKana(text: String): Boolean = text.any { c ->
            val script = Character.UnicodeScript.of(c.code)
            script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
        }

        /**
         * Plain letters only. ICU leaves some marks Latin-ASCII doesn't reach, most
         * of all for Urdu and Arabic: a tilde for a nasal "ں" with no letter of its
         * own to sit on, "ʰ" for an aspirate. Phones draw a mark without a letter as
         * a dotted circle, so marks go, "ʰ" becomes "h" and other modifier letters
         * ("ʾ", "ʿ") are dropped.
         */
        internal fun plain(s: String): String {
            val out = StringBuilder(s.length)
            for (c in java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)) {
                when (Character.getType(c).toByte()) {
                    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> Unit
                    Character.MODIFIER_LETTER -> when (c) {
                        'ʰ' -> out.append('h')
                        'ʷ' -> out.append('w')
                        'ʸ' -> out.append('y')
                        else -> Unit
                    }
                    else -> if (c != '\u25CC') out.append(c)
                }
            }
            return java.text.Normalizer.normalize(out, java.text.Normalizer.Form.NFC)
        }
    }
}

/**
 * Korean in the Revised Romanization, the spelling Korean fans read: 사랑해
 * is "saranghae", where ICU's letter-for-letter scheme writes "salanghae".
 * A final consonant before a silent ㅇ carries over into the next syllable,
 * and ㄹ is "r" between vowels and "l" next to another ㄹ.
 */
internal object Hangul {
    private val INITIAL = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIAL = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    /** A final consonant as it sounds at the end of a syllable. */
    private val FINAL = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "p", "t", "t", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    /** The same, carried into a following syllable that starts with a silent ㅇ. */
    private val LINKED = arrayOf("", "g", "kk", "ks", "n", "nj", "n", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "r", "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "")

    private const val SILENT = 11
    private const val RIEUL = 5
    private const val FINAL_RIEUL = 8

    /** [run] is all syllable blocks (U+AC00..U+D7A3), one word. */
    fun romanize(run: String): String {
        val codes = run.map { it.code - 0xAC00 }
        val out = StringBuilder()
        codes.forEachIndexed { i, c ->
            val initial = c / (21 * 28)
            val medial = c % (21 * 28) / 28
            val final = c % 28
            val prevFinal = if (i > 0) codes[i - 1] % 28 else 0
            out.append(
                when {
                    initial == SILENT -> ""
                    initial == RIEUL && prevFinal == FINAL_RIEUL -> "l"
                    else -> INITIAL[initial]
                },
            )
            out.append(MEDIAL[medial])
            val nextInitial = codes.getOrNull(i + 1)?.let { it / (21 * 28) }
            out.append(if (final != 0 && nextInitial == SILENT) LINKED[final] else FINAL[final])
        }
        return out.toString()
    }
}
