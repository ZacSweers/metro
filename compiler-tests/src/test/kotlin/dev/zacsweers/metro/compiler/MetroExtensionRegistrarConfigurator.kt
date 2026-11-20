// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import androidx.compose.compiler.plugins.kotlin.k2.ComposeFirExtensionRegistrar
import dev.zacsweers.metro.compiler.api.GenerateBindsContributionExtension
import dev.zacsweers.metro.compiler.api.GenerateBindsContributionMetroExtension
import dev.zacsweers.metro.compiler.api.GenerateDependencyGraphExtension
import dev.zacsweers.metro.compiler.api.GenerateGraphExtensionContributionExtension
import dev.zacsweers.metro.compiler.api.GenerateGraphExtensionExtension
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
import dev.zacsweers.metro.compiler.circuit.CircuitIrDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.circuit.CircuitIrExtension
import dev.zacsweers.metro.compiler.circuit.CircuitSerializableContributionExtension
import dev.zacsweers.metro.compiler.circuit.CircuitSerializableFirExtension
import dev.zacsweers.metro.compiler.circuit.CircuitSerializableIrDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.circuit.CircuitSerializableIrExtension
import dev.zacsweers.metro.compiler.circuit.configureCircuit
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.hilt.HiltContributionExtension
import dev.zacsweers.metro.compiler.hilt.HiltFirDeclarationExtension
import dev.zacsweers.metro.compiler.interop.configureAnvilAnnotations
import dev.zacsweers.metro.compiler.interop.configureDaggerInterop
import dev.zacsweers.metro.compiler.interop.configureGuiceInterop
import dev.zacsweers.metro.compiler.interop.configureHiltAnnotations
import dev.zacsweers.metro.compiler.interop.configureKsp
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import dev.zacsweers.metro.compiler.tracing.TraceContext
import kotlin.io.path.Path
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.cli.CliDirectives
import org.jetbrains.kotlin.test.directives.AdditionalFilesDirectives.WITH_COROUTINES as WITH_COROUTINE_HELPERS
import org.jetbrains.kotlin.test.directives.LanguageSettingsDirectives.LANGUAGE
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.defaultsProvider
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import org.junit.jupiter.api.Assumptions.assumeTrue

fun TestConfigurationBuilder.configurePlugin(
  compatContext: CompatContext = CompatContext.create(),
) {
  useConfigurators(
    ::SerializationFirExtensionRegistrarConfigurator,
    { MetroExtensionRegistrarConfigurator(it, compatContext) },
    ::SerializationIrExtensionRegistrarConfigurator,
    ::MetroRuntimeEnvironmentConfigurator,
  )

  useDirectives(MetroDirectives, CliDirectives)
  configureTestPhaseCompat()
  forTestsMatching("*/companionblocks/*") {
    // Skip before Kotlin parses module-level language directives.
    assumeTrue(compatContext.supportsCompanionBlocks, "Compiler does not support companion blocks")
    defaultDirectives { LANGUAGE + "+CompanionBlocks" }
  }
  forTestsMatching("*/box/suspend/*") {
    // Other box fixtures opt in to helpers.runBlocking with WITH_COROUTINES.
    defaultDirectives { +WITH_COROUTINE_HELPERS }
  }
  forTestsMatching("*/box/derivedbindings/*") {
    // Every box fixture in this directory uses derived bindings, so none of them repeats the
    // directive. Diagnostic fixtures keep it because some of them test the disabled option.
    defaultDirectives { +MetroDirectives.ENABLE_DERIVED_BINDINGS }
  }

  useCustomRuntimeClasspathProviders(::MetroRuntimeClassPathProvider)

  useSourcePreprocessor(::MetroDefaultImportPreprocessor)

  configureAnvilAnnotations()
  configureDaggerInterop()
  configureGuiceInterop()
  configureHiltAnnotations()
  configureCircuit()
  configureKsp()
  useAdditionalService { MetroMessageCollectorErrors() }
  useAfterAnalysisCheckers(::MetroReportsChecker, ::MetroMessageCollectorErrorsChecker)
}

