// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.rankValue
import dev.zacsweers.metro.compiler.fir.resolvedAdditionalScopesClassIds
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedExcludedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedReplacedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.singleOrError
import java.util.TreeMap
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.ResolveStateAccess
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.lookupTracker
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.recordFqNameLookup
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.getSingleClassifier
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

// Toe-hold for contributed types
@OptIn(SymbolInternals::class, ResolveStateAccess::class)
internal class ContributedInterfaceSupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session) {

  private val dependencyGraphs by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(session.predicates.dependencyGraphPredicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toSet()
  }

  private val inCompilationScopesToContributions:
    FirCache<ClassId, Set<ClassId>, TypeResolveService> =
    session.firCachesFactory.createCache { scopeClassId, typeResolver ->
      // In a KMP compilation we want to capture _all_ sessions' symbols. For example, if we are
      // generating supertypes for a graph in jvmMain, we want to capture contributions declared in
      // commonMain.
      val allSessions =
        sequenceOf(session).plus(session.moduleData.allDependsOnDependencies.map { it.session })

      // Predicates can't see the generated $$MetroContribution classes, but we can access them
      // by first querying the top level @ContributeX-annotated source symbols and then checking
      // their declaration scopes
      val contributingClasses =
        allSessions
          .flatMap {
            it.predicateBasedProvider.getSymbolsByPredicate(
              session.predicates.contributesAnnotationPredicate
            )
          }
          .filterIsInstance<FirRegularClassSymbol>()
          .toList()

      getScopedContributions(contributingClasses, typeResolver)[scopeClassId].orEmpty()
    }

  private val generatedScopesToContributions:
    FirCache<ClassId, Map<ClassId, Set<ClassId>>, TypeResolveService> =
    session.firCachesFactory.createCache { scopeClassId, typeResolver ->
      val scopeHintFqName = Symbols.FqNames.scopeHint(scopeClassId)
      val functionsInPackage =
        session.symbolProvider.getTopLevelFunctionSymbols(
          scopeHintFqName.parent(),
          scopeHintFqName.shortName(),
        )

      val contributingClasses =
        functionsInPackage.mapNotNull { contribution ->
          // This is the single value param
          contribution.valueParameterSymbols
            .single()
            .resolvedReturnType
            .toRegularClassSymbol(session)
        }

      getScopedContributions(contributingClasses, typeResolver)
    }

  /**
   * @param contributingClasses The classes annotated with some number of @ContributesX annotations.
   * @return A mapping of scope ids to @MetroContribution-annotated nested classes.
   */
  private fun getScopedContributions(
    contributingClasses: List<FirRegularClassSymbol>,
    typeResolver: TypeResolveService,
  ): Map<ClassId, Set<ClassId>> {
    val scopesToNestedContributions = mutableMapOf<ClassId, MutableSet<ClassId>>()

    contributingClasses.forEach { originClass ->
      val classDeclarationContainer =
        originClass.fir.symbol.declaredMemberScope(session, memberRequiredPhase = null)

      val contributionNames =
        classDeclarationContainer.getClassifierNames().filter {
          it.identifier.startsWith(Symbols.Names.metroContribution.identifier)
        }

      contributionNames
        .mapNotNull { nestedClassName ->
          val nestedClass = classDeclarationContainer.getSingleClassifier(nestedClassName)

          nestedClass
            ?.annotations
            ?.annotationsIn(session, setOf(Symbols.ClassIds.metroContribution))
            ?.single()
            ?.resolvedScopeClassId(typeResolver)
            ?.let { scopeId -> scopeId to originClass.classId.createNestedClassId(nestedClassName) }
        }
        .forEach { (scopeClassId, nestedContributionId) ->
          scopesToNestedContributions
            .getOrPut(scopeClassId, ::mutableSetOf)
            .add(nestedContributionId)
        }
    }

    return scopesToNestedContributions
  }

