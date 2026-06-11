// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import dev.zacsweers.metro.compiler.test.BUILD_COMPILER_VERSION
import dev.zacsweers.metro.compiler.test.COMPILER_TOOLING_VERSION
import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import dev.zacsweers.metro.compiler.test.TEST_COMPILER_VERSION
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

    if (
      testServices.isJsBackend() && TEST_COMPILER_TOOLING_VERSION != BUILD_COMPILER_TOOLING_VERSION
    ) {
      // JS box tests compile against Metro's runtime KLIB. KLIB compatibility depends on the
      // producing compiler, so only run these tests against the compiler version that built it.
      return true
    }

    System.getProperty("metro.singleTestName")?.let { singleTest ->
      return testServices.testInfo.methodName != singleTest
    }

    val directives = testServices.moduleStructure.allDirectives
    val generateClassesInIrDirectives = directives[MetroDirectives.GENERATE_CLASSES_IN_IR]
    if (
      generateClassesInIrDirectives.any { it.toString().toBoolean() } &&
        generateClassesInIrDirectives.any { !it.toString().toBoolean() }
    ) {
      return true
    }
    val generateClassesInIr =
      generateClassesInIrDirectives.lastOrNull()?.toString()?.toBoolean() == true
    val irOnlyClassesSuite =
      testServices.testInfo.className.substringAfterLast('.').substringBefore('$') ==
        "IrOnlyClassesBoxTestGenerated"
    if (
      irOnlyClassesSuite &&
        shouldSkipForCompilerVersion(
          compilerVersion = COMPILER_VERSION,
          compilerToolingVersion = KotlinToolingVersion(COMPILER_TOOLING_VERSION),
          minVersion = MIN_IR_ONLY_CLASSES_COMPILER_VERSION,
        )
    ) {
      return true
    }
    if (
      (MetroDirectives.ENABLE_CIRCUIT in directives ||
        MetroDirectives.ENABLE_HILT_INTEROP in directives ||
        MetroDirectives.ENABLE_HILT_KSP in directives) && generateClassesInIr
    ) {
      return true
    }
    return shouldSkipForCompilerVersion(
      compilerVersion = COMPILER_VERSION,
      compilerToolingVersion = KotlinToolingVersion(COMPILER_TOOLING_VERSION),
      targetVersion = directives[MetroDirectives.COMPILER_VERSION].firstOrNull(),
      minVersion = directives[MetroDirectives.MIN_COMPILER_VERSION].firstOrNull(),
      maxVersion = directives[MetroDirectives.MAX_COMPILER_VERSION].firstOrNull(),
    )
  }

  companion object {
    private const val MIN_IR_ONLY_CLASSES_COMPILER_VERSION = "2.4.20-dev-6138"

    /**
     * Determines whether a test should be skipped based on compiler version directives.
     *
     * [targetVersion] (from `COMPILER_VERSION`) supersedes [minVersion]/[maxVersion] — if set, the
     * min/max directives are ignored.
     *
     * Version comparisons use [KotlinToolingVersion] so dev build thresholds like `2.4.20-dev-6138`
     * compare against their classifier/build number instead of collapsing to `2.4.20`.
     */
    fun shouldSkipForCompilerVersion(
      compilerVersion: KotlinVersion,
      compilerToolingVersion: KotlinToolingVersion =
        KotlinToolingVersion(
          compilerVersion.major,
          compilerVersion.minor,
          compilerVersion.patch,
          classifier = null,
        ),
      targetVersion: String? = null,
      minVersion: String? = null,
      maxVersion: String? = null,
    ): Boolean {
      // COMPILER_VERSION supersedes MIN/MAX_COMPILER_VERSION
      if (targetVersion != null) {
        val (target, requiresFullMatch) = toolingVersionDirective(targetVersion)
        return !versionMatches(target, requiresFullMatch, compilerToolingVersion)
      }

      val min = minVersion?.let { toolingVersionDirective(it).first }
      if (min != null && compilerToolingVersion < min) return true

      val max = maxVersion?.let { toolingVersionDirective(it).first }
      if (max != null && compilerToolingVersion > max) return true

      return false
    }
  }
}

private val BUILD_COMPILER_TOOLING_VERSION = KotlinToolingVersion(BUILD_COMPILER_VERSION)
private val TEST_COMPILER_TOOLING_VERSION = KotlinToolingVersion(TEST_COMPILER_VERSION)

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
  targetVersion: KotlinToolingVersion,
  requiresFullMatch: Boolean,
  actualVersion: KotlinToolingVersion,
): Boolean {
  if (targetVersion.major != actualVersion.major) return false
  if (targetVersion.minor != actualVersion.minor) return false
  if (requiresFullMatch && targetVersion.patch != actualVersion.patch) return false
  if (requiresFullMatch && targetVersion.classifier != null) {
    return targetVersion.classifier.equals(actualVersion.classifier, ignoreCase = true)
  }
  return true
}

private fun toolingVersionDirective(versionString: String): Pair<KotlinToolingVersion, Boolean> {
  val versionPart = versionString.substringBefore('-')
  val parts = versionPart.split('.')
  val requiresFullMatch = parts.size == 3
  return KotlinToolingVersion(versionString) to requiresFullMatch
}
