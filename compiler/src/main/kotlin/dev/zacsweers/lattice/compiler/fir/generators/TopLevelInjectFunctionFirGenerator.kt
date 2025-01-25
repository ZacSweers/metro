/*
 * Copyright (C) 2025 Zac Sweers
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
import dev.zacsweers.lattice.compiler.asName
import dev.zacsweers.lattice.compiler.capitalizeUS
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.latticeFirBuiltIns
import dev.zacsweers.lattice.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.lattice.compiler.latticeAnnotations
import dev.zacsweers.lattice.compiler.mapToArray
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.withArguments
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * For top-level `@Inject`-annotated functions we generate synthetic classes.
 *
 * ```
 * @Inject
 * fun MyApp(message: String) {
 *   // ...
 * }
 * ```
 *
 * Will generate
 *
 * ```
 * class MyApp @Inject constructor(private val message: String) {
 *   operator fun invoke() {
 *     MyApp(message)
 *   }
 * }
 * ```
 *
 * Annotations and `suspend` modifiers will be copied over as well.
 */
internal class TopLevelInjectFunctionFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  // TODO
  //  private works
  //  visibility of params and return type
  //  no extension receivers

  private val injectAnnotationPredicate by unsafeLazy {
    annotated(session.latticeClassIds.injectAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(injectAnnotationPredicate)
  }

  private val symbols: FirCache<Unit, Map<ClassId, FirNamedFunctionSymbol>, TypeResolveService?> =
    session.firCachesFactory.createCache { _, _ ->
      session.predicateBasedProvider
        .getSymbolsByPredicate(injectAnnotationPredicate)
        .filterIsInstance<FirNamedFunctionSymbol>()
        .filter { it.callableId.classId == null }
        .associateBy {
          ClassId(
            it.callableId.packageName,
            "${it.callableId.callableName.capitalizeUS()}Class".asName(),
          )
        }
    }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    return symbols.getValue(Unit, null).keys
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val function = symbols.getValue(Unit, null).getValue(classId)
    val annotations = function.latticeAnnotations(session)
    return createTopLevelClass(classId, LatticeKeys.TopLevelInjectFunctionClass)
      .apply {
        replaceAnnotationsSafe(
          buildList {
            add(buildInjectAnnotation())
            annotations.qualifier?.fir?.let(::add)
            annotations.scope?.fir?.let(::add)
          }
        )
      }
      .symbol
  }

  private fun buildInjectAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.latticeFirBuiltIns.injectClassSymbol }
  }

  private fun buildComposableAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.latticeFirBuiltIns.composableClassSymbol }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    // TODO Generate companion if >0 args, object for 0 args
    return super.generateNestedClassLikeDeclaration(owner, name, context)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return super.getNestedClassifiersNames(classSymbol, context)
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    return if (classSymbol.hasOrigin(LatticeKeys.TopLevelInjectFunctionClass)) {
      setOf(SpecialNames.INIT, LatticeSymbols.Names.invoke)
    } else {
      emptySet()
    }
  }

  private fun functionFor(classId: ClassId) = symbols.getValue(Unit, null).getValue(classId)

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    if (context.owner.hasOrigin(LatticeKeys.TopLevelInjectFunctionClass)) {
      val function = functionFor(context.owner.classId)
      val nonAssistedParams =
        function.valueParameterSymbols.filterNot {
          it.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)
        }
      return createConstructor(
          context.owner,
          LatticeKeys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          for (param in nonAssistedParams) {
            valueParameter(
              param.name,
              typeProvider = {
                param.resolvedReturnType.withArguments(
                  it.mapToArray(FirTypeParameterRef::toConeType)
                )
              },
              key = LatticeKeys.ValueParameter,
            )
          }
        }
        .symbol
        .let(::listOf)
    }
    return emptyList()
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val owner = context?.owner ?: return emptyList()
    check(callableId.callableName == LatticeSymbols.Names.invoke)
    val function = symbols.getValue(Unit, null).getValue(context.owner.classId)
    return createMemberFunction(
        owner,
        LatticeKeys.TopLevelInjectFunctionClassFunction,
        callableId.callableName,
        returnTypeProvider = {
          function.resolvedReturnType.withArguments(it.mapToArray(FirTypeParameterRef::toConeType))
        },
      ) {
        status {
          isOperator = true
          isSuspend = function.isSuspend
          // TODO others?
        }

        for (param in function.valueParameterSymbols) {
          if (!param.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)) {
            continue
          }
          valueParameter(
            param.name,
            typeProvider = {
              param.resolvedReturnType.withArguments(it.mapToArray(FirTypeParameterRef::toConeType))
            },
            key = LatticeKeys.ValueParameter,
          )
        }
      }
      .apply {
        if (function.hasAnnotation(LatticeSymbols.ClassIds.composable, session)) {
          replaceAnnotationsSafe(listOf(buildComposableAnnotation()))
        }
      }
      .symbol
      .let(::listOf)
  }
}
