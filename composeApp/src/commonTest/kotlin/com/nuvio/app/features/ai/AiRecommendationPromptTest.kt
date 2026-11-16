package com.nuvio.app.features.ai

import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiRecommendationPromptTest {
    @Test
    fun `parses reply with fences and text around the object`() {
        val text = """
            Claro!
            ```json
            {"reply": "Separei clássicos.", "titles": [
              {"title": "Another", "year": 2012, "type": "series"},
              {"title": "Perfect Blue", "year": "1997", "type": "movie"},
              {"title": "Shiki", "type": "anime"}
            ]}
            ```
        """.trimIndent()

        val reply = AiRecommendationPrompt.parseReply(text)!!

        assertEquals("Separei clássicos.", reply.reply)
        assertEquals(
            listOf(
                AiSuggestedTitle("Another", 2012, AiTitleType.Series),
                AiSuggestedTitle("Perfect Blue", 1997, AiTitleType.Movie),
                AiSuggestedTitle("Shiki", null, AiTitleType.Series),
            ),
            reply.titles,
        )
    }

    @Test
    fun `accepts items as list name and skips entries without title`() {
        val reply = AiRecommendationPrompt.parseReply(
            """{"reply":"","items":[{"name":"Dune","year":2021,"type":"movie"},{"year":2020},{"title":"  "}]}""",
        )!!

        assertEquals(listOf(AiSuggestedTitle("Dune", 2021, AiTitleType.Movie)), reply.titles)
    }

    @Test
    fun `returns null for text without json or with empty content`() {
        assertNull(AiRecommendationPrompt.parseReply("Não entendi."))
        assertNull(AiRecommendationPrompt.parseReply("""{"reply":"","titles":[]}"""))
        assertNull(AiRecommendationPrompt.parseReply("{quebrado"))
    }

    @Test
    fun `history encoding round trips`() {
        val reply = AiRecommendationReply(
            reply = "Dois filmes.",
            titles = listOf(
                AiSuggestedTitle("Alien", 1979, AiTitleType.Movie),
                AiSuggestedTitle("Severance", null, AiTitleType.Series),
            ),
        )

        assertEquals(reply, AiRecommendationPrompt.parseReply(AiRecommendationPrompt.encodeForHistory(reply)))
    }

    @Test
    fun `watched context is newest first, deduplicated and typed`() {
        val watched = listOf(
            WatchedItem(id = "tt1", type = "movie", name = "Alien", releaseInfo = "1979", markedAtEpochMs = 100),
            WatchedItem(id = "tt2", type = "series", name = "Dark", season = 1, episode = 1, markedAtEpochMs = 300),
            WatchedItem(id = "tt2", type = "series", name = "Dark", season = 1, episode = 2, markedAtEpochMs = 400),
            WatchedItem(id = "tt3", type = "movie", name = " ", markedAtEpochMs = 500),
        )

        assertEquals(
            listOf("Dark [série]", "Alien (1979) [filme]"),
            AiRecommendationPrompt.watchedContextLines(watched),
        )
    }

    @Test
    fun `watched context uses the series name, not episode titles`() {
        // Marcar a série inteira grava cada episódio com o nome dele, mais um item da série.
        val episodes = (1..148).map { number ->
            WatchedItem(
                id = "tt2098220",
                type = "series",
                name = "Episódio $number",
                season = 1,
                episode = number,
                markedAtEpochMs = 1_000L + number,
            )
        }
        val watched = episodes + listOf(
            WatchedItem(id = "tt2098220", type = "series", name = "Hunter x Hunter", releaseInfo = "2011", markedAtEpochMs = 1_000),
            WatchedItem(id = "tt1", type = "movie", name = "Alien", markedAtEpochMs = 10),
            // Só episódios, sem o item da série: vale o nome que se repete.
            WatchedItem(id = "tt9", type = "series", name = "Dark", season = 1, episode = 1, markedAtEpochMs = 20),
            WatchedItem(id = "tt9", type = "series", name = "Dark", season = 1, episode = 2, markedAtEpochMs = 21),
            WatchedItem(id = "tt9", type = "series", name = "Secrets", season = 1, episode = 3, markedAtEpochMs = 22),
        )

        assertEquals(
            listOf("Hunter x Hunter (2011) [série]", "Dark [série]", "Alien [filme]"),
            AiRecommendationPrompt.watchedContextLines(watched),
        )
    }

    @Test
    fun `episodes with distinct titles use the known name or stay out`() {
        val episodes = listOf(
            WatchedItem(id = "tt5", type = "series", name = "Pilot", season = 1, episode = 1, markedAtEpochMs = 10),
            WatchedItem(id = "tt5", type = "series", name = "Cat's in the Bag", season = 1, episode = 2, markedAtEpochMs = 11),
        )
        val single = WatchedItem(id = "tt6", type = "series", name = "Severance", season = 1, episode = 1, markedAtEpochMs = 5)

        assertEquals(
            listOf("Breaking Bad [série]", "Severance [série]"),
            AiRecommendationPrompt.watchedContextLines(episodes + single, knownNames = mapOf("tt5" to "Breaking Bad")),
        )
        assertEquals(listOf("Severance [série]"), AiRecommendationPrompt.watchedContextLines(episodes + single))
    }

    @Test
    fun `watched context keeps one line when the same title has two ids`() {
        val watched = listOf(
            WatchedItem(id = "tmdb:1429", type = "series", name = "Attack on Titan", markedAtEpochMs = 200),
            WatchedItem(id = "tt2560140", type = "series", name = "Attack on Titan", markedAtEpochMs = 100),
        )

        assertEquals(listOf("Attack on Titan [série]"), AiRecommendationPrompt.watchedContextLines(watched))
    }

    @Test
    fun `parses actions and accepts a reply with only actions`() {
        val reply = AiRecommendationPrompt.parseReply(
            """
            {"reply": "• Marcar Death Note como assistido\nClique em Aprovar.", "titles": [],
             "actions": [
               {"action": "mark_watched", "title": "Death Note", "year": 2006, "type": "series"},
               {"action": "add-to-library", "title": "Perfect Blue", "year": 1997, "type": "movie"},
               {"action": "delete_everything", "title": "Dark", "year": 2017, "type": "series"},
               {"action": "mark_watched", "year": 2020}
             ]}
            """.trimIndent(),
        )!!

        assertTrue(reply.titles.isEmpty())
        assertEquals(
            listOf(
                AiSuggestedAction(AiAssistantActionKind.MarkWatched, AiSuggestedTitle("Death Note", 2006, AiTitleType.Series)),
                AiSuggestedAction(AiAssistantActionKind.AddToLibrary, AiSuggestedTitle("Perfect Blue", 1997, AiTitleType.Movie)),
            ),
            reply.actions,
        )
    }

    @Test
    fun `reply without actions field still parses`() {
        // OpenAI e Gemini não recebem o schema e podem omitir "actions".
        val reply = AiRecommendationPrompt.parseReply("""{"reply":"Um filme.","titles":[{"title":"Alien","year":1979,"type":"movie"}]}""")!!

        assertTrue(reply.actions.isEmpty())
        assertEquals(1, reply.titles.size)
    }

    @Test
    fun `history carries actions and their statuses`() {
        val reply = AiRecommendationReply(
            reply = "Confira.",
            titles = emptyList(),
            actions = listOf(
                AiSuggestedAction(AiAssistantActionKind.MarkWatched, AiSuggestedTitle("Death Note", 2006, AiTitleType.Series)),
                AiSuggestedAction(AiAssistantActionKind.AddToLibrary, AiSuggestedTitle("Alien", 1979, AiTitleType.Movie)),
            ),
        )
        val encoded = AiRecommendationPrompt.encodeForHistory(reply)
        assertEquals(reply, AiRecommendationPrompt.parseReply(encoded))

        val withStatus = AiRecommendationPrompt.withActionStatuses(encoded, listOf("feita", "cancelada pela pessoa"))
        assertTrue("\"status\":\"feita\"" in withStatus)
        assertTrue("\"status\":\"cancelada pela pessoa\"" in withStatus)
        assertEquals(reply, AiRecommendationPrompt.parseReply(withStatus))
        assertEquals(encoded, AiRecommendationPrompt.withActionStatuses(encoded, emptyList()))
    }

    @Test
    fun `schema requires actions with closed objects`() {
        val schema = AiRecommendationPrompt.responseSchema.toString()
        assertTrue("\"required\":[\"reply\",\"titles\",\"actions\"]" in schema)
        assertTrue("\"enum\":[\"mark_watched\",\"add_to_library\"]" in schema)
        assertTrue("\"required\":[\"action\",\"title\",\"year\",\"type\"]" in schema)
    }

    @Test
    fun `user turn carries the context only when there is history`() {
        assertEquals("animes de terror", AiRecommendationPrompt.buildUserTurn(" animes de terror ", emptyList()))

        val turn = AiRecommendationPrompt.buildUserTurn(
            "animes de terror",
            listOf(WatchedItem(id = "tt1", type = "movie", name = "Alien", markedAtEpochMs = 1)),
        )
        assertTrue(turn.startsWith("animes de terror\n\n<contexto>"))
        assertTrue("- Alien [filme]" in turn)
        assertTrue(turn.endsWith("</contexto>"))
    }

    @Test
    fun `display name falls back to the suggested title when tmdb has no latin name`() {
        assertEquals("Shiki", aiDisplayName(tmdbName = "屍鬼", suggestedName = "Shiki"))
        assertEquals("Parasita", aiDisplayName(tmdbName = "Parasita", suggestedName = "Parasyte"))
        assertEquals("Amélie", aiDisplayName(tmdbName = "Amélie", suggestedName = "Amelie"))
        assertEquals("屍鬼", aiDisplayName(tmdbName = "屍鬼", suggestedName = "屍鬼"))
    }

    private data class FakeResult(val name: String, val original: String, val votes: Int)

    private fun pick(results: List<FakeResult>, query: String) =
        pickBestTmdbMatch(results, query, { listOf(it.name, it.original) }, { it.votes })

    @Test
    fun `tmdb match prefers the main series over a translated spin-off`() {
        // Ordem real do TMDB para "Overlord" (2015) com language=pt.
        val results = listOf(
            FakeResult("Overlord: Play Play Pleiades", "Overlord: Play Play Pleiades", 0),
            FakeResult("オーバーロード", "オーバーロード", 1143),
        )

        assertEquals("オーバーロード", pick(results, "Overlord")?.original)
    }

    @Test
    fun `tmdb match keeps an exact name even with fewer votes`() {
        val results = listOf(
            FakeResult("Popular Show", "Popular Show", 9000),
            FakeResult("Another", "Another", 800),
        )

        assertEquals("Another", pick(results, "another")?.name)
        assertNull(pick(emptyList(), "x"))
    }

    @Test
    fun `system prompt asks for json`() {
        // OpenAI exige a palavra JSON nas mensagens quando response_format é json_object.
        assertTrue("JSON" in AiRecommendationPrompt.systemPrompt)
    }
}
