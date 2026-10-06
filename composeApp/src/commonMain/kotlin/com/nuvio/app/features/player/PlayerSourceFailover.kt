package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.streams.SourceRanking
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.bucket
import com.nuvio.app.features.streams.isAutoPlayableWith
import com.nuvio.app.features.streams.rankSourceStreams
import com.nuvio.app.features.streams.sourceKey
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Fork: Netflix-like failover. In the "best in my language" autoplay mode, when the source playing
// fails or turns out to be an addon's error clip, play the next best source in the same language
// (else the preferred ones) instead of the error. Up to MaxSourceFailovers per episode, never a
// source that already failed, and never over a source the user picked by hand.

private const val MaxSourceFailovers = 3
private const val SourceListWaitMs = 15_000L
private const val SwitchWatchMs = 20_000L
private const val ShortClipCheckAfterMs = 2_000L
/** ~48 Mbps: a real clip this short can't be bigger than that. */
private const val MaxRealClipBytesPerSecond = 6_000_000L
private val failoverLog = Logger.withTag("SourceFailover")

internal class SourceFailoverState(
    /** Identity the player gave the source it opened with (its first activeSourceIdentityKey). */
    launchSourceKey: String?,
    launch: PlayerLaunch?,
) {
    var episodeKey: String? = null
    val failedKeys = mutableSetOf<String>()
    var attempts = 0
    /**
     * Source picked by hand, by [playerSourceIdentityKey]: in the panels, or in the streams screen's
     * list for the one the player opened with. Its failure shows the error.
     */
    var manualPickKey: String? = launchSourceKey.takeIf { launch?.manualSourcePick == true }
    /** Playback whose failure is being handled while the next source is looked up. */
    var failingPlayback: PlaybackKey? = null
    var job: Job? = null
    val inProgress: Boolean get() = failingPlayback != null
    /** Resume point to keep when the failed source never played (its position is 0). */
    var resumeOverride: Pair<Long, Float?>? = null
    /** Audio language a source was picked for, by source identity: selects its track, never saved. */
    var audioLanguage: Pair<String?, String>? = null
    /** Size the playing source declares, by source identity (the launch's, then each switch's). */
    var declaredSize: Pair<String?, Long?>? = launchSourceKey to launch?.sourceVideoSize
    /** Source whose short clip was already checked: snapshots check each source once. */
    var shortClipCheckedKey: String? = null

    fun takeResume(): Pair<Long, Float?>? = resumeOverride.also { resumeOverride = null }

    /** Drops a lookup that the user (or another failure) already moved past. */
    fun cancelPending() {
        job?.cancel()
        job = null
        failingPlayback = null
    }
}

/** Marks a source the user picked by hand (panels), so its failure is not hidden. */
internal fun PlayerScreenRuntime.markManualSourcePick(stream: StreamItem) {
    sourceFailover.manualPickKey = stream.playerSourceIdentityKey()
    // The user's choice wins over a failover still looking for a source.
    sourceFailover.cancelPending()
}

/** The autoplay picks the next episode's source: the hand-picked one was for this playback only. */
internal fun PlayerScreenRuntime.clearManualSourcePick() {
    sourceFailover.manualPickKey = null
}

internal fun PlayerScreenRuntime.isManualSourcePlaying(): Boolean =
    sourceFailover.manualPickKey?.let { it == activeSourceIdentityKey } == true

/** Another episode starts: nothing of the previous one's failover carries over. */
internal fun PlayerScreenRuntime.resetSourceFailoverForEpisode() {
    sourceFailover.cancelPending()
    sourceFailover.resumeOverride = null
    sourceFailover.audioLanguage = null
}

/** A source starts: its declared size, for [onShortClipPlaying]. */
internal fun PlayerScreenRuntime.rememberSourceDeclaredSize(stream: StreamItem) {
    sourceFailover.declaredSize = stream.playerSourceIdentityKey() to stream.behaviorHints.videoSize
}

/** Audio language the failover picked the playing source for: its track comes first. */
internal val PlayerScreenRuntime.failoverAudioLanguage: String?
    get() = sourceFailover.audioLanguage
        ?.takeIf { (key, _) -> key != null && key == activeSourceIdentityKey }
        ?.second

/**
 * Called before a playback error is shown ([failureMessage]), or with null for an addon's error
 * clip (see [onShortClipPlaying]); true when another source takes over.
 */
