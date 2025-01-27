/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isDependencyGraph
import dev.zacsweers.metro.compiler.fir.isGraphFactory
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.orElse
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isUnit

// TODO
//  suggest private?
internal object MembersInjectChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session

    // TODO put all these into a set to check?
    if (declaration.symbol.isDependencyGraph(session)) return
    if (declaration.symbol.isGraphFactory(session)) return
    if (
      declaration.symbol.isAnnotatedWithAny(
        session,
        session.classIds.assistedFactoryAnnotations,
      )
    )
      return

    val isConstructorInjected by unsafeLazy {
      declaration.symbol.findInjectConstructors(session, checkClass = true).firstOrNull() != null
    }

    val isInClass = declaration.classKind == ClassKind.CLASS

    for (callable in declaration.declarations.filterIsInstance<FirCallableDeclaration>()) {
      if (callable is FirConstructor || callable is FirEnumEntry) continue
      val annotations = callable.symbol.metroAnnotations(session)
      if (!annotations.isInject) continue

      if (!isInClass) {
        reporter.reportOn(
          callable.source,
          FirMetroErrors.MEMBERS_INJECT_ERROR,
          "Only regular classes can have member injections but containing class was ${declaration.classKind}.",
          context,
        )
        continue
      } else if (callable.isAbstract) {
        reporter.reportOn(
          callable.status.source ?: callable.source,
          FirMetroErrors.MEMBERS_INJECT_STATUS_ERROR,
          "Injected members cannot be abstract.",
          context,
        )
        continue
      } else if (callable is FirProperty && callable.fromPrimaryConstructor == true) {
        reporter.reportOn(
          callable.source,
          FirMetroErrors.MEMBERS_INJECT_STATUS_ERROR,
          "Constructor property parameters should not be annotated with `@Inject`. Annotate the constructor or class instead.",
          context,
        )
        continue
      }

      if (
        callable is FirProperty &&
          !callable.returnTypeRef.coneTypeOrNull?.isMarkedNullable.orElse(false) &&
          isConstructorInjected
      ) {
        reporter.reportOn(
          callable.source,
          FirMetroErrors.MEMBERS_INJECT_WARNING,
          "Non-null injected member property in constructor-injected class should usually be moved to the inject constructor. If this has a default value, use Metro's default values support.",
          context,
        )
      }

      if (callable.isSuspend) {
        reporter.reportOn(
          callable.source,
          FirMetroErrors.MEMBERS_INJECT_ERROR,
          "Injected functions cannot be suspend functions.",
          context,
        )
      } else if (annotations.isComposable) {
        reporter.reportOn(
          callable.source,
          FirMetroErrors.MEMBERS_INJECT_ERROR,
          "Injected members cannot be composable functions.",
          context,
        )
      }

      if (callable is FirSimpleFunction) {
        callable.returnTypeRef.coneTypeOrNull?.let {
          if (!it.isUnit) {
            reporter.reportOn(
              callable.returnTypeRef.source,
              FirMetroErrors.MEMBERS_INJECT_RETURN_TYPE_WARNING,
              "Return types for injected member functions will always be ignored.",
              context,
            )
          }
        }
      }
    }
  }
}
