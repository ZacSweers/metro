// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.atomicfu.atomic

/**
 * This test is only possible to run in the JVM as it uses [StackOverflowError].
 *
 * Other platforms have undefined behavior in stack overflow scenarios.
 */
class DoubleCheckStackOverflowTest {
  val doubleCheckReference = atomic<Provider<Any>?>(null)

  @Test
  fun `reentrance without condition throws stack overflow`() {
    val doubleCheck = DoubleCheck.provider(Provider { doubleCheckReference.value!!.invoke() })
    doubleCheckReference.value = doubleCheck
    assertFailsWith<StackOverflowError> { doubleCheck() }
  }
}
