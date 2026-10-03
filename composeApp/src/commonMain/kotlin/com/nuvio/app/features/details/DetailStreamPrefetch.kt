package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.PlaybackAvailability
import com.nuvio.app.features.streams.StreamsEmptyStateReason
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.languageCounts
import com.nuvio.app.features.streams.languageKeysByCount
import com.nuvio.app.features.streams.matchingLanguageKey
import com.nuvio.app.features.streams.preferredAudioTargets
import com.nuvio.app.features.streams.streamContentOriginalLanguage
import com.nuvio.app.features.streams.streamLanguageLabel
import com.nuvio.app.features.streams.streamTraits
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.detail_stream_prefetch_failed
import nuvio.composeapp.generated.resources.detail_stream_prefetch_found
import nuvio.composeapp.generated.resources.detail_stream_prefetch_in_language
import nuvio.composeapp.generated.resources.detail_stream_prefetch_loading
import nuvio.composeapp.generated.resources.detail_stream_prefetch_loading_progress
import nuvio.composeapp.generated.resources.detail_stream_prefetch_none
import nuvio.composeapp.generated.resources.detail_stream_prefetch_none_in_language
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Starts loading streams as soon as the details screen opens, so the streams screen
// reuses the results when the user presses play. StreamsRepository dedupes on the
// request key, which only works because the request mirrors the primary play click.

private const val PrefetchDebounceMs = 500L

internal data class DetailStreamPrefetchRequest(
    val type: String,
    val videoId: String,
    val parentMetaId: String,
    val season: Int?,
    val episode: Int?,
) {
    val requestToken: String = StreamsRepository.requestToken(
        type = type,
        videoId = videoId,
        season = season,
        episode = episode,
    )

    fun load() {
        StreamsRepository.load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            season = season,
            episode = episode,
        )
    }
}

/** The prefetch owned by the details screen on screen, read by the status row under the play button. */
internal object DetailStreamPrefetch {
    private val _activeRequestToken = MutableStateFlow<String?>(null)
    val activeRequestToken: StateFlow<String?> = _activeRequestToken.asStateFlow()

    fun activate(requestToken: String?) {
        _activeRequestToken.value = requestToken
    }

    fun deactivate(requestToken: String?) {
        _activeRequestToken.compareAndSet(requestToken, null)
    }
}

/** Same target as the primary play click in [MetaDetailsScreen], or null when it would not open streams. */
internal fun detailStreamPrefetchRequest(
    meta: MetaDetails,
    hasEpisodes: Boolean,
    seriesAction: SeriesPrimaryAction?,
    seriesStreamVideoId: String?,
    canPlay: Boolean,
    playbackAvailability: PlaybackAvailability,
): DetailStreamPrefetchRequest? {
    if (!canPlay) return null
    val request = if ((meta.type == "series" || hasEpisodes) && seriesAction != null) {
        DetailStreamPrefetchRequest(
            type = meta.type,
            videoId = seriesStreamVideoId ?: seriesAction.videoId,
            parentMetaId = meta.id,
            season = seriesAction.seasonNumber,
            episode = seriesAction.episodeNumber,
        )
    } else {
        DetailStreamPrefetchRequest(
            type = meta.type,
            videoId = meta.id,
            parentMetaId = meta.id,
            season = null,
            episode = null,
        )
    }
    return request.takeIf { playbackAvailability.canStream(it.type, it.videoId) }
}

@Composable
internal fun DetailStreamPrefetchEffect(request: DetailStreamPrefetchRequest?) {
    val requestToken = request?.requestToken
    DisposableEffect(requestToken) {
        DetailStreamPrefetch.activate(requestToken)
        onDispose { DetailStreamPrefetch.deactivate(requestToken) }
    }
    LaunchedEffect(request) {
        val target = request ?: return@LaunchedEffect
        // Watch progress can still change the episode right after opening; wait it out.
        delay(PrefetchDebounceMs)
        target.load()
        // Leaving the streams screen clears the repository; load again while details is shown.
        StreamsRepository.uiState
            .map { it.requestToken }
            .distinctUntilChanged()
            .filter { it == null }
            .collect {
                delay(PrefetchDebounceMs)
                if (StreamsRepository.uiState.value.requestToken == null) target.load()
            }
    }
}

