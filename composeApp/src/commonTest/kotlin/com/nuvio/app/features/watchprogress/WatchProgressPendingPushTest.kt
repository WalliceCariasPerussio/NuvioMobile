package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchProgressPendingPushTest {

    @Test
    fun `pending row missing on the server is pushed`() {
        val pending = entry(parentMetaId = "tt1", videoId = "tt1")

        val plan = planPendingProgressPush(
            pendingEntries = pending.byKey(),
            serverRecords = emptyList(),
        )

        assertEquals(listOf(pending), plan.toPush.values.toList())
        assertEquals(emptySet(), plan.alreadySyncedKeys)
    }

    @Test
    fun `pending row fresher than the server copy is pushed`() {
        val pending = entry(parentMetaId = "tt1", videoId = "tt1", lastUpdatedEpochMs = 200L, lastPositionMs = 900L)

        val plan = planPendingProgressPush(
            pendingEntries = pending.byKey(),
            serverRecords = listOf(record(contentId = "tt1", videoId = "tt1", lastWatched = 100L, position = 300L)),
        )

        assertEquals(listOf(pending), plan.toPush.values.toList())
        assertEquals(emptySet(), plan.alreadySyncedKeys)
    }

    @Test
    fun `server copy saved later by another device is never overwritten`() {
        val pending = entry(parentMetaId = "tt1", videoId = "tt1", lastUpdatedEpochMs = 100L, lastPositionMs = 900L)

        val plan = planPendingProgressPush(
            pendingEntries = pending.byKey(),
            serverRecords = listOf(record(contentId = "tt1", videoId = "tt1", lastWatched = 200L, position = 300L)),
        )

        assertEquals(emptyMap(), plan.toPush)
        assertEquals(setOf("tt1"), plan.alreadySyncedKeys)
    }

    @Test
    fun `row the server already holds only needs its acknowledgement`() {
        val pending = entry(parentMetaId = "tt1", videoId = "tt1", lastUpdatedEpochMs = 100L, lastPositionMs = 300L)

        val plan = planPendingProgressPush(
            pendingEntries = pending.byKey(),
            serverRecords = listOf(record(contentId = "tt1", videoId = "tt1", lastWatched = 100L, position = 300L)),
        )

        assertEquals(emptyMap(), plan.toPush)
        assertEquals(setOf("tt1"), plan.alreadySyncedKeys)
    }

    @Test
    fun `legacy local key is pushed under the server key of the same episode`() {
        val pending = entry(
            parentMetaId = "tt2",
            videoId = "tt2:1:3",
            seasonNumber = 1,
            episodeNumber = 3,
            lastUpdatedEpochMs = 200L,
        )

        val plan = planPendingProgressPush(
            pendingEntries = pending.byKey(),
            serverRecords = listOf(
                record(
                    contentId = "tt2",
                    videoId = "tt2:1:3",
                    season = 1,
                    episode = 3,
                    lastWatched = 100L,
                    progressKey = "server-key",
                ),
            ),
        )

        assertEquals(
            mapOf("tt2_s1e3" to "server-key"),
            plan.toPush.mapValues { (_, entry) -> entry.resolvedProgressKey() },
        )
        assertEquals(emptySet(), plan.alreadySyncedKeys)
    }

    @Test
    fun `acknowledged keys keep the local key so the pending mark can be cleared`() {
        val pending = entry(
            parentMetaId = "tt2",
            videoId = "tt2:1:3",
            seasonNumber = 1,
            episodeNumber = 3,
            lastUpdatedEpochMs = 100L,
        )

        val plan = planPendingProgressPush(
            pendingEntries = pending.byKey(),
            serverRecords = listOf(
                record(
                    contentId = "tt2",
                    videoId = "tt2:1:3",
                    season = 1,
                    episode = 3,
                    lastWatched = 300L,
                    progressKey = "server-key",
                ),
            ),
        )

        assertEquals(emptyMap(), plan.toPush)
        assertEquals(setOf("tt2_s1e3"), plan.alreadySyncedKeys)
    }

    private fun WatchProgressEntry.byKey(): Map<String, WatchProgressEntry> =
        mapOf(resolvedProgressKey() to this)

    private fun entry(
        parentMetaId: String,
        videoId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        lastUpdatedEpochMs: Long = 100L,
        lastPositionMs: Long = 300L,
    ): WatchProgressEntry =
        WatchProgressEntry(
            contentType = if (seasonNumber == null) "movie" else "series",
            parentMetaId = parentMetaId,
            parentMetaType = if (seasonNumber == null) "movie" else "series",
            videoId = videoId,
            title = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            lastPositionMs = lastPositionMs,
            durationMs = 1_000L,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
        )

    private fun record(
        contentId: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long,
        position: Long = 300L,
        progressKey: String = "",
    ): ProgressSyncRecord =
        ProgressSyncRecord(
            contentId = contentId,
            contentType = if (season == null) "movie" else "series",
            videoId = videoId,
            season = season,
            episode = episode,
            position = position,
            duration = 1_000L,
            lastWatched = lastWatched,
            progressKey = progressKey,
        )
}
