package com.nuvio.app.features.player

/** A failed stream may come from a stale search, so the sources panel searches again. */
internal val PlayerScreenRuntime.isPlaybackFailed: Boolean
    get() = errorMessage != null || sourceFailover.troublePlayback == activePlaybackKey // fork: PlayerSourceFailover.kt
