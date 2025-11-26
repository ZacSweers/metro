// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.allScopeClassIds
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.moduleVisibilityChecker
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.resolve.getContainingDeclaration
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.StandardClassIds

internal object MergedContributionChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val dependencyGraphAnno =
      declaration.annotationsIn(session, classIds.graphLikeAnnotations).firstOrNull()

    if (dependencyGraphAnno == null) return

    dependencyGraphAnno.allScopeClassIds().ifEmpty {
      return
    }

    // Check all merged interfaces and ensure their visibilities are compatible
    // Public declarations may not extend effectively-internal or private declarations
    val effectiveVisibility = declaration.effectiveVisibility.toVisibility()
    for (supertype in declaration.superTypeRefs) {
      if (supertype.coneType.classId == StandardClassIds.Any) continue
      val supertypeClass = supertype.firClassLike(context.session) as? FirClass ?: continue
      if (
        !supertypeClass.symbol.name
          .asString()
          .startsWith(Symbols.StringNames.METRO_CONTRIBUTION_NAME_PREFIX)
      )
        continue
      val contributedType = supertypeClass.getContainingDeclaration(session) ?: continue
      val effectiveSuperVis = contributedType.effectiveVisibility.toVisibility()

      val compared = Visibilities.compare(effectiveVisibility, effectiveSuperVis) ?: continue
      if (compared > 0) {

        val isInternalInFriendModule =
          effectiveSuperVis == Visibilities.Internal &&
            effectiveVisibility == Visibilities.Internal &&
            (contributedType.moduleData == session.moduleData ||
              session.moduleVisibilityChecker?.isInFriendModule(contributedType) == true)

        if (!isInternalInFriendModule) {
          val declarationVis =
            if (declaration.visibility != effectiveVisibility) " effectively" else ""
          val supertypeVis =
            if (contributedType.visibility != effectiveSuperVis) " effectively" else ""
          reporter.reportOn(
            supertype.source,
            MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
            "${dependencyGraphAnno.toAnnotationClassIdSafe(session)?.shortClassName?.asString()} declarations may not extend declarations with narrower visibility. Contributed supertype '${contributedType.classId.asFqNameString()}' is$supertypeVis $effectiveSuperVis but graph declaration '${declaration.classId.asFqNameString()}' is$declarationVis ${effectiveVisibility}.",
          )
        }
      }
    }
  }
}
