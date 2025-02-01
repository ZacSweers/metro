// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructor
import dev.zacsweers.metro.compiler.fir.validateInjectedClass
import dev.zacsweers.metro.compiler.fir.validateVisibility
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny

internal object InjectConstructorChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val classInjectAnnotation =
      declaration.annotationsIn(session, classIds.injectAnnotations).toList()

    val injectedConstructor =
      declaration.symbol.findInjectConstructor(session, context, reporter, checkClass = false) {
        return
      }

    val isInjected = classInjectAnnotation.isNotEmpty() || injectedConstructor != null
    if (!isInjected) return

    if (classInjectAnnotation.isNotEmpty() && injectedConstructor != null) {
      reporter.reportOn(
        injectedConstructor.source,
        FirMetroErrors.CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
        context,
      )
      return
    }

    declaration.validateInjectedClass(context, reporter) {
      return
    }

    val constructorToValidate = injectedConstructor ?: declaration.primaryConstructorIfAny(session)
    constructorToValidate?.validateVisibility(context, reporter, "Injected constructors") {
      return
    }
  }
}