internal fun PlayerScreenRuntime.tryAutoSourceFailover(failureMessage: String?): Boolean {
    if (playerSettingsUiState.streamAutoPlayMode != StreamAutoPlayMode.BEST_PREFERRED_AUDIO) return false
    val videoId = activeVideoId ?: return false
    val failover = sourceFailover
    val playback = activePlaybackKey
    // The same source failing again while the next one is looked up.
    if (failover.failingPlayback == playback) return true
    // A lookup still running belongs to a source the player already left.
    failover.cancelPending()

    val episodeKey = "$videoId:$activeSeasonNumber:$activeEpisodeNumber"
    if (failover.episodeKey != episodeKey) {
        failover.episodeKey = episodeKey
        failover.failedKeys.clear()
        failover.attempts = 0
    }
    if (isManualSourcePlaying()) return false
    if (failover.attempts >= MaxSourceFailovers) return false
    failover.attempts++
    failover.failedKeys += activeTorrentInfoHash?.lowercase() ?: activeSourceUrl
    // The failed source never really played (it failed to open, or it's an addon's error clip):
    // keep the resume point instead of its position.
    if (playbackSnapshot.positionMs <= 0L || isShortPlaceholderDuration(playbackSnapshot.durationMs)) {
        failover.resumeOverride = activeInitialPositionMs to activeInitialProgressFraction
    }

    // Always the episode playing: the list may still hold another episode's sources. Its search is
    // kept by the player (StreamSearchCache), so this is usually instant.
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    // The language playing, else the preferred ones in order (as autoplay picks).
    val languages = nextEpisodeAudioLanguages()
    val manualPickKey = failover.manualPickKey
    failover.failingPlayback = playback
    failover.job = scope.launch {
        val pick = try {
            val sources = PlayerStreamsRepository.sourceState
            val debrid = DebridSettingsRepository.snapshot()
            fun bestLeft(): Pair<StreamItem, String?>? {
                val streams = sources.value.groups.flatMap { it.streams }
                // The source playing, as the list knows it (its hash or URL may differ from the player's).
                streams.filter { isCurrentPlayerControlStream(it) }.mapNotNull { it.sourceKey() }
                    .forEach(failover.failedKeys::add)
                val candidates = streams.filter { isFailoverPlayable(it, debrid) && !isCurrentPlayerControlStream(it) }
                return languages.ifEmpty { listOf(null) }.firstNotNullOfOrNull { language ->
                    rankSourceStreams(
                        candidates,
                        SourceRanking(
                            language = language,
                            maxQuality = playerSettingsUiState.streamAutoPlayMaxQuality.bucket,
                            // Above the cap is a manual choice, as in autoplay.
                            strictCap = true,
                            preferredBingeGroup = currentStreamBingeGroup,
                            excludedKeys = failover.failedKeys,
                        ),
                    ).firstOrNull()?.let { it to language }
                }
            }
            // Use what already arrived; wait for slow addons only when nothing fits yet.
            bestLeft() ?: run {
                withTimeoutOrNull(SourceListWaitMs) {
                    sources.first { it.groups.isNotEmpty() && !it.isAnyLoading }
                }
                bestLeft()
            }
        } finally {
            if (failover.failingPlayback == playback) failover.failingPlayback = null
        }
        // The user moved on while the sources loaded (another source or episode, or a pick by hand).
        if (activePlaybackKey != playback || failover.manualPickKey != manualPickKey) return@launch
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
        // The new source starts on that language without making it the title's saved choice.
        failover.audioLanguage = language?.let { next.playerSourceIdentityKey() to it }
        val beforeUrl = activeSourceUrl
        val beforeHash = activeTorrentInfoHash
        switchToSource(next)
        // A debrid candidate that fails to resolve only shows a toast and leaves the dead source:
        // move on to the next one (or show the error) instead of a frozen frame.
        val switched = withTimeoutOrNull(SwitchWatchMs) {
            snapshotFlow { activeSourceUrl != beforeUrl || activeTorrentInfoHash != beforeHash || errorMessage != null }
                .first { it }
        }
        // Left unused when the switch didn't happen: a later switch must not jump back to it.
        failover.resumeOverride = null
        if (switched == null) {
            failover.job = null
            if (!tryAutoSourceFailover(failureMessage)) onFailoverExhausted(failureMessage)
        }
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
    if (!isAddonErrorClip(playbackSnapshot.durationMs, currentDeclaredSize())) return
    failoverLog.w { "${playbackSnapshot.durationMs / 1000}s clip looks like an addon error video" }
    // No other source (tries used up, or a source picked by hand): the source list over the clip.
    if (
        playerSettingsUiState.streamAutoPlayMode == StreamAutoPlayMode.BEST_PREFERRED_AUDIO &&
        !tryAutoSourceFailover(failureMessage = null)
    ) {
        openSourcesPanel()
    }
}

/**
 * An addon's error clip carries the real file's declared size, far too big for a clip this short.
 * Without a size it is not called one: short episodes (anime shorts) are real videos too.
 */
private fun isAddonErrorClip(durationMs: Long, declaredSize: Long?): Boolean {
    val size = declaredSize?.takeIf { it > 0L } ?: return false
    return size / (durationMs / 1000).coerceAtLeast(1L) > MaxRealClipBytesPerSecond
}

/** Size the playing source declares: kept when it started, else found in the player's lists. */
internal fun PlayerScreenRuntime.currentDeclaredSize(): Long? {
    sourceFailover.declaredSize
        ?.takeIf { (key, _) -> key != null && key == activeSourceIdentityKey }
        ?.second
        ?.let { return it }
    return (PlayerStreamsRepository.sourceState.value.groups + PlayerStreamsRepository.episodeStreamsState.value.groups)
        .flatMap { it.streams }
        .firstOrNull { isCurrentPlayerControlStream(it) }
        ?.behaviorHints?.videoSize
}

/** What the player's onError does when no source takes over. */
private fun PlayerScreenRuntime.showPlaybackError(message: String) {
    errorMessage = message
    scrubbingPositionMs = null
    controlsVisible = !playerControlsLocked
    removeFailedStreamFromCache()
}

/**
 * A source the failover may switch to on its own: one autoplay would start, not a debrid link
 * still checking or not cached, and a torrent the player streams itself only with P2P on (never
 * a consent prompt, or a silent no-op, in the middle of playback).
 */
private fun PlayerScreenRuntime.isFailoverPlayable(stream: StreamItem, debrid: DebridSettings): Boolean {
    if (stream.shouldOpenExternally || !stream.isAutoPlayableWith(debrid)) return false
    when (stream.debridCacheStatus?.state) {
        StreamDebridCacheState.CHECKING, StreamDebridCacheState.NOT_CACHED -> return false
        else -> Unit
    }
    val viaP2p = isP2pStream(stream) && !DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)
    return !viaP2p || (P2pSettingsRepository.isVisible && P2pSettingsRepository.uiState.value.p2pEnabled)
}
