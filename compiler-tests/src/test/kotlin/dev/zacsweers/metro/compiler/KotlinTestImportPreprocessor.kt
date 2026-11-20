// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.directives.AdditionalFilesDirectives.WITH_COROUTINES as WITH_COROUTINE_HELPERS
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure

class KotlinTestImportPreprocessor(testServices: TestServices) : ImportsPreprocessor(testServices) {
  /**
   * `helpers.runBlocking` supports suspend blocks that complete synchronously. This lets `box()`
   * return a string on JVM and JS and keeps kotlinx.coroutines off the fixture classpath.
   *
   * For tests that actually suspend, use `kotlinx.coroutines.test.runTest` in a test that can
   * return its asynchronous result on JS.
   */
  private val withCoroutineHelpers by lazy {
    WITH_COROUTINE_HELPERS in testServices.moduleStructure.allDirectives
  }

  override val additionalImports: Set<String> by lazy {
    buildSet {
      add("kotlin.test.*")
      if (withCoroutineHelpers) add("helpers.runBlocking")
    }
  }
}
