package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.streams.StreamSearchCache

/**
 * While the player is open, keeps in [StreamSearchCache] the searches of what is playing and of
 * the next episode, past their normal expiry: the "Fontes" panel and moving to the next episode
 * reuse them instead of searching again. Closing the player releases them.
 */
@Composable
internal fun PlayerScreenRuntime.RetainPlayerStreamSearches() {
    val type = contentType ?: parentMetaType
    val videoId = activeVideoId
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val next = nextEpisodeInfo?.takeIf { it.hasAired }
    LaunchedEffect(type, videoId, season, episode, next?.videoId, next?.season, next?.episode) {
        StreamSearchCache.retain(
            setOfNotNull(
                videoId?.let { StreamSearchCache.media(type, it, season, episode) },
                next?.let { StreamSearchCache.media(type, it.videoId, it.season, it.episode) },
            ),
        )
    }
    DisposableEffect(Unit) {
        onDispose { StreamSearchCache.retain(emptySet()) }
    }
}

/** A failed stream may come from a stale search, so the sources panel searches again. */
internal val PlayerScreenRuntime.isPlaybackFailed: Boolean
    get() = errorMessage != null || sourceFailover.troublePlayback == activePlaybackKey // fork: PlayerSourceFailover.kt
