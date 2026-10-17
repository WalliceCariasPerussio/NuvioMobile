package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.sync.ProgressSyncRecord

internal data class PendingProgressPushPlan(
    /** Local progress key to the row to send, already carrying the server's key for that content. */
    val toPush: Map<String, WatchProgressEntry>,
    val alreadySyncedKeys: Set<String>,
)

/**
 * Splits progress rows whose Nuvio Sync push was never acknowledged (keyed by local progress key)
 * into the ones the server still lacks (missing or older there) and the ones it already holds at the
 * same or a fresher state, where only the acknowledgement got lost. Pushing only the former keeps an
 * old local row from overwriting progress another device saved later.
 */
internal fun planPendingProgressPush(
    pendingEntries: Map<String, WatchProgressEntry>,
    serverRecords: Collection<ProgressSyncRecord>,
): PendingProgressPushPlan {
    val remoteByKey = linkedMapOf<String, ProgressSyncRecord>()
    serverRecords.forEach { record ->
        val key = record.resolvedProgressKey()
        val existing = remoteByKey[key]
        if (existing == null || record.lastWatched > existing.lastWatched) {
            remoteByKey[key] = record
        }
    }
    val reconciledEntries = reconcileLocalProgressKeysWithSnapshot(
        serverEntries = serverRecords,
        localEntries = pendingEntries.values.toList(),
    ).entries

    val toPush = linkedMapOf<String, WatchProgressEntry>()
    val alreadySyncedKeys = linkedSetOf<String>()
    pendingEntries.keys.zip(reconciledEntries).forEach { (localKey, reconciled) ->
        val remote = remoteByKey[reconciled.resolvedProgressKey()]
        val remoteState = remote?.let {
            reconciled.copy(
                videoId = remote.videoId,
                lastPositionMs = remote.position,
                durationMs = remote.duration,
                lastUpdatedEpochMs = remote.lastWatched,
            )
        }
        if (remoteState != null && !reconciled.isFresherThan(remoteState)) {
            alreadySyncedKeys += localKey
        } else {
            toPush[localKey] = reconciled
        }
    }
    return PendingProgressPushPlan(
        toPush = toPush,
        alreadySyncedKeys = alreadySyncedKeys,
    )
}
