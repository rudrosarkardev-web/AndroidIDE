/*
 * Codex for Android — application level singletons.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.core

import android.content.Context

object AppContainer {

  lateinit var context: Context
    private set

  fun init(context: Context) {
    this.context = context.applicationContext
  }
}
