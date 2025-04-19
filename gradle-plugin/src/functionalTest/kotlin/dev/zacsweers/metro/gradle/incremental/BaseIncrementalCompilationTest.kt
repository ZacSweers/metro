// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import java.io.File
import org.intellij.lang.annotations.Language

abstract class BaseIncrementalCompilationTest {

  protected fun modifyKotlinFile(
      rootDir: File,
      packageName: String,
      fileName: String,
      @Language("kotlin") content: String,
  ) {
    val packageDir = packageName.replace('.', '/')
    val filePath = "src/main/kotlin/$packageDir/$fileName"
    rootDir.resolve(filePath).writeText(content)
  }
}
