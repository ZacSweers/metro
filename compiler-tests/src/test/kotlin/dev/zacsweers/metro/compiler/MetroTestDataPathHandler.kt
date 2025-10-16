// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import java.io.File
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.fir.TestDataFileReplacer
import org.jetbrains.kotlin.test.utils.originalTestDataFileName

/**
 * If [MetroDirectives.CUSTOM_TEST_DATA_PER_COMPILER_VERSION] is enabled in the test, generates test
 * data files per [COMPILER_VERSION]. Useful for dump tests that have different outputs per compiler
 * version.
 *
 * Currently has an unfortunate behavior of also generating a new-suffixed version of the .kt file too.
 */
class MetroTestDataPathHandler(testServices: TestServices) : TestDataFileReplacer(testServices) {
  override fun shouldReplaceFile(originalFile: File): Boolean {
    return originalFile.useLines { lines ->
      lines.any { it == "// ${MetroDirectives.CUSTOM_TEST_DATA_PER_COMPILER_VERSION.name}" }
    }
  }

  override val File.newFile: File
    get() {
      return getCustomTestDataFileWithPrefix(".${COMPILER_VERSION.toString().replace(".", "")}")
    }

  // Copied utils
  private val File.extensionWithDot: String
    get() = ".$extension"

  private fun File.isCustomTestDataWithPrefix(prefix: String): Boolean =
    name.endsWith("$prefix$extensionWithDot")

  private fun File.getCustomTestDataFileWithPrefix(prefix: String): File =
    if (isCustomTestDataWithPrefix(prefix)) this
    else {
      // Because `File` can be `.ll.kt` or `.fir.kt` test data, we have to go off
      // `originalTestDataFileName`, which removes the prefix
      // intelligently.
      val originalName = originalTestDataFileName
      val extension = extensionWithDot
      val customName = "${originalName.removeSuffix(extension)}$prefix$extension"
      parentFile.resolve(customName)
    }
}
