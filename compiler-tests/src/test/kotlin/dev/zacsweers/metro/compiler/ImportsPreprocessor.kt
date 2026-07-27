// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.services.ReversibleSourceFilePreprocessor
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.isJavaFile

abstract class ImportsPreprocessor(testServices: TestServices) :
  ReversibleSourceFilePreprocessor(testServices) {

  abstract val additionalImports: Set<String>

  private val additionalImportsString by lazy {
    additionalImports.sorted().joinToString(separator = "\n") { "import $it" }
  }

  final override fun process(file: TestFile, content: String): String {
    if (file.isAdditional) return content
    if (file.isJavaFile) return content

    val lines = content.lines().toMutableList()
    when (val packageIndex = lines.indexOfFirst { it.startsWith("package ") }) {
      -1 -> {
        val fileAnnotationIndex = lines.indexOfLast { it.trimStart().startsWith("@file:") }
        val importIndex =
          if (fileAnnotationIndex >= 0) {
            // File annotations must precede imports.
            fileAnnotationIndex + 1
          } else {
            val nonBlankIndex = lines.indexOfFirst { it.isNotBlank() }
            if (nonBlankIndex >= 0) nonBlankIndex else 0
          }
        lines.add(importIndex, additionalImportsString)
      }

      // Place imports just after package declaration.
      else -> lines.add(packageIndex + 1, additionalImportsString)
    }
    return lines.joinToString(separator = "\n")
  }

  final override fun revert(file: TestFile, actualContent: String): String {
    if (file.isAdditional) return actualContent
    if (file.isJavaFile) return actualContent
    return actualContent.replace(additionalImportsString + "\n", "")
  }
}
