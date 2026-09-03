// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.computeOriginClassIdChain
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.replacesArgument
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationCandidate
import dev.zacsweers.metro.compiler.ir.graph.reporting.ContributionDecisionCapture
import dev.zacsweers.metro.compiler.ir.graph.reporting.bindingExplanationDeclaration
import dev.zacsweers.metro.compiler.safePathString
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.SortedMap
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

@Inject
@SingleIn(IrScope::class)
internal class IrContributionMerger(
  metroContext: IrMetroContext,
  private val contributionData: IrContributionData,
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

  private data class ScopedContributions(
    val allContributions: Map<ClassId, List<IrType>>,
    val bindingContainers: Map<ClassId, IrClass>,
    val externalSupertypes: Map<ClassId, IrClass>,
    val originToContributions: Map<ClassId, Set<ClassId>>,
    val contributionSources: Map<ClassId, ClassId>,
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

  private fun IrClass.originClassIdChain(callingDeclaration: IrDeclaration): List<ClassId> {
    return computeOriginClassIdChain(
      startClass = this,
      originClassId = { currentClass -> currentClass.originClassId() },
      resolveClass = { originClassId -> callingDeclaration.lookupClass(originClassId)?.owner },
    )
  }

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
                  it.rawType().classIdOrFail.outerClassId!!
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
            // Reuse the resolved origin chains to name authored declarations in reports.
            val contributionSources =
              if (reportsDir != null) mutableMapOf<ClassId, ClassId>() else null

            // Check regular contributions (with nested `MetroContribution` classes)
            for ((contributionClassId, contributions) in allContributions) {
              // Get the actual contribution class (nested `MetroContribution`)
              val contributionClass = contributions.firstOrNull()?.rawTypeOrNull()
              if (contributionClass != null) {
                // @Origin is usually present on the contribution container (the parent class).
                // MetroContribution nested classes generated from those containers don't always
                // carry that annotation, so fall back to the parent to preserve replacement
                // behavior in IR-only graph-extension merging.
                val originChain =
                  contributionClass.originClassIdChain(callingDeclaration).ifEmpty {
                    contributionClass.parentAsClass.originClassIdChain(callingDeclaration)
                  }
                for (originClassId in originChain) {
                  originToContributions.getAndAdd(originClassId, contributionClassId)
                }
                contributionSources?.put(
                  contributionClassId,
                  originChain.lastOrNull() ?: contributionClassId,
                )
              }
            }

            // Also check binding containers (e.g., @ContributesTo classes)
            for ((containerClassId, containerClass) in bindingContainers) {
              val originChain = containerClass.originClassIdChain(callingDeclaration)
              for (originClassId in originChain) {
                originToContributions.getAndAdd(originClassId, containerClassId)
              }
              contributionSources?.put(
                containerClassId,
                originChain.lastOrNull() ?: containerClassId,
              )
            }

            // Also check direct supertype contributions. In IR-only mode, @ContributesTo
            // interfaces can be added both as direct graph supertypes and as binding containers.
            // Replacements/exclusions need to prune both views of the same source interface.
            for ((externalClassId, externalClass) in externalSupertypes) {
              val originChain = externalClass.originClassIdChain(callingDeclaration)
              for (originClassId in originChain) {
                originToContributions.getAndAdd(originClassId, externalClassId)
              }
              contributionSources?.put(externalClassId, originChain.lastOrNull() ?: externalClassId)
            }

            ScopedContributions(
              allContributions,
              bindingContainers,
              externalSupertypes,
              originToContributions,
              contributionSources.orEmpty(),
            )
          }
        }

      val originToContributions = scopedContributions.originToContributions

      // Start with copies of the contributions maps (we'll modify these)
      val mutableAllContributions = scopedContributions.allContributions.toMutableMap()
      val mutableContributedBindingContainers = scopedContributions.bindingContainers.toMutableMap()
      val mutableExternalSupertypes = scopedContributions.externalSupertypes.toMutableMap()

      val decisionCapture =
        if (reportsDir != null) {
          ContributionDecisionCapture(
            scopedContributions.allContributions.keys +
              scopedContributions.bindingContainers.keys +
              scopedContributions.externalSupertypes.keys,
            (callingDeclaration as? IrDeclarationWithName)?.bindingExplanationDeclaration(),
            scopedContributions.contributionSources,
          )
        } else {
          null
        }

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

            // Remove excluded external supertypes (raw IR extension contributions)
            val removedExternalSupertype = mutableExternalSupertypes.remove(excludedClassId)

            // Remove contributions that have @Origin annotation pointing to the excluded class
            val originContributions = originToContributions[excludedClassId]
            originContributions?.forEach { contributionId ->
              mutableAllContributions.remove(contributionId)
              mutableContributedBindingContainers.remove(contributionId)
              mutableExternalSupertypes.remove(contributionId)
            }

            val nestedContributions =
              (mutableAllContributions.keys +
                  mutableContributedBindingContainers.keys +
                  mutableExternalSupertypes.keys)
                .filter { it.outerClassId == excludedClassId }
            nestedContributions.forEach { contributionId ->
              mutableAllContributions.remove(contributionId)
              mutableContributedBindingContainers.remove(contributionId)
              mutableExternalSupertypes.remove(contributionId)
            }
            decisionCapture?.excluded(
              excludedClassId,
              originContributions.orEmpty(),
              nestedContributions,
            )

            val removedDirectContribution =
              removedContainer != null ||
                removedContribution != null ||
                removedExternalSupertype != null
            val removedOriginContribution = originContributions != null
            val removedNestedContribution = nestedContributions.isNotEmpty()
            val wasNotMatched =
              !removedDirectContribution && !removedOriginContribution && !removedNestedContribution
            if (wasNotMatched) {
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

        // Direct supertype contributions (only remaining ones after exclusions)
        yieldAll(mutableExternalSupertypes.values)

        // For binding containers with @Origin (contribution providers), also scan the
        // origin class for @ContributesBinding(replaces=...) annotations
        for (container in mutableContributedBindingContainers.values) {
          val originClassId = container.originClassId() ?: continue
          val originClass = container.lookupClass(originClassId)?.owner ?: continue
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
                    it.expectAsOrNull<FirGetClassCall>()?.resolveClassId(session)
                  }
              },
            )
          val replacingDeclaration =
            if (decisionCapture == null) null else irClass.bindingExplanationDeclaration()
          for (replacedClassId in replacedClasses) {
            classesToReplace += replacedClassId
            decisionCapture?.replaces(checkNotNull(replacingDeclaration), replacedClassId)
          }
        }

        // Process replacements
        if (classesToReplace.isNotEmpty()) {
          val unmatchedReplacements = mutableSetOf<ClassId>()

          for (replacedClassId in classesToReplace) {
            val removedContribution = mutableAllContributions.remove(replacedClassId)
            val removedContainer = mutableContributedBindingContainers.remove(replacedClassId)
            val removedExternalSupertype = mutableExternalSupertypes.remove(replacedClassId)

            // Remove contributions that have @Origin annotation pointing to the replaced class
            val originContributions = originToContributions[replacedClassId]
            originContributions?.forEach { contributionId ->
              mutableAllContributions.remove(contributionId)
              mutableContributedBindingContainers.remove(contributionId)
              mutableExternalSupertypes.remove(contributionId)
            }
            decisionCapture?.replaced(replacedClassId, originContributions.orEmpty())

            val removedDirectContribution =
              removedContribution != null ||
                removedContainer != null ||
                removedExternalSupertype != null
            val removedOriginContribution = originContributions != null
            val wasNotMatched = !removedDirectContribution && !removedOriginContribution
            if (wasNotMatched) {
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

      // Build and cache the result. Combine native markers and raw supertypes into a single sorted
      // set. The downstream supertype-chunking flow distinguishes MetroContribution markers by
      // their annotation.
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
          decisionCapture?.snapshot().orEmpty(),
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
   * `MetroContribution` markers as well as raw supertypes from direct `@ContributesTo` interfaces
   * and [MetroIrContributionExtension]s (e.g., Hilt entry points). Downstream code distinguishes
   * them by annotation in [computePromotedParents].
   */
  val supertypes: SortedSet<IrType>,
  // Deterministic sort
  val bindingContainers: SortedMap<ClassId, IrClass>,
  /** Removals captured during this merge when graph reports are enabled. */
  val contributionDecisions: List<BindingExplanationCandidate> = emptyList(),
)

/**
 * For each `MetroContribution` marker in [contributions], returns its parent contributing interface
 * if that parent is not already a direct supertype of [ownerGraph]. Raw supertypes are left as-is,
 * including nested direct `@ContributesTo` interfaces whose containing class is not a contribution.
 */
internal fun computePromotedParents(
  contributions: IrContributions,
  ownerGraph: IrClass,
): Map<IrType, IrType> {
  val existing = ownerGraph.superTypes.mapNotNullTo(mutableSetOf()) { it.rawTypeOrNull()?.classId }
  return buildMap {
    for (marker in contributions.supertypes) {
      val markerClass = marker.rawType()
      if (!markerClass.hasAnnotation(Symbols.ClassIds.metroContribution)) continue
      val parentClass = markerClass.parent as? IrClass ?: continue
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
