package com.nuvio.app.features.ai

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.showTrackingMembershipRewriteFeedback
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackId
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watched.toEpisodeWatchedItem
import com.nuvio.app.features.watched.toSeriesWatchedItem
import com.nuvio.app.features.watched.toWatchedItem
import com.nuvio.app.features.watching.domain.isSeriesLikeWatchingContentType
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ai_assistant_action_error_no_episodes
import nuvio.composeapp.generated.resources.ai_assistant_action_error_no_meta
import org.jetbrains.compose.resources.getString

internal data class AiActionOutcome(
    val status: AiAssistantActionStatus,
    val episodeCount: Int? = null,
    val error: String? = null,
)

/**
 * Executa as ações aprovadas no cartão do assistente. Nada aqui desfaz estado: título já visto ou
 * já salvo fica como está (os botões do cartaz alternam, e "marca os 3" desmarcaria o que já estava).
 */
internal object AiAssistantActionRunner {
    private val log = Logger.withTag("AiAssistantActions")

    suspend fun run(action: AiAssistantAction): AiActionOutcome {
        val target = action.target ?: return AiActionOutcome(AiAssistantActionStatus.NotFound)
        return try {
            when (action.kind) {
                AiAssistantActionKind.MarkWatched -> markWatched(target)
                AiAssistantActionKind.AddToLibrary -> addToLibrary(target)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "${action.kind} falhou para ${target.type} ${target.id}" }
            AiActionOutcome(
                status = AiAssistantActionStatus.Failed,
                error = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
            )
        }
    }

    private suspend fun markWatched(preview: MetaPreview): AiActionOutcome {
        // Os detalhes trazem o id do catálogo (tt...), o mesmo que o Trakt e os vistos guardam.
        val meta = fetchMeta(preview)
        if (!preview.type.isSeriesLikeWatchingContentType()) {
            val item = meta?.toSeriesWatchedItem() ?: preview.toWatchedItem(markedAtEpochMs = 0L)
            val alreadyWatched = WatchedRepository.isWatched(id = item.id, type = item.type) ||
                WatchedRepository.isWatched(id = preview.id, type = preview.type)
            if (alreadyWatched) return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)
            WatchedRepository.markWatched(item)
            return AiActionOutcome(AiAssistantActionStatus.Done)
        }

        meta ?: return failure(getString(Res.string.ai_assistant_action_error_no_meta))
        val alreadyWatched = WatchedRepository.isFullyWatchedSeries(id = meta.id, type = meta.type) ||
            WatchedRepository.isFullyWatchedSeries(id = preview.id, type = preview.type)
        if (alreadyWatched) return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)

        // Mesmo caminho do "Marcar como assistido" do cartaz (WatchingActions.togglePosterWatched),
        // só a parte que marca, e só o que falta: remarcar episódio visto troca a data dele e manda
        // de novo para o Trakt (série vista pela metade, ou o mesmo pedido aprovado duas vezes).
        val released = meta.releasedMainSeasonEpisodes(CurrentDateProvider.todayIsoDate())
        if (released.isEmpty()) return failure(getString(Res.string.ai_assistant_action_error_no_episodes))
        val missing = released.filterNot { episode ->
            WatchedRepository.isWatched(id = meta.id, type = meta.type, season = episode.season, episode = episode.episode) ||
                WatchedRepository.isWatched(id = preview.id, type = preview.type, season = episode.season, episode = episode.episode)
        }
        val seriesMarked = WatchedRepository.isWatched(id = meta.id, type = meta.type) ||
            WatchedRepository.isWatched(id = preview.id, type = preview.type)
        if (missing.isEmpty() && seriesMarked) return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)
        WatchedRepository.markWatched(
            buildList {
                if (!seriesMarked) add(meta.toSeriesWatchedItem())
                addAll(missing.map(meta::toEpisodeWatchedItem))
            },
        )
        WatchedRepository.updateFullyWatchedSeries(id = meta.id, type = meta.type, isFullyWatched = true)
        if (missing.isNotEmpty()) {
            WatchProgressRepository.clearProgress(
                videoIds = missing.map(meta::episodePlaybackId),
                parentMetaId = meta.id,
            )
        }
        return AiActionOutcome(AiAssistantActionStatus.Done, episodeCount = missing.size.takeIf { it > 0 })
    }

    private suspend fun addToLibrary(preview: MetaPreview): AiActionOutcome {
        val savedAt = LibraryClock.nowEpochMs()
        val item = fetchMeta(preview)?.toLibraryItem(savedAt) ?: preview.toLibraryItem(savedAt)
        // toggleSaved alterna pela situação que o provedor tem agora (getMembershipSnapshot), não pelo
        // cache do isSaved: decidir pela mesma leitura garante que ele só pode adicionar, nunca tirar.
        val alreadySaved = LibraryRepository.isSaved(item.id, item.type) ||
            LibraryRepository.isSaved(preview.id, preview.type) ||
            LibraryRepository.getMembershipSnapshot(item).values.any { it }
        if (alreadySaved) return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)
        // toggleSaved, e não save(): com o Trakt como fonte da biblioteca, só ele manda para lá.
        val result = LibraryRepository.toggleSaved(item)
        if (result.requiresRemovalConfirmation) {
            // Pediria confirmação de remoção: nada foi feito.
            return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)
        }
        showTrackingMembershipRewriteFeedback(result)
        return AiActionOutcome(AiAssistantActionStatus.Done)
    }

    private suspend fun fetchMeta(preview: MetaPreview): MetaDetails? =
        try {
            MetaDetailsRepository.fetch(type = preview.type, id = preview.id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Detalhes de ${preview.type} ${preview.id} falharam" }
            null
        }

    private fun failure(message: String) = AiActionOutcome(AiAssistantActionStatus.Failed, error = message)
}
