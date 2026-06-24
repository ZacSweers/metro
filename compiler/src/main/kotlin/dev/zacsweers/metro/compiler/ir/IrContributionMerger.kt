// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.coneTypeIfResolved
import dev.zacsweers.metro.compiler.fir.replacesArgument
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.graph.computeMergePlan
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
import org.jetbrains.kotlin.ir.util.defaultType
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
  private val scopeContributionsCache =
    ConcurrentHashMap<ScopedContributionsCacheKey, ScopedContributions>()

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
    val externalSupertypes: Map<ClassId, IrClass>,
    val originToContributions: Map<ClassId, Set<ClassId>>,
  )

  private data class ScopedContributionsCacheKey(val allScopes: Set<ClassId>, val caller: ClassId?)

  private data class ContributionsCacheKey(
    val primaryScope: ClassId,
    val allScopes: Set<ClassId>,
    val excluded: Set<ClassId>,
    val caller: ClassId?,
  )

  private fun callerCacheKey(callingDeclaration: IrDeclaration): ClassId? =
    (callingDeclaration as? IrClass)?.classId

  context(traceScope: TraceScope)
  fun computeContributions(
    graphLikeAnnotation: IrConstructorCall,
    callingDeclaration: IrDeclaration,
    parentExclusionDeclaration: IrDeclaration = callingDeclaration,
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
        excluded + parentExcluded(parentExclusionDeclaration),
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
      val callerKey = callerCacheKey(callingDeclaration)
      val cacheKey = ContributionsCacheKey(primaryScope, allScopes, excluded, callerKey)
      mergedContributionsCache[cacheKey]?.let {
        return@trace it
      }

      // Layer 1: Get or compute scoped contributions (before exclusions/replacements)
      val scopedContributions =
        scopeContributionsCache.getOrPut(ScopedContributionsCacheKey(allScopes, callerKey)) {
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
                .associateBy { it.classIdOrFail }

            // External supertype contributions from IR extensions (e.g., Hilt entry points). These
            // are top-level types added directly as graph supertypes, bypassing the
            // MetroContribution-marker parent-promotion path.
            val externalSupertypes =
              allScopes
                .flatMap { contributionData.getExtensionSupertypes(it, callingDeclaration) }
                .associateBy { it.classIdOrFail }

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

            // Also check direct supertype contributions. In IR-only mode, @ContributesTo
            // interfaces can be added both as direct graph supertypes and as binding containers.
            // Replacements/exclusions need to prune both views of the same source interface.
            for ((externalClassId, externalClass) in externalSupertypes) {
              externalClass.originClassId()?.let { originClassId ->
                originToContributions.getAndAdd(originClassId, externalClassId)
              }
            }

            ScopedContributions(
              allContributions,
              bindingContainers,
              externalSupertypes,
              originToContributions,
            )
          }
        }

      val originToContributions = scopedContributions.originToContributions

      // Start with copies of the contributions maps (we'll modify these)
      val mutableAllContributions = scopedContributions.allContributions.toMutableMap()
      val mutableContributedBindingContainers = scopedContributions.bindingContainers.toMutableMap()
      val mutableExternalSupertypes = scopedContributions.externalSupertypes.toMutableMap()

      // Excludes and replaces are computed by the shared merge engine: excluded classes are
      // removed first so they don't get their `replaces` honored, then survivors' replacements are
      // applied. The IR-specific shape (three contribution maps, @Origin indirection, nested
      // `MetroContribution` markers) is supplied to the engine via the navigators below.
      val presentIds = buildSet {
        addAll(mutableAllContributions.keys)
        addAll(mutableContributedBindingContainers.keys)
        addAll(mutableExternalSupertypes.keys)
      }

      fun scanReplaces(irClass: IrClass): Set<ClassId> =
        irClass
          .repeatableAnnotationsIn(
            metroSymbols.classIds.allContributesAnnotationsWithContainers,
            irBody = { annotations ->
              annotations
                .flatMap { annotation -> annotation.replacedClasses() }
                .mapNotNull { replacedClass -> replacedClass.classType.rawType().classId }
            },
            firBody = { session, annotations ->
              annotations
                .flatMap { it.replacesArgument(session)?.argumentList?.arguments.orEmpty() }
                .mapNotNull { it.expectAsOrNull<FirGetClassCall>()?.coneTypeIfResolved()?.classId }
            },
          )
          .toSet()

      // The classes that declare a contribution id's `replaces`: the contribution's parent class,
      // or a binding container plus its @Origin class (contribution providers).
      fun replacesForId(id: ClassId): Set<ClassId> = buildSet {
        mutableAllContributions[id]?.firstOrNull()?.rawTypeOrNull()?.parentAsClass?.let {
          addAll(scanReplaces(it))
        }
        mutableContributedBindingContainers[id]?.let { container ->
          addAll(scanReplaces(container))
          val originClassId = container.originClassId()
          if (originClassId != null) {
            container.lookupClass(originClassId)?.owner?.let { addAll(scanReplaces(it)) }
          }
        }
      }

      val mergePlan =
        trace("Process exclusions and replacements") {
          computeMergePlan(
            presentIds = presentIds,
            excluded = excluded,
            originToIds = originToContributions,
            nestedChildrenOf = { target ->
              presentIds.filterTo(mutableSetOf()) { it.parentClassId == target }
            },
            replacesOf = ::replacesForId,
          )
        }

      for (removedId in mergePlan.removed) {
        mutableAllContributions.remove(removedId)
        mutableContributedBindingContainers.remove(removedId)
        mutableExternalSupertypes.remove(removedId)
      }

      if (mergePlan.unmatchedExclusions.isNotEmpty()) {
        writeDiagnostic(
          "merging-unmatched-exclusions-ir",
          { "${primaryScope.safePathString}.txt" },
        ) {
          mergePlan.unmatchedExclusions.map { it.safePathString }.sorted().joinToString("\n")
        }
      }
      if (mergePlan.unmatchedReplacements.isNotEmpty()) {
        writeDiagnostic(
          "merging-unmatched-replacements-ir",
          { "${primaryScope.safePathString}.txt" },
        ) {
          mergePlan.unmatchedReplacements.map { it.safePathString }.sorted().joinToString("\n")
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
            val removedExternalSupertype = mutableExternalSupertypes.remove(replacedClassId)

            // Also remove contributions that have @Origin pointing to the replaced class
            val originContributions = originToContributions[replacedClassId]
            originContributions?.forEach { contributionId ->
              mutableAllContributions.remove(contributionId)
              mutableContributedBindingContainers.remove(contributionId)
              mutableExternalSupertypes.remove(contributionId)
            }

            val removedDirectContribution =
              removedContribution != null ||
                removedContainer != null ||
                removedExternalSupertype != null
            val removedOriginContribution = originContributions != null
            val wasNotMatched = !removedDirectContribution && !removedOriginContribution
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

      // Build and cache the result. Combine native markers and external supertypes into a single
      // sorted set. External supertypes are top-level types (no MetroContribution marker shape),
      // but the downstream supertype-chunking flow handles them uniformly via the safe cast in
      // computePromotedParents.
      val combinedSupertypes = mutableSetOf<IrType>()
      mutableAllContributions.values.forEach(combinedSupertypes::addAll)
      mutableExternalSupertypes.values.forEach { irClass ->
        combinedSupertypes.add(irClass.defaultType)
      }
      val result =
        IrContributions(
          primaryScope,
          allScopes,
          combinedSupertypes.toSortedSet(compareBy { it.rawType().classIdOrFail.toString() }),
          mutableContributedBindingContainers.toSortedMap(compareBy { it.toString() }),
        )

      mergedContributionsCache[cacheKey] = result
      return@trace result
    }
}

internal data class IrContributions(
  val primaryScope: ClassId?,
  val allScopes: Set<ClassId>,
  /**
   * All supertype contributions for the graph, sorted deterministically. Includes native nested
   * `MetroContribution` markers as well as raw top-level external supertypes from
   * [MetroIrContributionExtension]s (e.g., Hilt entry points). Downstream code distinguishes them
   * via the safe cast in [computePromotedParents] (top-level externals have no `IrClass` parent to
   * promote).
   */
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
      // Skip top-level types (e.g., external supertype contributions whose parent is an IrFile).
      // Only nested MetroContribution markers have a class parent to promote.
      val parentClass = marker.rawType().parent as? IrClass ?: continue
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
