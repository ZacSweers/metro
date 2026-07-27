// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendProvider
import dev.zacsweers.metro.suspendProviderOf
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@OptIn(ExperimentalAtomicApi::class)
class SuspendDoubleCheckTest {
  val doubleCheckReference = AtomicReference<SuspendProvider<Any>?>(null)
  val invocationCount = AtomicInt(0)

  @Test
  fun `double wrapping provider`() {
    val provider = SuspendDoubleCheck.provider { Any() }
    assertSame(provider, SuspendDoubleCheck.provider(provider))
  }

  @Test
  fun `lazy short-circuits an already-lazy delegate`() {
    // A delegate that is already a SuspendLazy is returned as-is instead of being wrapped again,
    // mirroring DoubleCheck.lazy. suspendProviderOf is backed by a SuspendInstanceFactory, which is
    // a SuspendLazy but not a SuspendDoubleCheck.
    val instance: SuspendProvider<String> = suspendProviderOf("value")
    assertSame<Any?>(instance, SuspendDoubleCheck.lazy(instance))
  }

  @Test
  fun `waiter takes over when the winning initializer is cancelled`() = runTest {
    val winnerEntered = CompletableDeferred<Unit>()
    val winnerGate = CompletableDeferred<Unit>()
    val doubleCheck = SuspendDoubleCheck.provider {
      if (invocationCount.incrementAndFetch() == 1) {
        winnerEntered.complete(Unit)
        // Suspends until cancelled
        winnerGate.await()
      }
      "value"
    }

    val winner = launch { doubleCheck() }
    winnerEntered.await()

    // Queue a second caller as a waiter behind the winner on the mutex.
    val waiter = async { doubleCheck() }
    yield()

    // Cancelling the winner releases the mutex; the waiter acquires it and runs the initializer.
    winner.cancelAndJoin()

    assertEquals("value", waiter.await())
    assertEquals(2, invocationCount.load())
    assertTrue((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
  }

  @Test
  fun `reentrant invocation throws IllegalStateException instead of deadlocking`() = runTest {
    // The coroutine Mutex is not reentrant, so unlike DoubleCheck we can't tolerate a reentrant
    // call even when it would return the same instance. It must fail fast rather than suspend
    // forever waiting on a lock its own coroutine holds.
    val doubleCheck = SuspendDoubleCheck.provider {
      doubleCheckReference.load()!!.invoke()
      Any()
    }
    doubleCheckReference.store(doubleCheck)
    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `reentrant invocation without a Job still throws instead of deadlocking`() {
    var thrown: Throwable? = null
    val doubleCheck = SuspendDoubleCheck.provider {
      doubleCheckReference.load()!!.invoke()
      Any()
    }
    doubleCheckReference.store(doubleCheck)
    val block: suspend () -> Any = { doubleCheck() }
    block.startCoroutine(
      Continuation(EmptyCoroutineContext) { result -> thrown = result.exceptionOrNull() }
    )
    assertTrue(thrown is IllegalStateException, "Expected IllegalStateException, was $thrown")
  }

  @Test
  fun `reentrant invocation through coroutineScope throws instead of deadlocking`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider {
      coroutineScope { doubleCheckReference.load()!!.invoke() }
      Any()
    }
    doubleCheckReference.store(doubleCheck)

    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `reentrant invocation through withContext throws instead of deadlocking`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider {
      withContext(NonCancellable) {
        doubleCheckReference.load()!!.invoke()
      }
      Any()
    }
    doubleCheckReference.store(doubleCheck)

    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `reentrant invocation through awaited async throws instead of deadlocking`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider {
      coroutineScope { async { doubleCheckReference.load()!!.invoke() }.await() }
      Any()
    }
    doubleCheckReference.store(doubleCheck)

    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `indirect reentrant invocation throws instead of deadlocking`() = runTest {
    val firstReference = AtomicReference<SuspendProvider<Any>?>(null)
    val second = SuspendDoubleCheck.provider { firstReference.load()!!.invoke() }
    val first = SuspendDoubleCheck.provider {
      second()
      Any()
    }
    firstReference.store(first)

    assertFailsWith<IllegalStateException> { first() }
  }

  @Test
  fun `concurrent callers sharing a coroutine context are not treated as reentrant`() = runTest {
    val initializerEntered = CompletableDeferred<Unit>()
    val releaseInitializer = CompletableDeferred<Unit>()
    val value = Any()
    val doubleCheck = SuspendDoubleCheck.provider {
      initializerEntered.complete(Unit)
      releaseInitializer.await()
      value
    }
    val firstResult = CompletableDeferred<Result<Any>>()
    val secondResult = CompletableDeferred<Result<Any>>()
    val block: suspend () -> Any = { doubleCheck() }
    val sharedContext = coroutineContext

    block.startCoroutine(Continuation(sharedContext) { result -> firstResult.complete(result) })
    initializerEntered.await()
    block.startCoroutine(Continuation(sharedContext) { result -> secondResult.complete(result) })
    yield()
    releaseInitializer.complete(Unit)

    assertSame(value, firstResult.await().getOrThrow())
    assertSame(value, secondResult.await().getOrThrow())
  }

  @Test
  fun `concurrent callers share a single in-flight computation`() = runTest {
    // Single-flight on ALL platforms, including single-threaded JS/Wasm where the initializer
    // interleaves with other coroutines at suspension points: the second caller must suspend and
    // share the first caller's result, not run the initializer again.
    val gate = CompletableDeferred<Unit>()
    val doubleCheck = SuspendDoubleCheck.provider {
      invocationCount.incrementAndFetch()
      gate.await()
      Any()
    }
    val first = async { doubleCheck() }
    val second = async { doubleCheck() }
    // Let both coroutines reach the provider
    yield()
    yield()
    gate.complete(Unit)
    assertSame(first.await(), second.await())
    assertEquals(1, invocationCount.load())
  }

  @Test
  fun `failed initializer is not cached and is retried`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider {
      if (invocationCount.incrementAndFetch() == 1) {
        throw IllegalArgumentException("first attempt fails")
      }
      "success"
    }
    assertFailsWith<IllegalArgumentException> { doubleCheck() }
    assertFalse((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
    assertEquals("success", doubleCheck())
    assertEquals(2, invocationCount.load())
    assertTrue(doubleCheck.isInitialized())
  }

  @Test
  fun `reentrancy detection resets after a failed initialization`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider {
      if (invocationCount.incrementAndFetch() == 1) {
        throw IllegalArgumentException("first attempt fails")
      }
      "success"
    }
    assertFailsWith<IllegalArgumentException> { doubleCheck() }
    // The same coroutine retries. It must not be misidentified as a reentrant cycle.
    assertEquals("success", doubleCheck())
  }

  @Test
  fun `stale init marker from a failed attempt does not block a child retry`() = runTest {
    // The first attempt launches a fire-and-forget child that inherits the initialization marker
    // but not the failing parent's Job, so the child survives the failure. When the child later
    // retries the same binding it must not be misidentified as a circular dependency.
    val childCanRun = CompletableDeferred<Unit>()
    val childResult = CompletableDeferred<Result<Any>>()

    // Launched from inside the initializer so it inherits the initialization marker. The failing
    // Job is dropped so the child outlives the attempt that spawned it.
    suspend fun launchStaleChild() {
      val inheritedContext = coroutineContext.minusKey(Job)
      backgroundScope.launch(inheritedContext) {
        childCanRun.await()
        childResult.complete(runCatching { doubleCheckReference.load()!!.invoke() })
      }
    }

    val doubleCheck = SuspendDoubleCheck.provider {
      if (invocationCount.incrementAndFetch() == 1) {
        launchStaleChild()
        throw IllegalArgumentException("first attempt fails")
      }
      Any()
    }
    doubleCheckReference.store(doubleCheck)

    assertFailsWith<IllegalArgumentException> { doubleCheck() }
    childCanRun.complete(Unit)

    // The child sees the marker but it is no longer active, so it initializes normally.
    val value = childResult.await().getOrThrow()
    assertEquals(2, invocationCount.load())
    assertSame(value, doubleCheck())
  }

  @Test
  fun `cancelled initializer does not poison the cache`() = runTest {
    val winnerEntered = CompletableDeferred<Unit>()
    val winnerGate = CompletableDeferred<Unit>()
    val doubleCheck = SuspendDoubleCheck.provider {
      if (invocationCount.incrementAndFetch() == 1) {
        winnerEntered.complete(Unit)
        // Suspends until cancelled
        winnerGate.await()
      }
      "value"
    }

    val winner = launch { doubleCheck() }
    winnerEntered.await()
    winner.cancelAndJoin()

    // The winner was cancelled mid-initialization; the next caller re-runs the initializer.
    assertFalse((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
    assertEquals("value", doubleCheck())
    assertEquals(2, invocationCount.load())
  }

  @Test
  fun `cancelled waiter stops waiting for the initializer`() = runTest {
    val initializerEntered = CompletableDeferred<Unit>()
    val releaseInitializer = CompletableDeferred<Unit>()
    val doubleCheck = SuspendDoubleCheck.provider {
      initializerEntered.complete(Unit)
      releaseInitializer.await()
      "value"
    }
    val initializer = async { doubleCheck() }
    initializerEntered.await()

    val waiterStarted = CompletableDeferred<Unit>()
    val waiter = launch {
      waiterStarted.complete(Unit)
      doubleCheck()
    }
    waiterStarted.await()
    yield()

    try {
      waiter.cancel()
      yield()
      assertTrue(waiter.isCompleted)
    } finally {
      releaseInitializer.complete(Unit)
      assertEquals("value", initializer.await())
    }
  }

  @Test
  fun `isInitialized works`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider { Any() }
    assertFalse((doubleCheck as SuspendDoubleCheck<*>).isInitialized())

    doubleCheck()
    assertTrue((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
  }
}
