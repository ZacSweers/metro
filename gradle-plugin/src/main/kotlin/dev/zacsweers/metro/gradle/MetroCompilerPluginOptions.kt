// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import dev.zacsweers.metro.compiler.MetroOption
import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.InternalSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

internal data class MetroCompilerPluginOption(
  val key: String,
  val value: String,
  val isFileOption: Boolean = false,
  /**
   * Internal options are passed to the compiler but excluded from Gradle's task input snapshot (via
   * [InternalSubpluginOption]).
   *
   * Use for presentation-only options whose value never affects compilation outputs — otherwise
   * environment-dependent values (like [ConsoleMode]) would spuriously invalidate compile tasks and
   * break build-cache sharing between environments.
   */
  val isInternal: Boolean = false,
)

@OptIn(
  DangerousMetroGradleApi::class,
  ExperimentalMetroGradleApi::class,
  RequiresIdeSupport::class,
)
internal fun Project.metroCompilerPluginOptions(
  extension: MetroPluginExtension,
  kotlinCompilation: KotlinCompilation<*>,
  reportsDir: Provider<Directory>,
  traceDir: Provider<Directory>,
  orderComposePlugin: Boolean,
  isJvmTarget: Boolean,
): Provider<List<MetroCompilerPluginOption>> = provider {
  buildList {
    add(metroOption(MetroOption.ENABLED, extension.enabled))
    add(metroOption(MetroOption.MAX_IR_ERRORS_COUNT, extension.maxIrErrors))
    add(metroOption(MetroOption.DEBUG, extension.debug))
    add(metroOption(MetroOption.GENERATE_ASSISTED_FACTORIES, extension.generateAssistedFactories))
    add(
      metroOption(
        MetroOption.GENERATE_CONTRIBUTION_HINTS,
        extension.generateContributionHints.orElse(
          provider { kotlinCompilation.platformType }
            .zip(extension.supportedHintContributionPlatforms) { platformType, supportedPlatforms ->
              platformType in supportedPlatforms
            }
        ),
      )
    )
    add(
      metroOption(
        MetroOption.GENERATE_CONTRIBUTION_HINTS_IN_FIR,
        extension.generateContributionHintsInFir.zip(extension.generateClassesInIr) {
          generateContributionHintsInFir,
          generateClassesInIr ->
          generateContributionHintsInFir && !generateClassesInIr
        },
      )
    )
    add(metroOption("generate-classes-in-ir", extension.generateClassesInIr))
    add(metroOption(MetroOption.STATEMENTS_PER_INIT_FUN, extension.statementsPerInitFun))
    add(metroOption(MetroOption.ENABLE_GRAPH_SHARDING, extension.enableGraphSharding))
    add(metroOption(MetroOption.KEYS_PER_GRAPH_SHARD, extension.keysPerGraphShard))
    add(metroOption(MetroOption.ENABLE_SWITCHING_PROVIDERS, extension.enableSwitchingProviders))
    add(metroOption(MetroOption.OPTIONAL_BINDING_BEHAVIOR, extension.optionalBindingBehavior))
    add(
      metroOption(
        MetroOption.PUBLIC_SCOPED_PROVIDER_SEVERITY,
        extension.publicScopedProviderSeverity,
      )
    )
    add(
      metroOption(
        MetroOption.NON_PUBLIC_CONTRIBUTION_SEVERITY,
        extension.nonPublicContributionSeverity,
      )
    )
    add(
      metroOption(
        MetroOption.WARN_ON_INJECT_ANNOTATION_PLACEMENT,
        extension.warnOnInjectAnnotationPlacement,
      )
    )
    add(
      metroOption(
        MetroOption.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY,
        extension.interopAnnotationsNamedArgSeverity,
      )
    )
    add(
      metroOption(
        MetroOption.UNUSED_GRAPH_INPUTS_SEVERITY,
        extension.unusedGraphInputsSeverity.map { severity ->
          check(!severity.isIdeOnly) {
            "metro.unusedGraphInputsSeverity (set to ${severity.name}) does not support ${severity.name} " +
              "because unused-input detection only runs during IR (CLI-only). Use WARN, ERROR, or NONE instead."
          }
          severity
        },
      )
    )
    add(
      metroOption(
        MetroOption.ENABLE_TOP_LEVEL_FUNCTION_INJECTION,
        extension.enableTopLevelFunctionInjection,
      )
    )
    add(metroOption(MetroOption.CONTRIBUTES_AS_INJECT, extension.contributesAsInject))
    add(metroOption(MetroOption.ENABLE_KLIB_PARAMS_CHECK, extension.enableKlibParamsCheck))
    add(metroOption(MetroOption.PATCH_KLIB_PARAMS, extension.patchKlibParams))
    add(metroOption(MetroOption.FORCE_ENABLE_FIR_IN_IDE, extension.forceEnableFirInIde))
    add(metroOption(MetroOption.COMPILER_VERSION, extension.compilerVersion))
    add(
      metroOption(
        MetroOption.COMPILER_VERSION_ALIASES,
        extension.compilerVersionAliases.map { map ->
          map.entries.joinToString(":") { "${it.key}=${it.value}" }
        },
      )
    )
    add(metroOption(MetroOption.ENABLE_FUNCTION_PROVIDERS, extension.enableFunctionProviders))
    add(metroOption(MetroOption.DESUGARED_PROVIDER_SEVERITY, extension.desugaredProviderSeverity))
    add(
      metroOption(
        MetroOption.GENERATE_CONTRIBUTION_PROVIDERS,
        extension.generateContributionProviders,
      )
    )
    add(metroOption(MetroOption.ENABLE_CIRCUIT_CODEGEN, extension.enableCircuitCodegen))
    add(
      MetroCompilerPluginOption(
        MetroOption.PLUGIN_ORDER_SET.raw.name,
        orderComposePlugin.toString(),
      )
    )
    reportsDir.orNull
      ?.let {
        MetroCompilerPluginOption(
          MetroOption.REPORTS_DESTINATION.raw.name,
          it.asFile.path,
          isFileOption = true,
        )
      }
      ?.let(::add)
    traceDir.orNull
      ?.let {
        MetroCompilerPluginOption(
          MetroOption.TRACE_DESTINATION.raw.name,
          it.asFile.path,
          isFileOption = true,
        )
      }
      ?.let(::add)

    if (isJvmTarget) {
      add(
        MetroCompilerPluginOption(
          MetroOption.ENABLE_DAGGER_RUNTIME_INTEROP.raw.name,
          extension.interop.enableDaggerRuntimeInterop.getOrElse(false).toString(),
        )
      )
      add(
        metroOption(
          MetroOption.ENABLE_KCLASS_TO_CLASS_INTEROP,
          extension.enableKClassToClassMapKeyInterop,
        )
      )
    }

    val compilerOptions = extension.compilerOptions.rawOptions
    for (key in compilerOptions.keySet().orNull.orEmpty().sorted()) {
      val valueProvider = compilerOptions.getting(key)
      add(metroOption(key, valueProvider))
    }

    with(extension.interop) {
      addCustomOption(MetroOption.CUSTOM_PROVIDER, provider)
      addCustomOption(MetroOption.CUSTOM_LAZY, lazy)
      addCustomOption(MetroOption.CUSTOM_ASSISTED, assisted)
      addCustomOption(MetroOption.CUSTOM_ASSISTED_FACTORY, assistedFactory)
      addCustomOption(MetroOption.CUSTOM_ASSISTED_INJECT, assistedInject)
      addCustomOption(MetroOption.CUSTOM_BINDS, binds)
      addCustomOption(MetroOption.CUSTOM_CONTRIBUTES_TO, contributesTo)
      addCustomOption(MetroOption.CUSTOM_CONTRIBUTES_BINDING, contributesBinding)
      addCustomOption(MetroOption.CUSTOM_CONTRIBUTES_INTO_SET, contributesIntoSet)
      addCustomOption(MetroOption.CUSTOM_GRAPH_EXTENSION, graphExtension)
      addCustomOption(MetroOption.CUSTOM_GRAPH_EXTENSION_FACTORY, graphExtensionFactory)
      addCustomOption(MetroOption.CUSTOM_ELEMENTS_INTO_SET, elementsIntoSet)
      addCustomOption(MetroOption.CUSTOM_DEPENDENCY_GRAPH, dependencyGraph)
      addCustomOption(MetroOption.CUSTOM_DEPENDENCY_GRAPH_FACTORY, dependencyGraphFactory)
      addCustomOption(MetroOption.CUSTOM_INJECT, inject)
      addCustomOption(MetroOption.CUSTOM_INTO_MAP, intoMap)
      addCustomOption(MetroOption.CUSTOM_INTO_SET, intoSet)
      addCustomOption(MetroOption.CUSTOM_MAP_KEY, mapKey)
      addCustomOption(MetroOption.CUSTOM_MULTIBINDS, multibinds)
      addCustomOption(MetroOption.CUSTOM_PROVIDES, provides)
      addCustomOption(MetroOption.CUSTOM_QUALIFIER, qualifier)
      addCustomOption(MetroOption.CUSTOM_SCOPE, scope)
      addCustomOption(MetroOption.CUSTOM_BINDING_CONTAINER, bindingContainer)
      addCustomOption(MetroOption.CUSTOM_ORIGIN, origin)
      addCustomOption(MetroOption.CUSTOM_OPTIONAL_BINDING, optionalBinding)
      add(metroOption(MetroOption.INTEROP_INCLUDE_JAVAX_ANNOTATIONS, includeJavaxAnnotations))
      add(metroOption(MetroOption.INTEROP_INCLUDE_JAKARTA_ANNOTATIONS, includeJakartaAnnotations))
      add(metroOption(MetroOption.INTEROP_INCLUDE_DAGGER_ANNOTATIONS, includeDaggerAnnotations))
      add(
        metroOption(
          MetroOption.INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS,
          includeKotlinInjectAnnotations,
        )
      )
      add(metroOption(MetroOption.INTEROP_INCLUDE_ANVIL_ANNOTATIONS, includeAnvilAnnotations))
      add(
        metroOption(
          MetroOption.INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS,
          includeKotlinInjectAnvilAnnotations,
        )
      )
      add(
        MetroCompilerPluginOption(
          MetroOption.ENABLE_DAGGER_ANVIL_INTEROP.raw.name,
          enableDaggerAnvilInterop.getOrElse(false).toString(),
        )
      )
      add(metroOption(MetroOption.INTEROP_INCLUDE_GUICE_ANNOTATIONS, includeGuiceAnnotations))
      add(
        MetroCompilerPluginOption(
          MetroOption.ENABLE_GUICE_RUNTIME_INTEROP.raw.name,
          enableGuiceRuntimeInterop.getOrElse(false).toString(),
        )
      )
      add(metroOption(MetroOption.INTEROP_INCLUDE_HILT_ANNOTATIONS, includeHiltAnnotations))
    }
  }
}

