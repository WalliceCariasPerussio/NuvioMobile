package com.nuvio.app.features.updater

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class ReleaseSelectorTest {
    @Test
    fun `stable channel excludes GitHub and tag prereleases`() {
        val releases = listOf(
            release("1.1.0-beta.2", prerelease = true),
            release("1.1.0-beta.1"),
            release("1.0.1"),
            release("1.0.0")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE)

        assertEquals(listOf("1.0.1", "1.0.0"), selected.map { it.tagName })
    }

    @Test
    fun `beta channel prefers final promotion over its betas`() {
        val releases = listOf(
            release("1.1.0-beta.10", prerelease = true),
            release("1.1.0"),
            release("1.1.0-beta.9", prerelease = true)
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.BETA)

        assertEquals(
            listOf("1.1.0", "1.1.0-beta.10", "1.1.0-beta.9"),
            selected.map { it.tagName }
        )
    }

    @Test
    fun `legacy release title marks beta without tag suffix`() {
        val releases = listOf(
            release(tag = "0.7.18", name = "Beta 0.7.18 Hotfix"),
            release(tag = "0.7.17")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE)

        assertEquals(listOf("0.7.17"), selected.map { it.tagName })
    }

    @Test
    fun `drafts and invalid tags are excluded`() {
        val releases = listOf(
            release("1.2.0", draft = true),
            release("nightly"),
            release("1.1.0")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.BETA)

        assertEquals(listOf("1.1.0"), selected.map { it.tagName })
    }

    @Test
    fun `stable channel accepts fork builds whatever upstream suffix they carry`() {
        val releases = listOf(
            release("v0.6.3-fork.2896", name = "Nuvio 0.6.3-fork.2896"),
            release("v0.6.4-fork.2900.beta.1", prerelease = true),
            release("v0.6.3-fork.2897.rc.1"),
            release("ios-0.6.3-146001"),
            release("0.6.2")
        )

        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE)

        assertEquals(
            listOf("v0.6.3-fork.2897.rc.1", "v0.6.3-fork.2896", "0.6.2"),
            selected.map { it.tagName }
        )
    }

    @Test
    fun `fork builds order by commit count before the upstream suffix`() {
        assertTrue(VersionUtils.isRemoteNewer("0.6.3-fork.2897", "0.6.3-fork.2896.rc.1"))
        assertTrue(VersionUtils.isRemoteNewer("0.6.4-fork.2897.beta.9", "0.6.3-fork.2896"))
    }

    private fun release(
        tag: String,
        name: String = tag,
        draft: Boolean = false,
        prerelease: Boolean = false
    ) = GitHubReleaseDto(
        tagName = tag,
        name = name,
        draft = draft,
        prerelease = prerelease
    )
}
