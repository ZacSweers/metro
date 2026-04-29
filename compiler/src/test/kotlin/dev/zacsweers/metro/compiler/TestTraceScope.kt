// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import androidx.tracing.AbstractTraceSink
import androidx.tracing.DelicateTracingApi
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.wire.TraceDriver
import dev.zacsweers.metro.compiler.tracing.TraceScope

internal fun testTraceScope(): TraceScope {
  return TraceScope(TraceDriver(EmptyTraceSink, isEnabled = false).tracer, "test")
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
