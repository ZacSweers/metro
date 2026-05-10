// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import org.jetbrains.kotlin.test.builders.RegisteredDirectivesBuilder
import org.jetbrains.kotlin.test.directives.LanguageSettingsDirectives.OPT_IN
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.services.MetaTestConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.testInfo

class MetroTestConfigurator(testServices: TestServices) : MetaTestConfigurator(testServices) {
  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(MetroDirectives)

  override fun shouldSkipTest(): Boolean {
    if (MetroDirectives.METRO_IGNORE in testServices.moduleStructure.allDirectives) return true

    System.getProperty("metro.singleTestName")?.let { singleTest ->
      return testServices.testInfo.methodName != singleTest
    }

    val directives = testServices.moduleStructure.allDirectives
    return shouldSkipForCompilerVersion(
      compilerVersion = COMPILER_VERSION,
      targetVersion = directives[MetroDirectives.COMPILER_VERSION].firstOrNull(),
      minVersion = directives[MetroDirectives.MIN_COMPILER_VERSION].firstOrNull(),
      maxVersion = directives[MetroDirectives.MAX_COMPILER_VERSION].firstOrNull(),
    )
  }

  companion object {
    /**
     * Determines whether a test should be skipped based on compiler version directives.
     *
     * [targetVersion] (from `COMPILER_VERSION`) supersedes [minVersion]/[maxVersion] — if set, the
     * min/max directives are ignored.
     *
     * Version comparisons use [KotlinVersion] which compares only major.minor.patch numerically,
     * ignoring classifiers. This means dev builds like "2.4.0-dev-1234" are treated as equal to
     * "2.4.0" for comparison purposes, so `MIN_COMPILER_VERSION: 2.4` correctly includes dev
     * builds.
     */
    fun shouldSkipForCompilerVersion(
      compilerVersion: KotlinVersion,
      targetVersion: String? = null,
      minVersion: String? = null,
      maxVersion: String? = null,
    ): Boolean {
      // COMPILER_VERSION supersedes MIN/MAX_COMPILER_VERSION
      if (targetVersion != null) {
        val (target, requiresFullMatch) = KotlinVersion.parse(targetVersion)
        return !versionMatches(target, requiresFullMatch, compilerVersion)
      }

      val min = minVersion?.let { KotlinVersion.parse(it).first }
      if (min != null && compilerVersion < min) return true

      val max = maxVersion?.let { KotlinVersion.parse(it).first }
      if (max != null && compilerVersion > max) return true

      return false
    }
  }
}

fun RegisteredDirectivesBuilder.commonMetroTestDirectives() {
  OPT_IN.with("dev.zacsweers.metro.ExperimentalMetroApi")
  OPT_IN.with("dev.zacsweers.metro.DelicateMetroApi")
}

/**
 * Checks if the target version matches the actual compiler version.
 *
 * @param targetVersion The parsed target version
 * @param requiresFullMatch Whether all components (major, minor, patch) must match. If false, only
 *   major and minor are compared.
 * @param actualVersion The actual compiler version
 */
private fun versionMatches(
  targetVersion: KotlinVersion,
  requiresFullMatch: Boolean,
  actualVersion: KotlinVersion,
): Boolean {
  if (targetVersion.major != actualVersion.major) return false
  if (targetVersion.minor != actualVersion.minor) return false
  if (requiresFullMatch && targetVersion.patch != actualVersion.patch) return false
  return true
}
