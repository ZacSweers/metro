// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Reads items on a bounded pool and returns their results in input order.
 *
 * A parallelism of one runs reads inline on the caller. Larger pools run reads in [context] and cap
 * concurrent reads at `min(parallelism, size)`. [read] must be safe to call from several threads at
 * once. The context defaults to [Dispatchers.Default] and must not carry a [Job].
 *
 * [accept] is an optional hook. It sees each result in input order on the caller's coroutine before
 * the result joins the returned list. It can't suspend. While it's set, reads run at most twice the
 * worker count of items ahead of acceptance.
 *
 * A failed read or acceptance fails the whole call. Failures from other workers arrive as
 * suppressed exceptions on the first one. Earlier items may already be accepted by then. Callers
 * must be able to discard that state. A `CancellationException` from a read propagates to the
 * caller and leaves the caller's job active. Cancellation joins the entire pool before this call
 * returns.
 */
public suspend fun <T, R> List<T>.parallelMap(
  parallelism: Int,
  context: CoroutineContext = Dispatchers.Default,
  accept: ((T, R) -> Unit)? = null,
  read: suspend (T) -> R,
): List<R> {
  val acceptIndexed: ((Int, T, R) -> Unit)? =
    if (accept == null) {
      null
    } else {
      { _, item, result -> accept(item, result) }
    }
  return parallelMapIndexed(parallelism, context, acceptIndexed) { _, item -> read(item) }
}

/** [parallelMap] with each item's index passed to both callbacks. */
public suspend fun <T, R> List<T>.parallelMapIndexed(
  parallelism: Int,
  context: CoroutineContext = Dispatchers.Default,
  accept: ((Int, T, R) -> Unit)? = null,
  read: suspend (Int, T) -> R,
): List<R> {
  require(parallelism > 0) { "parallelism must be positive, was $parallelism" }
  require(context[Job] == null) { "context must not contain a Job" }
  val results = ArrayList<R>(size)
  if (parallelism == 1) {
    for (index in indices) {
      val item = get(index)
      currentCoroutineContext().ensureActive()
      val result = read(index, item)
      currentCoroutineContext().ensureActive()
      accept?.invoke(index, item, result)
      results += result
    }
    return results
  }
  if (isEmpty()) {
    return results
  }

  // Three coroutines cooperate here. The producer hands out indices in order and takes one
  // `inFlight` permit per index. The workers read in `context` and send back each result with its
  // index. The collector runs on the caller, parks out-of-order results in `pending`, and
  // accepts them once everything before them has been accepted.
  //
  // The `inFlight` limit exists because acceptance is in order. Otherwise one slow item at the
  // front would let the workers read the whole rest of the list and hold every result in memory
  // until that item finished. Permits only come back when the collector accepts an item. Once a
  // slow item blocks acceptance the producer can hand out at most twice the worker count of
  // indices before it has to wait. A plain map keeps every result anyway, so it gets no limit.
  coroutineScope {
    val workers = minOf(parallelism, size)
    val inFlightLimit =
      if (accept == null) {
        size
      } else {
        minOf(size.toLong(), workers.toLong() * 2).toInt()
      }
    val inFlight = Semaphore(inFlightLimit)
    val input = Channel<Int>(workers)
    val completed = Channel<IndexedValue<R>>(workers)
    launch {
      for (index in indices) {
        inFlight.acquire()
        input.send(index)
      }
      input.close()
    }
    repeat(workers) {
      launch(context) {
        try {
          for (index in input) {
            // Channel fast paths skip cancellation checks. Bail before starting a read.
            ensureActive()
            completed.send(IndexedValue(index, read(index, get(index))))
          }
        } catch (failure: Throwable) {
          // A read can cancel itself independently of the pool's parent. Wake the collector so that
          // it also cancels and joins the remaining workers in that case.
          completed.close(failure)
          throw failure
        }
      }
    }

    // The wrapper keeps a null result distinct from a missing entry.
    val pending = HashMap<Int, IndexedValue<R>>()
    var next = 0
    repeat(size) {
      val result = completed.receive()
      pending[result.index] = result
      while (true) {
        val ready = pending.remove(next) ?: break
        currentCoroutineContext().ensureActive()
        accept?.invoke(next, get(next), ready.value)
        results += ready.value
        next++
        inFlight.release()
      }
    }
  }
  return results
}