  private fun FirAnnotationContainer.graphAnnotation(): FirAnnotation? {
    return annotations
      .annotationsIn(session, session.classIds.dependencyGraphAnnotations)
      .firstOrNull()
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

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    with(session.predicates) {
      register(dependencyGraphPredicate, contributesAnnotationPredicate, qualifiersPredicate)
    }
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

    for (classId in scopes) {
      session.lookupTracker?.recordFqNameLookup(
        Symbols.FqNames.scopeHint(classId),
        classLikeDeclaration.source,
        // The class source is the closest we can get to the file source,
        // and the file path lookup is cached internally.
        classLikeDeclaration.source,
      )
    }

    val contributions =
      scopes
        .flatMap { scopeClassId ->
          val classPathContributions =
            generatedScopesToContributions
              .getValue(scopeClassId, typeResolver)[scopeClassId]
              .orEmpty()

          val inCompilationContributions =
            inCompilationScopesToContributions.getValue(scopeClassId, typeResolver)

          (inCompilationContributions + classPathContributions).map {
            it.constructClassLikeType(emptyArray())
          }
        }
        .let {
          // Stable sort
          TreeMap<ClassId, ConeKotlinType>(compareBy(ClassId::asString)).apply {
            for (contribution in it) {
              // This is always the $$MetroContribution, the contribution is its parent
              val classId = contribution.classId?.parentClassId ?: continue
              put(classId, contribution)
            }
          }
        }

    val excluded = graphAnnotation.resolvedExcludedClassIds(typeResolver)
    if (contributions.isEmpty() && excluded.isEmpty()) {
      return emptyList()
    }

    val unmatchedExclusions = mutableSetOf<ClassId>()

    for (excludedClassId in excluded) {
      val removed = contributions.remove(excludedClassId)
      if (removed == null) {
        unmatchedExclusions += excludedClassId
      }
    }

    if (unmatchedExclusions.isNotEmpty()) {
      // TODO warn?
    }

    // Process replacements
    val unmatchedReplacements = mutableSetOf<ClassId>()
    contributions.values
      .filterIsInstance<ConeClassLikeType>()
      .mapNotNull { it.toClassSymbol(session)?.getContainingClassSymbol() }
      .flatMap { contributingType ->
        contributingType.annotations
          .annotationsIn(session, session.classIds.allContributesAnnotations)
          .flatMap { annotation -> annotation.resolvedReplacedClassIds(typeResolver) }
      }
      .distinct()
      .forEach { replacedClassId ->
        val removed = contributions.remove(replacedClassId)
        if (removed != null) {
          unmatchedReplacements += replacedClassId
        }
      }

    if (unmatchedReplacements.isNotEmpty()) {
      // TODO warn?
    }

    if (session.metroFirBuiltIns.options.enableDaggerAnvilInterop) {
      val unmatchedRankReplacements = mutableSetOf<ClassId>()
      val pendingRankReplacements = processRankBasedReplacements(contributions, typeResolver)

      pendingRankReplacements.distinct().forEach { replacedClassId ->
        val removed = contributions.remove(replacedClassId)
        if (removed != null) {
          unmatchedRankReplacements += replacedClassId
        }
      }

      if (unmatchedRankReplacements.isNotEmpty()) {
        // TODO we could report all rank based replacements here
      }
    }

    return contributions.values.toList()
  }

  /**
   * This provides ContributesBinding.rank interop for users migrating from Dagger-Anvil to make the
   * migration to Metro more feasible.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  private fun processRankBasedReplacements(
    contributions: TreeMap<ClassId, ConeKotlinType>,
    typeResolver: TypeResolveService,
  ): Set<ClassId> {
    val pendingRankReplacements = mutableSetOf<ClassId>()

    val rankedBindings =
      contributions.values
        .filterIsInstance<ConeClassLikeType>()
        .mapNotNull { it.toClassSymbol(session)?.getContainingClassSymbol() }
        .flatMap { contributingType ->
          contributingType.annotations
            .annotationsIn(session, session.classIds.contributesBindingAnnotations)
            .map { annotation ->
              val boundType =
                annotation.resolvedBindingArgument(session, typeResolver)?.let { explicitBinding ->
                  // @ContributesBinding.binding may not be resolved yet, while an interop
                  // 'boundType' should be okay already
                  if (explicitBinding is FirUserTypeRef) {
                      typeResolver.resolveUserType(explicitBinding)
                    } else {
                      explicitBinding
                    }
                    .coneType
                } ?: contributingType.implicitBoundType(typeResolver)

              ContributedBinding(
                contributingType,
                FirTypeKey(boundType, contributingType.annotations.qualifierAnnotation(session)),
                annotation.rankValue(),
              )
            }
        }
    val bindingGroups =
      rankedBindings
        .groupBy { binding -> binding.typeKey }
        .filter { bindingGroup -> bindingGroup.value.size > 1 }

    for (bindingGroup in bindingGroups.values) {
      val topBindings =
        bindingGroup
          .groupBy { binding -> binding.rank }
          .toSortedMap()
          .let { it.getValue(it.lastKey()) }

      // These are the bindings that were outranked and should not be processed further
      bindingGroup.minus(topBindings).forEach {
        pendingRankReplacements += it.contributingType.classId
      }
    }

    return pendingRankReplacements
  }

  private fun FirClassLikeSymbol<*>.implicitBoundType(
    typeResolver: TypeResolveService
  ): ConeKotlinType {
    return if (fir.resolveState.resolvePhase == FirResolvePhase.RAW_FIR) {
        // When processing bindings in the same module or compilation, we need to handle supertypes
        // that have not been resolved yet
        (this as FirClassSymbol<*>).fir.superTypeRefs.map {
          typeResolver.resolveUserType(it as FirUserTypeRef).coneType
        }
      } else {
        (this as FirClassSymbol<*>).resolvedSuperTypes
      }
      .singleOrError {
        val superTypeFqNames = map { it.classId?.asSingleFqName() }.joinToString()
        "${classId.asSingleFqName()} has a ranked binding with no explicit bound type and $size supertypes ($superTypeFqNames). There must be exactly one supertype or an explicit bound type."
      }
  }

  private data class ContributedBinding(
    val contributingType: FirClassLikeSymbol<*>,
    val typeKey: FirTypeKey,
    val rank: Long,
  )
}
