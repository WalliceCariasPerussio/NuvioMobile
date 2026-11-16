package com.nuvio.app.features.ai

import com.nuvio.app.features.watched.WatchedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal enum class AiTitleType(val wireName: String) {
    Movie("movie"),
    Series("series"),
}

internal data class AiSuggestedTitle(
    val title: String,
    val year: Int?,
    val type: AiTitleType,
)

/** Ação que o modelo propõe. O app só executa depois que a pessoa aprova no cartão. */
internal data class AiSuggestedAction(
    val kind: AiAssistantActionKind,
    val target: AiSuggestedTitle,
)

internal data class AiRecommendationReply(
    val reply: String,
    val titles: List<AiSuggestedTitle>,
    val actions: List<AiSuggestedAction> = emptyList(),
)

internal val AiAssistantActionKind.wireName: String
    get() = when (this) {
        AiAssistantActionKind.MarkWatched -> "mark_watched"
        AiAssistantActionKind.AddToLibrary -> "add_to_library"
    }

internal object AiRecommendationPrompt {
    const val MAX_TITLES = 15
    const val MAX_ACTIONS = 20
    private const val MAX_WATCHED_IN_CONTEXT = 150

    val systemPrompt: String = """
        Você é o assistente do Nuvio, um app para assistir filmes, séries e animes. A pessoa pede
        recomendações ou pede para você mexer na conta dela, e você responde em português do Brasil.

        Recomendações ("titles"), na ordem em que você recomenda:
        - Só títulos que existem de verdade. Escreva o título original ou o mais conhecido
          internacionalmente (como aparece no TMDB), não uma tradução livre.
        - "year" é o ano de lançamento; para série, o ano da primeira temporada.
        - "type" é "movie" para filme (inclusive filme de anime) e "series" para série ou anime em
          episódios.
        - Entre 6 e $MAX_TITLES títulos (nunca mais de $MAX_TITLES), a não ser que a pessoa peça menos.
        - "+18", "mais 18", "maiores de 18" e "adulto" falam de classificação etária (conteúdo
          adulto), não de quantidade.
        - Evite o que a pessoa já assistiu (lista no contexto), a não ser que ela peça.
        - Não repita títulos que você já sugeriu nesta conversa, a não ser que a pessoa peça.
        - Pedidos como "só filmes" ou "mais curtos" refinam a lista anterior da conversa.
        - "Um deles", "desses" ou "da lista" se referem à última lista que você mandou.

        Ações ("actions"): você pode propor duas ações, que o app só faz depois que a pessoa clicar
        em "Aprovar":
        - "mark_watched": marcar um filme como assistido, ou uma série inteira (todos os episódios
          já lançados).
        - "add_to_library": adicionar um filme ou uma série à biblioteca da pessoa.
        Regras das ações:
        - Só proponha uma ação quando a pessoa pedir ("marca como visto", "já vi esse", "salva na
          biblioteca", "adiciona na minha lista"). Recomendar não é ação.
        - "O 1, 2 e 5" ou "os três primeiros" são posições na última lista de títulos da conversa,
          contando a partir de 1.
        - Uma ação por título, com "title", "year" e "type" como nas recomendações.
        - Marcar só uma temporada ou só alguns episódios você não consegue: diga que isso se faz na
          página do título e devolva "actions" vazia.
        - Se não der para saber qual título é, pergunte em "reply" e devolva "actions" vazia.
        - Com ações, "reply" lista o que você entendeu, uma linha por ação começando com "• ", e
          termina pedindo para clicar em "Aprovar". Nunca diga que já fez: quem faz é o app, depois
          da aprovação.
        - No histórico, cada ação que você propôs traz "status". Não diga que algo foi feito se o
          status não for "feita" ou "já estava feita".
        - Se a pessoa responder "sim", "pode" ou parecido a ações aguardando aprovação, devolva as
          mesmas ações de novo e lembre que é preciso clicar em "Aprovar".
        - Com ações, "titles" fica vazia, a não ser que a pessoa também peça recomendações.

        Geral:
        - Sem ações, "reply" tem no máximo duas frases e não repete os nomes da lista.
        - Se o pedido não for sobre o que assistir nem uma das ações, responda em uma frase e
          devolva as listas vazias.

        Responda só com um objeto JSON neste formato:
        {"reply": "texto", "titles": [{"title": "nome", "year": 2010, "type": "series"}],
         "actions": [{"action": "mark_watched", "title": "nome", "year": 2010, "type": "series"}]}
    """.trimIndent()

