package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.sync.SyncNowManager
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.sync.SupabaseLibrarySyncAdapter
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import com.nuvio.app.features.watching.sync.SupabaseProgressSyncAdapter
import com.nuvio.app.features.watching.sync.SupabaseWatchedSyncAdapter
import com.nuvio.app.features.watching.sync.TraktWatchedSyncAdapter
import com.nuvio.app.features.watching.sync.shouldSyncToTraktHistory
import com.nuvio.app.features.watchprogress.isWatchProgressComplete
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val NUVIO_TO_TRAKT_WATCHED_PAGE_SIZE = 900
private const val NUVIO_TO_TRAKT_LIBRARY_PAGE_SIZE = 500
private const val NUVIO_TO_TRAKT_HISTORY_CHUNK = 100
private const val NUVIO_TO_TRAKT_MAX_IN_PROGRESS = 20
private const val NUVIO_TO_TRAKT_IN_PROGRESS_WINDOW_DAYS = 60L
private const val NUVIO_TO_TRAKT_MIN_PROGRESS_PERCENT = 1.0
private const val NUVIO_TO_TRAKT_SAME_PROGRESS_PERCENT = 1.0
// Trakt allows one authenticated POST per second.
private const val NUVIO_TO_TRAKT_POST_SPACING_MS = 1_100L
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
private const val NUVIO_TO_TRAKT_RECENT_PAUSE_MS = 15L * 60L * 1_000L
private const val NUVIO_TO_TRAKT_RECENT_HISTORY_MS = 12L * 60L * 60L * 1_000L

/** One in-progress row Trakt is missing, resumed there with a paused scrobble. */
data class NuvioToTraktProgress(
    val record: ProgressSyncRecord,
    val progressPercent: Double,
)

/** What the Nuvio cloud holds and Trakt lacks, e.g. watched on a device that was not connected to Trakt. */
data class NuvioToTraktPlan(
    val history: List<WatchedItem>,
    val inProgress: List<NuvioToTraktProgress>,
    val library: List<LibraryItem> = emptyList(),
    val skippedUnsupportedIds: Int,
) {
    val isEmpty: Boolean
        get() = history.isEmpty() && inProgress.isEmpty() && library.isEmpty()
}

/**
 * Every id form a saved title can carry on either side, so a Nuvio library item matches the Trakt
 * one however each stored it. Empty when Trakt has no way to identify the title (anime-only ids).
 */
internal fun libraryMatchKeys(item: LibraryItem): Set<String> {
    val kind = if (item.type.trim().lowercase() in setOf("movie", "film")) "movie" else "show"
    val parsed = parseTraktContentIds(normalizeTraktComparableId(item.id))
    return buildSet {
        listOfNotNull(item.imdbId, parsed.imdb)
            .map(String::trim)
            .filter { imdb -> imdb.startsWith("tt") }
            .forEach { imdb -> add("$kind:$imdb") }
        listOfNotNull(item.tmdbId, parsed.tmdb).forEach { tmdb -> add("$kind:tmdb:$tmdb") }
        listOfNotNull(item.traktId, parsed.trakt).forEach { trakt -> add("$kind:trakt:$trakt") }
    }
}

/**
 * Only ids Trakt itself reports back can be checked against its history. Anime ids such as kitsu or
 * mal never match, so sending them would add a duplicate play on every run.
 */
internal fun isTraktComparableContentId(id: String): Boolean {
    val trimmed = id.trim()
    return trimmed.startsWith("tt") ||
        trimmed.startsWith("tmdb:", ignoreCase = true) ||
        trimmed.startsWith("trakt:", ignoreCase = true)
}

/** Trakt reports `tmdb:`/`trakt:` in lower case and trimmed; Nuvio rows must match that to be recognised. */
internal fun normalizeTraktComparableId(id: String): String {
    val trimmed = id.trim()
    val prefix = trimmed.substringBefore(':', missingDelimiterValue = "")
    return if (prefix.equals("tmdb", ignoreCase = true) || prefix.equals("trakt", ignoreCase = true)) {
        "${prefix.lowercase()}:${trimmed.substringAfter(':').trim()}"
    } else {
        trimmed
    }
}

