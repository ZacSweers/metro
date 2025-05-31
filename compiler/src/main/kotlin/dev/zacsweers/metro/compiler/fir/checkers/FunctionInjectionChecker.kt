// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors.FUNCTION_INJECT_ERROR
import dev.zacsweers.metro.compiler.fir.FirMetroErrors.FUNCTION_INJECT_TYPE_PARAMETERS_ERROR
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirFunction

internal object FunctionInjectionChecker : FirFunctionChecker(MppCheckerKind.Common) {
  override fun check(
    declaration: FirFunction,
    context: CheckerContext,
    reporter: DiagnosticReporter,
  ) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    if (declaration.dispatchReceiverType != null) return // Instance function, setter injection
    if (!declaration.isAnnotatedWithAny(session, classIds.injectAnnotations)) return

    if (declaration.typeParameters.isNotEmpty()) {
      reporter.reportOn(
        source,
        FUNCTION_INJECT_TYPE_PARAMETERS_ERROR,
        "Injected functions cannot be generic.",
        context,
      )
    }

    // TODO eventually check context receivers too
    declaration.symbol.receiverParameter?.let { param ->
      reporter.reportOn(
        param.source ?: source,
        FUNCTION_INJECT_ERROR,
        "Injected functions cannot have receiver parameters.",
        context,
      )
    }

    val scope = declaration.symbol.metroAnnotations(session).scope

    if (scope != null) {
      reporter.reportOn(
        scope.fir.source ?: source,
        FUNCTION_INJECT_ERROR,
        "Injected functions are stateless and should not be scoped.",
        context,
      )
    }
  }
}
