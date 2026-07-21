// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.services.TestServices

class KotlinTestImportPreprocessor(testServices: TestServices) : ImportsPreprocessor(testServices) {
  /**
   * The compiler test framework's helper only supports suspend blocks that complete synchronously.
   * That is intentional for these box tests: it works with `fun box(): String` on JVM and JS, and
   * keeps kotlinx-coroutines-test and kotlinx-coroutines-core off fixture classpaths that are
   * verifying they are not required.
   *
   * `runTest` is also awkward on JS: its asynchronous `TestResult` must be returned immediately,
   * but compiler box tests must return a synchronous `String`.
   *
   * Tests of actual suspension, scheduling, or delays should use kotlinx.coroutines.test.runTest
   * instead.
   *
   * Note we use this helper runBlocking since there's no standard runBlocking for web. It's a
   * simple helper good enough for box tests.
   */
  override val additionalImports = setOf("helpers.runBlocking", "kotlin.test.*")
}
