// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.companionProvidesFunctions
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.isAny
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Validates `@ContributesTemplate` usage in two contexts:
 *
 * **Annotation definitions** (classes meta-annotated with `@ContributesTemplate`):
 * - The annotation must have a companion object
 * - The companion must have at least one `@Provides` function
 * - Each `@Provides` function must have exactly one type parameter (the target type `T`)
 * - The annotation must have a `scope: KClass<*>` parameter, or `@ContributesTemplate` must specify
 *   a `scope` (where `Nothing::class` is the sentinel for "not set"). These are mutually exclusive.
 *
 * **Target classes** (classes annotated with a `@ContributesTemplate`-meta-annotated annotation):
 * - The target class must satisfy the upper bounds on each `@Provides` function's type parameter
 *   `T` in the annotation's companion object
 */
internal object ContributesTemplateChecker : FirClassChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    if (declaration.source == null) return
    if (declaration.classKind == ClassKind.ANNOTATION_CLASS) {
      checkAnnotationDefinition(declaration)
    } else {
      checkTargetClassBounds(declaration)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkAnnotationDefinition(declaration: FirClass) {
    val session = context.session
    val classIds = session.classIds

    val metaAnnotation =
      declaration.annotations.firstOrNull {
        it.isResolved &&
          it.toAnnotationClassIdSafe(session) == classIds.contributesTemplateAnnotation
      } ?: return

    // Check that the annotation class has a companion with @Provides functions
    val declarationSymbol = declaration.symbol as? FirRegularClassSymbol ?: return
    val providesFunctions = declarationSymbol.companionProvidesFunctions(session)
    if (providesFunctions == null) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Annotation class annotated with `@ContributesTemplate` must have a companion object with `@Provides` functions.",
      )
      return
    }
    if (providesFunctions.isEmpty()) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Companion object of `@ContributesTemplate` annotation must have at least one `@Provides` function.",
      )
      return
    }

    // Check that each @Provides function has exactly one type parameter
    for (fn in providesFunctions) {
      if (fn.typeParameterSymbols.size != 1) {
        reporter.reportOn(
          declaration.source,
          MetroDiagnostics.AGGREGATION_ERROR,
          "`@Provides` function '${fn.name}' in companion object must have exactly one type parameter, but has ${fn.typeParameterSymbols.size}.",
        )
        return
      }
    }

    // Check scope: mutually exclusive — either meta-annotation specifies scope or annotation has
    // scope param
    val metaScopeArg = metaAnnotation.argumentAsOrNull<FirGetClassCall>(Symbols.Names.scope, 0)
    val hasMetaScope =
      metaScopeArg != null && metaScopeArg.resolvedClassId() != StandardClassIds.Nothing

    val primaryCtor = declaration.primaryConstructorIfAny(session)
    val hasScopeParam =
      primaryCtor?.valueParameterSymbols?.any { it.name.identifier == "scope" } ?: false
    if (hasMetaScope && hasScopeParam) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Annotation class annotated with `@ContributesTemplate` must not have a `scope` parameter when `@ContributesTemplate` already specifies a `scope`.",
      )
    } else if (!hasMetaScope && !hasScopeParam) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Annotation class annotated with `@ContributesTemplate` must have a `scope: KClass<*>` parameter or specify a `scope` in `@ContributesTemplate`.",
      )
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkTargetClassBounds(declaration: FirClass) {
    val session = context.session
    val classIds = session.classIds
    val declarationSymbol = declaration.symbol as? FirRegularClassSymbol ?: return
    val targetType = declarationSymbol.defaultType()

    for (annotation in declaration.annotations) {
      if (!annotation.isResolved) continue
      val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: continue

      // Resolve annotation class and check for @ContributesTemplate meta-annotation
      val annotationClassSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(annotationClassId)
          as? FirRegularClassSymbol ?: continue

      if (
        !annotationClassSymbol.isAnnotatedWithAny(
          session,
          setOf(classIds.contributesTemplateAnnotation),
        )
      )
        continue

      // Find companion @Provides functions
      val providesFunctions = annotationClassSymbol.companionProvidesFunctions(session) ?: continue

      // Check type parameter bounds
      for (fn in providesFunctions) {
        for (typeParam in fn.typeParameterSymbols) {
          val substitutionMap = mapOf(typeParam to targetType)
          val substitutor = substitutorByMap(substitutionMap, session)
          for (bound in typeParam.resolvedBounds) {
            val boundType = bound.coneType
            val substitutedBound = substitutor.substituteOrSelf(boundType)
            if (substitutedBound.isAny) continue
            if (!targetType.isSubtypeOf(substitutedBound, session)) {
              reporter.reportOn(
                annotation.source,
                MetroDiagnostics.AGGREGATION_ERROR,
                "Target class `${targetType.renderReadableWithFqNames()}` does not satisfy type parameter bound `${boundType.renderReadableWithFqNames()}` of `@Provides` function '${fn.name}' in `@${annotationClassId.shortClassName}`.",
              )
            }
          }
        }
      }
    }
  }
}
