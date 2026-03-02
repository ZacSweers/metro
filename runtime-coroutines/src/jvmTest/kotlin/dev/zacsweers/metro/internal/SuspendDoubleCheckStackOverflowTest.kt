// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * This test is only possible to run in the JVM as it uses [StackOverflowError].
 *
 * Other platforms have undefined behavior in stack overflow scenarios.
 */
@OptIn(ExperimentalAtomicApi::class)
class SuspendDoubleCheckStackOverflowTest {
  val doubleCheckReference = AtomicReference<SuspendProvider<Any>?>(null)

  @Test
  fun `reentrance without condition throws stack overflow`() = runTest {
    val doubleCheck =
      SuspendDoubleCheck.provider(SuspendProvider { doubleCheckReference.load()!!.invoke() })
    doubleCheckReference.store(doubleCheck)
    assertFailsWith<StackOverflowError> { doubleCheck() }
  }
}
