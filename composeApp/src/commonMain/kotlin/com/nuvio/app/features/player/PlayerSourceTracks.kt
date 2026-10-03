package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.preferredAudioTargets
import com.nuvio.app.features.streams.streamLanguageCode
import com.nuvio.app.features.streams.streamTraits

// Fork: which source and audio language are playing, in the source list's terms. Used by the
// "best in my language" autoplay, the source failover and the audio/quality panels.

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

/** Language of the audio track playing, in the source list's terms (e.g. "pt-br", "ja"). */
internal fun PlayerScreenRuntime.currentAudioLanguage(current: StreamItem?): String? {
    val streamLanguages = current?.streamTraits()?.audioLanguages.orEmpty()
    val trackLanguage = audioTracks.firstOrNull { it.index == selectedAudioIndex }
        ?.language
        ?.let(::streamLanguageCode)
        ?: return streamLanguages.firstOrNull()
    return streamLanguages.firstOrNull { it == trackLanguage }
        ?: streamLanguages.firstOrNull { sameLanguage(it, trackLanguage) }
        ?: trackLanguage
}

/** Audio language the failover keeps: the track playing, in the source list's terms. */
internal fun PlayerScreenRuntime.currentAudioLanguageForFailover(): String? =
    currentAudioLanguage(currentSourceStream(PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams }))

/** Languages for the next episode's autoplay: the one playing now, then the preferred ones. */
internal fun PlayerScreenRuntime.nextEpisodeAudioLanguages(): List<String> =
    (listOfNotNull(currentAudioLanguageForFailover()) + preferredAudioTargets(playerSettingsUiState, contentLanguage))
        .distinct()
