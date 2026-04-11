// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_INJECT_ERROR
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit

/** Whether the `@CircuitInject` site is a Presenter or UI. */
private enum class CircuitInjectSiteType {
  PRESENTER,
  UI,
}

/**
 * Validates parameters for a `@CircuitInject` site (function or class constructor).
 *
 * Rules:
 * - CircuitContext is never allowed (it's factory-level only)
 * - Navigator is presenter-only
 * - Modifier and CircuitUiState subtypes are UI-only
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
private fun validateCircuitInjectParams(
  siteType: CircuitInjectSiteType?,
  params: List<FirValueParameter>,
  fallbackSource: KtSourceElement,
  circuitSymbols: CircuitSymbols.Fir,
  siteLabel: String,
) {
  for (param in params) {
    val paramClassId = param.returnTypeRef.coneType.classId ?: continue

    // CircuitContext is factory-level only — never allowed
    if (circuitSymbols.isCircuitContextType(paramClassId)) {
      reporter.reportOn(
        param.source ?: fallbackSource,
        CIRCUIT_INJECT_ERROR,
        "@CircuitInject $siteLabel cannot have a CircuitContext parameter. CircuitContext is only available at the factory level.",
      )
    }

    if (siteType == null) continue

    // Navigator is presenter-only
    if (siteType == CircuitInjectSiteType.UI && circuitSymbols.isNavigatorType(paramClassId)) {
      reporter.reportOn(
        param.source ?: fallbackSource,
        CIRCUIT_INJECT_ERROR,
        "@CircuitInject UI $siteLabel cannot have a Navigator parameter. Navigator is only for presenters.",
      )
    }

    // Modifier and CircuitUiState are UI-only
    if (siteType == CircuitInjectSiteType.PRESENTER) {
      if (circuitSymbols.isModifierType(paramClassId)) {
        reporter.reportOn(
          param.source ?: fallbackSource,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject Presenter $siteLabel cannot have a Modifier parameter. Modifier is only for UI.",
        )
      }
      if (circuitSymbols.isUiStateType(paramClassId)) {
        reporter.reportOn(
          param.source ?: fallbackSource,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject Presenter $siteLabel cannot have a CircuitUiState parameter. State parameters are only for UI.",
        )
      }
    }
  }
}

/** FIR checker for `@CircuitInject` annotation usage on classes. */
internal object CircuitInjectClassChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    context(context.session.compatContext) { checkImpl(declaration) }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkImpl(declaration: FirClass) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds
    val circuitSymbols = session.circuitFirSymbols ?: return

    if (!declaration.hasAnnotation(CircuitClassIds.CircuitInject, session)) return

    // @CircuitInject on @AssistedInject class — should be on the @AssistedFactory instead
    if (declaration.isAnnotatedWithAny(session, classIds.assistedInjectAnnotations)) {
      val hasNestedFactory = hasNestedAssistedFactory(declaration, session)
      if (hasNestedFactory) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject with @AssistedInject must be placed on the nested @AssistedFactory interface, not the class itself.",
        )
      } else {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@AssistedInject class with @CircuitInject must have a nested @AssistedFactory-annotated interface.",
        )
      }
      return
    } else if (declaration.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)) {
      if (declaration.symbol.classId.outerClassId == null) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject @AssistedFactory must be nested inside the target Presenter or Ui class.",
        )
      }
      return
    }

    // Determine site type for param validation
    var siteType: CircuitInjectSiteType? = null
    for (supertype in declaration.symbol.getSuperTypes(session)) {
      // TODO remove expectAs after 2.3.20
      when (supertype.expectAs<ConeKotlinType>().classId) {
        CircuitClassIds.Ui -> {
          siteType = CircuitInjectSiteType.UI
          break
        }
        CircuitClassIds.Presenter -> {
          siteType = CircuitInjectSiteType.PRESENTER
          break
        }
      }
    }

    // For non-assisted classes, validate supertypes
    if (declaration.classKind != ClassKind.OBJECT && (siteType == null)) {
      reporter.reportOn(
        source,
        CIRCUIT_INJECT_ERROR,
        "@CircuitInject-annotated class must implement Presenter or Ui.",
      )
    }

    @OptIn(DirectDeclarationsAccess::class)
    for (constructor in declaration.declarations.filterIsInstance<FirConstructor>()) {
      validateCircuitInjectParams(
        siteType,
        constructor.valueParameters,
        source,
        circuitSymbols,
        "classes",
      )
    }
  }

  @OptIn(DirectDeclarationsAccess::class)
  private fun hasNestedAssistedFactory(declaration: FirClass, session: FirSession): Boolean {
    return declaration.declarations.filterIsInstance<FirClass>().any {
      it.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
    }
  }
}

/** FIR checker for `@CircuitInject`-annotated functions. */
internal object CircuitInjectCallableChecker :
  FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    val source = declaration.source ?: return
    val session = context.session
    val circuitSymbols = session.circuitFirSymbols ?: return

    if (declaration !is FirFunction) return
    if (!declaration.hasAnnotation(CircuitClassIds.CircuitInject, session)) return

    val returnTypeRef = declaration.returnTypeRef
    val returnType = returnTypeRef.coneType

    val hasModifier by memoize {
      declaration.valueParameters.any { param ->
        val paramClassId = param.returnTypeRef.coneType.classId ?: return@any false
        circuitSymbols.isModifierType(paramClassId)
      }
    }

    // Check for implicit return type on presenter functions.
    // If the return type is implicit and there's a Modifier param, we can assume it's UI (Unit),
    // so only flag when there's no Modifier param.
    if (returnTypeRef.source?.kind is KtFakeSourceElementKind.ImplicitTypeRef) {
      if (!hasModifier) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject presenter functions must have an explicit CircuitUiState subtype return " +
            "type and cannot be implicit.",
        )
        return
      }
    }

    val siteType: CircuitInjectSiteType?
    if (returnType.isUnit) {
      siteType = CircuitInjectSiteType.UI
      if (!hasModifier) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject @Composable functions that return Unit are treated as UI functions and must have a Modifier parameter. " +
            "If this is a presenter, add a CircuitUiState return type.",
        )
      }
    } else {
      siteType = CircuitInjectSiteType.PRESENTER
      returnType.classLikeLookupTagIfAny?.let { tag ->
        val returnClassId = tag.classId
        if (!circuitSymbols.isUiStateType(returnClassId)) {
          reporter.reportOn(
            source,
            CIRCUIT_INJECT_ERROR,
            "@CircuitInject @Composable presenter functions must return a CircuitUiState subtype.",
          )
        }
      }
    }

    validateCircuitInjectParams(
      siteType,
      declaration.valueParameters,
      source,
      circuitSymbols,
      "functions",
    )
  }
}
