// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** These tests need real threads, so they only run on the JVM and native. */
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
          parent.takeChildren()
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
}
