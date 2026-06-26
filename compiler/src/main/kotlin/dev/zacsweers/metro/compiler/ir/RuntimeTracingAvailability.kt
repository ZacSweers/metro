// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.memoize

/**
 * Caches runtime-tracing availability for this IR compilation.
 *
 * Symbol lookup is relatively cheap, but every generated graph asks whether it should create a
 * `MetroTraceContext`. This keeps that decision consistent without mixing in diagnostic reporting.
 */
@Inject
@SingleIn(IrScope::class)
internal class RuntimeTracingAvailability(context: IrMetroContext) {
  val unavailableReason: String? by memoize {
    when {
      !context.options.enableRuntimeTracing -> "Runtime tracing is not enabled."
      !context.platform.supportsTracing() ->
        "Runtime tracing is not supported on the given platform (${context.platform})."
      context.metroSymbols.tracer == null ->
        "androidx.tracing.Tracer is missing from the classpath."
      context.metroSymbols.metroTraceContext == null ->
        "Metro tracing infra is missing from the classpath."
      context.metroSymbols.metroTraceContextTrace == null ->
        "Metro tracing infra is missing from the classpath."
      context.metroSymbols.metroTraceContextInstant == null ->
        "Metro tracing infra is missing from the classpath."
      context.metroSymbols.metroTraceContextChild == null ->
        "Metro tracing infra is missing from the classpath."
      context.metroSymbols.tracedProvider == null ->
        "Metro tracing infra is missing from the classpath."
      else -> null
    }
  }

  fun isAvailable(): Boolean = unavailableReason == null
}
