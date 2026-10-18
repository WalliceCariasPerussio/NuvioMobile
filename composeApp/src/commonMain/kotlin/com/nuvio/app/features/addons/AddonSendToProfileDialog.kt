package com.nuvio.app.features.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.NuvioProfile
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

// Fork: pick the profiles an installed addon is sent to (see AddonProfileTransfer).
@Composable
internal fun AddonSendToProfileDialog(
    addon: ManagedAddon,
    targets: List<NuvioProfile>,
    onDismiss: () -> Unit,
    onSent: (message: String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val coroutineScope = rememberCoroutineScope()
    val manifestUrl = addon.manifestUrl
    var isChecking by remember(manifestUrl) { mutableStateOf(true) }
    var alreadyInstalled by remember(manifestUrl) { mutableStateOf(emptySet<Int>()) }
    var selected by remember(manifestUrl) { mutableStateOf(emptySet<Int>()) }
    var isSending by remember(manifestUrl) { mutableStateOf(false) }
    var failedProfileNames by remember(manifestUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(manifestUrl) {
        val profileIndexes = targets.map { it.profileIndex }
        // If the check fails, every profile stays selectable; sending checks again anyway.
        alreadyInstalled = AddonProfileTransfer.profilesWithAddon(manifestUrl, profileIndexes)
            .getOrDefault(emptySet())
        val available = profileIndexes.filterNot { it in alreadyInstalled }
        selected = if (available.size == 1) available.toSet() else emptySet()
        isChecking = false
    }

    val send: () -> Unit = send@{
        if (isChecking || isSending || selected.isEmpty()) return@send
        isSending = true
        failedProfileNames = null
        coroutineScope.launch {
            val added = mutableListOf<NuvioProfile>()
            val alreadyHad = mutableListOf<NuvioProfile>()
            val failed = mutableListOf<NuvioProfile>()
            targets.filter { it.profileIndex in selected }.forEach { profile ->
                AddonProfileTransfer.sendAddon(addon, profile.profileIndex)
                    .onSuccess { outcome ->
                        if (outcome == AddonTransferOutcome.Added) added += profile else alreadyHad += profile
                    }
                    .onFailure { failed += profile }
            }
            isSending = false
            if (failed.isEmpty()) {
                val parts = buildList {
                    if (added.isNotEmpty()) add(getString(Res.string.addon_send_done, added.names()))
                    if (alreadyHad.isNotEmpty()) add(getString(Res.string.addon_send_already_had, alreadyHad.names()))
                }
                onSent(parts.joinToString(" "))
            } else {
                // Keep the dialog open on the profiles that failed, so sending again only retries those.
                val done = (added + alreadyHad).map { it.profileIndex }.toSet()
                alreadyInstalled = alreadyInstalled + done
                selected = selected - done
                failedProfileNames = failed.names()
            }
        }
    }

    DialogSurface(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = stringResource(Res.string.addon_send_title, addon.displayTitle),
        message = stringResource(Res.string.addon_send_subtitle),
    ) {
        if (isChecking) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioLoadingIndicator(size = tokens.icons.md)
                Text(
                    text = stringResource(Res.string.addon_send_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap)) {
                targets.forEach { profile ->
                    val hasAddon = profile.profileIndex in alreadyInstalled
                    DialogOption(
                        text = if (profile.profileIndex == 1) {
                            stringResource(Res.string.addon_send_primary_profile, profile.name)
                        } else {
                            profile.name
                        },
                        description = if (hasAddon) stringResource(Res.string.addon_send_already_has) else null,
                        selected = hasAddon || profile.profileIndex in selected,
                        enabled = !hasAddon && !isSending,
                        role = Role.Checkbox,
                        onClick = {
                            selected = if (profile.profileIndex in selected) {
                                selected - profile.profileIndex
                            } else {
                                selected + profile.profileIndex
                            }
                            failedProfileNames = null
                        },
                    )
                }
            }
        }

        failedProfileNames?.let { names ->
            Text(
                text = stringResource(Res.string.addon_send_failed, names),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
                enabled = !isSending,
            )
            DialogButton(
                text = stringResource(Res.string.addon_send_action),
                onClick = send,
                style = DialogButtonStyle.Primary,
                enabled = !isChecking && selected.isNotEmpty(),
                loading = isSending,
            )
        }
    }
}

private fun List<NuvioProfile>.names(): String = joinToString(", ") { it.name }