    val responseSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reply") { put("type", "string") }
            putJsonObject("titles") {
                put("type", "array")
                put("items", titleSchema(actionField = false))
            }
            putJsonObject("actions") {
                put("type", "array")
                put("items", titleSchema(actionField = true))
            }
        }
        putJsonArray("required") {
            add("reply")
            add("titles")
            add("actions")
        }
        put("additionalProperties", false)
    }

    private fun titleSchema(actionField: Boolean): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            if (actionField) {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        AiAssistantActionKind.entries.forEach { add(it.wireName) }
                    }
                }
            }
            putJsonObject("title") { put("type", "string") }
            putJsonObject("year") { put("type", "integer") }
            putJsonObject("type") {
                put("type", "string")
                putJsonArray("enum") {
                    add(AiTitleType.Movie.wireName)
                    add(AiTitleType.Series.wireName)
                }
            }
        }
        putJsonArray("required") {
            if (actionField) add("action")
            add("title")
            add("year")
            add("type")
        }
        put("additionalProperties", false)
    }

    /** O contexto (o que já foi assistido) vai junto da última mensagem, não na instrução fixa. */
    fun buildUserTurn(request: String, watched: List<WatchedItem>): String {
        val names = watchedContextLines(watched)
        if (names.isEmpty()) return request.trim()
        return buildString {
            append(request.trim())
            append("\n\n<contexto>\nJá assistidos neste perfil (mais recentes primeiro):\n")
            names.forEach { append("- ").append(it).append('\n') }
            append("</contexto>")
        }
    }

    /**
     * Uma linha por título. Episódio marcado guarda o nome do episódio ("Renascimento"), não o da
     * série: agrupar pelo nome deixava uma série de 148 episódios ocupar quase todas as linhas.
     */
    internal fun watchedContextLines(watched: List<WatchedItem>): List<String> {
        val seenNames = mutableSetOf<String>()
        return watched
            .filter { it.id.isNotBlank() }
            .groupBy { item -> item.id to item.isSeriesLike() }
            .mapNotNull { (key, items) ->
                val name = contextName(items) ?: return@mapNotNull null
                val year = items.firstNotNullOfOrNull { item ->
                    item.releaseInfo?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                }
                WatchedContextEntry(
                    name = name,
                    year = year,
                    isSeries = key.second,
                    lastMarkedAt = items.maxOf { it.markedAtEpochMs },
                )
            }
            .sortedByDescending { it.lastMarkedAt }
            // O mesmo título pode vir com dois ids (tmdb: e tt): fica a primeira linha.
            .filter { entry -> seenNames.add("${entry.name.lowercase()}|${entry.isSeries}") }
            .take(MAX_WATCHED_IN_CONTEXT)
            .map { entry ->
                buildString {
                    append(entry.name)
                    if (entry.year != null) append(" (").append(entry.year).append(')')
                    append(if (entry.isSeries) " [série]" else " [filme]")
                }
            }
    }

    private data class WatchedContextEntry(
        val name: String,
        val year: String?,
        val isSeries: Boolean,
        val lastMarkedAt: Long,
    )

    private fun WatchedItem.isSeriesLike(): Boolean =
        season != null || type.lowercase() in setOf("series", "show", "tv", "anime")

    /** Nome do item da série (sem temporada); sem ele, o nome que mais se repete nos episódios. */
    private fun contextName(items: List<WatchedItem>): String? {
        items.firstOrNull { it.season == null && it.episode == null }
            ?.name?.trim()?.takeIf(String::isNotEmpty)
            ?.let { return it }
        return items
            .mapNotNull { it.name.trim().takeIf(String::isNotEmpty) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Lê a resposta do modelo; aceita JSON dentro de ``` e texto antes ou depois do objeto. */
    fun parseReply(text: String): AiRecommendationReply? {
        val root = parseObject(text) ?: return null
        val reply = (root["reply"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val list = (root["titles"] ?: root["items"] ?: root["results"]) as? JsonArray
        val titles = list.orEmpty().mapNotNull { element -> (element as? JsonObject)?.toSuggestedTitle() }
        // OpenAI e Gemini não recebem o schema: "actions" pode faltar ou vir em outro formato.
        val actions = (root["actions"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val kind = obj.actionKind() ?: return@mapNotNull null
            val target = obj.toSuggestedTitle() ?: return@mapNotNull null
            AiSuggestedAction(kind = kind, target = target)
        }.take(MAX_ACTIONS)
        if (reply.isEmpty() && titles.isEmpty() && actions.isEmpty()) return null
        return AiRecommendationReply(reply = reply, titles = titles, actions = actions)
    }

    private fun parseObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { lenientJson.parseToJsonElement(text.substring(start, end + 1)) }
            .getOrNull() as? JsonObject
    }

    private fun JsonObject.toSuggestedTitle(): AiSuggestedTitle? {
        val title = (this["title"] ?: this["name"])
            .let { it as? JsonPrimitive }?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
        val yearPrimitive = this["year"] as? JsonPrimitive
        val year = yearPrimitive?.intOrNull
            ?: yearPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
        val type = when ((this["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim()) {
            "series", "tv", "show", "anime", "serie", "série" -> AiTitleType.Series
            else -> AiTitleType.Movie
        }
        return AiSuggestedTitle(title = title, year = year?.takeIf { it in 1870..2100 }, type = type)
    }

    private fun JsonObject.actionKind(): AiAssistantActionKind? {
        val raw = ((this["action"] ?: this["tool"]) as? JsonPrimitive)?.contentOrNull
            ?.lowercase()?.trim()?.replace('-', '_')?.replace(' ', '_')
            ?: return null
        return when (raw) {
            "mark_watched", "watched", "mark_as_watched" -> AiAssistantActionKind.MarkWatched
            "add_to_library", "library", "save", "add_library" -> AiAssistantActionKind.AddToLibrary
            else -> null
        }
    }

    /**
     * Versão compacta da resposta, guardada no histórico para as próximas mensagens. `titles` deve
     * ser só o que apareceu na tela, na mesma ordem: "o 1, 2 e 5" conta pelas posições exibidas.
     */
    fun encodeForHistory(reply: AiRecommendationReply): String = buildJsonObject {
        put("reply", reply.reply)
        put("titles", buildJsonArray {
            reply.titles.forEach { title -> add(title.toJson()) }
        })
        put("actions", buildJsonArray {
            reply.actions.forEach { action ->
                add(buildJsonObject {
                    put("action", action.kind.wireName)
                    action.target.toJson().forEach { (key, value) -> put(key, value) }
                })
            }
        })
    }.toString()

    /** Põe em cada ação do histórico a situação dela (aguardando, feita, cancelada...). */
    fun withActionStatuses(historyText: String, statuses: List<String>): String {
        if (statuses.isEmpty()) return historyText
        val root = parseObject(historyText) ?: return historyText
        val actions = root["actions"] as? JsonArray ?: return historyText
        val updated = buildJsonArray {
            actions.forEachIndexed { index, element ->
                val obj = element as? JsonObject
                val status = statuses.getOrNull(index)
                if (obj == null || status == null) {
                    add(element)
                } else {
                    add(JsonObject(obj + ("status" to JsonPrimitive(status))))
                }
            }
        }
        return JsonObject(root + ("actions" to updated as JsonElement)).toString()
    }

    private fun AiSuggestedTitle.toJson(): JsonObject = buildJsonObject {
        put("title", title)
        year?.let { put("year", it) }
        put("type", type.wireName)
    }
}
