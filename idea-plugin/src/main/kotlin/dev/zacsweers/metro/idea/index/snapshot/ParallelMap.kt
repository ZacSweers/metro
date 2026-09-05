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
 * Reads items on a bounded pool and accepts each result in input order on the caller's coroutine.
 *
 * A parallelism of one runs reads inline on the caller. Larger pools run reads on
 * [Dispatchers.Default] and cap concurrent reads at `min(parallelism, size)`. At most twice the
 * worker count of items can await acceptance. Active reads count toward that limit. [accept] cannot
 * suspend.
 *
 * A failed read or acceptance fails the whole call. Earlier items may already be accepted by then.
 * Callers must be able to discard that state. A `CancellationException` from a read propagates to
 * the caller and leaves the caller's job active. Cancellation joins the entire pool before this
 * call returns.
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

    // The wrapper keeps a null result distinct from a missing entry.
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
