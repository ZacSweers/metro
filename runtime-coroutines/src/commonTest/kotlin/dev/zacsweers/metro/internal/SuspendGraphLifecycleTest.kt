// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.SuspendCloseableGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class SuspendGraphLifecycleTest {
  @Test
  fun `cleanup finishes when the caller is cancelled`() = runTest {
    val lifecycle = GraphLifecycle("AppGraph")
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var finished = false
    val job = launch {
      lifecycle.markClosed()
      lifecycle.closeSuspending {
        started.complete(Unit)
        release.await()
        finished = true
      }
    }
    started.await()
    job.cancel()
    release.complete(Unit)
    job.join()
    assertTrue(finished)
  }

  @Test
  fun `children of both kinds close before cleanup`() = runTest {
    val lifecycle = GraphLifecycle("AppGraph")
    val events = mutableListOf<String>()
    lifecycle.addChild(
      object : SuspendCloseableGraph {
        override suspend fun close() {
          yield()
          events += "suspend child"
        }
      },
    )
    lifecycle.addChild(AutoCloseable { events += "sync child" })
    lifecycle.markClosed()
    lifecycle.closeSuspending { events += "cleanup" }
    assertEquals(listOf("suspend child", "sync child"), events.dropLast(1).sorted())
    assertEquals("cleanup", events.last())
  }
}
