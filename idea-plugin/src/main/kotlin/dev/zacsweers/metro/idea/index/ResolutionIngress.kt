// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Accepts requests from concurrent IDE callbacks and merges matching keys before the coordinator
 * drains them. The channel signals pending work. Requests stay queued until [drain] or [close].
 *
 * The queue and its counters share one lock. Counters start at zero, advance when requests arrive,
 * and survive drains. Several submissions can merge into one queued event, so the counters can
 * advance by more than the number of events returned by [drain].
 *
 * An editor-hint request advances only [eventClock], allowing an index build to continue. A PSI
 * change also advances [semanticClock], so the build must reconsider its inputs. A manual refresh
 * keeps its request ID through retries. Repeated clicks share that request until it finishes.
 */
internal class ResolutionIngress<E : Any>(
  private val coalescingKey: (E) -> Any? = { null },
  private val merge: (E, E) -> E = { _, added -> added },
) {
  private val lock = Any()
  private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

  private var closed = false

  /** Sequence number of every accepted submission. Also supplies unique manual-refresh IDs. */
  private var eventClock = 0L

  /**
   * Counts submissions marked as potentially changing bindings, such as PSI, roots, or settings
   * changes. A newer value makes an in-progress build retry and tells current-data queries that
   * classification is pending. This still advances when classification finds no relevant change.
   */
  private var semanticClock = 0L

  /**
   * The [eventClock] of the latest accepted manual refresh, or zero before the first refresh.
   * Completion checks this ID so an older attempt cannot clear a newer request.
   */
  private var latestManualRequestId = 0L
  private var manualRefreshPending = false
  private var coalescedEvents = linkedMapOf<Any, E>()
  private var uncoalescedEvents = mutableListOf<E>()

  /** Merges repeated wakeups while retaining queued events for the next [drain]. */
  val wakeups: ReceiveChannel<Unit>
    get() = wakeChannel

  /**
   * Accepts one submission and returns the counter values assigned to it, or null after [close].
   * [event], [coalescingKey], and [merge] run under the queue lock and must remain short.
   *
   * [semanticChange] marks work that may invalidate binding data. Ordinary build requests and
   * worker completions leave it false. [manualRefresh] assigns a new refresh ID independently of
   * whether binding data changed. Duplicate manual requests return null while one is pending.
   */
  fun submit(
    semanticChange: Boolean = false,
    manualRefresh: Boolean = false,
    event: (ResolutionIngressTicket) -> E,
  ): ResolutionIngressTicket? {
    val ticket =
      synchronized(lock) {
        if (closed || (manualRefresh && manualRefreshPending)) return@synchronized null
        eventClock++
        if (semanticChange) semanticClock++
        if (manualRefresh) {
          latestManualRequestId = eventClock
          manualRefreshPending = true
        }
        val accepted =
          ResolutionIngressTicket(
            eventClock = eventClock,
            semanticClock = semanticClock,
            latestManualRequestId = latestManualRequestId,
          )
        val added = event(accepted)
        val key = coalescingKey(added)
        if (key == null) {
          uncoalescedEvents += added
        } else {
          val existing = coalescedEvents[key]
          coalescedEvents[key] = if (existing == null) added else merge(existing, added)
        }
        accepted
      }
    if (ticket != null) wakeChannel.trySend(Unit)
    return ticket
  }

  /**
   * Detaches the queued events and captures their counters under the same lock. The returned
   * snapshot has an empty queue even though the coordinator still needs to process these events.
   * New submissions can arrive as soon as the lock is released.
   */
  fun drain(): ResolutionIngressDrain<E> {
    return synchronized(lock) {
      val drained =
        buildList(coalescedEvents.size + uncoalescedEvents.size) {
          addAll(coalescedEvents.values)
          addAll(uncoalescedEvents)
        }
      coalescedEvents = linkedMapOf()
      uncoalescedEvents = mutableListOf()
      ResolutionIngressDrain(snapshotLocked(), drained)
    }
  }

  /** Reads the latest counters and queue state without consuming events. */
  fun snapshot(): ResolutionIngressSnapshot = synchronized(lock) { snapshotLocked() }

  /** Repeated Refresh clicks share one request until its load finishes or fails. */
  fun completeManualRefresh(requestId: Long): Boolean =
    synchronized(lock) {
      if (!manualRefreshPending || latestManualRequestId != requestId) return@synchronized false
      manualRefreshPending = false
      true
    }

  /** Stops accepting requests and returns queued events so the caller can cancel their waiters. */
  fun close(): List<E> {
    val abandoned =
      synchronized(lock) {
        if (closed) return@synchronized emptyList()
        closed = true
        manualRefreshPending = false
        val pending =
          buildList(coalescedEvents.size + uncoalescedEvents.size) {
            addAll(coalescedEvents.values)
            addAll(uncoalescedEvents)
          }
        coalescedEvents = linkedMapOf()
        uncoalescedEvents = mutableListOf()
        pending
      }
    wakeChannel.close()
    return abandoned
  }

  private fun snapshotLocked(): ResolutionIngressSnapshot {
    return ResolutionIngressSnapshot(
      eventClock = eventClock,
      semanticClock = semanticClock,
      latestManualRequestId = latestManualRequestId,
      manualRefreshPending = manualRefreshPending,
      hasPendingEvents = coalescedEvents.isNotEmpty() || uncoalescedEvents.isNotEmpty(),
      isClosed = closed,
    )
  }
}

/** Counter values captured for one accepted submission. */
internal data class ResolutionIngressTicket(
  /** Sequence number assigned to this submission, including submissions merged by key. */
  val eventClock: Long,
  /** Number of accepted requests that may change binding data, through this submission. */
  val semanticClock: Long,
  /** Latest manual-refresh submission's [eventClock], or zero before the first refresh. */
  val latestManualRequestId: Long,
)

/**
 * Counter values and queue state captured together. They describe accepted requests. The
 * coordinator tracks which changes it has classified and which indexes it has published.
 */
internal data class ResolutionIngressSnapshot(
  /** Sequence number of the latest accepted submission. Ordinary requests also advance it. */
  val eventClock: Long,
  /** Compared with a build's starting value to detect incoming changes that require a retry. */
  val semanticClock: Long,
  /** Compared with a manual-refresh build's ID to detect a newer refresh request. */
  val latestManualRequestId: Long,
  /** True from an accepted Refresh click through its terminal completion. */
  val manualRefreshPending: Boolean,
  /** Whether events remain in this queue. Drained events may still be awaiting processing. */
  val hasPendingEvents: Boolean,
  /** Whether [ResolutionIngress.close] has stopped further submissions. */
  val isClosed: Boolean,
)

/** One detached batch and the queue state captured immediately after detaching it. */
internal data class ResolutionIngressDrain<E : Any>(
  val snapshot: ResolutionIngressSnapshot,
  val events: List<E>,
)
