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
import dev.zacsweers.lattice.fir.isAnnotatedWithAny
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.visibility

// TODO
//  some of this changes if we reuse this for `@Binds`
//  What about future Kotlin versions where you can have different get signatures
//  Make visibility error configurable? ERROR/WARN/NONE
internal class ProvidesChecker(
  private val session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  override fun check(
    declaration: FirCallableDeclaration,
    context: CheckerContext,
    reporter: DiagnosticReporter,
  ) {
    val source = declaration.source ?: return

    if (!declaration.isAnnotatedWithAny(session, latticeClassIds.providesAnnotations)) {
      return
    }

    if (declaration.visibility != Visibilities.Private) {
      reporter.reportOn(source, FirLatticeErrors.PROVIDES_SHOULD_BE_PRIVATE, context)
    }

    if (declaration.receiverParameter != null) {
      reporter.reportOn(
        source,
        FirLatticeErrors.PROVIDES_ERROR,
        "`@Provides` declarations may not have receiver parameters.",
        context,
      )
      return
    }

    val bodyExpression =
      when (declaration) {
        is FirSimpleFunction -> declaration.body
        is FirProperty -> {
          declaration.getter?.body ?: declaration.initializer
        }
        else -> return
      }
    if (bodyExpression == null) {
      reporter.reportOn(
        source,
        FirLatticeErrors.PROVIDES_ERROR,
        "`@Provides` declarations must have bodies.",
        context,
      )
      return
    }
  }
}
