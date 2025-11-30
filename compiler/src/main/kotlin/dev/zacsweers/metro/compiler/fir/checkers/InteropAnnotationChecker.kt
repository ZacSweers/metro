// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.INTEROP_ANNOTATION_ARGS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.INTEROP_ANNOTATION_ARGS_WARNING
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirAnnotationChecker
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId

/**
 * Checks that any custom annotation that isn't from Metro's runtime package (i.e., it's an interop
 * annotation from another framework like Dagger) uses named arguments for all annotation arguments.
 *
 * This is important for interop because positional arguments may not be stable across different
 * frameworks.
 */
internal object InteropAnnotationChecker : FirAnnotationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirAnnotation) {
    if (context.containingDeclarations.lastOrNull()?.source?.kind != KtRealSourceElementKind) return

    val session = context.session
    val builtIns = session.metroFirBuiltIns
    val severity = builtIns.options.interopAnnotationsNamedArgSeverity
    if (severity == MetroOptions.DiagnosticSeverity.NONE) {
      // Should never happen as we never exec this checker if the severity is NONE
      return
    }

    val allCustomAnnotations = builtIns.classIds.allCustomAnnotations
    if (allCustomAnnotations.isEmpty()) return
    if (!expression.isResolved) return
    if (expression !is FirAnnotationCall) return
    val classId = expression.toAnnotationClassId(session) ?: return
    if (classId !in allCustomAnnotations) return
    if (isMetroRuntimeAnnotation(classId)) return

    val annotationType = expression.annotationTypeRef.coneType.abbreviatedTypeOrSelf
    val classSymbol =
      annotationType.classLikeLookupTagIfAny?.toClassSymbol(context.session) ?: return
    // Ignore Java annotations, kotlinc will enforce named args for us there
    if (classSymbol.origin is FirDeclarationOrigin.Java) return

    // Check if it uses named arguments
    expression.checkAnnotationHasNamedArguments(classId, severity)
  }

  private fun isMetroRuntimeAnnotation(classId: ClassId): Boolean {
    val packageName = classId.packageFqName.asString()
    return packageName == Symbols.StringNames.METRO_RUNTIME_PACKAGE ||
      packageName.startsWith("${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.")
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun FirAnnotationCall.checkAnnotationHasNamedArguments(
    annotationClassId: ClassId,
    severity: MetroOptions.DiagnosticSeverity,
  ) {
    // Check if any arguments are positional (not in the argumentMapping)
    for (arg in arguments) {
      if (arg !is FirNamedArgumentExpression) {
        val factory =
          when (severity) {
            MetroOptions.DiagnosticSeverity.ERROR -> INTEROP_ANNOTATION_ARGS_ERROR
            MetroOptions.DiagnosticSeverity.WARN -> INTEROP_ANNOTATION_ARGS_WARNING
            MetroOptions.DiagnosticSeverity.NONE -> return
          }
        reporter.reportOn(
          arg.source ?: source,
          factory,
          "Interop annotation @${annotationClassId.shortClassName.asString()} should use named arguments instead of positional arguments for better compatibility in Metro.",
        )
      }
    }
  }
}
