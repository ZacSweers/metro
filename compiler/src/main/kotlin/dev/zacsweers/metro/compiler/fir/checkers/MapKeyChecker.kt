// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MAP_KEY_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MAP_KEY_TYPE_PARAM_ERROR
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.types.UnexpandedTypeCheck
import org.jetbrains.kotlin.fir.types.isArrayType

internal object MapKeyChecker : FirClassChecker(MppCheckerKind.Common) {

  @OptIn(UnexpandedTypeCheck::class)
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val session = context.session
    val anno =
      declaration
        .annotationsIn(session, session.metroFirBuiltIns.classIds.mapKeyAnnotations)
        .firstOrNull() ?: return

    if (declaration.typeParameters.isNotEmpty()) {
      reporter.reportOn(
        declaration.source,
        MAP_KEY_TYPE_PARAM_ERROR,
        "Map key annotations cannot have type parameters.",
      )
    }

    val ctor = declaration.primaryConstructorIfAny(session)
    if (ctor == null || ctor.valueParameterSymbols.isEmpty()) {
      reporter.reportOn(
        ctor?.source ?: declaration.source,
        MAP_KEY_ERROR,
        "Map key annotations must have a primary constructor with at least one parameter.",
      )
    } else {
      val unwrapValues = anno.getBooleanArgument(Symbols.Names.unwrapValue, session) ?: true
      if (unwrapValues) {
        when (ctor.valueParameterSymbols.size) {
          0 -> {
            // Handled above
          }
          1 -> {
            if (ctor.valueParameterSymbols[0].resolvedReturnTypeRef.isArrayType) {
              reporter.reportOn(
                ctor.valueParameterSymbols[0].resolvedReturnTypeRef.source ?: ctor.source,
                MAP_KEY_ERROR,
                "Map key annotations with unwrapValue set to true (the default) cannot have an array parameter.",
              )
            }
          }
          else -> {
            reporter.reportOn(
              ctor.source,
              MAP_KEY_ERROR,
              "Map key annotations with unwrapValue set to true (the default) can only have a single constructor parameter.",
            )
          }
        }
      }
    }
  }
}
