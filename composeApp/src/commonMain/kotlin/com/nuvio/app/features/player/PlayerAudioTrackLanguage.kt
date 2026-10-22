package com.nuvio.app.features.player

import com.nuvio.app.features.streams.streamLanguageCode

// Fork: releases often tag every audio track with the original language and name the dub only in
// the title ("PT-BR E-AC-3 2.0" tagged jpn), so a language the title names wins over the tag.

private val TitleVariantPatterns = listOf(
    "pt-br" to titlePattern(
        "pt[-_ ]?br", "pob", "dublad[oa]", "brasileiro", "brazilian",
        "portugu[eê]s[ea]? ?\\(?(do )?(brasil|brazil|br)\\)?",
    ),
    "pt" to titlePattern("pt[-_]?pt", "portugu[eê]s[ea]? ?\\(?(de )?portugal\\)?", "european portuguese"),
    "es-419" to titlePattern("es[-_]?(419|la|lat)", "latino", "latam", "latin american", "espanhol ?\\(la\\)"),
)

// Codec names holding a language code: DTS-ES (Spanish) and HE-AAC (Hebrew).
private val TitleCodecPattern = titlePattern("dts[-_ ]?es", "he[-_ ]?aac(v2)?")

private val TitleWordPattern = Regex("[A-Za-zÀ-ɏ]+")

// Names streamLanguageCode doesn't know: Portuguese written without accents, and Spanish.
private val TitleLanguageNames = mapOf(
    "japones" to "ja",
    "japonés" to "ja",
    "inglés" to "en",
    "frances" to "fr",
    "francés" to "fr",
    "alemao" to "de",
    "alemán" to "de",
    "chines" to "zh",
    "español" to "es",
)

private fun titlePattern(vararg tokens: String): Regex =
    Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|$)")

/**
 * The one language [title] names ("PT-BR", "JPN", "Japanese", "Japonês", "Dublado"), or null when it
 * names none or several. Two- and three-letter words count only in capitals: in plain words, "de" or
 * "no" are not languages.
 */
internal fun audioTitleLanguage(title: String?): String? {
    if (title.isNullOrBlank()) return null
    val text = title.toCharArray()
    // Char by char, so positions in the lowercase copy match the title's.
    val lower = CharArray(text.size) { text[it].lowercaseChar() }
    fun blank(range: IntRange) = range.forEach { text[it] = ' '; lower[it] = ' ' }

    TitleCodecPattern.findAll(lower.concatToString()).forEach { blank(it.range) }
    val found = mutableSetOf<String>()
    TitleVariantPatterns.forEach { (code, pattern) ->
        pattern.findAll(lower.concatToString()).forEach { match ->
            found += code
            blank(match.range)
        }
    }
    TitleWordPattern.findAll(text.concatToString()).forEach { match ->
        val word = match.value
        val code = if (word.length <= 3) {
            if (word.length >= 2 && word.all { it.isUpperCase() }) streamLanguageCode(word.lowercase()) else null
        } else {
            TitleLanguageNames[word.lowercase()] ?: streamLanguageCode(word.lowercase())
        }
        if (code != null) found += code
    }
    // A variant also names its base language ("PT-BR Português").
    return found.filterNot { base -> found.any { it != base && it.substringBefore('-') == base } }.singleOrNull()
}

/** The language heard on this track: the one its title names, else its tag. */
internal fun AudioTrack.spokenLanguage(): String? =
    audioTitleLanguage(label) ?: normalizeLanguageCode(language)

/**
 * Position in [tracks] of the first track speaking [language] (by [spokenLanguage]): the same variant
 * first ("pt-br" before "pt"), then the same base language; -1 when none does.
 */
internal fun findSpokenAudioTrackPosition(tracks: List<AudioTrack>, language: String?): Int {
    val target = normalizeLanguageCode(language) ?: return -1
    val spoken = tracks.map { it.spokenLanguage() }
    val sameVariant = spoken.indexOf(target)
    if (sameVariant >= 0) return sameVariant
    return spoken.indexOfFirst { languageMatchesPreference(it, target) }
}
