// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.MetroOptions.DiagnosticSeverity
import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.diagnostics.DiagnosticContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.expressions.FirStatement

/** Names of every Metro diagnostic. */
internal val metroDiagnosticNames: Set<String> by lazy {
  MetroDiagnostics.getRendererFactory().MAP.factories.mapTo(mutableSetOf()) { it.name }
}

/**
 * Names of Metro diagnostics that IR reports. The `diagnostic-level` option can't change these yet
 * because it only applies to FIR checkers. Some of these are also reported in FIR.
 */
internal val irReportedDiagnosticNames: Set<String> by lazy {
  setOf(
      MetroDiagnostics.ASSISTED_FACTORY_SUSPEND_REQUIRED,
      MetroDiagnostics.DUPLICATE_BINDING,
      MetroDiagnostics.DUPLICATE_MAP_KEY,
      MetroDiagnostics.EMPTY_MULTIBINDING,
      MetroDiagnostics.GRAPH_DEPENDENCY_CYCLE,
      MetroDiagnostics.INCOMPATIBLE_OVERRIDES,
      MetroDiagnostics.INCOMPATIBLE_RETURN_TYPES,
      MetroDiagnostics.INCOMPATIBLE_SCOPE,
      MetroDiagnostics.INVALID_ASSISTED_BINDING,
      MetroDiagnostics.KNOWN_KOTLINC_BUG_ERROR,
      MetroDiagnostics.KNOWN_KOTLINC_BUG_WARNING,
      MetroDiagnostics.MEMBER_INJECTION_OVER_SUSPEND_BINDING,
      MetroDiagnostics.METRO_ERROR,
      MetroDiagnostics.METRO_TRACE_ERROR,
      MetroDiagnostics.METRO_WARNING,
      MetroDiagnostics.MISSING_BINDING,
      MetroDiagnostics.MISSING_RUNTIME_COROUTINES,
      MetroDiagnostics.MULTIBINDING_OVER_SUSPEND_BINDINGS,
      MetroDiagnostics.PRIVATE_BINDING_ERROR,
      MetroDiagnostics.QUALIFIER_OVERRIDE_MISMATCH,
      MetroDiagnostics.SOURCELESS_METRO_ERROR,
      MetroDiagnostics.SOURCELESS_METRO_WARNING,
      MetroDiagnostics.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR,
      MetroDiagnostics.SUSPEND_BINDING_WRAPPED_IN_LAZY,
      MetroDiagnostics.SUSPEND_BINDING_WRAPPED_IN_PROVIDER,
      MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
      MetroDiagnostics.SUSPICIOUS_MEMBER_INJECT_FUNCTION,
      MetroDiagnostics.SUSPICIOUS_UNUSED_MULTIBINDING,
      MetroDiagnostics.UNPROCESSED_UPSTREAM_DECLARATION,
      MetroDiagnostics.UNUSED_GRAPH_INPUT_ERROR,
      MetroDiagnostics.UNUSED_GRAPH_INPUT_WARNING,
    )
    .mapTo(mutableSetOf()) { it.name }
}

/**
 * Applies the `diagnostic-level` option to diagnostics reported by Metro's FIR checkers. A null
 * severity means the diagnostic is dropped.
 */
internal class DiagnosticLevels
private constructor(
  private val severities: Map<String, Severity?>,
  private val compatContext: CompatContext,
) {
  fun wrap(reporter: DiagnosticReporter): DiagnosticReporter {
    return LevelOverridingReporter(reporter)
  }

  fun <D : FirDeclaration> wrapDeclarationCheckers(
    checkers: Set<FirDeclarationChecker<D>>,
  ): Set<FirDeclarationChecker<D>> {
    return checkers.mapTo(LinkedHashSet()) { DeclarationCheckerWithLevels(it, this) }
  }

  fun <E : FirStatement> wrapExpressionCheckers(
    checkers: Set<FirExpressionChecker<E>>,
  ): Set<FirExpressionChecker<E>> {
    return checkers.mapTo(LinkedHashSet()) { ExpressionCheckerWithLevels(it, this) }
  }

  private inner class LevelOverridingReporter(private val delegate: DiagnosticReporter) :
    DiagnosticReporter() {
    override fun report(diagnostic: KtDiagnostic?, context: DiagnosticContext) {
      if (diagnostic == null || diagnostic.factory.name !in severities) {
        delegate.report(diagnostic, context)
        return
      }
      val severity = severities.getValue(diagnostic.factory.name) ?: return
      val adjusted = with(compatContext) { diagnostic.withSeverityCompat(severity) }
      delegate.report(adjusted, context)
    }

    override val hasErrors: Boolean
      get() = delegate.hasErrors

    override val hasWarningsForWError: Boolean
      get() = delegate.hasWarningsForWError
  }

  companion object {
    /** Returns null when no diagnostic levels are configured. */
    fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): DiagnosticLevels? {
      val configured = options.diagnosticLevels
      if (configured.isEmpty()) {
        return null
      }
      val isIde = session.isIde()
      val severities = configured.mapValues { (_, level) -> level.resolve(isIde).toKtSeverity() }
      return DiagnosticLevels(severities, compatContext)
    }

    private fun DiagnosticSeverity.toKtSeverity(): Severity? {
      return when (this) {
        DiagnosticSeverity.NONE -> null
        // kotlinc uses FIXED_WARNING for -Xwarning-level so -Werror doesn't promote it.
        DiagnosticSeverity.WARN -> Severity.FIXED_WARNING
        DiagnosticSeverity.ERROR -> Severity.ERROR
        DiagnosticSeverity.IDE_WARN,
        DiagnosticSeverity.IDE_ERROR -> error("IDE-only levels must be resolved first")
      }
    }
  }
}

private class DeclarationCheckerWithLevels<D : FirDeclaration>(
  private val delegate: FirDeclarationChecker<D>,
  private val levels: DiagnosticLevels,
) : FirDeclarationChecker<D>(delegate.mppKind) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: D) {
    kotlin.context(context, levels.wrap(reporter)) { delegate.check(declaration) }
  }
}

private class ExpressionCheckerWithLevels<E : FirStatement>(
  private val delegate: FirExpressionChecker<E>,
  private val levels: DiagnosticLevels,
) : FirExpressionChecker<E>(delegate.mppKind) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: E) {
    kotlin.context(context, levels.wrap(reporter)) { delegate.check(expression) }
  }
}
