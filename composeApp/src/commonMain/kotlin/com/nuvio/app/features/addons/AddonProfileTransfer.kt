package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileState
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

enum class AddonTransferOutcome { Added, AlreadyInstalled }

internal data class AddonTransferEntry(
    val manifestUrl: String,
    val name: String,
    val enabled: Boolean,
)

@Serializable
private data class AddonTransferRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonTransferPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

// Fork: send an installed addon to other profiles of the same account (same feature as the TV app).
// It is a one-time copy: each profile keeps its own list afterwards. sync_push_addons replaces a
// profile's whole list, so the target's current list is read first (from the account when signed in,
// else this device's copy) and the addon is appended to it.
internal object AddonProfileTransfer {
    private val log = Logger.withTag("AddonProfileTransfer")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Profiles that can receive an addon from the active one. A profile that mirrors the primary
     * profile's addons has no list of its own, so it is left out (and so is the primary when the
     * active profile is the one mirroring it).
     */
    fun eligibleTargets(state: ProfileState): List<NuvioProfile> {
        val active = state.activeProfile ?: return emptyList()
        val activeMirrorsPrimary = active.mirrorsPrimaryAddons
        return state.profiles.filter { profile ->
            profile.profileIndex != active.profileIndex &&
                !profile.mirrorsPrimaryAddons &&
                !(activeMirrorsPrimary && profile.profileIndex == 1)
        }
    }

    suspend fun profilesWithAddon(manifestUrl: String, profileIndexes: Collection<Int>): Result<Set<Int>> =
        withContext(Dispatchers.Default) {
            try {
                Result.success(
                    profileIndexes.filterTo(mutableSetOf()) { profileIndex ->
                        currentEntries(profileIndex).any { sameAddonUrl(it.manifestUrl, manifestUrl) }
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "profilesWithAddon() — FAILED for $manifestUrl" }
                Result.failure(error)
            }
        }

    suspend fun sendAddon(addon: ManagedAddon, profileIndex: Int): Result<AddonTransferOutcome> =
        withContext(Dispatchers.Default) {
            try {
                val entry = AddonTransferEntry(
                    manifestUrl = addon.manifestUrl,
                    name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                    enabled = true,
                )
                val merged = appendAddonEntry(currentEntries(profileIndex), entry)
                    ?: return@withContext Result.success(AddonTransferOutcome.AlreadyInstalled)
                if (isSignedIn()) {
                    pushToServer(profileIndex, merged)
                }
                // Signed in, this is what the profile shows until its next pull; signed out, it is the
                // whole transfer.
                AddonStorage.saveInstalledAddonUrls(profileIndex, merged.map { it.manifestUrl })
                AddonStorage.saveAddonEnabledStates(profileIndex, merged.associate { it.manifestUrl to it.enabled })
                log.d { "sendAddon() — sent ${addon.manifestUrl} to profile $profileIndex (${merged.size} addons)" }
                Result.success(AddonTransferOutcome.Added)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "sendAddon() — FAILED for profile $profileIndex" }
                Result.failure(error)
            }
        }

    private suspend fun currentEntries(profileIndex: Int): List<AddonTransferEntry> {
        if (!isSignedIn()) return localEntries(profileIndex)
        // A profile that never synced has no rows yet: its list is still this device's copy. Pushing only
        // the new addon would leave it with just that one.
        return remoteEntries(profileIndex).ifEmpty { localEntries(profileIndex) }
    }

    private suspend fun remoteEntries(profileIndex: Int): List<AddonTransferEntry> =
        SupabaseProvider.client.postgrest
            .from("addons")
            .select {
                filter { eq("profile_id", profileIndex) }
                order("sort_order", Order.ASCENDING)
            }
            .decodeList<AddonTransferRow>()
            .map { row ->
                AddonTransferEntry(
                    manifestUrl = ensureManifestSuffix(row.url),
                    name = row.name.orEmpty(),
                    enabled = row.enabled,
                )
            }
            .distinctBy { it.manifestUrl }

    private fun localEntries(profileIndex: Int): List<AddonTransferEntry> {
        val enabledByUrl = AddonStorage.loadAddonEnabledStates(profileIndex)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }
        return AddonStorage.loadInstalledAddonUrls(profileIndex)
            .map(::ensureManifestSuffix)
            .distinct()
            .map { url -> AddonTransferEntry(manifestUrl = url, name = "", enabled = enabledByUrl[url] ?: true) }
    }

    private suspend fun pushToServer(profileIndex: Int, entries: List<AddonTransferEntry>) {
        val items = entries.mapIndexed { index, entry ->
            AddonTransferPushItem(
                url = entry.manifestUrl,
                name = entry.name,
                enabled = entry.enabled,
                sortOrder = index,
            )
        }
        val params = buildJsonObject {
            put("p_profile_id", profileIndex)
            put("p_addons", json.encodeToJsonElement(items))
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
    }

    private fun isSignedIn(): Boolean = AuthRepository.state.value is AuthState.Authenticated
}

private val NuvioProfile.mirrorsPrimaryAddons: Boolean
    get() = profileIndex != 1 && usesPrimaryAddons

/** [entries] with [entry] appended, or null when the list already has that addon. */
internal fun appendAddonEntry(
    entries: List<AddonTransferEntry>,
    entry: AddonTransferEntry,
): List<AddonTransferEntry>? {
    if (entries.any { sameAddonUrl(it.manifestUrl, entry.manifestUrl) }) return null
    return entries + entry.copy(manifestUrl = ensureManifestSuffix(entry.manifestUrl))
}

/**
 * The apps store the same addon in different forms: the TV without /manifest.json, the desktop with
 * characters like `|` percent-encoded (configured addons carry them in the URL).
 */
internal fun sameAddonUrl(a: String, b: String): Boolean =
    addonIdentity(a) == addonIdentity(b)

private val ENCODED_URL_CHARACTERS = Regex("%(20|22|3C|3E|5C|5E|60|7B|7C|7D)", RegexOption.IGNORE_CASE)

private fun addonIdentity(url: String): String {
    val decoded = ENCODED_URL_CHARACTERS.replace(url.trim().lowercase()) { match ->
        match.groupValues[1].toInt(16).toChar().toString()
    }
    val path = decoded.substringBefore("?").trimEnd('/').removeSuffix("/manifest.json").trimEnd('/')
    val query = decoded.substringAfter("?", "")
    return if (query.isEmpty()) path else "$path?$query"
}
