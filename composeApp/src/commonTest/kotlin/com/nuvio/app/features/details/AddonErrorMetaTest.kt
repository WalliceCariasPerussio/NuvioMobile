package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonErrorMetaTest {

    @Test
    fun rateLimitPlaceholderIsAnAddonError() {
        assertTrue("⚠️ Rate limit exceeded — please wait a few minutes before trying again.".looksLikeAddonError())
        assertTrue("429 Too Many Requests".looksLikeAddonError())
        assertTrue("Ratelimit hit".looksLikeAddonError())
    }

    @Test
    fun realTitlesAreKept() {
        assertFalse("Hell Mode: O Gamer Hardcore Domina Outro Mundo com um Balanceamento Lixo".looksLikeAddonError())
        assertFalse("Corrida Contra o Tempo".looksLikeAddonError())
        assertFalse("The Limit".looksLikeAddonError())
        assertFalse("Rate My Plate".looksLikeAddonError())
    }
}