class MetroExtensionRegistrarConfigurator(
  testServices: TestServices,
  private val compatContext: CompatContext,
) : EnvironmentConfigurator(testServices) {
  @OptIn(ExperimentalCompilerApi::class)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    val options = MetroOptions.buildOptions {
      // Set non-annotation properties (only when directive is present or value is non-default)
      enabled = MetroDirectives.DISABLE_METRO !in module.directives
      module.directives.singleOrZeroValue(MetroDirectives.COMPANION_MODE)?.let {
        companionMode = it
      }
      enablePrivateProviderProperties =
        MetroDirectives.ENABLE_PRIVATE_PROVIDER_PROPERTIES in module.directives
      generateAssistedFactories = MetroDirectives.GENERATE_ASSISTED_FACTORIES in module.directives
      enableTopLevelFunctionInjection =
        MetroDirectives.ENABLE_TOP_LEVEL_FUNCTION_INJECTION in module.directives
      module.directives
        .singleOrZeroValue(MetroDirectives.FUNCTION_INJECT_ANNOTATIONS_OVERRIDE)
        ?.let {
          applyRawOption("function-inject-annotations-override", it)
        }
      module.directives.singleOrZeroValue(MetroDirectives.ENABLE_FUNCTION_PROVIDERS)?.let {
        enableFunctionProviders = it
      }
      module.directives
        .singleOrZeroValue(MetroDirectives.WARN_ON_INJECT_ANNOTATION_PLACEMENT)
        ?.let {
          warnOnInjectAnnotationPlacement = it
        }
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
      module.directives.singleOrZeroValue(MetroDirectives.MEMBER_NAMING_STRATEGY)?.let {
        memberNamingStrategy = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.MERGED_SUPERTYPE_CHUNK_SIZE)?.let {
        mergedSupertypeChunkSize = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.MULTIBINDING_GETTER_THRESHOLD)?.let {
        multibindingGetterThreshold = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.FORCE_PROVIDER_GETTERS)?.let {
        forceProviderGetters = it
      }
      // Fixture values follow suite defaults when both supply a directive.
      enableSwitchingProviders =
        module.directives[MetroDirectives.ENABLE_SWITCHING_PROVIDERS].lastOrNull() ?: false
      enableFullBindingGraphValidation =
        MetroDirectives.ENABLE_FULL_BINDING_GRAPH_VALIDATION in module.directives
      enableGraphImplClassAsReturnType =
        MetroDirectives.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE in module.directives
      generateContributionHints =
        module.directives[MetroDirectives.GENERATE_CONTRIBUTION_HINTS].lastOrNull() ?: true
      generateClassesInIr =
        module.directives[MetroDirectives.GENERATE_CLASSES_IN_IR].lastOrNull() ?: false
      fixEmptySmapRanges = MetroDirectives.FIX_EMPTY_SMAP_RANGES in module.directives
      omitRedundantMirrors =
        module.directives[MetroDirectives.OMIT_REDUNDANT_MIRRORS].lastOrNull() ?: false

      val shouldGenerateContributionHintsInFir =
        MetroDirectives.GENERATE_CONTRIBUTION_HINTS_IN_FIR in module.directives ||
          testServices.shouldGenerateContributionHintsInFirForBackend()

      generateContributionHintsInFir = shouldGenerateContributionHintsInFir

      module.directives.singleOrZeroValue(MetroDirectives.PUBLIC_SCOPED_PROVIDER_SEVERITY)?.let {
        publicScopedProviderSeverity = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.OPTIONAL_DEPENDENCY_BEHAVIOR)?.let {
        optionalBindingBehavior = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.DIAGNOSTICS_RENDER_MODE)?.let {
        diagnosticsRenderMode = it
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
      module.directives.singleOrZeroValue(MetroDirectives.DERIVED_BINDING_OVERLAP_SEVERITY)?.let {
        derivedBindingOverlapSeverity = it
      }
      for (level in module.directives[MetroDirectives.DIAGNOSTIC_LEVEL]) {
        diagnosticLevels.putAll(level)
      }
      module.directives.singleOrZeroValue(MetroDirectives.MAX_IR_ERRORS_COUNT)?.let {
        maxIrErrorsCount = it
      }
      val reportsRequested =
        MetroDirectives.REPORTS_DESTINATION in module.directives ||
          MetroDirectives.CHECK_REPORTS in module.directives
      if (reportsRequested) {
        reportsDestination = testServices.reportDirectory(module).toPath()
      }

      // Use explicit TRACE_DESTINATION or default if CHECK_TRACES is present
      val tracesDir =
        module.directives.singleOrZeroValue(MetroDirectives.TRACE_DESTINATION)
          ?: if (MetroDirectives.CHECK_TRACES in module.directives) {
            MetroReportsChecker.DEFAULT_TRACES_DIR
          } else {
            null
          }
      tracesDir?.let {
        traceDestination =
          Path("${testServices.temporaryDirectoryManager.rootDir.absolutePath}/$it")
      }
      module.directives.singleOrZeroValue(MetroDirectives.PARALLEL_THREADS)?.let {
        parallelThreads = it
      }
      module.directives.singleOrZeroValue(MetroDirectives.ENABLE_PROVIDER_INLINING)?.let {
        enableProviderInlining = it
      }
      contributesAsInject = MetroDirectives.CONTRIBUTES_AS_INJECT in module.directives
      module.directives.singleOrZeroValue(MetroDirectives.DESUGARED_PROVIDER_SEVERITY)?.let {
        desugaredProviderSeverity = it
      }
      enableKClassToClassInterop =
        MetroDirectives.ENABLE_KCLASS_TO_CLASS_INTEROP in module.directives

      generateContributionProviders =
        module.directives[MetroDirectives.GENERATE_CONTRIBUTION_PROVIDERS].lastOrNull() ?: false

      // Configure interop annotations using builder helper methods
      if (MetroDirectives.WITH_KI_ANVIL in module.directives) {
        includeKotlinInjectAnvilAnnotations()
      }
      if (MetroDirectives.enableAnvilAnnotations(module.directives)) {
        includeAnvilAnnotations()
      }
      module.directives.singleOrZeroValue(MetroDirectives.ENABLE_ANVIL_INTEROP)?.let {
        enableDaggerAnvilInterop = it
      }

      if (MetroDirectives.ENABLE_HILT_INTEROP in module.directives) {
        includeHiltAnnotations()
      }

      // The Hilt and Anvil helpers include Dagger annotations themselves.
      if (!enableDaggerRuntimeInterop && MetroDirectives.enableDaggerInterop(module.directives)) {
        includeDaggerAnnotations()
      }

      if (MetroDirectives.enableGuiceInterop(module.directives)) {
        includeGuiceAnnotations()
      }

      if (MetroDirectives.ENABLE_CIRCUIT in module.directives) {
        enableCircuitCodegen = true
      }

      if (MetroDirectives.ENABLE_RUNTIME_TRACING in module.directives) {
        enableRuntimeTracing = true
      }

      if (MetroDirectives.ENABLE_SUSPEND_PROVIDERS in module.directives) {
        enableSuspendProviders = true
      }

      if (MetroDirectives.ENABLE_DERIVED_BINDINGS in module.directives) {
        enableDerivedBindings = true
      }
      module.directives[MetroDirectives.MAX_GENERATED_CLASS_NAME_LENGTH].lastOrNull()?.let {
        maxGeneratedClassNameLength = it
      }
    }

    if (!options.enabled) return

    val withSampleExtensions = MetroDirectives.WITH_SAMPLE_EXTENSIONS in module.directives
    val classIds = ClassIds.fromOptions(options)
    val traceContext = TraceContext(options)
    FirExtensionRegistrarAdapter.registerExtension(
      MetroFirExtensionRegistrar(
        classIds = classIds,
        options = options,
        isIde = false,
        compatContext = compatContext,
        traceContext = traceContext,
        loadExternalDeclarationExtensions = { session, options, compatContext ->
          buildList {
            if (withSampleExtensions) {
              add(GenerateImplExtension.Factory().create(session, options, compatContext))
              add(
                GenerateProvidesContributionExtension.Factory()
                  .create(session, options, compatContext),
              )
              add(
                GenerateBindsContributionExtension.Factory()
                  .create(session, options, compatContext),
              )
              add(
                GenerateDependencyGraphExtension.Factory().create(session, options, compatContext),
              )
              add(GenerateGraphExtensionExtension.Factory().create(session, options, compatContext))
              add(
                GenerateProvidesInGraphExtension.Factory().create(session, options, compatContext),
              )
            }

            if (options.enableCircuitCodegen) {
              CircuitFirExtension.Factory().create(session, options, compatContext)?.let(::add)
              CircuitSerializableFirExtension.Factory()
                .create(session, options, compatContext)
                ?.let(::add)
            }
            if (options.enableHiltInterop) {
              HiltFirDeclarationExtension.Factory()
                .create(session, options, compatContext)
                ?.let(::add)
            }
          }
        },
        loadExternalContributionHintExtensions = { session, options, compatContext ->
          buildList {
            // Metro collects sample hints from the registered declaration extensions.
            if (options.enableHiltInterop) {
              HiltFirDeclarationExtension.HintFactory()
                .create(session, options, compatContext)
                ?.let(::add)
            }
          }
        },
        loadExternalContributionExtensions = { session, options, compatContext ->
          buildList {
            if (withSampleExtensions) {
              add(
                GenerateImplContributionExtension.Factory().create(session, options, compatContext),
              )
              add(
                GenerateProvidesContributionMetroExtension.Factory()
                  .create(session, options, compatContext),
              )
              add(
                GenerateBindsContributionMetroExtension.Factory()
                  .create(session, options, compatContext),
              )
              add(
                GenerateGraphExtensionContributionExtension.Factory()
                  .create(session, options, compatContext),
              )
            }

            if (options.enableCircuitCodegen && !options.generateClassesInIr) {
              add(CircuitContributionExtension.Factory().create(session, options, compatContext)!!)
              add(
                CircuitSerializableContributionExtension.Factory()
                  .create(session, options, compatContext)!!,
              )
            }
            if (options.enableHiltInterop) {
              HiltContributionExtension.Factory()
                .create(session, options, compatContext)
                ?.let(::add)
            }
          }
        },
      ),
    )
    if (options.enableCircuitCodegen) {
      FirExtensionRegistrarAdapter.registerExtension(ComposeFirExtensionRegistrar())
      val circuitFactoriesGeneratedInFir =
        !options.generateClassesInIr ||
          (options.generateContributionHints && options.generateContributionHintsInFir)
      val circuitSerializerRegistrationsGeneratedInFir =
        !options.generateClassesInIr ||
          (options.generateContributionHints && options.generateContributionHintsInFir)
      if (options.generateClassesInIr) {
        if (!circuitSerializerRegistrationsGeneratedInFir) {
          IrGenerationExtension.registerExtension(
            CircuitSerializableIrDeclarationGenerationExtension.create(
              compatContext = compatContext,
            ),
          )
        }
        if (!circuitFactoriesGeneratedInFir) {
          IrGenerationExtension.registerExtension(
            CircuitIrDeclarationGenerationExtension.create(
              classIds = classIds,
              compatContext = compatContext,
            ),
          )
        }
      }
      IrGenerationExtension.registerExtension(
        CircuitIrExtension.create(
          generateClassesInIr = options.generateClassesInIr,
          classIds = classIds,
          compatContext = compatContext,
        ),
      )
      IrGenerationExtension.registerExtension(
        CircuitSerializableIrExtension.create(
          generateClassesInIr = options.generateClassesInIr,
          compatContext = compatContext,
        ),
      )
    }
    if (withSampleExtensions) {
      IrGenerationExtension.registerExtension(GenerateImplIrExtension())
      IrGenerationExtension.registerExtension(GenerateProvidesContributionIrExtension())
      IrGenerationExtension.registerExtension(GenerateProvidersInGraphIrExtension())
    }

    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(
        messageCollector =
          testServices.metroMessageCollectorErrors.wrap(
            module,
            with(compatContext) { configuration.messageCollectorCompat() },
          ),
        classIds = classIds,
        options = options,
        // TODO ever support this in tests?
        lookupTracker = null,
        expectActualTracker = ExpectActualTracker.DoNothing,
        compatContext = compatContext,
        traceContext = traceContext,
      ),
    )
    if (options.enableCircuitCodegen) {
      IrGenerationExtension.registerExtension(
        ComposePluginRegistrar.createComposeIrExtension(configuration),
      )
    }
  }
}

private val MIN_KOTLIN_VERSION_FOR_JS_FIR_CONTRIBUTION_HINTS = KotlinToolingVersion("2.3.21")

private fun TestServices.shouldGenerateContributionHintsInFirForBackend(): Boolean {
  return when (defaultsProvider.targetBackend) {
    TargetBackend.JS_IR,
    TargetBackend.JS_IR_ES6 -> {
      TEST_COMPILER_TOOLING_VERSION >= MIN_KOTLIN_VERSION_FOR_JS_FIR_CONTRIBUTION_HINTS
    }
    else -> false
  }
}
