/*
 * Codex for Android — provider adapters and resilient key rotation.
 *
 * Requests use the provider's native JSON shape where it differs. OpenAI,
 * Ollama, LM Studio, llama.cpp, and most self-hosted gateways use the same
 * `/chat/completions` contract; this makes local models first-class routes.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.network

import ai.codex.android.data.ApiKeyProfile
import ai.codex.android.data.ChatMessage
import ai.codex.android.data.KeyVault
import ai.codex.android.data.ModelReply
import ai.codex.android.data.ProviderKind
import ai.codex.android.data.RouteResult
import ai.codex.android.data.ToolCall
import ai.codex.android.data.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ProviderHttpException(
  val statusCode: Int,
  val retryAfterMs: Long? = null,
  message: String,
) : IOException(message)

class NoRouteException(message: String) : IOException(message)

private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

class ModelRouter(
  private val vault: KeyVault,
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(6, TimeUnit.MINUTES)
    .build(),
) {
  private val adapters = ProviderAdapters(http)

  suspend fun complete(
    messages: List<ChatMessage>,
    tools: List<ToolSpec>,
    preferredProfileId: String? = null,
    provider: ProviderKind? = null,
    model: String? = null,
  ): RouteResult = withContext(Dispatchers.IO) {
    val all = vault.list()
    val preferred = all.firstOrNull { it.id == preferredProfileId }
    val now = System.currentTimeMillis()
    val candidates = buildList {
      if (preferred != null && preferred.enabled) add(preferred)
      all.asSequence()
        .filter { it.id != preferredProfileId }
        .filter { it.enabled && it.cooldownUntil <= now }
        .filter { provider == null || it.provider == provider }
        .filter { model.isNullOrBlank() || it.model.equals(model, ignoreCase = true) }
        .sortedWith(compareBy<ApiKeyProfile> { it.lastUsedAt }.thenBy { it.failures }.thenBy { it.requests })
        .forEach(::add)
    }

    if (candidates.isEmpty()) {
      val providerLabel = provider?.displayName ?: "the selected"
      throw NoRouteException(
        "No enabled route is available for $providerLabel. Add an API key or configure a local model in Keys."
      )
    }

    var lastError: Throwable? = null
    for (route in candidates) {
      try {
        val reply = adapters.complete(route, messages, tools)
        vault.reportSuccess(route.id)
        return@withContext RouteResult(route, reply)
      } catch (error: ProviderHttpException) {
        lastError = error
        // Authentication failures are useful to show, but a bad key should not
        // prevent the next key in a 100+ key pool from being tried.
        vault.reportFailure(route.id, error.retryAfterMs)
      } catch (error: SocketTimeoutException) {
        lastError = error
        vault.reportFailure(route.id)
      } catch (error: IOException) {
        lastError = error
        vault.reportFailure(route.id)
      }
    }

    throw lastError ?: NoRouteException("All configured routes failed")
  }
}

private class ProviderAdapters(private val http: OkHttpClient) {
  fun complete(route: ApiKeyProfile, messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
    return when (route.provider) {
      ProviderKind.ANTHROPIC -> anthropic(route, messages, tools)
      ProviderKind.GOOGLE -> google(route, messages, tools)
      ProviderKind.OLLAMA -> ollama(route, messages, tools)
      else -> openAiCompatible(route, messages, tools)
    }
  }

  private fun openAiCompatible(route: ApiKeyProfile, messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
    val url = route.baseUrl.trimEnd('/') + if (route.baseUrl.trimEnd('/').endsWith("/v1")) "/chat/completions" else "/v1/chat/completions"
    val body = JSONObject().apply {
      put("model", route.displayModel())
      put("messages", openAiMessages(messages))
      put("stream", false)
      put("temperature", 0.2)
      if (tools.isNotEmpty()) put("tools", openAiTools(tools))
      put("tool_choice", "auto")
    }
    val response = execute(
      url = url,
      body = body,
      headers = buildMap {
        if (route.secret.isNotBlank()) put("Authorization", "Bearer ${route.secret}")
      },
    )
    val root = JSONObject(response)
    val choice = root.optJSONArray("choices")?.optJSONObject(0)
      ?: throw IOException("Provider returned no choices")
    val message = choice.optJSONObject("message") ?: JSONObject()
    val calls = mutableListOf<ToolCall>()
    message.optJSONArray("tool_calls")?.let { array ->
      for (index in 0 until array.length()) {
        val call = array.optJSONObject(index) ?: continue
        val fn = call.optJSONObject("function") ?: continue
        calls += ToolCall(
          id = call.optString("id").ifBlank { "call-$index" },
          name = fn.optString("name"),
          arguments = fn.opt("arguments")?.toString() ?: "{}",
        )
      }
    }
    return ModelReply(
      content = jsonText(message.opt("content")),
      toolCalls = calls,
      inputTokens = root.optJSONObject("usage")?.optInt("prompt_tokens"),
      outputTokens = root.optJSONObject("usage")?.optInt("completion_tokens"),
    )
  }

  private fun anthropic(route: ApiKeyProfile, messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
    val system = messages.filter { it.role == ChatMessage.Role.SYSTEM }.joinToString("\n") { it.content }
    val body = JSONObject().apply {
      put("model", route.displayModel())
      put("max_tokens", 4096)
      put("messages", JSONArray().apply {
        messages.filter { it.role != ChatMessage.Role.SYSTEM }.forEach { message ->
          when (message.role) {
            ChatMessage.Role.TOOL -> put(JSONObject().apply {
              put("role", "user")
              put("content", JSONArray().put(JSONObject().apply {
                put("type", "tool_result")
                put("tool_use_id", message.toolCallId.orEmpty())
                put("content", message.content)
              }))
            })
            ChatMessage.Role.ASSISTANT -> put(JSONObject().apply {
              put("role", "assistant")
              if (message.toolCalls.isEmpty()) {
                put("content", message.content)
              } else {
                put("content", JSONArray().apply {
                  if (message.content.isNotBlank()) put(JSONObject().put("type", "text").put("text", message.content))
                  message.toolCalls.forEach { call ->
                    put(JSONObject().apply {
                      put("type", "tool_use")
                      put("id", call.id)
                      put("name", call.name)
                      put("input", JSONObject(call.arguments))
                    })
                  }
                })
              }
            })
            else -> put(JSONObject().apply {
              put("role", "user")
              put("content", message.content)
            })
          }
        }
      })
      if (system.isNotBlank()) put("system", system)
      if (tools.isNotEmpty()) put("tools", JSONArray().apply {
        tools.forEach { tool ->
          put(JSONObject().apply {
            put("name", tool.name)
            put("description", tool.description)
            put("input_schema", JSONObject(tool.parametersJson))
          })
        }
      })
    }
    val response = execute(
      url = route.baseUrl.trimEnd('/') + "/v1/messages",
      body = body,
      headers = mapOf(
        "x-api-key" to route.secret,
        "anthropic-version" to "2023-06-01",
      ),
    )
    val root = JSONObject(response)
    val text = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    root.optJSONArray("content")?.let { blocks ->
      for (index in 0 until blocks.length()) {
        val block = blocks.optJSONObject(index) ?: continue
        when (block.optString("type")) {
          "text" -> text.append(block.optString("text"))
          "tool_use" -> calls += ToolCall(
            id = block.optString("id").ifBlank { "call-$index" },
            name = block.optString("name"),
            arguments = block.optJSONObject("input")?.toString() ?: "{}",
          )
        }
      }
    }
    return ModelReply(text.toString(), calls)
  }

  private fun google(route: ApiKeyProfile, messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
    val body = JSONObject().apply {
      put("contents", JSONArray().apply {
        messages.filter { it.role != ChatMessage.Role.SYSTEM }.forEach { message ->
          val parts = JSONArray()
          when (message.role) {
            ChatMessage.Role.ASSISTANT -> {
              if (message.content.isNotBlank()) parts.put(JSONObject().put("text", message.content))
              message.toolCalls.forEach { call ->
                parts.put(JSONObject().put("functionCall", JSONObject().put("name", call.name).put("args", JSONObject(call.arguments))))
              }
            }
            ChatMessage.Role.TOOL -> parts.put(JSONObject().put("functionResponse", JSONObject()
              .put("name", message.toolName.orEmpty())
              .put("response", JSONObject().put("result", message.content))))
            else -> parts.put(JSONObject().put("text", message.content))
          }
          put(JSONObject().apply {
            put("role", if (message.role == ChatMessage.Role.ASSISTANT) "model" else "user")
            put("parts", parts)
          })
        }
      })
      messages.firstOrNull { it.role == ChatMessage.Role.SYSTEM }?.let {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it.content))))
      }
      if (tools.isNotEmpty()) put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
        tools.forEach { tool ->
          put(JSONObject().apply {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", JSONObject(tool.parametersJson))
          })
        }
      })))
    }
    val encodedModel = route.displayModel().replace("/", "%2F")
    val response = execute(
      url = "${route.baseUrl.trimEnd('/')}/v1beta/models/$encodedModel:generateContent?key=${route.secret}",
      body = body,
      headers = emptyMap(),
    )
    val root = JSONObject(response)
    val parts = root.optJSONArray("candidates")?.optJSONObject(0)
      ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
    val text = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    for (index in 0 until parts.length()) {
      val part = parts.optJSONObject(index) ?: continue
      text.append(part.optString("text"))
      part.optJSONObject("functionCall")?.let { call ->
        calls += ToolCall("call-$index", call.optString("name"), call.optJSONObject("args")?.toString() ?: "{}")
      }
    }
    return ModelReply(text.toString(), calls)
  }

  private fun ollama(route: ApiKeyProfile, messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
    val body = JSONObject().apply {
      put("model", route.displayModel())
      put("messages", openAiMessages(messages))
      put("stream", false)
      if (tools.isNotEmpty()) put("tools", openAiTools(tools))
    }
    val response = execute(route.baseUrl.trimEnd('/') + "/api/chat", body, emptyMap())
    val root = JSONObject(response)
    val message = root.optJSONObject("message") ?: JSONObject()
    val calls = mutableListOf<ToolCall>()
    message.optJSONArray("tool_calls")?.let { array ->
      for (index in 0 until array.length()) {
        val call = array.optJSONObject(index) ?: continue
        val fn = call.optJSONObject("function") ?: continue
        calls += ToolCall("call-$index", fn.optString("name"), fn.opt("arguments")?.toString() ?: "{}")
      }
    }
    return ModelReply(jsonText(message.opt("content")), calls)
  }

  private fun execute(url: String, body: JSONObject, headers: Map<String, String>): String {
    val requestBuilder = Request.Builder()
      .url(url)
      .post(body.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
      .header("Accept", "application/json")
    headers.forEach { (key, value) -> requestBuilder.header(key, value) }
    http.newCall(requestBuilder.build()).execute().use { response ->
      val content = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val retry = response.header("Retry-After")?.toLongOrNull()?.times(1000L)
        throw ProviderHttpException(response.code, retry, "HTTP ${response.code}: ${content.take(500)}")
      }
      return content
    }
  }

  private fun openAiMessages(messages: List<ChatMessage>): JSONArray = JSONArray().apply {
    messages.forEach { message ->
      put(JSONObject().apply {
        put("role", when (message.role) {
          ChatMessage.Role.SYSTEM -> "system"
          ChatMessage.Role.USER -> "user"
          ChatMessage.Role.ASSISTANT -> "assistant"
          ChatMessage.Role.TOOL -> "tool"
        })
        put("content", message.content)
        message.toolCallId?.let { put("tool_call_id", it) }
        message.toolName?.let { put("name", it) }
        if (message.toolCalls.isNotEmpty()) {
          put("tool_calls", JSONArray().apply {
            message.toolCalls.forEach { call ->
              put(JSONObject().apply {
                put("id", call.id)
                put("type", "function")
                put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
              })
            }
          })
        }
      })
    }
  }

  private fun openAiTools(tools: List<ToolSpec>): JSONArray = JSONArray().apply {
    tools.forEach { tool ->
      put(JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
          put("name", tool.name)
          put("description", tool.description)
          put("parameters", JSONObject(tool.parametersJson))
        })
      })
    }
  }

  private fun jsonText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is JSONArray -> (0 until value.length()).joinToString("") { value.optJSONObject(it)?.optString("text").orEmpty() }
    else -> value.toString()
  }
}
