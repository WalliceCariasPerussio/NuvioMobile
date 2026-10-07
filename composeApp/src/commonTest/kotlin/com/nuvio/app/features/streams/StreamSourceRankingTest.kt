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

    @Test
    fun nextEpisodeKeepsTheAddonAudioAndQualityPlayingEvenOutsideThePreferredLanguage() {
        val playing = stream("e9", "🇯🇵 720p", addonId = "animesdigital")
        val otherAddon1080 = stream("t-1080", "🇯🇵 1080p", addonId = "torrentio")
        val sameAddon1080 = stream("ad-1080", "🇯🇵 1080p", addonId = "animesdigital")
        val sameAddon720 = stream("ad-720", "🇯🇵 720p", addonId = "animesdigital")

        assertEquals(sameAddon720, selectContinuation(listOf(otherAddon1080, sameAddon1080, sameAddon720), playing))
    }

    @Test
    fun nextEpisodeResumedFromASavedLinkKeepsTheSourceWithTheSameName() {
        // A saved link keeps only the name: no addon, no audio line.
        val playing = stream("e9", "AnimesDigital LEG [Blogger] - 720p", addonId = "")
        val leg1080 = stream("leg-1080", "AnimesDigital LEG - 1080p\n🔊 Japonês", addonId = "animesdigital")
        val blogger = stream("blogger-720", "AnimesDigital LEG [Blogger] - 720p", addonId = "animesdigital")

        assertEquals(blogger, selectContinuation(listOf(leg1080, blogger), playing))
    }

    @Test
    fun nextEpisodeKeepsTheBingeGroupWithoutSwappingTheDubForTheOriginal() {
        val group = StreamBehaviorHints(bingeGroup = "torrentio|1080p")
        val playing = stream("e9", "🇯🇵 1080p", addonId = "torrentio").copy(behaviorHints = group)
        val dub = stream("dub", "🇧🇷 1080p", addonId = "torrentio").copy(behaviorHints = group)
        val original = stream("ja", "🇯🇵 1080p", addonId = "torrentio").copy(behaviorHints = group)

        assertEquals(original, selectContinuation(listOf(dub, original), playing))
    }

    @Test
    fun nextEpisodeWithoutTheSourcePlayingLeavesTheChoiceToTheLanguageRanking() {
        val playing = stream("e9", "🇯🇵 720p", addonId = "animesdigital")
        val otherAddon = stream("t-720", "🇯🇵 720p", addonId = "torrentio")
        val sameAddonOtherAudio = stream("dub-720", "🇧🇷 720p", addonId = "animesdigital")

        assertEquals(null, selectContinuation(listOf(otherAddon, sameAddonOtherAudio), playing))
    }

    private fun stream(id: String, name: String, addonId: String = "addon:test") = StreamItem(
        name = name,
        url = "https://example.com/$id.mkv",
        addonName = "Addon",
        addonId = addonId,
    )
}
