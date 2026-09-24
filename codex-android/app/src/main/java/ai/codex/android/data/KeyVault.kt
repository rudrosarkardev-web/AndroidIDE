/*
 * Codex for Android — encrypted API-key vault and rotation policy.
 *
 * Keys are encrypted with an AES-GCM key held by Android Keystore. The encrypted
 * record is the only value written to SharedPreferences; provider clients never
 * receive the vault's storage details.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyVault(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val lock = Any()

  fun list(): List<ApiKeyProfile> = synchronized(lock) { read().sortedWith(compareBy<ApiKeyProfile> { !it.enabled }.thenBy { it.label.lowercase() }) }

  fun add(profile: ApiKeyProfile): ApiKeyProfile = synchronized(lock) {
    val next = (read() + profile).distinctBy { it.id }
    write(next)
    profile
  }

  fun update(profile: ApiKeyProfile) = synchronized(lock) {
    write(read().map { if (it.id == profile.id) profile else it })
  }

  fun remove(id: String) = synchronized(lock) {
    write(read().filterNot { it.id == id })
  }

  fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
    write(read().map { if (it.id == id) it.copy(enabled = enabled) else it })
  }

  /**
   * Pick the least recently used healthy route. A route is considered a key slot,
   * so 100+ uploaded keys are rotated independently rather than overwritten.
   */
  fun next(provider: ProviderKind? = null, model: String? = null): ApiKeyProfile? = synchronized(lock) {
    val now = System.currentTimeMillis()
    read()
      .asSequence()
      .filter { it.enabled && it.cooldownUntil <= now }
      .filter { provider == null || it.provider == provider }
      .filter { model.isNullOrBlank() || it.model.equals(model, ignoreCase = true) }
      .sortedWith(compareBy<ApiKeyProfile> { it.lastUsedAt }.thenBy { it.failures }.thenBy { it.requests })
      .firstOrNull()
  }

  fun availableCount(provider: ProviderKind? = null): Int = synchronized(lock) {
    val now = System.currentTimeMillis()
    read().count { it.enabled && it.cooldownUntil <= now && (provider == null || it.provider == provider) }
  }

  fun reportSuccess(id: String) = synchronized(lock) {
    write(read().map {
      if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis(), cooldownUntil = 0L, failures = 0, requests = it.requests + 1)
      else it
    })
  }

  /** Mark rate-limited routes unavailable briefly and rotate to the next route. */
  fun reportFailure(id: String, retryAfterMs: Long? = null) = synchronized(lock) {
    val now = System.currentTimeMillis()
    write(read().map {
      if (it.id == id) {
        val nextFailure = it.failures + 1
        val backoff = retryAfterMs?.coerceIn(1_000L, 30 * 60_000L)
          ?: (1_000L * (1L shl nextFailure.coerceAtMost(10))).coerceAtMost(10 * 60_000L)
        it.copy(failures = nextFailure, cooldownUntil = now + backoff, requests = it.requests + 1)
      } else it
    })
  }

  /** Import one record per line: provider|label|api-key|base-url|model. */
  fun importBulk(input: String): Int = synchronized(lock) {
    val existing = read().toMutableList()
    val imported = mutableListOf<ApiKeyProfile>()
    val trimmed = input.trim()
    if (trimmed.isBlank()) return@synchronized 0

    if (trimmed.startsWith("[")) {
      val array = JSONArray(trimmed)
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        parseJsonProfile(item)?.let(imported::add)
      }
    } else if (trimmed.startsWith("{")) {
      val root = JSONObject(trimmed)
      val array = root.optJSONArray("keys") ?: JSONArray().put(root)
      for (index in 0 until array.length()) {
        parseJsonProfile(array.optJSONObject(index) ?: continue)?.let(imported::add)
      }
    } else {
      trimmed.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
          val parts = line.split('|', limit = 5)
          if (parts.size >= 3) {
            val provider = ProviderKind.fromStored(parts[0].trim().uppercase())
            imported += ApiKeyProfile(
              label = parts[1].trim().ifBlank { "${provider.displayName} key" },
              provider = provider,
              secret = parts[2].trim(),
              baseUrl = parts.getOrNull(3)?.trim().orEmpty().ifBlank { provider.defaultBaseUrl() },
              model = parts.getOrNull(4)?.trim().orEmpty().ifBlank { provider.defaultModel() },
            )
          }
        }
    }

    val fresh = imported.filter { it.secret.isNotBlank() || !it.provider.needsSecret }
    existing += fresh
    write(existing.distinctBy { it.id })
    fresh.size
  }

  fun exportRedacted(): String = synchronized(lock) {
    JSONArray().apply {
      read().forEach { profile ->
        put(JSONObject().apply {
          put("id", profile.id)
          put("label", profile.label)
          put("provider", profile.provider.name)
          put("model", profile.model)
          put("baseUrl", profile.baseUrl)
          put("enabled", profile.enabled)
          put("secret", profile.maskedSecret)
        })
      }
    }.toString(2)
  }

  private fun parseJsonProfile(item: JSONObject): ApiKeyProfile? {
    val provider = ProviderKind.fromStored(item.optString("provider", item.optString("kind", "OPENAI_COMPATIBLE")))
    val secret = item.optString("secret", item.optString("apiKey", ""))
    if (secret.isBlank() && provider.needsSecret) return null
    return ApiKeyProfile(
      id = item.optString("id", UUID.randomUUID().toString()),
      label = item.optString("label", item.optString("name", "${provider.displayName} key")),
      provider = provider,
      secret = secret,
      baseUrl = item.optString("baseUrl", provider.defaultBaseUrl()),
      model = item.optString("model", provider.defaultModel()),
      enabled = item.optBoolean("enabled", true),
    )
  }

  private fun read(): List<ApiKeyProfile> {
    val blob = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(decrypt(blob))
      buildList {
        for (index in 0 until array.length()) {
          val o = array.getJSONObject(index)
          val provider = ProviderKind.fromStored(o.optString("provider"))
          add(ApiKeyProfile(
            id = o.optString("id", UUID.randomUUID().toString()),
            label = o.optString("label", "API key"),
            provider = provider,
            secret = o.optString("secret", ""),
            baseUrl = o.optString("baseUrl", provider.defaultBaseUrl()),
            model = o.optString("model", provider.defaultModel()),
            enabled = o.optBoolean("enabled", true),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastUsedAt = o.optLong("lastUsedAt", 0L),
            cooldownUntil = o.optLong("cooldownUntil", 0L),
            failures = o.optInt("failures", 0),
            requests = o.optLong("requests", 0L),
          ))
        }
      }
    }.getOrElse { emptyList() }
  }

  private fun write(profiles: List<ApiKeyProfile>) {
    val array = JSONArray()
    profiles.forEach { profile ->
      array.put(JSONObject().apply {
        put("id", profile.id)
        put("label", profile.label)
        put("provider", profile.provider.name)
        put("secret", profile.secret)
        put("baseUrl", profile.baseUrl)
        put("model", profile.model)
        put("enabled", profile.enabled)
        put("createdAt", profile.createdAt)
        put("lastUsedAt", profile.lastUsedAt)
        put("cooldownUntil", profile.cooldownUntil)
        put("failures", profile.failures)
        put("requests", profile.requests)
      })
    }
    prefs.edit().putString(KEY_RECORDS, encrypt(array.toString())).apply()
  }

  private fun key(): SecretKey {
    val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .build())
    return generator.generateKey()
  }

  private fun encrypt(value: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val iv = cipher.iv
    val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
  }

  private fun decrypt(value: String): String {
    val all = Base64.decode(value, Base64.NO_WRAP)
    val iv = all.copyOfRange(0, 12)
    val encrypted = all.copyOfRange(12, all.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
    return cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
  }

  companion object {
    private const val PREFS = "codex_secure_vault"
    private const val KEY_RECORDS = "encrypted_profiles"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "codex_api_vault_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}
