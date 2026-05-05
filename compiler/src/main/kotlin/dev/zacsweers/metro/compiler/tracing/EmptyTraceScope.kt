// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.wire.TraceDriver

internal fun emptyTraceScope(category: String): TraceScope {
  return TraceScope(TraceDriver(EmptyTraceSink, isEnabled = false).tracer, category)
}

private object EmptyTraceSink : AbstractTraceSink() {
  @OptIn(DelicateTracingApi::class)
  override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
    pooledPacketArray.recycle()
  }

  override fun onDroppedTraceEvent() {
    // Does nothing
  }

  override fun flush() {
    // Does nothing
  }

  override fun close() {
    // Does nothing
  }
}
