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
package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.copyParameters
import dev.zacsweers.lattice.compiler.fir.generateMemberFunction
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.unsafeLazy
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
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
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
    annotated(session.latticeClassIds.assistedInjectAnnotations.map { it.asSingleFqName() })
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
      assistedInjectClasses[targetClass]?.let { constructor ->
        // Need to generate a SAM create() for this
        val id = CallableId(classSymbol.classId, LatticeSymbols.Names.create)
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
                if (
                  !param.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)
                ) {
                  return@mapNotNull null
                }
                LatticeFirValueParameter(session, param)
              }
          val createFunction =
            generateCreateFunction(assistedParams, targetClass, factoryClass, callableId)
          return listOf(createFunction.symbol)
        }
      }
    }
    return super.generateFunctions(callableId, context)
  }

  @OptIn(SymbolInternals::class)
  private fun FirExtension.generateCreateFunction(
    assistedParams: List<LatticeFirValueParameter>,
    targetClass: FirClassLikeSymbol<*>,
    factoryClass: FirClassSymbol<*>,
    callableId: CallableId,
  ): FirSimpleFunction {
    return generateMemberFunction(
      targetClass = targetClass,
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
      if (
        classSymbol.isAnnotatedWithAny(session, session.latticeClassIds.assistedInjectAnnotations)
      ) {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().singleOrNull {
          it.isPrimary
        }
      } else {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().firstOrNull {
          it.isAnnotatedWithAny(session, session.latticeClassIds.assistedInjectAnnotations)
        }
      }

    if (constructor != null) {
      // Check if there is already a nested factory. If there is, do nothing.
      val existingFactory =
        classSymbol.declarationSymbols.filterIsInstance<FirClassSymbol<*>>().singleOrNull {
          // TODO also check for factory annotation? Not sure what else we'd do anyway though
          it.name == LatticeSymbols.Names.factoryClassName
        }
      if (existingFactory != null) {
        // TODO test this case
        return emptySet()
      }

      assistedInjectClasses[classSymbol] = constructor
      // We want to generate an assisted factory
      return setOf(LatticeSymbols.Names.factoryClassName)
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
    return createNestedClass(owner, name, LatticeKeys.Default, classKind = ClassKind.INTERFACE) {
        status { isFun = true }
      }
      .apply {
        replaceAnnotations(
          annotations +
            buildAnnotation {
              val assistedFactoryClass =
                session.symbolProvider.getClassLikeSymbolByClassId(
                  session.latticeClassIds.latticeAssistedFactory
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
