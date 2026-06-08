// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import java.nio.file.Paths
import java.util.Locale
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.js.config.jsIncrementalCompilationEnabled
import org.jetbrains.kotlin.js.config.wasmCompilation

internal val RawMetroOption<*>.cliOption: AbstractCliOption
  get() =
    CliOption(
      optionName = name,
      valueDescription = valueDescription,
      description = description,
      required = required,
      allowMultipleOccurrences = allowMultipleOccurrences,
    )

private val compilerConfigurationKeysByName: Map<String, CompilerConfigurationKey<Any>> =
  MetroOption.entries.associate { it.raw.name to CompilerConfigurationKey(it.raw.name) }

private val <T : Any> RawMetroOption<T>.key: CompilerConfigurationKey<T>
  get() {
    @Suppress("UNCHECKED_CAST")
    return compilerConfigurationKeysByName.getValue(name) as CompilerConfigurationKey<T>
  }

internal fun <T : Any> RawMetroOption<T>.put(
  configuration: CompilerConfiguration,
  value: String,
) {
  configuration.put(key, valueMapper(value))
}

internal fun MetroOptions.Companion.load(configuration: CompilerConfiguration): MetroOptions =
  buildOptions {
    for (entry in MetroOption.entries) {
      when (entry) {
        DEBUG -> debug = configuration.getAsBoolean(entry)

        ENABLED -> enabled = configuration.getAsBoolean(entry)

        REPORTS_DESTINATION -> {
          reportsDestination =
            configuration.getAsString(entry).takeUnless(String::isBlank)?.let(Paths::get)
        }

        TRACE_DESTINATION -> {
          traceDestination =
            configuration.getAsString(entry).takeUnless(String::isBlank)?.let(Paths::get)
        }

        GENERATE_ASSISTED_FACTORIES -> generateAssistedFactories = configuration.getAsBoolean(entry)

        ENABLE_TOP_LEVEL_FUNCTION_INJECTION ->
          enableTopLevelFunctionInjection = configuration.getAsBoolean(entry)

        GENERATE_CONTRIBUTION_HINTS -> generateContributionHints = configuration.getAsBoolean(entry)

        GENERATE_CONTRIBUTION_HINTS_IN_FIR ->
          generateContributionHintsInFir = configuration.getAsBoolean(entry)

        SHRINK_UNUSED_BINDINGS -> shrinkUnusedBindings = configuration.getAsBoolean(entry)

        STATEMENTS_PER_INIT_FUN -> statementsPerInitFun = configuration.getAsInt(entry)

        ENABLE_GRAPH_SHARDING -> enableGraphSharding = configuration.getAsBoolean(entry)

        KEYS_PER_GRAPH_SHARD -> keysPerGraphShard = configuration.getAsInt(entry)

        MERGED_SUPERTYPE_CHUNK_SIZE -> mergedSupertypeChunkSize = configuration.getAsInt(entry)

        ENABLE_SWITCHING_PROVIDERS -> enableSwitchingProviders = configuration.getAsBoolean(entry)

        PUBLIC_SCOPED_PROVIDER_SEVERITY ->
          publicScopedProviderSeverity =
            configuration.getAsString(entry).let {
              MetroOptions.DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
            }

        NON_PUBLIC_CONTRIBUTION_SEVERITY ->
          nonPublicContributionSeverity =
            configuration.getAsString(entry).let {
              MetroOptions.DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
            }

        WARN_ON_INJECT_ANNOTATION_PLACEMENT ->
          warnOnInjectAnnotationPlacement = configuration.getAsBoolean(entry)

        INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY ->
          interopAnnotationsNamedArgSeverity =
            configuration.getAsString(entry).let {
              MetroOptions.DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
            }

        UNUSED_GRAPH_INPUTS_SEVERITY ->
          unusedGraphInputsSeverity =
            configuration.getAsString(entry).let {
              MetroOptions.DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
            }

        LOGGING ->
          enabledLoggers +=
            configuration.get(entry.raw.key)?.expectAs<Set<MetroLogger.Type>>().orEmpty()

        ENABLE_DAGGER_RUNTIME_INTEROP ->
          enableDaggerRuntimeInterop = configuration.getAsBoolean(entry)

        ENABLE_GUICE_RUNTIME_INTEROP ->
          enableGuiceRuntimeInterop = configuration.getAsBoolean(entry)

        MAX_IR_ERRORS_COUNT -> maxIrErrorsCount = configuration.getAsInt(entry)

        CUSTOM_PROVIDER -> customProviderTypes.addAll(configuration.getAsSet(entry))
        CUSTOM_LAZY -> customLazyTypes.addAll(configuration.getAsSet(entry))

        CUSTOM_ASSISTED -> customAssistedAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_ASSISTED_FACTORY ->
          customAssistedFactoryAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_ASSISTED_INJECT ->
          customAssistedInjectAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_BINDS -> customBindsAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_CONTRIBUTES_TO ->
          customContributesToAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_CONTRIBUTES_BINDING ->
          customContributesBindingAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_GRAPH_EXTENSION ->
          customGraphExtensionAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_GRAPH_EXTENSION_FACTORY ->
          customGraphExtensionFactoryAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_ELEMENTS_INTO_SET ->
          customElementsIntoSetAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_DEPENDENCY_GRAPH -> customGraphAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_DEPENDENCY_GRAPH_FACTORY ->
          customGraphFactoryAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_INJECT -> customInjectAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_INTO_MAP -> customIntoMapAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_INTO_SET -> customIntoSetAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_MAP_KEY -> customMapKeyAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_MULTIBINDS -> customMultibindsAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_PROVIDES -> customProvidesAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_QUALIFIER -> customQualifierAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_SCOPE -> customScopeAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_BINDING_CONTAINER ->
          customBindingContainerAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_CONTRIBUTES_INTO_SET ->
          customContributesIntoSetAnnotations.addAll(configuration.getAsSet(entry))

        ENABLE_DAGGER_ANVIL_INTEROP -> enableDaggerAnvilInterop = configuration.getAsBoolean(entry)

        ENABLE_FULL_BINDING_GRAPH_VALIDATION ->
          enableFullBindingGraphValidation = configuration.getAsBoolean(entry)

        ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE ->
          enableGraphImplClassAsReturnType = configuration.getAsBoolean(entry)

        CUSTOM_ORIGIN -> customOriginAnnotations.addAll(configuration.getAsSet(entry))
        CUSTOM_OPTIONAL_BINDING ->
          customOptionalBindingAnnotations.addAll(configuration.getAsSet(entry))
        OPTIONAL_BINDING_BEHAVIOR ->
          optionalBindingBehavior =
            configuration.getAsString(entry).let {
              OptionalBindingBehavior.valueOf(it.uppercase(Locale.US))
            }

        CONTRIBUTES_AS_INJECT -> contributesAsInject = configuration.getAsBoolean(entry)

        ENABLE_KLIB_PARAMS_CHECK -> enableKlibParamsCheck = configuration.getAsBoolean(entry)

        PATCH_KLIB_PARAMS -> patchKlibParams = configuration.getAsBoolean(entry)

        INTEROP_INCLUDE_JAVAX_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeJavaxAnnotations()
        }
        INTEROP_INCLUDE_JAKARTA_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeJakartaAnnotations()
        }
        INTEROP_INCLUDE_DAGGER_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeDaggerAnnotations()
        }
        INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeKotlinInjectAnnotations()
        }
        INTEROP_INCLUDE_ANVIL_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeAnvilAnnotations()
        }
        INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeKotlinInjectAnvilAnnotations()
        }
        INTEROP_INCLUDE_HILT_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeHiltAnnotations()
        }
        INTEROP_INCLUDE_GUICE_ANNOTATIONS -> {
          if (configuration.getAsBoolean(entry)) includeGuiceAnnotations()
        }
        FORCE_ENABLE_FIR_IN_IDE -> forceEnableFirInIde = configuration.getAsBoolean(entry)
        PLUGIN_ORDER_SET ->
          pluginOrderSet =
            configuration.getAsString(entry).takeUnless(String::isBlank)?.toBooleanStrict()
        COMPILER_VERSION ->
          compilerVersion = configuration.getAsString(entry).takeUnless(String::isBlank)
        COMPILER_VERSION_ALIASES -> compilerVersionAliases = configuration.getAsMap(entry)
        PARALLEL_THREADS -> parallelThreads = configuration.getAsInt(entry)
        BUFFERED_IC_TRACKING -> bufferedIcTracking = configuration.getAsBoolean(entry)
        ENABLE_PROVIDER_INLINING -> enableProviderInlining = configuration.getAsBoolean(entry)
        ENABLE_FUNCTION_PROVIDERS -> enableFunctionProviders = configuration.getAsBoolean(entry)
        DESUGARED_PROVIDER_SEVERITY ->
          desugaredProviderSeverity =
            configuration.getAsString(entry).let {
              MetroOptions.DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
            }
        ENABLE_KCLASS_TO_CLASS_INTEROP ->
          enableKClassToClassInterop = configuration.getAsBoolean(entry)
        GENERATE_CONTRIBUTION_PROVIDERS ->
          generateContributionProviders = configuration.getAsBoolean(entry)
        ENABLE_CIRCUIT_CODEGEN -> enableCircuitCodegen = configuration.getAsBoolean(entry)
        RICH_DIAGNOSTICS -> richDiagnostics = configuration.getAsBoolean(entry)
        GENERATE_STATIC_ANNOTATIONS -> generateStaticAnnotations = configuration.getAsBoolean(entry)
        BINDING_CONTRIBUTIONS_AS_CONTAINERS ->
          bindingContributionsAsContainers = configuration.getAsBoolean(entry)
        MEMBER_NAMING_STRATEGY ->
          memberNamingStrategy =
            configuration.getAsString(entry).let {
              MemberNamingStrategy.valueOf(it.uppercase(Locale.US))
            }
      }
    }
  }

