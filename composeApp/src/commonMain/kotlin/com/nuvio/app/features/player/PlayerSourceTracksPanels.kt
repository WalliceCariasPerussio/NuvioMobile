package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.streams.StreamQualityBucket
import com.nuvio.app.features.streams.preferredAudioTargets
import com.nuvio.app.features.streams.streamLanguageLabel
import com.nuvio.app.features.streams.streamQualityLabel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_source_tracks_best_quality
import nuvio.composeapp.generated.resources.player_source_tracks_cancel
import nuvio.composeapp.generated.resources.player_source_tracks_loading
import nuvio.composeapp.generated.resources.player_source_tracks_missing_audio
import nuvio.composeapp.generated.resources.player_source_tracks_none
import nuvio.composeapp.generated.resources.player_source_tracks_quality_title
import nuvio.composeapp.generated.resources.player_source_tracks_scope_all_audio
import nuvio.composeapp.generated.resources.player_source_tracks_scope_all_qualities
import nuvio.composeapp.generated.resources.player_source_tracks_scope_current_audio
import nuvio.composeapp.generated.resources.player_source_tracks_scope_current_stream
import nuvio.composeapp.generated.resources.player_source_tracks_sources
import nuvio.composeapp.generated.resources.player_source_tracks_switch_audio_to
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Fork: the audio panel's "Todos" scope (one row per audio language of every source) and the
// quality panel. Picking a language or quality the stream playing doesn't have switches to the best
// source for it (PlayerSourceTracks.kt).

/** What the audio "Todos" scope and the quality panel show, from every source of what is playing. */
class PlayerSourceTracksUi internal constructor(
    internal val options: SourceTrackOptions,
    internal val isLoading: Boolean,
    internal val onRequestSources: () -> Unit,
    internal val onLanguageSelected: (String) -> Unit,
    internal val onQualitySelected: (StreamQualityBucket, String?) -> Unit,
)

private val NoSourceTrackOptions = SourceTrackOptions(
    currentLanguage = null,
    languages = emptyList(),
    qualitiesWithCurrentAudio = emptyList(),
    allQualities = emptyList(),
)

@Composable
internal fun PlayerScreenRuntime.rememberPlayerSourceTracksUi(): PlayerSourceTracksUi {
    // Reading every source's traits is only worth it while one of the panels is open.
    val panelsOpen = showAudioModal || showQualityModal
    val targets = preferredAudioTargets(playerSettingsUiState, contentLanguage)
    val options = remember(
        panelsOpen, sourceStreamsState.groups, audioTracks, selectedAudioIndex, activeSourceUrl,
        activeSourceIdentityKey, targets, playerSettingsUiState.streamAutoPlayMaxQuality,
    ) {
        if (panelsOpen) {
            buildSourceTrackOptions(sourceStreamsState.groups.flatMap { it.streams }, targets)
        } else {
            NoSourceTrackOptions
        }
    }
    return PlayerSourceTracksUi(
        options = options,
        isLoading = sourceStreamsState.isAnyLoading,
        onRequestSources = { loadSourcesForTrackPanels() },
        onLanguageSelected = { language -> selectSourceAudioLanguage(language) },
        onQualitySelected = { quality, language -> selectSourceQuality(quality, language) },
    )
}

@Composable
private fun sourcesCount(n: Int): String = pluralStringResource(Res.plurals.player_source_tracks_sources, n, n)

