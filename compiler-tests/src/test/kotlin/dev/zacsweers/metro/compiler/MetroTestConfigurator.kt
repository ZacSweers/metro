// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.services.MetaTestConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.testInfo

class MetroTestConfigurator(testServices: TestServices) : MetaTestConfigurator(testServices) {
  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(MetroDirectives)

  override fun shouldSkipTest(): Boolean {
    val enabled =
      testServices.moduleStructure.allDirectives[MetroDirectives.ENABLE_IF_PROPERTY_SET]
        .firstOrNull()
        ?.let { property -> System.getProperty(property, "false")?.toBooleanStrict() == true }
        ?: false
    if (!enabled) return true

    System.getProperty("metro.singleTestName")?.let { singleTest ->
      return testServices.testInfo.methodName != singleTest
    }

    val (targetVersion, requiresFullMatch) = targetKotlinVersion(testServices) ?: return false
    return !versionMatches(targetVersion, requiresFullMatch, COMPILER_VERSION)
  }
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
