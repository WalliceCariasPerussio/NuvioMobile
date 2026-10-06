package com.nuvio.app.features.player

import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.streams.SourceRanking
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLanguageUnknown
import com.nuvio.app.features.streams.StreamQualityBucket
import com.nuvio.app.features.streams.StreamTraits
import com.nuvio.app.features.streams.bucket
import com.nuvio.app.features.streams.isAutoPlayableWith
import com.nuvio.app.features.streams.isWithin
import com.nuvio.app.features.streams.languageCounts
import com.nuvio.app.features.streams.languageKeysByCount
import com.nuvio.app.features.streams.matchingLanguageKey
import com.nuvio.app.features.streams.preferredAudioTargets
import com.nuvio.app.features.streams.rankSourceStreams
import com.nuvio.app.features.streams.streamLanguageCode
import com.nuvio.app.features.streams.streamTraits

// Fork: which source and audio language are playing, in the source list's terms. Used by the
// "best in my language" autoplay, the source failover and the audio/quality panels
// (PlayerSourceTracksPanels.kt).

internal fun sameLanguage(a: String, b: String): Boolean =
    a == b || a.substringBefore('-') == b.substringBefore('-')

internal fun PlayerScreenRuntime.isCurrentPlayerControlStream(stream: StreamItem): Boolean {
    val activeKey = activeSourceIdentityKey
    val streamKey = stream.playerSourceIdentityKey()
    if (activeKey != null) {
        return streamKey == activeKey
    }
    val directUrl = stream.playableDirectUrl
    if (directUrl != null && directUrl == activeSourceUrl) return true
    val infoHash = stream.p2pInfoHash
    if (infoHash != null && infoHash == activeTorrentInfoHash) return true
    return false
}

internal fun PlayerScreenRuntime.currentSourceStream(streams: List<StreamItem>): StreamItem? =
    streams.firstOrNull { isCurrentPlayerControlStream(it) }

/**
 * Language of [track] in the source list's terms: as the stream playing lists it, else as the track
 * tells it (its label may tell PT-BR or Latin Spanish when its code doesn't).
 */
private fun audioTrackLanguage(track: AudioTrack, streamLanguages: List<String>): String? {
    val base = streamLanguageCode(track.language) ?: return null
    val code = streamLanguageCode(SubtitleLanguageMatching.detectTrackLanguageVariant(track.language, track.label, track.id))
        ?.takeIf { sameLanguage(it, base) }
        ?: base
    return streamLanguages.firstOrNull { it == code }
        ?: streamLanguages.firstOrNull { sameLanguage(it, code) }
        ?: code
}

/** Language of the audio track playing, in the source list's terms (e.g. "pt-br", "ja"). */
internal fun PlayerScreenRuntime.currentAudioLanguage(current: StreamItem?): String? {
    val streamLanguages = current?.streamTraits()?.audioLanguages.orEmpty()
    val trackLanguage = audioTracks.firstOrNull { it.index == selectedAudioIndex }
        ?.let { audioTrackLanguage(it, streamLanguages) }
        ?: return streamLanguages.firstOrNull()
    if (trackLanguage in streamLanguages || '-' in trackLanguage) return trackLanguage
    // Only the base code ("pt", as ExoPlayer reports a PT-BR dub) and no stream to tell which: the
    // preferred variant of it, so another country's release doesn't rank first.
    return preferredAudioTargets(playerSettingsUiState, contentLanguage).firstOrNull { sameLanguage(it, trackLanguage) }
        ?: trackLanguage
}

/** Audio language the failover keeps: the track playing, in the source list's terms. */
internal fun PlayerScreenRuntime.currentAudioLanguageForFailover(): String? =
    currentAudioLanguage(currentSourceStream(PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams }))

/** Languages for the next episode's autoplay: the one playing now, then the preferred ones. */
internal fun PlayerScreenRuntime.nextEpisodeAudioLanguages(): List<String> =
    (listOfNotNull(currentAudioLanguageForFailover()) + preferredAudioTargets(playerSettingsUiState, contentLanguage))
        .distinct()

