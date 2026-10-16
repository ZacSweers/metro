// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isDependencyGraph
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCallableReferenceAccessChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit

private val lifecycleAnnotations = setOf(MetroClassIds.onGraphInit, MetroClassIds.onGraphClose)

internal object GraphLifecycleChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration !is FirFunction) {
      return
    }
    val symbol = declaration.symbol as? FirNamedFunctionSymbol ?: return
    val session = context.session
    val annotated = symbol.isAnnotatedWithAny(session, lifecycleAnnotations)
    val inherited =
      symbol
        .directOverriddenSymbolsSafe()
        .flatMap { overridden ->
          lifecycleAnnotations.filter { overridden.isAnnotatedWithAny(session, setOf(it)) }
        }
        .toSet()
    val missing = inherited.filterNot { symbol.isAnnotatedWithAny(session, setOf(it)) }
    if (missing.isNotEmpty()) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "[Metro/GraphLifecycle] Overrides must repeat ${missing.joinToString { "@${it.shortClassName}" }}.",
      )
    }
    val containingClass = declaration.getContainingClassSymbol() as? FirClassSymbol<*>
    if (annotated) {
      val validCallback = declaration.body != null && declaration.returnTypeRef.coneType.isUnit
      if (!validCallback || containingClass == null) {
        reporter.reportOn(
          declaration.source,
          MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
          "[Metro/GraphLifecycle] Lifecycle callbacks must be implemented member functions returning Unit.",
        )
      }
      val hasReceiver =
        declaration.receiverParameter != null || declaration.contextParameters.isNotEmpty()
      if (hasReceiver || declaration.typeParameters.isNotEmpty()) {
        reporter.reportOn(
          declaration.source,
          MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
          "[Metro/GraphLifecycle] Lifecycle callbacks cannot declare receivers or type parameters.",
        )
      }
    }
    val manualClose = symbol.name.asString() == "close" && declaration.valueParameters.isEmpty()
    if (!manualClose || declaration.body == null) {
      return
    }
    if (containingClass == null) {
      return
    }
    val closeable =
      containingClass.implements(MetroClassIds.closeableGraph, session) ||
        containingClass.implements(MetroClassIds.suspendCloseableGraph, session)
    val generatedGraph =
      containingClass.isDependencyGraph(session) ||
        containingClass.isAnnotatedWithAny(session, setOf(MetroClassIds.graphExtension))
    if (closeable && generatedGraph) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "[Metro/GraphLifecycle] Metro generates close(). Put cleanup in an @OnGraphClose callback.",
      )
    }
  }
}

internal object GraphLifecycleCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirFunctionCall) {
    val callee = expression.toResolvedCallableSymbol() ?: return
    if (callee.isAnnotatedWithAny(context.session, lifecycleAnnotations)) {
      reporter.reportOn(
        expression.source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "[Metro/GraphLifecycle] Lifecycle callbacks cannot be called directly.",
      )
    }
  }
}

internal object GraphLifecycleReferenceChecker :
  FirCallableReferenceAccessChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirCallableReferenceAccess) {
    val callee = expression.toResolvedCallableSymbol() ?: return
    if (callee.isAnnotatedWithAny(context.session, lifecycleAnnotations)) {
      reporter.reportOn(
        expression.source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "[Metro/GraphLifecycle] Callable references to lifecycle callbacks are prohibited.",
      )
    }
  }
}
