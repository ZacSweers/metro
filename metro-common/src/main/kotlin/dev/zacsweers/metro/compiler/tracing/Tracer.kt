// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.EventMetadata
import androidx.tracing.Tracer
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@IgnorableReturnValue
context(scope: TraceScope)
public inline fun <T> trace(
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

public object TraceCategories {
  public const val FIR_CHECKER: String = "fir-checker"
  public const val FIR_GEN: String = "fir-gen"
  public const val FIR_SUPERTYPE: String = "fir-supertype"
}

public interface TraceScope {
  public val tracer: Tracer
  public val category: String

  public companion object {
    public operator fun invoke(tracer: Tracer, category: String): TraceScope =
      TraceScopeImpl(tracer, category)

    public fun noop(): TraceScope {
      return NoopTraceScope
    }
  }
}

public class TraceScopeImpl(override val tracer: Tracer, override val category: String) : TraceScope

/**
 * Singleton no-op [TraceScope] used as the receiver when tracing runs disabled. Nested `trace(...)`
 * calls inside the block hit this scope's no-op [Tracer], so they remain valid but do no work.
 */
public val NoopTraceScope: TraceScope by lazy { TraceScope(Tracer.getStubTracer(), "noop") }
