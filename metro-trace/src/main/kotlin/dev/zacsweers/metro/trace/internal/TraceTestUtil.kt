// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.trace.internal

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.Tracer
import androidx.tracing.wire.TraceDriver
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import org.jetbrains.annotations.TestOnly

/**
 * Runs [block] with an AndroidX [Tracer] and assertions for generated Metro runtime trace events.
 *
 * This is primarily intended for functional tests that need to exercise generated code and assert
 * its trace events in the same synchronous block. Only Metro events are recorded, identified by the
 * `metro.graph` metadata emitted by generated runtime tracing code.
 */
@TestOnly
public fun testMetroTrace(block: MetroTraceTestScope.() -> Unit) {
  val traceSink = RecordingTraceSink()
  val traceDriver = TraceDriver(traceSink)
  val scope = MetroTraceTestScope(traceDriver, traceSink.events)
  traceDriver.use {
    scope.block()
  }
  scope.assertNoEventsRemaining()
}

/**
 * Assertions for Metro trace events recorded while [tracer] is used.
 *
 * [events] is the queue of generated Metro trace events recorded by AndroidX tracing. [assertEvent]
 * flushes the trace driver, then consumes the next recorded event so tests can assert a span at the
 * point they expect generated code to have emitted it.
 */
@TestOnly
public class MetroTraceTestScope(
  private val traceDriver: TraceDriver,
  private val events: BlockingDeque<MetroTraceEvent>,
) {
  public val tracer: Tracer
    get() = traceDriver.tracer

  /** Flushes tracing and asserts the next generated Metro event. */
  public fun assertEvent(
    name: String,
    graph: String,
    path: String,
    type: String,
    contextualType: String? = null,
    qualifier: String? = null,
    kind: String? = null,
  ) {
    val metadata = buildMap {
      put("metro.graph", graph)
      put("metro.graph_path", path)
      put("metro.type", type)
      contextualType?.let { put("metro.contextual_type", it) }
      qualifier?.let { put("metro.qualifier", it) }
      kind?.let { put("metro.binding_kind", it) }
    }
    val expected = ExpectedMetroTraceEvent(name, metadata)
    traceDriver.flush()
    assertNextEvent(expected)
  }

  private fun assertNextEvent(expected: ExpectedMetroTraceEvent) {
    val actualEvent = events.pollFirst()
    val actual = actualEvent?.let { ExpectedMetroTraceEvent(it.name, it.metadata) }
    check(actual == expected) {
      throw AssertionError(
        buildString {
          appendLine("Expected next Metro trace event to match.")
          appendLine("Expected: $expected")
          appendLine("Actual: $actual")
        }
      )
    }
  }

  internal fun assertNoEventsRemaining() {
    val remainingEvents = events.toList()
    check(remainingEvents.isEmpty()) {
      throw AssertionError(
        buildString {
          appendLine("Expected all Metro trace events to be asserted.")
          appendLine("Remaining:")
          remainingEvents.forEach { appendLine("  $it") }
        }
      )
    }
  }
}

/** A named trace event and its string metadata. */
@TestOnly
public data class MetroTraceEvent(
  public val name: String,
  public val metadata: Map<String, String>,
)

/** Expected trace event used internally by [MetroTraceTestScope] assertions. */
@TestOnly
public data class ExpectedMetroTraceEvent(
  public val name: String,
  public val metadata: Map<String, String>,
)

@OptIn(DelicateTracingApi::class)
private class RecordingTraceSink : AbstractTraceSink() {
  val events: BlockingDeque<MetroTraceEvent> = LinkedBlockingDeque()

  override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
    // AndroidX also emits bookkeeping packets such as "flush". Metro's runtime assertions only care
    // about generated Metro sections, and the pooled array must be recycled even when a packet is
    // skipped.
    pooledPacketArray.forEach { traceEvent ->
      val metadata = buildMap {
        for (index in 0..traceEvent.lastMetadataEntryIndex) {
          val metadataEntry = traceEvent.metadataEntries[index]
          val metadataName = metadataEntry.name ?: continue
          val stringValue = metadataEntry.stringValue
          put(metadataName, stringValue)
        }
      }
      val eventName = traceEvent.name ?: return@forEach
      if ("metro.graph" !in metadata) return@forEach
      events += MetroTraceEvent(eventName, metadata)
    }
    pooledPacketArray.recycle()
  }

  override fun flush() {}

  override fun close() {}

  override fun onDroppedTraceEvent() {
    error("Dropped trace event")
  }
}
