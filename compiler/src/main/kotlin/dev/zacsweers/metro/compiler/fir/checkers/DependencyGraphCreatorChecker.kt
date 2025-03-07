// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isDependencyGraph
import dev.zacsweers.metro.compiler.fir.singleAbstractFunction
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.toClassSymbol

// TODO platform types must be BindsInstance?
internal object DependencyGraphCreatorChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val graphFactoryAnnotation =
      declaration.annotationsIn(session, classIds.dependencyGraphFactoryAnnotations).toList()

    if (graphFactoryAnnotation.isEmpty()) return

    declaration.validateApiDeclaration(context, reporter, "DependencyGraph factory") {
      return
    }

    val createFunction =
      declaration.singleAbstractFunction(session, context, reporter, "@DependencyGraph.Factory") {
        return
      }

    createFunction.resolvedReturnType.toClassSymbol(session)?.let {
      if (!it.isDependencyGraph(session)) {
        reporter.reportOn(
          createFunction.resolvedReturnTypeRef.source ?: declaration.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "DependencyGraph.Factory abstract function '${createFunction.name}' must return a dependency graph but found ${it.classId.asSingleFqName()}.",
          context,
        )
        return
      }
    }

    val paramTypes = mutableSetOf<FirTypeKey>()

    for (param in createFunction.valueParameterSymbols) {
      val typeKey = FirTypeKey.from(session, param)
      if (!paramTypes.add(typeKey)) {
        reporter.reportOn(
          param.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "DependencyGraph.Factory abstract function parameters must be unique.",
          context,
        )
        return
      }
    }
  }
}
