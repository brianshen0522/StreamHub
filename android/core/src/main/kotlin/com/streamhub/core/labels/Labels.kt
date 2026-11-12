package com.streamhub.core.labels

/**
 * A season label split for a pill: [main] is 「第 N 季」 (or the label itself,
 * truncated, when no number is found) and [rest] the remaining site text
 * (year, update status) for a quieter second line, possibly empty.
 */
data class SeasonParts(val main: String, val rest: String)

/**
 * How episode and season labels are *shown*. A port of
 * `tizen/core/src/labels.rs`.
 *
 * Display only: a label is an opaque key (shared/api/README.md) — progress,
 * favourites and /api/sources all use the raw label, so nothing here may ever
 * be used as a key.
 */
object Labels {

    /** Max characters of a season label shown when no number can be found. */
    const val FALLBACK_CHARS = 14

    /**
     * An episode label for the screen. Bare digits become 「第N集」, keeping the
     * provider's zero padding ("01" → 「第01集」); anything else ("第04集",
     * "第37集已完結", "EP1", "預告片", "HD中字") is shown as given.
     */
    fun episodeCaption(label: String): String {
        val t = label.trim()
        return if (t.isNotEmpty() && t.all { it.isAsciiDigit() }) "第${t}集" else t
    }

    /**
     * Splits a season label into [SeasonParts]. Labels with no recognisable
     * number come back truncated at [FALLBACK_CHARS] characters with an
     * ellipsis, and no second line.
     *
     * Recognised: "Season 2 201510集全", "season 3", "第3季", "第三季", "S02",
     * "S2 2019".
     */
    fun seasonParts(label: String): SeasonParts {
        val t = label.trim()
        fun main(n: Long) = "第 $n 季"

        // "Season N …". ASCII-only lowering keeps indices aligned with [t].
        val lower = t.asciiLowercase()
        val pos = lower.indexOf("season")
        if (pos >= 0) {
            val after = t.substring(pos + "season".length)
            val trimmed = after.trimStart()
            val digits = trimmed.takeWhile { it.isAsciiDigit() }
            val n = parseU32(digits)
            if (n != null) {
                val skip = after.length - trimmed.length + digits.length
                val rest = t.substring(0, pos) + after.substring(skip)
                return SeasonParts(main(n), rest.trim())
            }
        }

        // "第N季" / "第三季"
        val start = t.indexOf('第')
        val end = t.indexOf('季')
        if (start >= 0 && end >= 0 && start < end) {
            val inner = t.substring(start + 1, end).trim()
            val n = parseU32(inner) ?: chineseNumber(inner)
            if (n != null) {
                val rest = t.substring(0, start) + t.substring(end + 1)
                return SeasonParts(main(n), rest.trim())
            }
        }

        // "S02" as its own token.
        val words = t.split(WHITESPACE).filter { it.isNotEmpty() }
        val first = words.firstOrNull()
        if (first != null && (first[0] == 'S' || first[0] == 's')) {
            val digits = first.substring(1)
            if (digits.isNotEmpty() && digits.all { it.isAsciiDigit() }) {
                val n = parseU32(digits)
                if (n != null) return SeasonParts(main(n), words.drop(1).joinToString(" "))
            }
        }

        val count = t.codePointCount(0, t.length)
        return if (count > FALLBACK_CHARS) {
            SeasonParts(t.substring(0, t.offsetByCodePoints(0, FALLBACK_CHARS)) + "…", "")
        } else {
            SeasonParts(t, "")
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private const val CHINESE_DIGITS = "零一二三四五六七八九"

    private fun chineseNumber(s: String): Long? {
        fun digit(c: Char): Long? = CHINESE_DIGITS.indexOf(c).takeIf { it >= 0 }?.toLong()
        return when (s.length) {
            0 -> null
            1 -> if (s[0] == '十') 10 else digit(s[0])
            2 -> when {
                s[0] == '十' -> digit(s[1])?.let { 10 + it }
                s[1] == '十' -> digit(s[0])?.let { it * 10 }
                else -> null
            }
            3 -> if (s[1] == '十') {
                val tens = digit(s[0])
                val ones = digit(s[2])
                if (tens != null && ones != null) tens * 10 + ones else null
            } else {
                null
            }
            else -> null
        }
    }

    /** Rust's `str::parse::<u32>`: an optional '+', then ASCII digits, in range. */
    private fun parseU32(s: String): Long? {
        val digits = s.removePrefix("+")
        if (digits.isEmpty() || !digits.all { it.isAsciiDigit() }) return null
        val value = digits.trimStart('0').ifEmpty { "0" }
        if (value.length > 10) return null
        return value.toLong().takeIf { it <= 0xFFFF_FFFFL }
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun String.asciiLowercase(): String =
        String(CharArray(length) { i -> this[i].let { if (it in 'A'..'Z') it + 32 else it } })
}
