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

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.fir.FirLatticeErrors
import dev.zacsweers.lattice.fir.findInjectConstructor
import dev.zacsweers.lattice.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.fir.singleAbstractFunction
import dev.zacsweers.lattice.fir.validateFactoryClass
import dev.zacsweers.lattice.fir.validateInjectedClass
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.resolve.firClassLike

internal class AssistedInjectChecker(
  private val session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    val source = declaration.source ?: return

    // Check if this is an assisted factory
    val isAssistedFactory =
      declaration.isAnnotatedWithAny(session, latticeClassIds.assistedFactoryAnnotations)

    if (!isAssistedFactory) return

    // TODO test
    if (declaration.isLocal) {
      reporter.reportOn(source, FirLatticeErrors.LOCAL_CLASSES_CANNOT_BE_INJECTED, context)
      return
    }
    declaration.validateFactoryClass(context, reporter, "Assisted factory") {
      return
    }

    // Get single abstract function
    // TODO test
    val function =
      declaration.singleAbstractFunction(session, context, reporter, "@AssistedFactory") {
        return
      }

    // Ensure target type has an assistedinject constructor
    val targetType = function.returnTypeRef.firClassLike(session) as? FirClass? ?: return
    val injectConstructor =
      targetType.findInjectConstructor(session, latticeClassIds, context, reporter) {
        return
      }
    if (injectConstructor == null) {
      // TODO error + test
      return
    }
    if (
      !injectConstructor.annotations.isAnnotatedWithAny(
        session,
        latticeClassIds.assistedAnnotations,
      )
    ) {
      // TODO error + test
      return
    }

    // check for scopes? Scopes not allowed
    // TODO error + test

    val functionParams = function.valueParameters
    val assistedParams =
      injectConstructor.valueParameterSymbols.filter {
        it.annotations.isAnnotatedWithAny(session, latticeClassIds.assistedAnnotations)
      }

    // ensure assisted params match
    if (functionParams.size != assistedParams.size) {
      // TODO error + test
      return
    }

    // check duplicate keys
    // TODO error + test

    // check non-matching keys
    // TODO error + test

    // no qualifiers on assisted params
    // TODO error + test

    // if multiple types with same typekey, all need custom keys. OR all but one?
    // TODO error + test
  }
}
