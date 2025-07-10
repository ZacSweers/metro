// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.classKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.nameOrSpecialName
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.propertyIfAccessor
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames

// TODO
//  What about future Kotlin versions where you can have different get signatures
//  Check for no conflicting names, requires class-level
internal object ProvidesChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    // Check if this is overriding a provider parent here and error if so. Otherwise people could
    // sneak these by!
    // If we ever wanted to allow providers in the future, this is the check to remove
    if (declaration.isOverride) {
      val overridesAProvider =
        declaration.symbol.directOverriddenSymbolsSafe(context).any {
          it.isAnnotatedWithAny(session, classIds.providesAnnotations)
        }
      if (overridesAProvider) {
        reporter.reportOn(source, FirMetroErrors.PROVIDER_OVERRIDES)
      }
    }

    val annotations = declaration.symbol.metroAnnotations(session)
    if (!annotations.isProvides && !annotations.isBinds) {
      return
    }

    if (annotations.isBinds && annotations.scope != null) {
      reporter.reportOn(
        annotations.scope.fir.source ?: source,
        FirMetroErrors.BINDS_ERROR,
        "@Binds declarations may not have scopes.",
      )
      return
    }

    declaration
      .getAnnotationByClassId(DaggerSymbols.ClassIds.DAGGER_REUSABLE_CLASS_ID, session)
      ?.let {
        reporter.reportOn(it.source ?: source, FirMetroErrors.DAGGER_REUSABLE_ERROR)
        return
      }

    if (declaration.typeParameters.isNotEmpty()) {
      val type = if (annotations.isProvides) "Provides" else "Binds"
      reporter.reportOn(
        source,
        FirMetroErrors.METRO_TYPE_PARAMETERS_ERROR,
        "`@$type` declarations may not have type parameters.",
      )
      return
    }

    // Ensure declarations are within a class/companion object/interface
    if (declaration.symbol.containingClassLookupTag() == null) {
      reporter.reportOn(
        source,
        FirMetroErrors.PROVIDES_ERROR,
        "@Provides/@Binds declarations must be within an interface, class, or companion object. " +
          "If you're seeing this, `${declaration.nameOrSpecialName}` is likely defined as a " +
          "top-level method which isn't supported.",
      )
      return
    } else if (
      annotations.isProvides &&
        declaration.symbol.getContainingClassSymbol()?.classKind?.isObject == true &&
        declaration.symbol.getContainingClassSymbol()?.isCompanion != true
    ) {
      // @Provides declarations can't live in objects currently, this is a common case hit when
      // migrating from Dagger/Anvil and you have a non-contributed @Module,
      // e.g. `@Module object MyModule { /* provides */ }`
      reporter.reportOn(
        source,
        FirMetroErrors.PROVIDES_ERROR,
        "@Provides declarations must be within an interface, class, or companion object. " +
          "`${declaration.nameOrSpecialName}` appears to be defined directly within a " +
          "(non-companion) object.",
      )
      return
    }

    // Check property is not var
    if (declaration is FirProperty && declaration.isVar) {
      reporter.reportOn(source, FirMetroErrors.PROVIDES_ERROR, "@Provides properties cannot be var")
      return
    }

    val returnTypeRef = declaration.propertyIfAccessor.returnTypeRef
    if (returnTypeRef.source?.kind is KtFakeSourceElementKind.ImplicitTypeRef) {
      reporter.reportOn(
        source,
        FirMetroErrors.PROVIDES_ERROR,
        "Implicit return types are not allowed for `@Provides` declarations. Specify the return type explicitly.",
      )
      return
    }

    val returnType = returnTypeRef.coneTypeOrNull ?: return

    val bodyExpression =
      when (declaration) {
        is FirSimpleFunction -> declaration.body
        is FirProperty -> {
          declaration.getter?.body ?: declaration.initializer
        }
        else -> return
      }

    val isPrivate = declaration.visibility == Visibilities.Private
    if (declaration !is FirProperty) {
      if (
        session.metroFirBuiltIns.options.publicProviderSeverity !=
          MetroOptions.DiagnosticSeverity.NONE
      ) {
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
          reporter.reportOn(source, diagnosticFactory, message)
        }
      }
    } else if (isPrivate /* && is FirProperty */) {
      reporter.reportOn(
        source,
        FirMetroErrors.PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE,
        "`@Provides` properties cannot be private yet.",
      )
      return
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
              )
            }
          } else if (!implType.isSubtypeOf(boundType, session)) {
            reporter.reportOn(
              source,
              FirMetroErrors.PROVIDES_ERROR,
              "Binds receiver type `${implType.renderReadableWithFqNames()}` is not a subtype of bound type `${boundType.renderReadableWithFqNames()}`.",
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
            "`@Provides` extension $name just returning `this` should be annotated with `@Binds` instead for these. See https://zacsweers.github.io/metro/bindings/#binds for more information.",
          )
          return
        } else if (!returnsThis && annotations.isBinds) {
          reporter.reportOn(
            source,
            FirMetroErrors.BINDS_ERROR,
            "`@Binds` declarations with bodies should just return `this`. See https://zacsweers.github.io/metro/bindings/#binds for more information.",
          )
          return
        }

        if (annotations.isProvides) {
          reporter.reportOn(
            source,
            FirMetroErrors.PROVIDES_ERROR,
            "`@Provides` $name may not be extension $name. Use `@Binds` instead for these. See https://zacsweers.github.io/metro/bindings/#binds for more information.",
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
        )
        return
      }

      if (returnType.typeArguments.isEmpty()) {
        val returnClass = returnType.toClassSymbol(session) ?: return
        val injectConstructor = returnClass.findInjectConstructors(session).firstOrNull()

        if (injectConstructor != null) {
          // If the type keys and scope are the same, this is redundant
          val classTypeKey =
            FirTypeKey.from(
              session,
              returnType,
              returnClass.resolvedCompilerAnnotationsWithClassIds,
            )
          val providerTypeKey = FirTypeKey.from(session, returnType, declaration.annotations)
          if (classTypeKey == providerTypeKey) {
            val providerScope = annotations.scope
            val classScope =
              returnClass.resolvedCompilerAnnotationsWithClassIds
                .scopeAnnotations(session)
                .singleOrNull()
            // TODO maybe we should report matching keys but different scopes? Feels like it could
            //  be confusing at best
            if (providerScope == classScope) {
              reporter.reportOn(
                source,
                FirMetroErrors.PROVIDES_WARNING,
                "Provided type '${classTypeKey.render(short = false, includeQualifier = true)}' is already constructor-injected and does not need to be provided explicitly. Consider removing this `@Provides` declaration.",
              )
              return
            }
          }
        }
      }

      if (declaration is FirSimpleFunction) {
        for (parameter in declaration.valueParameters) {
          val assistedAnnotation =
            parameter.annotationsIn(session, classIds.assistedAnnotations).firstOrNull() ?: continue
          reporter.reportOn(
            assistedAnnotation.source ?: parameter.source ?: source,
            FirMetroErrors.PROVIDES_ERROR,
            "Assisted parameters are not supported for `@Provides` methods. Create a concrete assisted-injected factory class instead.",
          )
          return
        }
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
