// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.isJvm

/** Keeps FIR and IR in agreement about which phase owns native contribution hints. */
internal fun MetroOptions.useJvmIrContributionHints(
  platform: TargetPlatform?,
  languageVersionSettings: LanguageVersionSettings,
): Boolean {
  if (!generateJvmContributionHintsInIr || !platform.isJvm()) {
    return false
  }
  if (!generateContributionHints || !generateContributionHintsInFir) {
    return false
  }
  if (!generateClassesInIr || generateContributionProviders) {
    return false
  }
  // Common metadata and platform compilations must agree on generated declarations.
  return !languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects)
}