// --- Audio "Todos" and quality panels ---------------------------------------------------------

internal data class SourceLanguageOption(
    val key: String,
    val streamCount: Int,
    val bestQuality: StreamQualityBucket?,
    val isCurrent: Boolean,
)

internal data class SourceQualityOption(
    val bucket: StreamQualityBucket,
    val streamCount: Int,
    val isCurrent: Boolean,
    val hasCurrentAudio: Boolean,
    /** Audio languages found in this quality, preferred first (for the "switch audio to" prompt). */
    val languages: List<String>,
    /** Some source of this quality has no audio language marked ("Não identificado" in the prompt). */
    val hasUnknownAudio: Boolean = false,
)

internal class SourceTrackOptions(
    val currentLanguage: String?,
    val languages: List<SourceLanguageOption>,
    val qualitiesWithCurrentAudio: List<SourceQualityOption>,
    val allQualities: List<SourceQualityOption>,
)

internal fun PlayerScreenRuntime.buildSourceTrackOptions(
    streams: List<StreamItem>,
    preferredTargets: List<String>,
): SourceTrackOptions {
    // Only what a pick here can start (the autoplay's rule): a language or quality without it isn't offered.
    val debrid = DebridSettingsRepository.snapshot()
    val traits = streams.filter { it.isAutoPlayableWith(debrid) }.map { it.streamTraits() }
    val current = currentSourceStream(streams)
    val currentLanguage = currentAudioLanguage(current)
    val currentQuality = current?.streamTraits()?.quality

    val counts = languageCounts(traits) - StreamLanguageUnknown
    val keysByCount = languageKeysByCount(counts)
    val preferredKeys = preferredTargets.mapNotNull { matchingLanguageKey(keysByCount, it) }.distinct()
    val orderedKeys = preferredKeys + keysByCount.filterNot { it in preferredKeys }

    val languages = orderedKeys.map { key ->
        SourceLanguageOption(
            key = key,
            streamCount = counts[key] ?: 0,
            // What picking the language would play: the best within the cap, else the closest above.
            bestQuality = traits.filter { key in it.audioLanguages }.map { it.quality }.let { qualities ->
                val cap = playerSettingsUiState.streamAutoPlayMaxQuality.bucket
                qualities.filter { it.isWithin(cap) }.minOrNull() ?: qualities.maxOrNull()
            },
            // Exact: PT-BR playing doesn't mark Portuguese (Portugal) too.
            isCurrent = key == currentLanguage,
        )
    }

    fun hasLanguage(traits: StreamTraits, language: String) =
        traits.audioLanguages.any { sameLanguage(it, language) }

    fun qualities(language: String?): List<SourceQualityOption> = StreamQualityBucket.entries.mapNotNull { bucket ->
        val inBucket = traits.filter { it.quality == bucket }
        val matching = if (language == null) inBucket else inBucket.filter { hasLanguage(it, language) }
        if (matching.isEmpty()) return@mapNotNull null
        SourceQualityOption(
            bucket = bucket,
            streamCount = matching.size,
            isCurrent = bucket == currentQuality,
            hasCurrentAudio = currentLanguage == null || inBucket.any { hasLanguage(it, currentLanguage) },
            languages = orderedKeys.filter { key -> inBucket.any { key in it.audioLanguages } },
            hasUnknownAudio = inBucket.any { it.audioLanguages.isEmpty() },
        )
    }

    return SourceTrackOptions(
        currentLanguage = currentLanguage,
        languages = languages,
        qualitiesWithCurrentAudio = currentLanguage?.let { qualities(it) }.orEmpty(),
        allQualities = qualities(null),
    )
}

/**
 * Best source for a language and/or quality, by the shared ranking (StreamSourceRanking.kt): a
 * language pick stays within the quality cap when it can; a quality picked by hand has no cap. Ties
 * prefer the release group and the addon playing now. Only sources autoplay would start (no torrent
 * where P2P can't play it).
 */
