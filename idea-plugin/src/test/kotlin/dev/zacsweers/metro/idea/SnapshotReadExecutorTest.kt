// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.IdempotenceChecker
import dev.zacsweers.metro.idea.index.IndexBuildFile
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Exercises independent IDE read retries, ordered collection, and phase-owned worker cleanup. */
class SnapshotReadExecutorTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    // Light fixtures retain the project service. Drain its refresh work before controlling reads.
    MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
    val service = project.service<MetroResolutionService>()
    service.settingsChanged()
    val drained = CompletableFuture.runAsync {
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    }
    PlatformTestUtil.waitForFuture(drained, 30_000)
    IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
  }

  fun testWriteRetriesOnlyTheUnfinishedCapture() {
    val attempts = List(2) { AtomicInteger() }
    val completed = CompletableFuture<Unit>()
    val blocked = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    val firstResult = CapturedValue(0)
    val events = ConcurrentLinkedQueue<IndexBuildProgress>()
    val retriedSlots = ConcurrentLinkedQueue<Int>()
    val preparation =
      startMap(
        listOf(0, 1),
        events = events,
        onProgress = { progress ->
          if (progress.completed == 1) {
            completed.complete(Unit)
          }
        },
      ) { index ->
        val attempt = attempts[index].incrementAndGet()
        if (index == 0) {
          firstResult
        } else {
          val current = events.last()
          val slot = current.workerFiles.indexOfFirst { it?.name == "Class$index" }
          assertTrue("The retry must retain its worker row", slot >= 0)
          retriedSlots += slot
          if (attempt == 1) {
            blocked.complete(Unit)
            try {
              awaitRelease(release)
            } catch (failure: Throwable) {
              if (failure is ProcessCanceledException || failure is CancellationException) {
                interrupted.set(true)
              }
              throw failure
            }
          }
          CapturedValue(index)
        }
      }
    try {
      PlatformTestUtil.waitForFuture(completed, 30_000)
      PlatformTestUtil.waitForFuture(blocked, 30_000)
      runInEdtAndWait { runWriteAction {} }
      val result = PlatformTestUtil.waitForFuture(preparation, 30_000).getOrThrow()
      assertTrue(interrupted.get())
      assertSame(firstResult, result[0])
      assertEquals(listOf(0, 1), result.map { it.index })
      assertEquals(1, attempts[0].get())
      assertEquals(2, attempts[1].get())
      assertEquals(1, retriedSlots.distinct().size)
      assertEquals(2, events.last().completed)
      assertEquals(listOf(null, null), events.last().workerFiles)
    } finally {
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testConcurrentReadsStayBoundedAndReturnInputOrder() {
    val firstPair = CountDownLatch(2)
    val bothEntered = CompletableFuture<Unit>()
    val laterCompleted = CompletableFuture<Unit>()
    val releaseFirst = CountDownLatch(1)
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val completionOrder = ConcurrentLinkedQueue<Int>()
    val events = ConcurrentLinkedQueue<IndexBuildProgress>()
    val preparation =
      startMap((0..5).toList(), events = events) { index ->
        val running = active.incrementAndGet()
        peak.accumulateAndGet(running, ::maxOf)
        try {
          if (index < 2) {
            firstPair.countDown()
            awaitRelease(firstPair)
            bothEntered.complete(Unit)
          }
          if (index == 0) {
            awaitRelease(releaseFirst)
          }
          completionOrder += index
          if (index == 1) {
            laterCompleted.complete(Unit)
          }
          CapturedValue(index)
        } finally {
          active.decrementAndGet()
        }
      }
    try {
      PlatformTestUtil.waitForFuture(bothEntered, 30_000)
      PlatformTestUtil.waitForFuture(laterCompleted, 30_000)
      assertFalse("The first capture is still held", preparation.isDone)
      assertEquals(2, peak.get())
      releaseFirst.countDown()
      val result = PlatformTestUtil.waitForFuture(preparation, 30_000).getOrThrow()
      assertEquals((0..5).toList(), result.map { it.index })
      val finished = completionOrder.toList()
      assertTrue(finished.indexOf(1) < finished.indexOf(0))
      assertEquals(0, active.get())
      assertEquals(2, peak.get())
      assertTrue(events.all { checkNotNull(it.activeWorkers) <= 2 })
      assertEquals(6, events.last().completed)
      assertEquals(listOf(null, null), events.last().workerFiles)
    } finally {
      releaseFirst.countDown()
      while (firstPair.count > 0) {
        firstPair.countDown()
      }
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testParentCancellationJoinsCapturesAndClearsWorkerRows() {
    val parent = Job()
    val bothEntered = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val active = AtomicInteger()
    val exited = ConcurrentLinkedQueue<Int>()
    val events = ConcurrentLinkedQueue<IndexBuildProgress>()
    val preparation =
      startMap(
        (0..5).toList(),
        parent = parent,
        events = events,
        progressIntervalNanos = 250_000_000L,
      ) { index ->
        if (active.incrementAndGet() == 2) {
          bothEntered.complete(Unit)
        }
        try {
          awaitRelease(release)
          CapturedValue(index)
        } finally {
          exited += index
          active.decrementAndGet()
        }
      }
    try {
      PlatformTestUtil.waitForFuture(bothEntered, 30_000)
      val occupied = events.last()
      assertEquals(
        setOf("Class0", "Class1"),
        occupied.workerFiles.filterNotNull().map { it.name }.toSet(),
      )
      parent.cancel()
      val result = PlatformTestUtil.waitForFuture(preparation, 30_000)
      assertTrue(result.exceptionOrNull() is CancellationException)
      assertEquals(0, active.get())
      assertEquals(setOf(0, 1), exited.toSet())
      val canceled = events.last()
      assertEquals(0, canceled.completed)
      assertEquals(0, canceled.activeWorkers)
      assertEquals(listOf(null, null), canceled.workerFiles)
      assertEquals(2, occupied.workerFiles.filterNotNull().size)
    } finally {
      parent.cancel()
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testProgressCancellationBeforeCaptureClearsItsAllocatedSlot() {
    val events = ConcurrentLinkedQueue<IndexBuildProgress>()
    val canceled = AtomicBoolean()
    val captures = AtomicInteger()
    val preparation =
      startMap(
        listOf(0),
        events = events,
        parallelism = 1,
        onProgress = { progress ->
          if (progress.activeWorkers == 1 && canceled.compareAndSet(false, true)) {
            throw CancellationException("Cancel when the worker row becomes visible")
          }
        },
      ) { index ->
        captures.incrementAndGet()
        CapturedValue(index)
      }
    val result = PlatformTestUtil.waitForFuture(preparation, 30_000)
    assertTrue(result.exceptionOrNull() is CancellationException)
    assertTrue(canceled.get())
    assertEquals(0, captures.get())
    assertTrue(events.any { it.activeWorkers == 1 })
    assertEquals(0, events.last().activeWorkers)
    assertEquals(0, events.last().completed)
    assertEquals(listOf(null), events.last().workerFiles)
  }

  /** Launches outside EDT read access and leaves the fixture thread free to perform writes. */
  private fun <T> startMap(
    items: List<Int>,
    events: ConcurrentLinkedQueue<IndexBuildProgress>,
    parallelism: Int = 2,
    parent: Job? = null,
    progressIntervalNanos: Long = 0,
    onProgress: (IndexBuildProgress) -> Unit = {},
    capture: (Int) -> T,
  ): CompletableFuture<Result<List<T>>> = CompletableFuture.supplyAsync {
    runCatching {
      runBlocking(parent ?: EmptyCoroutineContext) {
        val progress =
          IndexBuildProgressReporter(
            publish = { value ->
              events += value
              onProgress(value)
            },
            updateIntervalNanos = progressIntervalNanos,
          )
        SnapshotReadExecutor(
            project,
            parallelism,
            progress,
            IndexBuildPhase.RESOLVING_CLASS_BINDINGS,
          )
          .run { executor ->
            executor.map(
              items,
              describe = { index ->
                assertTrue(ApplicationManager.getApplication().isReadAccessAllowed)
                IndexBuildFile("Class$index", "test/Class$index.kt", "test")
              },
              capture = { index ->
                assertTrue(ApplicationManager.getApplication().isReadAccessAllowed)
                capture(index)
              },
            )
          }
      }
    }
  }

  /** Cooperates with the IDE cancellation check while keeping a selected read action active. */
  private fun awaitRelease(release: CountDownLatch) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!release.await(1, TimeUnit.MILLISECONDS)) {
      ProgressManager.checkCanceled()
      check(System.nanoTime() < deadline) { "The test did not release the active read" }
    }
  }

  private class CapturedValue(val index: Int)
}
