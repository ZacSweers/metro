// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.test.TestInfrastructureInternals
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.DefaultsProvider
import org.jetbrains.kotlin.test.services.ModuleStructureTransformer
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.impl.TestModuleStructureImpl

/** Applies feature overrides before FIR and IR read each module's effective language settings. */
@OptIn(TestInfrastructureInternals::class)
internal class MetroCompanionFeaturesTransformer(private val compatContext: CompatContext) :
  ModuleStructureTransformer() {
  override fun transformModuleStructure(
    moduleStructure: TestModuleStructure,
    defaultsProvider: DefaultsProvider,
  ): TestModuleStructure {
    val transformedModules = mutableMapOf<String, TestModule>()
    val modules =
      moduleStructure.modules.map { module ->
        val features = buildMap {
          if (compatContext.supportsCompanionBlocks) {
            module.directives[MetroDirectives.COMPANION_BLOCKS].lastOrNull()?.let {
              put(featureNamed("CompanionBlocks"), it.toFeatureState())
            }
          }
          if (compatContext.supportsCompanionExtensions) {
            module.directives[MetroDirectives.COMPANION_EXTENSIONS].lastOrNull()?.let {
              put(featureNamed("CompanionExtensions"), it.toFeatureState())
            }
          }
        }
        val settings =
          if (features.isEmpty()) {
            module.languageVersionSettings
          } else {
            CompanionFeatureSettings(module.languageVersionSettings, features)
          }
        // Modules arrive in dependency order, so every dependency points at its updated settings.
        val dependencies =
          module.allDependencies.map { dependency ->
            dependency.copy(
              dependencyModule = transformedModules.getValue(dependency.dependencyModule.name)
            )
          }
        module.copy(allDependencies = dependencies, languageVersionSettings = settings).also {
          transformedModules[it.name] = it
        }
      }
    return TestModuleStructureImpl(modules, moduleStructure.originalTestDataFiles)
  }

  private fun featureNamed(name: String): LanguageFeature {
    // Availability checks keep older compilers from resolving new language-feature names.
    return LanguageFeature.entries.first { it.name == name }
  }

  private fun Boolean.toFeatureState(): LanguageFeature.State {
    return if (this) {
      LanguageFeature.State.ENABLED
    } else {
      LanguageFeature.State.DISABLED
    }
  }
}

/** Delegation preserves analysis flags and version settings while overriding feature support. */
private class CompanionFeatureSettings(
  private val delegate: LanguageVersionSettings,
  private val features: Map<LanguageFeature, LanguageFeature.State>,
) : LanguageVersionSettings by delegate {
  override fun getFeatureSupport(feature: LanguageFeature): LanguageFeature.State {
    return features[feature] ?: delegate.getFeatureSupport(feature)
  }

  override fun supportsFeature(feature: LanguageFeature): Boolean {
    val overriddenState = features[feature] ?: return delegate.supportsFeature(feature)
    return overriddenState == LanguageFeature.State.ENABLED
  }

  override fun getCustomizedLanguageFeatures(): Map<LanguageFeature, LanguageFeature.State> {
    return delegate.getCustomizedLanguageFeatures() + features
  }
}
