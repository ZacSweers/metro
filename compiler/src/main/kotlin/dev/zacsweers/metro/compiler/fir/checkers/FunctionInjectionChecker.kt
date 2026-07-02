// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.FUNCTION_INJECT_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.FUNCTION_INJECT_TYPE_PARAMETERS_ERROR
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.validateInjectionSiteType
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Checker for injected functions.
 *
 * Note: We use [FirCallableDeclarationChecker] instead of `FirSimpleFunctionChecker` because
 * `FirSimpleFunction` was renamed to `FirNamedFunction` in Kotlin 2.3.20, causing linkage failures.
 * We filter for named functions using [CompatContext.isNamedFunction] from the compat layer.
 */
internal object FunctionInjectionChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    // Only check named functions (FirSimpleFunction/FirNamedFunction)
    if (declaration !is FirFunction) return
    val session = context.session
    if (!with(session.compatContext) { declaration.isNamedFunction() }) return
    val source = declaration.source ?: return
    session.trace(name = { "FunctionInjectionChecker(${declaration.symbol.callableId})" }) {
      checkImpl(declaration, source)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkImpl(declaration: FirFunction, source: KtSourceElement) {
    val session = context.session
    val classIds = session.classIds

    if (declaration.dispatchReceiverType != null) return // Instance function, setter injection
    if (!declaration.isAnnotatedWithAny(session, classIds.injectAnnotations)) return

    if (declaration.typeParameters.isNotEmpty()) {
      for (tp in declaration.typeParameters) {
        if (tp.symbol.isReified) {
          reporter.reportOn(
            source,
            FUNCTION_INJECT_TYPE_PARAMETERS_ERROR,
            "Injected functions cannot have reified generics.",
          )
        }
      }
    }

    declaration.symbol.receiverParameterSymbol?.let { param ->
      reporter.reportOn(
        param.source ?: source,
        FUNCTION_INJECT_ERROR,
        "Injected functions cannot have receiver parameters.",
      )
    }

    for (contextParam in declaration.symbol.contextParameterSymbols) {
      if (contextParam.isAnnotatedWithAny(session, session.classIds.optionalBindingAnnotations)) {
        reporter.reportOn(
          contextParam.source ?: source,
          FUNCTION_INJECT_ERROR,
          "Context parameters cannot be annotated @OptionalBinding.",
        )
      }

      if (
        contextParam.name == SpecialNames.UNDERSCORE_FOR_UNUSED_VAR &&
          contextParam.isAnnotatedWithAny(session, classIds.assistedAnnotations)
      ) {
        reporter.reportOn(
          contextParam.source ?: source,
          FUNCTION_INJECT_ERROR,
          "`_` is not allowed for `@Assisted` parameters because assisted factories require matching parameter names.",
        )
      }
    }

    val scope = declaration.symbol.metroAnnotations().scope

    if (scope != null) {
      reporter.reportOn(
        scope.fir.source ?: source,
        FUNCTION_INJECT_ERROR,
        "Injected functions are stateless and should not be scoped.",
      )
    }

    for (param in declaration.valueParameters) {
      val annotations = param.symbol.metroAnnotations()
      if (annotations.isAssisted) continue
      validateInjectionSiteType(
        session = session,
        typeRef = param.returnTypeRef,
        qualifier = annotations.qualifier,
        source = param.source ?: source,
        isAccessor = annotations.isOptionalBinding,
        hasDefault = param.symbol.hasDefaultValue,
      )
    }
  }
}
