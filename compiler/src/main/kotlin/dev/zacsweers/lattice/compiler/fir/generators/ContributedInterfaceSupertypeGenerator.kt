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

import dev.zacsweers.lattice.compiler.LatticeClassIds
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.asName
import dev.zacsweers.lattice.compiler.fir.annotationsIn
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.toRegularClassSymbol
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.buildUserTypeFromQualifierParts
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

// Toe-hold for contributed types
internal class ContributedInterfaceSupertypeGenerator(
  session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirSupertypeGenerationExtension(session) {

  class Factory(private val latticeClassIds: LatticeClassIds) :
    FirSupertypeGenerationExtension.Factory {
    override fun create(session: FirSession) =
      ContributedInterfaceSupertypeGenerator(session, latticeClassIds)
  }

  private val dependencyGraphPredicate =
    LookupPredicate.create {
      annotated(latticeClassIds.dependencyGraphAnnotations.map { it.asSingleFqName() })
    }

  private val dependencyGraphs by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(dependencyGraphPredicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toSet()
  }

  private val contributingTypesPredicate =
    LookupPredicate.create {
      annotated(latticeClassIds.allContributesAnnotations.map { it.asSingleFqName() })
    }

  // TODO can we remove this and just use generatedScopesToContributions? Seems like they're always
  // generated first
  private val inCompilationScopesToContributions:
    FirCache<Unit, Map<ClassId, Set<ClassId>>, TypeResolveService> =
    session.firCachesFactory.createCache { _, typeResolver ->
      val scopesToContributingClass = mutableMapOf<ClassId, MutableSet<ClassId>>()
      session.predicateBasedProvider
        .getSymbolsByPredicate(contributingTypesPredicate)
        .filterIsInstance<FirRegularClassSymbol>()
        .forEach { clazz ->
          clazz.annotations
            .annotationsIn(session, session.latticeClassIds.allContributesAnnotations)
            .mapNotNull { it.resolvedScopeClass(typeResolver) }
            .forEach { scopeClass ->
              scopesToContributingClass
                .getOrPut(scopeClass.classId!!, ::mutableSetOf)
                .add(clazz.classId)
            }
        }
      scopesToContributingClass
    }

  // TODO this includes classes from the current compilation unit
  private val generatedScopesToContributions:
    FirCache<FqName, Map<ClassId, Set<ClassId>>, TypeResolveService> =
    session.firCachesFactory.createCache { hintsPackage, typeResolver ->
      val classesInPackage =
        session.symbolProvider.symbolNamesProvider
          .getTopLevelClassifierNamesInPackage(hintsPackage)
          .orEmpty()
          .mapNotNull { name ->
            session.symbolProvider.getClassLikeSymbolByClassId(ClassId(hintsPackage, name))
          }

      buildMap<ClassId, MutableSet<ClassId>> {
        for (contribution in classesInPackage) {
          val origin =
            contribution.getAnnotationByClassId(LatticeSymbols.ClassIds.latticeOrigin, session)!!

          val originClass =
            origin
              .resolvedClassArgumentTarget(LatticeSymbols.Names.value, 0, typeResolver)
              ?.toRegularClassSymbol(session)!!

          originClass.annotations
            .annotationsIn(session, session.latticeClassIds.allContributesAnnotations)
            .mapNotNull { it.resolvedScopeClass(typeResolver)?.classId }
            .distinct()
            .forEach { scopeClassId ->
              getOrPut(scopeClassId, ::mutableSetOf).add(contribution.classId)
            }
        }
      }
    }

  private fun FirAnnotationContainer.graphAnnotation(): FirAnnotation? {
    return annotations
      .annotationsIn(session, latticeClassIds.dependencyGraphAnnotations)
      .firstOrNull()
  }

  private val FirPropertyAccessExpression.qualifierName: Name?
    get() = (calleeReference as? FirSimpleNamedReference)?.name

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    if (declaration.symbol !in dependencyGraphs) {
      return false
    }
    val graphAnnotation = declaration.graphAnnotation()
    if (graphAnnotation == null) {
      return false
    }

    // TODO in an FIR checker, disallow omitting scope but defining additional scopes
    // Can't check the scope class ID here but we'll check in computeAdditionalSupertypes
    return graphAnnotation.scopeArgument() != null
  }

  @OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    // TODO compute additional scopes here too
    val graphAnnotation = classLikeDeclaration.graphAnnotation()!!

    val scopes =
      buildSet {
          graphAnnotation.resolvedScopeClass(typeResolver)?.let { add(it) }
          // TODO additionalScopes
        }
        .filterNotTo(mutableSetOf()) { it.classId == StandardClassIds.Nothing }

    val contributions =
      scopes.flatMap { scope ->
        // TODO these may include some from this compilation unit. Maybe we don't need to look
        //  separately?
        val scopeClassId = scope.classId
        val classPathContributions =
          generatedScopesToContributions
            .getValue(LatticeSymbols.FqNames.latticeHintsPackage, typeResolver)[scopeClassId]
            .orEmpty()

        val inCompilationContributions =
          inCompilationScopesToContributions.getValue(Unit, typeResolver)[scopeClassId].orEmpty()

        (inCompilationContributions + classPathContributions).map {
          it.constructClassLikeType(emptyArray())
        }
      }

    return contributions
  }

  private fun FirAnnotation.scopeArgument() = classArgument("scope".asName(), index = 0)

  private fun FirAnnotation.resolvedScopeClass(typeResolver: TypeResolveService) =
    resolvedClassArgumentTarget("scope".asName(), index = 0, typeResolver)

  private fun FirAnnotation.resolvedClassArgumentTarget(
    name: Name,
    index: Int,
    typeResolver: TypeResolveService,
  ): ConeKotlinType? {
    // TODO if the annotation is resolved we can skip ahead
    val classArgument = argumentAsOrNull<FirGetClassCall>(name, index) ?: return null

    if (classArgument.isResolved) {
      return (classArgument.argument as FirClassReferenceExpression).classTypeRef.coneTypeOrNull
    }

    val typeToResolve =
      buildUserTypeFromQualifierParts(isMarkedNullable = false) {
        fun visitQualifiers(expression: FirExpression) {
          if (expression !is FirPropertyAccessExpression) return
          expression.explicitReceiver?.let { visitQualifiers(it) }
          expression.qualifierName?.let { part(it) }
        }
        visitQualifiers(classArgument.argument)
      }

    val resolvedArgument = typeResolver.resolveUserType(typeToResolve).coneType
    return resolvedArgument
  }

  private fun FirAnnotation.classArgument(name: Name, index: Int) =
    argumentAsOrNull<FirGetClassCall>(name, index)

  private fun <T> FirAnnotation.argumentAsOrNull(name: Name, index: Int): T? {
    findArgumentByName(name)?.let {
      @Suppress("UNCHECKED_CAST")
      return it as T
    }
    if (this !is FirAnnotationCall) return null
    // Fall back to the index if necessary
    @Suppress("UNCHECKED_CAST")
    return arguments.getOrNull(index) as T?
  }
}
