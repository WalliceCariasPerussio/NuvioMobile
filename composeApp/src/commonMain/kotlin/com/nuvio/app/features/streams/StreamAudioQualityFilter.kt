package com.nuvio.app.features.streams

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.player.normalizeLanguageCode
import com.nuvio.app.features.player.resolveContentLanguage
import com.nuvio.app.features.player.resolvePreferredAudioLanguageTargets
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.stream_filter_all_languages
import nuvio.composeapp.generated.resources.stream_filter_all_qualities
import nuvio.composeapp.generated.resources.stream_filter_audio
import nuvio.composeapp.generated.resources.stream_filter_language_portuguese
import nuvio.composeapp.generated.resources.stream_filter_language_unknown
import nuvio.composeapp.generated.resources.stream_filter_preferred_missing
import nuvio.composeapp.generated.resources.stream_filter_quality
import nuvio.composeapp.generated.resources.stream_filter_quality_other
import nuvio.composeapp.generated.resources.streams_refresh
import org.jetbrains.compose.resources.stringResource

// Audio language and quality chips that replace the addon chips on the streams screen.
// Languages are read from the stream text (AIOStreams' parsed languages, the "🔊" line
// written by nuvio-providers, or Torrentio's flags) and normalized to one code, so the
// same language coming from different addons lands in a single chip.

internal const val StreamLanguageUnknown = "unknown"
private const val ChoiceAuto = "auto"
private const val ChoiceAll = "all"

private const val AudioMarker = "🔊"
private const val SubtitleMarker = "💬"
private const val OtherTracksMarker = "🌐"

enum class StreamQualityBucket { UHD_4K, QHD_1440, FHD_1080, HD_720, SD, OTHER }

internal data class StreamTraits(
    val audioLanguages: List<String>,
    val quality: StreamQualityBucket,
)

/** Labels nuvio-providers writes in the "🔊" line (tracks.js `LANG_NAMES`). */
private val ProviderLanguageNames = mapOf(
    "pt-br" to "pt-br",
    "pt-pt" to "pt",
    "português" to "pt",
    "portugues" to "pt",
    "inglês" to "en",
    "ingles" to "en",
    "japonês" to "ja",
    "espanhol" to "es",
    "espanhol (la)" to "es-419",
    "italiano" to "it",
    "francês" to "fr",
    "alemão" to "de",
    "russo" to "ru",
    "hindi" to "hi",
    "tâmil" to "ta",
    "telugu" to "te",
    "coreano" to "ko",
    "chinês" to "zh",
    "polonês" to "pl",
    "húngaro" to "hu",
    "tcheco" to "cs",
    "eslovaco" to "sk",
    "ucraniano" to "uk",
    "árabe" to "ar",
    "turco" to "tr",
    "sueco" to "sv",
    "holandês" to "nl",
    "hebraico" to "he",
    "tailandês" to "th",
    "vietnamita" to "vi",
    "indonésio" to "id",
)

private val FlagLanguages = mapOf(
    "🇬🇧" to "en",
    "🇺🇸" to "en",
    "🇧🇷" to "pt-br",
    "🇵🇹" to "pt",
    "🇯🇵" to "ja",
    "🇪🇸" to "es",
    "🇲🇽" to "es-419",
    "🇮🇹" to "it",
    "🇫🇷" to "fr",
    "🇩🇪" to "de",
    "🇷🇺" to "ru",
    "🇰🇷" to "ko",
    "🇨🇳" to "zh",
    "🇵🇱" to "pl",
    "🇺🇦" to "uk",
    "🇮🇳" to "hi",
    "🇹🇷" to "tr",
    "🇳🇱" to "nl",
    "🇸🇪" to "sv",
)

private val KnownLanguageCodes: Set<String> =
    AvailableLanguageOptions.mapNotNull { normalizeLanguageCode(it.code) }.toSet()

