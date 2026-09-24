/*
 * Codex for Android — dark coding workspace theme.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val CodexTypography = Typography().run {
  copy(
    bodyLarge = bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
    bodyMedium = bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
    labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
  )
}

private val CodexColors = darkColorScheme(
  primary = Color(0xFF8BD5FF),
  onPrimary = Color(0xFF00202B),
  primaryContainer = Color(0xFF123B4A),
  onPrimaryContainer = Color(0xFFC1EBFF),
  secondary = Color(0xFFA7C8D6),
  onSecondary = Color(0xFF0A2029),
  secondaryContainer = Color(0xFF233A44),
  onSecondaryContainer = Color(0xFFC3E8F5),
  tertiary = Color(0xFFE2C1FF),
  background = Color(0xFF0D0D0D),
  onBackground = Color(0xFFE6E2E3),
  surface = Color(0xFF141414),
  onSurface = Color(0xFFE6E2E3),
  surfaceVariant = Color(0xFF242424),
  onSurfaceVariant = Color(0xFFC4C6C7),
  outline = Color(0xFF41484C),
  error = Color(0xFFFFB4AB),
)

@Composable
fun CodexTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = CodexColors,
    typography = CodexTypography,
    content = content,
  )
}
