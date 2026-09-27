// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.anvil

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.allScopeClassIds
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.diagnosticString
import dev.zacsweers.metro.compiler.fir.resolvedExcludedClassIds
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.name.ClassId

/**
 * Reports Anvil-compiled subcomponents that a graph would merge but can't reach.
 *
 * Anvil generates an accessor for a `@ContributesSubcomponent` with no factory or parent component
 * interface. Metro can't add that accessor, so the subcomponent would be dropped without a trace.
 * Excluding the subcomponent from the graph silences the error.
 */
internal object AnvilSubcomponentChecker : FirClassChecker(MppCheckerKind.Common) {

  private data class Unreachable(val subcomponent: ClassId, val parentScope: ClassId)

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val source = declaration.source ?: return
    val session = context.session
    val graphAnnotation =
      declaration.annotationsIn(session, session.classIds.graphLikeAnnotations).firstOrNull()
        ?: return
    val scopes = graphAnnotation.allScopeClassIds(session)
    if (scopes.isEmpty()) {
      return
    }
    val unreachable =
      unreachableSubcomponents(
        scanner = session.anvilHintScanner,
        scopes = scopes,
        graphExcludes = graphAnnotation.resolvedExcludedClassIds(session),
      )
    if (unreachable.isEmpty()) {
      return
    }
    reporter.reportOn(source, MetroDiagnostics.AGGREGATION_ERROR, message(unreachable))
  }

  /**
   * Walks the subcomponents merged into each scope. Anvil-compiled subcomponents have no source for
   * this checker to run on, so their own scopes are checked from here too.
   */
  private fun unreachableSubcomponents(
    scanner: AnvilHintScanner,
    scopes: Set<ClassId>,
    graphExcludes: Set<ClassId>,
  ): List<Unreachable> {
    val result = mutableListOf<Unreachable>()
    val visitedScopes = mutableSetOf<ClassId>()

    fun visit(scope: ClassId, excludes: Set<ClassId>) {
      if (!visitedScopes.add(scope)) {
        return
      }
      for (subcomponent in scanner.subcomponents(scope)) {
        if (subcomponent.classId in excludes) {
          continue
        }
        if (!subcomponent.isReachable) {
          result += Unreachable(subcomponent.classId, scope)
          continue
        }
        val childScope = subcomponent.scope ?: continue
        visit(childScope, excludes + subcomponent.excludes)
      }
    }

    for (scope in scopes) {
      visit(scope, graphExcludes)
    }
    return result.sortedBy { it.subcomponent.asString() }
  }

  context(context: CheckerContext)
  private fun message(unreachable: List<Unreachable>): String = buildString {
    appendLine("Metro can't reach these Anvil-compiled subcomponents from this graph:")
    for ((subcomponent, parentScope) in unreachable) {
      appendLine(
        "- ${subcomponent.diagnosticString} (parent scope ${parentScope.diagnosticString})"
      )
    }
    appendLine()
    append("Each one needs a nested @ContributesSubcomponent.Factory or a nested interface ")
    append("annotated with @ContributesTo(parentScope) that returns it. Metro can't add the ")
    append("accessor that Anvil generates for a subcomponent without one. Add one, or exclude the ")
    append("subcomponent from this graph.")
  }
}
