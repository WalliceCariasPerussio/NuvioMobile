package com.nuvio.app.features.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.ai_assistant_back_to_search
import nuvio.composeapp.generated.resources.ai_assistant_cancel
import nuvio.composeapp.generated.resources.ai_assistant_change
import nuvio.composeapp.generated.resources.ai_assistant_empty_message
import nuvio.composeapp.generated.resources.ai_assistant_empty_title
import nuvio.composeapp.generated.resources.ai_assistant_free
import nuvio.composeapp.generated.resources.ai_assistant_key_hint
import nuvio.composeapp.generated.resources.ai_assistant_key_label
import nuvio.composeapp.generated.resources.ai_assistant_model_default
import nuvio.composeapp.generated.resources.ai_assistant_model_title
import nuvio.composeapp.generated.resources.ai_assistant_models_loading
import nuvio.composeapp.generated.resources.ai_assistant_models_need_key
import nuvio.composeapp.generated.resources.ai_assistant_new_chat
import nuvio.composeapp.generated.resources.ai_assistant_none_found
import nuvio.composeapp.generated.resources.ai_assistant_prompt_placeholder
import nuvio.composeapp.generated.resources.ai_assistant_results_title
import nuvio.composeapp.generated.resources.ai_assistant_save
import nuvio.composeapp.generated.resources.ai_assistant_send
import nuvio.composeapp.generated.resources.ai_assistant_setup_message
import nuvio.composeapp.generated.resources.ai_assistant_setup_title
import nuvio.composeapp.generated.resources.ai_assistant_some_missing
import nuvio.composeapp.generated.resources.ai_assistant_suggestion_1
import nuvio.composeapp.generated.resources.ai_assistant_suggestion_2
import nuvio.composeapp.generated.resources.ai_assistant_suggestion_3
import nuvio.composeapp.generated.resources.ai_assistant_suggestion_4
import nuvio.composeapp.generated.resources.ai_assistant_thinking
import nuvio.composeapp.generated.resources.ai_assistant_toggle
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberAiAssistantSettings(): State<AiAssistantSettings> =
    remember {
        AiAssistantSettingsRepository.ensureLoaded()
        AiAssistantSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

/** Botão do cabeçalho da Busca que alterna entre a busca normal e o assistente. */
@Composable
fun AiAssistantModeButton(
    active: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioIconActionButton(
        icon = if (active) Icons.Rounded.Search else Icons.Rounded.AutoAwesome,
        contentDescription = stringResource(
            if (active) Res.string.ai_assistant_back_to_search else Res.string.ai_assistant_toggle,
        ),
        tint = if (active) tokens.colors.textPrimary else tokens.colors.accent,
        onClick = onClick,
    )
}

/** Campo do pedido, no lugar do campo de busca quando o assistente está aberto. */
@Composable
fun AiAssistantPromptField(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val state by AiAssistantRepository.uiState.collectAsStateWithLifecycle()
    val settings by rememberAiAssistantSettings()
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = text.isNotBlank() && !state.isBusy && settings.isConfigured

    fun submit() {
        if (!canSend) return
        if (AiAssistantRepository.send(text)) {
            text = ""
            keyboard?.hide()
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        enabled = settings.isConfigured,
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        placeholder = {
            Text(
                text = stringResource(Res.string.ai_assistant_prompt_placeholder),
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { submit() }),
        trailingIcon = {
            IconButton(onClick = { submit() }, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(Res.string.ai_assistant_send),
                    tint = if (canSend) tokens.colors.accent else tokens.colors.textDisabled,
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus,
            unfocusedBorderColor = tokens.colors.borderDefault,
            disabledBorderColor = tokens.colors.borderDefault,
            focusedContainerColor = tokens.colors.surfaceCard,
            unfocusedContainerColor = tokens.colors.surfaceCard,
            disabledContainerColor = tokens.colors.surfaceCard,
            cursorColor = tokens.colors.accent,
        ),
    )
}

/** Leva a lista para o topo quando chega uma mensagem nova (a mais nova fica em cima). */
@Composable
fun AiAssistantScrollEffect(listState: LazyListState) {
    val state by AiAssistantRepository.uiState.collectAsStateWithLifecycle()
    val newestId = state.turns.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null) listState.animateScrollToItem(0)
    }
}

fun LazyListScope.aiAssistantContent(
    state: AiAssistantUiState,
    horizontalPadding: Dp,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    item(key = "ai_assistant_settings") {
        AiAssistantSettingsSection(
            hasTurns = state.turns.isNotEmpty(),
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
    }
    if (state.turns.isEmpty()) {
        item(key = "ai_assistant_empty") {
            AiAssistantEmptyState(modifier = Modifier.padding(horizontal = horizontalPadding))
        }
    } else {
        items(
            items = state.turns.asReversed(),
            key = { turn -> "ai_assistant_turn_${turn.id}" },
        ) { turn ->
            AiAssistantTurnItem(
                turn = turn,
                horizontalPadding = horizontalPadding,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
            )
        }
    }
}

@Composable
private fun AiAssistantSettingsSection(
    hasTurns: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val settings by rememberAiAssistantSettings()
    var editing by rememberSaveable { mutableStateOf(false) }

    if (!settings.isConfigured || editing) {
        AiAssistantSetupCard(
            settings = settings,
            canCancel = settings.isConfigured,
            onDone = { editing = false },
            modifier = modifier.padding(bottom = NuvioTokens.Space.s16),
        )
        return
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(NuvioTokens.Space.s8))
        Text(
            text = "${settings.provider.displayName} · ${settings.model()}",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { editing = true }) {
            Text(stringResource(Res.string.ai_assistant_change))
        }
        if (hasTurns) {
            TextButton(onClick = AiAssistantRepository::clear) {
                Text(stringResource(Res.string.ai_assistant_new_chat))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiAssistantSetupCard(
    settings: AiAssistantSettings,
    canCancel: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var providerId by rememberSaveable { mutableStateOf(settings.provider.id) }
    val provider = AiProvider.fromId(providerId)
    var apiKey by rememberSaveable(providerId) { mutableStateOf(settings.apiKey(provider)) }
    var model by rememberSaveable(providerId) { mutableStateOf(settings.customModel(provider)) }
    val freeLabel = stringResource(Res.string.ai_assistant_free)

    NuvioSurfaceCard(modifier = modifier) {
        Text(
            text = stringResource(Res.string.ai_assistant_setup_title),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
        Text(
            text = stringResource(Res.string.ai_assistant_setup_message),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            AiProvider.entries.forEach { option ->
                Chip(
                    label = if (option == AiProvider.Gemini) "${option.displayName} ($freeLabel)" else option.displayName,
                    selected = option == provider,
                    onClick = { providerId = option.id },
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.ai_assistant_key_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Text(
            text = stringResource(Res.string.ai_assistant_key_hint, provider.keyUrl),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(top = NuvioTokens.Space.s4),
        )
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        AiModelPicker(
            provider = provider,
            apiKey = apiKey,
            selected = model.ifBlank { provider.defaultModel },
            onSelect = { id -> model = if (id == provider.defaultModel) "" else id },
        )
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            if (canCancel) {
                TextButton(onClick = onDone) {
                    Text(stringResource(Res.string.ai_assistant_cancel))
                }
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.ai_assistant_save),
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    AiAssistantSettingsRepository.save(provider, apiKey, model)
                    onDone()
                },
            )
        }
    }
}

/** Botões com os modelos de chat que a chave pode usar, lidos da API do provedor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiModelPicker(
    provider: AiProvider,
    apiKey: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var models by remember(provider) { mutableStateOf<List<AiModelOption>>(emptyList()) }
    var error by remember(provider) { mutableStateOf<String?>(null) }
    var loading by remember(provider) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(provider, apiKey, reload) {
        models = emptyList()
        error = null
        if (apiKey.isBlank()) return@LaunchedEffect
        delay(400) // espera terminar de colar a chave
        loading = true
        try {
            when (val result = AiModelCatalog.fetch(provider, apiKey)) {
                is AiModelListResult.Success -> models = result.models
                is AiModelListResult.Failure -> error = result.message
            }
        } finally {
            loading = false
        }
    }

    Text(
        text = stringResource(Res.string.ai_assistant_model_title),
        style = MaterialTheme.typography.titleSmall,
        color = tokens.colors.textPrimary,
    )
    val status = when {
        apiKey.isBlank() -> stringResource(Res.string.ai_assistant_models_need_key)
        loading -> stringResource(Res.string.ai_assistant_models_loading)
        else -> null
    }
    if (status != null) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(top = NuvioTokens.Space.s4),
        )
    }
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
    val defaultLabel = stringResource(Res.string.ai_assistant_model_default)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        AiModelCatalog.options(models, provider, selected).forEach { option ->
            Chip(
                label = if (option.id == provider.defaultModel) "${option.label} · $defaultLabel" else option.label,
                selected = option.id == selected,
                onClick = { onSelect(option.id) },
            )
        }
    }
    error?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.danger,
            modifier = Modifier.padding(top = NuvioTokens.Space.s8),
        )
        TextButton(onClick = { reload++ }) {
            Text(stringResource(Res.string.action_retry))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiAssistantEmptyState(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val settings by rememberAiAssistantSettings()
    if (!settings.isConfigured) return
    val suggestions = listOf(
        stringResource(Res.string.ai_assistant_suggestion_1),
        stringResource(Res.string.ai_assistant_suggestion_2),
        stringResource(Res.string.ai_assistant_suggestion_3),
        stringResource(Res.string.ai_assistant_suggestion_4),
    )
    Column(modifier = modifier.fillMaxWidth().padding(top = NuvioTokens.Space.s8)) {
        Text(
            text = stringResource(Res.string.ai_assistant_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
        Text(
            text = stringResource(Res.string.ai_assistant_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            suggestions.forEach { suggestion ->
                Chip(
                    label = suggestion,
                    selected = false,
                    onClick = { AiAssistantRepository.send(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun AiAssistantTurnItem(
    turn: AiAssistantTurn,
    horizontalPadding: Dp,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = NuvioTokens.Space.s20)) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = turn.prompt,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .background(tokens.colors.surfaceCard, RoundedCornerShape(NuvioTokens.Radius.lg))
                    .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s10),
            )
        }
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
        when (turn.status) {
            AiAssistantTurnStatus.Loading -> {
                Text(
                    text = stringResource(Res.string.ai_assistant_thinking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
                HomeSkeletonRow(horizontalPadding = horizontalPadding)
            }

            AiAssistantTurnStatus.Failed -> {
                Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                    Text(
                        text = turn.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.danger,
                    )
                    TextButton(onClick = { AiAssistantRepository.retry(turn.id) }) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }

            AiAssistantTurnStatus.Done -> {
                if (turn.reply.isNotBlank()) {
                    Text(
                        text = turn.reply,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )
                }
                if (turn.items.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
                    NuvioShelfSection(
                        title = stringResource(Res.string.ai_assistant_results_title),
                        entries = turn.items,
                        headerHorizontalPadding = horizontalPadding,
                        rowContentPadding = PaddingValues(horizontal = horizontalPadding),
                        key = { item -> item.stableKey() },
                    ) { item ->
                        HomePosterCard(
                            item = item,
                            isWatched = WatchingState.isPosterWatched(
                                watchedKeys = watchedKeys,
                                item = item,
                                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                            ),
                            onClick = onPosterClick?.let { click -> { click(item) } },
                            onLongClick = onPosterLongClick?.let { click -> { click(item) } },
                        )
                    }
                }
                val missing = turn.suggestedCount - turn.items.size
                val note = when {
                    turn.suggestedCount > 0 && turn.items.isEmpty() -> stringResource(Res.string.ai_assistant_none_found)
                    missing > 0 -> stringResource(Res.string.ai_assistant_some_missing, missing)
                    else -> null
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = NuvioTokens.Space.s4),
                    )
                }
            }
        }
    }
}
