// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import dev.zacsweers.metro.idea.index.FactoryInputEntry
import dev.zacsweers.metro.idea.index.GraphInterfaceSurface
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DynamicGraphCall
import dev.zacsweers.metro.idea.model.DynamicGraphId
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import java.util.Collections
import org.jetbrains.kotlin.name.ClassId

/** Combines source declarations and keeps shared factory inputs attached to their graph owners. */
internal fun aggregateSource(
  snapshot: SourceSnapshot,
  progress: IndexBuildProgressReporter?,
): SourceAggregate {
  val bindings = mutableListOf<KaBinding>()
  val consumers = mutableListOf<ConsumerEntry>()
  val graphs = mutableListOf<KaGraphDeclaration>()
  val contributions = mutableListOf<ContributionEntry>()
  val assistedSites = mutableListOf<AssistedSite>()
  val bindingContainers = mutableListOf<BindingContainerEntry>()
  val graphInterfaces = mutableListOf<GraphInterfaceSurface>()
  val dynamicGraphs = linkedMapOf<DynamicGraphId, DynamicGraphCall>()
  val factoryInputs = linkedMapOf<FactoryInputEntry.Id, FactoryInputEntry>()
  var factoryInputBindings: CanonicalFactoryInputBindings? = null
  var completed = 0
  progress?.counted(
    IndexBuildPhase.COMBINING_DECLARATIONS,
    completed,
    snapshot.shardOrder.size,
  )
  for (virtualFile in snapshot.shardOrder) {
    ProgressManager.checkCanceled()
    try {
      val shard = snapshot.shards[virtualFile] ?: continue
      if (shard.factoryInputs.isEmpty()) {
        bindings += shard.bindings
      } else {
        for (binding in shard.bindings) {
          val isOwnedFactoryInput =
            binding is KaBinding.BoundInstance &&
              binding.ownerGraphId != null &&
              (binding.isGraphInput || binding.isBindingContainerInput)
          if (!isOwnedFactoryInput) {
            bindings += binding
            continue
          }
          val instances =
            factoryInputBindings
              ?: CanonicalFactoryInputBindings(bindings).also { factoryInputBindings = it }
          instances.add(binding)
        }
      }
      consumers += shard.consumers
      graphs += shard.graphs
      contributions += shard.contributions
      assistedSites += shard.assistedSites
      bindingContainers += shard.bindingContainers
      graphInterfaces += shard.graphInterfaces
      for (dynamicGraph in shard.dynamicGraphs) {
        dynamicGraphs.putIfAbsent(dynamicGraph.id, dynamicGraph)
      }
      for (input in shard.factoryInputs) factoryInputs.putIfAbsent(input.id, input)
    } finally {
      completed++
      progress?.counted(
        IndexBuildPhase.COMBINING_DECLARATIONS,
        completed,
        snapshot.shardOrder.size,
      )
    }
  }
  factoryInputBindings?.finish()
  for (input in factoryInputs.values) {
    val sharedBindings = input.bindings
    if (sharedBindings.firstOrNull() is KaBinding.BoundInstance) {
      bindings.addAll(sharedBindings.subList(1, sharedBindings.size))
    } else {
      bindings += sharedBindings
    }
    consumers += input.consumers
  }
  attachGraphInterfaces(graphInterfaces, graphs, bindings, consumers)
  return SourceAggregate(
    bindings,
    consumers,
    graphs,
    contributions,
    assistedSites,
    bindingContainers,
    dynamicGraphs.values.toList(),
  )
}

