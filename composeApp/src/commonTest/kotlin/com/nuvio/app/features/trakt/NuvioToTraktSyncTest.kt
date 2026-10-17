package com.nuvio.app.features.trakt

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NuvioToTraktSyncTest {

    @Test
    fun `episode watched only in the Nuvio cloud goes to Trakt history with its original date`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = listOf(watched("tt0898266", season = 1, episode = 7, at = 500L)),
            nuvioProgress = emptyList(),
            traktWatchedKeys = setOf(watchedItemKey("series", "tt0898266", 1, 6)),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
        )

        assertEquals(listOf(Triple("tt0898266", 1, 7)), plan.history.map { Triple(it.id, it.season, it.episode) })
        assertEquals(500L, plan.history.single().markedAtEpochMs)
        assertTrue(plan.inProgress.isEmpty())
    }

    @Test
    fun `items Trakt already has under any id form are not sent again`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = listOf(
                watched("tt0898266", season = 1, episode = 6, at = 500L),
                watched("tmdb:1418", season = 1, episode = 5, at = 400L),
                watched("tt0111161", type = "movie", at = 300L),
            ),
            nuvioProgress = emptyList(),
            traktWatchedKeys = setOf(
                watchedItemKey("series", "tt0898266", 1, 6),
                watchedItemKey("series", "tmdb:1418", 1, 5),
                watchedItemKey("movie", "tt0111161"),
            ),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `ids differing only in case or spaces still match Trakt`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = listOf(
                watched(" TMDB:1418 ", season = 1, episode = 5, at = 400L),
                watched("Trakt:123", type = "movie", at = 300L),
            ),
            nuvioProgress = emptyList(),
            traktWatchedKeys = setOf(
                watchedItemKey("series", "tmdb:1418", 1, 5),
                watchedItemKey("movie", "trakt:123"),
            ),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `history sent moments ago is not offered again while Trakt cannot recognise it`() {
        val item = watched("tt13293588", season = 2, episode = 24, at = 500L)

        val plan = planNuvioToTraktSync(
            nuvioWatched = listOf(item),
            nuvioProgress = emptyList(),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
            recentlySentHistory = setOf(historyIdentity(item)),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `anime ids Trakt cannot confirm are counted and left out`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = listOf(watched("kitsu:5045", season = 1, episode = 3, at = 500L)),
            nuvioProgress = listOf(progress("mal:21", season = 1, episode = 4, position = 600_000L, at = 600L)),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
        )

        assertTrue(plan.isEmpty)
        assertEquals(2, plan.skippedUnsupportedIds)
    }

    @Test
    fun `completed progress counts as watched and one row per episode is sent`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = listOf(watched("tt0898266", season = 1, episode = 8, at = 700L, name = "The Big Bang Theory")),
            nuvioProgress = listOf(
                progress("tt0898266", season = 1, episode = 8, position = 1_400_000L, at = 690L),
                progress("tt0898266", season = 1, episode = 9, position = 1_400_000L, at = 800L),
            ),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
        )

        assertEquals(
            listOf(Triple(8, 700L, "The Big Bang Theory"), Triple(9, 800L, "tt0898266")),
            plan.history.map { Triple(it.episode, it.markedAtEpochMs, it.name) },
        )
        assertTrue(plan.inProgress.isEmpty())
    }

    @Test
    fun `in-progress rows newer than Trakt are resumed oldest first, one per title`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = emptyList(),
            nuvioProgress = listOf(
                progress("tt13293588", season = 1, episode = 23, position = 600_000L, at = 900L),
                progress("tt0898266", season = 1, episode = 7, position = 300_000L, at = 950L),
                progress("tt0898266", season = 1, episode = 8, position = 300_000L, at = 1_000L),
            ),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = mapOf("tt13293588" to 100L, "tt0898266" to 100L),
            inProgressCutoffEpochMs = null,
        )

        assertEquals(
            listOf("tt13293588" to 23, "tt0898266" to 8),
            plan.inProgress.map { it.record.contentId to it.record.episode },
        )
        assertEquals(600_000.0 * 100.0 / 1_440_000.0, plan.inProgress.first().progressPercent)
    }

    @Test
    fun `in-progress rows already superseded are skipped`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = listOf(watched("tt3", season = 1, episode = 5, at = 2_000L)),
            nuvioProgress = listOf(
                // Trakt has something newer for this title.
                progress("tt1", season = 1, episode = 2, position = 600_000L, at = 500L),
                // The episode itself is already watched on Trakt.
                progress("tt2", season = 1, episode = 2, position = 600_000L, at = 900L),
                // A later episode was finished in Nuvio afterwards.
                progress("tt3", season = 1, episode = 4, position = 600_000L, at = 1_500L),
                // Outside the Continue Watching window.
                progress("tt4", season = 1, episode = 1, position = 600_000L, at = 10L),
            ),
            traktWatchedKeys = setOf(watchedItemKey("series", "tt2", 1, 2)),
            traktLatestActivityByContentId = mapOf("tt1" to 800L),
            inProgressCutoffEpochMs = 100L,
        )

        assertTrue(plan.inProgress.isEmpty())
        assertEquals(listOf("tt3"), plan.history.map { it.id })
    }

    @Test
    fun `pause Trakt already holds at the same point is not sent again`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = emptyList(),
            nuvioProgress = listOf(
                progress("tt2085059", season = 1, episode = 1, position = 578_000L, at = 900L),
                progress("tt14476236", season = 1, episode = 1, position = 900_000L, at = 900L),
            ),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = mapOf("tt2085059" to 100L, "tt14476236" to 100L),
            inProgressCutoffEpochMs = null,
            traktPausedPercentByKey = mapOf(
                pausedProgressKey("tt2085059", 1, 1) to 40.2,
                // Trakt is behind here, so the newer point still goes.
                pausedProgressKey("tt14476236", 1, 1) to 34.1,
            ),
        )

        assertEquals(listOf("tt14476236"), plan.inProgress.map { it.record.contentId })
    }

    @Test
    fun `accidental opens are dropped without hiding the episode really in progress`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = emptyList(),
            nuvioProgress = listOf(
                progress("tt13293588", season = 3, episode = 2, position = 650_000L, at = 900L),
                // Opened the next episode for a few seconds afterwards.
                progress("tt13293588", season = 3, episode = 3, position = 5_000L, at = 950L),
                progress("tt33764258", type = "movie", position = 7_000L, at = 960L),
            ),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
        )

        assertEquals(
            listOf(Triple("tt13293588", 3, 2)),
            plan.inProgress.map { Triple(it.record.contentId, it.record.season, it.record.episode) },
        )
    }

    @Test
    fun `library titles missing on Trakt go to the watchlist, newest saved first`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = emptyList(),
            nuvioProgress = emptyList(),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
            nuvioLibrary = listOf(
                saved("tt0111161", type = "movie", at = 100L),
                saved("tt0898266", type = "series", at = 300L),
            ),
            traktLibraryKeys = emptySet(),
        )

        assertEquals(listOf("tt0898266", "tt0111161"), plan.library.map { it.id })
        assertTrue(plan.history.isEmpty() && plan.inProgress.isEmpty())
    }

    @Test
    fun `library titles Trakt holds under another id form are not sent`() {
        val traktItem = saved("tt0898266", type = "series", at = 1L, tmdbId = 1418)
        val plan = planNuvioToTraktSync(
            nuvioWatched = emptyList(),
            nuvioProgress = emptyList(),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
            nuvioLibrary = listOf(saved("TMDB:1418", type = "series", at = 300L)),
            traktLibraryKeys = libraryMatchKeys(traktItem),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `library titles saved twice or with anime ids only are handled once or skipped`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = emptyList(),
            nuvioProgress = emptyList(),
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
            nuvioLibrary = listOf(
                saved("tt13293588", type = "series", at = 300L, tmdbId = 94664),
                saved("tmdb:94664", type = "series", at = 200L),
                saved("kitsu:43731", type = "series", at = 100L),
            ),
            traktLibraryKeys = emptySet(),
        )

        assertEquals(listOf("tt13293588"), plan.library.map { it.id })
        assertEquals(1, plan.skippedUnsupportedIds)
    }

    @Test
    fun `watched dates are written in the UTC form Trakt expects`() {
        assertEquals("1970-01-01T00:00:00.000Z", epochMsToUtcIso(0L))
        assertEquals("2026-10-07T18:20:00.000Z", epochMsToUtcIso(1_791_397_200_000L))
        assertEquals("2026-10-01T23:39:00.000Z", epochMsToUtcIso(1_790_897_940_000L))
        // A leap day and a non-zero millisecond part.
        assertEquals("2000-02-29T00:00:00.123Z", epochMsToUtcIso(951_782_400_123L))
        assertEquals("1970-01-01T00:00:00.000Z", epochMsToUtcIso(-5L))
    }

    @Test
    fun `only the most recent in-progress rows are kept`() {
        val plan = planNuvioToTraktSync(
            nuvioWatched = emptyList(),
            nuvioProgress = (1..5).map { index ->
                progress("tt$index", type = "movie", position = 600_000L, at = index * 100L)
            },
            traktWatchedKeys = emptySet(),
            traktLatestActivityByContentId = emptyMap(),
            inProgressCutoffEpochMs = null,
            maxInProgress = 2,
        )

        assertEquals(listOf("tt4", "tt5"), plan.inProgress.map { it.record.contentId })
    }

    private fun watched(
        id: String,
        type: String = "series",
        season: Int? = null,
        episode: Int? = null,
        at: Long,
        name: String = id,
    ) = WatchedItem(
        id = id,
        type = type,
        name = name,
        season = season,
        episode = episode,
        markedAtEpochMs = at,
    )

    private fun saved(
        id: String,
        type: String,
        at: Long,
        tmdbId: Int? = null,
    ) = LibraryItem(
        id = id,
        type = type,
        name = id,
        tmdbId = tmdbId,
        savedAtEpochMs = at,
    )

    private fun progress(
        contentId: String,
        type: String = "series",
        season: Int? = null,
        episode: Int? = null,
        position: Long,
        at: Long,
    ) = ProgressSyncRecord(
        contentId = contentId,
        contentType = type,
        videoId = if (season != null) "$contentId:$season:$episode" else contentId,
        season = season,
        episode = episode,
        position = position,
        duration = 1_440_000L,
        lastWatched = at,
    )
}
