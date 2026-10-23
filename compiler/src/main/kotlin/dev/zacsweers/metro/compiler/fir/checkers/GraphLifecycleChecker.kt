// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.isUnit

/** Checks callback declarations even when their graph is assembled in a different module. */
internal object GraphLifecycleChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration !is FirFunction || declaration.symbol !is FirNamedFunctionSymbol) {
      return
    }
    val session = context.session
    val ids = MetroClassIds.lifecycleCallbacks
    val phases =
      declaration
        .annotationsIn(session, ids)
        .mapNotNull { it.toAnnotationClassIdSafe(session) }
        .toSet()
    if (declaration.isOverride) {
      for (base in declaration.symbol.directOverriddenSymbolsSafe()) {
        for (annotation in base.annotationsIn(session, ids)) {
          val phase = annotation.toAnnotationClassIdSafe(session) ?: continue
          if (phase !in phases) {
            reporter.reportOn(
              declaration.source,
              MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
              "An override of a lifecycle callback must repeat @${phase.shortClassName}.",
            )
          }
        }
      }
    }
    if (phases.isEmpty()) {
      return
    }
    val reason =
      when {
        declaration.dispatchReceiverType == null -> "Lifecycle callbacks must be member functions."
        declaration.body == null -> "Lifecycle callbacks must have an implementation."
        !declaration.symbol.resolvedReturnType.isUnit -> "Lifecycle callbacks must return Unit."
        else -> null
      }
    if (reason != null) {
      reporter.reportOn(declaration.source, MetroDiagnostics.DEPENDENCY_GRAPH_ERROR, reason)
    }
    if (declaration.isSuspend && !session.metroFirBuiltIns.options.enableSuspendProviders) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
        "Suspend lifecycle callbacks require enableSuspendProviders.",
      )
    }
  }
}
