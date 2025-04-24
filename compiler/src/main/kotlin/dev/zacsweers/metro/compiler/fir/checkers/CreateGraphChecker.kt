// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors.CREATE_GRAPH_ERROR
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.toConeTypeProjection
import org.jetbrains.kotlin.fir.types.type

internal object CreateGraphChecker : FirFunctionCallChecker(MppCheckerKind.Common) {

  override fun check(
    expression: FirFunctionCall,
    context: CheckerContext,
    reporter: DiagnosticReporter,
  ) {
    val source = expression.source ?: return

    val callee = expression.toResolvedCallableSymbol() ?: return
    val session = context.session
    when (callee.callableId) {
      context.session.metroFirBuiltIns.createGraph.callableId -> {
        // Check that the target is a graph and has no factory
        val typeArg = expression.typeArguments.singleOrNull() ?: return
        val rawType =
          typeArg.toConeTypeProjection().type?.classLikeLookupTagIfAny?.toClassSymbol(session)
            ?: return
        if (
          !rawType.isAnnotatedWithAny(
            session,
            session.metroFirBuiltIns.classIds.dependencyGraphAnnotations,
          )
        ) {
          reporter.reportOn(
            typeArg.source ?: source,
            CREATE_GRAPH_ERROR,
            "`createGraph` type argument '${rawType.classId.asFqNameString()}' must be annotated with a `@DependencyGraph` annotation.",
            context,
          )
          return
        }
        // Check that it doesn't have a factory
        val creator =
          rawType.declarationSymbols.filterIsInstance<FirClassSymbol<*>>().find {
            it.isAnnotatedWithAny(
              session,
              session.metroFirBuiltIns.classIds.dependencyGraphFactoryAnnotations,
            )
          }
        if (creator != null) {
          reporter.reportOn(
            typeArg.source ?: source,
            CREATE_GRAPH_ERROR,
            "`createGraph` type argument '${rawType.classId.asFqNameString()}' has a factory at '${creator.classId.asFqNameString()}'. Use `createGraphFactory` with that type instead.",
            context,
          )
          return
        }
      }
      context.session.metroFirBuiltIns.createGraphFactory.callableId -> {
        // Check that the target is a graph factory
        val typeArg = expression.typeArguments.singleOrNull() ?: return
        val rawType =
          typeArg.toConeTypeProjection().type?.classLikeLookupTagIfAny?.toClassSymbol(session)
        if (
          rawType != null &&
            !rawType.isAnnotatedWithAny(
              session,
              session.metroFirBuiltIns.classIds.dependencyGraphFactoryAnnotations,
            )
        ) {
          reporter.reportOn(
            typeArg.source ?: source,
            CREATE_GRAPH_ERROR,
            "`createGraphFactory` type argument '${rawType.classId.asFqNameString()}' must be annotated with a `@DependencyGraph.Factory` annotation.",
            context,
          )
          return
        }
      }
      else -> return
    }
  }
}
