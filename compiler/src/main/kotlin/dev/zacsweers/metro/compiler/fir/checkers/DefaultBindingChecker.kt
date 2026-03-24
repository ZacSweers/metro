// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.types.FirPlaceholderProjection
import org.jetbrains.kotlin.fir.types.FirStarProjection

/** Validates `@DefaultBinding<T>` annotations. */
internal object DefaultBindingChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val session = context.session

    val annotation =
      declaration
        .annotationsIn(session, setOf(session.classIds.defaultBindingAnnotation))
        .firstOrNull() ?: return

    val typeArg = annotation.typeArguments.firstOrNull()
    if (typeArg == null) {
      reporter.reportOn(
        annotation.source,
        MetroDiagnostics.DEFAULT_BINDING_ERROR,
        "`@DefaultBinding` must have a type argument.",
      )
      return
    }

    when (typeArg) {
      is FirStarProjection -> {
        reporter.reportOn(
          typeArg.source ?: annotation.source,
          MetroDiagnostics.DEFAULT_BINDING_ERROR,
          "`@DefaultBinding` type argument must not be a star projection (`*`).",
        )
      }
      is FirPlaceholderProjection -> {
        reporter.reportOn(
          typeArg.source ?: annotation.source,
          MetroDiagnostics.DEFAULT_BINDING_ERROR,
          "`@DefaultBinding` type argument must not be a placeholder (`_`).",
        )
      }

      else -> {
        // No issues
      }
    }
  }
}
