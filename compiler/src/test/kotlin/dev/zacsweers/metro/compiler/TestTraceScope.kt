// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import androidx.tracing.Counter
import androidx.tracing.DelicateTracingApi
import androidx.tracing.EventMetadataCloseable
import androidx.tracing.ExperimentalContextPropagation
import androidx.tracing.PropagationToken
import androidx.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.TraceScope

internal fun testTraceScope(): TraceScope {
  return TraceScope(NoOpTracer(), "test")
}

private class NoOpTracer : Tracer(false) {
  @ExperimentalContextPropagation
  override fun tokenForManualPropagation(): PropagationToken {
    TODO("Not yet implemented")
  }

  @DelicateTracingApi
  override fun tokenFromThreadContext(): PropagationToken {
    TODO("Not yet implemented")
  }

  @DelicateTracingApi
  override suspend fun tokenFromCoroutineContext(): PropagationToken {
    TODO("Not yet implemented")
  }

  @DelicateTracingApi
  override fun beginSectionWithMetadata(
    category: String,
    name: String,
    token: PropagationToken?,
    isRoot: Boolean,
  ): EventMetadataCloseable {
    TODO("Not yet implemented")
  }

  @DelicateTracingApi
  override suspend fun beginCoroutineSectionWithMetadata(
    category: String,
    name: String,
    token: PropagationToken?,
    isRoot: Boolean,
  ): EventMetadataCloseable {
    TODO("Not yet implemented")
  }

  override fun counter(category: String, name: String): Counter {
    TODO("Not yet implemented")
  }

  @DelicateTracingApi
  override fun instant(category: String, name: String): EventMetadataCloseable {
    TODO("Not yet implemented")
  }
}
