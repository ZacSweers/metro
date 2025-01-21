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

import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.parentAnnotated
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getContainingDeclaration
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

/**
 * Generates factory supertypes onto companion objects of `@DependencyGraph` types IFF the graph has
 * a factory creator that is an interface.
 *
 * Given this example:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     operator fun invoke(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 * }
 * ```
 *
 * This will generate the following:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     // ...
 *   }
 *
 *   // ----------------vv
 *   companion object : AppGraph.Factory {
 *     // ...
 *   }
 * }
 * ```
 */
internal class GraphFactoryFirSupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session) {
  private val dependencyGraphCompanionPredicate by unsafeLazy {
    parentAnnotated(session.latticeClassIds.dependencyGraphAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(dependencyGraphCompanionPredicate)
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    return declaration.symbol.isCompanion &&
      declaration.getContainingClassSymbol()?.isDependencyGraph(session) == true
    // TODO why does the above work but not the predicate matcher?
    //  session.predicateBasedProvider.matches(dependencyGraphCompanionPredicate, declaration)
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val graphCreator =
      computeCompanionSupertype(classLikeDeclaration, typeResolver) ?: return emptyList()

    // TODO generics?
    return listOf(graphCreator)
  }

  // Called for companion objects we generate for graph classes
  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<FirResolvedTypeRef> {
    val graphCreator = computeCompanionSupertype(klass, typeResolver) ?: return emptyList()

    return listOf(graphCreator.toFirResolvedTypeRef())
  }

  private fun computeCompanionSupertype(
    companionClass: FirClassLikeDeclaration,
    typeResolver: TypeResolveService,
  ): ConeClassLikeType? {
    val graphCreator =
      resolveCreatorInterfaceFromGraphCompanion(companionClass, typeResolver) ?: return null
    // TODO generics?
    // TODO check for existing supertype?
    return graphCreator.defaultType()
  }

  private fun resolveCreatorInterfaceFromGraphCompanion(
    declaration: FirClassLikeDeclaration,
    typeResolver: TypeResolveService,
  ): FirClass? {
    val graphClass = declaration.getContainingDeclaration(session) ?: return null
    if (graphClass !is FirClass) return null

    return graphClass.declarations.filterIsInstance<FirClass>().firstOrNull {
      if (it.symbol.isCompanion) return@firstOrNull false
      if (!it.symbol.isInterface) return@firstOrNull false

      for (annotation in it.annotations) {
        val typeRef = annotation.annotationTypeRef
        // Resolve the annotation type
        val refToCheck =
          if (typeRef is FirUserTypeRef) {
            typeResolver.resolveUserType(typeRef)
          } else {
            typeRef
          }
        val classId = refToCheck.coneTypeOrNull?.classId ?: continue
        if (classId in session.latticeClassIds.dependencyGraphFactoryAnnotations) {
          return@firstOrNull true
        }
      }
      false
    }
  }
}
