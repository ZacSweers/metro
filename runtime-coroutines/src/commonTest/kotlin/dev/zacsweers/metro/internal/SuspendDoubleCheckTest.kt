// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalAtomicApi::class)
class SuspendDoubleCheckTest {
  val doubleCheckReference = AtomicReference<SuspendProvider<Any>?>(null)
  val invocationCount = AtomicInt(0)

  @Test
  fun `double wrapping provider`() {
    val provider = SuspendDoubleCheck.provider(SuspendProvider { Any() })
    assertSame(provider, SuspendDoubleCheck.provider(provider))
  }

  @Test
  fun `reentrance returning same instance`() = runTest {
    val obj = Any()
    val doubleCheck =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          if (invocationCount.incrementAndFetch() == 1) {
            doubleCheckReference.load()!!.invoke()
          }
          obj
        }
      )
    doubleCheckReference.store(doubleCheck)
    assertSame(obj, doubleCheck())
  }

  @Test
  fun `reentrance returning different instances throws IllegalStateException`() = runTest {
    val doubleCheck =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          if (invocationCount.incrementAndFetch() == 1) {
            doubleCheckReference.load()!!.invoke()
          }
          Any()
        }
      )
    doubleCheckReference.store(doubleCheck)
    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `isInitialized works`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider(SuspendProvider { Any() })
    assertFalse((doubleCheck as SuspendDoubleCheck<*>).isInitialized())

    doubleCheck()
    assertTrue((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
  }
}
