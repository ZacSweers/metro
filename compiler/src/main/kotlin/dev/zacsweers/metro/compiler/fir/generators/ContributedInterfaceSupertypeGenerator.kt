// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.resolvedAdditionalScopesClassIds
import dev.zacsweers.metro.compiler.fir.resolvedExcludedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeArgument
import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds

// Toe-hold for contributed types
internal class ContributedInterfaceSupertypeGenerator(
  session: FirSession,
  private val classIds: ClassIds,
) : FirSupertypeGenerationExtension(session) {

  class Factory(private val classIds: ClassIds) : FirSupertypeGenerationExtension.Factory {
    override fun create(session: FirSession) =
      ContributedInterfaceSupertypeGenerator(session, classIds)
  }

  private val dependencyGraphPredicate =
    LookupPredicate.create {
      annotated(classIds.dependencyGraphAnnotations.map { it.asSingleFqName() })
    }

  private val dependencyGraphs by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(dependencyGraphPredicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toSet()
  }

  private val contributingTypesPredicate =
    LookupPredicate.create {
      annotated(classIds.allContributesAnnotations.map { it.asSingleFqName() })
    }

  private val inCompilationScopesToContributions:
    FirCache<FirSession, Map<ClassId, Set<ClassId>>, TypeResolveService> =
    session.firCachesFactory.createCache { session, typeResolver ->
      val scopesToContributingClass = mutableMapOf<ClassId, MutableSet<ClassId>>()
      // In a KMP compilation we want to capture _all_ sessions' symbols. For example, if we are
      // generating supertypes for a graph in jvmMain, we want to capture contributions declared in
      // commonMain.
      val allSessions =
        sequenceOf(session).plus(session.moduleData.allDependsOnDependencies.map { it.session })
      allSessions
        .flatMap { it.predicateBasedProvider.getSymbolsByPredicate(contributingTypesPredicate) }
        .filterIsInstance<FirRegularClassSymbol>()
        .forEach { clazz ->
          clazz.annotations
            .annotationsIn(session, session.classIds.allContributesAnnotations)
            .mapNotNull { it.resolvedScopeClassId(typeResolver) }
            .forEach { scopeClassId ->
              scopesToContributingClass
                .getOrPut(scopeClassId, ::mutableSetOf)
                .add(clazz.classId.createNestedClassId(Symbols.Names.metroContribution))
            }
        }
      scopesToContributingClass
    }

  private val generatedScopesToContributions:
    FirCache<FqName, Map<ClassId, Set<ClassId>>, TypeResolveService> =
    session.firCachesFactory.createCache { hintsPackage, typeResolver ->
      val functionsInPackage =
        session.symbolProvider.symbolNamesProvider
          .getTopLevelCallableNamesInPackage(hintsPackage)
          .orEmpty()
          .flatMap { name -> session.symbolProvider.getTopLevelFunctionSymbols(hintsPackage, name) }

      buildMap<ClassId, MutableSet<ClassId>> {
        for (contribution in functionsInPackage) {
          val originClass =
            contribution.resolvedReturnType.toRegularClassSymbol(session) ?: continue

          originClass.annotations
            .annotationsIn(session, session.classIds.allContributesAnnotations)
            .mapNotNull { it.resolvedScopeClassId(typeResolver) }
            .distinct()
            .forEach { scopeClassId ->
              val metroContribution =
                originClass.classId.createNestedClassId(Symbols.Names.metroContribution)
              getOrPut(scopeClassId, ::mutableSetOf).add(metroContribution)
            }
        }
      }
    }

  private fun FirAnnotationContainer.graphAnnotation(): FirAnnotation? {
    return annotations.annotationsIn(session, classIds.dependencyGraphAnnotations).firstOrNull()
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    if (declaration.symbol !in dependencyGraphs) {
      return false
    }
    val graphAnnotation = declaration.graphAnnotation() ?: return false

    // TODO in an FIR checker, disallow omitting scope but defining additional scopes
    // Can't check the scope class ID here but we'll check in computeAdditionalSupertypes
    return graphAnnotation.scopeArgument() != null
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val graphAnnotation = classLikeDeclaration.graphAnnotation()!!

    val scopes =
      buildSet {
          graphAnnotation.resolvedScopeClassId(typeResolver)?.let(::add)
          graphAnnotation.resolvedAdditionalScopesClassIds(typeResolver).let(::addAll)
        }
        .filterNotTo(mutableSetOf()) { it == StandardClassIds.Nothing }

    val excluded = graphAnnotation.resolvedExcludedClassIds(typeResolver)
    val matchedExclusions = mutableSetOf<ClassId>()

    val contributions =
      scopes
        .flatMap { scopeClassId ->
          val classPathContributions =
            generatedScopesToContributions
              .getValue(Symbols.FqNames.metroHintsPackage, typeResolver)[scopeClassId]
              .orEmpty()

          val inCompilationContributions =
            inCompilationScopesToContributions
              .getValue(session, typeResolver)[scopeClassId]
              .orEmpty()

          (inCompilationContributions + classPathContributions).map {
            it.constructClassLikeType(emptyArray())
          }
        }
        .filterNot {
          // This is always the $$MetroContribution, the contribution is its parent
          val contributionId = it.classId?.parentClassId
          val isExcluded = contributionId in excluded
          if (isExcluded) {
            matchedExclusions += contributionId!!
          }
          isExcluded
        }

    if (excluded.isNotEmpty() && matchedExclusions.size != excluded.size) {
      // TODO how can we report an error more gracefully here?
      throw CompilationException(
        "Some excluded types were not matched. These can be removed from ${classLikeDeclaration.classId.asFqNameString()}: ${excluded - matchedExclusions}",
        null,
        classLikeDeclaration.psi,
      )
    }

    return contributions
  }
}
