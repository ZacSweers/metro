// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.interop.configureAnvilAnnotations
import dev.zacsweers.metro.compiler.interop.configureDaggerAnnotations
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
  configureDaggerAnnotations()
}

class MetroExtensionRegistrarConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  @OptIn(ExperimentalCompilerApi::class)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    val transformProvidersToPrivate =
      MetroDirectives.DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE !in module.directives
    val options =
      MetroOptions(
        generateAssistedFactories =
          MetroDirectives.GENERATE_ASSISTED_FACTORIES in module.directives,
        transformProvidersToPrivate = transformProvidersToPrivate,
        publicProviderSeverity =
          if (transformProvidersToPrivate) {
            MetroOptions.DiagnosticSeverity.NONE
          } else {
            module.directives.singleOrZeroValue(MetroDirectives.PUBLIC_PROVIDER_SEVERITY)
              ?: MetroOptions.DiagnosticSeverity.NONE
          },
        customGraphAnnotations =
          buildSet {
            if (MetroDirectives.WITH_ANVIL in module.directives) {
              add(ClassId.fromString("com/squareup/anvil/annotations/MergeComponent"))
            }
            if (MetroDirectives.WITH_DAGGER in module.directives) {
              add(ClassId.fromString("dagger/Component"))
            }
          },
        customGraphFactoryAnnotations =
          buildSet {
            if (MetroDirectives.WITH_ANVIL in module.directives) {
              add(ClassId.fromString("com/squareup/anvil/annotations/MergeComponent.Factory"))
            }
            if (MetroDirectives.WITH_DAGGER in module.directives) {
              add(ClassId.fromString("dagger/Component.Factory"))
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
        customInjectAnnotations =
          buildSet {
            if (MetroDirectives.WITH_DAGGER in module.directives) {
              add(ClassId.fromString("javax/inject/Inject"))
              add(ClassId.fromString("jakarta/inject/Inject"))
            }
          },
        customProviderTypes =
          buildSet {
            if (MetroDirectives.WITH_DAGGER in module.directives) {
              add(ClassId.fromString("javax/inject/Provider"))
              add(ClassId.fromString("jakarta/inject/Provider"))
            }
          },
        customProvidesAnnotations =
          buildSet {
            if (MetroDirectives.WITH_DAGGER in module.directives) {
              add(ClassId.fromString("dagger/Provides"))
            }
          },
        customBindsAnnotations =
          buildSet {
            if (MetroDirectives.WITH_DAGGER in module.directives) {
              add(ClassId.fromString("dagger/Binds"))
            }
          },
        customLazyTypes =
          buildSet {
            if (MetroDirectives.WITH_DAGGER in module.directives) {
              add(ClassId.fromString("dagger/Lazy"))
            }
          },
        // TODO other dagger annotations/types not yet implemented
      )
    val classIds = ClassIds.fromOptions(options)
    FirExtensionRegistrarAdapter.registerExtension(MetroFirExtensionRegistrar(classIds, options))
    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(
        messageCollector = configuration.messageCollector,
        classIds = classIds,
        options = options,
        // TODO ever support this in tests?
        lookupTracker = null,
      )
    )
  }
}
