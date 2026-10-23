package com.nuvio.app.features.trakt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fork: runs one [fetch] per [key] at a time; concurrent callers wait for its result.
 *
 * The deferred always completes and the key always leaves [inFlight], even when the caller running
 * the fetch is cancelled mid-request or the fetch fails. TraktEpisodeMappingService used to leave
 * both behind, and every later caller for that show awaited forever, so the Trakt progress refresh
 * and Continue Watching stopped updating. A waiter whose fetch failed or was cancelled gets [fallback].
 */
internal suspend fun <T> sharedFetch(
    mutex: Mutex,
    inFlight: MutableMap<String, CompletableDeferred<T>>,
    key: String,
    fallback: T,
    cached: () -> T? = { null },
    fetch: suspend () -> T,
): T {
    val deferred = CompletableDeferred<T>()
    val running = mutex.withLock {
        cached()?.let { return it }
        inFlight[key].also { if (it == null) inFlight[key] = deferred }
    }
    if (running != null) {
        return try {
            running.await()
        } catch (_: CancellationException) {
            // The fetch was cancelled with its caller; this one goes on unless it was cancelled too.
            currentCoroutineContext().ensureActive()
            fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return try {
        fetch().also { deferred.complete(it) }
    } catch (e: CancellationException) {
        deferred.cancel(e)
        throw e
    } catch (e: Exception) {
        deferred.completeExceptionally(e)
        fallback
    } finally {
        if (!deferred.isCompleted) deferred.complete(fallback)
        withContext(NonCancellable) {
            mutex.withLock { if (inFlight[key] === deferred) inFlight.remove(key) }
        }
    }
}