/** UTC timestamp in the form Trakt expects, e.g. `2026-10-17T01:47:00.000Z`. */
internal fun epochMsToUtcIso(epochMs: Long): String {
    val ms = epochMs.coerceAtLeast(0L)
    val totalSeconds = ms / 1_000L
    val millis = (ms % 1_000L).toInt()
    val secondOfDay = (totalSeconds % 86_400L).toInt()
    // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm), valid for every date after 1970.
    val z = totalSeconds / 86_400L + 719_468L
    val era = z / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val shiftedMonth = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * shiftedMonth + 2L) / 5L + 1L).toInt()
    val month = (if (shiftedMonth < 10L) shiftedMonth + 3L else shiftedMonth - 9L).toInt()
    val year = (yearOfEra + era * 400L + if (month <= 2) 1L else 0L).toInt()
    fun Int.pad(width: Int) = toString().padStart(width, '0')
    return "${year.pad(4)}-${month.pad(2)}-${day.pad(2)}T" +
        "${(secondOfDay / 3_600).pad(2)}:${(secondOfDay / 60 % 60).pad(2)}:${(secondOfDay % 60).pad(2)}.${millis.pad(3)}Z"
}

/** Identifies one paused movie or episode, matching any id form of the title. */
internal fun pausedProgressKey(contentId: String, season: Int?, episode: Int?): String =
    "${contentId.trim()}:${season ?: -1}:${episode ?: -1}"

private fun WatchedItem.isMovie(): Boolean =
    type.trim().lowercase() in setOf("movie", "film")

internal fun historyIdentity(item: WatchedItem): String =
    if (item.isMovie()) "movie:${item.id.trim()}" else "episode:${item.id.trim()}:${item.season}:${item.episode}"

/**
 * Picks the Nuvio cloud rows Trakt is missing. Watched episodes and movies (and completed progress)
 * go to Trakt history with their original date, so they never jump ahead in Continue Watching.
 * In-progress rows are resumed only when they are newer than anything Trakt has for that title,
 * oldest first, because Trakt stamps a paused scrobble with the time it is sent.
 */
