package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.SourceRanking
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.bucket
import com.nuvio.app.features.streams.rankSourceStreams
import com.nuvio.app.features.streams.sourceKey
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Fork: Netflix-like failover. In the "best in my language" autoplay mode, when the source playing
// fails or turns out to be an addon's error clip, play the next best source in the same language
// (else the preferred ones) instead of the error. Up to MaxSourceFailovers per episode, never a
// source that already failed, and never over a source the user picked by hand in the panels.

private const val MaxSourceFailovers = 3
private const val SourceListWaitMs = 15_000L
private const val SwitchWatchMs = 20_000L
private const val ShortClipCheckAfterMs = 2_000L
/** ~48 Mbps: a real clip this short can't be bigger than that. */
private const val MaxRealClipBytesPerSecond = 6_000_000L
private val failoverLog = Logger.withTag("SourceFailover")

internal class SourceFailoverState {
    var episodeKey: String? = null
    val failedKeys = mutableSetOf<String>()
    var attempts = 0
    /** Source picked by hand in the panels: its failure shows the error. */
    var manualPick: StreamItem? = null
    var inProgress = false
    /** Resume point to keep when the failed source never played (its position is 0). */
    var resumeOverride: Pair<Long, Float?>? = null
    /** Source whose short clip was already checked: snapshots check each source once. */
    var shortClipCheckedKey: String? = null

    fun takeResume(): Pair<Long, Float?>? = resumeOverride.also { resumeOverride = null }
}

/** Marks a source the user picked by hand (Fontes/Episodes panels), so its failure is not hidden. */
internal fun PlayerScreenRuntime.markManualSourcePick(stream: StreamItem) {
    sourceFailover.manualPick = stream
}

/**
 * Called before a playback error is shown ([failureMessage]), or with null for an addon's error
 * clip (see [onShortClipPlaying]); true when another source takes over.
 */
internal fun PlayerScreenRuntime.tryAutoSourceFailover(failureMessage: String?): Boolean {
    if (playerSettingsUiState.streamAutoPlayMode != StreamAutoPlayMode.BEST_PREFERRED_AUDIO) return false
    val videoId = activeVideoId ?: return false
    val failover = sourceFailover
    if (failover.inProgress) return true

    val episodeKey = "$videoId:$activeSeasonNumber:$activeEpisodeNumber"
    if (failover.episodeKey != episodeKey) {
        failover.episodeKey = episodeKey
        failover.failedKeys.clear()
        failover.attempts = 0
    }
    if (failover.manualPick?.let { isCurrentPlayerControlStream(it) } == true) return false
    if (failover.attempts >= MaxSourceFailovers) return false
    failover.attempts++
    failover.inProgress = true
    failover.failedKeys += activeTorrentInfoHash?.lowercase() ?: activeSourceUrl
    // The failed source never really played (it failed to open, or it's an addon's error clip):
    // keep the resume point instead of its position.
    if (playbackSnapshot.positionMs <= 0L || isShortPlaceholderDuration(playbackSnapshot.durationMs)) {
        failover.resumeOverride = activeInitialPositionMs to activeInitialProgressFraction
    }

    // The language playing, else the preferred ones in order (as autoplay picks).
    val languages = nextEpisodeAudioLanguages()
    val type = contentType ?: parentMetaType
    scope.launch {
        val pick = try {
            val sources = PlayerStreamsRepository.sourceState
            fun bestLeft(): Pair<StreamItem, String?>? {
                val streams = sources.value.groups.flatMap { it.streams }
                // The source playing, as the list knows it (its hash or URL may differ from the player's).
                streams.filter { isCurrentPlayerControlStream(it) }.mapNotNull { it.sourceKey() }
                    .forEach(failover.failedKeys::add)
                val candidates = streams.filter { it.isFailoverPlayable() && !isCurrentPlayerControlStream(it) }
                return languages.ifEmpty { listOf(null) }.firstNotNullOfOrNull { language ->
                    rankSourceStreams(
                        candidates,
                        SourceRanking(
                            language = language,
                            maxQuality = playerSettingsUiState.streamAutoPlayMaxQuality.bucket,
                            strictCap = false,
                            preferredBingeGroup = currentStreamBingeGroup,
                            excludedKeys = failover.failedKeys,
                        ),
                    ).firstOrNull()?.let { it to language }
                }
            }
            if (sources.value.groups.isEmpty()) {
                // The episode's search is kept by the player, so this is usually instant.
                PlayerStreamsRepository.loadSources(
                    type = type,
                    videoId = videoId,
                    season = activeSeasonNumber,
                    episode = activeEpisodeNumber,
                )
            }
            // Use what already arrived; wait for slow addons only when nothing fits yet.
            bestLeft() ?: run {
                withTimeoutOrNull(SourceListWaitMs) {
                    sources.first { it.groups.isNotEmpty() && !it.isAnyLoading }
                }
                bestLeft()
            }
        } finally {
            failover.inProgress = false
        }
        if (pick == null) {
            failoverLog.w { "no other source in $languages" }
            failover.resumeOverride = null
            onFailoverExhausted(failureMessage)
            return@launch
        }
        val (next, language) = pick
        failoverLog.w { "attempt ${failover.attempts}: -> ${next.name ?: next.addonName} ($language)" }
        // Counted as failed up front: if it can't even be resolved, the next attempt skips it.
        next.sourceKey()?.let(failover.failedKeys::add)
        // The dead link must not come back through "reuse last link" on the next Continuar.
        removeFailedStreamFromCache()
        if (language != null) {
            updateTrackPreference { it.copy(audioLanguage = language, audioName = null, audioTrackId = null) }
        }
        val beforeUrl = activeSourceUrl
        val beforeHash = activeTorrentInfoHash
        switchToSource(next)
        // A P2P source waits for the user's consent dialog: nothing more to watch here.
        if (pendingP2pSwitch != null) return@launch
        // A debrid candidate that fails to resolve only shows a toast and leaves the dead source:
        // move on to the next one (or show the error) instead of a frozen frame.
        val switched = withTimeoutOrNull(SwitchWatchMs) {
            snapshotFlow { activeSourceUrl != beforeUrl || activeTorrentInfoHash != beforeHash || errorMessage != null }
                .first { it }
        }
        if (switched == null && !tryAutoSourceFailover(failureMessage)) onFailoverExhausted(failureMessage)
    }
    return true
}

