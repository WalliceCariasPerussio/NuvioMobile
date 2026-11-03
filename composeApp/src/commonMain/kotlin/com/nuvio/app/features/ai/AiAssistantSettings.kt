package com.nuvio.app.features.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Provedores de IA aceitos pelo assistente. O modelo padrão de cada um pode ser trocado nas
 * configurações do assistente (campo "Modelo").
 */
enum class AiProvider(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val keyUrl: String,
) {
    Gemini(
        id = "gemini",
        displayName = "Google Gemini",
        defaultModel = "gemini-3.5-flash-lite",
        keyUrl = "aistudio.google.com/apikey",
    ),
    Anthropic(
        id = "anthropic",
        displayName = "Anthropic Claude",
        defaultModel = "claude-opus-5-5",
        keyUrl = "console.anthropic.com/settings/keys",
    ),
    OpenAi(
        id = "openai",
        displayName = "OpenAI",
        defaultModel = "gpt-6-luna",
        keyUrl = "platform.openai.com/api-keys",
    );

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: Gemini
    }
}

data class AiAssistantSettings(
    val provider: AiProvider = AiProvider.Gemini,
    val apiKeys: Map<AiProvider, String> = emptyMap(),
    val models: Map<AiProvider, String> = emptyMap(),
) {
    fun apiKey(provider: AiProvider = this.provider): String = apiKeys[provider].orEmpty()

    fun model(provider: AiProvider = this.provider): String =
        models[provider]?.trim()?.takeIf(String::isNotEmpty) ?: provider.defaultModel

    fun customModel(provider: AiProvider = this.provider): String = models[provider].orEmpty()

    val isConfigured: Boolean
        get() = apiKey().isNotBlank()
}

@Serializable
internal data class AiAssistantSettingsPayload(
    val provider: String? = null,
    val apiKeys: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
)

internal fun AiAssistantSettings.toPayload(): AiAssistantSettingsPayload =
    AiAssistantSettingsPayload(
        provider = provider.id,
        apiKeys = apiKeys.filterValues(String::isNotBlank).mapKeys { it.key.id },
        models = models.filterValues(String::isNotBlank).mapKeys { it.key.id },
    )

internal fun AiAssistantSettingsPayload.toSettings(): AiAssistantSettings =
    AiAssistantSettings(
        provider = AiProvider.fromId(provider),
        apiKeys = apiKeys.mapNotNull { (id, key) ->
            AiProvider.entries.firstOrNull { it.id == id }?.let { it to key.trim() }
        }.toMap(),
        models = models.mapNotNull { (id, model) ->
            AiProvider.entries.firstOrNull { it.id == id }?.let { it to model.trim() }
        }.toMap(),
    )

/**
 * A chave fica só neste aparelho (não vai para a sincronização da conta) e vale para todos os
 * perfis.
 */
internal expect object AiAssistantStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

object AiAssistantSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(AiAssistantSettings())
    val uiState: StateFlow<AiAssistantSettings> = _uiState.asStateFlow()
    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _uiState.value = AiAssistantStorage.loadPayload()
            ?.let { payload ->
                runCatching { json.decodeFromString<AiAssistantSettingsPayload>(payload).toSettings() }.getOrNull()
            }
            ?: AiAssistantSettings()
    }

    fun snapshot(): AiAssistantSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun save(provider: AiProvider, apiKey: String, model: String) {
        ensureLoaded()
        val current = _uiState.value
        val updated = current.copy(
            provider = provider,
            apiKeys = current.apiKeys + (provider to apiKey.trim()),
            models = current.models + (provider to model.trim()),
        )
        if (updated == current) return
        _uiState.value = updated
        AiAssistantStorage.savePayload(json.encodeToString(AiAssistantSettingsPayload.serializer(), updated.toPayload()))
    }
}
