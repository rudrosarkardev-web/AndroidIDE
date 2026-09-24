/*
 * Codex for Android — main activity.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android

import ai.codex.android.ui.CodexScreen
import ai.codex.android.ui.CodexTheme
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
  private val viewModel by viewModels<CodexViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleRunIntent(intent)
    setContent {
      CodexTheme {
        CodexScreen(viewModel)
      }
    }
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    if (intent != null) handleRunIntent(intent)
  }

  private fun handleRunIntent(intent: Intent) {
    if (intent.action == ACTION_RUN) {
      val prompt = intent.getStringExtra(Intent.EXTRA_TEXT)
        ?: intent.getStringExtra(EXTRA_PROMPT)
      if (!prompt.isNullOrBlank()) viewModel.send(prompt)
    }
  }

  companion object {
    private const val ACTION_RUN = "ai.codex.android.action.RUN"
    private const val EXTRA_PROMPT = "prompt"
  }
}
