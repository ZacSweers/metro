// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirValueParameter

internal object OverridesParentBindingChecker :
  FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration is FirPropertyAccessor) return // Handled via FirProperty.
    val source = declaration.source ?: return
    val annotations = declaration.symbol.metroAnnotations()
    if (!annotations.isOverridesParentBinding) return

    if (annotations.isIntoSet || annotations.isElementsIntoSet) {
      reporter.reportOn(
        source,
        MetroDiagnostics.OVERRIDES_PARENT_BINDING_ON_SET_CONTRIBUTION,
        "`@OverridesParentBinding` cannot be applied to `@IntoSet` or `@ElementsIntoSet` contributions. Set element conflicts cannot be detected at compile time.",
      )
      return
    }

    val isValidSite =
      if (declaration is FirValueParameter) {
        // Graph factory inputs: instance bindings (@Provides) or included dependencies (@Includes).
        annotations.isBindsInstance ||
          declaration.symbol.isAnnotatedWithAny(context.session, context.session.classIds.includes)
      } else {
        annotations.isProvides || annotations.isBinds
      }

    if (!isValidSite) {
      reporter.reportOn(
        source,
        MetroDiagnostics.OVERRIDES_PARENT_BINDING_INVALID_SITE,
        "`@OverridesParentBinding` may only be applied to `@Provides` or `@Binds` declarations, or graph-factory instance/`@Includes` inputs.",
      )
    }
  }
}
