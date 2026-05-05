// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.EventMetadata
import androidx.tracing.Tracer
import dev.zacsweers.metro.compiler.fir.MetroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.kotlinFqName

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@IgnorableReturnValue
context(scope: TraceScope)
internal inline fun <T> trace(
  name: String,
  category: String = "main",
  crossinline metadataBlock: EventMetadata.() -> Unit = {},
  crossinline block: TraceScope.() -> T,
): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  return scope.tracer.trace(category = category, name = name, metadataBlock = metadataBlock) {
    scope.block()
  }
}

/**
 * FIR-side counterpart to [trace]. Pulls the [TraceScope] off [MetroFirBuiltIns]; when tracing is
 * disabled (IDE or no `traceDestination`), invokes [block] against [NoopTraceScope] without
 * touching the real tracer or evaluating [metadataBlock].
 *
 * The block exposes [TraceScope] as its receiver so nested IR-style `trace(...)` calls inside the
 * lambda resolve through the same scope (and the same propagation token) as the outer span.
 */
@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@IgnorableReturnValue
internal inline fun <T> FirSession.trace(
  name: String,
  category: String = "fir",
  crossinline metadataBlock: EventMetadata.() -> Unit = {},
  crossinline block: TraceScope.() -> T,
): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  val scope = metroFirBuiltIns.traceScope ?: return NoopTraceScope.block()
  return scope.tracer.trace(category = category, name = name, metadataBlock = metadataBlock) {
    scope.block()
  }
}

internal object TraceCategories {
  const val FIR_CHECKER = "fir-checker"
}

internal interface TraceScope {
  val tracer: Tracer

  companion object {
    operator fun invoke(tracer: Tracer, category: String): TraceScope = TraceScopeImpl(tracer)
  }
}

@JvmInline internal value class TraceScopeImpl(override val tracer: Tracer) : TraceScope

/**
 * Singleton no-op [TraceScope] used as the receiver when [FirSession.trace] runs with tracing
 * disabled. Nested `trace(...)` calls inside the block hit this scope's no-op [Tracer], so they
 * remain valid but do no work.
 */
internal val NoopTraceScope: TraceScope by lazy { emptyTraceScope("noop") }

internal val IrClass.diagnosticTag: String
  get() = kotlinFqName.asString().replace('.', '_')
