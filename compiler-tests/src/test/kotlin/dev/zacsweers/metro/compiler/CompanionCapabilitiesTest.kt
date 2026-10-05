// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Exercises feature queries against both older adapters and the selected compiler. */
class CompanionCapabilitiesTest {
  @Test
  fun olderAdapterReportsUnavailableAndDisabled() {
    val compatContext = CompatContext.create(KotlinToolingVersion("2.3.0"))
    val settings = settings()
    assertFalse(compatContext.supportsCompanionBlocks)
    assertFalse(compatContext.supportsCompanionExtensions)
    with(compatContext) {
      assertFalse(settings.companionBlocksEnabledCompat())
      assertFalse(settings.companionExtensionsEnabledCompat())
    }
  }

  @Test
  fun availableFeaturesRemainDisabledByDefault() {
    val compatContext = currentCompanionContext()
    with(compatContext) {
      assertFalse(settings().companionBlocksEnabledCompat())
      assertFalse(settings().companionExtensionsEnabledCompat())
    }
  }

  @Test
  fun blocksCanBeEnabledWithoutExtensions() {
    val compatContext = currentCompanionContext()
    val settings = settings("CompanionBlocks")
    with(compatContext) {
      assertTrue(settings.companionBlocksEnabledCompat())
      assertFalse(settings.companionExtensionsEnabledCompat())
    }
  }

  @Test
  fun combinedFeatureStateEnablesBlocksAndExtensions() {
    val compatContext = currentCompanionContext()
    val settings = settings("CompanionBlocks", "CompanionExtensions")
    with(compatContext) {
      assertTrue(settings.companionBlocksEnabledCompat())
      assertTrue(settings.companionExtensionsEnabledCompat())
    }
  }

  private fun currentCompanionContext(): CompatContext {
    val currentVersion = CompatContext.Factory.loadCompilerVersion()
    assumeTrue(currentVersion >= KotlinToolingVersion("2.5.0-Beta1"))
    val compatContext = CompatContext.create()
    assertTrue(compatContext.supportsCompanionBlocks)
    assertTrue(compatContext.supportsCompanionExtensions)
    return compatContext
  }

  private fun settings(vararg enabledFeatureNames: String): LanguageVersionSettingsImpl {
    // Feature names keep these tests loadable on compiler versions that lack the new enum entries.
    val features = enabledFeatureNames.associate { name ->
      LanguageFeature.entries.first { it.name == name } to LanguageFeature.State.ENABLED
    }
    return LanguageVersionSettingsImpl(
      LanguageVersion.LATEST_STABLE,
      ApiVersion.LATEST_STABLE,
      specificFeatures = features,
    )
  }
}
