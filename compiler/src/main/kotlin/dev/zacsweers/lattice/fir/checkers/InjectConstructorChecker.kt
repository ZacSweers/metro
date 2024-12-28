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
package dev.zacsweers.lattice.fir.checkers

import dev.zacsweers.lattice.fir.FirLatticeErrors
import dev.zacsweers.lattice.fir.annotationsIn
import dev.zacsweers.lattice.fir.findInjectConstructor
import dev.zacsweers.lattice.fir.latticeClassIds
import dev.zacsweers.lattice.fir.validateInjectedClass
import dev.zacsweers.lattice.fir.validateVisibility
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny

internal object InjectConstructorChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val latticeClassIds = session.latticeClassIds

    val classInjectAnnotation =
      declaration.annotationsIn(session, latticeClassIds.injectAnnotations).toList()

    val injectedConstructor =
      declaration.findInjectConstructor(session, latticeClassIds, context, reporter) {
        return
      }

    val isInjected = classInjectAnnotation.isNotEmpty() || injectedConstructor != null
    if (!isInjected) return

    if (classInjectAnnotation.isNotEmpty() && injectedConstructor != null) {
      reporter.reportOn(
        injectedConstructor.source,
        FirLatticeErrors.CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
        context,
      )
      return
    }

    declaration.validateInjectedClass(context, reporter) {
      return
    }

    val constructorToValidate = injectedConstructor ?: declaration.primaryConstructorIfAny(session)
    constructorToValidate?.validateVisibility(context, reporter, "Injected constructors") {
      return
    }
  }
}
