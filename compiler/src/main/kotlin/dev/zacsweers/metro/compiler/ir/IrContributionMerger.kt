// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.coneTypeIfResolved
import dev.zacsweers.metro.compiler.fir.replacesArgument
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.safePathString
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.SortedMap
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

@Inject
@SingleIn(IrScope::class)
internal class IrContributionMerger(
  metroContext: IrMetroContext,
  private val contributionData: IrContributionData,
  boundTypeResolver: IrBoundTypeResolver,
) : IrMetroContext by metroContext {

  // Cache for scope-based contributions (before exclusions/replacements).
  // Thread-safe for concurrent access during parallel graph validation.
  private val scopeContributionsCache = ConcurrentHashMap<Set<ClassId>, ScopedContributions>()

  // Cache for fully processed contributions (after exclusions/replacements).
  // Thread-safe for concurrent access during parallel graph validation.
  private val mergedContributionsCache = ConcurrentHashMap<ContributionsCacheKey, IrContributions>()

  // Cache for parent exclusions by starting class - avoids recomputing hierarchy walks.
  // Thread-safe for concurrent access during parallel graph validation.
  private val parentExcludedCache = ConcurrentHashMap<ClassId, Set<ClassId>>()

  private val rankedBindingProcessing = IrRankedBindingProcessing(boundTypeResolver)

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

  context(traceScope: TraceScope)
  fun computeContributions(
    graphLikeAnnotation: IrConstructorCall,
    callingDeclaration: IrDeclaration,
  ): IrContributions? {
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
      return computeContributions(
        scope,
        allScopes,
        excluded + parentExcluded(callingDeclaration),
        callingDeclaration,
      )
    } else {
      return null
    }
  }

  /**
   * Computes and caches parent exclusions incrementally. Each cache entry contains the cumulative
   * exclusions for that class and all its parents, so lookups at any level in the hierarchy are
   * O(1) after the first computation.
   */
  private fun parentExcluded(callingDeclaration: IrDeclaration): Set<ClassId> {
    val startingClass = callingDeclaration as? IrClass ?: return emptySet()
    return parentExcludedForClass(startingClass)
  }

  private fun parentExcludedForClass(irClass: IrClass): Set<ClassId> {
    val classId = irClass.classId ?: return emptySet()

    // Check cache first - this includes all exclusions from this level and above
    parentExcludedCache[classId]?.let {
      return it
    }

    // Compute this level's own exclusions
    val thisLevelExclusions =
      irClass.sourceGraphIfMetroGraph
        .annotationsIn(metroSymbols.classIds.graphLikeAnnotations)
        .firstOrNull()
        ?.excludedClasses()
        ?.mapToClassIds()
        .orEmpty()

    // Get parent's cumulative exclusions (recursively, which also caches intermediate levels)
    val parentExclusions =
      if (irClass.origin == Origins.GeneratedGraphExtension) {
        parentExcludedForClass(irClass.parentAsClass)
      } else {
        emptySet()
      }

    // Combine and cache the cumulative result
    val totalExclusions =
      if (parentExclusions.isEmpty()) {
        thisLevelExclusions
      } else if (thisLevelExclusions.isEmpty()) {
        parentExclusions
      } else {
        thisLevelExclusions + parentExclusions
      }

    parentExcludedCache[classId] = totalExclusions
    return totalExclusions
  }

  context(traceScope: TraceScope)
  fun computeContributions(
    primaryScope: ClassId,
    allScopes: Set<ClassId>,
    excluded: Set<ClassId>,
    callingDeclaration: IrDeclaration,
  ): IrContributions? =
    trace("Compute contributions") {
      if (allScopes.isEmpty()) return@trace null

      // Track scope hint lookups before checking caches to ensure all callers
      // register their IC dependency, even when hitting a cached result
      for (scope in allScopes) {
        contributionData.trackScopeHintLookup(scope, callingDeclaration)
      }

      // Layer 2: Check if we have a fully processed result cached
      val cacheKey = ContributionsCacheKey(primaryScope, allScopes, excluded)
      mergedContributionsCache[cacheKey]?.let {
        return@trace it
      }

      // Layer 1: Get or compute scoped contributions (before exclusions/replacements)
      val scopedContributions =
        scopeContributionsCache.getOrPut(allScopes) {
          trace("Compute contributions for $allScopes") {
            // Get all contributions and binding containers
            val allContributions =
              allScopes
                .flatMap { contributionData.getContributions(it, callingDeclaration) }
                .groupByTo(mutableMapOf()) {
                  // For Metro contributions, we need to check the parent class ID
                  // This is always the `MetroContribution`, the contribution's parent is the actual
                  // class
                  it.rawType().classIdOrFail.parentClassId!!
                }

            val bindingContainers =
              allScopes
                .flatMap {
                  contributionData.getBindingContainerContributions(it, callingDeclaration)
                }
                .associateByTo(mutableMapOf()) { it.classIdOrFail }

            // Build a cache of origin class -> contribution classes mappings upfront
            // This maps from an origin class to all contributions that have an @Origin pointing to
            // it
            val originToContributions = mutableMapOf<ClassId, MutableSet<ClassId>>()

            // Check regular contributions (with nested `MetroContribution` classes)
            for ((contributionClassId, contributions) in allContributions) {
              // Get the actual contribution class (nested `MetroContribution`)
              val contributionClass = contributions.firstOrNull()?.rawTypeOrNull()
              if (contributionClass != null) {
                // @Origin is usually present on the contribution container (the parent class).
                // MetroContribution nested classes generated from those containers don't always
                // carry that annotation, so fall back to the parent to preserve replacement
                // behavior in IR-only graph-extension merging.
                val originClassId =
                  contributionClass.originClassId()
                    ?: contributionClass.parentAsClass.originClassId()
                originClassId?.let {
                  originToContributions.getAndAdd(originClassId, contributionClassId)
                }
              }
            }

            // Also check binding containers (e.g., @ContributesTo classes)
            for ((containerClassId, containerClass) in bindingContainers) {
              containerClass.originClassId()?.let { originClassId ->
                originToContributions.getAndAdd(originClassId, containerClassId)
              }
            }

            ScopedContributions(allContributions, bindingContainers, originToContributions)
          }
        }

      val originToContributions = scopedContributions.originToContributions

      // Start with copies of the contributions maps (we'll modify these)
      val mutableAllContributions = scopedContributions.allContributions.toMutableMap()
      val mutableContributedBindingContainers = scopedContributions.bindingContainers.toMutableMap()

      // Process excludes FIRST - excluded classes should not have their `replaces` effect applied
      if (excluded.isNotEmpty()) {
        trace("Process exclusions") {
          val unmatchedExclusions = mutableSetOf<ClassId>()
          for (excludedClassId in excluded) {
            // Remove excluded binding containers - they won't contribute their bindings
            val removedContainer = mutableContributedBindingContainers.remove(excludedClassId)

            // Remove contributions from excluded classes that have nested `MetroContribution`
            // classes
            // (binding containers don't have these, so this only affects @ContributesBinding etc.)
            val removedContribution = mutableAllContributions.remove(excludedClassId)

            // Remove contributions that have @Origin annotation pointing to the excluded class
            val originContributions = originToContributions[excludedClassId]
            originContributions?.forEach { contributionId ->
              mutableAllContributions.remove(contributionId)
              mutableContributedBindingContainers.remove(contributionId)
            }

            // Track unmatched if nothing was removed
            if (
              removedContainer == null && removedContribution == null && originContributions == null
            ) {
              unmatchedExclusions += excludedClassId
            }
          }

          if (unmatchedExclusions.isNotEmpty()) {
            writeDiagnostic(
              "merging-unmatched-exclusions-ir",
              { "${primaryScope.safePathString}.txt" },
            ) {
              unmatchedExclusions.map { it.safePathString }.sorted().joinToString("\n")
            }
          }
        }
      }

      // Collect replacements AFTER exclusions - only from remaining (non-excluded) contributions
      // This ensures excluded classes don't have their `replaces` effect applied
      val classesToReplace = mutableSetOf<ClassId>()
      val allClassesToScan = sequence {
        // Parent classes of regular contributions (only remaining ones after exclusions)
        for (contributions in mutableAllContributions.values) {
          contributions.firstOrNull()?.rawTypeOrNull()?.parentAsClass?.let { yield(it) }
        }
        // Binding containers (only remaining ones after exclusions)
        yieldAll(mutableContributedBindingContainers.values)

        // For binding containers with @Origin (contribution providers), also scan the
        // origin class for @ContributesBinding(replaces=...) annotations
        for (container in mutableContributedBindingContainers.values) {
          val originClassId = container.originClassId() ?: continue
          val originClass = pluginContext.referenceClass(originClassId)?.owner ?: continue
          yield(originClass)
        }
      }

      trace("Process replacements") {
        for (irClass in allClassesToScan) {
          val replacedClasses =
            irClass.repeatableAnnotationsIn(
              metroSymbols.classIds.allContributesAnnotationsWithContainers,
              irBody = { annotations ->
                annotations
                  .flatMap { annotation -> annotation.replacedClasses() }
                  .mapNotNull { replacedClass -> replacedClass.classType.rawType().classId }
              },
              firBody = { session, annotations ->
                annotations
                  .flatMap { it.replacesArgument(session)?.argumentList?.arguments.orEmpty() }
                  .mapNotNull {
                    it.expectAsOrNull<FirGetClassCall>()?.coneTypeIfResolved()?.classId
                  }
              },
            )
          classesToReplace.addAll(replacedClasses)
        }

        // Process replacements
        if (classesToReplace.isNotEmpty()) {
          val unmatchedReplacements = mutableSetOf<ClassId>()

          for (replacedClassId in classesToReplace) {
            val removedContribution = mutableAllContributions.remove(replacedClassId)
            val removedContainer = mutableContributedBindingContainers.remove(replacedClassId)

            // Remove contributions that have @Origin annotation pointing to the replaced class
            val originContributions = originToContributions[replacedClassId]
            originContributions?.forEach { contributionId ->
              mutableAllContributions.remove(contributionId)
              mutableContributedBindingContainers.remove(contributionId)
            }

            // Track unmatched if nothing was removed
            if (
              removedContribution == null && removedContainer == null && originContributions == null
            ) {
              unmatchedReplacements += replacedClassId
            }
          }

          if (unmatchedReplacements.isNotEmpty()) {
            writeDiagnostic(
              "merging-unmatched-replacements-ir",
              { "${primaryScope.safePathString}.txt" },
            ) {
              unmatchedReplacements.map { it.safePathString }.sorted().joinToString("\n")
            }
          }
        }
      }

      // Process rank-based replacements if Dagger-Anvil interop is enabled
      if (options.enableDaggerAnvilInterop) {
        trace("Process ranked replacements") {
          val unmatchedRankReplacements = mutableSetOf<ClassId>()
          val rankReplacements =
            rankedBindingProcessing.processRankBasedReplacements(
              allScopes,
              mutableAllContributions,
              mutableContributedBindingContainers,
            )

          for (replacedClassId in rankReplacements) {
            val removedContribution = mutableAllContributions.remove(replacedClassId)
            val removedContainer = mutableContributedBindingContainers.remove(replacedClassId)

            // Also remove contributions that have @Origin pointing to the replaced class
            originToContributions[replacedClassId]?.forEach { contributionId ->
              mutableAllContributions.remove(contributionId)
              mutableContributedBindingContainers.remove(contributionId)
            }

            val wasNotMatched =
              removedContribution == null &&
                removedContainer == null &&
                originToContributions[replacedClassId] == null
            if (wasNotMatched) {
              unmatchedRankReplacements += replacedClassId
            }
          }

          if (unmatchedRankReplacements.isNotEmpty()) {
            writeDiagnostic(
              "merging-unmatched-rank-replacements-ir",
              { "${primaryScope.safePathString}.txt" },
            ) {
              unmatchedRankReplacements.map { it.safePathString }.sorted().joinToString("\n")
            }
          }
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
      return@trace result
    }
}

internal data class IrContributions(
  val primaryScope: ClassId?,
  val allScopes: Set<ClassId>,
  val supertypes: SortedSet<IrType>,
  // Deterministic sort
  val bindingContainers: SortedMap<ClassId, IrClass>,
)

/**
 * For each `MetroContribution` marker in [contributions], returns its parent contributing interface
 * if that parent is not already a direct supertype of [ownerGraph]. Each marker in
 * [IrContributions.supertypes] is already a `@MetroContribution(scope = X)` for an X in
 * `allScopes`, so the parent is unconditionally a legitimate contribution.
 */
internal fun computePromotedParents(
  contributions: IrContributions,
  ownerGraph: IrClass,
): Map<IrType, IrType> {
  val existing = ownerGraph.superTypes.mapNotNullTo(mutableSetOf()) { it.rawTypeOrNull()?.classId }
  return buildMap {
    for (marker in contributions.supertypes) {
      val parentClass = marker.rawType().parentAsClass
      val parentClassId = parentClass.classId ?: continue
      if (!existing.add(parentClassId)) continue
      put(marker, parentClass.symbol.defaultType)
    }
  }
}

/**
 * Groups [markers] into synthetic chunk interfaces nested under [ownerGraph]. Each chunk holds at
 * most `merged-supertype-chunk-size` markers, so the chunk count tracks the contribution count
 * rather than the raw supertype count. When [promotedParents] supplies a parent for a marker, the
 * parent goes into the same chunk as its marker.
 *
 * When the option is below 2 or the input fits in a single chunk, the input is returned flat
 * (markers and any promoted parents inline) without producing chunk classes.
 *
 * Chunks carry [Origins.ContributionSupertypeChunk] so downstream supertype walkers (notably
 * binding-alias creation) can identify and skip them.
 */
context(traceScope: TraceScope)
internal fun IrMetroContext.chunkSupertypesIfNeeded(
  markers: SortedSet<IrType>,
  ownerGraph: IrClass,
  promotedParents: Map<IrType, IrType> = emptyMap(),
): Collection<IrType> {
  val chunkSize = options.mergedSupertypeChunkSize
  if (chunkSize < 2 || markers.size <= chunkSize) {
    return markers.flatMap { listOfNotNull(it, promotedParents[it]) }
  }
  return trace("Chunk merged supertypes") {
    markers.chunked(chunkSize).mapIndexed { index, chunk ->
      val chunkClass =
        irFactory
          .buildClass {
            name = "ContributionChunk_$index".asName()
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            // Match the owner graph impl so the chunks aren't more or less visible than the
            // class that references them.
            visibility = ownerGraph.visibility
            origin = Origins.ContributionSupertypeChunk
          }
          .apply {
            createThisReceiverParameter()
            superTypes = chunk.flatMap { marker -> listOfNotNull(marker, promotedParents[marker]) }
            ownerGraph.addChild(this)
            addFakeOverrides(irTypeSystemContext)
          }
      chunkClass.symbol.defaultType
    }
  }
}
