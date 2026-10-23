package com.nuvio.app.features.trakt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedFetchTest {

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<List<Int>>>()

    @Test
    fun `a fetch cancelled mid-request does not block the next caller`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val owner = launch {
            sharedFetch(mutex, inFlight, KEY, fallback = emptyList()) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        owner.cancel()
        owner.join()

        val result = withTimeout(TIMEOUT_MS) {
            sharedFetch(mutex, inFlight, KEY, fallback = emptyList()) { listOf(1, 2) }
        }

        assertEquals(listOf(1, 2), result)
        assertTrue(inFlight.isEmpty())
    }

    @Test
    fun `a caller waiting on a cancelled fetch gets the fallback`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val owner = launch {
            sharedFetch(mutex, inFlight, KEY, fallback = emptyList()) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val waiter = async { sharedFetch(mutex, inFlight, KEY, fallback = listOf(-1)) { listOf(9) } }
        yield()
        owner.cancel()

        assertEquals(listOf(-1), withTimeout(TIMEOUT_MS) { waiter.await() })
        assertTrue(inFlight.isEmpty())
    }

    @Test
    fun `a failed fetch releases its waiters with the fallback`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val owner = async {
            sharedFetch(mutex, inFlight, KEY, fallback = listOf(-1)) {
                release.await()
                error("network")
            }
        }
        yield()
        val waiter = async { sharedFetch(mutex, inFlight, KEY, fallback = listOf(-2)) { listOf(9) } }
        yield()
        release.complete(Unit)

        assertEquals(listOf(-1), withTimeout(TIMEOUT_MS) { owner.await() })
        assertEquals(listOf(-2), withTimeout(TIMEOUT_MS) { waiter.await() })
        assertTrue(inFlight.isEmpty())
    }

    @Test
    fun `a cached value skips the fetch`() = runBlocking {
        val result = sharedFetch(mutex, inFlight, KEY, fallback = emptyList(), cached = { listOf(7) }) {
            error("must not fetch")
        }

        assertEquals(listOf(7), result)
    }

    private companion object {
        const val KEY = "tt0000001"
        const val TIMEOUT_MS = 5_000L
    }
}
