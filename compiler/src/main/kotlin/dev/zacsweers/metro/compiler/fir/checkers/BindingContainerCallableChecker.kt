// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.Symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.fir.FirContextualTypeKey
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.BINDING_CONTAINER_ERROR
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.fir.validateInjectionSiteType
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
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
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.correspondingProperty
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.toAnnotationClass
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
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.type

// TODO
//  What about future Kotlin versions where you can have different get signatures
internal object BindingContainerCallableChecker :
  FirCallableDeclarationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val containingClassSymbol by memoize {
      with(session.compatContext) { declaration.getContainingClassSymbol() }
    }
    if (declaration is FirConstructor) {
      val isInBindingContainer =
        containingClassSymbol?.isAnnotatedWithAny(session, classIds.bindingContainerAnnotations)
          ?: false
      if (isInBindingContainer) {
        // Check for Provides annotations on constructor params
        for (param in declaration.valueParameters) {
          val providesAnno =
            listOfNotNull(
                param,
                param.correspondingProperty,
                param.correspondingProperty?.getter,
                param.correspondingProperty?.backingField,
              )
              .firstNotNullOfOrNull {
                it.annotationsIn(session, classIds.providesAnnotations).singleOrNull()
              }
          if (providesAnno != null) {
            reporter.reportOn(
              providesAnno.source,
              BINDING_CONTAINER_ERROR,
              "@${providesAnno.toAnnotationClass(session)?.name} cannot be applied to constructor parameters. Use a member property or function in the class instead.",
            )
          }
        }
      }
      return
    }

    // Check if this is overriding a provider parent here and error if so. Otherwise people could
    // sneak these by!
    // If we ever wanted to allow providers in the future, this is the check to remove
    if (declaration.isOverride) {
      val overridesAProvider =
        declaration.symbol.directOverriddenSymbolsSafe().any {
          it.isAnnotatedWithAny(session, classIds.providesAnnotations)
        }
      if (overridesAProvider) {
        reporter.reportOn(source, MetroDiagnostics.PROVIDER_OVERRIDES)
      }
    }

    val annotations = declaration.symbol.metroAnnotations(session)

    if (
      session.metroFirBuiltIns.options.enableDaggerRuntimeInterop && annotations.isBindsOptionalOf
    ) {
      val contextKey = FirContextualTypeKey.from(session, declaration.symbol)
      if (!contextKey.isCanonical) {
        reporter.reportOn(
          declaration.returnTypeRef.source ?: source,
          MetroDiagnostics.BINDS_OPTIONAL_OF_ERROR,
          "@BindsOptionalOf declarations should return the target type (and not wrapped in Provider, Lazy, etc.)",
        )
      } else {
        // If it's wrapped in Optional, report a warning because it's probably not what they mean
        val isOptional =
          declaration.returnTypeRef.coneTypeOrNull?.classId == Symbols.ClassIds.JavaOptional
        if (isOptional) {
          val genericType =
            declaration.returnTypeRef.coneType.typeArguments.first().type!!.classId!!.shortClassName
          reporter.reportOn(
            declaration.returnTypeRef.source ?: source,
            MetroDiagnostics.BINDS_OPTIONAL_OF_WARNING,
            "@BindsOptionalOf declarations usually just return the target type directly (i.e. `$genericType`) but this suspiciously returns `Optional<$genericType>`, which would result callers to use `Optional<Optional<$genericType>>`. If this is really what you intend, you can suppress this warning.",
          )
        }
      }
      return
    }

    if (!annotations.isProvides && !annotations.isBinds) {
      return
    }

    declaration
      .getAnnotationByClassId(DaggerSymbols.ClassIds.DAGGER_REUSABLE_CLASS_ID, session)
      ?.let {
        reporter.reportOn(it.source ?: source, MetroDiagnostics.DAGGER_REUSABLE_ERROR)
        return
      }

    // After the reusable check because reusable is technically a scope and we don't want to
    // double-report
    if (annotations.isBinds && annotations.scope != null) {
      reporter.reportOn(
        annotations.scope.fir.source ?: source,
        MetroDiagnostics.BINDS_ERROR,
        "@Binds declarations may not have scopes.",
      )
    }

    if (declaration.typeParameters.isNotEmpty()) {
      val type = if (annotations.isProvides) "Provides" else "Binds"
      reporter.reportOn(
        source,
        MetroDiagnostics.METRO_TYPE_PARAMETERS_ERROR,
        "`@$type` declarations may not have type parameters.",
      )
    }

    // Ensure declarations are within a class/companion object/interface
    if (declaration.symbol.containingClassLookupTag() == null) {
      reporter.reportOn(
        source,
        MetroDiagnostics.PROVIDES_ERROR,
        "@Provides/@Binds declarations must be within an interface, class, or companion object. " +
          "If you're seeing this, `${declaration.nameOrSpecialName}` is likely defined as a " +
          "top-level method which isn't supported.",
      )
    }

    if (annotations.isProvides) {
      containingClassSymbol?.let { containingClass ->
        if (!containingClass.isAnnotatedWithAny(session, classIds.bindingContainerAnnotations)) {
          if (containingClass.classKind?.isObject == true && !containingClass.isCompanion) {
            // @Provides declarations can't live in non-@BindingContainer objects, this is a common
            // case hit when migrating from Dagger/Anvil and you have a non-contributed @Module,
            // e.g. `@Module object MyModule { /* provides */ }`
            reporter.reportOn(
              source,
              MetroDiagnostics.PROVIDES_ERROR,
              "@Provides declarations must be within an either a @BindingContainer-annotated class XOR interface, class, or companion object. " +
                "`${declaration.nameOrSpecialName}` appears to be defined directly within a " +
                "(non-companion) object that is not annotated @BindingContainer.",
            )
          }
        }
      }
    }

    // Check property is not var
    if (declaration is FirProperty && declaration.isVar) {
      reporter.reportOn(
        source,
        MetroDiagnostics.PROVIDES_ERROR,
        "@Provides properties cannot be var",
      )
    }

    val returnTypeRef = declaration.propertyIfAccessor.returnTypeRef
    if (returnTypeRef.source?.kind is KtFakeSourceElementKind.ImplicitTypeRef) {
      reporter.reportOn(
        source,
        MetroDiagnostics.PROVIDES_ERROR,
        "Implicit return types are not allowed for `@Provides` declarations. Specify the return type explicitly.",
      )
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
              MetroOptions.DiagnosticSeverity.NONE -> reportCompilerBug("Not possible")
              MetroOptions.DiagnosticSeverity.WARN ->
                MetroDiagnostics.PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING
              MetroOptions.DiagnosticSeverity.ERROR ->
                MetroDiagnostics.PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR
            }
          reporter.reportOn(source, diagnosticFactory, message)
        }
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
                MetroDiagnostics.PROVIDES_ERROR,
                "Binds receiver type `${receiverTypeKey.render(short = false)}` is the same type and qualifier as the bound type `${returnTypeKey.render(short = false)}`.",
              )
            }
          } else if (!implType.isSubtypeOf(boundType, session)) {
            reporter.reportOn(
              source,
              MetroDiagnostics.PROVIDES_ERROR,
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
            MetroDiagnostics.PROVIDES_COULD_BE_BINDS,
            "`@Provides` extension $name just returning `this` should be annotated with `@Binds` instead for these. See https://zacsweers.github.io/metro/latest/bindings/#binds for more information.",
          )
          return
        } else if (!returnsThis && annotations.isBinds) {
          reporter.reportOn(
            source,
            MetroDiagnostics.BINDS_ERROR,
            "`@Binds` declarations with bodies should just return `this`. See https://zacsweers.github.io/metro/latest/bindings/#binds for more information.",
          )
          return
        }

        if (annotations.isProvides) {
          reporter.reportOn(
            source,
            MetroDiagnostics.PROVIDES_ERROR,
            "`@Provides` $name may not be extension $name. Use `@Binds` instead for these. See https://zacsweers.github.io/metro/latest/bindings/#binds for more information.",
          )
          return
        }
      }
    }

    if (annotations.isProvides) {
      if (bodyExpression == null) {
        reporter.reportOn(
          source,
          MetroDiagnostics.PROVIDES_ERROR,
          "`@Provides` declarations must have bodies.",
        )
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
                MetroDiagnostics.REDUNDANT_PROVIDES,
                "Provided type '${classTypeKey.render(short = false, includeQualifier = true)}' is already constructor-injected and does not need to be provided explicitly. Consider removing this `@Provides` declaration.",
              )
            } else if (classScope != null) {
              reporter.reportOn(
                source,
                MetroDiagnostics.CONFLICTING_PROVIDES_SCOPE,
                "Provided type '${classTypeKey.render(short = false, includeQualifier = true)}' is already constructor-injected but declares a different scope. This is likely a bug.",
              )
            }
          }
        }
      }

      if (declaration is FirSimpleFunction) {
        for (parameter in declaration.valueParameters) {
          val annotations =
            parameter.symbol.metroAnnotations(
              session,
              MetroAnnotations.Kind.OptionalBinding,
              MetroAnnotations.Kind.Assisted,
              MetroAnnotations.Kind.Qualifier,
            )

          val assistedAnnotation = annotations.assisted
          if (assistedAnnotation != null) {
            reporter.reportOn(
              assistedAnnotation.fir.source ?: parameter.source ?: source,
              MetroDiagnostics.PROVIDES_ERROR,
              "Assisted parameters are not supported for `@Provides` methods. Create a concrete assisted-injected factory class instead.",
            )
            return
          }

          // Check for lazy-wrapped assisted factories in provides function parameters
          if (
            validateInjectionSiteType(
              session,
              parameter.returnTypeRef,
              annotations.qualifier,
              parameter.source ?: source,
              isOptionalBinding = annotations.isOptionalBinding,
              hasDefault = parameter.symbol.hasDefaultValue,
            )
          ) {
            return
          }
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
