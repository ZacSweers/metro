// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_INJECT_ERROR
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.fir.implementsAny
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.toClassSymbolCompat
import dev.zacsweers.metro.compiler.memoize
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
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit

/**
 * FIR checker for `@CircuitInject` annotation usage on classes. Validates:
 * - Classes with `@CircuitInject` must implement `Presenter` or `Ui`
 * - `@AssistedInject` classes must place `@CircuitInject` on the `@AssistedFactory`, not the class
 * - `@AssistedFactory + @CircuitInject` must be nested inside the target class
 */
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
        // @AssistedInject + @CircuitInject but no nested @AssistedFactory
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@AssistedInject class with @CircuitInject must have a nested @AssistedFactory-annotated interface.",
        )
      }
      return
    } else if (declaration.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)) {
      // @AssistedFactory + @CircuitInject must be nested inside the target Presenter/Ui class
      if (declaration.symbol.classId.outerClassId == null) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject @AssistedFactory must be nested inside the target Presenter or Ui class.",
        )
      }
      return
    }

    // For non-assisted classes, validate supertypes
    val isPresenterOrUi by memoize {
      declaration.implementsAny(session, setOf(CircuitClassIds.Presenter, CircuitClassIds.Ui))
    }
    if (declaration.classKind != ClassKind.OBJECT && !isPresenterOrUi) {
      reporter.reportOn(
        source,
        CIRCUIT_INJECT_ERROR,
        "@CircuitInject-annotated class must implement Presenter or Ui.",
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

/**
 * FIR checker for `@CircuitInject`-annotated functions. Validates:
 * - Presenter functions (non-Unit return) must return a `CircuitUiState` subtype
 * - UI functions (Unit return) must have a `Modifier` parameter
 */
internal object CircuitInjectCallableChecker :
  FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    val source = declaration.source ?: return
    val session = context.session

    if (declaration !is FirFunction) return
    if (!declaration.hasAnnotation(CircuitClassIds.CircuitInject, session)) return

    val returnType = declaration.returnTypeRef.coneType

    if (returnType.isUnit) {
      // Unit return: either a UI function (needs Modifier) or a presenter missing its return type
      val hasModifier =
        declaration.valueParameters.any { param ->
          val paramClassId = param.returnTypeRef.coneType.classId
          paramClassId == CircuitClassIds.Modifier
        }
      if (!hasModifier) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject @Composable functions that return Unit are treated as UI functions and must have a Modifier parameter. " +
            "If this is a presenter, add a CircuitUiState return type.",
        )
      }
    } else {
      // Presenter function must return a CircuitUiState subtype
      returnType.classLikeLookupTagIfAny?.toClassSymbolCompat(session)?.let { returnClassSymbol ->
        val isUiState = returnClassSymbol.implements(CircuitClassIds.CircuitUiState, session)
        if (!isUiState) {
          reporter.reportOn(
            source,
            CIRCUIT_INJECT_ERROR,
            "@CircuitInject @Composable presenter functions must return a CircuitUiState subtype.",
          )
        }
      }
    }
  }
}
