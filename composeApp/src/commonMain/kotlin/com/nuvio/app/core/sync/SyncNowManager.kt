package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.tracking.providerId
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import com.nuvio.app.features.watchprogress.WatchProgressSourceTransitionResult
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SyncNowStep {
    PendingUploads,
    Library,
    WatchProgress,
    WatchedHistory,
}

data class SyncNowUiState(
    val isRunning: Boolean = false,
    val profileId: Int? = null,
    val finishedAtEpochMs: Long? = null,
    val failedSteps: Set<SyncNowStep> = emptySet(),
)

internal data class SyncNowOperations(
    val pushPendingProgress: suspend (Int) -> Boolean,
    val pushPendingWatched: suspend (Int) -> Boolean,
    val pullLibrary: suspend (Int) -> Boolean,
    val refreshProgressProvider: suspend (Int) -> Boolean,
    val refreshActiveWatchSource: suspend (Int) -> WatchProgressSourceTransitionResult,
    val rebuildContinueWatching: (Int) -> Unit,
)

/**
 * Manual "sync now". Sends what never reached Nuvio Sync first, so the forced pulls cannot bury it,
 * then reloads library, progress and watched history from the active sources past their freshness
 * windows, and finally drops the Continue Watching cache so Home rebuilds it from the fresh data.
 */
internal suspend fun runSyncNow(
    profileId: Int,
    hasNuvioAccount: Boolean,
    operations: SyncNowOperations,
    onFailure: (SyncNowStep, Throwable) -> Unit = { _, _ -> },
): Set<SyncNowStep> {
    val failedSteps = linkedSetOf<SyncNowStep>()

    suspend fun runStep(
        step: SyncNowStep,
        operation: suspend () -> Boolean,
    ) {
        val succeeded = try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(step, error)
            false
        }
        if (!succeeded) failedSteps += step
    }

    if (hasNuvioAccount) {
        runStep(SyncNowStep.PendingUploads) { operations.pushPendingProgress(profileId) }
        runStep(SyncNowStep.PendingUploads) { operations.pushPendingWatched(profileId) }
    }
    runStep(SyncNowStep.Library) { operations.pullLibrary(profileId) }
    runStep(SyncNowStep.WatchProgress) { operations.refreshProgressProvider(profileId) }

    var watchedHistoryRefreshed = false
    runStep(SyncNowStep.WatchProgress) {
        val result = operations.refreshActiveWatchSource(profileId)
        watchedHistoryRefreshed = result.watchedHistoryRefreshed
        result.progressRefreshed
    }
    if (!watchedHistoryRefreshed) failedSteps += SyncNowStep.WatchedHistory

    // Offline the cache is all Home has for posters and next-up episodes, so keep it unless both reads worked:
    // rebuilding from a progress list without its history would show stale next-up episodes.
    if (SyncNowStep.WatchProgress !in failedSteps && SyncNowStep.WatchedHistory !in failedSteps) {
        operations.rebuildContinueWatching(profileId)
    }
    return failedSteps
}

/**
 * Best-effort resend of watch activity that never reached Nuvio Sync, run before automatic pulls.
 * A failure here must not block the pull; the rows stay pending for the next sync.
 */
