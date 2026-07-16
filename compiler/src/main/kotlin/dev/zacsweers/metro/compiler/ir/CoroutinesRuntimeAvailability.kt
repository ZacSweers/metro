// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrDeclaration

internal const val MISSING_RUNTIME_COROUTINES_MESSAGE =
  "Add `dev.zacsweers.metro:runtime-coroutines` to the compile and runtime classpath."

internal fun IrMetroContext.reportMissingRuntimeCoroutines(
  declaration: IrDeclaration,
  subject: String,
) {
  val message =
    "[${MetroDiagnosticId.MISSING_RUNTIME_COROUTINES.fullId}] $subject requests a `SuspendLazy` " +
      "value, which needs the optional runtime-coroutines artifact. " +
      MISSING_RUNTIME_COROUTINES_MESSAGE
  reportCompat(declaration, MetroDiagnostics.MISSING_RUNTIME_COROUTINES, message)
}

/** Caches optional coroutines-runtime availability for this IR compilation. */
@JvmInline
internal value class CoroutinesRuntimeAvailability(val isAvailable: Boolean) {
  @Inject constructor(symbols: Symbols) : this(symbols.suspendDoubleCheckCompanionObject != null)
}