// Last resort for streams without a "🔊" line or flags. "Dual Áudio" only counts with the
// accent: that is how the Brazilian providers name PT-BR + original releases.
private val DubbedPtBrPattern = Regex("(^|[^a-z0-9])(dublado|dublada|pt-?br|nacional|dual áudio)([^a-z0-9]|$)")
private val WithoutPtBrPattern = Regex("(^|[^a-z0-9])(sem|no|without)[ -]+(pt-?br|dublagem|portugu)")

// Same tokens as DebridStreamMetadata.resolutionValue, compiled once: this runs for
// every stream each time an addon answers.
private val ResolutionPatterns = listOf(
    StreamQualityBucket.UHD_4K to resolutionPattern("2160p?", "4k", "uhd"),
    StreamQualityBucket.QHD_1440 to resolutionPattern("1440p?", "2k"),
    StreamQualityBucket.FHD_1080 to resolutionPattern("1080p?", "fhd"),
    StreamQualityBucket.HD_720 to resolutionPattern("720p?", "hd"),
    StreamQualityBucket.SD to resolutionPattern("576p?", "480p?", "360p?", "sd"),
)

private fun resolutionPattern(vararg tokens: String): Regex =
    Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|$)")

/** Normalized audio language code, or null when [raw] is not a language. */
internal fun streamLanguageCode(raw: String?): String? {
    val cleaned = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val code = ProviderLanguageNames[cleaned] ?: cleaned
    return normalizeLanguageCode(code)?.takeIf { it in KnownLanguageCodes }
}

internal fun StreamItem.streamTraits(): StreamTraits = StreamTraits(
    audioLanguages = detectAudioLanguages(),
    quality = detectQuality(),
)

private fun StreamItem.detectAudioLanguages(): List<String> {
    clientResolve?.stream?.raw?.parsed?.languages
        ?.mapNotNull(::streamLanguageCode)
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    val lines = listOfNotNull(name, title, description).flatMap { it.lines() }
    lines.firstOrNull { AudioMarker in it }?.let { line ->
        return line.substringAfter(AudioMarker)
            .split('+')
            .mapNotNull { part ->
                streamLanguageCode(part.replace("(original)", "").trim().trimEnd('?'))
            }
            .distinct()
    }

    val flagged = lines
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith(SubtitleMarker) || trimmed.startsWith(OtherTracksMarker)
        }
        .flatMap { line -> line.flagEmojis().mapNotNull { FlagLanguages[it] } }
        .distinct()
    if (flagged.isNotEmpty()) return flagged

    val text = listOfNotNull(name, title, behaviorHints.filename).joinToString(" ").lowercase()
    if (WithoutPtBrPattern.containsMatchIn(text)) return emptyList()
    return if (DubbedPtBrPattern.containsMatchIn(text)) listOf("pt-br") else emptyList()
}

/**
 * Flags in [this], in order. A flag is a pair of regional indicators (each a surrogate pair), read
 * from the start of each run: "🇬🇧🇷🇺" is GB + RU, never the BR formed by the letters in the middle.
 */
private fun String.flagEmojis(): List<String> {
    val flags = mutableListOf<String>()
    var pending = -1
    var index = 0
    while (index < length) {
        val isIndicator = this[index] == '\uD83C' && index + 1 < length && this[index + 1] in '\uDDE6'..'\uDDFF'
        if (!isIndicator) {
            pending = -1
            index++
            continue
        }
        if (pending < 0) {
            pending = index
        } else {
            flags += substring(pending, index + 2)
            pending = -1
        }
        index += 2
    }
    return flags
}

private fun StreamItem.detectQuality(): StreamQualityBucket {
    val parsed = clientResolve?.stream?.raw?.parsed
    val candidates = listOfNotNull(
        parsed?.resolution,
        parsed?.quality,
        name,
        listOfNotNull(title, description, behaviorHints.filename).joinToString(" "),
    )
    for (candidate in candidates) {
        val text = candidate.lowercase()
        ResolutionPatterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }
            ?.let { return it.first }
    }
    return StreamQualityBucket.OTHER
}

