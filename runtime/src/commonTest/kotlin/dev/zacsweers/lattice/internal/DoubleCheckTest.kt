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
package dev.zacsweers.lattice.internal

import dev.zacsweers.lattice.Provider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.atomicfu.atomic

class DoubleCheckTest {
  val doubleCheckReference = atomic<Provider<Any>?>(null)
  val invocationCount = atomic(0)

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
          if (invocationCount.incrementAndGet() == 1) {
            doubleCheckReference.value!!.invoke()
          }
          obj
        }
      )
    doubleCheckReference.value = doubleCheck
    assertSame(obj, doubleCheck())
  }

  @Test
  fun `reentrance returning different instances throws IllegalStateException`() {
    val doubleCheck =
      DoubleCheck.provider(
        Provider {
          if (invocationCount.incrementAndGet() == 1) {
            doubleCheckReference.value!!.invoke()
          }
          Any()
        }
      )
    doubleCheckReference.value = doubleCheck
    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `instance factory as lazy does not wrap`() {
    val factory = InstanceFactory.create(Any())
    assertSame<Any>(factory, DoubleCheck.lazy(factory))
  }

  companion object {
    private val DOUBLE_CHECK_OBJECT_PROVIDER: Provider<Any> =
      DoubleCheck.provider(Provider { Any() })
  }
}
