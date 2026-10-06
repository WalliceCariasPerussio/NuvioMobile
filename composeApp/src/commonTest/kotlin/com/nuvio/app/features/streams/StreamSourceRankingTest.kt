package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

// Fork: autoplay "best in my language" (StreamSourceRanking.kt) and the audio flags it reads.
class StreamSourceRankingTest {

    @Test
    fun languageOnlyAboveTheCapFallsBackToTheNextLanguage() {
        val ptBr4k = stream("pt-4k", "🇧🇷 2160p")
        val en1080 = stream("en-1080", "🇬🇧 1080p")

        val ranked = rankBestInPreferredLanguage(
            streams = listOf(ptBr4k, en1080),
            preferredLanguages = listOf("pt-br", "en"),
            maxQuality = StreamQualityBucket.FHD_1080,
        )

        assertEquals(listOf(en1080), ranked)
    }

    @Test
    fun noPreferredLanguageAcceptsAnyLanguageWithinTheCap() {
        val en4k = stream("en-4k", "🇬🇧 2160p")
        val ja720 = stream("ja-720", "🇯🇵 720p")

        val ranked = rankBestInPreferredLanguage(
            streams = listOf(en4k, ja720),
            preferredLanguages = emptyList(),
            maxQuality = StreamQualityBucket.FHD_1080,
        )

        assertEquals(listOf(ja720), ranked)
    }

    @Test
    fun adjacentFlagsAreReadInPairs() {
        // 🇬🇧🇷🇺 has the letters B+R (Brazil) in the middle, 🇺🇸🇪🇸 has S+E (Sweden).
        assertEquals(listOf("en", "ru"), stream("gb-ru", "🇬🇧🇷🇺 1080p").streamTraits().audioLanguages)
        assertEquals(listOf("en", "es"), stream("us-es", "🇺🇸🇪🇸 1080p").streamTraits().audioLanguages)
    }

    private fun stream(id: String, name: String) = StreamItem(
        name = name,
        url = "https://example.com/$id.mkv",
        addonName = "Addon",
        addonId = "addon:test",
    )
}
