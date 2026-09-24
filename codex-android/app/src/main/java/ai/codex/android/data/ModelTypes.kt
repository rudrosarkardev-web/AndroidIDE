/*
 * Codex for Android — shared model and provider types.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.data

import java.util.UUID

/** API families supported by the built-in router. */
enum class ProviderKind(
  val displayName: String,
  val needsSecret: Boolean,
  val local: Boolean,
) {
  OPENAI("OpenAI", true, false),
  ANTHROPIC("Anthropic", true, false),
  GOOGLE("Google Gemini", true, false),
  OPENAI_COMPATIBLE("OpenAI-compatible", false, false),
  OLLAMA("Ollama", false, true),
  LM_STUDIO("LM Studio", false, true),
  LLAMA_CPP("llama.cpp", false, true),
  ;

  companion object {
    fun fromStored(value: String?): ProviderKind {
      val normalized = value?.trim()?.uppercase().orEmpty()
      return entries.firstOrNull { it.name == normalized } ?: OPENAI_COMPATIBLE
    }
  }
}

data class ApiKeyProfile(
  val id: String = UUID.randomUUID().toString(),
  val label: String,
  val provider: ProviderKind,
  val secret: String = "",
  val baseUrl: String = provider.defaultBaseUrl(),
  val model: String = provider.defaultModel(),
  val enabled: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val lastUsedAt: Long = 0L,
  val cooldownUntil: Long = 0L,
  val failures: Int = 0,
  val requests: Long = 0L,
) {
  val isCoolingDown: Boolean
    get() = cooldownUntil > System.currentTimeMillis()

  val maskedSecret: String
    get() = when {
      secret.isBlank() -> if (provider.local) "local" else "not set"
      secret.length <= 8 -> "••••••••"
      else -> "${secret.take(4)}••••${secret.takeLast(4)}"
    }

  fun displayModel(): String = model.ifBlank { provider.defaultModel() }
}

data class ModelOption(
  val id: String,
  val label: String,
  val provider: ProviderKind,
  val baseUrl: String,
  val local: Boolean,
)

data class ChatMessage(
  val id: String = UUID.randomUUID().toString(),
  val role: Role,
  val content: String,
  val timestamp: Long = System.currentTimeMillis(),
  val toolName: String? = null,
  val toolCallId: String? = null,
  val toolCalls: List<ToolCall> = emptyList(),
) {
  enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

data class ToolCall(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val arguments: String,
)

data class ModelReply(
  val content: String,
  val toolCalls: List<ToolCall> = emptyList(),
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
)

data class RouteResult(
  val profile: ApiKeyProfile,
  val reply: ModelReply,
)

data class AgentEvent(
  val kind: Kind,
  val text: String,
  val toolName: String? = null,
) {
  enum class Kind { STATUS, THINKING, TOOL_STARTED, TOOL_FINISHED, ERROR, DONE }
}

data class ToolSpec(
  val name: String,
  val description: String,
  val parametersJson: String,
)

fun ProviderKind.defaultBaseUrl(): String = when (this) {
  ProviderKind.OPENAI -> "https://api.openai.com/v1"
  ProviderKind.ANTHROPIC -> "https://api.anthropic.com"
  ProviderKind.GOOGLE -> "https://generativelanguage.googleapis.com"
  ProviderKind.OPENAI_COMPATIBLE -> "http://127.0.0.1:11434/v1"
  ProviderKind.OLLAMA -> "http://127.0.0.1:11434"
  ProviderKind.LM_STUDIO -> "http://127.0.0.1:1234/v1"
  ProviderKind.LLAMA_CPP -> "http://127.0.0.1:8080/v1"
}

fun ProviderKind.defaultModel(): String = when (this) {
  ProviderKind.OPENAI -> "gpt-4o-mini"
  ProviderKind.ANTHROPIC -> "claude-3-5-haiku-latest"
  ProviderKind.GOOGLE -> "gemini-2.0-flash"
  ProviderKind.OPENAI_COMPATIBLE -> "llama3.2"
  ProviderKind.OLLAMA -> "llama3.2"
  ProviderKind.LM_STUDIO -> "local-model"
  ProviderKind.LLAMA_CPP -> "local-model"
}

fun ProviderKind.Companion.fromLabel(label: String): ProviderKind =
  ProviderKind.entries.firstOrNull { it.displayName.equals(label, ignoreCase = true) }
    ?: fromStored(label.uppercase())
