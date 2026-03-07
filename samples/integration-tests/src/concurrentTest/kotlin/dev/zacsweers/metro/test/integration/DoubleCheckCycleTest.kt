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

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalAtomicApi::class)
class DoubleCheckCycleTest {
  /** A qualifier for a reentrant scoped binding. */
  @Qualifier annotation class Reentrant

  @Scope annotation class ReentrantScope

  /** A module to be overridden in each test. */
  @BindingContainer
  class OverrideModule(
    private val provideAny: () -> Any = { fail("This method should be overridden in tests") },
    private val provideReentrantAny: (Provider<Any>) -> Any = {
      fail("This method should be overridden in tests")
    },
  ) {

    @Provides
    @SingleIn(ReentrantScope::class)
    fun provideAny(): Any {
      return provideAny.invoke()
    }

    @Provides
    @SingleIn(ReentrantScope::class)
    @Reentrant
    fun provideReentrantAny(@Reentrant provider: Provider<Any>): Any {
      return provideReentrantAny.invoke(provider)
    }
  }

  @DependencyGraph(ReentrantScope::class)
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

  @Test
  fun testGetFromMultipleThreads() = runBlocking {
    val callCount = AtomicInt(0)
    val requestCount = AtomicInt(0)
    val deferred = CompletableDeferred<Any>()

    // Provides a non-reentrant binding. In this case, we return a CompletableDeferred so that we
    // can control when the provides method returns.
    val component: TestComponent =
      createGraphFactory<TestComponent.Factory>()
        .create(
          OverrideModule(
            provideAny = {
              callCount.incrementAndFetch()
              runBlocking { deferred.await() }
            }
          )
        )

    val numCoroutines = 10
    val completedCount = AtomicInt(0)
    val mutex = Mutex()
    val values = mutableListOf<Any>()

    // Set up multiple coroutines that call component.obj.
    // Use Dispatchers.Default to get actual parallelism across threads.
    val jobs =
      List(numCoroutines) {
        launch(Dispatchers.Default) {
          requestCount.incrementAndFetch()
          val value = component.obj
          mutex.withLock { values.add(value) }
          completedCount.incrementAndFetch()
        }
      }

    // Check initial conditions
    assertEquals(0, completedCount.load())

    // Wait for all coroutines to reach the blocking point.
    // One coroutine should enter the provider, others should be waiting on the lock.
    while (callCount.load() < 1) {
      delay(1)
    }
    // Give time for other coroutines to queue up waiting on the synchronized block
    delay(100)

    // Check the intermediate state conditions.
    // * Only 1 coroutine should have reached the provides method.
    // * None of the coroutines should have completed (since they are waiting for deferred).
    assertEquals(0, completedCount.load())
    assertEquals(1, callCount.load())
    assertTrue(values.isEmpty())

    // Complete the deferred and wait on all coroutines to finish.
    val futureValue = Any()
    deferred.complete(futureValue)
    jobs.joinAll()

    // Check the final state conditions.
    // All values should be set now, and they should all be equal to the same instance.
    assertEquals(numCoroutines, completedCount.load())
    assertEquals(1, callCount.load())
    assertEquals(numCoroutines, values.size)
    values.forEach { assertSame(futureValue, it) }
  }
}
