package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridSettings

// Fork: one rule for every automatic source choice (autoplay "best in my language", the player's
// audio/quality panels and the failover to the next source), Netflix-like: the language first,
// then the best quality up to the cap, then the source most likely to start well.

/** The cap as a quality bucket; null means no limit. */
internal val StreamAutoPlayMaxQuality.bucket: StreamQualityBucket?
    get() = when (this) {
        StreamAutoPlayMaxQuality.NO_LIMIT -> null
        StreamAutoPlayMaxQuality.UHD_4K -> StreamQualityBucket.UHD_4K
        StreamAutoPlayMaxQuality.FHD_1080 -> StreamQualityBucket.FHD_1080
        StreamAutoPlayMaxQuality.HD_720 -> StreamQualityBucket.HD_720
    }

/** Within the cap (or no cap). "Outras" (unknown quality) counts as within. */
internal fun StreamQualityBucket.isWithin(cap: StreamQualityBucket?): Boolean =
    cap == null || this == StreamQualityBucket.OTHER || ordinal >= cap.ordinal

internal data class SourceRanking(
    /** Audio language the source must have; null accepts any. */
    val language: String?,
    val maxQuality: StreamQualityBucket?,
    /** Drop sources above the cap; otherwise they come after, closest to the cap first. */
    val strictCap: Boolean,
    val preferredBingeGroup: String? = null,
    val preferredAddonId: String? = null,
    /** Sources to leave out (already failed), by [sourceKey]. */
    val excludedKeys: Set<String> = emptySet(),
)

/** Identity of a source across searches: the torrent hash, else its URL. */
internal fun StreamItem.sourceKey(): String? =
    p2pInfoHash?.lowercase() ?: playableDirectUrl ?: externalOpenUrl

/** Likely to start fast: cached on the debrid, or a direct link that isn't a torrent. */
private fun StreamItem.isReadyLink(): Boolean =
    debridCacheStatus?.state == StreamDebridCacheState.CACHED || (playableDirectUrl != null && !isTorrentStream)

/** [streams] ordered from the best match down; sources without the language are left out. */
internal fun rankSourceStreams(streams: List<StreamItem>, ranking: SourceRanking): List<StreamItem> {
    val language = ranking.language
    val cap = ranking.maxQuality
    return streams.withIndex().mapNotNull { (index, stream) ->
        if (stream.sourceKey()?.let { it in ranking.excludedKeys } == true) return@mapNotNull null
        val traits = stream.streamTraits()
        val languageRank = when {
            language == null -> 0
            language in traits.audioLanguages -> 0
            traits.audioLanguages.any { it.substringBefore('-') == language.substringBefore('-') } -> 1
            else -> return@mapNotNull null
        }
        val withinCap = traits.quality.isWithin(cap)
        if (!withinCap && ranking.strictCap) return@mapNotNull null
        val qualityRank = if (withinCap) {
            traits.quality.ordinal
        } else {
            // Above the cap: the closest to it first (1440p before 4K under a 1080p cap).
            StreamQualityBucket.entries.size - traits.quality.ordinal
        }
        val key = listOf(
            languageRank,
            if (withinCap) 0 else 1,
            qualityRank,
            if (ranking.preferredBingeGroup != null && stream.behaviorHints.bingeGroup == ranking.preferredBingeGroup) 0 else 1,
            if (ranking.preferredAddonId != null && stream.addonId == ranking.preferredAddonId) 0 else 1,
            if (stream.isReadyLink()) 0 else 1,
            if (traits.audioLanguages.size > 1) 0 else 1,
        )
        RankedStream(stream, key, stream.behaviorHints.videoSize ?: 0L, index)
    }.sortedWith(
        Comparator<RankedStream> { a, b ->
            a.key.zip(b.key).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
        }.thenByDescending { it.size }.thenBy { it.index }
    ).map { it.stream }
}

private class RankedStream(val stream: StreamItem, val key: List<Int>, val size: Long, val index: Int)

