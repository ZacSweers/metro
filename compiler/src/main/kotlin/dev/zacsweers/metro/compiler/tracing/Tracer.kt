// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.Tracer
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.kotlinFqName

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@IgnorableReturnValue
context(scope: TraceScope)
internal inline fun <T> trace(name: String, crossinline block: TraceScope.() -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  return scope.tracer.trace(category = "main", name = name) { scope.block() }
}

internal interface TraceScope {
  val tracer: Tracer

  companion object {
    operator fun invoke(tracer: Tracer, category: String): TraceScope = TraceScopeImpl(tracer)
  }
}

@JvmInline internal value class TraceScopeImpl(override val tracer: Tracer) : TraceScope

internal val IrClass.diagnosticTag: String
  get() = kotlinFqName.asString().replace('.', '_')
