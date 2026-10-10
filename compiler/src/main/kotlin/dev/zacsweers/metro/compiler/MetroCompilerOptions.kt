// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import dev.zacsweers.metro.compiler.fir.irReportedDiagnosticNames
import dev.zacsweers.metro.compiler.fir.metroDiagnosticNames
import dev.zacsweers.metro.compiler.internal.isTopLevelFirGenerationSupported
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

/** Holds every occurrence of an option that allows multiple occurrences. */
private val <T : Any> RawMetroOption<T>.listKey: CompilerConfigurationKey<List<T>>
  get() {
    @Suppress("UNCHECKED_CAST")
    return compilerConfigurationKeysByName.getValue(name) as CompilerConfigurationKey<List<T>>
  }

internal fun <T : Any> RawMetroOption<T>.put(
  configuration: CompilerConfiguration,
  value: String,
) {
  if (allowMultipleOccurrences) {
    configuration.add(listKey, valueMapper(value))
  } else {
    configuration.put(key, valueMapper(value))
  }
}

internal fun CompilerConfiguration.metroOptionValue(option: MetroOption): Any =
  get(option.raw.key) ?: option.raw.defaultValue

internal fun MetroOptions.Companion.load(
  configuration: CompilerConfiguration,
  kotlinCompilerVersion: KotlinToolingVersion?,
  isIde: Boolean,
): MetroOptions = buildOptions {
  omitRedundantMirrors =
    kotlinCompilerVersion?.let(::kotlinVersionSupportsOmittingRedundantMirrors) == true

  for (entry in MetroOption.entries) {
    if (entry.raw.allowMultipleOccurrences) {
      for (value in configuration.getList(entry.raw.listKey)) {
        applyOptionValue(entry, value)
      }
    } else {
      configuration[entry.raw.key]?.let { applyOptionValue(entry, it) }
    }
  }

  val firHintOptionIsConfigured =
    configuration[MetroOption.GENERATE_CONTRIBUTION_HINTS_IN_FIR.raw.key] != null
  val firHintsAreRequiredByDefault =
    generateContributionHints &&
      (isIde || kotlinCompilerVersion?.let(::kotlinVersionSupportsTopLevelFirGen) == true)
  if (!firHintOptionIsConfigured && firHintsAreRequiredByDefault) {
    generateContributionHintsInFir = true
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

  val contributionHintsAreGeneratedInIr =
    generateContributionHints && !generateContributionHintsInFir
  val contributionProvidersAreEnabledWithoutFirHintGen =
    generateContributionProviders && contributionHintsAreGeneratedInIr && !generateClassesInIr
  if (contributionHintsAreGeneratedInIr && kotlinVersionSupportsTopLevelFirGen(compilerVersion)) {
    onError(
      "generateContributionHintsInFir cannot be disabled when generateContributionHints is enabled " +
        "on Kotlin $compilerVersion.",
    )
    valid = false
  } else if (contributionProvidersAreEnabledWithoutFirHintGen) {
    onError(
      "generateContributionProviders with generateContributionHints requires " +
        "generateContributionHintsInFir to also be enabled.",
    )
    valid = false
  }

  if (unusedGraphInputsSeverity.isIdeOnly) {
    onError(
      "unusedGraphInputsSeverity (set to ${unusedGraphInputsSeverity.name}) does not support IDE_WARN/IDE_ERROR " +
        "because the underlying check only runs during IR (CLI-only). Use WARN, ERROR, or NONE instead.",
    )
    valid = false
  }
  return valid
}

internal fun MetroOptions.validateDiagnosticLevels(
  configuration: CompilerConfiguration,
  onError: (String) -> Unit,
): Boolean {
  var valid = true
  val configuredNames =
    configuration.getList(MetroOption.DIAGNOSTIC_LEVEL.raw.listKey).flatMap {
      (it as Map<*, *>).keys
    }
  val duplicateNames = configuredNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
  for (name in duplicateNames) {
    onError("diagnostic-level is duplicated for $name.")
    valid = false
  }

  for (name in diagnosticLevels.keys) {
    if (name !in metroDiagnosticNames) {
      onError("diagnostic-level references unknown Metro diagnostic $name.")
      valid = false
    } else if (name in irReportedDiagnosticNames) {
      onError(
        "diagnostic-level does not support $name because it is reported in IR. " +
          "Only diagnostics reported in FIR are supported.",
      )
      valid = false
    }
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
        "Either disable ${jsICOptions.joinToString()} for JS targets or disable JS IC.",
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

private val MIN_KOTLIN_OMIT_REDUNDANT_MIRRORS = KotlinToolingVersion("2.4.0")

internal fun kotlinVersionSupportsOmittingRedundantMirrors(version: KotlinToolingVersion): Boolean {
  return version >= MIN_KOTLIN_OMIT_REDUNDANT_MIRRORS
}

internal fun kotlinVersionSupportsTopLevelFirGen(version: KotlinToolingVersion): Boolean {
  val isDevVersion = version.maturity == KotlinToolingVersion.Maturity.DEV
  return isTopLevelFirGenerationSupported(isDevVersion) { minimumVersion ->
    version >= KotlinToolingVersion(minimumVersion)
  }
}

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
