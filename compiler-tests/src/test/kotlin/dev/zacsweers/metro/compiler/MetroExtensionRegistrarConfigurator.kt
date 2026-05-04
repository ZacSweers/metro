// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import androidx.compose.compiler.plugins.kotlin.k2.ComposeFirExtensionRegistrar
import dev.zacsweers.metro.compiler.api.GenerateBindsContributionExtension
import dev.zacsweers.metro.compiler.api.GenerateBindsContributionMetroExtension
import dev.zacsweers.metro.compiler.api.GenerateDependencyGraphExtension
import dev.zacsweers.metro.compiler.api.GenerateImplContributionExtension
import dev.zacsweers.metro.compiler.api.GenerateImplExtension
import dev.zacsweers.metro.compiler.api.GenerateImplIrExtension
import dev.zacsweers.metro.compiler.api.GenerateProvidersInGraphIrExtension
import dev.zacsweers.metro.compiler.api.GenerateProvidesContributionExtension
import dev.zacsweers.metro.compiler.api.GenerateProvidesContributionIrExtension
import dev.zacsweers.metro.compiler.api.GenerateProvidesContributionMetroExtension
import dev.zacsweers.metro.compiler.api.GenerateProvidesInGraphExtension
import dev.zacsweers.metro.compiler.circuit.CircuitContributionExtension
import dev.zacsweers.metro.compiler.circuit.CircuitFirExtension
import dev.zacsweers.metro.compiler.circuit.CircuitIrExtension
import dev.zacsweers.metro.compiler.circuit.configureCircuit
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
  configureCircuit()
  useAdditionalSourceProviders(::Ksp2AdditionalSourceProvider)
  useAfterAnalysisCheckers(::MetroReportsChecker)
}

class MetroExtensionRegistrarConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  @OptIn(ExperimentalCompilerApi::class)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    val options = MetroOptions.buildOptions {
      // Set non-annotation properties (only when directive is present or value is non-default)
      enabled = MetroDirectives.DISABLE_METRO !in module.directives
      generateAssistedFactories = MetroDirectives.GENERATE_ASSISTED_FACTORIES in module.directives
      enableTopLevelFunctionInjection =
        MetroDirectives.ENABLE_TOP_LEVEL_FUNCTION_INJECTION in module.directives
      module.directives.singleOrZeroValue(MetroDirectives.SHRINK_UNUSED_BINDINGS)?.let {
        shrinkUnusedBindings = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.STATEMENTS_PER_INIT_FUN)?.let {
        statementsPerInitFun = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.ENABLE_GRAPH_SHARDING)?.let {
        enableGraphSharding = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.KEYS_PER_GRAPH_SHARD)?.let {
        keysPerGraphShard = it
      }
      enableSwitchingProviders =
        // Weird but necessary because we may set a default in default configurations that we
        // override in the test, so just take the last one from the file
        module.directives[MetroDirectives.ENABLE_SWITCHING_PROVIDERS]
          .lastOrNull()
          ?.toString()
          ?.toBoolean() ?: false
      enableFullBindingGraphValidation =
        MetroDirectives.ENABLE_FULL_BINDING_GRAPH_VALIDATION in module.directives
      enableGraphImplClassAsReturnType =
        MetroDirectives.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE in module.directives
      generateContributionHints =
        module.directives.singleOrZeroValue(MetroDirectives.GENERATE_CONTRIBUTION_HINTS) ?: true
      generateContributionHintsInFir =
        MetroDirectives.GENERATE_CONTRIBUTION_HINTS_IN_FIR in module.directives
      module.directives.singleOrZeroValue(MetroDirectives.PUBLIC_SCOPED_PROVIDER_SEVERITY)?.let {
        publicScopedProviderSeverity = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.OPTIONAL_DEPENDENCY_BEHAVIOR)?.let {
        optionalBindingBehavior = it
      }
      module.directives
        .singleOrZeroValue(MetroDirectives.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY)
        ?.let { interopAnnotationsNamedArgSeverity = it }
      module.directives.singleOrZeroValue(MetroDirectives.NON_PUBLIC_CONTRIBUTION_SEVERITY)?.let {
        nonPublicContributionSeverity = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.UNUSED_GRAPH_INPUTS_SEVERITY)?.let {
        unusedGraphInputsSeverity = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.MAX_IR_ERRORS_COUNT)?.let {
        maxIrErrorsCount = it
      }
      // Use explicit REPORTS_DESTINATION or default if CHECK_REPORTS is present
      val reportsDir =
        module.directives.singleOrZeroValue(MetroDirectives.REPORTS_DESTINATION)
          ?: if (module.directives[MetroDirectives.CHECK_REPORTS].isNotEmpty()) {
            MetroReportsChecker.DEFAULT_REPORTS_DIR
          } else {
            null
          }
      reportsDir?.let {
        reportsDestination =
          Path("${testServices.temporaryDirectoryManager.rootDir.absolutePath}/$it")
      }
      module.directives.singleOrZeroValue(MetroDirectives.PARALLEL_THREADS)?.let {
        parallelThreads = it
      }
      contributesAsInject = MetroDirectives.CONTRIBUTES_AS_INJECT in module.directives
      module.directives.singleOrZeroValue(MetroDirectives.DESUGARED_PROVIDER_SEVERITY)?.let {
        desugaredProviderSeverity = it
      }
      enableKClassToClassInterop =
        MetroDirectives.ENABLE_KCLASS_TO_CLASS_INTEROP in module.directives

