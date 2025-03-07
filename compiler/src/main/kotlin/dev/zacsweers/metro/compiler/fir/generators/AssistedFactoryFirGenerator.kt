// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.copyParameters
import dev.zacsweers.metro.compiler.fir.generateMemberFunction
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * For assisted injection, we can generate the assisted factory _for_ the assisted type as a nested
 * interface of the annotated class. This saves the user some boilerplate.
 *
 * Note this is specifically for generating `@AssistedFactory`-annotated declarations, not for
 * generating assisted factory impls.
 */
internal class AssistedFactoryFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val assistedInjectAnnotationPredicate by unsafeLazy {
    annotated(session.classIds.injectAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(assistedInjectAnnotationPredicate)
  }

  private val assistedInjectClasses = mutableMapOf<FirClassLikeSymbol<*>, FirConstructorSymbol>()
  private val assistedFactoriesToClasses =
    mutableMapOf<FirClassLikeSymbol<*>, FirClassLikeSymbol<*>>()
  private val createIdsToFactories = mutableMapOf<CallableId, FirClassSymbol<*>>()

  // Called for generating callables
  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    assistedFactoriesToClasses[classSymbol]?.let { targetClass ->
      assistedInjectClasses[targetClass]?.let {
        // Need to generate a SAM create() for this
        val id = CallableId(classSymbol.classId, Symbols.Names.create)
        createIdsToFactories[id] = classSymbol
        return setOf(id.callableName)
      }
    }

    return super.getCallableNamesForClass(classSymbol, context)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    createIdsToFactories[callableId]?.let { factoryClass ->
      assistedFactoriesToClasses[factoryClass]?.let { targetClass ->
        assistedInjectClasses[targetClass]?.let { constructor ->
          // Generate a create() function

          // Collect assisted params, we need to potentially port their assisted annotations if they
          // have custom identifiers
          val assistedParams =
            constructor.valueParameterSymbols
              // TODO need a predicate?
              .mapNotNull { param ->
                if (!param.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)) {
                  return@mapNotNull null
                }
                MetroFirValueParameter(session, param)
              }
          val createFunction = generateCreateFunction(assistedParams, targetClass, callableId)
          return listOf(createFunction.symbol)
        }
      }
    }
    return super.generateFunctions(callableId, context)
  }

  private fun FirExtension.generateCreateFunction(
    assistedParams: List<MetroFirValueParameter>,
    targetClass: FirClassLikeSymbol<*>,
    callableId: CallableId,
  ): FirSimpleFunction {
    return generateMemberFunction(
      owner = targetClass,
      returnTypeRef = targetClass.constructType().toFirResolvedTypeRef(),
      modality = Modality.ABSTRACT,
      callableId = callableId,
    ) {
      copyParameters(
        functionBuilder = this,
        sourceParameters = assistedParams,
        copyParameterDefaults = true,
      )
    }
  }

  // Called for generating nested names
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    val constructor =
      if (classSymbol.isAnnotatedWithAny(session, session.classIds.injectAnnotations)) {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().singleOrNull {
          it.isPrimary
        }
      } else {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().firstOrNull {
          it.isAnnotatedWithAny(session, session.classIds.injectAnnotations)
        }
      }

    if (constructor != null) {
      // Check if there is already a nested factory. If there is, do nothing.
      val existingFactory =
        classSymbol.declarationSymbols.filterIsInstance<FirClassSymbol<*>>().singleOrNull {
          // TODO also check for factory annotation? Not sure what else we'd do anyway though
          it.name == Symbols.Names.factoryClassName
        }
      if (existingFactory != null) {
        // TODO test this case
        return emptySet()
      }

      assistedInjectClasses[classSymbol] = constructor
      // We want to generate an assisted factory
      return setOf(Symbols.Names.factoryClassName)
    }
    return super.getNestedClassifiersNames(classSymbol, context)
  }

  // Called for generating nested class declarations
  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (owner !is FirRegularClassSymbol) return null
    // This assumes that all callbacks are for assisted. If we ever make this broader in scope then
    // need to track their combos somewhere to check here
    return createNestedClass(owner, name, Keys.Default, classKind = ClassKind.INTERFACE) {
        // annoyingly not implicit from the class kind
        modality = Modality.ABSTRACT
        status { isFun = true }
      }
      .apply {
        replaceAnnotationsSafe(
          annotations +
            buildAnnotation {
              val assistedFactoryClass =
                session.symbolProvider.getClassLikeSymbolByClassId(
                  session.classIds.metroAssistedFactory
                ) as FirRegularClassSymbol
              annotationTypeRef = assistedFactoryClass.defaultType().toFirResolvedTypeRef()
              argumentMapping = buildAnnotationArgumentMapping()
            }
        )
      }
      .symbol
      .also { assistedFactoriesToClasses[it] = owner }
  }
}
