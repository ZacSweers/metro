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
import dev.zacsweers.lattice.compiler.fir.allAnnotations
import dev.zacsweers.lattice.compiler.fir.findInjectConstructor
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.scopeAnnotation
import dev.zacsweers.lattice.compiler.fir.validateApiDeclaration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.constructors
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isUnit

internal object DependencyGraphChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val latticeClassIds = session.latticeClassIds

    val isDependencyGraph =
      declaration.isAnnotatedWithAny(session, latticeClassIds.dependencyGraphAnnotations)

    if (!isDependencyGraph) return

    declaration.validateApiDeclaration(context, reporter, "DependencyGraph") {
      return
    }

    // TODO dagger doesn't appear to error for this case to model off of
    for (constructor in declaration.constructors(session)) {
      reporter.reportOn(
        constructor.source,
        FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
        "Dependency graphs cannot have constructors. Use @DependencyGraph.Factory instead.",
        context,
      )
      return
    }

    // Note this doesn't check inherited supertypes. Maybe we should, but where do we report errors?
    val callables = declaration.declarations.asSequence().filterIsInstance<FirCallableDeclaration>()

    for (callable in callables) {
      val isMultibinds = callable.isAnnotatedWithAny(session, latticeClassIds.multibindsAnnotations)
      if (isMultibinds) {
        val scopeAnnotation = callable.annotations.scopeAnnotation(session)
        if (scopeAnnotation != null) {
          reporter.reportOn(
            scopeAnnotation.fir.source,
            FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
            "@Multibinds members cannot be scoped.",
            context,
          )
          continue
        }
        if (!callable.isAbstract) {
          reporter.reportOn(
            callable.source,
            FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
            "@Multibinds members must be abstract.",
            context,
          )
          continue
        }
      }

      if (!callable.isAbstract) continue

      val isBindsOrProvides =
        callable.isAnnotatedWithAny(
          session,
          latticeClassIds.providesAnnotations + latticeClassIds.bindsAnnotations,
        )
      if (isBindsOrProvides) continue

      // Functions with no params are accessors
      if (
        callable is FirProperty || (callable is FirFunction && callable.valueParameters.isEmpty())
      ) {
        val returnType = callable.returnTypeRef.coneTypeOrNull
        if (returnType?.isUnit == true) {
          reporter.reportOn(
            callable.returnTypeRef.source ?: callable.source,
            FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members must have a return type and cannot be Unit.",
            context,
          )
          continue
        } else if (returnType?.isNothing == true) {
          reporter.reportOn(
            callable.source,
            FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot return Nothing.",
            context,
          )
          continue
        }

        val scopeAnnotation = callable.allAnnotations().scopeAnnotation(session)
        if (scopeAnnotation != null) {
          reporter.reportOn(
            scopeAnnotation.fir.source,
            FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot be scoped.",
            context,
          )
        }
        continue
      }

      if (callable is FirFunction && callable.valueParameters.isNotEmpty()) {
        if (callable.returnTypeRef.coneTypeOrNull?.isUnit != true) {
          reporter.reportOn(
            callable.returnTypeRef.source,
            FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
            "Inject functions must not return anything other than Unit.",
            context,
          )
          continue
        }

        // If it has one param, it's an injector
        when (callable.valueParameters.size) {
          1 -> {
            val parameter = callable.valueParameters[0]
            val clazz = parameter.returnTypeRef.firClassLike(session) ?: continue
            val isInjected =
              clazz.isAnnotatedWithAny(session, latticeClassIds.injectAnnotations) ||
                clazz.findInjectConstructor(session, latticeClassIds, context, reporter) {
                  return
                } != null

            if (isInjected) {
              reporter.reportOn(
                parameter.source,
                FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
                "Injected type is constructor-injected and can be instantiated by Lattice directly, so this inject function is unnecessary.",
                context,
              )
            }
          }
          // > 1
          else -> {
            // TODO Not actually sure what dagger does. Maybe we should support this?
            reporter.reportOn(
              callable.source,
              FirLatticeErrors.DEPENDENCY_GRAPH_ERROR,
              "Inject functions must have exactly one parameter.",
              context,
            )
          }
        }
      }
    }
  }
}
