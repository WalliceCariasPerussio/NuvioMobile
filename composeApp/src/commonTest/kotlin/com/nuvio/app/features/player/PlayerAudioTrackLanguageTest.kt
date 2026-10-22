package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerAudioTrackLanguageTest {

    // A dual audio release that tags both tracks with the original language.
    private val japanese = AudioTrack(0, "1", "JPN FLAC 2.0 (Stereo)", "jpn")
    private val dubbed = AudioTrack(1, "2", "PT-BR E-AC-3 2.0 (Stereo)", "jpn")

    @Test
    fun titleLanguageWinsOverTheTag() {
        val tracks = listOf(japanese, dubbed)

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt-br")))
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt-br", "ja")))
        assertEquals(0, findPreferredAudioTrackIndex(tracks, listOf("ja")))
        assertEquals(-1, findPreferredAudioTrackIndex(tracks, listOf("en")))
    }

    @Test
    fun titleLanguageWinsInEitherOrder() {
        val tracks = listOf(dubbed.copy(index = 0, id = "1"), japanese.copy(index = 1, id = "2"))

        assertEquals(0, findPreferredAudioTrackIndex(tracks, listOf("pt-br")))
        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("ja")))
    }

    @Test
    fun sameVariantComesBeforeTheBaseLanguage() {
        val tracks = listOf(
            AudioTrack(0, "1", "Português (Portugal)", "por"),
            AudioTrack(1, "2", "Português (Brasil)", "por"),
        )

        assertEquals(1, findPreferredAudioTrackIndex(tracks, listOf("pt-br")))
        assertEquals(0, findPreferredAudioTrackIndex(tracks, listOf("pt")))
    }

    @Test
    fun savedDubIsFoundWhenTheNextEpisodeRenamesItsTracks() {
        val preference = PersistedPlayerTrackPreference(
            audioLanguage = "jpn",
            audioName = "PT-BR E-AC-3 2.0 (Stereo)",
            audioTrackId = "2",
        )
        val nextEpisode = listOf(
            AudioTrack(0, "1", "PT-BR AAC 2.0 (Stereo)", "jpn"),
            AudioTrack(1, "2", "JPN AAC 2.0 (Stereo)", "jpn"),
        )

        assertEquals(0, findPersistedAudioTrackIndex(nextEpisode, preference))
    }

    @Test
    fun languagePickedForAnotherSourceFindsTheTitledTrack() {
        // What the audio panel saves before switching to another source in that language.
        val preference = PersistedPlayerTrackPreference(audioLanguage = "pt-br")

        assertEquals(1, findPersistedAudioTrackIndex(listOf(japanese, dubbed), preference))
    }

    @Test
    fun titleNamesALanguage() {
        assertEquals("pt-br", audioTitleLanguage("PT-BR E-AC-3 2.0 (Stereo)"))
        assertEquals("ja", audioTitleLanguage("JPN FLAC 2.0 (Stereo)"))
        assertEquals("pt-br", audioTitleLanguage("Português (Brasil)"))
        assertEquals("pt-br", audioTitleLanguage("Português Brasileiro"))
        assertEquals("pt-br", audioTitleLanguage("Dublado"))
        assertEquals("pt", audioTitleLanguage("Português"))
        assertEquals("ja", audioTitleLanguage("Japonês"))
        assertEquals("ja", audioTitleLanguage("Japones 2.0"))
        assertEquals("ja", audioTitleLanguage("Japanese"))
        assertEquals("en", audioTitleLanguage("ENG DTS-HD MA 5.1"))
        assertEquals("es-419", audioTitleLanguage("Español Latino"))
        // The accent as a separate combining mark.
        assertEquals("pt-br", audioTitleLanguage("Português (Brasil)"))
    }

    @Test
    fun titleWithoutOneLanguageNamesNone() {
        assertNull(audioTitleLanguage(null))
        assertNull(audioTitleLanguage("Stereo"))
        assertNull(audioTitleLanguage("Track 1 (AAC)"))
        assertNull(audioTitleLanguage("Japanese / English"))
        // Codecs holding a language code, and short plain words.
        assertNull(audioTitleLanguage("DTS-ES 6.1"))
        assertNull(audioTitleLanguage("HE-AAC 2.0"))
        assertNull(audioTitleLanguage("AAC-HE 2.0"))
        assertNull(audioTitleLanguage("DTS-HD HR 5.1"))
        assertNull(audioTitleLanguage("Comentário do diretor"))
        assertNull(audioTitleLanguage("jpn (Stereo, AAC)"))
    }

    @Test
    fun titleOverridesTheTagOnlyWhenItSaysSomethingElse() {
        assertEquals(true, dubbed.titleOverridesTag())
        assertEquals(true, AudioTrack(0, "1", "Português (Brasil)", "por").titleOverridesTag())
        assertEquals(true, AudioTrack(0, "1", "English", null).titleOverridesTag())
        assertEquals(false, japanese.titleOverridesTag())
        assertEquals(false, AudioTrack(0, "1", "English 5.1", "eng").titleOverridesTag())
        assertEquals(false, AudioTrack(0, "1", "Stereo", "eng").titleOverridesTag())
    }

    @Test
    fun codecWithALanguageCodeKeepsTheTag() {
        val tracks = listOf(AudioTrack(0, "1", "English DTS-ES 6.1", "eng"))

        assertEquals(-1, findPreferredAudioTrackIndex(tracks, listOf("es")))
        assertEquals(0, findPreferredAudioTrackIndex(tracks, listOf("en")))
    }
}