/**
 * Autoplay "best in my language", as the ordered candidates of the first language that has a source
 * within the cap (preferred, then secondary). Empty when none does, so the source list opens; a
 * language only found above the cap is not played automatically (4K stays a manual choice), the next
 * language is tried instead. No language at all (audio "Default", no secondary) accepts any.
 */
internal fun rankBestInPreferredLanguage(
    streams: List<StreamItem>,
    preferredLanguages: List<String>,
    maxQuality: StreamQualityBucket?,
    preferredBingeGroup: String? = null,
    // The source playing, for the next episode: it comes first within the language and the cap.
    preferredAddonId: String? = null,
): List<StreamItem> {
    for (language in preferredLanguages.ifEmpty { listOf(null) }) {
        val withinCap = rankSourceStreams(
            streams,
            SourceRanking(language, maxQuality, strictCap = true, preferredBingeGroup, preferredAddonId),
        )
        if (withinCap.isEmpty()) continue
        // Same release as the episode before, else the same addon: what the user was watching.
        val continuing = preferredBingeGroup?.let { group -> withinCap.firstOrNull { it.behaviorHints.bingeGroup == group } }
            ?: preferredAddonId?.let { id -> withinCap.firstOrNull { it.addonId == id } }
        return if (continuing == null) withinCap else listOf(continuing) + (withinCap - continuing)
    }
    return emptyList()
}

/**
 * The next episode keeps what was [playing]: the same release (binge group), the same quality
 * first; else the source with the same name ("AnimesDigital LEG [Blogger] - 720p") from the same
 * addon, when known; else the same addon in the same quality. Always with the same audio: every
 * language the playing source had, so a group shared by the dub and the original doesn't swap the
 * language. A source playing without a known language (resumed with only its name) doesn't rule any
 * out. Null when the next episode has none of them.
 */
internal fun selectContinuation(streams: List<StreamItem>, playing: StreamItem): StreamItem? {
    val playingTraits = playing.streamTraits()
    val languages = playingTraits.audioLanguages
    val candidates = streams.map { it to it.streamTraits() }
        .filter { (_, traits) -> traits.audioLanguages.containsAll(languages) }
    fun List<Pair<StreamItem, StreamTraits>>.sameQualityFirst(): StreamItem? =
        (firstOrNull { (_, traits) -> traits.quality == playingTraits.quality } ?: firstOrNull())?.first

    playing.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotEmpty() }?.let { group ->
        candidates.filter { (stream, _) -> stream.behaviorHints.bingeGroup == group }
            .sameQualityFirst()?.let { return it }
    }
    val addonId = playing.addonId.takeIf { it.isNotBlank() }
    playing.name?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
        candidates.filter { (stream, _) -> stream.name?.trim() == name && (addonId == null || stream.addonId == addonId) }
            .sameQualityFirst()?.let { return it }
    }
    if (addonId == null) return null
    return candidates.firstOrNull { (stream, traits) ->
        stream.addonId == addonId && traits.quality == playingTraits.quality
    }?.first
}

/** The autoplay's rule for a source that can start on its own ([StreamAutoPlaySelector]). */
internal fun StreamItem.isAutoPlayableWith(debrid: DebridSettings): Boolean {
    val stream = this
    return with(StreamAutoPlaySelector) {
        stream.isAutoPlayable(debrid.canResolvePlayableLinks, debrid.activeResolverProviderId)
    }
}

/**
 * True when [stream] can't be beaten by sources still loading: preferred language, at the cap
 * quality (no cap: never) and a ready link. Lets autoplay start before every addon answers.
 */
internal fun isUnbeatableChoice(stream: StreamItem, preferredLanguage: String?, maxQuality: StreamQualityBucket?): Boolean {
    if (maxQuality == null || preferredLanguage == null) return false
    val traits = stream.streamTraits()
    return preferredLanguage in traits.audioLanguages && traits.quality == maxQuality && stream.isReadyLink()
}
