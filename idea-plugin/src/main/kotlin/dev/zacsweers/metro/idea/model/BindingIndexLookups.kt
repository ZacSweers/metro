// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import dev.zacsweers.metro.idea.checkCanceledEvery
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.name.ClassId

/** Lookup tables built before publishing a [BindingIndex]. Lists preserve declaration order. */
internal class BindingIndexLookups
private constructor(
  val containersById: ScatterMap<ClassId, List<BindingContainerEntry>>,
  val bindingsByOrigin: ScatterMap<ClassId, List<KaBinding>>,
  /** Contributions grouped by their originating class for replacement checks. */
  val contributionsByOrigin: ScatterMap<ClassId, List<ContributionEntry>>,
  /** Bindings whose replacements can remove other contributions from a graph. */
  val bindingsWithReplacements: List<KaBinding>,
  /** Class contributions checked for priority conflicts. Set elements are excluded. */
  val priorityEligibleBindings: List<KaBinding>,
  val bindingsByMemberOwner: ScatterMap<ClassId, List<KaBinding>>,
  val contributionsByScope: ScatterMap<ClassId, List<ContributionEntry>>,
  /** Source ranges copied by the builder and shared with these lookups. */
  val bindingSourceIdentities: Map<KaBinding, BindingIndex.SourcePointerIdentity>,
  /** Uses object identity because contributed bindings can share declaration names. */
  val contributedBindingOwners: Map<KaBinding, GraphInterfaceContribution>,
  val graphsByReference: Map<GraphReference, List<KaGraphDeclaration>>,
  /** Graphs that can inherit or declare each contribution scope. */
  val graphsByReachableScope: ScatterMap<ClassId, List<KaGraphDeclaration>>,
  /** Graphs whose context paths can contain each declaration. */
  val graphsByReachableAncestor: Map<GraphDeclarationId, List<KaGraphDeclaration>>,
  val dynamicGraphsByTarget: Map<GraphReference, List<DynamicGraphCall>>,
  /** Possible parents of each graph. Contribution selection determines which parents apply. */
  val potentialParentsByReference: Map<GraphReference, List<KaGraphDeclaration>>,
  /** Whether an inherited binding is public in its owning graph. */
  val specializedBindingIdentities: Map<SpecializedBindingIdentity, Boolean>,
  /** A concrete specialization replaces its raw declaration even when its return key changes. */
  val specializedDeclarationIdentities: Set<SpecializedDeclarationIdentity>,
  val contributedSpecializedBindings: Map<SpecializedBindingIdentity, List<KaBinding>>,
  val contributedSpecializedDeclarations: Map<SpecializedDeclarationIdentity, List<KaBinding>>,
  /** Raw source callables remain authoritative when their interface was written explicitly. */
  val unownedBindingDeclarations: Map<BindingDeclarationIdentity, List<KaBinding>>,
  val bindingsByKey: ScatterMap<KaTypeKey, List<KaBinding>>,
  val bindingsByType: ScatterMap<KaTypeSnapshot, List<KaBinding>>,
  val assistedFactoriesByTarget: ScatterMap<KaTypeKey, List<KaBinding.AssistedFactory>>,
  val consumersByKey: ScatterMap<KaTypeKey, List<ConsumerEntry>>,
  val contributionsByMultibindingId: ScatterMap<String, List<KaBinding>>,
  val consumersByMultibindingId: ScatterMap<String, List<ConsumerEntry>>,
  val accessorsByGraph: ScatterMap<GraphDeclarationId, List<ConsumerEntry>>,
  /** Groups by file without retaining PSI elements. Resolving pointers needs a read action. */
  val bindingsByFile: ScatterMap<VirtualFile, List<KaBinding>>,
  val consumersByFile: ScatterMap<VirtualFile, List<ConsumerEntry>>,
  val specializedConsumerIdentities: Set<SpecializedConsumerIdentity>,
  val contributedSpecializedConsumers: Map<SpecializedConsumerIdentity, List<ConsumerEntry>>,
  /** Keys of assisted factories found in multiple source shards. */
  val duplicatedAssistedFactoryKeys: Set<KaTypeKey>,
  val graphsByFile: ScatterMap<VirtualFile, List<KaGraphDeclaration>>,
  val assistedSitesByFile: ScatterMap<VirtualFile, List<AssistedSite>>,
) {
  companion object {
    /** Builds lookups from the builder's immutable declarations and source ranges. */
    fun build(
      bindings: List<KaBinding>,
      consumers: List<ConsumerEntry>,
      graphs: List<KaGraphDeclaration>,
      contributions: List<ContributionEntry>,
      assistedSites: List<AssistedSite>,
      bindingContainers: List<BindingContainerEntry>,
      dynamicGraphs: List<DynamicGraphCall>,
      bindingSourceIdentities: Map<KaBinding, BindingIndex.SourcePointerIdentity>,
    ): BindingIndexLookups {
      val hasDeclarations =
        bindings.isNotEmpty() ||
          consumers.isNotEmpty() ||
          graphs.isNotEmpty() ||
          contributions.isNotEmpty() ||
          assistedSites.isNotEmpty() ||
          bindingContainers.isNotEmpty() ||
          dynamicGraphs.isNotEmpty()
      val contributedBindingOwners = IdentityHashMap<KaBinding, GraphInterfaceContribution>()
      val graphsByReference = linkedMapOf<GraphReference, MutableList<KaGraphDeclaration>>()
      val potentialParentsByReference =
        linkedMapOf<GraphReference, MutableList<KaGraphDeclaration>>()
      val contributedSpecializedBindings =
        linkedMapOf<SpecializedBindingIdentity, MutableList<KaBinding>>()
      val contributedSpecializedDeclarations =
        linkedMapOf<SpecializedDeclarationIdentity, MutableList<KaBinding>>()
      val graphsByFile = MutableScatterMap<VirtualFile, MutableList<KaGraphDeclaration>>()

      var graphWorkIndex = 0
      for (graph in graphs) {
        checkCanceledEvery(graphWorkIndex++)
        graph.pointer.virtualFile?.let { file ->
          graphsByFile.getOrPut(file, ::mutableListOf) += graph
        }
        for (reference in graph.selfReferences) {
          checkCanceledEvery(graphWorkIndex++)
          graphsByReference.getOrPut(reference, ::mutableListOf) += graph
        }

        val parentReferences = linkedSetOf<GraphReference>()
        parentReferences += graph.extensionCreations
        for (contribution in graph.contributedInterfaces) {
          checkCanceledEvery(graphWorkIndex++)
          parentReferences += contribution.extensionCreations
          for (binding in contribution.bindings) {
            checkCanceledEvery(graphWorkIndex++)
            val alreadyKnown = contributedBindingOwners.containsKey(binding)
            contributedBindingOwners[binding] = contribution
            if (alreadyKnown) continue

            val owner = binding.ownerGraphId ?: continue
            val source = bindingSourceIdentities[binding] ?: continue
            val bindingIdentity =
              SpecializedBindingIdentity(owner, source, binding.javaClass, binding.typeKey)
            contributedSpecializedBindings.getOrPut(bindingIdentity, ::mutableListOf) += binding
            val declarationIdentity =
              SpecializedDeclarationIdentity(owner, source, binding.javaClass)
            contributedSpecializedDeclarations.getOrPut(
              declarationIdentity,
              ::mutableListOf,
            ) += binding
          }
        }
        for (reference in parentReferences) {
          checkCanceledEvery(graphWorkIndex++)
          potentialParentsByReference.getOrPut(reference, ::mutableListOf) += graph
        }
      }

      val graphsByReachableScope = MutableScatterMap<ClassId, MutableList<KaGraphDeclaration>>()
      val graphsByReachableAncestor =
        linkedMapOf<GraphDeclarationId, MutableList<KaGraphDeclaration>>()
      var reachabilityWorkIndex = 0
      for (graph in graphs) {
        checkCanceledEvery(reachabilityWorkIndex++)
        val scopes = linkedSetOf<ClassId>()
        val graphIds = linkedSetOf<GraphDeclarationId>()
        val visited = linkedSetOf<KaGraphDeclaration>()
        val remaining = ArrayDeque<KaGraphDeclaration>()
        remaining += graph
        while (remaining.isNotEmpty()) {
          checkCanceledEvery(reachabilityWorkIndex++)
          val current = remaining.removeFirst()
          if (!visited.add(current)) continue
          scopes += current.scopeKeys
          graphIds += current.declarationId
          for (reference in current.selfReferences) {
            checkCanceledEvery(reachabilityWorkIndex++)
            remaining += potentialParentsByReference[reference].orEmpty()
          }
        }
        for (scope in scopes) {
          checkCanceledEvery(reachabilityWorkIndex++)
          graphsByReachableScope.getOrPut(scope, ::mutableListOf) += graph
        }
        for (graphId in graphIds) {
          checkCanceledEvery(reachabilityWorkIndex++)
          graphsByReachableAncestor.getOrPut(graphId, ::mutableListOf) += graph
        }
      }

      val bindingsByOrigin = MutableScatterMap<ClassId, MutableList<KaBinding>>()
      val bindingsWithReplacements = mutableListOf<KaBinding>()
      val priorityEligibleBindings = mutableListOf<KaBinding>()
      val bindingsByMemberOwner = MutableScatterMap<ClassId, MutableList<KaBinding>>()
      val bindingsByKey = MutableScatterMap<KaTypeKey, MutableList<KaBinding>>()
      val bindingsByType = MutableScatterMap<KaTypeSnapshot, MutableList<KaBinding>>()
      val assistedFactoriesByTarget =
        MutableScatterMap<KaTypeKey, MutableList<KaBinding.AssistedFactory>>()
      val contributionsByMultibindingId = MutableScatterMap<String, MutableList<KaBinding>>()
      val bindingsByFile = MutableScatterMap<VirtualFile, MutableList<KaBinding>>()
      val specializedBindingIdentities = linkedMapOf<SpecializedBindingIdentity, Boolean>()
      val specializedDeclarationIdentities = linkedSetOf<SpecializedDeclarationIdentity>()
      val unownedBindingDeclarations =
        linkedMapOf<BindingDeclarationIdentity, MutableList<KaBinding>>()
      val assistedFactoryIdentities = HashSet<Triple<ClassId?, VirtualFile?, KaTypeKey>>()
      val duplicatedAssistedFactoryKeys = linkedSetOf<KaTypeKey>()

      var bindingWorkIndex = 0
      for (binding in bindings) {
        checkCanceledEvery(bindingWorkIndex++)
        if (binding.replaces.isNotEmpty()) bindingsWithReplacements += binding
        if (binding.isPriorityEligibleContribution()) priorityEligibleBindings += binding
        val source = bindingSourceIdentities[binding]
        binding.originClassId?.let { origin ->
          bindingsByOrigin.getOrPut(origin, ::mutableListOf) += binding
        }
        for (owner in binding.memberInjectionOwnerIds) {
          checkCanceledEvery(bindingWorkIndex++)
          bindingsByMemberOwner.getOrPut(owner, ::mutableListOf) += binding
        }
        val multibindingId = binding.multibindingId
        if (multibindingId == null) {
          bindingsByKey.getOrPut(binding.typeKey, ::mutableListOf) += binding
        } else {
          contributionsByMultibindingId.getOrPut(
            multibindingId,
            ::mutableListOf,
          ) += binding
        }
        bindingsByType.getOrPut(binding.typeKey.type, ::mutableListOf) += binding
        binding.pointer.virtualFile?.let { file ->
          bindingsByFile.getOrPut(file, ::mutableListOf) += binding
        }

        if (binding is KaBinding.AssistedFactory) {
          binding.targetTypeKey?.let { target ->
            assistedFactoriesByTarget.getOrPut(target, ::mutableListOf) += binding
          }
          val identity = Triple(binding.originClassId, binding.pointer.virtualFile, binding.typeKey)
          if (!assistedFactoryIdentities.add(identity)) {
            duplicatedAssistedFactoryKeys += binding.typeKey
          }
        }

        if (binding.ownerGraphId == null && binding.containerId != null && source != null) {
          val identity = BindingDeclarationIdentity(source, binding.javaClass, binding.typeKey)
          unownedBindingDeclarations.getOrPut(identity, ::mutableListOf) += binding
        }
        val ownerGraphId = binding.ownerGraphId
        if (
          binding is KaBinding.BoundInstance ||
            ownerGraphId == null ||
            contributedBindingOwners.containsKey(binding) ||
            source == null
        ) {
          continue
        }
        val specialization =
          SpecializedBindingIdentity(
            ownerGraphId,
            source,
            binding.javaClass,
            binding.typeKey,
          )
        val alreadyPublic = specializedBindingIdentities[specialization] == true
        specializedBindingIdentities[specialization] = alreadyPublic || !binding.isGraphPrivate
        specializedDeclarationIdentities +=
          SpecializedDeclarationIdentity(
            specialization.graphId,
            specialization.pointer,
            specialization.bindingClass,
          )
      }

      val consumersByKey = MutableScatterMap<KaTypeKey, MutableList<ConsumerEntry>>()
      val consumersByMultibindingId = MutableScatterMap<String, MutableList<ConsumerEntry>>()
      val accessorsByGraph = MutableScatterMap<GraphDeclarationId, MutableList<ConsumerEntry>>()
      val consumersByFile = MutableScatterMap<VirtualFile, MutableList<ConsumerEntry>>()
      val specializedConsumerIdentities = linkedSetOf<SpecializedConsumerIdentity>()
      val contributedSpecializedConsumers =
        linkedMapOf<SpecializedConsumerIdentity, MutableList<ConsumerEntry>>()

      for ((index, consumer) in consumers.withIndex()) {
        checkCanceledEvery(index)
        consumersByKey.getOrPut(consumer.key, ::mutableListOf) += consumer
        consumer.multibindingId?.let { multibindingId ->
          consumersByMultibindingId.getOrPut(multibindingId, ::mutableListOf) += consumer
        }
        consumer.graphId?.let { graphId ->
          accessorsByGraph.getOrPut(graphId, ::mutableListOf) += consumer
        }
        consumer.pointer.virtualFile?.let { file ->
          consumersByFile.getOrPut(file, ::mutableListOf) += consumer
        }

        val graphId = consumer.graphId ?: continue
        if (consumer.graphRequestKind != null) continue
        val source = consumer.sourceIdentity ?: continue
        val identity = SpecializedConsumerIdentity(graphId, source)
        if (consumer.graphContribution == null) {
          specializedConsumerIdentities += identity
        } else {
          contributedSpecializedConsumers.getOrPut(identity, ::mutableListOf) += consumer
        }
      }

      val contributionsByScope = MutableScatterMap<ClassId, MutableList<ContributionEntry>>()
      val contributionsByOrigin = MutableScatterMap<ClassId, MutableList<ContributionEntry>>()
      var contributionWorkIndex = 0
      for (contribution in contributions) {
        checkCanceledEvery(contributionWorkIndex++)
        contribution.classId?.let { origin ->
          contributionsByOrigin.getOrPut(origin, ::mutableListOf) += contribution
        }
        for (scope in contribution.scopeKeys) {
          checkCanceledEvery(contributionWorkIndex++)
          contributionsByScope.getOrPut(scope, ::mutableListOf) += contribution
        }
      }

      val containersById = MutableScatterMap<ClassId, MutableList<BindingContainerEntry>>()
      for ((index, container) in bindingContainers.withIndex()) {
        checkCanceledEvery(index)
        containersById.getOrPut(container.classId, ::mutableListOf) += container
      }

      val dynamicGraphsByTarget = linkedMapOf<GraphReference, MutableList<DynamicGraphCall>>()
      var dynamicGraphWorkIndex = 0
      for (graph in dynamicGraphs) {
        checkCanceledEvery(dynamicGraphWorkIndex++)
        dynamicGraphsByTarget.getOrPut(graph.targetGraph, ::mutableListOf) += graph
      }

      val assistedSitesByFile = MutableScatterMap<VirtualFile, MutableList<AssistedSite>>()
      for ((index, site) in assistedSites.withIndex()) {
        checkCanceledEvery(index)
        site.pointer.virtualFile?.let { file ->
          assistedSitesByFile.getOrPut(file, ::mutableListOf) += site
        }
      }

      // BindingIndex.EMPTY must initialize even when its first caller is canceled. Builds with
      // declarations check cancellation before returning.
      if (hasDeclarations) ProgressManager.checkCanceled()
      return BindingIndexLookups(
        containersById.freezeListLookup(),
        bindingsByOrigin.freezeListLookup(),
        contributionsByOrigin.freezeListLookup(),
        bindingsWithReplacements.toList(),
        priorityEligibleBindings.toList(),
        bindingsByMemberOwner.freezeListLookup(),
        contributionsByScope.freezeListLookup(),
        bindingSourceIdentities,
        contributedBindingOwners.freezeIdentityMap(),
        graphsByReference.freezeListMap(),
        graphsByReachableScope.freezeListLookup(),
        graphsByReachableAncestor.freezeListMap(),
        dynamicGraphsByTarget.freezeListMap(),
        potentialParentsByReference.freezeListMap(),
        specializedBindingIdentities.toMap(),
        specializedDeclarationIdentities.toSet(),
        contributedSpecializedBindings.freezeListMap(),
        contributedSpecializedDeclarations.freezeListMap(),
        unownedBindingDeclarations.freezeListMap(),
        bindingsByKey.freezeListLookup(),
        bindingsByType.freezeListLookup(),
        assistedFactoriesByTarget.freezeListLookup(),
        consumersByKey.freezeListLookup(),
        contributionsByMultibindingId.freezeListLookup(),
        consumersByMultibindingId.freezeListLookup(),
        accessorsByGraph.freezeListLookup(),
        bindingsByFile.freezeListLookup(),
        consumersByFile.freezeListLookup(),
        specializedConsumerIdentities.toSet(),
        contributedSpecializedConsumers.freezeListMap(),
        duplicatedAssistedFactoryKeys.toSet(),
        graphsByFile.freezeListLookup(),
        assistedSitesByFile.freezeListLookup(),
      )
    }
  }
}

