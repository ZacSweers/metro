// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.interop.Ksp2AdditionalSourceProvider
import dev.zacsweers.metro.compiler.interop.configureAnvilAnnotations
import dev.zacsweers.metro.compiler.interop.configureDaggerAnnotations
import dev.zacsweers.metro.compiler.interop.configureDaggerInterop
import dev.zacsweers.metro.compiler.interop.configureGuiceInterop
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import kotlin.io.path.Path
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager

fun TestConfigurationBuilder.configurePlugin() {
  useConfigurators(::MetroExtensionRegistrarConfigurator, ::MetroRuntimeEnvironmentConfigurator)

  useDirectives(MetroDirectives)

  useCustomRuntimeClasspathProviders(::MetroRuntimeClassPathProvider)

  useSourcePreprocessor(::MetroDefaultImportPreprocessor)

  configureAnvilAnnotations()
  configureDaggerAnnotations()
  configureDaggerInterop()
  configureGuiceInterop()
  useAdditionalSourceProviders(::Ksp2AdditionalSourceProvider)
}

class MetroExtensionRegistrarConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  @OptIn(ExperimentalCompilerApi::class)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    val options =
      MetroOptions.buildOptions {
        // Set non-annotation properties (only when directive is present or value is non-default)
        enabled = MetroDirectives.DISABLE_METRO !in module.directives
        generateAssistedFactories = MetroDirectives.GENERATE_ASSISTED_FACTORIES in module.directives
        transformProvidersToPrivate =
          MetroDirectives.DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE !in module.directives
        enableTopLevelFunctionInjection =
          MetroDirectives.ENABLE_TOP_LEVEL_FUNCTION_INJECTION in module.directives
        module.directives.singleOrZeroValue(MetroDirectives.SHRINK_UNUSED_BINDINGS)?.let {
          shrinkUnusedBindings = it
        }
        module.directives.singleOrZeroValue(MetroDirectives.CHUNK_FIELD_INITS)?.let {
          chunkFieldInits = it
        }
        enableFullBindingGraphValidation =
          MetroDirectives.ENABLE_FULL_BINDING_GRAPH_VALIDATION in module.directives
        enableGraphImplClassAsReturnType =
          MetroDirectives.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE in module.directives
        generateContributionHints =
          module.directives.singleOrZeroValue(MetroDirectives.GENERATE_CONTRIBUTION_HINTS) ?: true
        generateContributionHintsInFir =
          MetroDirectives.GENERATE_CONTRIBUTION_HINTS_IN_FIR in module.directives
        if (transformProvidersToPrivate) {
          publicProviderSeverity = MetroOptions.DiagnosticSeverity.NONE
        } else {
          module.directives.singleOrZeroValue(MetroDirectives.PUBLIC_PROVIDER_SEVERITY)?.let {
            publicProviderSeverity = it
          }
        }
        module.directives.singleOrZeroValue(MetroDirectives.OPTIONAL_DEPENDENCY_BEHAVIOR)?.let {
          optionalBindingBehavior = it
        }
        module.directives
          .singleOrZeroValue(MetroDirectives.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY)
          ?.let { interopAnnotationsNamedArgSeverity = it }
        module.directives.singleOrZeroValue(MetroDirectives.MAX_IR_ERRORS_COUNT)?.let {
          maxIrErrorsCount = it
        }
        module.directives.singleOrZeroValue(MetroDirectives.REPORTS_DESTINATION)?.let {
          reportsDestination =
            Path("${testServices.temporaryDirectoryManager.rootDir.absolutePath}/$it")
        }
        contributesAsInject = MetroDirectives.CONTRIBUTES_AS_INJECT in module.directives

        // Configure interop annotations using builder helper methods
        if (MetroDirectives.WITH_KI_ANVIL in module.directives) {
          includeKotlinInjectAnvilAnnotations()
        } else if (
          MetroDirectives.WITH_ANVIL in module.directives ||
            MetroDirectives.ENABLE_ANVIL_KSP in module.directives
        ) {
          includeAnvilAnnotations()
        }

        if (
          MetroDirectives.WITH_DAGGER in module.directives ||
            MetroDirectives.ENABLE_DAGGER_INTEROP in module.directives ||
            MetroDirectives.ENABLE_DAGGER_KSP in module.directives
        ) {
          includeDaggerAnnotations()
        }

        if (MetroDirectives.enableGuiceAnnotations(module.directives)) {
          includeGuiceAnnotations()
        }

        // Override enableDaggerRuntimeInterop if needed
        if (MetroDirectives.enableDaggerRuntimeInterop(module.directives)) {
          enableDaggerRuntimeInterop = true
        }

        // Override enableGuiceRuntimeInterop if needed
        if (MetroDirectives.enableGuiceInterop(module.directives)) {
          enableGuiceRuntimeInterop = true
        }
      }

    if (!options.enabled) return

    val classIds = ClassIds.fromOptions(options)
    val compatContext = CompatContext.getInstance()
    FirExtensionRegistrarAdapter.registerExtension(
      MetroFirExtensionRegistrar(classIds, options, compatContext)
    )
    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(
        messageCollector = configuration.messageCollector,
        classIds = classIds,
        options = options,
        // TODO ever support this in tests?
        lookupTracker = null,
        expectActualTracker = ExpectActualTracker.DoNothing,
        compatContext = compatContext,
      )
    )
  }
}
