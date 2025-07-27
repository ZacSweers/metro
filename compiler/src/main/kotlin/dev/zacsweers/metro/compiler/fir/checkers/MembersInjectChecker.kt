// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.directCallableSymbols
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isDependencyGraph
import dev.zacsweers.metro.compiler.fir.isGraphFactory
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isUnit

// TODO
//  suggest private?
internal object MembersInjectChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    declaration.source ?: return
    val session = context.session

    // TODO put all these into a set to check?
    if (declaration.symbol.isDependencyGraph(session)) return
    if (declaration.symbol.isGraphFactory(session)) return
    if (declaration.symbol.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations))
      return

    val isConstructorInjected by unsafeLazy {
      declaration.symbol.findInjectConstructors(session, checkClass = true).firstOrNull() != null
    }

    val isInClass = declaration.classKind == ClassKind.CLASS

    for (callable in declaration.symbol.directCallableSymbols()) {
      if (callable is FirConstructorSymbol || callable is FirEnumEntrySymbol) continue
      val annotations = callable.metroAnnotations(session)
      if (!annotations.isInject) continue

      if (!isInClass) {
        reporter.reportOn(
          callable.source,
          MetroDiagnostics.MEMBERS_INJECT_ERROR,
          "Only regular classes can have member injections but containing class was ${declaration.classKind}.",
        )
        continue
      } else if (callable.isAbstract) {
        reporter.reportOn(
          callable.resolvedStatus.source ?: callable.source,
          MetroDiagnostics.MEMBERS_INJECT_STATUS_ERROR,
          "Injected members cannot be abstract.",
        )
        continue
      } else if (callable is FirPropertySymbol && callable.fromPrimaryConstructor) {
        reporter.reportOn(
          callable.source,
          MetroDiagnostics.MEMBERS_INJECT_STATUS_ERROR,
          "Constructor property parameters should not be annotated with `@Inject`. Annotate the constructor or class instead.",
        )
        continue
      }

      if (
        callable is FirPropertySymbol &&
          !callable.resolvedReturnTypeRef.coneType.isMarkedNullable &&
          isConstructorInjected
      ) {
        reporter.reportOn(
          callable.source,
          MetroDiagnostics.MEMBERS_INJECT_WARNING,
          "Non-null injected member property in constructor-injected class should usually be moved to the inject constructor. If this has a default value, use Metro's default values support.",
        )
      }

      if (callable.isSuspend) {
        reporter.reportOn(
          callable.source,
          MetroDiagnostics.MEMBERS_INJECT_ERROR,
          "Injected functions cannot be suspend functions.",
        )
      } else if (annotations.isComposable) {
        reporter.reportOn(
          callable.source,
          MetroDiagnostics.MEMBERS_INJECT_ERROR,
          "Injected members cannot be composable functions.",
        )
      }

      if (callable is FirNamedFunctionSymbol) {
        if (!callable.resolvedReturnType.isUnit) {
          reporter.reportOn(
            callable.resolvedReturnTypeRef.source,
            MetroDiagnostics.MEMBERS_INJECT_RETURN_TYPE_WARNING,
            "Return types for injected member functions will always be ignored.",
          )
        }

        if (callable.typeParameterSymbols.isNotEmpty()) {
          reporter.reportOn(
            callable.source,
            MetroDiagnostics.MEMBERS_INJECT_TYPE_PARAMETERS_ERROR,
            "Injected member functions cannot have type parameters.",
          )
        }
      }
    }
  }
}
