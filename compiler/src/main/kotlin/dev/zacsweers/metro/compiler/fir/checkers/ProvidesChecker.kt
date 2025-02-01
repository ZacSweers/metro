// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.KtFakeSourceElementKind
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
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames

// TODO
//  What about future Kotlin versions where you can have different get signatures
//  Check for no conflicting names, requires class-level
internal object ProvidesChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {

  override fun check(
    declaration: FirCallableDeclaration,
    context: CheckerContext,
    reporter: DiagnosticReporter,
  ) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    // Check if this is overriding a provider parent here and error if so. Otherwise people could
    // sneak these by!
    // If we ever wanted to allow providers in the future, this is the check to remove
    if (declaration.isOverride) {
      val overridesAProvider =
        declaration.getDirectOverriddenSymbols(context).any {
          it.isAnnotatedWithAny(session, classIds.providesAnnotations)
        }
      if (overridesAProvider) {
        reporter.reportOn(source, FirMetroErrors.PROVIDER_OVERRIDES, context)
      }
    }

    val annotations = declaration.symbol.metroAnnotations(session)
    if (!annotations.isProvides && !annotations.isBinds) {
      return
    }

    if (declaration.typeParameters.isNotEmpty()) {
      val type = if (annotations.isProvides) "Provides" else "Binds"
      reporter.reportOn(
        source,
        FirMetroErrors.METRO_TYPE_PARAMETERS_ERROR,
        "`@$type` declarations may not have type parameters.",
        context,
      )
      return
    }

    if (declaration.returnTypeRef.source?.kind is KtFakeSourceElementKind.ImplicitTypeRef) {
      reporter.reportOn(
        source,
        FirMetroErrors.PROVIDES_ERROR,
        "Implicit return types are not allowed for `@Provides` declarations. Specify the return type explicitly.",
        context,
      )
      return
    }

    if (declaration.returnTypeRef.coneTypeOrNull?.isMarkedNullable == true) {
      reporter.reportOn(
        source,
        FirMetroErrors.PROVIDES_ERROR,
        "Provider return types cannot be nullable. See https://github.com/ZacSweers/metro/discussions/153",
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

    if (
      session.metroFirBuiltIns.options.publicProviderSeverity !=
        MetroOptions.DiagnosticSeverity.NONE
    ) {
      val isPrivate = declaration.visibility == Visibilities.Private
      if (!isPrivate && (annotations.isProvides || /* isBinds && */ bodyExpression != null)) {
        val message =
          if (annotations.isBinds) {
            "`@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private."
          } else {
            "`@Provides` declarations should be private."
          }
        val diagnosticFactory =
          when (session.metroFirBuiltIns.options.publicProviderSeverity) {
            MetroOptions.DiagnosticSeverity.NONE -> error("Not possible")
            MetroOptions.DiagnosticSeverity.WARN ->
              FirMetroErrors.PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING
            MetroOptions.DiagnosticSeverity.ERROR ->
              FirMetroErrors.PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR
          }
        reporter.reportOn(source, diagnosticFactory, message, context)
      }
    }

    // TODO support first, non-receiver parameter
    if (declaration.receiverParameter != null) {
      if (bodyExpression == null) {
        if (annotations.isBinds) {
          // Treat this as a Binds provider
          // Validate the assignability
          val implType = declaration.receiverParameter?.typeRef?.coneType ?: return
          val boundType = declaration.returnTypeRef.coneType

          if (implType == boundType) {
            // Compare type keys. Different qualifiers are ok
            val returnTypeKey =
              when (declaration) {
                is FirSimpleFunction -> FirTypeKey.from(session, declaration)
                is FirProperty -> FirTypeKey.from(session, declaration)
                else -> return
              }
            val receiverTypeKey =
              FirTypeKey.from(session, declaration.receiverParameter!!, declaration)

            // TODO add a test for isIntoMultibinding
            if (returnTypeKey == receiverTypeKey && !annotations.isIntoMultibinding) {
              reporter.reportOn(
                source,
                FirMetroErrors.PROVIDES_ERROR,
                "Binds receiver type `${receiverTypeKey.render(short = false)}` is the same type and qualifier as the bound type `${returnTypeKey.render(short = false)}`.",
                context,
              )
            }
          } else if (!implType.isSubtypeOf(boundType, session)) {
            reporter.reportOn(
              source,
              FirMetroErrors.PROVIDES_ERROR,
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
        if (returnsThis && annotations.isProvides) {
          reporter.reportOn(
            source,
            FirMetroErrors.PROVIDES_COULD_BE_BINDS,
            // TODO link a docsite link
            "`@Provides` extension $name just returning `this` should be annotated with `@Binds` instead for these.",
            context,
          )
          return
        } else if (!returnsThis && annotations.isBinds) {
          reporter.reportOn(
            source,
            FirMetroErrors.BINDS_ERROR,
            "`@Binds` declarations with bodies should just return `this`.",
            context,
          )
          return
        }

        if (annotations.isProvides) {
          reporter.reportOn(
            source,
            FirMetroErrors.PROVIDES_ERROR,
            // TODO link a docsite link
            "`@Provides` $name may not be extension $name. Use `@Binds` instead for these.",
            context,
          )
          return
        }
      }
    }

    if (annotations.isProvides) {
      if (bodyExpression == null) {
        reporter.reportOn(
          source,
          FirMetroErrors.PROVIDES_ERROR,
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
