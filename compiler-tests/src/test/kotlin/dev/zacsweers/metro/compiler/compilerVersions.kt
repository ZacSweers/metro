// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure

/**
 * True if the Kotlin compiler we're testing against still emits diagnostic / IR dump goldens with
 * the legacy `.fir.` infix (e.g. `<name>.fir.diag.txt`). KT-85292 dropped that infix in Kotlin
 * 2.4.20 as part of the K1 removal effort.
 *
 * Drives the diagnostic-handler shim wired in by [AbstractDiagnosticTest]. Once Metro's floor moves
 * to 2.4.20+, this constant, the two `Metro*DiagnosticsHandler` classes, and the inline configure
 * path can all be deleted.
 */
internal val NEEDS_LEGACY_GOLDEN_NAMING: Boolean = COMPILER_VERSION < KotlinVersion(2, 4, 20)

fun targetKotlinVersionString(testServices: TestServices): String? {
  return testServices.moduleStructure.allDirectives[MetroDirectives.COMPILER_VERSION].firstOrNull()
}

/**
 * Parses the target version and returns a pair of (version, requiresFullMatch).
 * - "2.2" -> (KotlinVersion(2, 2, 0), false) - matches any 2.2.x
 * - "2.2.0" -> (KotlinVersion(2, 2, 0), true) - matches only 2.2.0
 * - "2.2.0-Beta1" -> (KotlinVersion(2, 2, 0), true) - matches only 2.2.0
 */
fun targetKotlinVersion(testServices: TestServices): Pair<KotlinVersion, Boolean>? {
  val versionString = targetKotlinVersionString(testServices) ?: return null
  return KotlinVersion.parse(versionString)
}

/**
 * Parses a version string and returns a pair of (KotlinVersion, requiresFullMatch). The boolean
 * indicates whether all specified version components must match.
 *
 * Examples:
 * - "2.2" -> only major and minor must match
 * - "2.2.0" -> major, minor, and patch must match
 */
fun KotlinVersion.Companion.parse(versionString: String): Pair<KotlinVersion, Boolean> {
  val versionPart = versionString.substringBefore('-')
  val parts = versionPart.split('.')

  return when (parts.size) {
    2 -> {
      val (major, minor) = parts
      Pair(KotlinVersion(major.toInt(), minor.toInt(), 0), false)
    }
    3 -> {
      val (major, minor, patch) = parts
      Pair(KotlinVersion(major.toInt(), minor.toInt(), patch.toInt()), true)
    }
    else -> error("Invalid version string: $versionString")
  }
}
