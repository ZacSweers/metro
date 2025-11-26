// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.SortedMap
import java.util.SortedSet
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

internal class IrContributionMerger(
  metroContext: IrMetroContext,
  private val contributionData: IrContributionData,
) : IrMetroContext by metroContext {

  // Cache for scope-based contributions (before exclusions/replacements)
  private val scopeContributionsCache = mutableMapOf<Set<ClassId>, ScopedContributions>()

  // Cache for fully processed contributions (after exclusions/replacements)
  private val mergedContributionsCache = mutableMapOf<ContributionsCacheKey, IrContributions>()

  private data class ScopedContributions(
    val allContributions: Map<ClassId, List<IrType>>,
    val bindingContainers: Map<ClassId, IrClass>,
    val originToContributions: Map<ClassId, Set<ClassId>>,
  )

  private data class ContributionsCacheKey(
    val primaryScope: ClassId,
    val allScopes: Set<ClassId>,
    val excluded: Set<ClassId>,
  )

  fun computeContributions(graphLikeAnnotation: IrConstructorCall): IrContributions? {
    val sourceScope = graphLikeAnnotation.scopeClassOrNull()
    val scope = sourceScope?.classId

    if (scope != null) {
      val additionalScopes = graphLikeAnnotation.additionalScopes().mapToClassIds()

      val allScopes =
        if (additionalScopes.isEmpty()) {
          setOf(scope)
        } else {
          buildSet {
            add(scope)
            addAll(additionalScopes)
          }
        }

      val excluded = graphLikeAnnotation.excludedClasses().mapToClassIds()
      return computeContributions(scope, allScopes, excluded)
    } else {
      return null
    }
  }

  fun computeContributions(
    primaryScope: ClassId,
    allScopes: Set<ClassId>,
    excluded: Set<ClassId>,
  ): IrContributions? {
    if (allScopes.isEmpty()) return null

    // Layer 2: Check if we have a fully processed result cached
    val cacheKey = ContributionsCacheKey(primaryScope, allScopes, excluded)
    mergedContributionsCache[cacheKey]?.let {
      return it
    }

    // Layer 1: Get or compute scoped contributions (before exclusions/replacements)
    val scopedContributions =
      scopeContributionsCache.getOrPut(allScopes) {
        // Get all contributions and binding containers
        val allContributions =
          allScopes
            .flatMap { contributionData.getContributions(it) }
            .groupByTo(mutableMapOf()) {
              // For Metro contributions, we need to check the parent class ID
              // This is always the `MetroContribution`, the contribution's parent is the actual
              // class
              it.rawType().classIdOrFail.parentClassId!!
            }

        val bindingContainers =
          allScopes
            .flatMap { contributionData.getBindingContainerContributions(it) }
            .associateByTo(mutableMapOf()) { it.classIdOrFail }

        // Build a cache of origin class -> contribution classes mappings upfront
        // This maps from an origin class to all contributions that have an @Origin pointing to it
        val originToContributions = mutableMapOf<ClassId, MutableSet<ClassId>>()

        // Check regular contributions (with nested `MetroContribution` classes)
        for ((contributionClassId, contributions) in allContributions) {
          // Get the actual contribution class (nested `MetroContribution`)
          val contributionClass = contributions.firstOrNull()?.rawTypeOrNull()
          if (contributionClass != null) {
            contributionClass.originClassId()?.let { originClassId ->
              originToContributions
                .getOrPut(originClassId) { mutableSetOf() }
                .add(contributionClassId)
            }
          }
        }

        // Also check binding containers (e.g., @ContributesTo classes)
        for ((containerClassId, containerClass) in bindingContainers) {
          containerClass.originClassId()?.let { originClassId ->
            originToContributions.getOrPut(originClassId) { mutableSetOf() }.add(containerClassId)
          }
        }

        ScopedContributions(allContributions, bindingContainers, originToContributions)
      }

    // Make mutable copies for processing exclusions and replacements
    val mutableAllContributions = scopedContributions.allContributions.toMutableMap()
    val mutableContributedBindingContainers = scopedContributions.bindingContainers.toMutableMap()
    val originToContributions = scopedContributions.originToContributions

    // TODO do we exclude directly contributed ones or also include transitives?

    // Process excludes
    for (excludedClassId in excluded) {
      // Remove excluded binding containers - they won't contribute their bindings
      mutableContributedBindingContainers.remove(excludedClassId)

      // Remove contributions from excluded classes that have nested `MetroContribution` classes
      // (binding containers don't have these, so this only affects @ContributesBinding etc.)
      mutableAllContributions.remove(excludedClassId)

      // Remove contributions that have @Origin annotation pointing to the excluded class
      originToContributions[excludedClassId]?.forEach { contributionId ->
        mutableAllContributions.remove(contributionId)
        mutableContributedBindingContainers.remove(contributionId)
      }
    }

    // Process replacements from both regular contributions and binding containers

    // Read the original copies as we are modifying the mutable copy after this
    scopedContributions.allContributions.values
      .asSequence()
      // Add parent classes of regular contributions (e.g., @Contributes* classes)
      .mapNotNull { contributions -> contributions.firstOrNull()?.rawTypeOrNull()?.parentAsClass }
      // binding containers
      .plus(scopedContributions.bindingContainers.values)
      .flatMap { contributingClass ->
        contributingClass
          .annotationsIn(metroSymbols.classIds.allContributesAnnotations)
          .flatMap { annotation -> annotation.replacedClasses() }
          .mapNotNull { replacedClass -> replacedClass.classType.rawType().classId }
      }
      .forEach { replacedClassId ->
        mutableAllContributions.remove(replacedClassId)
        mutableContributedBindingContainers.remove(replacedClassId)

        // Remove contributions that have @Origin annotation pointing to the replaced class
        originToContributions[replacedClassId]?.forEach { contributionId ->
          mutableAllContributions.remove(contributionId)
          mutableContributedBindingContainers.remove(contributionId)
        }
      }

    // Process rank-based replacements if Dagger-Anvil interop is enabled
    if (options.enableDaggerAnvilInterop) {
      val rankReplacements = processRankBasedReplacements(allScopes, mutableAllContributions)
      for (replacedClassId in rankReplacements) {
        mutableAllContributions.remove(replacedClassId)
      }
    }

    // Build and cache the result
    val result =
      IrContributions(
        primaryScope,
        allScopes,
        mutableAllContributions.values
          .flatten()
          .toSortedSet(compareBy { it.rawType().classIdOrFail.toString() }),
        mutableContributedBindingContainers.toSortedMap(compareBy { it.toString() }),
      )

    mergedContributionsCache[cacheKey] = result
    return result
  }

  /**
   * This provides `ContributesBinding.rank` interop for users migrating from Dagger-Anvil to make
   * the migration to Metro more feasible.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  private fun processRankBasedReplacements(
    allScopes: Set<ClassId>,
    contributions: Map<ClassId, List<IrType>>,
  ): Set<ClassId> {
    val pendingRankReplacements = mutableSetOf<ClassId>()

    val rankedBindings =
      contributions.values
        .flatten()
        .map { it.rawType().parentAsClass }
        .distinctBy { it.classIdOrFail }
        .flatMap { contributingType ->
          contributingType
            .annotationsIn(metroSymbols.classIds.contributesBindingAnnotations)
            .mapNotNull { annotation ->
              val scope = annotation.scopeOrNull() ?: return@mapNotNull null
              if (scope !in allScopes) return@mapNotNull null

              val explicitBindingMissingMetadata =
                annotation.getValueArgument(Symbols.Names.binding)

              if (explicitBindingMissingMetadata != null) {
                // This is a case where an explicit binding is specified but we receive the argument
                // as FirAnnotationImpl without the metadata containing the type arguments so we
                // short-circuit since we lack the info to compare it against other bindings.
                null
              } else {
                val (explicitBindingType, ignoreQualifier) = annotation.bindingTypeOrNull()
                val boundType =
                  explicitBindingType
                    ?: contributingType.implicitBoundTypeOrNull()!! // Checked in FIR

                ContributedIrBinding(
                  contributingType = contributingType,
                  typeKey =
                    IrTypeKey(
                      boundType,
                      if (ignoreQualifier) null else contributingType.qualifierAnnotation(),
                    ),
                  rank = annotation.rankValue(),
                )
              }
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
        pendingRankReplacements += it.contributingType.classIdOrFail
      }
    }

    return pendingRankReplacements
  }
}

private data class ContributedIrBinding(
  val contributingType: IrClass,
  val typeKey: IrTypeKey,
  val rank: Long,
)

internal data class IrContributions(
  val primaryScope: ClassId?,
  val allScopes: Set<ClassId>,
  val supertypes: SortedSet<IrType>,
  // Deterministic sort
  val bindingContainers: SortedMap<ClassId, IrClass>,
)