internal suspend fun pushPendingWatchActivity(profileId: Int) {
    try {
        WatchProgressRepository.pushPendingToServer(profileId)
        WatchedRepository.pushPendingToServer(profileId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Logger.withTag("SyncNow").w(error) { "Pending watch activity resend failed profile=$profileId" }
    }
}

object SyncNowManager {
    private val log = Logger.withTag("SyncNow")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runLock = SynchronizedObject()
    private var runJob: Job? = null

    private val _uiState = MutableStateFlow(SyncNowUiState())
    val uiState: StateFlow<SyncNowUiState> = _uiState.asStateFlow()

    private val operations = SyncNowOperations(
        pushPendingProgress = { profileId -> WatchProgressRepository.pushPendingToServer(profileId) },
        pushPendingWatched = { profileId -> WatchedRepository.pushPendingToServer(profileId) },
        pullLibrary = { profileId -> pullLibraryFromActiveSource(profileId) },
        refreshProgressProvider = { refreshProgressProviderSnapshot() },
        refreshActiveWatchSource = { profileId ->
            WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = true)
        },
        rebuildContinueWatching = { profileId -> ContinueWatchingEnrichmentCache.clearAll(profileId) },
    )

    fun syncNow(profileId: Int) {
        synchronized(runLock) {
            if (runJob?.isActive == true) return
            _uiState.update { state -> state.copy(isRunning = true, profileId = profileId) }
            runJob = scope.launch {
                var failedSteps: Set<SyncNowStep>? = null
                try {
                    log.i { "Sync now started profile=$profileId" }
                    failedSteps = try {
                        runSyncNow(
                            profileId = profileId,
                            hasNuvioAccount = hasNuvioAccount(),
                            operations = operations,
                            onFailure = { step, error ->
                                log.e(error) { "Sync now step failed profile=$profileId step=$step" }
                            },
                        )
                    } catch (error: CancellationException) {
                        // The source refresh drops work for a profile that is no longer active.
                        ensureActive()
                        log.w { "Sync now interrupted profile=$profileId: ${error.message}" }
                        setOf(SyncNowStep.WatchProgress)
                    }
                    log.i { "Sync now finished profile=$profileId failedSteps=$failedSteps" }
                } finally {
                    _uiState.value = SyncNowUiState(
                        profileId = profileId,
                        finishedAtEpochMs = failedSteps?.let { EpisodeReleaseDatePlatform.nowEpochMs() },
                        failedSteps = failedSteps.orEmpty(),
                    )
                }
            }
        }
    }

    private fun hasNuvioAccount(): Boolean {
        val authState = AuthRepository.state.value
        return authState is AuthState.Authenticated && !authState.isAnonymous
    }

    private suspend fun pullLibraryFromActiveSource(profileId: Int): Boolean {
        val providerId = activeLibraryProviderId()
        if (providerId == null && !hasNuvioAccount()) return true

        LibraryRepository.pullFromServer(
            profileId = profileId,
            refreshIntent = TrackingRefreshIntent.USER_INITIATED,
        )
        // The repository logs and swallows pull errors; a provider keeps its own in the snapshot.
        return providerId
            ?.let { id -> TrackingProviderRegistry.libraryProvider(id)?.snapshot()?.errorMessage == null }
            ?: true
    }

    /**
     * Forced source refreshes still honour some providers' own freshness windows (Simkl's progress
     * refresh is always automatic), so the snapshot behind progress is reloaded as a user request.
     */
    private suspend fun refreshProgressProviderSnapshot(): Boolean {
        TrackingSettingsRepository.ensureLoaded()
        val progressProviderId = effectiveWatchProgressSource(
            requestedSource = TrackingSettingsRepository.uiState.value.watchProgressSource,
            isProviderAuthenticated = { id ->
                TrackingProviderRegistry.progressProvider(id) != null && TrackingProviderRegistry.isAuthenticated(id)
            },
        ).providerId ?: return true
        // The library pull already reloaded this provider with the same intent.
        if (progressProviderId == activeLibraryProviderId()) return true

        val provider = TrackingProviderRegistry.libraryProvider(progressProviderId) ?: return true
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        return provider.snapshot().errorMessage == null
    }

    private fun activeLibraryProviderId(): TrackingProviderId? {
        TrackingSettingsRepository.ensureLoaded()
        return effectiveLibrarySourceMode(
            requestedSource = TrackingSettingsRepository.uiState.value.librarySourceMode,
            isProviderAuthenticated = { id ->
                TrackingProviderRegistry.libraryProvider(id) != null && TrackingProviderRegistry.isAuthenticated(id)
            },
        ).providerId
    }
}
