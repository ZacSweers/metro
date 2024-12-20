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
import dev.zacsweers.lattice.fir.FirTypeKey
import dev.zacsweers.lattice.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.fir.latticeClassIds
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames

// TODO
//  some of this changes if we reuse this for `@Binds`
//  What about future Kotlin versions where you can have different get signatures
//  Make visibility error configurable? ERROR/WARN/NONE
internal object ProvidesChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  override fun check(
    declaration: FirCallableDeclaration,
    context: CheckerContext,
    reporter: DiagnosticReporter,
  ) {
    val source = declaration.source ?: return
    val session = context.session
    val latticeClassIds = session.latticeClassIds

    if (!declaration.isAnnotatedWithAny(session, latticeClassIds.providesAnnotations)) {
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

    if (declaration.visibility != Visibilities.Private && bodyExpression != null) {
      reporter.reportOn(source, FirLatticeErrors.PROVIDES_SHOULD_BE_PRIVATE, context)
    }

    if (declaration.receiverParameter != null) {
      if (bodyExpression == null) {
        // Treat this as a Binds provider
        // Validate the assignability
        val implType = declaration.receiverParameter?.typeRef?.coneType ?: return
        val boundType = declaration.returnTypeRef.coneType

        if (implType == boundType) {
          // Compare type keys. Different qualifiers are ok
          val returnTypeKey =
            when (declaration) {
              is FirSimpleFunction -> FirTypeKey.from(session, latticeClassIds, declaration)
              is FirProperty -> FirTypeKey.from(session, latticeClassIds, declaration)
              else -> return
            }
          val receiverTypeKey =
            FirTypeKey.from(session, latticeClassIds, declaration.receiverParameter!!, declaration)
          if (returnTypeKey == receiverTypeKey) {
            reporter.reportOn(
              source,
              FirLatticeErrors.PROVIDES_ERROR,
              "Binds receiver type `${receiverTypeKey.simpleString()}` is the same type and qualifier as the bound type `${returnTypeKey.simpleString()}`.",
              context,
            )
          }
        } else if (!implType.isSubtypeOf(boundType, session)) {
          reporter.reportOn(
            source,
            FirLatticeErrors.PROVIDES_ERROR,
            "Binds receiver type `${implType.renderReadableWithFqNames()}` is not a subtype of bound type `${boundType.renderReadableWithFqNames()}`.",
            context,
          )
        }
      } else {
        reporter.reportOn(
          source,
          FirLatticeErrors.PROVIDES_ERROR,
          // TODO link a docsite link
          "`@Provides` declarations may not have receiver parameters unless they are binds providers.",
          context,
        )
      }
      return
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