internal fun planNuvioToTraktSync(
    nuvioWatched: Collection<WatchedItem>,
    nuvioProgress: Collection<ProgressSyncRecord>,
    traktWatchedKeys: Set<String>,
    traktLatestActivityByContentId: Map<String, Long>,
    inProgressCutoffEpochMs: Long?,
    traktPausedPercentByKey: Map<String, Double> = emptyMap(),
    recentlySentHistory: Set<String> = emptySet(),
    nuvioLibrary: Collection<LibraryItem> = emptyList(),
    traktLibraryKeys: Set<String> = emptySet(),
    maxInProgress: Int = NUVIO_TO_TRAKT_MAX_IN_PROGRESS,
): NuvioToTraktPlan {
    fun isWatchedOnTrakt(type: String, id: String, season: Int?, episode: Int?): Boolean =
        watchedItemKeys(type = type, id = id, season = season, episode = episode)
            .any(traktWatchedKeys::contains)

    val normalizedWatched = nuvioWatched.map { item -> item.copy(id = normalizeTraktComparableId(item.id)) }
    val normalizedProgress = nuvioProgress.map { record ->
        record.copy(contentId = normalizeTraktComparableId(record.contentId))
    }
    val unsupported = linkedSetOf<String>()
    val historyByIdentity = linkedMapOf<String, WatchedItem>()
    val completedProgressItems = normalizedProgress
        .filter { record -> isWatchProgressComplete(record.position, record.duration, false) }
        .map { record ->
            WatchedItem(
                id = record.contentId,
                type = record.contentType,
                name = record.contentId,
                season = record.season,
                episode = record.episode,
                videoId = record.videoId.takeIf(String::isNotBlank),
                markedAtEpochMs = record.lastWatched,
            )
        }
    (normalizedWatched + completedProgressItems).forEach { item ->
        if (!item.shouldSyncToTraktHistory()) return@forEach
        val identity = historyIdentity(item)
        if (!isTraktComparableContentId(item.id)) {
            unsupported += identity
            return@forEach
        }
        if (isWatchedOnTrakt(item.type, item.id, item.season, item.episode)) return@forEach
        // Sent moments ago but not recognised in Trakt's reply (e.g. other episode numbering):
        // sending it again would only add another play.
        if (identity in recentlySentHistory) return@forEach
        // A watched mark carries the title; keep it over the completed-progress copy.
        historyByIdentity.putIfAbsent(identity, item)
    }

    val latestByContentId = traktLatestActivityByContentId.toMutableMap()
    (normalizedWatched + completedProgressItems).forEach { item ->
        val id = item.id.trim()
        latestByContentId[id] = maxOf(latestByContentId[id] ?: Long.MIN_VALUE, item.markedAtEpochMs)
    }

    val inProgress = normalizedProgress
        .asSequence()
        .filter { record -> record.position > 0L && record.duration > 0L }
        .filter { record -> !isWatchProgressComplete(record.position, record.duration, false) }
        .filter { record -> inProgressCutoffEpochMs == null || record.lastWatched >= inProgressCutoffEpochMs }
        .filter { record ->
            val supported = isTraktComparableContentId(record.contentId)
            if (!supported) unsupported += "progress:${record.contentId}:${record.season}:${record.episode}"
            supported
        }
        .filterNot { record -> isWatchedOnTrakt(record.contentType, record.contentId, record.season, record.episode) }
        // Something newer for the title (on Trakt or finished in Nuvio) makes this row stale.
        .filter { record -> record.lastWatched > (latestByContentId[record.contentId.trim()] ?: Long.MIN_VALUE) }
        .map { record ->
            NuvioToTraktProgress(
                record = record,
                progressPercent = (record.position.toDouble() * 100.0 / record.duration.toDouble()).coerceAtMost(99.0),
            )
        }
        // Opened by accident (a trailer, a wrong episode): not worth a Continue Watching slot.
        .filter { progress -> progress.progressPercent >= NUVIO_TO_TRAKT_MIN_PROGRESS_PERCENT }
        .groupBy { progress -> progress.record.contentId.trim() }
        .values
        .map { rows -> rows.maxBy { progress -> progress.record.lastWatched } }
        // Trakt keeps the old pause date when the same point is sent again, so this would never settle.
        .filterNot { progress ->
            val record = progress.record
            val pausedPercent = traktPausedPercentByKey[pausedProgressKey(record.contentId, record.season, record.episode)]
            pausedPercent != null && pausedPercent >= progress.progressPercent - NUVIO_TO_TRAKT_SAME_PROGRESS_PERCENT
        }
        .sortedBy { progress -> progress.record.lastWatched }
        .takeLast(maxInProgress)

    // Saved titles go to the Trakt watchlist; adding there is idempotent, so a missed match only re-offers it.
    val libraryByKey = linkedMapOf<String, LibraryItem>()
    nuvioLibrary.sortedByDescending(LibraryItem::savedAtEpochMs).forEach { item ->
        val keys = libraryMatchKeys(item)
        if (keys.isEmpty()) {
            unsupported += "library:${item.id.trim()}"
            return@forEach
        }
        if (keys.any(traktLibraryKeys::contains)) return@forEach
        // The same title saved twice in Nuvio (e.g. under an imdb and a tmdb id) is sent once.
        if (keys.any(libraryByKey::containsKey)) return@forEach
        keys.forEach { key -> libraryByKey[key] = item }
    }

    return NuvioToTraktPlan(
        history = historyByIdentity.values.sortedBy(WatchedItem::markedAtEpochMs),
        inProgress = inProgress,
        library = libraryByKey.values.distinct(),
        skippedUnsupportedIds = unsupported.size,
    )
}

sealed interface NuvioToTraktUiState {
    data object Idle : NuvioToTraktUiState
    data object Analyzing : NuvioToTraktUiState
    data class Ready(
        val profileId: Int,
        val plan: NuvioToTraktPlan,
        val titles: Map<String, String>,
    ) : NuvioToTraktUiState
    data object Sending : NuvioToTraktUiState
    data class Done(
        val historySent: Int,
        val historyConfirmed: Int,
        val inProgressSent: Int,
        val librarySent: Int,
        val skippedUnsupportedIds: Int,
    ) : NuvioToTraktUiState
    data object NothingToSend : NuvioToTraktUiState
    data object Failed : NuvioToTraktUiState
}

/**
 * Brings what only the Nuvio cloud has into Trakt, so a profile that reads Trakt sees what was watched
 * on devices not connected to it, without switching sources. Analysis and sending are separate steps:
 * Trakt history adds are not idempotent, so the user confirms the exact counts first.
 */
