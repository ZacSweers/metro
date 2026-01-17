// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import dev.zacsweers.metro.compiler.test.OVERRIDE_COMPILER_VERSION

fun main() {
  val targetCompilerVersion = COMPILER_VERSION
  val versionString = targetCompilerVersion.toString().filterNot { it == '.' }

  val exclusionPattern =
    if (OVERRIDE_COMPILER_VERSION.toBoolean()) {
      // Exclude files with .k<version> where version != targetCompilerVersion
      // Pattern must match the full filename (with ^ and $ anchors)
      // language=RegExp
      """^(.+)\.k(?!$versionString\b)\w+\.kt(s)?$"""
    } else {
      null
    }

  generateTests<
    AbstractBoxTest,
    AbstractDiagnosticTest,
    AbstractFirDumpTest,
    AbstractIrDumpTest,
    AbstractReportsTest,
  >(exclusionPattern)
}
