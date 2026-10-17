package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.trakt.NuvioToTraktSyncManager
import com.nuvio.app.features.trakt.NuvioToTraktUiState
import com.nuvio.app.features.trakt.TraktAuthRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_analyzing
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_description
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_dialog_history
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_dialog_library
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_dialog_more
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_dialog_progress
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_dialog_skipped
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_dialog_title
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_done
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_episode
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_failed
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_nothing
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_send
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_sending
import nuvio.composeapp.generated.resources.settings_nuvio_to_trakt_title
import org.jetbrains.compose.resources.stringResource

private const val NUVIO_TO_TRAKT_PREVIEW_LINES = 8

/** Row of the Sync section that brings Nuvio-cloud-only watching into Trakt. */
@Composable
internal fun NuvioToTraktSyncRow(
    isTablet: Boolean,
    activeProfileId: Int,
) {
    val tokens = MaterialTheme.nuvio
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val traktConnected by TraktAuthRepository.isAuthenticated.collectAsStateWithLifecycle()
    val state by NuvioToTraktSyncManager.uiState.collectAsStateWithLifecycle()
    val hasNuvioAccount = (authState as? AuthState.Authenticated)?.isAnonymous == false
    if (!hasNuvioAccount || !traktConnected) return

    val isBusy = state is NuvioToTraktUiState.Analyzing || state is NuvioToTraktUiState.Sending
    val status = when (val current = state) {
        NuvioToTraktUiState.Analyzing -> stringResource(Res.string.settings_nuvio_to_trakt_analyzing)
        NuvioToTraktUiState.Sending -> stringResource(Res.string.settings_nuvio_to_trakt_sending)
        NuvioToTraktUiState.NothingToSend -> stringResource(Res.string.settings_nuvio_to_trakt_nothing)
        is NuvioToTraktUiState.Done -> stringResource(
            Res.string.settings_nuvio_to_trakt_done,
            current.historySent,
            current.historyConfirmed,
            current.inProgressSent,
            current.librarySent,
        )
        else -> null
    }

    SettingsGroupDivider(isTablet = isTablet)
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_nuvio_to_trakt_title),
        description = listOfNotNull(
            stringResource(Res.string.settings_nuvio_to_trakt_description),
            status,
        ).joinToString("\n"),
        icon = Icons.Rounded.CloudUpload,
        isTablet = isTablet,
        trailingContent = {
            if (isBusy) NuvioLoadingIndicator(modifier = Modifier.size(16.dp))
        },
        onClick = {
            if (!isBusy) NuvioToTraktSyncManager.analyze(activeProfileId)
        },
    )
    if (state is NuvioToTraktUiState.Failed) {
        SettingsGroupDivider(isTablet = isTablet)
        Text(
            text = stringResource(Res.string.settings_nuvio_to_trakt_failed),
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

    (state as? NuvioToTraktUiState.Ready)?.let { ready ->
        NuvioToTraktConfirmDialog(
            ready = ready,
            onConfirm = NuvioToTraktSyncManager::send,
            onDismiss = NuvioToTraktSyncManager::dismiss,
        )
    }
}

@Composable
private fun NuvioToTraktConfirmDialog(
    ready: NuvioToTraktUiState.Ready,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val plan = ready.plan
    // Newest first: what was just watched elsewhere is what the user is looking for.
    val previewItems = (
        plan.inProgress.map { progress ->
            Triple(progress.record.contentId, progress.record.season, progress.record.episode) to
                progress.record.lastWatched
        } + plan.history.map { item ->
            Triple(item.id, item.season, item.episode) to item.markedAtEpochMs
        }
        )
        .sortedByDescending { (_, at) -> at }
        .map { (identity, _) -> identity }
    val watchingLines = previewItems.map { (contentId, season, episode) ->
        val title = ready.titles[contentId] ?: contentId
        if (season != null && episode != null) {
            stringResource(Res.string.settings_nuvio_to_trakt_episode, title, season, episode)
        } else {
            title
        }
    }
    val allLines = watchingLines + plan.library.map { item -> item.name.ifBlank { item.id } }
    val previewLines = allLines.take(NUVIO_TO_TRAKT_PREVIEW_LINES)
    val hiddenCount = allLines.size - previewLines.size
    val moreLabel = if (hiddenCount > 0) {
        stringResource(Res.string.settings_nuvio_to_trakt_dialog_more, hiddenCount)
    } else {
        null
    }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_nuvio_to_trakt_dialog_title),
    ) {
        val summary = listOfNotNull(
            plan.history.size.takeIf { it > 0 }?.let { count ->
                stringResource(Res.string.settings_nuvio_to_trakt_dialog_history, count)
            },
            plan.inProgress.size.takeIf { it > 0 }?.let { count ->
                stringResource(Res.string.settings_nuvio_to_trakt_dialog_progress, count)
            },
            plan.library.size.takeIf { it > 0 }?.let { count ->
                stringResource(Res.string.settings_nuvio_to_trakt_dialog_library, count)
            },
        )
        summary.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
            )
        }
        Text(
            text = (previewLines.map { line -> "• $line" } + listOfNotNull(moreLabel)).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        if (plan.skippedUnsupportedIds > 0) {
            Text(
                text = stringResource(Res.string.settings_nuvio_to_trakt_dialog_skipped, plan.skippedUnsupportedIds),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.settings_nuvio_to_trakt_send),
                onClick = onConfirm,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
