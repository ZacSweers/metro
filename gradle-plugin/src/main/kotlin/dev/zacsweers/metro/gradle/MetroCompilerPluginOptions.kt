// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

internal data class MetroCompilerPluginOption(
  val key: String,
  val value: String,
  val isFileOption: Boolean = false,
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
    add(metroOption("enabled", extension.enabled))
    add(metroOption("max-ir-errors-count", extension.maxIrErrors))
    add(metroOption("debug", extension.debug))
    add(metroOption("generate-assisted-factories", extension.generateAssistedFactories))
    add(
      metroOption(
        "generate-contribution-hints",
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
        "generate-contribution-hints-in-fir",
        extension.generateContributionHintsInFir.zip(extension.generateClassesInIr) {
          generateContributionHintsInFir,
          generateClassesInIr ->
          generateContributionHintsInFir && !generateClassesInIr
        },
      )
    )
    add(metroOption("generate-classes-in-ir", extension.generateClassesInIr))
    add(metroOption("statements-per-init-fun", extension.statementsPerInitFun))
    add(metroOption("enable-graph-sharding", extension.enableGraphSharding))
    add(metroOption("keys-per-graph-shard", extension.keysPerGraphShard))
    add(metroOption("enable-switching-providers", extension.enableSwitchingProviders))
    add(metroOption("optional-binding-behavior", extension.optionalBindingBehavior))
    add(metroOption("public-scoped-provider-severity", extension.publicScopedProviderSeverity))
    add(metroOption("non-public-contribution-severity", extension.nonPublicContributionSeverity))
    add(
      metroOption(
        "warn-on-inject-annotation-placement",
        extension.warnOnInjectAnnotationPlacement,
      )
    )
    add(
      metroOption(
        "interop-annotations-named-arg-severity",
        extension.interopAnnotationsNamedArgSeverity,
      )
    )
    add(
      metroOption(
        "unused-graph-inputs-severity",
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
        "enable-top-level-function-injection",
        extension.enableTopLevelFunctionInjection,
      )
    )
    add(metroOption("contributes-as-inject", extension.contributesAsInject))
    add(metroOption("enable-klib-params-check", extension.enableKlibParamsCheck))
    add(metroOption("patch-klib-params", extension.patchKlibParams))
    add(metroOption("force-enable-fir-in-ide", extension.forceEnableFirInIde))
    add(metroOption("compiler-version", extension.compilerVersion))
    add(
      metroOption(
        "compiler-version-aliases",
        extension.compilerVersionAliases.map { map ->
          map.entries.joinToString(":") { "${it.key}=${it.value}" }
        },
      )
    )
    add(metroOption("enable-function-providers", extension.enableFunctionProviders))
    add(metroOption("desugared-provider-severity", extension.desugaredProviderSeverity))
    add(metroOption("generate-contribution-providers", extension.generateContributionProviders))
    add(metroOption("enable-circuit-codegen", extension.enableCircuitCodegen))
    add(MetroCompilerPluginOption("plugin-order-set", orderComposePlugin.toString()))
    reportsDir.orNull
      ?.let {
        MetroCompilerPluginOption("reports-destination", it.asFile.path, isFileOption = true)
      }
      ?.let(::add)
    traceDir.orNull
      ?.let { MetroCompilerPluginOption("trace-destination", it.asFile.path, isFileOption = true) }
      ?.let(::add)

    if (isJvmTarget) {
      add(
        MetroCompilerPluginOption(
          "enable-dagger-runtime-interop",
          extension.interop.enableDaggerRuntimeInterop.getOrElse(false).toString(),
        )
      )
      add(
        metroOption(
          "enable-kclass-to-class-interop",
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
      addCustomOption("custom-provider", provider)
      addCustomOption("custom-lazy", lazy)
      addCustomOption("custom-assisted", assisted)
      addCustomOption("custom-assisted-factory", assistedFactory)
      addCustomOption("custom-assisted-inject", assistedInject)
      addCustomOption("custom-binds", binds)
      addCustomOption("custom-contributes-to", contributesTo)
      addCustomOption("custom-contributes-binding", contributesBinding)
      addCustomOption("custom-contributes-into-set", contributesIntoSet)
      addCustomOption("custom-graph-extension", graphExtension)
      addCustomOption("custom-graph-extension-factory", graphExtensionFactory)
      addCustomOption("custom-elements-into-set", elementsIntoSet)
      addCustomOption("custom-dependency-graph", dependencyGraph)
      addCustomOption("custom-dependency-graph-factory", dependencyGraphFactory)
      addCustomOption("custom-inject", inject)
      addCustomOption("custom-into-map", intoMap)
      addCustomOption("custom-into-set", intoSet)
      addCustomOption("custom-map-key", mapKey)
      addCustomOption("custom-multibinds", multibinds)
      addCustomOption("custom-provides", provides)
      addCustomOption("custom-qualifier", qualifier)
      addCustomOption("custom-scope", scope)
      addCustomOption("custom-binding-container", bindingContainer)
      addCustomOption("custom-origin", origin)
      addCustomOption("custom-optional-binding", optionalBinding)
      add(metroOption("interop-include-javax-annotations", includeJavaxAnnotations))
      add(metroOption("interop-include-jakarta-annotations", includeJakartaAnnotations))
      add(metroOption("interop-include-dagger-annotations", includeDaggerAnnotations))
      add(metroOption("interop-include-kotlin-inject-annotations", includeKotlinInjectAnnotations))
      add(metroOption("interop-include-anvil-annotations", includeAnvilAnnotations))
      add(
        metroOption(
          "interop-include-kotlin-inject-anvil-annotations",
          includeKotlinInjectAnvilAnnotations,
        )
      )
      add(
        MetroCompilerPluginOption(
          "enable-dagger-anvil-interop",
          enableDaggerAnvilInterop.getOrElse(false).toString(),
        )
      )
      add(metroOption("interop-include-guice-annotations", includeGuiceAnnotations))
      add(
        MetroCompilerPluginOption(
          "enable-guice-runtime-interop",
          enableGuiceRuntimeInterop.getOrElse(false).toString(),
        )
      )
      add(metroOption("interop-include-hilt-annotations", includeHiltAnnotations))
    }
  }
}

private fun MutableList<MetroCompilerPluginOption>.addCustomOption(
  key: String,
  value: Provider<Set<String>>,
) {
  value
    .getOrElse(emptySet())
    .takeUnless { it.isEmpty() }
    ?.let { MetroCompilerPluginOption(key, value = it.joinToString(":")) }
    ?.let(::add)
}

@JvmName("booleanPluginOptionOf")
private fun metroOption(key: String, value: Provider<Boolean>): MetroCompilerPluginOption =
  metroOption(key, value.map { it.toString() })

@JvmName("intPluginOptionOf")
private fun metroOption(key: String, value: Provider<Int>): MetroCompilerPluginOption =
  metroOption(key, value.map { it.toString() })

@JvmName("enumPluginOptionOf")
private fun <T : Enum<T>> metroOption(key: String, value: Provider<T>): MetroCompilerPluginOption =
  metroOption(key, value.map { it.name })

private fun metroOption(key: String, value: Provider<String>): MetroCompilerPluginOption =
  MetroCompilerPluginOption(key, value.get())

internal fun MetroCompilerPluginOption.toSubpluginOption(): SubpluginOption =
  if (isFileOption) {
    FilesSubpluginOption(key, listOf(File(value)))
  } else {
    SubpluginOption(key, value)
  }

internal fun Collection<MetroCompilerPluginOption>.renderForReport(): List<String> = map {
  "${it.key} = ${it.value}"
}