object NuvioToTraktSyncManager {
    private val log = Logger.withTag("NuvioToTrakt")
    private val json = Json { ignoreUnknownKeys = true }
    private val writeJson = Json { explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobLock = SynchronizedObject()
    private var job: Job? = null
    private data class SentPause(val percent: Double, val sentAtEpochMs: Long)
    /** A 4xx from Trakt: it recorded nothing, so the batch can be offered again. */
    private class TraktHistoryRejected(message: String) : Exception(message)

    private fun markHistorySent(profileId: Int, items: List<WatchedItem>) {
        val sentAt = TraktPlatformClock.nowEpochMs()
        synchronized(jobLock) {
            items.forEach { item -> sentHistoryAt["$profileId|${historyIdentity(item)}"] = sentAt }
        }
    }

    private fun unmarkHistorySent(profileId: Int, items: List<WatchedItem>) {
        synchronized(jobLock) {
            items.forEach { item -> sentHistoryAt.remove("$profileId|${historyIdentity(item)}") }
        }
    }

    private class RecentlySent(val pausedPercentByKey: Map<String, Double>, val historyIdentities: Set<String>)

    // Keyed by "profileId|item"; only kept for this app session.
    private val sentPauses = mutableMapOf<String, SentPause>()
    private val sentHistoryAt = mutableMapOf<String, Long>()

    private fun recentlySent(profileId: Int): RecentlySent {
        val prefix = "$profileId|"
        val now = TraktPlatformClock.nowEpochMs()
        return synchronized(jobLock) {
            sentPauses.entries.removeAll { (_, pause) -> now - pause.sentAtEpochMs > NUVIO_TO_TRAKT_RECENT_PAUSE_MS }
            sentHistoryAt.entries.removeAll { (_, sentAt) -> now - sentAt > NUVIO_TO_TRAKT_RECENT_HISTORY_MS }
            RecentlySent(
                pausedPercentByKey = sentPauses
                    .filterKeys { key -> key.startsWith(prefix) }
                    .entries
                    .associate { (key, pause) -> key.removePrefix(prefix) to pause.percent },
                historyIdentities = sentHistoryAt.keys
                    .filter { key -> key.startsWith(prefix) }
                    .mapTo(linkedSetOf()) { key -> key.removePrefix(prefix) },
            )
        }
    }

    private val _uiState = MutableStateFlow<NuvioToTraktUiState>(NuvioToTraktUiState.Idle)
    val uiState: StateFlow<NuvioToTraktUiState> = _uiState.asStateFlow()

    fun isAvailable(): Boolean {
        val authState = AuthRepository.state.value
        return authState is AuthState.Authenticated &&
            !authState.isAnonymous &&
            TraktAuthRepository.isAuthenticated.value
    }

    fun analyze(profileId: Int) = launchExclusive(NuvioToTraktUiState.Analyzing) {
        val (plan, traktTitles) = buildPlan(profileId)
        log.i {
            "Nuvio to Trakt plan profile=$profileId history=${plan.history.size} " +
                "inProgress=${plan.inProgress.size} library=${plan.library.size} " +
                "skippedUnsupported=${plan.skippedUnsupportedIds}"
        }
        if (plan.isEmpty) {
            NuvioToTraktUiState.NothingToSend
        } else {
            val titles = plan.history
                .filter { item -> item.name.isNotBlank() && item.name != item.id }
                .associate { item -> item.id to item.name } + traktTitles
            NuvioToTraktUiState.Ready(profileId = profileId, plan = plan, titles = titles)
        }
    }

    fun send() {
        val ready = _uiState.value as? NuvioToTraktUiState.Ready ?: return
        launchExclusive(NuvioToTraktUiState.Sending) {
            sendPlan(ready.profileId, ready.plan)
        }
    }

    fun dismiss() {
        synchronized(jobLock) {
            if (job?.isActive == true) return
            _uiState.value = NuvioToTraktUiState.Idle
        }
    }

    private fun launchExclusive(
        runningState: NuvioToTraktUiState,
        block: suspend () -> NuvioToTraktUiState,
    ) {
        synchronized(jobLock) {
            if (job?.isActive == true) return
            _uiState.value = runningState
            job = scope.launch {
                var finalState: NuvioToTraktUiState = NuvioToTraktUiState.Failed
                try {
                    finalState = block()
                } catch (error: CancellationException) {
                    finalState = NuvioToTraktUiState.Idle
                    throw error
                } catch (error: Throwable) {
                    log.e(error) { "Nuvio to Trakt sync failed" }
                } finally {
                    _uiState.value = finalState
                }
            }
        }
    }

    /** The plan, plus Trakt's titles for the ids it knows (Nuvio progress rows carry no title). */
    private suspend fun buildPlan(profileId: Int): Pair<NuvioToTraktPlan, Map<String, String>> {
        check(isAvailable()) { "Nuvio account and Trakt must both be connected" }
        val nuvioWatched = SupabaseWatchedSyncAdapter.pull(profileId, NUVIO_TO_TRAKT_WATCHED_PAGE_SIZE)
        val nuvioProgress = SupabaseProgressSyncAdapter.pull(profileId)
        val traktWatched = pullTraktWatched(profileId)
        val playback = fetchTraktPlayback()
        val latestActivity = traktWatched.latestByContentId.toMutableMap()
        playback.latestByContentId.forEach { (contentId, pausedAt) ->
            latestActivity[contentId] = maxOf(latestActivity[contentId] ?: Long.MIN_VALUE, pausedAt)
        }
        // Trakt takes minutes to list a fresh pause; what was just sent counts as there for a while.
        val recent = recentlySent(profileId)
        val pausedPercent = playback.percentByKey.toMutableMap()
        recent.pausedPercentByKey.forEach { (key, percent) ->
            pausedPercent[key] = maxOf(pausedPercent[key] ?: 0.0, percent)
        }
        val nuvioLibrary = SupabaseLibrarySyncAdapter.pullSnapshot(profileId, NUVIO_TO_TRAKT_LIBRARY_PAGE_SIZE)
        val plan = planNuvioToTraktSync(
            nuvioWatched = nuvioWatched,
            nuvioProgress = nuvioProgress,
            traktWatchedKeys = traktWatched.keys,
            traktLatestActivityByContentId = latestActivity,
            inProgressCutoffEpochMs = TraktPlatformClock.nowEpochMs() -
                NUVIO_TO_TRAKT_IN_PROGRESS_WINDOW_DAYS * MILLIS_PER_DAY,
            traktPausedPercentByKey = pausedPercent,
            recentlySentHistory = recent.historyIdentities,
            nuvioLibrary = nuvioLibrary,
            traktLibraryKeys = loadTraktLibraryKeys(),
        )
        return plan to (playback.titlesByContentId + traktWatched.titlesByContentId)
    }

    private suspend fun sendPlan(profileId: Int, plan: NuvioToTraktPlan): NuvioToTraktUiState {
        check(isAvailable()) { "Nuvio account and Trakt must both be connected" }
        // Sent straight to Trakt, without the app's usual retry under a remapped episode number: that retry
        // adds a play the comparison cannot recognise afterwards, so the next run would add another one.
        val history = mutableListOf<WatchedItem>()
        plan.history.chunked(NUVIO_TO_TRAKT_HISTORY_CHUNK).forEachIndexed { index, chunk ->
            if (index > 0) delay(NUVIO_TO_TRAKT_POST_SPACING_MS)
            // The plan was made when the dialog opened. Another device may have logged some of these since, and a
            // history add is not idempotent, so what Trakt holds right before each batch decides what goes in it.
            val alreadyThere = pullTraktWatched(profileId).keys
            val toSend = chunk.filterNot { item ->
                watchedItemKeys(item.type, item.id, item.season, item.episode).any(alreadyThere::contains)
            }
            if (toSend.isEmpty()) return@forEachIndexed
            val added = try {
                // Marked as the request goes out: if it times out after Trakt processed it, a re-run must not add
                // it again. Not before: a batch that never left (no token) stays on offer.
                addToTraktHistory(toSend, onSending = { markHistorySent(profileId, toSend) })
            } catch (error: TraktHistoryRejected) {
                unmarkHistorySent(profileId, toSend)
                throw error
            }
            log.i { "Trakt history add profile=$profileId sent=${toSend.size} added=$added" }
            // Trakt said it recorded none of them (all not found): nothing to guard against. An answer without
            // the counts says nothing, so the batch stays marked.
            if (added == 0) unmarkHistorySent(profileId, toSend)
            history += toSend
        }

        var inProgressSent = 0
        plan.inProgress.forEach { progress ->
            delay(NUVIO_TO_TRAKT_POST_SPACING_MS)
            val record = progress.record
            TraktScrobbleRepository.scrobble(
                profileId = profileId,
                action = TrackingScrobbleAction.PAUSE,
                event = TrackingScrobbleEvent(
                    media = buildTrackingMediaReference(
                        contentType = record.contentType,
                        parentMetaId = record.contentId,
                        videoId = record.videoId.takeIf(String::isNotBlank),
                        seasonNumber = record.season,
                        episodeNumber = record.episode,
                    ),
                    progressPercent = progress.progressPercent,
                ),
            )
            // The scrobble reports no outcome; the entry expires so a silent failure is offered again later.
            synchronized(jobLock) {
                sentPauses["$profileId|${pausedProgressKey(record.contentId, record.season, record.episode)}"] =
                    SentPause(percent = progress.progressPercent, sentAtEpochMs = TraktPlatformClock.nowEpochMs())
            }
            inProgressSent += 1
        }

        var libraryAdded = 0
        plan.library.chunked(NUVIO_TO_TRAKT_HISTORY_CHUNK).forEach { chunk ->
            delay(NUVIO_TO_TRAKT_POST_SPACING_MS)
            libraryAdded += addToTraktWatchlist(chunk)
        }
        if (plan.library.isNotEmpty()) TraktLibraryRepository.refreshNow()

        // Trakt answers adds without per-item detail; re-reading its history shows what actually landed.
        val confirmedKeys = pullTraktWatched(profileId).keys
        val historyConfirmed = history.count { item ->
            watchedItemKeys(item.type, item.id, item.season, item.episode).any(confirmedKeys::contains)
        }
        log.i {
            "Nuvio to Trakt sent profile=$profileId history=${history.size} confirmed=$historyConfirmed " +
                "inProgress=$inProgressSent library=${plan.library.size} libraryConfirmed=$libraryAdded"
        }
        SyncNowManager.syncNow(profileId)
        return NuvioToTraktUiState.Done(
            historySent = history.size,
            historyConfirmed = historyConfirmed,
            inProgressSent = inProgressSent,
            librarySent = libraryAdded,
            skippedUnsupportedIds = plan.skippedUnsupportedIds,
        )
    }

    /**
     * Adds watched items to Trakt history with their original date; returns how many Trakt recorded, or
     * null when its answer doesn't say. [onSending] runs right before the request leaves, once nothing can
     * stop it but the network.
     */
    private suspend fun addToTraktHistory(items: List<WatchedItem>, onSending: () -> Unit): Int? {
        val headers = TraktAuthRepository.authorizedHeaders() ?: error("Trakt is not connected")
        fun WatchedItem.historyIds(): TraktWatchlistIdsDto {
            val parsed = parseTraktContentIds(normalizeTraktComparableId(id))
            return TraktWatchlistIdsDto(imdb = parsed.imdb, tmdb = parsed.tmdb, trakt = parsed.trakt)
        }
        val (movieItems, episodeItems) = items.partition { item -> item.isMovie() }
        val shows = episodeItems.groupBy { item -> normalizeTraktComparableId(item.id) }.values.map { episodes ->
            TraktHistoryShowRequestDto(
                title = episodes.first().name.takeIf { name -> name.isNotBlank() && name != episodes.first().id },
                ids = episodes.first().historyIds(),
                seasons = episodes.groupBy { item -> item.season }.mapNotNull { (season, inSeason) ->
                    season ?: return@mapNotNull null
                    TraktHistorySeasonRequestDto(
                        number = season,
                        episodes = inSeason.mapNotNull { item ->
                            item.episode?.let { number ->
                                TraktHistoryEpisodeRequestDto(number = number, watchedAt = epochMsToUtcIso(item.markedAtEpochMs))
                            }
                        },
                    )
                },
            )
        }
        val body = writeJson.encodeToString(
            TraktHistoryRequestDto(
                movies = movieItems.map { item ->
                    TraktHistoryMovieRequestDto(
                        title = item.name.takeIf { name -> name.isNotBlank() && name != item.id },
                        ids = item.historyIds(),
                        watchedAt = epochMsToUtcIso(item.markedAtEpochMs),
                    )
                }.takeIf { it.isNotEmpty() },
                shows = shows.takeIf { it.isNotEmpty() },
            ),
        )
        onSending()
        val response = httpPostOnce(
            url = "https://api.trakt.tv/sync/history",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ) + headers,
            body = body,
        )
        if (response.status in 400..499) throw TraktHistoryRejected("Trakt history add rejected: HTTP ${response.status}")
        // A 5xx (or a gateway's 52x) can come after Trakt recorded the batch: it stays marked as maybe sent.
        check(response.status in 200..299) { "Trakt history add failed: HTTP ${response.status}" }
        val added = json.decodeFromString<TraktHistoryResponseDto>(response.body).added ?: return null
        return added.movies + added.episodes
    }

