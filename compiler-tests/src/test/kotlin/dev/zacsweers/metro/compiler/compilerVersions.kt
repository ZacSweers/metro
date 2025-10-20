// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure

fun targetKotlinVersionString(testServices: TestServices): String? {
  return testServices.moduleStructure.allDirectives[MetroDirectives.COMPILER_VERSION].firstOrNull()
}

fun targetKotlinVersion(testServices: TestServices): KotlinVersion? {
  val versionString = targetKotlinVersionString(testServices) ?: return null
  return KotlinVersion.parse(versionString)
}

fun KotlinVersion.Companion.parse(versionString: String): KotlinVersion {
  return versionString.substringBefore('-').split('.').let { (major, minor, patch) ->
    KotlinVersion(major.toInt(), minor.toInt(), patch.toInt())
  }
}
