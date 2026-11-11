// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Closes graphs from several threads. Each thread is a daemon, so a deadlock fails a join. */
class GraphLifecycleConcurrentCloseTest {
  /** Models the generated `close()` of a closeable graph with the state API. */
  private class FakeGraph(val parent: FakeGraph?, val onClose: () -> Unit = {}) : AutoCloseable {
    val state = GraphLifecycleState()

    init {
      parent?.state?.addCloseableChild(this, state)
    }

    override fun close() {
      if (state.markClosed()) {
        try {
          state.closeChildren()
          try {
            state.enterCloseCallback()
            try {
              onClose()
            } finally {
              state.exitCloseCallback()
            }
          } catch (failure: Throwable) {
            state.recordCloseFailure(failure)
          }
        } finally {
          parent?.state?.removeChild(state)
          state.markCloseFinished()
        }
        state.throwCloseFailure()
      }
    }
  }

  private val events = Collections.synchronizedList(mutableListOf<String>())

  @Test
  fun aParentWaitsForAChildClosingOnAnotherThread() {
    val childCallbackStarted = CountDownLatch(1)
    val releaseChild = CountDownLatch(1)
    val parent = FakeGraph(parent = null) { events += "parent callback" }
    val child =
      FakeGraph(parent) {
        events += "child callback started"
        childCallbackStarted.countDown()
        check(releaseChild.await(5, TimeUnit.SECONDS))
        events += "child callback finished"
      }

    val childCloser = daemonThread { child.close() }
    assertTrue(childCallbackStarted.await(5, TimeUnit.SECONDS))
    val parentCloser = daemonThread { parent.close() }
    awaitWaiting(parentCloser)
    releaseChild.countDown()
    joinAll(childCloser, parentCloser)

    assertEquals(
      listOf("child callback started", "child callback finished", "parent callback"),
      events.toList(),
    )
  }

  @Test
  fun aChildCallbackCanCloseItsParentOnTheSameThread() {
    lateinit var parent: FakeGraph
    parent = FakeGraph(parent = null) { events += "parent callback" }
    val child =
      FakeGraph(parent) {
        events += "child callback started"
        parent.close()
        events += "child callback finished"
      }

    joinAll(daemonThread { child.close() })

    assertEquals(
      listOf("child callback started", "parent callback", "child callback finished"),
      events.toList(),
    )
  }

  /**
   * One thread closes a child, which waits for a grandchild that another thread is closing. The
   * grandchild's callback closes the root. That close doesn't wait for the child, so nothing hangs.
   */
  @Test
  fun aCallbackThatClosesAnAncestorDoesNotDeadlock() {
    val grandchildCallbackStarted = CountDownLatch(1)
    val closeRoot = CountDownLatch(1)
    val root = FakeGraph(parent = null) { events += "root callback" }
    val child = FakeGraph(root) { events += "child callback" }
    val grandchild =
      FakeGraph(child) {
        grandchildCallbackStarted.countDown()
        check(closeRoot.await(5, TimeUnit.SECONDS))
        root.close()
        events += "grandchild callback finished"
      }

    val grandchildCloser = daemonThread { grandchild.close() }
    assertTrue(grandchildCallbackStarted.await(5, TimeUnit.SECONDS))
    val childCloser = daemonThread { child.close() }
    awaitWaiting(childCloser)
    closeRoot.countDown()
    joinAll(grandchildCloser, childCloser)

    // The root ran its callback without waiting for the child. The child waited for the grandchild.
    assertEquals(
      listOf("root callback", "grandchild callback finished", "child callback"),
      events.toList(),
    )
  }

  private fun daemonThread(block: () -> Unit): Thread = thread(isDaemon = true) { block() }

  private fun joinAll(vararg threads: Thread) {
    for (thread in threads) {
      thread.join(TimeUnit.SECONDS.toMillis(5))
      assertFalse(thread.isAlive, "A close deadlocked")
    }
  }

  /** Waits until [thread] blocks in a wait. */
  private fun awaitWaiting(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (thread.state != Thread.State.WAITING) {
      if (System.nanoTime() > deadline) {
        fail("The thread never started waiting")
      }
      Thread.sleep(1)
    }
  }
}
