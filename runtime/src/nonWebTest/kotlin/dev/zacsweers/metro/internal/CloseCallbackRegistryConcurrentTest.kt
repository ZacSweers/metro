// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class CloseCallbackRegistryConcurrentTest {
  @Test
  fun concurrentRegistrationAndRemovalPreserveEveryRemainingChild() = runBlocking {
    val registry = CompositeCloseable()
    val start = CompletableDeferred<Unit>()
    val calls = AtomicInt(0)
    val mutations =
      List(32) { index ->
        async(Dispatchers.Default) {
          start.await()
          val key = Any()
          registry.add(key) { calls.incrementAndFetch() }
          if (index % 2 == 0) {
            registry.remove(key)
          }
        }
      }
    start.complete(Unit)
    mutations.awaitAll()
    registry.close()
    assertEquals(16, calls.load())
  }

  @Test
  fun concurrentDrainsInvokeEachCallbackOnce() = runBlocking {
    val registry = CompositeCloseable()
    val calls = AtomicInt(0)
    repeat(64) { registry.add(Any()) { calls.incrementAndFetch() } }
    val start = CompletableDeferred<Unit>()
    val closes =
      List(16) {
        async(Dispatchers.Default) {
          start.await()
          registry.close()
        }
      }
    start.complete(Unit)
    closes.awaitAll()
    assertEquals(64, calls.load())
  }
}
