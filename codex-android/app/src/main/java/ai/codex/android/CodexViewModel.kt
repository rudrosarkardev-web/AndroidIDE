/*
 * Codex for Android — state holder for chat, routes, and workspace sessions.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android

import ai.codex.android.agent.AgentEngine
import ai.codex.android.agent.WorkspaceTools
import ai.codex.android.data.AgentEvent
import ai.codex.android.data.ApiKeyProfile
import ai.codex.android.data.ChatMessage
import ai.codex.android.data.KeyVault
import ai.codex.android.data.ProviderKind
import ai.codex.android.data.defaultBaseUrl
import ai.codex.android.data.defaultModel
import ai.codex.android.network.ModelRouter
import ai.codex.android.network.NoRouteException
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class CodexViewModel(application: Application) : AndroidViewModel(application) {
  private val prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
  val vault = KeyVault(application)
  val workspaceRoot: File = File(application.filesDir, "workspace").apply { mkdirs() }
  private val tools = WorkspaceTools(workspaceRoot)
  private val router = ModelRouter(vault)
  private val engine = AgentEngine(router, tools)

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  private val _keys = MutableStateFlow(vault.list())
  val keys: StateFlow<List<ApiKeyProfile>> = _keys.asStateFlow()

  private val _events = MutableStateFlow<AgentEvent?>(null)
  val events: StateFlow<AgentEvent?> = _events.asStateFlow()

  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy.asStateFlow()

  private val _selectedProfileId = MutableStateFlow(prefs.getString(KEY_PROFILE, null))
  val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

  private val _workspaceLabel = MutableStateFlow(workspaceRoot.name)
  val workspaceLabel: StateFlow<String> = _workspaceLabel.asStateFlow()

  private var runJob: Job? = null
  private val history = mutableListOf<ChatMessage>()

  init {
    reloadKeys()
  }

  fun reloadKeys() {
    _keys.value = vault.list()
    val selected = _selectedProfileId.value
    if (selected != null && _keys.value.none { it.id == selected }) selectProfile(null)
  }

  fun selectProfile(id: String?) {
    _selectedProfileId.value = id
    prefs.edit().putString(KEY_PROFILE, id).apply()
  }

  fun send(prompt: String) {
    val clean = prompt.trim()
    if (clean.isEmpty() || _busy.value) return
    runJob?.cancel()
    runJob = viewModelScope.launch {
      _busy.value = true
      _events.value = AgentEvent(AgentEvent.Kind.STATUS, "Starting agent…")
      try {
        engine.run(
          prompt = clean,
          history = history,
          preferredProfileId = _selectedProfileId.value,
          onMessage = { message ->
            if (message.role != ChatMessage.Role.SYSTEM) {
              _messages.value = _messages.value + message
            }
          },
          onEvent = { event -> _events.value = event },
        )
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        _events.value = AgentEvent(AgentEvent.Kind.STATUS, "Stopped")
      } catch (error: Throwable) {
        val message = error.message ?: "Agent failed"
        _events.value = AgentEvent(AgentEvent.Kind.ERROR, message)
        val errorMessage = ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "I couldn't complete that: $message")
        history += errorMessage
        _messages.value = _messages.value + errorMessage
      } finally {
        reloadKeys()
        _busy.value = false
      }
    }
  }

  fun stop() {
    runJob?.cancel()
    runJob = null
    _busy.value = false
    _events.value = AgentEvent(AgentEvent.Kind.STATUS, "Stopped")
  }

  fun newTask() {
    stop()
    history.clear()
    _messages.value = emptyList()
    _events.value = AgentEvent(AgentEvent.Kind.STATUS, "New task")
  }

  fun addProfile(
    provider: ProviderKind,
    label: String,
    secret: String,
    baseUrl: String,
    model: String,
  ): ApiKeyProfile {
    val profile = ApiKeyProfile(
      label = label.trim().ifBlank { "${provider.displayName} route ${_keys.value.size + 1}" },
      provider = provider,
      secret = secret.trim(),
      baseUrl = baseUrl.trim().ifBlank { provider.defaultBaseUrl() },
      model = model.trim().ifBlank { provider.defaultModel() },
    )
    vault.add(profile)
    reloadKeys()
    if (_selectedProfileId.value == null) selectProfile(profile.id)
    return profile
  }

  fun importProfiles(text: String): Int {
    val count = vault.importBulk(text)
    reloadKeys()
    if (_selectedProfileId.value == null) _keys.value.firstOrNull()?.let { selectProfile(it.id) }
    return count
  }

  fun toggleProfile(profile: ApiKeyProfile) {
    vault.setEnabled(profile.id, !profile.enabled)
    reloadKeys()
  }

  fun removeProfile(profile: ApiKeyProfile) {
    vault.remove(profile.id)
    if (_selectedProfileId.value == profile.id) selectProfile(_keys.value.firstOrNull { it.id != profile.id }?.id)
    reloadKeys()
  }

  fun testProfile(profile: ApiKeyProfile, onDone: (String) -> Unit) {
    viewModelScope.launch {
      try {
        val result = router.complete(
          messages = listOf(ChatMessage(role = ChatMessage.Role.USER, content = "Reply with the single word OK.")),
          tools = emptyList(),
          preferredProfileId = profile.id,
        )
        reloadKeys()
        onDone("Connected · ${result.profile.displayModel()}")
      } catch (error: Throwable) {
        reloadKeys()
        onDone(error.message ?: "Connection failed")
      }
    }
  }

  fun clearStatus() {
    _events.value = null
  }

  override fun onCleared() {
    runJob?.cancel()
    super.onCleared()
  }

  companion object {
    private const val PREFS = "codex_preferences"
    private const val KEY_PROFILE = "selected_profile"
  }
}
