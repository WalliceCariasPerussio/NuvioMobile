package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonProfileTransferTest {
    private val cinemeta = AddonTransferEntry("https://v3-cinemeta.strem.io/manifest.json", name = "Cinemeta", enabled = true)
    private val subtitles = AddonTransferEntry("https://opensubtitles-v3.strem.io/manifest.json", name = "", enabled = false)

    @Test
    fun appendsAtTheEndKeepingTheTargetListAsItIs() {
        val torrentio = AddonTransferEntry("https://torrentio.strem.fun/abc/manifest.json", name = "Torrentio", enabled = true)

        assertEquals(listOf(cinemeta, subtitles, torrentio), appendAddonEntry(listOf(cinemeta, subtitles), torrentio))
    }

    @Test
    fun anAddonSavedByTheTvWithoutManifestSuffixIsAlreadyInstalled() {
        val fromTv = AddonTransferEntry("https://V3-Cinemeta.strem.io", name = "", enabled = true)

        assertNull(appendAddonEntry(listOf(fromTv), cinemeta))
    }

    @Test
    fun percentEncodedConfigIsTheSameAddon() {
        assertTrue(
            sameAddonUrl(
                "https://torrentio.strem.fun/providers=yts|qualityfilter=480p",
                "https://torrentio.strem.fun/providers=yts%7Cqualityfilter=480p/manifest.json",
            ),
        )
    }

    @Test
    fun differentConfigsAreDifferentAddons() {
        val yts = AddonTransferEntry("https://torrentio.strem.fun/providers=yts/manifest.json", name = "", enabled = true)
        val eztv = AddonTransferEntry("https://torrentio.strem.fun/providers=eztv/manifest.json", name = "", enabled = true)

        assertEquals(listOf(yts, eztv), appendAddonEntry(listOf(yts), eztv))
    }
}
