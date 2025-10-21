// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroAnnotations
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

    declaration.symbol.receiverParameterSymbol?.let { param ->
      reporter.reportOn(
        param.source ?: source,
        FUNCTION_INJECT_ERROR,
        "Injected functions cannot have receiver parameters.",
      )
    }

    for (contextParam in declaration.symbol.contextParameterSymbols) {
      if (contextParam.isAnnotatedWithAny(session, session.classIds.optionalBindingAnnotations)) {
        reporter.reportOn(
          contextParam.source ?: source,
          FUNCTION_INJECT_ERROR,
          "Context parameters cannot be annotated @OptionalBinding.",
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
      val annotations =
        param.symbol.metroAnnotations(
          session,
          MetroAnnotations.Kind.OptionalBinding,
          MetroAnnotations.Kind.Assisted,
          MetroAnnotations.Kind.Qualifier,
        )
      if (annotations.isAssisted) continue
      validateInjectionSiteType(
        session,
        param.returnTypeRef,
        annotations.qualifier,
        param.source ?: source,
        annotations.isOptionalBinding,
        param.symbol.hasDefaultValue,
      )
    }
  }
}