      generateContributionProviders =
        // Weird but necessary because we may set a default in default configurations that we
        // override in the test, so just take the last one from the file
        module.directives[MetroDirectives.GENERATE_CONTRIBUTION_PROVIDERS]
          .lastOrNull()
          ?.toString()
          ?.toBoolean() ?: false

      // Configure interop annotations using builder helper methods
      if (MetroDirectives.WITH_KI_ANVIL in module.directives) {
        includeKotlinInjectAnvilAnnotations()
      }
      if (
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

      if (MetroDirectives.ENABLE_CIRCUIT in module.directives) {
        enableCircuitCodegen = true
      }
    }

    if (!options.enabled) return

    val classIds = ClassIds.fromOptions(options)
    val compatContext = CompatContext.create()
    FirExtensionRegistrarAdapter.registerExtension(
      MetroFirExtensionRegistrar(
        classIds = classIds,
        options = options,
        isIde = false,
        compatContext = compatContext,
        loadExternalDeclarationExtensions = { session, options, compatContext ->
          buildList {
            add(GenerateImplExtension.Factory().create(session, options, compatContext))
            add(
              GenerateProvidesContributionExtension.Factory()
                .create(session, options, compatContext)
            )
            add(
              GenerateBindsContributionExtension.Factory().create(session, options, compatContext)
            )
            add(GenerateDependencyGraphExtension.Factory().create(session, options, compatContext))
            add(GenerateProvidesInGraphExtension.Factory().create(session, options, compatContext))
            if (options.enableCircuitCodegen) {
              add(CircuitFirExtension.Factory().create(session, options, compatContext)!!)
            }
          }
        },
        loadExternalContributionExtensions = { session, options, compatContext ->
          buildList {
            add(GenerateImplContributionExtension.Factory().create(session, options, compatContext))
            add(
              GenerateProvidesContributionMetroExtension.Factory()
                .create(session, options, compatContext)
            )
            add(
              GenerateBindsContributionMetroExtension.Factory()
                .create(session, options, compatContext)
            )
            if (options.enableCircuitCodegen) {
              add(CircuitContributionExtension.Factory().create(session, options, compatContext)!!)
            }
          }
        },
      )
    )
    if (options.enableCircuitCodegen) {
      FirExtensionRegistrarAdapter.registerExtension(ComposeFirExtensionRegistrar())
      IrGenerationExtension.registerExtension(CircuitIrExtension(compatContext))
    }
    IrGenerationExtension.registerExtension(GenerateImplIrExtension())
    IrGenerationExtension.registerExtension(GenerateProvidesContributionIrExtension())
    IrGenerationExtension.registerExtension(GenerateProvidersInGraphIrExtension())
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
    if (options.enableCircuitCodegen) {
      IrGenerationExtension.registerExtension(
        ComposePluginRegistrar.createComposeIrExtension(configuration)
      )
    }
  }
}
