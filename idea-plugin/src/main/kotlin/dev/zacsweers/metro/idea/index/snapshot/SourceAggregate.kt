// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.progress.ProgressManager
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
import dev.zacsweers.metro.idea.model.GraphInterfaceContribution
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
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
  val factoryInputs = FactoryInputMerger(bindings)
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
      factoryInputs.addBindings(shard.bindings, shard.factoryInputs.isNotEmpty())
      consumers += shard.consumers
      graphs += shard.graphs
      contributions += shard.contributions
      assistedSites += shard.assistedSites
      bindingContainers += shard.bindingContainers
      graphInterfaces += shard.graphInterfaces
      for (dynamicGraph in shard.dynamicGraphs) {
        dynamicGraphs.putIfAbsent(dynamicGraph.id, dynamicGraph)
      }
      factoryInputs.addInputs(shard.factoryInputs)
    } finally {
      completed++
      progress?.counted(
        IndexBuildPhase.COMBINING_DECLARATIONS,
        completed,
        snapshot.shardOrder.size,
      )
    }
  }
  val mergedInputs = factoryInputs.finish(consumers)
  val interfaces = graphInterfaceOverlay(graphInterfaces, graphs)
  bindings += interfaces.bindings
  consumers += interfaces.consumers
  for (index in graphs.indices) {
    ProgressManager.checkCanceled()
    graphs[index] = interfaces.attachTo(graphs[index])
  }
  return SourceAggregate(
    bindings,
    consumers,
    graphs,
    contributions,
    assistedSites,
    bindingContainers,
    dynamicGraphs.values.toList(),
    graphInterfaceSurfaces = graphInterfaces,
    factoryInputs = mergedInputs,
  )
}

/** Captures interfaces with matching scopes. BindingIndex selects them for each graph path. */
internal fun graphInterfaceOverlay(
  surfaces: List<GraphInterfaceSurface>,
  graphs: List<KaGraphDeclaration>,
): GraphInterfaceOverlay {
  if (surfaces.isEmpty()) return GraphInterfaceOverlay.EMPTY
  val interfacesByGraph = linkedMapOf<GraphDeclarationId, List<GraphInterfaceContribution>>()
  val bindings = mutableListOf<KaBinding>()
  val consumers = mutableListOf<ConsumerEntry>()
  val surfacesByScope = linkedMapOf<ClassId, MutableList<GraphInterfaceSurface>>()
  for (surface in surfaces) {
    ProgressManager.checkCanceled()
    for (scope in surface.contribution.scopeKeys) {
      surfacesByScope.getOrPut(scope) { mutableListOf() } += surface
    }
  }
  for (graph in graphs) {
    ProgressManager.checkCanceled()
    val candidates = linkedSetOf<GraphInterfaceSurface>()
    for (scope in graph.scopeKeys) candidates += surfacesByScope[scope].orEmpty()
    if (candidates.isEmpty()) continue
    val interfaces = candidates.map { surface ->
      ProgressManager.checkCanceled()
      surface.forGraph(graph)
    }
    interfacesByGraph[graph.declarationId] = interfaces
    for (contribution in interfaces) {
      bindings += contribution.bindings
      consumers += contribution.consumers
    }
  }
  return GraphInterfaceOverlay(interfacesByGraph, bindings, consumers)
}

/** Keeps candidate members together so graph metadata and lookup indexes share their instances. */
internal class GraphInterfaceOverlay(
  val interfacesByGraph: Map<GraphDeclarationId, List<GraphInterfaceContribution>>,
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
) {
  val isEmpty: Boolean
    get() = interfacesByGraph.isEmpty()

  /** Keeps already-attached source contributions when adding cached binary members. */
  fun attachTo(graph: KaGraphDeclaration): KaGraphDeclaration {
    val additional = interfacesByGraph[graph.declarationId].orEmpty()
    if (additional.isEmpty()) return graph
    return graph.withContributedInterfaces(graph.contributedInterfaces + additional)
  }

  companion object {
    val EMPTY = GraphInterfaceOverlay(emptyMap(), emptyList(), emptyList())
  }
}

/** Source declarations combined for graph indexing and dependency lookup. */
internal data class SourceAggregate(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphDeclaration>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
  val dynamicGraphs: List<DynamicGraphCall>,
  /** Retained for binary child graphs discovered after source aggregation. */
  val graphInterfaceSurfaces: List<GraphInterfaceSurface> = emptyList(),
  /** Shared input identities used when binary graph factories join this source snapshot. */
  val factoryInputs: List<FactoryInputEntry> = emptyList(),
) {
  /** Reuses binary candidates while retaining the current source graph's own member instances. */
  fun withGraphInterfaces(overlay: GraphInterfaceOverlay): SourceAggregate {
    if (overlay.isEmpty) return this
    val composedGraphs = graphs.map { graph ->
      ProgressManager.checkCanceled()
      overlay.attachTo(graph)
    }
    return copy(
      bindings = bindings + overlay.bindings,
      consumers = consumers + overlay.consumers,
      graphs = composedGraphs,
    )
  }

  fun withAddedClassBindings(classBindings: List<KaBinding>): SourceAggregate {
    if (classBindings.isEmpty()) return this
    return copy(bindings = bindings + classBindings)
  }
}
