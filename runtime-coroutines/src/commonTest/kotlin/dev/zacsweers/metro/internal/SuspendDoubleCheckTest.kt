// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroSuspendApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroSuspendApi
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
  fun `reentrant invocation throws IllegalStateException instead of deadlocking`() = runTest {
    // The coroutine Mutex is not reentrant, so unlike DoubleCheck we can't tolerate a reentrant
    // call even when it would return the same instance — it must fail fast rather than suspend
    // forever waiting on a lock its own coroutine holds.
    val doubleCheck =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          doubleCheckReference.load()!!.invoke()
          Any()
        }
      )
    doubleCheckReference.store(doubleCheck)
    assertFailsWith<IllegalStateException> { doubleCheck() }
  }

  @Test
  fun `reentrant invocation without a Job still throws instead of deadlocking`() {
    // Job-less coroutines (suspend fun main, bare startCoroutine) have no Job to identify the
    // initializing caller by; the guard falls back to coroutine context identity.
    var thrown: Throwable? = null
    val doubleCheck =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          doubleCheckReference.load()!!.invoke()
          Any()
        }
      )
    doubleCheckReference.store(doubleCheck)
    val block: suspend () -> Any = { doubleCheck() }
    block.startCoroutine(
      Continuation(EmptyCoroutineContext) { result -> thrown = result.exceptionOrNull() }
    )
    assertTrue(thrown is IllegalStateException, "Expected IllegalStateException, was $thrown")
  }

  @Test
  fun `failed initializer is not cached and is retried`() = runTest {
    val doubleCheck =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          if (invocationCount.incrementAndFetch() == 1) {
            throw IllegalArgumentException("first attempt fails")
          }
          "success"
        }
      )
    assertFailsWith<IllegalArgumentException> { doubleCheck() }
    assertFalse((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
    assertEquals("success", doubleCheck())
    assertEquals(2, invocationCount.load())
    assertTrue(doubleCheck.isInitialized())
  }

  @Test
  fun `reentrancy detection resets after a failed initialization`() = runTest {
    val doubleCheck =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          if (invocationCount.incrementAndFetch() == 1) {
            throw IllegalArgumentException("first attempt fails")
          }
          "success"
        }
      )
    assertFailsWith<IllegalArgumentException> { doubleCheck() }
    // The same coroutine retries — must not be misidentified as a reentrant cycle.
    assertEquals("success", doubleCheck())
  }

  @Test
  fun `cancelled initializer does not poison the cache`() = runTest {
    val winnerEntered = CompletableDeferred<Unit>()
    val winnerGate = CompletableDeferred<Unit>()
    val doubleCheck =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          if (invocationCount.incrementAndFetch() == 1) {
            winnerEntered.complete(Unit)
            // Suspends until cancelled
            winnerGate.await()
          }
          "value"
        }
      )

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
    val doubleCheck = SuspendDoubleCheck.provider(SuspendProvider { Any() })
    assertFalse((doubleCheck as SuspendDoubleCheck<*>).isInitialized())

    doubleCheck()
    assertTrue((doubleCheck as SuspendDoubleCheck<*>).isInitialized())
  }
}