/** Scope chips at the top of the audio and quality panels. */
@Composable
internal fun SourceTrackScopeChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Text(
                text = label,
                color = if (selected) tokens.colors.onAccent else Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) tokens.colors.accent else Color.White.copy(alpha = 0.14f))
                    .clickable { onSelected(index) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** A row of the audio "Todos" list or of the quality panel, styled like the audio track rows. */
@Composable
private fun SourceTrackOptionRow(
    label: String,
    detail: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val primaryColor = if (isSelected) tokens.colors.onAccent else Color.White
    val secondaryColor = if (isSelected) tokens.colors.onAccent.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.72f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) tokens.colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                color = primaryColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = secondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun SourceTrackEmptyText(isLoading: Boolean) {
    Text(
        text = stringResource(if (isLoading) Res.string.player_source_tracks_loading else Res.string.player_source_tracks_none),
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
}

/** Audio panel, "Todos" scope: one row per audio language found in the sources. */
@Composable
internal fun SourceAudioLanguageList(
    sourceTracks: PlayerSourceTracksUi,
    maxHeight: Dp,
) {
    LaunchedEffect(Unit) { sourceTracks.onRequestSources() }
    val languages = sourceTracks.options.languages
    if (languages.isEmpty()) {
        SourceTrackEmptyText(sourceTracks.isLoading)
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(languages, key = { it.key }) { option ->
            val sources = sourcesCount(option.streamCount)
            SourceTrackOptionRow(
                label = streamLanguageLabel(option.key),
                detail = option.bestQuality?.let {
                    stringResource(Res.string.player_source_tracks_best_quality, streamQualityLabel(it), sources)
                } ?: sources,
                isSelected = option.isCurrent,
                onClick = { sourceTracks.onLanguageSelected(option.key) },
            )
        }
    }
}

/**
 * Quality panel: "Áudio atual · X" lists the qualities that keep the audio playing; "Todas" lists
 * every quality, and one without that audio asks which audio to switch to.
 */
@Composable
internal fun PlayerQualityModal(
    visible: Boolean,
    sourceTracks: PlayerSourceTracksUi?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerOverlayScaffold(
        visible = visible && sourceTracks != null,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp),
    ) {
        val tracks = sourceTracks ?: return@PlayerOverlayScaffold
        LaunchedEffect(Unit) { tracks.onRequestSources() }
        val options = tracks.options
        val currentLanguage = options.currentLanguage
        var showAll by remember { mutableStateOf(currentLanguage == null) }
        var prompt by remember { mutableStateOf<SourceQualityOption?>(null) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val railWidth = minOf(maxWidth, 444.dp)
            val railMaxHeight = (maxHeight - 112.dp).coerceAtLeast(120.dp).coerceAtMost(560.dp)

            Column(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.BottomStart),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = stringResource(Res.string.player_source_tracks_quality_title),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                val currentLanguageLabel = currentLanguage?.let { streamLanguageLabel(it) }
                if (currentLanguageLabel != null) {
                    SourceTrackScopeChips(
                        labels = listOf(
                            stringResource(Res.string.player_source_tracks_scope_current_audio, currentLanguageLabel),
                            stringResource(Res.string.player_source_tracks_scope_all_qualities),
                        ),
                        selectedIndex = if (showAll) 1 else 0,
                        onSelected = { index ->
                            showAll = index == 1
                            prompt = null
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                val pending = prompt
                if (pending != null) {
                    QualityAudioPrompt(
                        option = pending,
                        currentLanguageLabel = currentLanguageLabel.orEmpty(),
                        maxHeight = railMaxHeight,
                        onLanguageSelected = { language -> tracks.onQualitySelected(pending.bucket, language) },
                        onCancel = { prompt = null },
                    )
                    return@Column
                }

                val qualities = if (showAll || currentLanguage == null) {
                    options.allQualities
                } else {
                    options.qualitiesWithCurrentAudio
                }
                if (qualities.isEmpty()) {
                    SourceTrackEmptyText(tracks.isLoading)
                    return@Column
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = railMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(qualities, key = { it.bucket.name }) { option ->
                        SourceTrackOptionRow(
                            label = streamQualityLabel(option.bucket),
                            detail = sourcesCount(option.streamCount),
                            isSelected = option.isCurrent,
                            onClick = {
                                if (option.hasCurrentAudio) {
                                    tracks.onQualitySelected(option.bucket, currentLanguage)
                                } else {
                                    prompt = option
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** "No 4K source has PT-BR audio. Switch audio to:" with that quality's languages and Cancel. */
@Composable
private fun QualityAudioPrompt(
    option: SourceQualityOption,
    currentLanguageLabel: String,
    maxHeight: Dp,
    onLanguageSelected: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = stringResource(
            Res.string.player_source_tracks_missing_audio,
            streamQualityLabel(option.bucket),
            currentLanguageLabel,
        ),
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
    Text(
        text = stringResource(Res.string.player_source_tracks_switch_audio_to),
        color = Color.White.copy(alpha = 0.72f),
        style = MaterialTheme.typography.bodyMedium,
    )
    val cancelLabel = stringResource(Res.string.player_source_tracks_cancel)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(option.languages, key = { it }) { language ->
            SourceTrackOptionRow(
                label = streamLanguageLabel(language),
                detail = null,
                isSelected = false,
                onClick = { onLanguageSelected(language) },
            )
        }
        item(key = "cancel") {
            SourceTrackOptionRow(
                label = cancelLabel,
                detail = null,
                isSelected = false,
                onClick = onCancel,
            )
        }
    }
}

@Composable
internal fun sourceTrackAudioScopeLabels(): List<String> = listOf(
    stringResource(Res.string.player_source_tracks_scope_current_stream),
    stringResource(Res.string.player_source_tracks_scope_all_audio),
)
