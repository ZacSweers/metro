// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.Symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructor
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.validateInjectedClass
import dev.zacsweers.metro.compiler.fir.validateInjectionSiteType
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny

internal object InjectConstructorChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val classInjectAnnotation =
      declaration.annotationsIn(session, classIds.allInjectAnnotations).toList()

    val injectedConstructor =
      declaration.symbol.findInjectConstructor(
        session,
        checkClass = false,
        classIds = classIds.allInjectAnnotations,
      ) {
        return
      }

    val isInjected = classInjectAnnotation.isNotEmpty() || injectedConstructor != null
    if (!isInjected) return

    declaration
      .getAnnotationByClassId(DaggerSymbols.ClassIds.DAGGER_REUSABLE_CLASS_ID, session)
      ?.let {
        reporter.reportOn(it.source ?: source, MetroDiagnostics.DAGGER_REUSABLE_ERROR)
        return
      }

    if (classInjectAnnotation.isNotEmpty() && injectedConstructor != null) {
      reporter.reportOn(
        injectedConstructor.annotation.source,
        MetroDiagnostics.CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
      )
      return
    }

    declaration.validateInjectedClass(context, reporter) {
      return
    }

    val constructorToValidate =
      injectedConstructor?.constructor ?: declaration.primaryConstructorIfAny(session) ?: return

    for (parameter in constructorToValidate.valueParameterSymbols) {
      if (parameter.isAnnotatedWithAny(session, classIds.assistedAnnotations)) continue
      validateInjectionSiteType(
        session,
        parameter.resolvedReturnTypeRef,
        parameter.qualifierAnnotation(session),
        parameter.source ?: source,
      )
    }
  }
}
