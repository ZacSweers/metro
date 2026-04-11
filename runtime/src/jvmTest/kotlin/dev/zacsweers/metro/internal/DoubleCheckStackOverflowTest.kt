// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * This test is only possible to run in the JVM as it uses [StackOverflowError].
 *
 * Other platforms have undefined behavior in stack overflow scenarios.
 */
@OptIn(ExperimentalAtomicApi::class)
class DoubleCheckStackOverflowTest {
  val doubleCheckReference = AtomicReference<Provider<Any>?>(null)

  @Test
  fun `reentrance without condition throws stack overflow`() {
    val doubleCheck = DoubleCheck.provider(Provider { doubleCheckReference.load()!!.invoke() })
    doubleCheckReference.store(doubleCheck)
    assertFailsWith<StackOverflowError> { doubleCheck() }
  }
}
