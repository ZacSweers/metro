/*
 * Copyright (C) 2016 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
class DoubleCheckTest {
  val doubleCheckReference = AtomicReference<Provider<Any>?>(null)
  val invocationCount = AtomicInt(0)

  @Test
  fun `double wrapping provider`() {
    assertSame(DOUBLE_CHECK_OBJECT_PROVIDER, DoubleCheck.provider(DOUBLE_CHECK_OBJECT_PROVIDER))
  }

  @Test
  fun `double wrapping lazy`() {
    assertSame<Any>(DOUBLE_CHECK_OBJECT_PROVIDER, DoubleCheck.lazy(DOUBLE_CHECK_OBJECT_PROVIDER))
  }

  @Test
  fun `reentrance returning same instance`() {
    val obj = Any()
    val doubleCheck =
      DoubleCheck.provider(
        Provider {
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
  fun `reentrance returning different instances throws IllegalStateException`() {
    val doubleCheck =
      DoubleCheck.provider(
        Provider {
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
  fun `instance factory as lazy does not wrap`() {
    val factory = InstanceFactory(Any())
    // We use toString() here because on JVM the boxed type is used
    assertEquals(factory.toString(), DoubleCheck.lazy(factory).toString())
  }

  @Test
  fun `isInitialized check for lazy works`() {
    val doubleCheck = DoubleCheck.lazy(Provider { Any() })
    assertFalse(doubleCheck.isInitialized())

    doubleCheck.value
    assertTrue(doubleCheck.isInitialized())
  }

  companion object {
    private val DOUBLE_CHECK_OBJECT_PROVIDER: Provider<Any> =
      DoubleCheck.provider(Provider { Any() })
  }
}
