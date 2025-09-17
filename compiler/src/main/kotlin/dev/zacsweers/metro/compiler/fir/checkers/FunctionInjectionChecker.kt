// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.FUNCTION_INJECT_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.FUNCTION_INJECT_TYPE_PARAMETERS_ERROR
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.validateInjectionSiteType
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction

internal object FunctionInjectionChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirSimpleFunction) {
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
      )
    }

    // TODO eventually check context receivers too
    declaration.symbol.receiverParameterSymbol?.let { param ->
      reporter.reportOn(
        param.source ?: source,
        FUNCTION_INJECT_ERROR,
        "Injected functions cannot have receiver parameters.",
      )
    }

    val contextParams = declaration.symbol.contextParameterSymbols
    if (contextParams.isNotEmpty()) {
      for (param in contextParams) {
        reporter.reportOn(
          param.source ?: source,
          FUNCTION_INJECT_ERROR,
          "Injected functions cannot have context parameters.",
        )
      }
    }

    val scope = declaration.symbol.metroAnnotations(session).scope

    if (scope != null) {
      reporter.reportOn(
        scope.fir.source ?: source,
        FUNCTION_INJECT_ERROR,
        "Injected functions are stateless and should not be scoped.",
      )
    }

    for (param in declaration.valueParameters) {
      if (param.isAnnotatedWithAny(session, classIds.assistedAnnotations)) continue
      validateInjectionSiteType(session, param.returnTypeRef, param.source ?: source)
    }
  }
}
