// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.SmartPsiElementPointer
import java.util.IdentityHashMap
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/**
 * Caches graph queries for one operation against an immutable [BindingIndex].
 *
 * The caller must prevent concurrent access and discard the session when the operation finishes.
 */
internal class BindingResolutionSession internal constructor(val index: BindingIndex) {
  private val graphContexts = HashMap<KaGraphDeclaration, List<GraphContext>>()
  private val plannedGraphQueries = HashMap<GraphContext, PlannedGraphQuery>()
  private val consumerResolutions = HashMap<ConsumerEntry, ConsumerResolution>()
  /** Custom query views can share a graph context while applying different visibility scopes. */
  private val validationPlans = IdentityHashMap<GraphQueryContext, BindingIndex.GraphQueryPlan>()
  private val graphCompositions =
    HashMap<GraphCompositionKey, BindingIndex.SelectedGraphComposition>()
  private var allGraphContexts: List<GraphContext>? = null

  /**
   * Bindings satisfying [consumer]: direct key matches plus, for `Set`/`Map` multibinding sites,
   * the multibinding contributions collected into them.
   */
  fun bindingsFor(consumer: ConsumerEntry): List<KaBinding> = index.bindingsFor(this, consumer)

  /**
   * The bindings for [consumer]'s key that are members of [queryContext]'s graph. This is a
   * binding-membership query: it does not constrain by whether [consumer]'s own site belongs to the
   * graph (that is [resolveConsumer]'s job), so a consumer can probe any query context.
   */
  fun bindingsFor(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): List<KaBinding> = index.bindingsFor(this, consumer, queryContext)

  /**
   * Per-context resolution of [consumer]: which bindings satisfy it in each concrete graph path,
   * plus the use-site-visible candidates as a fallback for files/projects without graphs.
   */
  fun resolveConsumer(consumer: ConsumerEntry): ConsumerResolution {
    return index.resolveConsumer(this, consumer)
  }

  /**
   * The bindings for [key] that are members of [queryContext]'s graph. Multibinding contributions
   * are resolved separately by [multibindingContributions].
   */
  fun bindingsForKey(key: KaTypeKey, queryContext: GraphQueryContext): List<KaBinding> {
    return index.bindingsForKey(key, validationPlan(queryContext))
  }

  /** Contributions collected into [multibindingId] in [queryContext]'s graph. */
  fun multibindingContributions(
    multibindingId: String,
    queryContext: GraphQueryContext,
  ): List<KaBinding> = index.multibindingContributions(multibindingId, validationPlan(queryContext))

  /**
   * Every binding that is a member of [queryContext]'s graph. Linear over all bindings, so call on
   * demand only.
   */
  fun bindingsInContext(queryContext: GraphQueryContext): List<KaBinding> {
    return index.bindingsInContext(this, queryContext)
  }

  /** The actual seal roots after selecting this graph's contributed interface surface. */
  fun accessorsFor(queryContext: GraphQueryContext): List<ConsumerEntry> {
    return index.accessorsFor(this, queryContext)
  }

  /** The selected surface of [graph] in this exact root module and ancestor suffix. */
  fun graphComposition(
    queryContext: GraphQueryContext,
    graph: KaGraphDeclaration = queryContext.graphContext.graph,
  ): GraphComposition = index.graphComposition(this, queryContext, graph)

  /** Child declarations created by the selected surface, excluding recursive parent paths. */
  fun extensionsOf(queryContext: GraphQueryContext): List<KaGraphDeclaration> {
    return index.extensionsOf(this, queryContext)
  }

  /** Every valid aggregation context for [graph]. Extensions can have multiple parent paths. */
  fun contextsFor(graph: KaGraphDeclaration): List<GraphContext> {
    return index.contextsFor(this, graph)
  }

  /** Builds the module-aware query view for [context], or null if its graph disappeared. */
  fun queryContext(context: GraphContext): GraphQueryContext? {
    return index.queryContext(this, context)
  }

  /**
   * Reuses the completed plan for this exact query view. Includes bindings with incompatible scopes
   * so validation can report them. Canceled construction leaves the query retryable.
   */
  internal fun validationPlan(queryContext: GraphQueryContext): BindingIndex.GraphQueryPlan {
    return validationPlans.getOrPut(queryContext) {
      val computed = index.createValidationPlan(this, queryContext)
      ProgressManager.checkCanceled()
      computed
    }
  }

  /** Finds the current index's context for a path retained across an index rebuild. */
  fun findContext(path: GraphPath): GraphContext? = index.findContext(this, path)

  /** Concrete child contexts created directly from [parent]'s exact graph path. */
  fun extensionContextsOf(parent: GraphContext): List<GraphContext> {
    return index.extensionContextsOf(this, parent)
  }

  /**
   * Contributions aggregated by [queryContext]'s graph itself: matched against the graph's own
   * aggregation scopes, minus excluded. Contributions a graph extension sees through its parent
   * chain are reported separately by [inheritedContributionsFor].
   */
  fun contributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    return index.contributionsFor(this, queryContext)
  }

  /**
   * Contributions [queryContext]'s graph receives from its parent chain: matched against ancestor
   * scopes only, minus excluded. Empty for non-extension graphs.
   */
  fun inheritedContributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    return index.inheritedContributionsFor(this, queryContext)
  }

  /**
   * Containers [graph] itself wires: declared, factory-included, contributed into its own
   * aggregation scopes, and everything those include transitively. Bindings from these stay local
   * to [graph]'s context like the compiler's locally declared keys.
   */
  fun graphOwnContainers(
    graph: KaGraphDeclaration,
    queryContext: GraphQueryContext,
  ): Set<ClassId> = index.graphOwnContainers(this, graph, queryContext)

  /** Whether [binding] belongs to the current graph's own declared or included bindings. */
  fun isBindingOwnedByCurrentGraph(
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean = index.isBindingOwnedByCurrentGraph(this, binding, queryContext)

  /** Whether an ancestor can resolve [key] through one of its private bindings. */
  fun hasPrivateAncestorBinding(key: KaTypeKey, queryContext: GraphQueryContext): Boolean {
    return index.hasPrivateAncestorBinding(this, key, queryContext)
  }

  /** Whether the consumer's declaration participates in this exact graph query view. */
  fun isConsumerInContext(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): Boolean = index.isConsumerInContext(this, consumer, queryContext)

  /** Sites consuming any of [bindingEntries], joining multibinding contributions by id. */
  fun consumersFor(
    bindingEntries: Collection<KaBinding>,
    graphPath: GraphPath? = null,
  ): List<ConsumerEntry> = index.consumersFor(this, bindingEntries, graphPath)

  /** Selects one presentation consumer when inherited sites agree on their resolved bindings. */
  fun consumerEntryAt(element: KtElement): ConsumerEntry? {
    return selectConsumerEntry(index.consumerEntriesAt(element)) { entry ->
      resolveConsumer(entry).uniformBindings?.let(index::bindingResolutionIdentities)
    }
  }

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
