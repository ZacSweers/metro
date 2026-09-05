// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PooledTracePacketArray
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal data class RecordedIdeTraceEvent(
  val type: Int,
  val name: String?,
  val metadata: Map<String, String>,
)

/** Copies pooled events before recycling, following Metro's runtime tracing test sink. */
@OptIn(DelicateTracingApi::class)
internal class RecordingIdeTraceSink : AbstractTraceSink() {
  private val recorded = CopyOnWriteArrayList<RecordedIdeTraceEvent>()
  private val closes = AtomicInteger()
  val events: List<RecordedIdeTraceEvent>
    get() = recorded.toList()

  val closeCount: Int
    get() = closes.get()

  var closeFailure: Throwable? = null
  var onClose: (() -> Unit)? = null

  fun results(name: String): List<RecordedIdeTraceEvent> = events.filter {
    it.name == "$name.result"
  }

  override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
    try {
      check(closeCount == 0) { "Trace emitted after close" }
      pooledPacketArray.forEach { event ->
        val metadata = linkedMapOf<String, String>()
        for (index in 0..event.lastMetadataEntryIndex) {
          val entry = event.metadataEntries[index]
          val name = entry.name ?: continue
          metadata[name] = entry.stringValue
        }
        recorded += RecordedIdeTraceEvent(event.type, event.name, metadata)
      }
    } finally {
      pooledPacketArray.recycle()
    }
  }

  override fun flush() {}

  override fun close() {
    check(closes.incrementAndGet() == 1) { "Trace closed more than once" }
    onClose?.invoke()
    closeFailure?.let { throw it }
  }

  override fun onDroppedTraceEvent() = error("Dropped trace event")
}