private fun MutableList<MetroCompilerPluginOption>.addCustomOption(
  option: MetroOption,
  value: Provider<Set<String>>,
) {
  value
    .getOrElse(emptySet())
    .takeUnless { it.isEmpty() }
    ?.let { MetroCompilerPluginOption(option.raw.name, value = it.joinToString(":")) }
    ?.let(::add)
}

@JvmName("booleanPluginOptionOf")
private fun metroOption(option: MetroOption, value: Provider<Boolean>): MetroCompilerPluginOption =
  metroOption(option, value.map { it.toString() })

@JvmName("intPluginOptionOf")
private fun metroOption(option: MetroOption, value: Provider<Int>): MetroCompilerPluginOption =
  metroOption(option, value.map { it.toString() })

@JvmName("enumPluginOptionOf")
private fun <T : Enum<T>> metroOption(
  option: MetroOption,
  value: Provider<T>,
): MetroCompilerPluginOption = metroOption(option, value.map { it.name })

@JvmName("booleanPluginOptionOf")
private fun metroOption(key: String, value: Provider<Boolean>): MetroCompilerPluginOption =
  metroOption(key, value.map { it.toString() })

private fun metroOption(key: String, value: Provider<String>): MetroCompilerPluginOption =
  MetroCompilerPluginOption(key, value.get())

private fun metroOption(option: MetroOption, value: Provider<String>): MetroCompilerPluginOption =
  MetroCompilerPluginOption(option.raw.name, value.get())

internal fun MetroCompilerPluginOption.toSubpluginOption(): SubpluginOption =
  when {
    isFileOption -> FilesSubpluginOption(key, listOf(File(value)))
    isInternal -> InternalSubpluginOption(key, value)
    else -> SubpluginOption(key, value)
  }

internal fun Collection<MetroCompilerPluginOption>.renderForReport(): List<String> = map {
  "${it.key} = ${it.value}"
}
