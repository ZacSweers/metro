/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir.checkers

import dev.zacsweers.lattice.compiler.fir.FirLatticeErrors
import dev.zacsweers.lattice.compiler.fir.FirTypeKey
import dev.zacsweers.lattice.compiler.fir.annotationsIn
import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.singleAbstractFunction
import dev.zacsweers.lattice.compiler.fir.validateApiDeclaration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.toClassSymbol

internal object DependencyGraphCreatorChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val latticeClassIds = session.latticeClassIds

    val graphFactoryAnnotation =
      declaration.annotationsIn(session, latticeClassIds.dependencyGraphFactoryAnnotations).toList()

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
          FirLatticeErrors.GRAPH_CREATORS_ERROR,
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
          FirLatticeErrors.GRAPH_CREATORS_ERROR,
          "DependencyGraph.Factory abstract function parameters must be unique.",
          context,
        )
        return
      }
    }
  }
}
