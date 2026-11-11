// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** These tests need real threads, so they only run on the JVM and native. */
@OptIn(ExperimentalAtomicApi::class)
class GraphLifecycleStateConcurrentTest {
  /**
   * Children that register while their parent closes either join the parent's cleanup or fail to
   * register. A child that registers successfully is never left out.
   */
  @Test
  fun registeringWhileClosingNeverLosesAChild() = runBlocking {
    repeat(200) {
      val parent = GraphLifecycleState()
      val start = Mutex(locked = true)
      val registrations =
        List(8) {
          async(Dispatchers.Default) {
            start.withLock {}
            val child = Any()
            val isRegistered =
              try {
                parent.addChild(child, GraphLifecycleState())
                true
              } catch (_: IllegalStateException) {
                false
              }
            child to isRegistered
          }
        }
      val close =
        async(Dispatchers.Default) {
          start.withLock {}
          parent.markClosed()
          parent.takeChildren().map { it.second }
        }
      start.unlock()

      val taken = close.await()
      val registered = registrations.awaitAll().filter { it.second }.map { it.first }
      for (child in registered) {
        assertTrue(taken.any { it === child }, "A registered child was left out of cleanup")
      }
      assertEquals(taken.size, taken.distinct().size)
    }
  }

  /** Children that register and leave from several threads at once leave only the open ones. */
  @Test
  fun concurrentChurnKeepsExactlyTheOpenChildren() = runBlocking {
    val parent = GraphLifecycleState()
    val open =
      List(8) {
          async(Dispatchers.Default) {
            val kept = mutableListOf<Any>()
            repeat(500) { index ->
              val child = Any()
              val childState = GraphLifecycleState()
              parent.addChild(child, childState)
              if (index % 7 == 0) {
                kept += child
              } else {
                parent.removeChild(childState)
              }
            }
            kept
          }
        }
        .awaitAll()
        .flatten()

    val taken = parent.takeChildren().map { it.second }
    assertEquals(open.size, taken.size)
    for (child in open) {
      assertTrue(taken.any { it === child }, "An open child was left out of cleanup")
    }
  }

  /**
   * A parent blocks until a child that another caller is closing finishes. A thread stuck in the
   * wait can't be cancelled, so this polls with a deadline and fails instead of hanging.
   */
  @Test
  fun aParentWaitsForAChildClosingElsewhere() = runBlocking {
    val parent = GraphLifecycleState()
    val childState = GraphLifecycleState()
    parent.addChild(AutoCloseable {}, childState)
    // Another caller owns the child's cleanup.
    assertTrue(childState.markClosed())
    val isParentDone = AtomicBoolean(false)
    // Not a child of runBlocking, which would otherwise wait for a stuck closer.
    CoroutineScope(Dispatchers.Default).launch {
      parent.closeChildren()
      isParentDone.store(true)
    }
    delay(100)
    assertFalse(isParentDone.load(), "The parent didn't wait for the child")
    childState.markCloseFinished()
    val deadline = TimeSource.Monotonic.markNow() + 5.seconds
    while (!isParentDone.load()) {
      assertTrue(deadline.hasNotPassedNow(), "The parent never woke up")
      delay(10)
    }
  }
}
