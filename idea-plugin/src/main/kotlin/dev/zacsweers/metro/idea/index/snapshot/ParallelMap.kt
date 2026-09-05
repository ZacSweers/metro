// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Reads independent items on a fixed pool and accepts results on the caller in input order. The
 * caller must supply distinct items. At most twice the worker count can await acceptance, including
 * active reads. Cancellation joins the entire pool before this call returns.
 */
internal suspend fun <T, R> List<T>.parallelMap(
  parallelism: Int,
  read: suspend (T) -> R,
  accept: (T, R) -> Unit,
) {
  require(parallelism > 0) { "Source scan parallelism must be positive" }
  if (parallelism == 1) {
    for (item in this) {
      currentCoroutineContext().ensureActive()
      val result = read(item)
      currentCoroutineContext().ensureActive()
      accept(item, result)
    }
    return
  }
  if (isEmpty()) {
    return
  }

  coroutineScope {
    val workers = minOf(parallelism, size)
    val window = Semaphore(minOf(size.toLong(), workers.toLong() * 2).toInt())
    val input = Channel<Int>(workers)
    val results = Channel<IndexedValue<R>>(workers)
    launch {
      for (index in indices) {
        window.acquire()
        input.send(index)
      }
      input.close()
    }
    repeat(workers) {
      launch(Dispatchers.Default) {
        try {
          for (index in input) {
            results.send(IndexedValue(index, read(get(index))))
          }
        } catch (failure: Throwable) {
          // A read can cancel itself independently of the pool's parent. Wake the collector so that
          // it also cancels and joins the remaining workers in that case.
          results.close(failure)
          throw failure
        }
      }
    }

    val pending = HashMap<Int, IndexedValue<R>>()
    var next = 0
    repeat(size) {
      val result = results.receive()
      pending[result.index] = result
      while (true) {
        val ready = pending.remove(next) ?: break
        currentCoroutineContext().ensureActive()
        accept(get(next), ready.value)
        next++
        // Acceptance opens another slot even when an earlier slow read filled the reorder window.
        window.release()
      }
    }
  }
}
