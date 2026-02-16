// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ASSISTED_INJECTION_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ASSISTED_INJECTION_WARNING
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.checkers.AssistedInjectChecker.FirAssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.findAssistedInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.singleAbstractFunction
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import dev.zacsweers.metro.compiler.mapToSetWithDupes
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection

internal object AssistedInjectChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    context(context.session.compatContext) { checkImpl(declaration) }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter, compatContext: CompatContext)
  private fun checkImpl(declaration: FirClass) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    // Check if this is an assisted factory
    val isAssistedFactory =
      declaration.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)

    if (isAssistedFactory) {
      checkAssistedFactory(declaration, source, session, classIds)
      return
    }

    val assistedInjectConstructors =
      declaration.symbol.findAssistedInjectConstructors(session, checkClass = true)
    if (assistedInjectConstructors.isNotEmpty()) {
      val qualifier = declaration.symbol.qualifierAnnotation(session)
      if (qualifier != null) {
        reporter.reportOn(
          source,
          ASSISTED_INJECTION_ERROR,
          "@AssistedInject-annotated classes cannot be annotated with qualifier annotations.",
        )
      }
    }
  }

  // TODO validate the assisted injection here too?
  context(context: CheckerContext, reporter: DiagnosticReporter, compatContext: CompatContext)
  private fun checkAssistedFactory(
    declaration: FirClass,
    source: KtSourceElement,
    session: FirSession,
    classIds: ClassIds,
  ) {
    declaration.validateApiDeclaration("@AssistedFactory declarations", checkConstructor = true) {
      return
    }

    // Get single abstract function
    val function =
      declaration.singleAbstractFunction(
        session,
        reporter,
        "@AssistedFactory declarations",
        allowProtected = true,
      ) {
        return
      }

    // TODO dagger doesn't allow type params on these, but seems like we could?
    if (function.typeParameterSymbols.isNotEmpty()) {
      reporter.reportOn(
        function.source ?: source,
        ASSISTED_INJECTION_ERROR,
        "`@AssistedFactory` functions cannot have type parameters.",
      )
    }

    // Ensure target type has an assisted inject constructor
    val targetType = function.resolvedReturnTypeRef.firClassLike(session) as? FirClass? ?: return
    val injectConstructor =
      targetType.symbol.findAssistedInjectConstructors(session, checkClass = true).firstOrNull()
    if (injectConstructor == null) {
      reporter.reportOn(
        function.resolvedReturnTypeRef.source ?: function.source ?: source,
        ASSISTED_INJECTION_ERROR,
        "Invalid return type: ${targetType.symbol.classId.asSingleFqName()}. `@AssistedFactory` target classes must have a single `@AssistedInject`-annotated constructor or be annotated `@AssistedInject` with only a primary constructor.",
      )
      return
    }

    // check for scopes? Scopes not allowed, dagger ignores them
    // TODO error + test

    val functionParams = function.valueParameterSymbols
    val constructorAssistedParams =
      injectConstructor.constructor?.valueParameterSymbols.orEmpty().filter {
        it.isAnnotatedWithAny(session, classIds.assistedAnnotations)
      }

    // Extract concrete type arguments from the factory's return type
    val returnType = function.resolvedReturnTypeRef.coneType
    val targetSubstitutionMap = mutableMapOf<FirTypeParameterSymbol, ConeKotlinType>()

    if (returnType is ConeClassLikeType && returnType.typeArguments.isNotEmpty()) {
      targetType.typeParameters.zip(returnType.typeArguments).forEach { (param, arg) ->
        if (arg is ConeKotlinTypeProjection) {
          targetSubstitutionMap[param.symbol] = arg.type
        }
      }
    }

    // Build unified substitution map for factory parameters
    val factorySubstitutionMap = mutableMapOf<FirTypeParameterSymbol, ConeKotlinType>()

    // Map factory type parameters to the same concrete types
    declaration.typeParameters.forEachIndexed { index, factoryTypeParam ->
      val targetTypeParam = targetType.typeParameters.getOrNull(index)
      if (targetTypeParam != null) {
        // Use the concrete type from the return type if available
        val concreteType =
          targetSubstitutionMap[targetTypeParam.symbol] ?: targetTypeParam.toConeType()
        factorySubstitutionMap[factoryTypeParam.symbol] = concreteType
      }
    }

    val functionSubstitutor = substitutorByMap(factorySubstitutionMap, session)

    val (factoryKeys, dupeFactoryKeys) =
      functionParams.mapToSetWithDupes {
        it.toAssistedParameterKey(session, FirTypeKey.from(session, it, functionSubstitutor))
      }

    if (dupeFactoryKeys.isNotEmpty()) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "Assisted factory parameters must be unique. Found duplicates: ${dupeFactoryKeys.joinToString(", ")}",
      )
    }

    val constructorSubstitutor = substitutorByMap(targetSubstitutionMap, session)
    val (constructorKeys, dupeConstructorKeys) =
      constructorAssistedParams.mapToSetWithDupes {
        it.toAssistedParameterKey(session, FirTypeKey.from(session, it, constructorSubstitutor))
      }

    if (dupeConstructorKeys.isNotEmpty()) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "Assisted constructor parameters must be unique. Found duplicates: $dupeConstructorKeys",
      )
    }

    // for (parameters in listOf(factoryKeys, constructorKeys)) {
    //   // no qualifiers on assisted params
    //   // TODO error + test. Or just just ignore them?
    // }

    // check non-matching keys
    if (factoryKeys != constructorKeys) {
      val missingFromFactory = constructorKeys.subtract(factoryKeys).joinToString()
      val missingFromConstructor = factoryKeys.subtract(constructorKeys).joinToString()
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        buildString {
          appendLine(
            "Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:"
          )
          if (missingFromFactory.isNotEmpty()) {
            append("  Missing from factory: ")
            appendLine(missingFromFactory)
          }
          if (missingFromConstructor.isNotEmpty()) {
            append("  Missing from constructor: ")
            appendLine(missingFromConstructor)
          }
        },
      )
    }
  }

  // @Assisted parameters are equal, if the type and the identifier match. This subclass makes
  // diffing the parameters easier.
  data class FirAssistedParameterKey(val typeKey: FirTypeKey, val assistedIdentifier: String) {
    private val cachedToString by memoize {
      buildString {
        append(typeKey)
        if (assistedIdentifier.isNotEmpty()) {
          append(" (")
          append(assistedIdentifier)
          append(")")
        }
      }
    }

    override fun toString() = cachedToString

    companion object {
      context(context: CheckerContext, reporter: DiagnosticReporter)
      fun FirValueParameterSymbol.toAssistedParameterKey(
        session: FirSession,
        typeKey: FirTypeKey,
      ): FirAssistedParameterKey {
        val paramName = name.asString()
        val classIds = session.classIds
        val options = session.metroFirBuiltIns.options

        val assistedAnnotation =
          resolvedCompilerAnnotationsWithClassIds
            .annotationsIn(session, classIds.assistedAnnotations)
            .singleOrNull()

        // Custom/interop annotations (e.g. Dagger's @Assisted) always use param names.
        // For Metro's native @Assisted or no annotation (factory method params), the flag controls
        // whether param names are used as identifiers.
        val isNativeMetroAssisted =
          assistedAnnotation != null &&
            assistedAnnotation.toAnnotationClassIdSafe(session) == classIds.metroAssisted
        val hasCustomAssistedAnnotation = assistedAnnotation != null && !isNativeMetroAssisted

        val useParamNames =
          if (hasCustomAssistedAnnotation) {
            true
          } else {
            options.useAssistedParamNamesAsIdentifiers
          }

        val explicitIdentifier =
          assistedAnnotation
            ?.getStringArgument(StandardNames.DEFAULT_VALUE_PARAMETER, session)
            ?.takeUnless { it.isBlank() }

        if (
          useParamNames &&
            explicitIdentifier != null &&
            options.assistedIdentifierSeverity.isEnabled
        ) {
          val rawArg = assistedAnnotation.findArgumentByName(StandardNames.DEFAULT_VALUE_PARAMETER)
          val diagnostic =
            when (options.assistedIdentifierSeverity) {
              MetroOptions.DiagnosticSeverity.ERROR -> ASSISTED_INJECTION_ERROR
              else -> ASSISTED_INJECTION_WARNING
            }
          reporter.reportOn(
            rawArg?.source,
            diagnostic,
            "Explicit @Assisted identifiers are deprecated. Use matching parameter names instead.",
          )
        }

        val defaultIdentifier = if (useParamNames) paramName else ""
        return FirAssistedParameterKey(typeKey, explicitIdentifier ?: defaultIdentifier)
      }
    }
  }
}
