/*
 * Copyright (C) 2015 The Dagger Authors.
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
package dev.zacsweers.metro.test.integration

import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.Uninterruptibles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import java.lang.Thread.State.BLOCKED
import java.lang.Thread.State.WAITING
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalAtomicApi::class)
class DoubleCheckCycleTest {
  /** A qualifier for a reentrant scoped binding. */
  @Qualifier annotation class Reentrant

  /** A module to be overridden in each test. */
  @BindingContainer
  class OverrideModule(
    private val provideAny: () -> Any = { fail("This method should be overridden in tests") },
    private val provideReentrantAny: (Provider<Any>) -> Any = {
      fail("This method should be overridden in tests")
    },
  ) {
    @Provides
    @SingleIn(AppScope::class)
    fun provideAny(): Any {
      return provideAny.invoke()
    }

    @Provides
    @SingleIn(AppScope::class)
    @Reentrant
    fun provideReentrantAny(@Reentrant provider: Provider<Any>): Any {
      return provideReentrantAny.invoke(provider)
    }
  }

  @DependencyGraph(AppScope::class)
  interface TestComponent {
    val obj: Any
    @Reentrant val reentrantAny: Any

    @DependencyGraph.Factory
    interface Factory {
      fun create(@Includes overrides: OverrideModule): TestComponent
    }
  }

  @Test
  fun testNonReentrant() {
    val callCount = AtomicInt(0)

    // Provides a non-reentrant binding. The provides method should only be called once.
    val component: TestComponent =
      createGraphFactory<TestComponent.Factory>()
        .create(
          OverrideModule(
            provideAny = {
              callCount.fetchAndIncrement()
              Any()
            }
          )
        )

    assertEquals(0, callCount.load())
    val first: Any = component.obj
    assertEquals(1, callCount.load())
    val second: Any = component.obj
    assertEquals(1, callCount.load())
    assertSame(second, first)
  }

  @Test
  fun testReentrant() {
    val callCount = AtomicInt(0)

    // Provides a reentrant binding. Even though it's scoped, the provides method is called twice.
    // In this case, we allow it since the same instance is returned on the second call.
    val component: TestComponent =
      createGraphFactory<TestComponent.Factory>()
        .create(
          OverrideModule(
            provideReentrantAny = { provider ->
              if (callCount.incrementAndFetch() == 1) {
                provider()
              } else {
                Any()
              }
            }
          )
        )

    assertEquals(0, callCount.load())
    val first: Any = component.reentrantAny
    assertEquals(2, callCount.load())
    val second: Any = component.reentrantAny
    assertEquals(2, callCount.load())
    assertSame(second, first)
  }

  @Test
  fun testFailingReentrant() {
    val callCount = AtomicInt(0)

    // Provides a failing reentrant binding. Even though it's scoped, the provides method is called
    // twice. In this case we throw an exception since a different instance is provided on the
    // second call.
    val component: TestComponent =
      createGraphFactory<TestComponent.Factory>()
        .create(
          OverrideModule(
            provideReentrantAny = { provider ->
              if (callCount.incrementAndFetch() == 1) {
                provider()
              }
              Any()
            }
          )
        )

    assertEquals(0, callCount.load())
    val t = assertFailsWith<IllegalStateException> { component.reentrantAny }
    assertContains(t.message!!, "Scoped provider was invoked recursively")
    assertEquals(2, callCount.load())
  }

  @Test(timeout = 5000)
  fun testGetFromMultipleThreads() {
    val callCount = AtomicInt(0)
    val requestCount = AtomicInt(0)
    val future: SettableFuture<Any> = SettableFuture.create()

    // Provides a non-reentrant binding. In this case, we return a SettableFuture so that we can
    // control when the provides method returns.
    val component: TestComponent =
      createGraphFactory<TestComponent.Factory>()
        .create(
          OverrideModule(
            provideAny = {
              callCount.incrementAndFetch()
              Uninterruptibles.getUninterruptibly(future)
            }
          )
        )

    val numThreads = 10
    val remainingTasks = CountDownLatch(numThreads)
    val tasks = ArrayList<Thread>(numThreads)
    val values: MutableList<Any> = Collections.synchronizedList(ArrayList(numThreads))

    // Set up multiple threads that call component.getAny().
    repeat(numThreads) {
      tasks.add(
        Thread {
          requestCount.incrementAndFetch()
          values.add(component.obj)
          remainingTasks.countDown()
        }
      )
    }

    // Check initial conditions
    assertEquals(10, remainingTasks.count)
    assertEquals(0, requestCount.load())
    assertEquals(0, callCount.load())
    assertTrue(values.isEmpty())

    // Start all threads
    tasks.forEach(Thread::start)

    // Wait for all threads to wait/block.
    var waiting: Long = 0
    while (waiting != numThreads.toLong()) {
      waiting =
        tasks
          .stream()
          .map(Thread::getState)
          .filter({ state -> state == WAITING || state == BLOCKED })
          .count()
    }

    // Check the intermediate state conditions.
    // * All 10 threads should have requested the binding, but none should have finished.
    // * Only 1 thread should have reached the provides method.
    // * None of the threads should have set a value (since they are waiting for future to be set).
    assertEquals(10, remainingTasks.count)
    assertEquals(10, requestCount.load())
    assertEquals(1, callCount.load())
    assertTrue(values.isEmpty())

    // Set the future and wait on all remaining threads to finish.
    val futureValue = Any()
    future.set(futureValue)
    remainingTasks.await()

    // Check the final state conditions.
    // All values should be set now, and they should all be equal to the same instance.
    assertEquals(0, remainingTasks.count)
    assertEquals(10, requestCount.load())
    assertEquals(1, callCount.load())
    assertEquals(Collections.nCopies(numThreads, futureValue), values)
  }
}
