// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.services.TestServices

class KotlinTestImportPreprocessor(testServices: TestServices) : ImportsPreprocessor(testServices) {
  override val additionalImports = setOf("kotlin.test.*")
}
