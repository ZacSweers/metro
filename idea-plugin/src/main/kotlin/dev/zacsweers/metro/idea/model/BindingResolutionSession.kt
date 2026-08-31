// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/**
 * Caches graph queries for one operation against an immutable [BindingIndex].
 *
 * The caller must prevent concurrent access and discard the session when the operation finishes.
 */
internal class BindingResolutionSession internal constructor(private val index: BindingIndex) {
  private val graphContexts = HashMap<KaGraphDeclaration, List<GraphContext>>()
  private val plannedGraphQueries = HashMap<GraphContext, PlannedGraphQuery>()
  private val consumerResolutions = HashMap<ConsumerEntry, ConsumerResolution>()
  private val graphCompositions =
    HashMap<GraphCompositionKey, BindingIndex.SelectedGraphComposition>()
  private var allGraphContexts: List<GraphContext>? = null

  fun bindingsFor(consumer: ConsumerEntry): List<KaBinding> = index.bindingsFor(this, consumer)

  fun bindingsFor(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): List<KaBinding> = index.bindingsFor(this, consumer, queryContext)

  fun resolveConsumer(consumer: ConsumerEntry): ConsumerResolution {
    return index.resolveConsumer(this, consumer)
  }

  fun bindingsForKey(key: KaTypeKey, queryContext: GraphQueryContext): List<KaBinding> {
    return index.bindingsForKey(this, key, queryContext)
  }

  fun multibindingContributions(
    multibindingId: String,
    queryContext: GraphQueryContext,
  ): List<KaBinding> = index.multibindingContributions(this, multibindingId, queryContext)

  fun bindingsInContext(queryContext: GraphQueryContext): List<KaBinding> {
    return index.bindingsInContext(this, queryContext)
  }

  fun accessorsFor(queryContext: GraphQueryContext): List<ConsumerEntry> {
    return index.accessorsFor(this, queryContext)
  }

  fun graphComposition(
    queryContext: GraphQueryContext,
    graph: KaGraphDeclaration = queryContext.graphContext.graph,
  ): GraphComposition = index.graphComposition(this, queryContext, graph)

  fun extensionsOf(queryContext: GraphQueryContext): List<KaGraphDeclaration> {
    return index.extensionsOf(this, queryContext)
  }

  fun contextsFor(graph: KaGraphDeclaration): List<GraphContext> {
    return index.contextsFor(this, graph)
  }

  fun queryContext(context: GraphContext): GraphQueryContext? {
    return index.queryContext(this, context)
  }

  internal fun validationPlan(queryContext: GraphQueryContext): BindingIndex.GraphQueryPlan {
    return index.validationPlan(this, queryContext)
  }

  fun findContext(path: GraphPath): GraphContext? = index.findContext(this, path)

  fun extensionContextsOf(parent: GraphContext): List<GraphContext> {
    return index.extensionContextsOf(this, parent)
  }

  fun contributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    return index.contributionsFor(this, queryContext)
  }

  fun inheritedContributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    return index.inheritedContributionsFor(this, queryContext)
  }

  fun graphOwnContainers(
    graph: KaGraphDeclaration,
    queryContext: GraphQueryContext,
  ): Set<ClassId> = index.graphOwnContainers(this, graph, queryContext)

  fun isBindingOwnedByCurrentGraph(
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean = index.isBindingOwnedByCurrentGraph(this, binding, queryContext)

  fun hasPrivateAncestorBinding(key: KaTypeKey, queryContext: GraphQueryContext): Boolean {
    return index.hasPrivateAncestorBinding(this, key, queryContext)
  }

  fun isConsumerInContext(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): Boolean = index.isConsumerInContext(this, consumer, queryContext)

  fun consumersFor(
    bindingEntries: Collection<KaBinding>,
    graphPath: GraphPath? = null,
  ): List<ConsumerEntry> = index.consumersFor(this, bindingEntries, graphPath)

  internal fun cachedContextsFor(
    graph: KaGraphDeclaration,
    build: () -> List<GraphContext>,
  ): List<GraphContext> = graphContexts.getOrPut(graph, build)

  @TestOnly
  internal fun hasComputedContextsFor(graph: KaGraphDeclaration): Boolean {
    return graph in graphContexts
  }

  internal fun allGraphContexts(): List<GraphContext> {
    allGraphContexts?.let {
      return it
    }
    val computed =
      index.graphs.flatMap { graph ->
        ProgressManager.checkCanceled()
        contextsFor(graph)
      }
    allGraphContexts = computed
    return computed
  }

  internal fun plannedQuery(context: GraphContext): PlannedGraphQuery? {
    return plannedGraphQueries[context]
  }

  internal fun plannedQuery(
    context: GraphContext,
    create: () -> PlannedGraphQuery,
  ): PlannedGraphQuery = plannedGraphQueries.getOrPut(context, create)

  internal fun consumerResolution(
    consumer: ConsumerEntry,
    create: () -> ConsumerResolution,
  ): ConsumerResolution = consumerResolutions.getOrPut(consumer, create)

  internal fun graphComposition(
    path: GraphPath,
    module: KaModule,
    create: () -> BindingIndex.SelectedGraphComposition,
  ): BindingIndex.SelectedGraphComposition {
    return graphCompositions.getOrPut(GraphCompositionKey(path, module), create)
  }

  internal fun resolutionViewFor(
    sourceIdentity: BindingIndex.SourcePointerIdentity?,
    pointer: SmartPsiElementPointer<out KtElement>,
  ): ResolutionModuleView? {
    val frozenInputs = index.resolutionInputs
    val file = sourceIdentity?.file ?: pointer.virtualFile
    val view = frozenInputs.moduleViewFor(file) ?: return null
    return ResolutionModuleView(
      view.module,
      view.resolutionScope,
      view.daggerAnvilInteropEnabled,
    )
  }

  internal class PlannedGraphQuery(
    val queryContext: GraphQueryContext,
    val aggregateSelection: BindingIndex.ContributionSelection,
  ) {
    var structure: BindingIndex.GraphQueryStructure? = null
    var editorPlan: BindingIndex.GraphQueryPlan? = null
  }

  private data class GraphCompositionKey(val path: GraphPath, val module: KaModule)
}

internal data class ResolutionModuleView(
  val module: KaModule,
  val resolutionScope: DeclarationResolutionScope,
  val daggerAnvilInteropEnabled: Boolean,
)
