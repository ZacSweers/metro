// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.Symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.asFirContextualTypeKey
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructor
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.validateInjectedClass
import dev.zacsweers.metro.compiler.fir.validateVisibility
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.classId

internal object InjectConstructorChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    val source = declaration.source ?: return
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

    declaration
      .getAnnotationByClassId(DaggerSymbols.ClassIds.DAGGER_REUSABLE_CLASS_ID, session)
      ?.let {
        reporter.reportOn(it.source ?: source, FirMetroErrors.DAGGER_REUSABLE_ERROR, context)
        return
      }

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

    val constructorToValidate =
      injectedConstructor ?: declaration.primaryConstructorIfAny(session) ?: return
    constructorToValidate.validateVisibility(context, reporter, "Injected constructors") {
      return
    }

    for (parameter in constructorToValidate.valueParameterSymbols) {
      if (parameter.isAnnotatedWithAny(session, classIds.assistedAnnotations)) continue
      val type = parameter.resolvedReturnTypeRef.coneType
      val contextKey = type.asFirContextualTypeKey(session, null, false)
      if (contextKey.isWrappedInLazy) {
        val canonicalType = contextKey.typeKey.type
        val canonicalClass = canonicalType.toClassSymbol(session) ?: continue
        if (canonicalClass.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)) {
          reporter.reportOn(
            parameter.resolvedReturnTypeRef.source ?: parameter.source ?: source,
            FirMetroErrors.ASSISTED_FACTORIES_CANNOT_BE_LAZY,
            canonicalClass.name.asString(),
            canonicalClass.classId.asFqNameString(),
            context,
          )
          return
        }
      }
    }
  }
}
