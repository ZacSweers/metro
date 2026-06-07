// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
  ExperimentalCompilerApi::class,
  CompilerConfiguration.Internals::class,
)

package dev.zacsweers.metro.idea

import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
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
  fun readsDefaultEnabledOption() {
    assertTrue(MetroIdeOptions.load(CompilerConfiguration()).enabled)
  }

  @Test
  fun readsDisabledEnabledOption() {
    assertFalse(
      MetroIdeOptions.load(CompilerConfiguration().apply { put(METRO_ENABLED_OPTION_KEY, false) })
        .enabled
    )
  }

  @Test
  fun preservesReadEnabledOptionInReturnedConfiguration() {
    val provider = MetroFirCompilerPluginConfigurationProvider()
    val original = CompilerConfiguration().apply { put(METRO_ENABLED_OPTION_KEY, false) }

    assertFalse(MetroIdeOptions.load(original).enabled)
    assertTrue(provider.isConfigurationProviderForCompilerPlugin(MetroCompilerPluginRegistrarStub))

    val configuration = provider.provideCompilerConfigurationWithCustomOptions(original)

    assertNotSame(original, configuration)
    assertFalse(MetroIdeOptions.load(original).enabled)
    assertFalse(MetroIdeOptions.load(configuration).enabled)
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