/** No other source: the error for a playback failure, the source list for an addon's error clip. */
private fun PlayerScreenRuntime.onFailoverExhausted(failureMessage: String?) {
    if (failureMessage != null) showPlaybackError(failureMessage) else openSourcesPanel()
}

/**
 * Called on every playback snapshot. Debrid addons answer with a 30 s video when they can't serve
 * the file ("only rar is available", "downloading"): in the "best in my language" mode that is a
 * failed source too, so the next one plays.
 */
internal fun PlayerScreenRuntime.onShortClipPlaying(snapshot: PlayerPlaybackSnapshot) {
    // A couple of seconds in, so a duration reported before the real one is known doesn't count.
    if (snapshot.isLoading || snapshot.positionMs < ShortClipCheckAfterMs) return
    if (!isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val key = activeTorrentInfoHash?.lowercase() ?: activeSourceUrl.ifBlank { return }
    if (sourceFailover.shortClipCheckedKey == key) return
    sourceFailover.shortClipCheckedKey = key
    val declaredSize = PlayerStreamsRepository.sourceState.value.groups
        .flatMap { it.streams }
        .firstOrNull { isCurrentPlayerControlStream(it) }
        ?.behaviorHints?.videoSize
    if (!isAddonErrorClip(playbackSnapshot.durationMs, declaredSize)) return
    failoverLog.w { "${playbackSnapshot.durationMs / 1000}s clip looks like an addon error video" }
    tryAutoSourceFailover(failureMessage = null)
}

/** A short clip is the real file only when the source declares a size that fits it. */
private fun isAddonErrorClip(durationMs: Long, declaredSize: Long?): Boolean {
    val size = declaredSize?.takeIf { it > 0L } ?: return true
    return size / (durationMs / 1000).coerceAtLeast(1L) > MaxRealClipBytesPerSecond
}

/** What the player's onError does when no source takes over. */
private fun PlayerScreenRuntime.showPlaybackError(message: String) {
    errorMessage = message
    scrubbingPositionMs = null
    controlsVisible = !playerControlsLocked
    removeFailedStreamFromCache()
}

private fun StreamItem.isFailoverPlayable(): Boolean {
    if (shouldOpenExternally) return false
    return when (debridCacheStatus?.state) {
        StreamDebridCacheState.CHECKING, StreamDebridCacheState.NOT_CACHED -> false
        else -> true
    }
}
