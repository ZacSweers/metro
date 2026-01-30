// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.Counter
import androidx.tracing.DelicateTracingApi
import androidx.tracing.EventMetadataCloseable
import androidx.tracing.ExperimentalContextPropagation
import androidx.tracing.PropagationToken
import androidx.tracing.Tracer

/** A simple [Tracer] subclass that exposes the [category]. */
internal class MetroTracer(private val delegate: Tracer, val category: String) :
  Tracer(delegate.isEnabled) {
  @ExperimentalContextPropagation
  override fun tokenForManualPropagation(): PropagationToken {
    return delegate.tokenForManualPropagation()
  }

  @DelicateTracingApi
  override fun tokenFromThreadContext(): PropagationToken {
    return delegate.tokenFromThreadContext()
  }

  @DelicateTracingApi
  override suspend fun tokenFromCoroutineContext(): PropagationToken {
    return delegate.tokenFromCoroutineContext()
  }

  @DelicateTracingApi
  override fun beginSectionWithMetadata(
    category: String,
    name: String,
    token: PropagationToken?,
    isRoot: Boolean,
  ): EventMetadataCloseable {
    return delegate.beginSectionWithMetadata(category, name, token, isRoot)
  }

  @DelicateTracingApi
  override suspend fun beginCoroutineSectionWithMetadata(
    category: String,
    name: String,
    token: PropagationToken?,
    isRoot: Boolean,
  ): EventMetadataCloseable {
    return delegate.beginCoroutineSectionWithMetadata(category, name, token, isRoot)
  }

  override fun counter(category: String, name: String): Counter {
    return delegate.counter(category, name)
  }

  @DelicateTracingApi
  override fun instant(category: String, name: String): EventMetadataCloseable {
    return delegate.instant(category, name)
  }
}