internal fun MetroOptions.validate(
  compilerVersion: KotlinToolingVersion,
  configuration: CompilerConfiguration,
  onError: (String) -> Unit,
): Boolean {
  var valid = true
  if (!validateKotlinJsIC(compilerVersion, configuration, onError)) {
    valid = false
  }

  val contributionProvidersAreEnabledWithoutFirHintGen =
    generateContributionProviders && generateContributionHints && !generateContributionHintsInFir
  if (contributionProvidersAreEnabledWithoutFirHintGen) {
    onError(
      "generateContributionProviders with generateContributionHints requires " +
        "generateContributionHintsInFir to also be enabled."
    )
    valid = false
  }

  if (unusedGraphInputsSeverity.isIdeOnly) {
    onError(
      "unusedGraphInputsSeverity (set to ${unusedGraphInputsSeverity.name}) does not support IDE_WARN/IDE_ERROR " +
        "because the underlying check only runs during IR (CLI-only). Use WARN, ERROR, or NONE instead."
    )
    valid = false
  }
  return valid
}

private fun MetroOptions.validateKotlinJsIC(
  compilerVersion: KotlinToolingVersion,
  configuration: CompilerConfiguration,
  onError: (String) -> Unit,
): Boolean {
  val supportsJsIc =
    !configuration.jsIncrementalCompilationEnabled ||
      configuration.wasmCompilation ||
      kotlinVersionSupportsJsIC(compilerVersion)
  if (supportsJsIc) {
    return true
  }

  val jsICOptions = buildList {
    if (enableTopLevelFunctionInjection) {
      add("enableTopLevelFunctionInjection")
    }
    if (generateContributionHints) {
      add("generateContributionHints")
    }
    if (generateContributionHintsInFir) {
      add("generateContributionHintsInFir")
    }
  }

  if (jsICOptions.isNotEmpty()) {
    onError(
      "Kotlin/JS does not support generating top-level declarations with incremental compilation enabled. " +
        "See https://youtrack.jetbrains.com/issue/KT-82395 and https://youtrack.jetbrains.com/issue/KT-82989. " +
        "Either disable ${jsICOptions.joinToString()} for JS targets or disable JS IC."
    )
    return false
  }
  return true
}

