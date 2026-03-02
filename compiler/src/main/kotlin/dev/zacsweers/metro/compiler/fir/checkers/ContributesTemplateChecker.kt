// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.classIds
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
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.isAny
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Validates `@ContributesTemplate` usage in three contexts:
 *
 * **Annotation definitions** (classes meta-annotated with `@ContributesTemplate`):
 * - The `template` parameter must reference a class annotated with `@ContributesTemplate.Template`
 * - The template must be either an `object` (with `@Provides` functions) or `abstract class` (with
 *   `@Binds` functions)
 * - Each template function must have exactly one type parameter (the target type `T`)
 * - The annotation must have a `scope: KClass<*>` parameter, or `@ContributesTemplate` must specify
 *   a `scope` (where `Nothing::class` is the sentinel for "not set"). These are mutually exclusive.
 *
 * **Template classes** (classes annotated with `@ContributesTemplate.Template`):
 * - Must be an `object` or `abstract class`
 *
 * **Target classes** (classes annotated with a `@ContributesTemplate`-meta-annotated annotation):
 * - The target class must satisfy the upper bounds on each template function's type parameter `T`
 */
internal object ContributesTemplateChecker : FirClassChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    if (declaration.source == null) return
    if (declaration.classKind == ClassKind.ANNOTATION_CLASS) {
      checkAnnotationDefinition(declaration)
    } else {
      // Check if this is a @Template-annotated class
      checkTemplateClass(declaration)
      // Check if this is a target class
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

    // Extract and validate the template parameter
    val templateGetClassCall =
      metaAnnotation.argumentAsOrNull<FirGetClassCall>(Symbols.Names.template, 0)
    if (templateGetClassCall == null) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Annotation class annotated with `@ContributesTemplate` must specify a `template` parameter.",
      )
      return
    }

    val templateClassId = templateGetClassCall.resolvedClassId()
    if (templateClassId == null) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Could not resolve the `template` class reference in `@ContributesTemplate`.",
      )
      return
    }

    val templateClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(templateClassId) as? FirRegularClassSymbol
    if (templateClassSymbol == null) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Could not find template class `$templateClassId` referenced in `@ContributesTemplate`.",
      )
      return
    }

    // Verify template is annotated with @ContributesTemplate.Template
    if (
      !templateClassSymbol.isAnnotatedWithAny(
        session,
        setOf(Symbols.ClassIds.contributesTemplateTemplate),
      )
    ) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Template class `${templateClassId.shortClassName}` must be annotated with `@ContributesTemplate.Template`.",
      )
      return
    }

    // Validate template is object or abstract class
    val isObject = templateClassSymbol.classKind == ClassKind.OBJECT
    val isAbstractClass =
      templateClassSymbol.classKind == ClassKind.CLASS && templateClassSymbol.isAbstract

    if (!isObject && !isAbstractClass) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Template class `${templateClassId.shortClassName}` must be an `object` (for `@Provides` functions) or `abstract class` (for `@Binds` functions).",
      )
      return
    }

    // Find template functions (@Provides for objects, @Binds for abstract classes)
    val templateFunctions = findTemplateFunctions(session, templateClassSymbol, isAbstractClass)
    if (templateFunctions.isEmpty()) {
      val functionType = if (isAbstractClass) "`@Binds`" else "`@Provides`"
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Template class `${templateClassId.shortClassName}` must have at least one $functionType function.",
      )
      return
    }

    // Check that each template function has exactly one type parameter
    for (fn in templateFunctions) {
      if (fn.typeParameterSymbols.size != 1) {
        reporter.reportOn(
          declaration.source,
          MetroDiagnostics.AGGREGATION_ERROR,
          "Template function '${fn.name}' in `${templateClassId.shortClassName}` must have exactly one type parameter, but has ${fn.typeParameterSymbols.size}.",
        )
        return
      }
    }

    // Check scope: mutually exclusive — either meta-annotation specifies scope or annotation has
    // scope param
    val metaScopeArg = metaAnnotation.argumentAsOrNull<FirGetClassCall>(Symbols.Names.scope, 1)
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
  private fun checkTemplateClass(declaration: FirClass) {
    val session = context.session
    val declarationSymbol = declaration.symbol as? FirRegularClassSymbol ?: return

    if (
      !declarationSymbol.isAnnotatedWithAny(
        session,
        setOf(Symbols.ClassIds.contributesTemplateTemplate),
      )
    )
      return

    val isObject = declaration.classKind == ClassKind.OBJECT
    val isAbstractClass = declaration.classKind == ClassKind.CLASS && declarationSymbol.isAbstract

    if (!isObject && !isAbstractClass) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Classes annotated with `@ContributesTemplate.Template` must be an `object` or `abstract class`.",
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

      val metaAnnotation =
        annotationClassSymbol.resolvedCompilerAnnotationsWithClassIds.firstOrNull {
          it.isResolved &&
            it.toAnnotationClassIdSafe(session) == classIds.contributesTemplateAnnotation
        } ?: continue

      // Extract template class from @ContributesTemplate
      val templateGetClassCall =
        metaAnnotation.argumentAsOrNull<FirGetClassCall>(Symbols.Names.template, 0) ?: continue
      val templateClassId = templateGetClassCall.resolvedClassId() ?: continue

      val templateClassSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(templateClassId)
          as? FirRegularClassSymbol ?: continue

      val isAbstractClass =
        templateClassSymbol.classKind == ClassKind.CLASS && templateClassSymbol.isAbstract
      val templateFunctions = findTemplateFunctions(session, templateClassSymbol, isAbstractClass)

      // Check type parameter bounds
      for (fn in templateFunctions) {
        for (typeParam in fn.typeParameterSymbols) {
          val substitutionMap = mapOf(typeParam to targetType)
          val substitutor = substitutorByMap(substitutionMap, session)
          for (bound in typeParam.resolvedBounds) {
            val boundType = bound.coneType
            val substitutedBound = substitutor.substituteOrSelf(boundType)
            if (substitutedBound.isAny) continue
            if (!targetType.isSubtypeOf(substitutedBound, session)) {
              val functionType = if (isAbstractClass) "@Binds" else "@Provides"
              reporter.reportOn(
                annotation.source,
                MetroDiagnostics.AGGREGATION_ERROR,
                "Target class `${targetType.renderReadableWithFqNames()}` does not satisfy type parameter bound `${boundType.renderReadableWithFqNames()}` of `$functionType` function '${fn.name}' in `@${annotationClassId.shortClassName}`.",
              )
            }
          }
        }
      }
    }
  }

  private fun findTemplateFunctions(
    session: org.jetbrains.kotlin.fir.FirSession,
    templateClassSymbol: FirRegularClassSymbol,
    isAbstractClass: Boolean,
  ): List<FirNamedFunctionSymbol> {
    val classIds = session.classIds
    @OptIn(org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class)
    return templateClassSymbol.declarationSymbols
      .filterIsInstance<FirNamedFunctionSymbol>()
      .filter { fn ->
        if (isAbstractClass) {
          fn.isAnnotatedWithAny(session, classIds.bindsAnnotations)
        } else {
          fn.isAnnotatedWithAny(session, classIds.providesAnnotations)
        }
      }
  }
}
