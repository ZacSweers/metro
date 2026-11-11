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
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides

/**
 * Only the generated graph may call a lifecycle callback or take its reference. An override can
 * still call the callback it overrides through `super`.
 */
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
    // Only named functions can be callbacks, which skips the annotation lookup for most
    // expressions.
    val target = expression.toResolvedCallableSymbol() as? FirNamedFunctionSymbol ?: return
    if (!target.isAnnotatedWithAny(context.session, MetroClassIds.lifecycleCallbacks)) {
      return
    }
    if (expression is FirFunctionCall && expression.isSuperCallFromOverrideOf(target)) {
      return
    }
    reporter.reportOn(
      expression.calleeReference.source,
      MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
      LifecycleDiagnosticMessages.callbackIsCalledByMetro(target.name.asString()),
    )
  }

  /**
   * Returns true when this call goes through `super` from a function that overrides [target]. Metro
   * only runs the most specific override. A `super` call is how that override also runs the
   * callback it replaces. The call can sit in a lambda inside the override.
   */
  context(context: CheckerContext)
  private fun FirFunctionCall.isSuperCallFromOverrideOf(target: FirNamedFunctionSymbol): Boolean {
    val receiver = explicitReceiver as? FirQualifiedAccessExpression ?: return false
    if (receiver.calleeReference !is FirSuperReference) {
      return false
    }
    val callback = target.unwrapSubstitutionOverrides()
    val enclosingFunctions =
      context.containingDeclarations.filterIsInstance<FirNamedFunctionSymbol>()
    return enclosingFunctions.any { enclosing ->
      enclosing.directOverriddenSymbolsSafe().any { it.unwrapSubstitutionOverrides() == callback }
    }
  }
}
