// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendProvider
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
  fun `isInitialized works`() = runTest {
    val doubleCheck = SuspendDoubleCheck.provider { Any() }
    assertFalse((doubleCheck as SuspendDoubleCheck<*>).isInitialized())

    doubleCheck()
    assertTrue((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
  }
}
