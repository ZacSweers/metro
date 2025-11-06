// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructor
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.validateInjectedClass
import dev.zacsweers.metro.compiler.fir.validateInjectionSiteType
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
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

    // Check for class-level inject-like annotations (@Inject or @Contributes*)
    val classInjectLikeAnnotations =
      declaration.annotationsIn(session, classIds.injectLikeAnnotations).toList()

    // Check for constructor-level @Inject annotations (only @Inject, not @Contributes*)
    val injectedConstructor =
      declaration.symbol.findInjectConstructor(
        session,
        checkClass = false,
        classIds = classIds.allInjectAnnotations,
      ) {
        return
      }

    val isInjected = classInjectLikeAnnotations.isNotEmpty() || injectedConstructor != null
    if (!isInjected) return

    declaration
      .getAnnotationByClassId(DaggerSymbols.ClassIds.DAGGER_REUSABLE_CLASS_ID, session)
      ?.let {
        reporter.reportOn(it.source ?: source, MetroDiagnostics.DAGGER_REUSABLE_ERROR)
        return
      }

    // Only error if there's an actual @Inject annotation on the class (not @Contributes*)
    // @Contributes* annotations are allowed to coexist with constructor @Inject
    val classInjectAnnotations =
      declaration.annotationsIn(session, classIds.allInjectAnnotations).toList()
    if (injectedConstructor != null && classInjectAnnotations.isNotEmpty()) {
      reporter.reportOn(
        injectedConstructor.annotation.source,
        MetroDiagnostics.CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
      )
    }

    // Assisted factories can be annotated with @Contributes* annotations and fall through here
    // While they're implicitly injectable lookups, they aren't beholden to the same injection
    // requirements
    val isAssistedFactory =
      declaration.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)
    if (!isAssistedFactory) {
      declaration.validateInjectedClass(context, reporter, classInjectAnnotations)
    }

    val constructorToValidate =
      injectedConstructor?.constructor ?: declaration.primaryConstructorIfAny(session) ?: return

    for (parameter in constructorToValidate.valueParameterSymbols) {
      val annotations =
        parameter.metroAnnotations(
          session,
          MetroAnnotations.Kind.OptionalBinding,
          MetroAnnotations.Kind.Assisted,
          MetroAnnotations.Kind.Qualifier,
        )
      if (annotations.isAssisted) continue
      validateInjectionSiteType(
        session,
        parameter.resolvedReturnTypeRef,
        annotations.qualifier,
        parameter.source ?: source,
        isOptionalBinding = annotations.isOptionalBinding,
        hasDefault = parameter.hasDefaultValue,
      )
    }
  }
}