internal data class BindingDeclarationIdentity(
  val pointer: BindingIndex.SourcePointerIdentity,
  val bindingClass: Class<*>,
  val bindingKey: KaTypeKey,
)

internal data class SpecializedBindingIdentity(
  val graphId: GraphDeclarationId,
  val pointer: BindingIndex.SourcePointerIdentity,
  val bindingClass: Class<*>,
  val bindingKey: KaTypeKey,
)

internal data class SpecializedDeclarationIdentity(
  val graphId: GraphDeclarationId,
  val pointer: BindingIndex.SourcePointerIdentity,
  val bindingClass: Class<*>,
)

internal data class SpecializedConsumerIdentity(
  val graphId: GraphDeclarationId,
  val pointer: BindingIndex.SourcePointerIdentity,
)

private fun KaBinding.isPriorityEligibleContribution(): Boolean {
  val isClassContribution =
    when (this) {
      is KaBinding.Alias -> isClassContribution
      is KaBinding.Provided -> isClassContribution
      else -> false
    }
  val isSetContribution = multibindingId != null && mapKeyValue == null
  return isClassContribution && !isSetContribution && originClassId != null
}

private fun <K, V> Map<K, MutableList<V>>.freezeListMap(): Map<K, List<V>> {
  return buildMap(size) {
    for ((key, values) in this@freezeListMap) {
      put(key, values.toList())
    }
  }
}

private fun <K : Any, V> IdentityHashMap<K, V>.freezeIdentityMap(): Map<K, V> {
  return Collections.unmodifiableMap(IdentityHashMap(this))
}
