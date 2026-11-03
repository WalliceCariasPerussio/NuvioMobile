package com.nuvio.app.features.ai

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AiAssistantTurnStatus {
    Loading,
    Done,
    Failed,
}

data class AiAssistantTurn(
    val id: Long,
    val prompt: String,
    val status: AiAssistantTurnStatus,
    val reply: String = "",
    val items: List<MetaPreview> = emptyList(),
    val suggestedCount: Int = 0,
    val error: String? = null,
    /** Resposta do modelo em JSON compacto, mandada de volta como histórico. */
    internal val historyText: String? = null,
)

data class AiAssistantUiState(
    val turns: List<AiAssistantTurn> = emptyList(),
) {
    val isBusy: Boolean
        get() = turns.any { it.status == AiAssistantTurnStatus.Loading }
}

/** Conversa do assistente. Fica só na memória: some ao fechar o app. */
object AiAssistantRepository {
    /** Quantas trocas anteriores vão como histórico em cada pedido. */
    private const val MAX_HISTORY_TURNS = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState: StateFlow<AiAssistantUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var nextId = 1L

    fun send(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || _uiState.value.isBusy) return false
        val settings = AiAssistantSettingsRepository.snapshot()
        if (!settings.isConfigured) return false

        val history = _uiState.value.turns
            .filter { it.status == AiAssistantTurnStatus.Done && it.historyText != null }
            .takeLast(MAX_HISTORY_TURNS)
        val turnId = nextId++
        _uiState.update { state ->
            state.copy(turns = state.turns + AiAssistantTurn(turnId, prompt, AiAssistantTurnStatus.Loading))
        }
        activeJob = scope.launch {
            val finished = runTurn(settings, history, prompt, turnId)
            _uiState.update { state ->
                state.copy(turns = state.turns.map { if (it.id == turnId) finished else it })
            }
        }
        return true
    }

    /** Tira a mensagem que falhou e manda o mesmo pedido de novo. */
    fun retry(turnId: Long) {
        val turn = _uiState.value.turns.firstOrNull { it.id == turnId } ?: return
        if (turn.status != AiAssistantTurnStatus.Failed || _uiState.value.isBusy) return
        _uiState.update { state -> state.copy(turns = state.turns.filterNot { it.id == turnId }) }
        send(turn.prompt)
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        _uiState.value = AiAssistantUiState()
    }

    private suspend fun runTurn(
        settings: AiAssistantSettings,
        history: List<AiAssistantTurn>,
        prompt: String,
        turnId: Long,
    ): AiAssistantTurn {
        // A SearchScreen já chama WatchedRepository.ensureLoaded() na thread principal.
        val watched = WatchedRepository.uiState.value.items
        val messages = buildList {
            history.forEach { turn ->
                add(AiChatMessage(AiChatRole.User, turn.prompt))
                add(AiChatMessage(AiChatRole.Assistant, turn.historyText.orEmpty()))
            }
            add(AiChatMessage(AiChatRole.User, AiRecommendationPrompt.buildUserTurn(prompt, watched)))
        }

        val failed = AiAssistantTurn(turnId, prompt, AiAssistantTurnStatus.Failed)
        val result = AiChatClient.complete(
            provider = settings.provider,
            apiKey = settings.apiKey(),
            model = settings.model(),
            system = AiRecommendationPrompt.systemPrompt,
            messages = messages,
            responseSchema = AiRecommendationPrompt.responseSchema,
        )
        val text = when (result) {
            is AiChatResult.Failure -> return failed.copy(error = result.message)
            is AiChatResult.Success -> result.text
        }
        val reply = AiRecommendationPrompt.parseReply(text)
            ?: return failed.copy(error = "Resposta em formato inesperado: ${text.take(200)}")
        val items = AiTitleResolver.resolve(reply.titles)
        return AiAssistantTurn(
            id = turnId,
            prompt = prompt,
            status = AiAssistantTurnStatus.Done,
            reply = reply.reply,
            items = items,
            // Só conta o que foi buscado: acima do limite os títulos nem vão ao TMDB.
            suggestedCount = minOf(reply.titles.size, AiRecommendationPrompt.MAX_TITLES),
            historyText = AiRecommendationPrompt.encodeForHistory(reply),
        )
    }
}
