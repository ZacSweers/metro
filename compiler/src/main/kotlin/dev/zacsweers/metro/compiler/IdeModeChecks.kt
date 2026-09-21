// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings

private val MIN_KOTLIN_IDE_MODE_VERSION = KotlinToolingVersion("2.4.20")

private val LEGACY_IS_IDE: Boolean by lazy {
  try {
    // Try to look up an IntelliJ-only class
    Class.forName("org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession")
    true
  } catch (_: ClassNotFoundException) {
    false
  }
}

/**
 * Previously we'd just look for an IJ-only class, but per
 * https://github.com/ZacSweers/metro/issues/2845 that may not always be reliable. Instead on newer
 * versions we use [AnalysisFlags].
 */
internal fun CompilerConfiguration.isIdeMode(
  compilerVersion: KotlinToolingVersion?,
  legacyIdeDetection: () -> Boolean = { LEGACY_IS_IDE },
): Boolean {
  // Older IDEs don't populate the language settings passed to compiler plugins.
  if (compilerVersion == null || compilerVersion < MIN_KOTLIN_IDE_MODE_VERSION) {
    return legacyIdeDetection()
  }
  return languageVersionSettings.getFlag(AnalysisFlags.ideMode)
}
