package com.nuvio.app.features.ai

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Transforma os títulos sugeridos pelo modelo em cartazes do TMDB. Título que o TMDB não acha
 * (inventado ou com nome errado) fica de fora.
 */
internal object AiTitleResolver {
    private val log = Logger.withTag("AiTitleResolver")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(titles: List<AiSuggestedTitle>): List<MetaPreview> = coroutineScope {
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank)
            ?: return@coroutineScope emptyList()
        val language = TmdbSettingsRepository.snapshot().language.ifBlank { "en" }
        titles
            .take(AiRecommendationPrompt.MAX_TITLES)
            .map { title -> async { resolveOne(title, apiKey, language) } }
            .awaitAll()
            .filterNotNull()
            // Filme e série do TMDB podem ter o mesmo número: o tipo faz parte da identidade.
            .distinctBy { it.type to it.id }
    }

    private suspend fun resolveOne(title: AiSuggestedTitle, apiKey: String, language: String): MetaPreview? {
        val endpoint = if (title.type == AiTitleType.Series) "search/tv" else "search/movie"
        val yearParam = if (title.type == AiTitleType.Series) "first_air_date_year" else "primary_release_year"
        val baseQuery = mapOf(
            "query" to title.title.encodeURLParameter(),
            "language" to language,
            "include_adult" to "false",
        )
        val typed = title.year
            ?.let { year -> search(endpoint, apiKey, baseQuery + (yearParam to year.toString())) }
            ?.let { results -> pickBestTmdbMatch(results, title.title, TmdbSearchResult::names, TmdbSearchResult::votes) }
            ?: search(endpoint, apiKey, baseQuery)
                ?.let { results -> pickBestTmdbMatch(results, title.title, TmdbSearchResult::names, TmdbSearchResult::votes) }
        if (typed != null) return typed.toMetaPreview(title.type, suggestedName = title.title)

        // O modelo às vezes troca filme por série (comum em anime): tenta a busca geral.
        val multi = search("search/multi", apiKey, baseQuery)
            ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            ?.let { results ->
                val sameYear = results.filter { result -> title.year != null && result.year() == title.year }
                pickBestTmdbMatch(sameYear.ifEmpty { results }, title.title, TmdbSearchResult::names, TmdbSearchResult::votes)
            }
            ?: return null
        return multi.toMetaPreview(
            type = if (multi.mediaType == "tv") AiTitleType.Series else AiTitleType.Movie,
            suggestedName = title.title,
        )
    }

    private suspend fun search(
        endpoint: String,
        apiKey: String,
        query: Map<String, String>,
    ): List<TmdbSearchResult>? = try {
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        json.decodeFromString<TmdbSearchResponse>(httpGetText(url)).results.filter { it.id > 0 }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.w { "TMDB $endpoint falhou: ${error.message}" }
        null
    }
}

@Serializable
private data class TmdbSearchResponse(
    val results: List<TmdbSearchResult> = emptyList(),
)

@Serializable
private data class TmdbSearchResult(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
) {
    fun year(): Int? = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

    fun names(): List<String?> = listOf(title, name, originalTitle, originalName)

    fun votes(): Int = voteCount ?: 0

    fun toMetaPreview(type: AiTitleType, suggestedName: String): MetaPreview? {
        val tmdbName = listOf(title, name, originalTitle, originalName)
            .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
            ?: return null
        val displayName = aiDisplayName(tmdbName = tmdbName, suggestedName = suggestedName)
        val date = (releaseDate ?: firstAirDate)?.takeIf(String::isNotBlank)
        return MetaPreview(
            id = "tmdb:$id",
            type = type.wireName,
            name = displayName,
            poster = tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780"),
            banner = tmdbImageUrl(backdropPath, "w1280"),
            posterShape = PosterShape.Poster,
            description = overview?.trim()?.takeIf(String::isNotEmpty),
            releaseInfo = date?.take(4),
            rawReleaseDate = date,
            imdbRating = voteAverage?.takeIf { it > 0.0 }?.let { (kotlin.math.round(it * 10.0) / 10.0).toString() },
        )
    }
}

/**
 * Escolhe entre os primeiros resultados da busca. Com o idioma `pt`, o TMDB põe na frente o derivado
 * que tem nome traduzido ("Overlord: Play Play Pleiades", 0 votos) e deixa atrás a série principal
 * (nome só em japonês, 1143 votos). Vale o nome exato; sem nome exato, o mais votado.
 */
internal fun <T> pickBestTmdbMatch(
    results: List<T>,
    query: String,
    names: (T) -> List<String?>,
    votes: (T) -> Int,
    candidates: Int = 3,
): T? {
    val top = results.take(candidates)
    if (top.isEmpty()) return null
    val wanted = query.normalizedTitle()
    val exact = top.filter { result -> names(result).any { it != null && it.normalizedTitle() == wanted } }
    return exact.ifEmpty { top }.maxByOrNull(votes)
}

private fun String.normalizedTitle(): String =
    lowercase().filter { it.isLetterOrDigit() }

/**
 * Sem tradução no idioma pedido, o TMDB devolve o nome original (屍鬼, コープスパーティー). Nesse
 * caso o cartaz mostra o nome que o modelo sugeriu ("Shiki", "Corpse Party").
 */
internal fun aiDisplayName(tmdbName: String, suggestedName: String): String {
    val suggested = suggestedName.trim()
    return if (!tmdbName.hasLatinLetter() && suggested.hasLatinLetter()) suggested else tmdbName
}

private fun String.hasLatinLetter(): Boolean = any { it in 'A'..'Z' || it in 'a'..'z' || it in 'À'..'ÿ' }

private fun tmdbImageUrl(path: String?, size: String): String? =
    path?.trim()?.takeIf(String::isNotEmpty)?.let { "https://image.tmdb.org/t/p/$size$it" }