private fun CompilerConfiguration.getAsString(option: MetroOption): String {
  @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<String>
  return get(typed.key, typed.defaultValue)
}

private fun CompilerConfiguration.getAsBoolean(option: MetroOption): Boolean {
  @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Boolean>
  return get(typed.key, typed.defaultValue)
}

private fun CompilerConfiguration.getAsInt(option: MetroOption): Int {
  @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Int>
  return get(typed.key, typed.defaultValue)
}

private fun <E> CompilerConfiguration.getAsSet(option: MetroOption): Set<E> {
  @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Set<E>>
  return get(typed.key, typed.defaultValue)
}

private fun <K, V> CompilerConfiguration.getAsMap(option: MetroOption): Map<K, V> {
  @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Map<K, V>>
  return get(typed.key, typed.defaultValue)
}

/** Minimum Kotlin version on the 2.3.x line that supports JS IC with top-level declarations. */
private val MIN_KOTLIN_2_3_JS_IC = KotlinToolingVersion("2.3.21-RC")

/** Minimum Kotlin dev version on the 2.4.x line that supports JS IC with top-level declarations. */
private val MIN_KOTLIN_2_4_DEV_JS_IC = KotlinToolingVersion("2.4.0-dev-8064")

/**
 * Minimum Kotlin non-dev version on the 2.4.x line that supports JS IC with top-level declarations.
 */
private val MIN_KOTLIN_2_4_JS_IC = KotlinToolingVersion("2.4.0-Beta2")

private fun kotlinVersionSupportsJsIC(version: KotlinToolingVersion): Boolean {
  if (version.major > 2) return true // ... if K3 ever happens
  return when (version.minor) {
    in 0..2 -> false
    3 -> version >= MIN_KOTLIN_2_3_JS_IC
    4 ->
      if (version.maturity == KotlinToolingVersion.Maturity.DEV) {
        version >= MIN_KOTLIN_2_4_DEV_JS_IC
      } else {
        version >= MIN_KOTLIN_2_4_JS_IC
      }
    else -> true // 2.5+
  }
}
