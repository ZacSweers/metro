// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ASSISTED_INJECTION_ERROR
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.checkers.AssistedInjectChecker.FirAssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructor
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.singleAbstractFunction
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import dev.zacsweers.metro.compiler.mapToSetWithDupes
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getStringArgument
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
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    // Check if this is an assisted factory
    val isAssistedFactory =
      declaration.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)

    if (!isAssistedFactory) return

    declaration.validateApiDeclaration("@Assisted.Factory declarations", checkConstructor = true) {
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
      return
    }

    // Ensure target type has an inject constructor
    val targetType = function.resolvedReturnTypeRef.firClassLike(session) as? FirClass? ?: return
    val injectConstructor =
      targetType.symbol.findInjectConstructor(session, checkClass = true) {
        return
      }
    if (injectConstructor == null) {
      reporter.reportOn(
        function.source ?: source,
        ASSISTED_INJECTION_ERROR,
        "Invalid return type: ${targetType.symbol.classId.asSingleFqName()}. `@AssistedFactory` target classes must have a single `@Inject`-annotated constructor or be annotated `@Inject` with only a primary constructor.",
      )
      return
    }

    // check for scopes? Scopes not allowed, dagger ignores them
    // TODO error + test

    val functionParams = function.valueParameterSymbols
    val constructorAssistedParams =
      injectConstructor.valueParameterSymbols.filter {
        it.isAnnotatedWithAny(session, classIds.assistedAnnotations)
      }

    // ensure assisted params match
    if (functionParams.size != constructorAssistedParams.size) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "Assisted parameter mismatch. Expected ${functionParams.size} assisted parameters but found ${constructorAssistedParams.size}.",
      )
      return
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
      return
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
      return
    }

    //    for (parameters in listOf(factoryKeys, constructorKeys)) {
    //      // no qualifiers on assisted params
    //      // TODO error + test. Or just just ignore them?
    //    }

    // check non-matching keys
    if (factoryKeys != constructorKeys) {
      val missingFromFactory = factoryKeys.subtract(constructorKeys).joinToString()
      val missingFromConstructor = constructorKeys.subtract(factoryKeys).joinToString()
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
      return
    }
  }

  // @Assisted parameters are equal, if the type and the identifier match. This subclass makes
  // diffing the parameters easier.
  data class FirAssistedParameterKey(val typeKey: FirTypeKey, val assistedIdentifier: String) {
    private val cachedToString by unsafeLazy {
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
      fun FirValueParameterSymbol.toAssistedParameterKey(
        session: FirSession,
        typeKey: FirTypeKey,
      ): FirAssistedParameterKey {
        return FirAssistedParameterKey(
          typeKey,
          resolvedCompilerAnnotationsWithClassIds
            .annotationsIn(session, session.classIds.assistedAnnotations)
            .singleOrNull()
            ?.getStringArgument(StandardNames.DEFAULT_VALUE_PARAMETER, session)
            .orEmpty(),
        )
      }
    }
  }
}
