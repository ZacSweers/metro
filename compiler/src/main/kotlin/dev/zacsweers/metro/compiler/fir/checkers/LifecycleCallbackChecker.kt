// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.hasMetroDefault
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.validateInjectionSiteType
import dev.zacsweers.metro.compiler.graph.SuspendDiagnosticMessages
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.utils.hasBody
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit

/**
 * Checks functions annotated with `@OnGraphInit` or `@OnGraphClose`, and functions that override
 * them.
 *
 * Note: We use [FirCallableDeclarationChecker] instead of `FirSimpleFunctionChecker` because
 * `FirSimpleFunction` was renamed to `FirNamedFunction` in Kotlin 2.3.20, causing linkage failures.
 * We filter for named functions using [CompatContext.isNamedFunction] from the compat layer.
 */
internal object LifecycleCallbackChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration !is FirFunction) return
    val session = context.session
    if (!with(session.compatContext) { declaration.isNamedFunction() }) return
    val source = declaration.source ?: return
    val annotationName = declaration.symbol.lifecycleAnnotationName(session)
    if (declaration.status.isOverride) {
      checkOverrides(declaration, annotationName, source)
    }
    if (annotationName == null) return
    session.trace(name = { "LifecycleCallbackChecker(${declaration.symbol.callableId})" }) {
      checkCallback(declaration, annotationName, source)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkOverrides(
    declaration: FirFunction,
    annotationName: String?,
    source: KtSourceElement,
  ) {
    val session = context.session
    for (overridden in declaration.symbol.directOverriddenSymbolsSafe()) {
      val overriddenName = overridden.lifecycleAnnotationName(session) ?: continue
      if (overriddenName != annotationName) {
        reporter.reportOn(
          source,
          MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
          "'${declaration.symbol.name}' overrides an $overriddenName callback, so it must also be annotated $overriddenName.",
        )
        return
      }
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkCallback(
    declaration: FirFunction,
    annotationName: String,
    source: KtSourceElement,
  ) {
    val session = context.session
    val classIds = session.classIds
    val symbol = declaration.symbol

    val isInit = symbol.isAnnotatedWithAny(session, setOf(classIds.onGraphInitAnnotation))
    val isClose = symbol.isAnnotatedWithAny(session, setOf(classIds.onGraphCloseAnnotation))
    if (isInit && isClose) {
      reporter.reportOn(
        source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "A function can't be both an @OnGraphInit and an @OnGraphClose callback.",
      )
    }

    val containingClass = symbol.getContainingClassSymbol() as? FirClassSymbol<*>
    val classKind = containingClass?.classKind
    val isInterface = classKind == ClassKind.INTERFACE
    val isAbstractClass =
      classKind == ClassKind.CLASS && containingClass.resolvedStatus.modality == Modality.ABSTRACT
    if (!isInterface && !isAbstractClass) {
      reporter.reportOn(
        source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks must be declared in an interface or abstract class that a graph extends.",
      )
    }

    if (!declaration.hasBody) {
      reporter.reportOn(
        source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks must have a body. Metro calls them but doesn't implement them.",
      )
    }

    if (!declaration.returnTypeRef.coneType.isUnit) {
      reporter.reportOn(
        declaration.returnTypeRef.source ?: source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks must return Unit.",
      )
    }

    val visibility = declaration.visibility
    val isCallableFromGraph =
      visibility == Visibilities.Public || visibility == Visibilities.Protected
    if (!isCallableFromGraph) {
      reporter.reportOn(
        source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks must be public or protected so generated graphs can call them.",
      )
    }

    if (declaration.typeParameters.isNotEmpty()) {
      reporter.reportOn(
        source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks can't have type parameters.",
      )
    }

    declaration.receiverParameter?.let { receiver ->
      reporter.reportOn(
        receiver.source ?: source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks can't have extension receivers.",
      )
    }

    for (contextParameter in symbol.contextParameterSymbols) {
      reporter.reportOn(
        contextParameter.source ?: source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks can't have context parameters.",
      )
    }

    val annotations = symbol.metroAnnotations()
    val hasOtherRole =
      annotations.isProvides ||
        annotations.isBinds ||
        annotations.isMultibinds ||
        annotations.isInject ||
        annotations.isOptionalBinding ||
        annotations.isIntoMultibinding
    val hasBindingAnnotation = annotations.isScoped || annotations.isQualified
    if (hasOtherRole || hasBindingAnnotation) {
      reporter.reportOn(
        source,
        MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
        "$annotationName callbacks can't have other Metro binding annotations.",
      )
    }

    if (declaration.isSuspend && !session.metroFirBuiltIns.options.enableSuspendProviders) {
      reporter.reportOn(
        source,
        MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
        SuspendDiagnosticMessages.SUSPEND_PROVIDERS_NOT_ENABLED,
      )
    }

    for (parameter in declaration.valueParameters) {
      val parameterSource = parameter.source ?: source
      val parameterAnnotations = parameter.symbol.metroAnnotations()
      if (parameterAnnotations.isAssisted) {
        reporter.reportOn(
          parameterSource,
          MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
          "$annotationName callback parameters can't be @Assisted.",
        )
        continue
      }
      if (parameterAnnotations.isOptionalBinding || parameter.symbol.hasMetroDefault(session)) {
        reporter.reportOn(
          parameterSource,
          MetroDiagnostics.GRAPH_LIFECYCLE_ERROR,
          "$annotationName callback parameters can't be optional or have default values.",
        )
        continue
      }
      validateInjectionSiteType(
        session = session,
        typeRef = parameter.returnTypeRef,
        qualifier = parameterAnnotations.qualifier,
        source = parameterSource,
      )
    }
  }
}

/**
 * Returns `@OnGraphInit` or `@OnGraphClose` when this declaration has that annotation, preferring
 * `@OnGraphInit`, or null when it has neither.
 */
internal fun FirBasedSymbol<*>.lifecycleAnnotationName(session: FirSession): String? {
  val classIds = session.classIds
  return when {
    isAnnotatedWithAny(session, setOf(classIds.onGraphInitAnnotation)) -> "@OnGraphInit"
    isAnnotatedWithAny(session, setOf(classIds.onGraphCloseAnnotation)) -> "@OnGraphClose"
    else -> null
  }
}
