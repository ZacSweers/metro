// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.EventMetadata
import dev.zacsweers.metro.compiler.fir.MetroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * FIR-side counterpart to [trace]. Pulls the [TraceScope] off [MetroFirBuiltIns]. When tracing is
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

internal val IrClass.diagnosticTag: String
  get() = kotlinFqName.asString().replace('.', '_')
