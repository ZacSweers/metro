// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
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
    if (expression !is FirFunctionCall && expression !is FirCallableReferenceAccess) {
      return
    }
    val target = expression.toResolvedCallableSymbol() ?: return
    if (target.isAnnotatedWithAny(context.session, MetroClassIds.lifecycleCallbacks)) {
      reporter.reportOn(
        expression.calleeReference.source,
        MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
        "Metro calls lifecycle callback '${target.name}' during graph initialization or cleanup.",
      )
    }
  }
}
