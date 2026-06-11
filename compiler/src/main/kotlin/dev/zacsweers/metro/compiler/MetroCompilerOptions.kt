// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
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
      configuration.get(entry.raw.key)?.let { applyOptionValue(entry, it) }
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
    generateContributionProviders &&
      generateContributionHints &&
      !generateContributionHintsInFir &&
      !generateClassesInIr
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
