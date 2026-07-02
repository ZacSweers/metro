// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.diagnosticString
import dev.zacsweers.metro.compiler.fir.findInjectConstructorsImpl
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.render
import dev.zacsweers.metro.compiler.fir.singleAbstractFunction
import dev.zacsweers.metro.compiler.fir.toClassSymbolCompat
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.name.ClassId

internal object SuspendAwareChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    context(context.session.compatContext) { checkImpl(declaration) }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter, compatContext: CompatContext)
  private fun checkImpl(declaration: FirClass) {
    val session = context.session
    val classIds = session.classIds

    // First: an @AssistedFactory whose target is `@SuspendAware` must declare its SAM as suspend.
    if (declaration.symbol.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)) {
      checkAssistedFactorySam(declaration)
      return
    }

    val injectLikeConstructors =
      declaration.symbol.findInjectConstructorsImpl(
        session = session,
        annotationClassIds = classIds.allInjectAnnotations,
        checkClass = true,
      )
    if (injectLikeConstructors.isEmpty()) return

    val isAlreadySuspendAware =
      declaration.symbol.isAnnotatedWithAny(session, suspendAwareAnnotations)
    if (isAlreadySuspendAware) return

    val constructor: FirConstructorSymbol =
      injectLikeConstructors.firstOrNull()?.constructor ?: return

    val enableFunctionProviders = session.metroFirBuiltIns.options.enableFunctionProviders

    // SuspendProvider/SuspendLazy/`suspend () -> T` ctor params already defer the suspend cost,
    // so a class with only such suspend-typed params doesn't actually need `@SuspendAware`. The
    // hard "you need it" cases are only visible after binding analysis (the IR-level Step 5
    // check in `validateSuspendBindings` reports them with a full trace).
    //
    // The one source-level signal we *can* surface is when a ctor param's type is itself
    // `@SuspendAware`: that strongly suggests the suspend chain propagates through this
    // binding. We still only warn — the consumer might supply that param manually (e.g. via
    // `@BindsInstance`) so we can't be certain at FIR time.
    for (parameter in constructor.valueParameterSymbols) {
      val paramType = parameter.resolvedReturnTypeRef.coneType
      val paramClass = paramType.toClassSymbolCompat(session) ?: continue
      val paramIsSuspendAware = paramClass.isAnnotatedWithAny(session, suspendAwareAnnotations)
      if (paramIsSuspendAware) {
        val rendered = paramType.render(short = true)
        reporter.reportOn(
          parameter.source ?: declaration.source,
          MetroDiagnostics.SUSPEND_AWARE_RECOMMENDED,
          "[Metro/SuspendAwareRecommended] '${declaration.classId.diagnosticString}' likely consumes " +
            "suspend bindings via parameter '${parameter.name.asString()}: $rendered' (the parameter " +
            "type is itself `@SuspendAware`). Annotate the class with `@SuspendAware` unless this " +
            "binding will be provided manually.",
        )
        return
      }
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter, compatContext: CompatContext)
  private fun checkAssistedFactorySam(declaration: FirClass) {
    val session = context.session
    // Find the SAM. If it's not present or the rest of the assisted factory shape is invalid,
    // AssistedInjectChecker will report — we only worry about suspend wiring here.
    val samFunction =
      declaration.singleAbstractFunction(
        session,
        reporter,
        "@AssistedFactory declarations",
        allowProtected = true,
      ) {
        return
      }
    val targetType = samFunction.resolvedReturnTypeRef.firClassLike(session) as? FirClass ?: return
    val targetIsSuspendAware =
      targetType.symbol.isAnnotatedWithAny(session, suspendAwareAnnotations)
    if (!targetIsSuspendAware) return
    if (samFunction.isSuspend) return
    reporter.reportOn(
      samFunction.source ?: declaration.source,
      MetroDiagnostics.METRO_ERROR,
      "[Metro/SuspendAwareAssistedFactoryRequiresSuspend] '${declaration.classId.diagnosticString}' targets " +
        "'${targetType.symbol.classId.diagnosticString}' which is `@SuspendAware`. The factory's " +
        "abstract function must be declared `suspend` so it can await the suspend invoke of the " +
        "underlying factory.",
    )
  }

  private val suspendAwareAnnotations: Set<ClassId> = setOf(Symbols.ClassIds.metroSuspendAware)
}
