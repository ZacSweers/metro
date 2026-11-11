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
  /** Models the generated `close()` of a graph whose `close()` doesn't suspend. */
  private class FakeGraph(val parent: FakeGraph?, val onClose: () -> Unit = {}) : AutoCloseable {
    val state = GraphLifecycleState()

    init {
      parent?.state?.addCloseableChild(this, state)
    }

    override fun close() {
      if (state.markClosed()) {
        state.runCleanup {
          try {
            state.closeChildren()
            try {
              onClose()
            } catch (failure: Throwable) {
              state.recordCloseFailure(failure)
            }
          } finally {
            parent?.state?.removeChild(state)
          }
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
    awaitBlocked(parentCloser)
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
   * A child's callback closes its parent while another thread is closing that parent and waiting
   * for the child. The parent is already marked closed, so the callback's close returns at once.
   */
  @Test
  fun aChildCallbackCanCloseAParentThatAnotherThreadIsClosing() {
    val childCallbackStarted = CountDownLatch(1)
    val closeParent = CountDownLatch(1)
    lateinit var parent: FakeGraph
    parent = FakeGraph(parent = null) { events += "parent callback" }
    val child =
      FakeGraph(parent) {
        childCallbackStarted.countDown()
        check(closeParent.await(5, TimeUnit.SECONDS))
        parent.close()
        events += "child callback finished"
      }

    val childCloser = daemonThread { child.close() }
    assertTrue(childCallbackStarted.await(5, TimeUnit.SECONDS))
    val parentCloser = daemonThread { parent.close() }
    awaitBlocked(parentCloser)
    closeParent.countDown()
    joinAll(childCloser, parentCloser)

    assertEquals(listOf("child callback finished", "parent callback"), events.toList())
  }

  private fun daemonThread(block: () -> Unit): Thread = thread(isDaemon = true) { block() }

  private fun joinAll(vararg threads: Thread) {
    for (thread in threads) {
      thread.join(TimeUnit.SECONDS.toMillis(5))
      assertFalse(thread.isAlive, "A close deadlocked")
    }
  }

  /** Waits until [thread] blocks on a monitor, which is how the JVM guard waits. */
  private fun awaitBlocked(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (thread.state != Thread.State.BLOCKED) {
      if (System.nanoTime() > deadline) {
        fail("The thread never blocked")
      }
      Thread.sleep(1)
    }
  }
}
