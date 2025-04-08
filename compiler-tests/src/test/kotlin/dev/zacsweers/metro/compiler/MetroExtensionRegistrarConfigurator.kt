// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.fir.ExtensionPredicates
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.interop.configureAnvilAnnotations
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configurePlugin() {
  useConfigurators(::MetroExtensionRegistrarConfigurator, ::MetroRuntimeEnvironmentConfigurator)

  useDirectives(MetroDirectives)

  useCustomRuntimeClasspathProviders(::MetroRuntimeClassPathProvider)

  useSourcePreprocessor(::MetroDefaultImportPreprocessor)

  configureAnvilAnnotations()
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
        customGraphAnnotations =
          buildSet {
            if (MetroDirectives.WITH_ANVIL in module.directives) {
              add(ClassId.fromString("com/squareup/anvil/annotations/MergeComponent"))
            }
          },
        customGraphFactoryAnnotations =
          buildSet {
            if (MetroDirectives.WITH_ANVIL in module.directives) {
              add(ClassId.fromString("com/squareup/anvil/annotations/MergeComponent.Factory"))
            }
          },
        customContributesToAnnotations =
          buildSet {
            if (MetroDirectives.WITH_ANVIL in module.directives) {
              add(ClassId.fromString("com/squareup/anvil/annotations/ContributesTo"))
            }
          },
        customContributesBindingAnnotations =
          buildSet {
            if (MetroDirectives.WITH_ANVIL in module.directives) {
              add(ClassId.fromString("com/squareup/anvil/annotations/ContributesBinding"))
            }
          },
        customContributesIntoSetAnnotations =
          buildSet {
            if (MetroDirectives.WITH_ANVIL in module.directives) {
              add(ClassId.fromString("com/squareup/anvil/annotations/ContributesMultibinding"))
            }
          },
      )
    val classIds = ClassIds.fromOptions(options)
    val predicates = ExtensionPredicates(classIds)
    FirExtensionRegistrarAdapter.registerExtension(
      MetroFirExtensionRegistrar(classIds, predicates, options)
    )
    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(configuration.messageCollector, classIds, options)
    )
  }
}