/** Attaches interfaces with matching scopes. BindingIndex selects them for each graph path. */
private fun attachGraphInterfaces(
  surfaces: List<GraphInterfaceSurface>,
  graphs: MutableList<KaGraphDeclaration>,
  bindings: MutableList<KaBinding>,
  consumers: MutableList<ConsumerEntry>,
) {
  if (surfaces.isEmpty()) return
  val surfacesByScope = linkedMapOf<ClassId, MutableList<GraphInterfaceSurface>>()
  for (surface in surfaces) {
    ProgressManager.checkCanceled()
    for (scope in surface.contribution.scopeKeys) {
      surfacesByScope.getOrPut(scope) { mutableListOf() } += surface
    }
  }
  for (graphIndex in graphs.indices) {
    ProgressManager.checkCanceled()
    val graph = graphs[graphIndex]
    val candidates = linkedSetOf<GraphInterfaceSurface>()
    for (scope in graph.scopeKeys) candidates += surfacesByScope[scope].orEmpty()
    if (candidates.isEmpty()) continue
    val interfaces = candidates.map { surface ->
      ProgressManager.checkCanceled()
      surface.forGraph(graph)
    }
    graphs[graphIndex] = graph.withContributedInterfaces(interfaces)
    for (contribution in interfaces) {
      bindings += contribution.bindings
      consumers += contribution.consumers
    }
  }
}

/** Keeps one factory instance per source parameter while retaining every exact graph owner. */
private class CanonicalFactoryInputBindings(private val bindings: MutableList<KaBinding>) {
  private val groups = LinkedHashMap<FactoryInputBindingIdentity, FactoryInputBindingGroup>()

  fun add(binding: KaBinding.BoundInstance) {
    val file = binding.pointer.virtualFile
    val range = binding.pointer.psiRange
    if (file == null || range == null) {
      bindings += binding
      return
    }

    val identity =
      FactoryInputBindingIdentity(
        binding.typeKey,
        file,
        range.startOffset,
        range.endOffset,
        binding.isGraphInput,
        binding.isBindingContainerInput,
      )
    val existing = groups[identity]
    if (existing == null) {
      groups[identity] = FactoryInputBindingGroup(bindings.size, binding)
      bindings += binding
      return
    }

    val ownerGraphId = binding.ownerGraphId
    if (ownerGraphId != null && ownerGraphId != existing.binding.ownerGraphId) {
      val owners =
        existing.additionalOwners
          ?: linkedSetOf<GraphDeclarationId>().also { existing.additionalOwners = it }
      owners += ownerGraphId
    }
    if (binding.additionalOwnerGraphIds.isNotEmpty()) {
      val owners =
        existing.additionalOwners
          ?: linkedSetOf<GraphDeclarationId>().also { existing.additionalOwners = it }
      owners += binding.additionalOwnerGraphIds
      existing.binding.ownerGraphId?.let(owners::remove)
    }
  }

  fun finish() {
    for (group in groups.values) {
      ProgressManager.checkCanceled()
      val owners = group.additionalOwners
      if (owners.isNullOrEmpty()) continue

      val binding = group.binding
      bindings[group.index] =
        KaBinding.BoundInstance(
          pointer = binding.pointer,
          typeKey = binding.typeKey,
          containerId = binding.containerId,
          isGraphInput = binding.isGraphInput,
          isBindingContainerInput = binding.isBindingContainerInput,
          isGraphPrivate = binding.isGraphPrivate,
          ownerGraphId = binding.ownerGraphId,
          additionalOwnerGraphIds = Collections.unmodifiableSet(LinkedHashSet(owners)),
        )
    }
  }
}

private data class FactoryInputBindingIdentity(
  val key: KaTypeKey,
  val file: VirtualFile,
  val startOffset: Int,
  val endOffset: Int,
  val isGraphInput: Boolean,
  val isBindingContainerInput: Boolean,
)

private class FactoryInputBindingGroup(
  val index: Int,
  val binding: KaBinding.BoundInstance,
  var additionalOwners: MutableSet<GraphDeclarationId>? = null,
)

/** Source declarations combined for graph indexing and dependency lookup. */
internal data class SourceAggregate(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphDeclaration>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
  val dynamicGraphs: List<DynamicGraphCall>,
) {
  fun withAddedFactories(factories: List<KaBinding.AssistedFactory>): SourceAggregate {
    if (factories.isEmpty()) return this
    return copy(bindings = bindings + factories)
  }
}
