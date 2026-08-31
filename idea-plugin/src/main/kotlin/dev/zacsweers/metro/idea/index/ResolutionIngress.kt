// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Accepts requests from concurrent IDE callbacks and merges matching keys before the coordinator
 * drains them. The channel signals pending work. Requests stay queued until [drain] or [close].
 */
internal class ResolutionIngress<E : Any>(
  private val coalescingKey: (E) -> Any? = { null },
  private val merge: (E, E) -> E = { _, added -> added },
) {
  private val lock = Any()
  private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

  private var closed = false
  private var eventClock = 0L
  private var semanticClock = 0L
  private var latestManualRequestId = 0L
  private var coalescedEvents = linkedMapOf<Any, E>()
  private var uncoalescedEvents = mutableListOf<E>()

  val wakeups: ReceiveChannel<Unit>
    get() = wakeChannel

  fun submit(
    semanticChange: Boolean = false,
    manualRefresh: Boolean = false,
    event: (ResolutionIngressTicket) -> E,
  ): ResolutionIngressTicket? {
    val ticket =
      synchronized(lock) {
        if (closed) return@synchronized null
        eventClock++
        if (semanticChange) semanticClock++
        if (manualRefresh) latestManualRequestId = eventClock
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

  fun snapshot(): ResolutionIngressSnapshot = synchronized(lock) { snapshotLocked() }

  /** Stops accepting requests and returns queued events so the caller can cancel their waiters. */
  fun close(): List<E> {
    val abandoned =
      synchronized(lock) {
        if (closed) return@synchronized emptyList()
        closed = true
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
      hasPendingEvents = coalescedEvents.isNotEmpty() || uncoalescedEvents.isNotEmpty(),
      isClosed = closed,
    )
  }
}

internal data class ResolutionIngressTicket(
  val eventClock: Long,
  val semanticClock: Long,
  val latestManualRequestId: Long,
)

internal data class ResolutionIngressSnapshot(
  val eventClock: Long,
  val semanticClock: Long,
  val latestManualRequestId: Long,
  val hasPendingEvents: Boolean,
  val isClosed: Boolean,
)

internal data class ResolutionIngressDrain<E : Any>(
  val snapshot: ResolutionIngressSnapshot,
  val events: List<E>,
)
