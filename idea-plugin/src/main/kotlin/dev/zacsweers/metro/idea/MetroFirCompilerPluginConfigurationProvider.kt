// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.fir.extensions.KotlinFirCompilerPluginConfigurationForIdeProvider

/**
 * Supplies IDE-side Kotlin compiler plugin configuration for Metro projects.
 *
 * Metro does not publish shareable compiler option keys yet, so this currently only identifies the
 * Metro compiler plugin and returns an empty configuration copy.
 */
class MetroFirCompilerPluginConfigurationProvider :
  KotlinFirCompilerPluginConfigurationForIdeProvider {

  override fun provideCompilerConfigurationWithCustomOptions(
    original: CompilerConfiguration
  ): CompilerConfiguration {
    // Keep this empty until MetroOptions and its configuration keys move to a shared artifact.
    return original.copy()
  }

  @OptIn(ExperimentalCompilerApi::class)
  override fun isConfigurationProviderForCompilerPlugin(
    registrar: CompilerPluginRegistrar
  ): Boolean {
    return registrar.pluginId == PLUGIN_ID
  }
}
