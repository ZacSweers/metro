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
 * Records Metro runtime trace events emitted while [block] runs.
 *
 * This is primarily intended for functional tests that need to assert Metro's generated tracing
 * calls without writing and parsing Perfetto trace files. The returned [MetroTrace] contains Metro
 * events, identified by the `metro.graph` metadata emitted by generated runtime tracing code.
 */
@TestOnly
public fun recordMetroTrace(block: (Tracer) -> Unit): MetroTrace {
  val traceSink = RecordingTraceSink()
  val traceDriver = TraceDriver(traceSink)
  traceDriver.use { traceDriver ->
    block(traceDriver.tracer)
  }
  return MetroTrace(traceSink.events)
}

/**
 * A captured Metro trace.
 *
 * [events] is the queue of generated Metro trace events recorded by AndroidX tracing. [test]
 * consumes this queue in order so fixtures can assert the exact sequence of spans emitted by
 * generated code.
 */
@TestOnly
public class MetroTrace(private val events: BlockingDeque<MetroTraceEvent>) {
  /** Runs ordered assertions against this trace's recorded Metro events. */
  public fun test(body: TraceTestScope.() -> Unit) {
    val scope =
      object : TraceTestScope {
        override fun assertEvent(
          name: String,
          graph: String,
          path: String,
          binding: String?,
          qualifier: String?,
          kind: String?,
        ) {
          val next = events.pollFirst().let { ExpectedMetroTraceEvent(it.name, it.metadata) }
          val metadata = buildMap {
            put("metro.graph", graph)
            put("metro.graph_path", path)
            binding?.let { put("metro.binding", it) }
            qualifier?.let { put("metro.qualifier", it) }
            kind?.let { put("metro.binding_kind", it) }
          }
          val expected = ExpectedMetroTraceEvent(name, metadata)
          check(next == expected) {
            throw AssertionError(
              buildString {
                appendLine("Expected Metro trace events to match exactly.")
                appendLine("Expected: $expected")
                appendLine("Actual: $next")
              }
            )
          }
        }
      }
    with(scope) {
      body()
    }
  }
}

/** A named trace event and its string metadata. */
@TestOnly
public data class MetroTraceEvent(
  public val name: String,
  public val metadata: Map<String, String>,
)

/** Expected trace event used internally by [MetroTrace.test]. */
@TestOnly
public data class ExpectedMetroTraceEvent(
  public val name: String,
  public val metadata: Map<String, String>,
)

@TestOnly
public interface TraceTestScope {
  /**
   * Asserts that the next recorded event matches a generated Metro runtime trace event.
   *
   * [name] is the visible section name shown in trace viewers. [graph] is the graph that owns the
   * traced binding, while [path] is the slash-separated root-to-current graph path. These are equal
   * for root graph bindings and differ for graph extension bindings, such as `AppGraph/ChildGraph`.
   * The remaining parameters map to optional metadata generated for the traced binding.
   */
  public fun assertEvent(
    name: String,
    graph: String,
    path: String,
    binding: String? = null,
    qualifier: String? = null,
    kind: String? = null,
  )
}

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
