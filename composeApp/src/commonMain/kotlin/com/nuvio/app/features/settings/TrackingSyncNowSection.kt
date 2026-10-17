package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.sync.SyncNowManager
import com.nuvio.app.core.sync.SyncNowStep
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_sync_now_description
import nuvio.composeapp.generated.resources.settings_sync_now_done
import nuvio.composeapp.generated.resources.settings_sync_now_failed
import nuvio.composeapp.generated.resources.settings_sync_now_hours_ago
import nuvio.composeapp.generated.resources.settings_sync_now_just_now
import nuvio.composeapp.generated.resources.settings_sync_now_minutes_ago
import nuvio.composeapp.generated.resources.settings_sync_now_running
import nuvio.composeapp.generated.resources.settings_sync_now_section
import nuvio.composeapp.generated.resources.settings_sync_now_step_history
import nuvio.composeapp.generated.resources.settings_sync_now_step_library
import nuvio.composeapp.generated.resources.settings_sync_now_step_progress
import nuvio.composeapp.generated.resources.settings_sync_now_step_uploads
import nuvio.composeapp.generated.resources.settings_sync_now_title
import org.jetbrains.compose.resources.stringResource

private const val SYNC_NOW_ELAPSED_TICK_MS = 30_000L

@Composable
internal fun TrackingSyncNowSection(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val syncState by SyncNowManager.uiState.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val activeProfileId = profileState.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    val profileSyncState = syncState.takeIf { state -> state.profileId == activeProfileId }
    val isRunning = profileSyncState?.isRunning == true
    val finishedAtEpochMs = profileSyncState?.finishedAtEpochMs
    val failedSteps = profileSyncState?.failedSteps.orEmpty()

    var nowEpochMs by remember { mutableLongStateOf(EpisodeReleaseDatePlatform.nowEpochMs()) }
    LaunchedEffect(finishedAtEpochMs) {
        while (true) {
            nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs()
            delay(SYNC_NOW_ELAPSED_TICK_MS)
        }
    }

    val status = when {
        isRunning -> stringResource(Res.string.settings_sync_now_running)
        finishedAtEpochMs != null && failedSteps.isEmpty() -> stringResource(
            Res.string.settings_sync_now_done,
            syncNowElapsedLabel(nowEpochMs - finishedAtEpochMs),
        )
        else -> null
    }

    SettingsSection(
        title = stringResource(Res.string.settings_sync_now_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_sync_now_title),
                description = listOfNotNull(
                    stringResource(Res.string.settings_sync_now_description),
                    status,
                ).joinToString("\n"),
                icon = Icons.Rounded.Sync,
                isTablet = isTablet,
                trailingContent = if (isRunning) {
                    { NuvioLoadingIndicator(modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
                onClick = {
                    if (!isRunning) SyncNowManager.syncNow(activeProfileId)
                },
            )
            if (!isRunning && failedSteps.isNotEmpty()) {
                val failedLabels = failedSteps.map { step -> syncNowStepLabel(step) }
                SettingsGroupDivider(isTablet = isTablet)
                Text(
                    text = stringResource(Res.string.settings_sync_now_failed, failedLabels.joinToString(", ")),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isTablet) 20.dp else 16.dp,
                            vertical = if (isTablet) 14.dp else 12.dp,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            NuvioToTraktSyncRow(
                isTablet = isTablet,
                activeProfileId = activeProfileId,
            )
        }
    }
}

@Composable
private fun syncNowElapsedLabel(elapsedMs: Long): String {
    val minutes = (elapsedMs / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> stringResource(Res.string.settings_sync_now_just_now)
        minutes < 60L -> stringResource(Res.string.settings_sync_now_minutes_ago, minutes.toInt())
        else -> stringResource(Res.string.settings_sync_now_hours_ago, (minutes / 60L).toInt())
    }
}

@Composable
private fun syncNowStepLabel(step: SyncNowStep): String = stringResource(
    when (step) {
        SyncNowStep.PendingUploads -> Res.string.settings_sync_now_step_uploads
        SyncNowStep.Library -> Res.string.settings_sync_now_step_library
        SyncNowStep.WatchProgress -> Res.string.settings_sync_now_step_progress
        SyncNowStep.WatchedHistory -> Res.string.settings_sync_now_step_history
    },
)
