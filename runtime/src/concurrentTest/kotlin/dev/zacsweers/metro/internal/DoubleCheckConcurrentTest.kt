/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** These tests are only possible to run in jvm and native. */
class DoubleCheckConcurrentTest {
  // Use runBlocking and not runTest because we actually want multithreading in this test
  @Test
  fun get() = runBlocking {
    val numCoroutines = 10

    val mutex = Mutex(locked = true) // Start locked
    val provider = CoroutineLatchedProvider(mutex)
    val lazy = DoubleCheck.lazy(provider)

    val results = List(numCoroutines) { async(Dispatchers.Default) { lazy.value } }

    // Release all coroutines at once and await the results
    mutex.unlock()
    val values = results.awaitAll().toSet()

    assertEquals(1, provider.provisions.value)
    assertEquals(1, values.size)
  }

  class CoroutineLatchedProvider(private val mutex: Mutex) : Provider<Any> {
    val provisions = atomic(0)

    override fun invoke(): Any {
      runBlocking {
        // Wait until mutex is unlocked
        mutex.withLock {}
      }
      provisions.incrementAndGet()
      return Any()
    }
  }
}
