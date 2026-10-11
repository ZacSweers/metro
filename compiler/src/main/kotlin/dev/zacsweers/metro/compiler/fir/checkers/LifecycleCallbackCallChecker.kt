// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCallableReferenceAccessChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.unwrapFakeOverrides

/**
 * Reports direct calls and callable references to `@OnGraphInit` and `@OnGraphClose` callbacks.
 * Metro calls these itself when a graph is created or closed. This covers `super` calls too.
 */
internal object LifecycleCallbackCallChecker {
  object Calls : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
      val source = expression.source ?: return
      checkCallee(expression.toResolvedCallableSymbol(), source)
    }
  }

  object References : FirCallableReferenceAccessChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirCallableReferenceAccess) {
      val source = expression.source ?: return
      checkCallee(expression.toResolvedCallableSymbol(), source)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkCallee(callee: FirCallableSymbol<*>?, source: KtSourceElement) {
    if (callee !is FirNamedFunctionSymbol) return
    // Callbacks are always members.
    if (callee.dispatchReceiverType == null) return
    val original = callee.unwrapFakeOverrides()
    val annotationName = original.lifecycleAnnotationName(context.session) ?: return
    reporter.reportOn(
      source,
      MetroDiagnostics.LIFECYCLE_CALLBACK_DIRECT_CALL,
      "'${callee.name}' is an $annotationName callback. Metro calls it for you, so it can't be called or referenced directly.",
    )
  }
}
