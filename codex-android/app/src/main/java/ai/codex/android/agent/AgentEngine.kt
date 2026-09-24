/*
 * Codex for Android — tool-using agent loop.
 *
 * The loop is deliberately provider-neutral: native tool calls are preferred,
 * with a small JSON fallback for local models that do not advertise function
 * calling. Tool results are returned to the model so it can continue until the
 * task is complete instead of pretending a command succeeded.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.agent

import ai.codex.android.data.AgentEvent
import ai.codex.android.data.ChatMessage
import ai.codex.android.data.ModelReply
import ai.codex.android.data.ToolCall
import ai.codex.android.network.ModelRouter
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

class AgentEngine(
  private val router: ModelRouter,
  private val tools: WorkspaceTools,
) {
  suspend fun run(
    prompt: String,
    history: MutableList<ChatMessage>,
    preferredProfileId: String?,
    onMessage: suspend (ChatMessage) -> Unit,
    onEvent: suspend (AgentEvent) -> Unit,
  ) {
    if (prompt.isBlank()) return
    val system = ChatMessage(
      role = ChatMessage.Role.SYSTEM,
      content = SYSTEM_PROMPT + "\nWorkspace root: ${tools.workspaceRootPath}",
    )
    if (history.none { it.role == ChatMessage.Role.SYSTEM }) {
      history.add(0, system)
    }
    val user = ChatMessage(role = ChatMessage.Role.USER, content = prompt)
    history += user
    onMessage(user)

    var rounds = 0
    while (rounds++ < MAX_ROUNDS) {
      coroutineContext.ensureActive()
      onEvent(AgentEvent(AgentEvent.Kind.THINKING, "Thinking…"))
      val route = router.complete(history, tools.specs, preferredProfileId)
      onEvent(AgentEvent(
        AgentEvent.Kind.STATUS,
        "${route.profile.provider.displayName} · ${route.profile.displayModel()} · ${route.profile.label}",
      ))

      val reply = normalizeToolFallback(route.reply)
      val assistant = ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply.content, toolCalls = reply.toolCalls)
      history += assistant
      if (reply.content.isNotBlank()) onMessage(assistant)

      if (reply.toolCalls.isEmpty()) {
        onEvent(AgentEvent(AgentEvent.Kind.DONE, "Done"))
        return
      }

      for (call in reply.toolCalls) {
        coroutineContext.ensureActive()
        val toolName = call.name.ifBlank { "unknown" }
        onEvent(AgentEvent(AgentEvent.Kind.TOOL_STARTED, "Running $toolName", toolName))
        val result = tools.execute(toolName, call.arguments)
        val toolMessage = ChatMessage(
          role = ChatMessage.Role.TOOL,
          content = result.output,
          toolName = toolName,
          toolCallId = call.id,
        )
        history += toolMessage
        onMessage(toolMessage)
        onEvent(AgentEvent(
          AgentEvent.Kind.TOOL_FINISHED,
          if (result.success) "$toolName completed" else "$toolName failed",
          toolName,
        ))
      }
    }
    onEvent(AgentEvent(AgentEvent.Kind.DONE, "Stopped after $MAX_ROUNDS steps"))
  }

  private fun normalizeToolFallback(reply: ModelReply): ModelReply {
    if (reply.toolCalls.isNotEmpty() || reply.content.isBlank()) return reply
    val trimmed = reply.content.trim()
    val json = runCatching {
      when {
        trimmed.startsWith("{") && trimmed.endsWith("}") -> JSONObject(trimmed)
        trimmed.contains("```json") -> JSONObject(trimmed.substringAfter("```json").substringBefore("```" ).trim())
        else -> null
      }
    }.getOrNull() ?: return reply
    val name = json.optString("tool", json.optString("name"))
    if (name.isBlank()) return reply
    val args = json.optJSONObject("arguments") ?: json.optJSONObject("args") ?: JSONObject()
    return reply.copy(content = json.optString("message", ""), toolCalls = listOf(ToolCall(name = name, arguments = args.toString())))
  }

  companion object {
    private const val MAX_ROUNDS = 24
    private val SYSTEM_PROMPT = """
      You are Codex, an on-device coding agent. Work carefully and verify your work.
      You have tools for listing, reading, writing, editing, searching, making directories,
      and running commands in the workspace. Use them instead of claiming that you changed files.
      Before editing, inspect relevant files. Keep changes focused. After edits, run an appropriate
      check or test when possible and report what actually happened. Never expose API keys.
      If a tool call is unavailable, you may output exactly one JSON object like
      {"tool":"read_file","arguments":{"path":"relative/path"}}.
    """.trimIndent()
  }
}

