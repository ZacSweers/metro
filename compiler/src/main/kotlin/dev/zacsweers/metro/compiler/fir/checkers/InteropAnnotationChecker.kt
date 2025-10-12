// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.INTEROP_ANNOTATION_ARGS
import dev.zacsweers.metro.compiler.fir.classIds
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDanglingModifierList
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.ClassId

/**
 * Checks that any custom annotation that isn't from Metro's runtime package (i.e., it's an interop
 * annotation from another framework like Dagger) uses named arguments for all annotation arguments.
 *
 * This is important for interop because positional arguments may not be stable across different
 * frameworks.
 */
internal object InteropAnnotationChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirDeclaration) {
    val session = context.session
    val allCustomAnnotations = session.classIds.allCustomAnnotations
    if (allCustomAnnotations.isEmpty()) return

    if (declaration is FirDanglingModifierList) {
      return
    }

    checkAnnotationContainer(declaration, session, allCustomAnnotations)

    if (declaration is FirCallableDeclaration) {
      declaration.receiverParameter?.let {
        checkAnnotationContainer(it, session, allCustomAnnotations)
      }
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkAnnotationContainer(
    declaration: FirAnnotationContainer,
    session: FirSession,
    allCustomAnnotations: Set<ClassId>,
  ) {
    for (annotation in declaration.annotations) {
      if (!annotation.isResolved) continue
      if (annotation !is FirAnnotationCall) continue
      val classId = annotation.toAnnotationClassId(session) ?: continue
      if (classId !in allCustomAnnotations) continue
      if (isMetroRuntimeAnnotation(classId)) continue

      // Check if it uses named arguments
      annotation.checkAnnotationHasNamedArguments(classId)
    }
  }

  private fun isMetroRuntimeAnnotation(classId: ClassId): Boolean {
    val packageName = classId.packageFqName.asString()
    return packageName == Symbols.StringNames.METRO_RUNTIME_PACKAGE ||
      packageName.startsWith("${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.")
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun FirAnnotationCall.checkAnnotationHasNamedArguments(annotationClassId: ClassId) {
    // Check if any arguments are positional (not in the argumentMapping)
    for (arg in arguments) {
      if (arg !is FirNamedArgumentExpression) {
        reporter.reportOn(
          arg.source ?: source,
          INTEROP_ANNOTATION_ARGS,
          "Interop annotation @${annotationClassId.shortClassName.asString()} should use named arguments instead of positional arguments for better compatibility in Metro.",
        )
      }
    }
  }
}
