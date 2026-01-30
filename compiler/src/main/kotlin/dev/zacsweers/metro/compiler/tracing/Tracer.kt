// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.Tracer
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@IgnorableReturnValue
context(scope: TraceScope)
internal inline fun <T> trace(
  name: String,
  category: String = scope.tracer.category,
  crossinline block: TraceScope.() -> T,
): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  return scope.tracer.trace(category = category, name = name) { scope.block() }
}

internal interface TraceScope {
  val tracer: MetroTracer

  val diagnosticTag: String
    get() = tracer.category.replace('.', '_')

  companion object {
    operator fun invoke(tracer: Tracer, category: String): TraceScope =
      TraceScopeImpl(MetroTracer(tracer, category))
  }
}

@JvmInline internal value class TraceScopeImpl(override val tracer: MetroTracer) : TraceScope
