package com.nuvio.app.features.streams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Source groups that already answered recent searches, shared by the streams screen and the player
 * panels so a search done once is not repeated. Groups are saved one by one as they finish, so a
 * search cancelled halfway (opening the player cancels the streams screen's) still leaves what it
 * got, and the next search for the same media fetches only the missing groups.
 *
 * Entries expire because addon links may. The player retains the media it is playing and the next
 * episode until it closes.
 */
object StreamSearchCache {
    private val Ttl = 15.minutes
    private const val MaxEntries = 12

    private class Entry(
        val media: String,
        val groups: Map<String, AddonStreamGroup>,
        val savedAt: TimeMark,
    )

    private data class State(
        val entries: Map<String, Entry> = emptyMap(),
        val retained: Set<String> = emptySet(),
    )

    private val state = MutableStateFlow(State())

    fun media(type: String, videoId: String, season: Int?, episode: Int?): String =
        "$type::$videoId::$season::$episode"

    /** Finished groups of this search by addonId; empty when there is nothing fresh. */
    fun finishedGroups(media: String, pluginsGrouped: Boolean): Map<String, AddonStreamGroup> {
        val current = state.value
        val entry = current.entries[key(media, pluginsGrouped)] ?: return emptyMap()
        return if (entry.isFresh(current.retained)) entry.groups else emptyMap()
    }

    /** Keeps a group that answered; groups still loading or that failed are fetched again. */
    fun save(media: String, pluginsGrouped: Boolean, group: AddonStreamGroup) {
        if (group.isLoading || !group.error.isNullOrBlank()) return
        val key = key(media, pluginsGrouped)
        state.update { current ->
            val previous = current.entries[key]?.takeIf { it.isFresh(current.retained) }
            val entry = Entry(
                media = media,
                groups = previous?.groups.orEmpty() + (group.addonId to group),
                savedAt = previous?.savedAt ?: TimeSource.Monotonic.markNow(),
            )
            val kept = (current.entries - key).filterValues { it.isFresh(current.retained) }
            current.copy(entries = (kept + (key to entry)).trimmed(current.retained))
        }
    }

    /** Forgets this search, so the next one fetches every group (reload, failed playback). */
    fun evict(media: String, pluginsGrouped: Boolean) {
        state.update { current -> current.copy(entries = current.entries - key(media, pluginsGrouped)) }
    }

    /** Media whose searches never expire, replacing the previous set; empty releases them. */
    fun retain(medias: Set<String>) {
        state.update { current -> if (current.retained == medias) current else current.copy(retained = medias) }
    }

    private fun key(media: String, pluginsGrouped: Boolean) = "$media::pluginsGrouped=$pluginsGrouped"

    private fun Entry.isFresh(retained: Set<String>): Boolean = media in retained || savedAt.elapsedNow() < Ttl

    private fun Map<String, Entry>.trimmed(retained: Set<String>): Map<String, Entry> {
        var result = this
        while (result.size > MaxEntries) {
            val evictable = result.entries.firstOrNull { it.value.media !in retained } ?: break
            result = result - evictable.key
        }
        return result
    }
}
