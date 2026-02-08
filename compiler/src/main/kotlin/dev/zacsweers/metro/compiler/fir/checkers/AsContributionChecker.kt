// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.AS_CONTRIBUTION_ERROR
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.toClassSymbolCompat
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.toConeTypeProjection
import org.jetbrains.kotlin.fir.types.type

internal object AsContributionChecker : FirFunctionCallChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirFunctionCall) {
    val source = expression.source ?: return

    val callee = expression.toResolvedCallableSymbol() ?: return
    val session = context.session
    when (callee.callableId) {
      context.session.metroFirBuiltIns.asContribution.callableId -> {
        val resolvedType = expression.extensionReceiver?.resolvedType
        val rawType = resolvedType?.classLikeLookupTagIfAny?.toClassSymbolCompat(session) ?: return
        val mergedGraph =
          if (
            rawType.isAnnotatedWithAny(
              session,
              session.metroFirBuiltIns.classIds.dependencyGraphAnnotations,
            )
          ) {
            resolvedType
          } else {
            null
          }

        if (mergedGraph == null) {
          reporter.reportOn(
            expression.extensionReceiver?.source ?: source,
            AS_CONTRIBUTION_ERROR,
            "`asContribution` receiver must be annotated with a `@DependencyGraph` annotation.",
          )
          return
        }
        val typeArg = expression.typeArguments.singleOrNull() ?: return
        val contributedType = typeArg.toConeTypeProjection().type ?: return
        if (mergedGraph == contributedType) {
          reporter.reportOn(
            typeArg.source ?: source,
            AS_CONTRIBUTION_ERROR,
            "`asContribution` type argument '${contributedType.classId?.asFqNameString()}' is the same as its receiver type. This is a useless cast.",
          )
          return
        } else if (!mergedGraph.isSubtypeOf(contributedType, session, false)) {
          reporter.reportOn(
            typeArg.source ?: source,
            AS_CONTRIBUTION_ERROR,
            "`asContribution` type argument '${contributedType.classId?.asFqNameString()}' is not a merged supertype of ${mergedGraph.classId?.asFqNameString()}.",
          )
          return
        }
      }
      else -> return
    }
  }
}
