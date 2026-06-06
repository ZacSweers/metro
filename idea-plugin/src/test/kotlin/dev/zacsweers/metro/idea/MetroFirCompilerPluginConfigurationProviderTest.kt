// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
  ExperimentalCompilerApi::class,
  CompilerConfiguration.Internals::class,
)

package dev.zacsweers.metro.idea

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroFirCompilerPluginConfigurationProviderTest {

  @Test
  fun createsCompilerConfigurationWithoutMetroCompilerClasses() {
    val original = CompilerConfiguration()
    val configuration =
      MetroFirCompilerPluginConfigurationProvider()
        .provideCompilerConfigurationWithCustomOptions(original)

    assertNotSame(original, configuration)
  }

  @Test
  fun matchesMetroCompilerPluginByPluginId() {
    assertTrue(
      MetroFirCompilerPluginConfigurationProvider()
        .isConfigurationProviderForCompilerPlugin(MetroCompilerPluginRegistrarStub)
    )
  }
}

private object MetroCompilerPluginRegistrarStub : CompilerPluginRegistrar() {
  override val pluginId: String = PLUGIN_ID

  override val supportsK2: Boolean = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) = Unit
}
