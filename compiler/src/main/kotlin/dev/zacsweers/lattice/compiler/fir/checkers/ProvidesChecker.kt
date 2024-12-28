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
package dev.zacsweers.lattice.compiler.fir.checkers

import dev.zacsweers.lattice.compiler.fir.FirLatticeErrors
import dev.zacsweers.lattice.compiler.fir.FirTypeKey
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.getDirectOverriddenSymbols
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames

// TODO
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

    // Check if this is overridding a provider parent here and error if so. Otherwise people could
    // sneak these by!
    // If we ever wanted to allow providers in the future, this is the check to remove
    if (declaration.isOverride) {
      val overridesAProvider =
        declaration.getDirectOverriddenSymbols(context).any {
          it.isAnnotatedWithAny(session, latticeClassIds.providesAnnotations)
        }
      if (overridesAProvider) {
        reporter.reportOn(source, FirLatticeErrors.PROVIDER_OVERRIDES, context)
      }
    }

    val isProvides = declaration.isAnnotatedWithAny(session, latticeClassIds.providesAnnotations)
    val isBinds = declaration.isAnnotatedWithAny(session, latticeClassIds.bindsAnnotations)
    if (!isProvides && !isBinds) {
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

    val isPrivate = declaration.visibility == Visibilities.Private
    if (!isPrivate && (isProvides || /* isBinds && */ bodyExpression != null)) {
      val message =
        if (isBinds) {
          "`@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private."
        } else {
          "`@Provides` declarations should be private."
        }
      reporter.reportOn(
        source,
        FirLatticeErrors.PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE,
        message,
        context,
      )
    }

    if (declaration.receiverParameter != null) {
      if (bodyExpression == null) {
        if (isBinds) {
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
              FirTypeKey.from(
                session,
                latticeClassIds,
                declaration.receiverParameter!!,
                declaration,
              )
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
          return
        } else {
          // Fall through to the Provides-without-body error below
        }
      } else {
        val name = if (declaration is FirSimpleFunction) "functions" else "properties"
        // Check if the body expression is just returning "this"
        // NOTE we only do this check for `@Provides`. It's valid to annotate a
        // `@Binds` with a body if the caller wants to still mark it private
        val returnsThis = bodyExpression.returnsThis()
        if (returnsThis && isProvides) {
          reporter.reportOn(
            source,
            FirLatticeErrors.PROVIDES_COULD_BE_BINDS,
            // TODO link a docsite link
            "`@Provides` extension $name just returning `this` should be annotated with `@Binds` instead for these.",
            context,
          )
          return
        } else if (!returnsThis && isBinds) {
          reporter.reportOn(
            source,
            FirLatticeErrors.BINDS_ERROR,
            "`@Binds` declarations with bodies should just return `this`.",
            context,
          )
          return
        }

        if (isProvides) {
          reporter.reportOn(
            source,
            FirLatticeErrors.PROVIDES_ERROR,
            // TODO link a docsite link
            "`@Provides` $name may not be extension $name. Use `@Binds` instead for these.",
            context,
          )
          return
        }
      }
    }

    if (isProvides) {
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

  private fun FirExpression.returnsThis(): Boolean {
    if (this is FirBlock) {
      if (statements.size == 1) {
        val singleStatement = statements[0]
        if (singleStatement is FirReturnExpression) {
          if (singleStatement.result is FirThisReceiverExpression) {
            return true
          }
        }
      }
    }
    return false
  }
}
