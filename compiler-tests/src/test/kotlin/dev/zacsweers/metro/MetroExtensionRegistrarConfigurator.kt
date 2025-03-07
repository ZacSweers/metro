// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configurePlugin() {
  useConfigurators(::MetroExtensionRegistrarConfigurator, ::RuntimeEnvironmentConfigurator)

  useDirectives(MetroDirectives)

  useCustomRuntimeClasspathProviders(::MetroRuntimeClassPathProvider)

  useSourcePreprocessor(::MetroDefaultImportPreprocessor)
}

class MetroExtensionRegistrarConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  @OptIn(ExperimentalCompilerApi::class)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    val options =
      MetroOptions(
        generateAssistedFactories =
          MetroDirectives.GENERATE_ASSISTED_FACTORIES in module.directives,
        publicProviderSeverity =
          module.directives.singleOrZeroValue(MetroDirectives.PUBLIC_PROVIDER_SEVERITY)
            ?: MetroOptions.DiagnosticSeverity.NONE,
      )
    val classIds = ClassIds()
    FirExtensionRegistrarAdapter.registerExtension(MetroFirExtensionRegistrar(classIds, options))
    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(configuration.messageCollector, classIds, options)
    )
  }
}