private sealed interface PrefetchStatus {
    data class Loading(val completed: Int, val total: Int) : PrefetchStatus
    data class Found(val count: Int) : PrefetchStatus
    data object NoneFound : PrefetchStatus
    data object Failed : PrefetchStatus
}

private fun StreamsUiState.prefetchStatus(expectedRequestToken: String): PrefetchStatus {
    if (requestToken != expectedRequestToken) return PrefetchStatus.Loading(completed = 0, total = 0)
    val stillLoading = isAnyLoading ||
        groups.any { it.isLoading } ||
        (groups.isEmpty() && emptyStateReason == null)
    val streamCount = groups.sumOf { it.streams.size }
    return when {
        stillLoading -> PrefetchStatus.Loading(
            completed = groups.count { !it.isLoading },
            total = groups.size,
        )
        streamCount > 0 -> PrefetchStatus.Found(streamCount)
        emptyStateReason == StreamsEmptyStateReason.StreamFetchFailed -> PrefetchStatus.Failed
        else -> PrefetchStatus.NoneFound
    }
}

/** "34 em PT-BR" for the primary and secondary audio languages, counted like the streams screen chips. */
@Composable
private fun preferredLanguageSummary(groups: List<AddonStreamGroup>, requestToken: String): List<String> {
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val counts = remember(groups) {
        languageCounts(groups.flatMap { group -> group.streams.map { it.streamTraits() } })
    }
    val originalLanguage = remember(requestToken) { streamContentOriginalLanguage(requestToken) }
    val targets = remember(
        playerSettings.preferredAudioLanguage,
        playerSettings.secondaryPreferredAudioLanguage,
        originalLanguage,
    ) {
        preferredAudioTargets(playerSettings, originalLanguage).take(2)
    }
    val keysByCount = languageKeysByCount(counts)
    return targets
        .map { target -> target to matchingLanguageKey(keysByCount, target) }
        .distinctBy { (target, key) -> key ?: target }
        .map { (target, key) ->
            if (key == null) {
                stringResource(Res.string.detail_stream_prefetch_none_in_language, streamLanguageLabel(target))
            } else {
                stringResource(Res.string.detail_stream_prefetch_in_language, counts[key] ?: 0, streamLanguageLabel(key))
            }
        }
}

@Composable
internal fun DetailStreamPrefetchStatus(
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val activeRequestToken by DetailStreamPrefetch.activeRequestToken.collectAsStateWithLifecycle()
    val expectedRequestToken = activeRequestToken ?: return
    val streamsUiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val status = streamsUiState.prefetchStatus(expectedRequestToken)
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                is PrefetchStatus.Loading -> NuvioLoadingIndicator(size = 24.dp)
                is PrefetchStatus.Found -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colorScheme.primary,
                )
                PrefetchStatus.NoneFound -> Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colorScheme.onSurfaceVariant,
                )
                PrefetchStatus.Failed -> Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = when (status) {
                is PrefetchStatus.Loading -> if (status.total > 0) {
                    stringResource(
                        Res.string.detail_stream_prefetch_loading_progress,
                        status.completed,
                        status.total,
                    )
                } else {
                    stringResource(Res.string.detail_stream_prefetch_loading)
                }
                is PrefetchStatus.Found -> {
                    val found = pluralStringResource(
                        Res.plurals.detail_stream_prefetch_found,
                        status.count,
                        status.count,
                    )
                    val byLanguage = preferredLanguageSummary(streamsUiState.groups, expectedRequestToken)
                    (listOf(found) + byLanguage).joinToString(" · ")
                }
                PrefetchStatus.NoneFound -> stringResource(Res.string.detail_stream_prefetch_none)
                PrefetchStatus.Failed -> stringResource(Res.string.detail_stream_prefetch_failed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