private fun PlayerScreenRuntime.bestSourceStream(
    streams: List<StreamItem>,
    language: String?,
    quality: StreamQualityBucket?,
): StreamItem? {
    val debrid = DebridSettingsRepository.snapshot()
    val scoped = streams.filter {
        it.isAutoPlayableWith(debrid) && (quality == null || it.streamTraits().quality == quality)
    }
    return rankSourceStreams(
        scoped,
        SourceRanking(
            language = language,
            maxQuality = if (quality == null) playerSettingsUiState.streamAutoPlayMaxQuality.bucket else null,
            strictCap = false,
            preferredBingeGroup = currentStreamBingeGroup,
            preferredAddonId = activeProviderAddonId,
        ),
    ).firstOrNull()
}

/** The panels list every source of what is playing: search them (usually kept from the start). */
internal fun PlayerScreenRuntime.loadSourcesForTrackPanels() {
    val videoId = activeVideoId ?: return
    sourceTracksPanelsSearching++
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
}

/** "Todos" scope: plays the language; on the stream playing if it carries it, else on its best source. */
internal fun PlayerScreenRuntime.selectSourceAudioLanguage(language: String) {
    val current = currentSourceStream(sourceStreamsState.groups.flatMap { it.streams })
    // The row marked as playing: nothing to switch.
    if (language == currentAudioLanguage(current)) {
        closeSourceTrackPanels()
        return
    }
    val streamLanguages = current?.streamTraits()?.audioLanguages.orEmpty()
    // Exact: a Castilian track ("spa") doesn't play Latin Spanish, nor "pt" PT-BR.
    val embedded = audioTracks.firstOrNull { track -> audioTrackLanguage(track, streamLanguages) == language }
    if (embedded != null) {
        // Same as picking the track in "Stream atual".
        selectedAudioIndex = embedded.index
        persistAudioPreference(embedded)
        playerController?.selectAudioTrack(embedded.index)
        closeSourceTrackPanels()
        return
    }
    val stream = bestSourceStream(sourceStreamsState.groups.flatMap { it.streams }, language, quality = null) ?: return
    switchSourceForTrackPanel(stream, language)
}

internal fun PlayerScreenRuntime.selectSourceQuality(quality: StreamQualityBucket, language: String?) {
    val streams = sourceStreamsState.groups.flatMap { it.streams }
    // The panel only offers a quality without the current audio through its "switch audio to" prompt,
    // where "Não identificado" means the sources without an audio language marked.
    val unmarked = language == StreamLanguageUnknown
    val scoped = if (unmarked) streams.filter { it.streamTraits().audioLanguages.isEmpty() } else streams
    val stream = bestSourceStream(scoped, language.takeUnless { unmarked }, quality) ?: return
    if (isCurrentPlayerControlStream(stream)) {
        closeSourceTrackPanels()
        return
    }
    switchSourceForTrackPanel(stream, language.takeUnless { unmarked })
}

private fun PlayerScreenRuntime.switchSourceForTrackPanel(stream: StreamItem, language: String?) {
    // A choice by hand, as in the Fontes panel: the failover doesn't replace it.
    markManualSourcePick(stream)
    // The new stream restores this title's saved audio choice, so it starts on the language picked here.
    if (language != null) {
        updateTrackPreference { it.copy(audioLanguage = language, audioName = null, audioTrackId = null) }
    }
    closeSourceTrackPanels()
    // A P2P source asks for consent first (PlayerScreenModalHosts), as when it is picked in Fontes.
    switchToSource(stream)
}

private fun PlayerScreenRuntime.closeSourceTrackPanels() {
    showAudioModal = false
    showQualityModal = false
}

/**
 * A panel left the screen: stop the search it started, as closing Fontes does, unless Fontes or the
 * source failover is using it now.
 */
internal fun PlayerScreenRuntime.stopSourceTracksSearch() {
    if (sourceTracksPanelsSearching == 0) return
    sourceTracksPanelsSearching--
    if (sourceTracksPanelsSearching > 0 || showSourcesPanel || sourceFailover.inProgress) return
    PlayerStreamsRepository.stopSourcesLoading()
}
