// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.graph.LifecycleDiagnosticMessages
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol

/** Only the generated graph may call a lifecycle callback or take its reference. */
internal object GraphLifecycleCallChecker :
  FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirQualifiedAccessExpression) {
    // Calls and callable references both let user code run a callback outside Metro's control.
    val isCallOrReference =
      expression is FirFunctionCall || expression is FirCallableReferenceAccess
    if (!isCallOrReference) {
      return
    }
    // `super` calls resolve to the overridden callback, so they're caught here too.
    val target = expression.toResolvedCallableSymbol() ?: return
    if (target.isAnnotatedWithAny(context.session, MetroClassIds.lifecycleCallbacks)) {
      reporter.reportOn(
        expression.calleeReference.source,
        MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
        LifecycleDiagnosticMessages.callbackIsCalledByMetro(target.name.asString()),
      )
    }
  }
}
