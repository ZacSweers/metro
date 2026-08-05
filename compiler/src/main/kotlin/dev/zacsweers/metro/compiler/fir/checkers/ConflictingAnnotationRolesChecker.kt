// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CONFLICTING_ANNOTATION_ROLES
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.toClassSymbolCompat
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirAnnotationChecker
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Reports annotation types that combine multiple Metro roles, such as qualifier and map key.
 *
 * Metro processes these roles independently during code generation. Checking each source use
 * prevents duplicate generated annotations and also covers annotation types declared in
 * dependencies.
 */
internal object ConflictingAnnotationRolesChecker : FirAnnotationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirAnnotation) {
    val source = expression.source ?: return
    if (context.containingDeclarations.lastOrNull()?.source?.kind != KtRealSourceElementKind) return
    if (!expression.isResolved) return

    val session = context.session
    val annotationClassId = expression.toAnnotationClassId(session) ?: return
    val annotationType = expression.annotationTypeRef.coneType.abbreviatedTypeOrSelf
    val annotationClass =
      annotationType.classLikeLookupTagIfAny?.toClassSymbolCompat(session) ?: return
    val classIds = session.metroFirBuiltIns.classIds
    val roles = buildList {
      if (annotationClass.isAnnotatedWithAny(session, classIds.qualifierAnnotations)) {
        add("qualifier")
      }
      if (annotationClass.isAnnotatedWithAny(session, classIds.scopeAnnotations)) {
        add("scope")
      }
      if (annotationClass.isAnnotatedWithAny(session, classIds.mapKeyAnnotations)) {
        add("map key")
      }
    }
    if (roles.size < 2) return

    reporter.reportOn(
      source,
      CONFLICTING_ANNOTATION_ROLES,
      annotationClassId.shortClassName.asString(),
      roles.joinToString(),
    )
  }
}