internal fun languageCounts(traits: List<StreamTraits>): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    traits.forEach { streamTraits ->
        streamTraits.audioLanguages.ifEmpty { listOf(StreamLanguageUnknown) }.forEach { key ->
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts
}

/** Identified languages, most streams first. */
internal fun languageKeysByCount(counts: Map<String, Int>): List<String> =
    counts.entries
        .filter { it.key != StreamLanguageUnknown }
        .sortedByDescending { it.value }
        .map { it.key }

/** The chip a preferred language selects: exact code first, then the same base language. */
internal fun matchingLanguageKey(keysByCount: List<String>, target: String): String? =
    keysByCount.firstOrNull { it == target }
        ?: keysByCount.firstOrNull { it.substringBefore('-') == target.substringBefore('-') }

/** Preferred audio languages in order (primary, then secondary), as normalized codes. */
internal fun preferredAudioTargets(
    settings: PlayerSettingsUiState,
    contentOriginalLanguage: String?,
): List<String> =
    resolvePreferredAudioLanguageTargets(
        preferredAudioLanguage = settings.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        contentOriginalLanguage = contentOriginalLanguage,
    ).mapNotNull(::normalizeLanguageCode).distinct()

/** Original language of the title being streamed, when it is the one open in details. */
internal fun streamContentOriginalLanguage(requestToken: String?): String? {
    val videoId = requestToken?.split("::")?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val meta = MetaDetailsRepository.uiState.value.meta ?: return null
    val sameTitle = meta.id == videoId ||
        videoId.startsWith("${meta.id}:") ||
        meta.videos.any { it.id == videoId }
    if (!sameTitle) return null
    return resolveContentLanguage(meta.language, meta.country)
}

@Composable
internal fun streamLanguageLabel(key: String): String = when (key) {
    "pt-br" -> "PT-BR"
    "pt" -> stringResource(Res.string.stream_filter_language_portuguese)
    StreamLanguageUnknown -> stringResource(Res.string.stream_filter_language_unknown)
    else -> languageLabelForCode(key)
}

@Composable
internal fun streamQualityLabel(bucket: StreamQualityBucket): String = when (bucket) {
    StreamQualityBucket.UHD_4K -> "4K"
    StreamQualityBucket.QHD_1440 -> "1440p"
    StreamQualityBucket.FHD_1080 -> "1080p"
    StreamQualityBucket.HD_720 -> "720p"
    StreamQualityBucket.SD -> "SD"
    StreamQualityBucket.OTHER -> stringResource(Res.string.stream_filter_quality_other)
}

internal data class StreamFilterOption(val key: String, val count: Int)

internal class StreamAudioQualityFilter(
    val languages: List<StreamFilterOption>,
    val totalCount: Int,
    val selectedLanguage: String,
    val qualities: List<StreamFilterOption>,
    val languageMatchCount: Int,
    val selectedQuality: String,
    /** Preferred language that has no streams, when the selection fell back to another one. */
    val missingPreferredLanguage: String?,
    val filteredUiState: StreamsUiState,
    val onLanguageSelected: (String) -> Unit,
    val onQualitySelected: (String) -> Unit,
)

private class StreamFilterSelection(
    val languages: List<StreamFilterOption>,
    val totalCount: Int,
    val selectedLanguage: String,
    val qualities: List<StreamFilterOption>,
    val languageMatchCount: Int,
    val selectedQuality: String,
    val missingPreferredLanguage: String?,
    val filteredUiState: StreamsUiState,
)

@Composable
internal fun rememberStreamAudioQualityFilter(uiState: StreamsUiState): StreamAudioQualityFilter {
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val requestToken = uiState.requestToken
    val groups = uiState.groups
    val traitsByGroup = remember(groups) {
        groups.map { group -> group.streams.map { it.streamTraits() } }
    }
    val originalLanguage = remember(requestToken) { streamContentOriginalLanguage(requestToken) }
    val targets = remember(
        playerSettings.preferredAudioLanguage,
        playerSettings.secondaryPreferredAudioLanguage,
        originalLanguage,
    ) {
        preferredAudioTargets(playerSettings, originalLanguage)
    }
    // "auto" follows the preferred language and the best quality until the user picks a chip.
    var languageChoice by remember(requestToken) { mutableStateOf(ChoiceAuto) }
    var qualityChoice by remember(requestToken) { mutableStateOf(ChoiceAuto) }

    val maxQuality = playerSettings.streamAutoPlayMaxQuality.bucket
    val selection = remember(uiState, traitsByGroup, targets, originalLanguage, languageChoice, qualityChoice, maxQuality) {
        selectStreams(uiState, traitsByGroup, targets, originalLanguage, languageChoice, qualityChoice, maxQuality)
    }
    return StreamAudioQualityFilter(
        languages = selection.languages,
        totalCount = selection.totalCount,
        selectedLanguage = selection.selectedLanguage,
        qualities = selection.qualities,
        languageMatchCount = selection.languageMatchCount,
        selectedQuality = selection.selectedQuality,
        missingPreferredLanguage = selection.missingPreferredLanguage,
        filteredUiState = selection.filteredUiState,
        onLanguageSelected = { key ->
            languageChoice = key
            qualityChoice = ChoiceAuto
        },
        onQualitySelected = { key -> qualityChoice = key },
    )
}

private fun selectStreams(
    uiState: StreamsUiState,
    traitsByGroup: List<List<StreamTraits>>,
    targets: List<String>,
    originalLanguage: String?,
    languageChoice: String,
    qualityChoice: String,
    maxQuality: StreamQualityBucket?,
): StreamFilterSelection {
    val allTraits = traitsByGroup.flatten()
    val counts = languageCounts(allTraits)
    val keysByCount = languageKeysByCount(counts)

    val preferredKeys = targets.mapNotNull { matchingLanguageKey(keysByCount, it) }.distinct()
    // "Não identificado" sits right after "Todos", ahead of the languages.
    val orderedKeys = listOfNotNull(StreamLanguageUnknown.takeIf { it in counts }) +
        preferredKeys +
        keysByCount.filterNot { it in preferredKeys }

    // With no preference the list starts unfiltered; otherwise preferred, then original, then the largest.
    val autoLanguage: String
    val missingPreferred: String?
    when {
        targets.isEmpty() || counts.isEmpty() -> {
            autoLanguage = ChoiceAll
            missingPreferred = null
        }
        preferredKeys.isNotEmpty() -> {
            autoLanguage = preferredKeys.first()
            missingPreferred = null
        }
        else -> {
            autoLanguage = originalLanguage?.let { matchingLanguageKey(keysByCount, it) }
                ?: keysByCount.firstOrNull()
                ?: ChoiceAll
            missingPreferred = targets.first()
        }
    }
    val selectedLanguage = when (languageChoice) {
        ChoiceAuto -> autoLanguage
        ChoiceAll -> ChoiceAll
        in counts -> languageChoice
        else -> autoLanguage
    }

    fun matchesLanguage(traits: StreamTraits): Boolean = when (selectedLanguage) {
        ChoiceAll -> true
        StreamLanguageUnknown -> traits.audioLanguages.isEmpty()
        else -> selectedLanguage in traits.audioLanguages
    }

    val languageTraits = allTraits.filter(::matchesLanguage)
    val qualities = StreamQualityBucket.entries.mapNotNull { bucket ->
        val count = languageTraits.count { it.quality == bucket }
        if (count > 0) StreamFilterOption(bucket.name, count) else null
    }
    // The best quality within the automatic cap, else the closest one above it.
    val knownQualities = qualities.filter { it.key != StreamQualityBucket.OTHER.name }
    val autoQuality = knownQualities.firstOrNull { StreamQualityBucket.valueOf(it.key).isWithin(maxQuality) }?.key
        ?: knownQualities.lastOrNull()?.key
        ?: ChoiceAll
    val selectedQuality = when (qualityChoice) {
        ChoiceAuto -> autoQuality
        ChoiceAll -> ChoiceAll
        else -> qualityChoice.takeIf { choice -> qualities.any { it.key == choice } } ?: ChoiceAll
    }

    fun matchesQuality(traits: StreamTraits): Boolean =
        selectedQuality == ChoiceAll || traits.quality.name == selectedQuality

    val filtering = selectedLanguage != ChoiceAll || selectedQuality != ChoiceAll
    val filteredGroups = if (!filtering) {
        uiState.groups
    } else {
        uiState.groups.mapIndexedNotNull { groupIndex, group ->
            val traits = traitsByGroup.getOrNull(groupIndex).orEmpty()
            val kept = group.streams.filterIndexed { index, _ ->
                traits.getOrNull(index)?.let { matchesLanguage(it) && matchesQuality(it) } == true
            }
            // Groups still loading stay so the list keeps its loading footer.
            if (kept.isEmpty() && !group.isLoading) null else group.copy(streams = kept)
        }
    }

    return StreamFilterSelection(
        languages = orderedKeys.map { StreamFilterOption(it, counts[it] ?: 0) },
        totalCount = allTraits.size,
        selectedLanguage = selectedLanguage,
        qualities = qualities,
        languageMatchCount = languageTraits.size,
        selectedQuality = selectedQuality,
        missingPreferredLanguage = missingPreferred.takeIf { languageChoice == ChoiceAuto },
        // The addon filter has no chips anymore, so a saved one must not hide streams.
        filteredUiState = uiState.copy(groups = filteredGroups, selectedFilter = null),
    )
}

@Composable
internal fun StreamAudioQualityFilterRow(
    filter: StreamAudioQualityFilter,
    isLoading: Boolean,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        StreamFilterChipRow(
            label = stringResource(Res.string.stream_filter_audio).takeIf { filter.totalCount > 0 },
        ) {
            if (onRefresh != null) {
                StreamFilterChip(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.streams_refresh),
                    selected = false,
                    onClick = onRefresh,
                )
            }
            if (filter.totalCount > 0) {
                StreamFilterChip(
                    label = stringResource(Res.string.stream_filter_all_languages),
                    count = filter.totalCount,
                    selected = filter.selectedLanguage == ChoiceAll,
                    onClick = { filter.onLanguageSelected(ChoiceAll) },
                )
                filter.languages.forEach { option ->
                    StreamFilterChip(
                        label = streamLanguageLabel(option.key),
                        count = option.count,
                        selected = filter.selectedLanguage == option.key,
                        onClick = { filter.onLanguageSelected(option.key) },
                    )
                }
            }
        }
        if (filter.qualities.isNotEmpty()) {
            StreamFilterChipRow(label = stringResource(Res.string.stream_filter_quality)) {
                StreamFilterChip(
                    label = stringResource(Res.string.stream_filter_all_qualities),
                    count = filter.languageMatchCount,
                    selected = filter.selectedQuality == ChoiceAll,
                    onClick = { filter.onQualitySelected(ChoiceAll) },
                )
                filter.qualities.forEach { option ->
                    StreamFilterChip(
                        label = streamQualityLabel(StreamQualityBucket.valueOf(option.key)),
                        count = option.count,
                        selected = filter.selectedQuality == option.key,
                        onClick = { filter.onQualitySelected(option.key) },
                    )
                }
            }
        }
        val missing = filter.missingPreferredLanguage
        if (missing != null && !isLoading && filter.selectedLanguage != ChoiceAll) {
            Text(
                text = stringResource(
                    Res.string.stream_filter_preferred_missing,
                    streamLanguageLabel(missing),
                    streamLanguageLabel(filter.selectedLanguage),
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StreamFilterChipRow(
    label: String?,
    chips: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        chips()
    }
}

@Composable
private fun StreamFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = null,
    count: Int? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "stream_filter_chip_container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 180),
        label = "stream_filter_chip_content",
    )
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        letterSpacing = 0.1.sp,
                    ),
                    color = contentColor,
                    maxLines = 1,
                )
            }
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}