    /** Library ids Trakt already holds, across the watchlist and personal lists. */
    private suspend fun loadTraktLibraryKeys(): Set<String> {
        TraktLibraryRepository.refreshNow()
        val state = TraktLibraryRepository.uiState.value
        check(state.hasLoaded && state.errorMessage == null) { "Trakt library unavailable: ${state.errorMessage}" }
        return state.allItems.flatMapTo(linkedSetOf(), ::libraryMatchKeys)
    }

    /** Adds titles to the Trakt watchlist; returns how many Trakt now holds there (added or already present). */
    private suspend fun addToTraktWatchlist(items: List<LibraryItem>): Int {
        val headers = TraktAuthRepository.authorizedHeaders() ?: return 0
        fun LibraryItem.watchlistIds(): TraktWatchlistIdsDto {
            val parsed = parseTraktContentIds(normalizeTraktComparableId(id))
            return TraktWatchlistIdsDto(
                imdb = (imdbId ?: parsed.imdb)?.takeIf { imdb -> imdb.startsWith("tt") },
                tmdb = tmdbId ?: parsed.tmdb,
                trakt = traktId ?: parsed.trakt,
            )
        }
        val (movies, shows) = items.partition { item -> item.type.trim().lowercase() in setOf("movie", "film") }
        val body = writeJson.encodeToString(
            TraktWatchlistAddRequestDto(
                movies = movies.map { item -> TraktWatchlistItemDto(ids = item.watchlistIds()) }.takeIf { it.isNotEmpty() },
                shows = shows.map { item -> TraktWatchlistItemDto(ids = item.watchlistIds()) }.takeIf { it.isNotEmpty() },
            ),
        )
        val response = httpRequestRaw(
            method = "POST",
            url = "https://api.trakt.tv/sync/watchlist",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ) + headers,
            body = body,
        )
        if (response.status !in 200..299) {
            log.w { "Trakt watchlist add failed: HTTP ${response.status}" }
            return 0
        }
        val result = json.decodeFromString<TraktWatchlistAddResponseDto>(response.body)
        return listOfNotNull(result.added, result.existing).sumOf { counts -> counts.movies + counts.shows }
    }

    private class TraktWatchedSnapshot(
        val keys: Set<String>,
        val latestByContentId: Map<String, Long>,
        val titlesByContentId: Map<String, String>,
    )

    private suspend fun pullTraktWatched(profileId: Int): TraktWatchedSnapshot {
        TraktWatchedShowSnapshotRepository.clear()
        val items = TraktWatchedSyncAdapter.pull(profileId, NUVIO_TO_TRAKT_WATCHED_PAGE_SIZE)
        val keys = items.flatMapTo(linkedSetOf()) { item ->
            watchedItemKeys(item.type, item.id, item.season, item.episode)
        }
        keys += TraktWatchedSyncAdapter.pullExtraWatchedKeys(profileId)
        val latest = mutableMapOf<String, Long>()
        items.forEach { item ->
            latest[item.id] = maxOf(latest[item.id] ?: Long.MIN_VALUE, item.markedAtEpochMs)
        }
        return TraktWatchedSnapshot(
            keys = keys,
            latestByContentId = latest,
            titlesByContentId = items.associate { item -> item.id to item.name },
        )
    }

    private class TraktPlaybackSnapshot(
        val latestByContentId: Map<String, Long>,
        val percentByKey: Map<String, Double>,
        val titlesByContentId: Map<String, String>,
    )

    /** Trakt's paused items, keyed under every id form Trakt reports for the title. */
    private suspend fun fetchTraktPlayback(): TraktPlaybackSnapshot {
        val headers = TraktAuthRepository.authorizedHeaders()
            ?: return TraktPlaybackSnapshot(emptyMap(), emptyMap(), emptyMap())
        val payload = httpGetTextWithHeaders(url = "https://api.trakt.tv/sync/playback", headers = headers)
        val latest = mutableMapOf<String, Long>()
        val percent = mutableMapOf<String, Double>()
        val titles = mutableMapOf<String, String>()
        json.decodeFromString<List<TraktPlaybackPeekDto>>(payload).forEach { item ->
            val media = item.show ?: item.movie ?: return@forEach
            val ids = media.ids ?: return@forEach
            val pausedAt = item.pausedAt?.let(::parseTraktIsoDateTimeToEpochMs)
            listOfNotNull(
                ids.imdb?.takeIf(String::isNotBlank),
                ids.tmdb?.let { "tmdb:$it" },
                ids.trakt?.let { "trakt:$it" },
            ).forEach { contentId ->
                pausedAt?.let { latest[contentId] = maxOf(latest[contentId] ?: Long.MIN_VALUE, it) }
                item.progress?.let {
                    percent[pausedProgressKey(contentId, item.episode?.season, item.episode?.number)] = it
                }
                media.title?.takeIf(String::isNotBlank)?.let { titles[contentId] = it }
            }
        }
        return TraktPlaybackSnapshot(
            latestByContentId = latest,
            percentByKey = percent,
            titlesByContentId = titles,
        )
    }
}

