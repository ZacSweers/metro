// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import org.intellij.lang.annotations.Language

fun main() {
  val targetCompilerVersion = COMPILER_VERSION
  val versionString = targetCompilerVersion.toString().filterNot { it == '.' }

  // Exclude files with .k<version> where version != targetCompilerVersion
  // Pattern must match the full filename (with ^ and $ anchors)
  @Language("RegExp")
  val exclusionPattern = """^(.+)\.k(?!$versionString\b)\w+\.kts?$"""
  generateTests<AbstractBoxTest, AbstractDiagnosticTest, AbstractFirDumpTest, AbstractIrDumpTest>(
    exclusionPattern
  )
}
