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
  category: String = scope.category,
  crossinline metadataBlock: EventMetadata.() -> Unit = {},
  crossinline block: TraceScope.() -> T,
): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  val innerScope = if (category == scope.category) scope else TraceScope(scope.tracer, category)
  return scope.tracer.trace(category = category, name = name, metadataBlock = metadataBlock) {
    innerScope.block()
  }
}

/**
 * FIR-side counterpart to [trace]. Pulls the [TraceScope] off [MetroFirBuiltIns]; when tracing is
 * disabled (IDE or no `traceDestination`), invokes [block] against [NoopTraceScope] without
 * touching the real tracer or evaluating [metadataBlock].
 *
 * The block exposes a [TraceScope] receiver tagged with [category] so nested `trace(...)` calls
 * inside the lambda inherit it by default.
 */
@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@IgnorableReturnValue
internal inline fun <T> FirSession.trace(
  name: () -> String,
  category: String = TraceCategories.FIR_CHECKER,
  crossinline metadataBlock: EventMetadata.() -> Unit = {},
  crossinline block: TraceScope.() -> T,
): T {
  contract {
    callsInPlace(name, InvocationKind.AT_MOST_ONCE)
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    callsInPlace(metadataBlock, InvocationKind.EXACTLY_ONCE)
  }
  // Note: name is deferred so the no-op path below never has to build the name string.
  val outerScope = metroFirBuiltIns.traceScope ?: return NoopTraceScope.block()
  val innerScope =
    if (category == outerScope.category) {
      outerScope
    } else {
      TraceScope(outerScope.tracer, category)
    }
  return outerScope.tracer.trace(
    category = category,
    name = name(),
    metadataBlock = metadataBlock,
  ) {
    innerScope.block()
  }
}

internal object TraceCategories {
  const val FIR_CHECKER = "fir-checker"
  const val FIR_GEN = "fir-gen"
  const val FIR_SUPERTYPE = "fir-supertype"
}

internal interface TraceScope {
  val tracer: Tracer
  val category: String

  companion object {
    operator fun invoke(tracer: Tracer, category: String): TraceScope =
      TraceScopeImpl(tracer, category)

    fun noop(): TraceScope {
      return NoopTraceScope
    }
  }
}

internal class TraceScopeImpl(override val tracer: Tracer, override val category: String) :
  TraceScope

/**
 * Singleton no-op [TraceScope] used as the receiver when [FirSession.trace] runs with tracing
 * disabled. Nested `trace(...)` calls inside the block hit this scope's no-op [Tracer], so they
 * remain valid but do no work.
 */
internal val NoopTraceScope: TraceScope by lazy { TraceScope(Tracer.getStubTracer(), "noop") }

internal val IrClass.diagnosticTag: String
  get() = kotlinFqName.asString().replace('.', '_')