@Serializable
private data class TraktHistoryRequestDto(
    val movies: List<TraktHistoryMovieRequestDto>? = null,
    val shows: List<TraktHistoryShowRequestDto>? = null,
)

@Serializable
private data class TraktHistoryMovieRequestDto(
    val title: String? = null,
    val ids: TraktWatchlistIdsDto,
    @SerialName("watched_at") val watchedAt: String,
)

@Serializable
private data class TraktHistoryShowRequestDto(
    val title: String? = null,
    val ids: TraktWatchlistIdsDto,
    val seasons: List<TraktHistorySeasonRequestDto>,
)

@Serializable
private data class TraktHistorySeasonRequestDto(
    val number: Int,
    val episodes: List<TraktHistoryEpisodeRequestDto>,
)

@Serializable
private data class TraktHistoryEpisodeRequestDto(
    val number: Int,
    @SerialName("watched_at") val watchedAt: String,
)

@Serializable
private data class TraktHistoryResponseDto(
    val added: TraktHistoryCountsDto? = null,
)

@Serializable
private data class TraktHistoryCountsDto(
    val movies: Int = 0,
    val episodes: Int = 0,
)

@Serializable
private data class TraktWatchlistAddRequestDto(
    val movies: List<TraktWatchlistItemDto>? = null,
    val shows: List<TraktWatchlistItemDto>? = null,
)

@Serializable
private data class TraktWatchlistItemDto(
    val ids: TraktWatchlistIdsDto,
)

@Serializable
private data class TraktWatchlistIdsDto(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val trakt: Int? = null,
)

@Serializable
private data class TraktWatchlistAddResponseDto(
    val added: TraktWatchlistCountsDto? = null,
    val existing: TraktWatchlistCountsDto? = null,
)

@Serializable
private data class TraktWatchlistCountsDto(
    val movies: Int = 0,
    val shows: Int = 0,
)

@Serializable
private data class TraktPlaybackPeekDto(
    @SerialName("paused_at") val pausedAt: String? = null,
    val progress: Double? = null,
    val movie: TraktPlaybackPeekMediaDto? = null,
    val show: TraktPlaybackPeekMediaDto? = null,
    val episode: TraktPlaybackPeekEpisodeDto? = null,
)

@Serializable
private data class TraktPlaybackPeekMediaDto(
    val title: String? = null,
    val ids: TraktPlaybackPeekIdsDto? = null,
)

@Serializable
private data class TraktPlaybackPeekEpisodeDto(
    val season: Int? = null,
    val number: Int? = null,
)

@Serializable
private data class TraktPlaybackPeekIdsDto(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val trakt: Long? = null,
)
