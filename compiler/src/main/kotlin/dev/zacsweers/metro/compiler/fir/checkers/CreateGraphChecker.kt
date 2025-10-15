// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CREATE_GRAPH_ERROR
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.nestedClasses
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.toConeTypeProjection
import org.jetbrains.kotlin.fir.types.type

internal object CreateGraphChecker : FirFunctionCallChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirFunctionCall) {
    val source = expression.source ?: return

    val callee = expression.toResolvedCallableSymbol() ?: return
    val session = context.session
    val builtins = session.metroFirBuiltIns

    val targetFunction = builtins.createGraphIntrinsicCallableIds[callee.callableId] ?: return

    // It's a createGraph() intrinsic
    val typeArg = expression.typeArguments.singleOrNull() ?: return
    val rawType =
      typeArg.toConeTypeProjection().type?.classLikeLookupTagIfAny?.toClassSymbol(session) ?: return

    // If it's factory-less
    if (targetFunction == builtins.createGraph || targetFunction == builtins.createDynamicGraph) {
      if (
        !rawType.isAnnotatedWithAny(
          session,
          session.metroFirBuiltIns.classIds.dependencyGraphAnnotations,
        )
      ) {
        reporter.reportOn(
          typeArg.source ?: source,
          CREATE_GRAPH_ERROR,
          "`${targetFunction.name}` type argument '${rawType.classId.asFqNameString()}' must be annotated with a `@DependencyGraph` annotation.",
        )
      }

      // Check that it doesn't have a factory
      val creator =
        rawType.nestedClasses().find {
          it.isAnnotatedWithAny(
            session,
            session.metroFirBuiltIns.classIds.dependencyGraphFactoryAnnotations,
          )
        }
      if (creator != null) {
        reporter.reportOn(
          typeArg.source ?: source,
          CREATE_GRAPH_ERROR,
          "`${targetFunction.name}` type argument '${rawType.classId.asFqNameString()}' has a factory at '${creator.classId.asFqNameString()}'. Use `createGraphFactory` with that type instead.",
        )
      }
    } else {
      // It's a factory type
      if (
        !rawType.isAnnotatedWithAny(
          session,
          session.metroFirBuiltIns.classIds.dependencyGraphFactoryAnnotations,
        )
      ) {
        reporter.reportOn(
          typeArg.source ?: source,
          CREATE_GRAPH_ERROR,
          "`${targetFunction.name}` type argument '${rawType.classId.asFqNameString()}' must be annotated with a `@DependencyGraph.Factory` annotation.",
        )
      }
    }
  }
}
