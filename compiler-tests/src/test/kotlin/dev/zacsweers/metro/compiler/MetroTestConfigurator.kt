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

    // COMPILER_VERSION supersedes MIN/MAX_COMPILER_VERSION
    targetKotlinVersion(testServices)?.let { (targetVersion, requiresFullMatch) ->
      return !versionMatches(targetVersion, requiresFullMatch, COMPILER_VERSION)
    }

    // Min/max version checks use KotlinVersion which compares only major.minor.patch numerically,
    // ignoring classifiers. This means dev builds like "2.4.0-dev-1234" are treated as equal to
    // "2.4.0" for comparison purposes, so MIN_COMPILER_VERSION: 2.4 correctly includes dev builds.
    val directives = testServices.moduleStructure.allDirectives

    val minVersion =
      directives[MetroDirectives.MIN_COMPILER_VERSION].firstOrNull()?.let {
        KotlinVersion.parse(it).first
      }
    if (minVersion != null && COMPILER_VERSION < minVersion) return true

    val maxVersion =
      directives[MetroDirectives.MAX_COMPILER_VERSION].firstOrNull()?.let {
        KotlinVersion.parse(it).first
      }
    if (maxVersion != null && COMPILER_VERSION > maxVersion) return true

    return false
  }
}

fun RegisteredDirectivesBuilder.commonMetroTestDirectives() {
  OPT_IN.with("dev.zacsweers.metro.ExperimentalMetroApi")
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
