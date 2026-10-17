package com.nuvio.app.core.sync

import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchprogress.WatchProgressSourceTransitionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncNowManagerTest {

    @Test
    fun `pending uploads run before every pull and the cache rebuild comes last`() = runBlocking {
        val events = mutableListOf<String>()

        val failedSteps = runSyncNow(
            profileId = 3,
            hasNuvioAccount = true,
            operations = operations(events),
        )

        assertEquals(emptySet(), failedSteps)
        assertEquals(
            listOf(
                "push-progress:3",
                "push-watched:3",
                "library:3",
                "provider:3",
                "source:3",
                "rebuild:3",
            ),
            events,
        )
    }

    @Test
    fun `without a Nuvio account nothing is uploaded but sources still refresh`() = runBlocking {
        val events = mutableListOf<String>()

        val failedSteps = runSyncNow(
            profileId = 1,
            hasNuvioAccount = false,
            operations = operations(events),
        )

        assertEquals(emptySet(), failedSteps)
        assertEquals(listOf("library:1", "provider:1", "source:1", "rebuild:1"), events)
    }

    @Test
    fun `a failing step is reported and the remaining steps still run`() = runBlocking {
        val events = mutableListOf<String>()
        val reported = mutableListOf<SyncNowStep>()

        val failedSteps = runSyncNow(
            profileId = 1,
            hasNuvioAccount = true,
            operations = operations(events).copy(
                pushPendingProgress = { error("offline") },
                pullLibrary = { profileId ->
                    events += "library:$profileId"
                    false
                },
            ),
            onFailure = { step, _ -> reported += step },
        )

        assertEquals(setOf(SyncNowStep.PendingUploads, SyncNowStep.Library), failedSteps)
        assertEquals(listOf(SyncNowStep.PendingUploads), reported)
        assertEquals(listOf("push-watched:1", "library:1", "provider:1", "source:1", "rebuild:1"), events)
    }

    @Test
    fun `failed progress refresh keeps the Continue Watching cache`() = runBlocking {
        val events = mutableListOf<String>()

        val failedSteps = runSyncNow(
            profileId = 1,
            hasNuvioAccount = false,
            operations = operations(
                events = events,
                transition = transitionResult(progressRefreshed = false, watchedHistoryRefreshed = true),
            ),
        )

        assertEquals(setOf(SyncNowStep.WatchProgress), failedSteps)
        assertEquals(listOf("library:1", "provider:1", "source:1"), events)
    }

    @Test
    fun `watched history failure alone still rebuilds Continue Watching`() = runBlocking {
        val events = mutableListOf<String>()

        val failedSteps = runSyncNow(
            profileId = 1,
            hasNuvioAccount = false,
            operations = operations(
                events = events,
                transition = transitionResult(progressRefreshed = true, watchedHistoryRefreshed = false),
            ),
        )

        assertEquals(setOf(SyncNowStep.WatchedHistory), failedSteps)
        assertEquals("rebuild:1", events.last())
    }

    @Test
    fun `throwing source refresh fails both progress and history`() = runBlocking {
        val events = mutableListOf<String>()

        val failedSteps = runSyncNow(
            profileId = 1,
            hasNuvioAccount = false,
            operations = operations(events).copy(
                refreshActiveWatchSource = { error("trakt down") },
            ),
        )

        assertEquals(setOf(SyncNowStep.WatchProgress, SyncNowStep.WatchedHistory), failedSteps)
        assertEquals(listOf("library:1", "provider:1"), events)
    }

    @Test
    fun `cancellation is not swallowed as a step failure`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                runSyncNow(
                    profileId = 1,
                    hasNuvioAccount = false,
                    operations = operations(mutableListOf()).copy(
                        refreshActiveWatchSource = { throw CancellationException("inactive profile") },
                    ),
                )
            }
        }
    }

    private fun operations(
        events: MutableList<String>,
        transition: WatchProgressSourceTransitionResult = transitionResult(),
    ): SyncNowOperations = SyncNowOperations(
        pushPendingProgress = { profileId ->
            events += "push-progress:$profileId"
            true
        },
        pushPendingWatched = { profileId ->
            events += "push-watched:$profileId"
            true
        },
        pullLibrary = { profileId ->
            events += "library:$profileId"
            true
        },
        refreshProgressProvider = { profileId ->
            events += "provider:$profileId"
            true
        },
        refreshActiveWatchSource = { profileId ->
            events += "source:$profileId"
            transition
        },
        rebuildContinueWatching = { profileId -> events += "rebuild:$profileId" },
    )

    private fun transitionResult(
        progressRefreshed: Boolean = true,
        watchedHistoryRefreshed: Boolean = true,
    ) = WatchProgressSourceTransitionResult(
        requestedSource = WatchProgressSource.TRAKT,
        effectiveSource = WatchProgressSource.TRAKT,
        progressRefreshed = progressRefreshed,
        watchedHistoryRefreshed = watchedHistoryRefreshed,
    )
}
