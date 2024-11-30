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

import com.google.common.collect.Sets
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Uninterruptibles
import dev.zacsweers.lattice.Provider
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith

// TODO convert to coroutines test instead?
class DoubleCheckTest {

  @Test
  fun `double wrapping provider`() {
    assertThat(DoubleCheck.provider(DOUBLE_CHECK_OBJECT_PROVIDER))
      .isSameInstanceAs(DOUBLE_CHECK_OBJECT_PROVIDER)
  }

  @Test
  fun `double wrapping lazy`() {
    assertThat(DoubleCheck.lazy(DOUBLE_CHECK_OBJECT_PROVIDER))
      .isSameInstanceAs(DOUBLE_CHECK_OBJECT_PROVIDER)
  }

  @Test
  fun get() {
    val numThreads = 10
    val executor = Executors.newFixedThreadPool(numThreads)

    val latch = CountDownLatch(numThreads)
    val provider = LatchedProvider(latch)
    val lazy = DoubleCheck.lazy(provider)

    val tasks =
      List<Callable<Any>>(numThreads) {
        Callable {
          latch.countDown()
          lazy.value
        }
      }

    val futures = executor.invokeAll(tasks)

    assertThat(provider.provisions.get()).isEqualTo(1)
    val results: MutableSet<Any> = Sets.newIdentityHashSet()
    for (future in futures) {
      results.add(future.get())
    }
    assertThat(results).hasSize(1)
  }

  private class LatchedProvider(val latch: CountDownLatch?) : Provider<Any> {
    val provisions = AtomicInteger()

    override fun invoke(): Any {
      if (latch != null) {
        Uninterruptibles.awaitUninterruptibly(latch)
      }
      provisions.incrementAndGet()
      return Any()
    }
  }

  @Test
  fun `reentrance without condition throws stack overflow`() {
    val doubleCheckReference: AtomicReference<Provider<Any>> = AtomicReference()
    val doubleCheck: Provider<Any> = DoubleCheck.provider(Provider { doubleCheckReference.get()() })
    doubleCheckReference.set(doubleCheck)
    assertFailsWith<StackOverflowError> { doubleCheck() }
  }

  @Test
  fun `reentrance returning same instance`() {
    val doubleCheckReference: AtomicReference<Provider<Any>> = AtomicReference()
    val invocationCount = AtomicInteger()
    val obj = Any()
    val doubleCheck: Provider<Any> =
      DoubleCheck.provider(
        Provider {
          if (invocationCount.incrementAndGet() == 1) {
            doubleCheckReference.get()()
          }
          obj
        }
      )
    doubleCheckReference.set(doubleCheck)
    assertThat(doubleCheck()).isSameInstanceAs(obj)
  }

  @Test
  fun `reentrance returning different instances throws IllegalStateException`() {
    val doubleCheckReference = AtomicReference<Provider<Any>>()
    val invocationCount = AtomicInteger()
    val doubleCheck =
      DoubleCheck.provider(
        Provider {
          if (invocationCount.incrementAndGet() == 1) {
            doubleCheckReference.get()()
          }
          Any()
        }
      )
    doubleCheckReference.set(doubleCheck)
    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `instance factory as lazy does not wrap`() {
    val factory: Factory<Any> = InstanceFactory.create(Any())
    assertThat(DoubleCheck.lazy(factory)).isSameInstanceAs(factory)
  }

  companion object {
    private val DOUBLE_CHECK_OBJECT_PROVIDER: Provider<Any> =
      DoubleCheck.provider(Provider { Any() })
  }
}
