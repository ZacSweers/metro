// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPsiElementPointer
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/**
 * Project-wide snapshot of Metro declarations, built from stub indexes + the Analysis API.
 *
 * Resolution starts with project-wide key matches, then filters those candidates through each
 * graph's aggregation context for editor features that need graph membership.
 */
internal class BindingIndex(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphNode>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite> = emptyList(),
  val bindingContainers: List<BindingContainerEntry> = emptyList(),
) {
  private val containersById: Map<ClassId, BindingContainerEntry> by lazy {
    bindingContainers.associateBy { it.classId }
  }

  private val graphContexts = ConcurrentHashMap<KaGraphNode, List<GraphContext>>()

  // Contributions are keyed solely by multibindingId, mirroring the compiler's
  // @MultibindingElement qualifier swap — their element key must not satisfy plain consumers.
  private val bindingsByKey: Map<KaTypeKey, List<KaBinding>> by lazy {
    bindings.filter { it.multibindingId == null }.groupBy { it.key }
  }

  private val consumersByKey: Map<KaTypeKey, List<ConsumerEntry>> by lazy {
    consumers.groupBy { it.key }
  }

  private val contributionsByMultibindingId: Map<String, List<KaBinding>> by lazy {
    bindings.filter { it.multibindingId != null }.groupBy { it.multibindingId!! }
  }

  private val consumersByMultibindingId: Map<String, List<ConsumerEntry>> by lazy {
    consumers.filter { it.multibindingId != null }.groupBy { it.multibindingId!! }
  }

  // PSI-identity lookups for editor features classifying the element under the caret/pass.
  // Bucketed by the pointers' virtual files (no PSI dereference) so the index never pins PSI
  // project-wide; only the queried file's bucket dereferences its pointers. Must be accessed in
  // a read action.
  private val bindingsByFile: Map<VirtualFile, List<KaBinding>> by lazy {
    bindings.groupByFile { it.pointer }
  }

  private val consumersByFile: Map<VirtualFile, List<ConsumerEntry>> by lazy {
    consumers.groupByFile { it.pointer }
  }

  private val graphsByFile: Map<VirtualFile, List<KaGraphNode>> by lazy {
    graphs.groupByFile { it.pointer }
  }

  private val assistedSitesByFile: Map<VirtualFile, List<AssistedSite>> by lazy {
    assistedSites.groupByFile { it.pointer }
  }

  /**
   * Bindings satisfying [consumer]: direct key matches plus, for `Set`/`Map` aggregate sites, the
   * multibinding contributions collected into them.
   */
  fun bindingsFor(consumer: ConsumerEntry): List<KaBinding> {
    val direct = bindingsByKey[consumer.key].orEmpty()
    val contributions = consumer.multibindingId?.let { contributionsByMultibindingId[it] }.orEmpty()
    return direct + contributions
  }

  /** [bindingsFor] filtered to bindings that are members of [context]'s graph. */
  fun bindingsFor(
    consumer: ConsumerEntry,
    context: GraphContext,
  ): List<KaBinding> {
    return applyReplaces(bindingsFor(consumer).filter { isInContext(it, context) })
  }

  /**
   * Per-graph resolution of [consumer]: which bindings satisfy it in each graph that can resolve
   * it, plus the unfiltered project-wide candidates as a fallback for projects without graphs.
   */
  fun resolveConsumer(consumer: ConsumerEntry): ConsumerResolution {
    val global = bindingsFor(consumer)
    if (global.isEmpty() || graphs.isEmpty()) {
      return ConsumerResolution(global, emptyMap())
    }
    val perGraph = LinkedHashMap<KaGraphNode, List<KaBinding>>()
    for (graph in graphs) {
      val filtered =
        contextsFor(graph).flatMap { context -> bindingsFor(consumer, context) }.distinct()
      if (filtered.isNotEmpty()) {
        perGraph[graph] = filtered
      }
    }
    return ConsumerResolution(global, perGraph)
  }

  /** The aggregated context of [graph], following extension parent chains. */
  fun contextFor(graph: KaGraphNode): GraphContext {
    val contexts = contextsFor(graph)
    if (contexts.size == 1) return contexts.single()

    val orderedChain = contexts.flatMap { it.chain }.distinct()
    val chain =
      if (orderedChain.firstOrNull() == graph) {
        orderedChain
      } else {
        listOf(graph) + orderedChain.filter { it != graph }
      }

    return GraphContext(
      chain = chain,
      scopes = contexts.flatMapTo(hashSetOf()) { it.scopes },
      scopingAnnotations = contexts.flatMapTo(hashSetOf()) { it.scopingAnnotations },
      excludes = contexts.flatMapTo(hashSetOf()) { it.excludes },
      containers = contexts.flatMapTo(hashSetOf()) { it.containers },
      includedDependencies = contexts.flatMapTo(hashSetOf()) { it.includedDependencies },
      graphClassIds = contexts.flatMapTo(hashSetOf()) { it.graphClassIds },
    )
  }

  /** Every valid aggregation context for [graph]. Extensions can have multiple parent paths. */
  fun contextsFor(graph: KaGraphNode): List<GraphContext> {
    return graphContexts.computeIfAbsent(graph) { buildContexts(it) }
  }

  /**
   * Contributions aggregated by [context]'s graph itself: matched against the graph's own
   * aggregation scopes, minus excluded. Contributions a graph extension sees through its parent
   * chain are reported separately by [inheritedContributionsFor].
   */
  fun contributionsFor(context: GraphContext): List<ContributionEntry> {
    return contributionsForScopes(context.graph.scopeKeys).filter {
      it.classId !in context.excludes
    }
  }

  /**
   * Contributions [context]'s graph receives from its parent chain rather than aggregating itself:
   * matched against ancestor scopes only, minus excluded. Empty for non-extension graphs.
   */
  fun inheritedContributionsFor(context: GraphContext): List<ContributionEntry> {
    val inheritedScopes = context.scopes - context.graph.scopeKeys
    return contributionsForScopes(inheritedScopes).filter {
      it.classId !in context.excludes && it.scopeKeys.none(context.graph.scopeKeys::contains)
    }
  }

  private fun buildContexts(graph: KaGraphNode): List<GraphContext> {
    return buildChains(graph, visited = setOf(graph)).map(::buildContext)
  }

  private fun buildChains(
    graph: KaGraphNode,
    visited: Set<KaGraphNode>,
  ): List<List<KaGraphNode>> {
    if (!graph.isExtension) return listOf(listOf(graph))

    val parents = graphs.filter { candidate ->
      candidate !in visited && candidate.accessorTypeIds.any { it in graph.selfIds }
    }
    if (parents.isEmpty()) return listOf(listOf(graph))

    val chains = mutableListOf<List<KaGraphNode>>()
    for (parent in parents) {
      val parentChains = buildChains(parent, visited + parent)
      for (parentChain in parentChains) {
        chains += listOf(graph) + parentChain
      }
    }
    return chains
  }

  private fun buildContext(chain: List<KaGraphNode>): GraphContext {
    val scopes = chain.flatMapTo(hashSetOf()) { it.scopeKeys }
    val excludes = chain.flatMapTo(hashSetOf()) { it.excludes }
    val graphClassIds = chain.flatMapTo(hashSetOf()) { it.selfIds }
    val includedDependencies = chain.flatMapTo(hashSetOf()) { it.includedDependencies }

    // Containers: declared on the graphs, contributed into scope, or transitively included
    val containerRoots = chain.flatMapTo(hashSetOf()) { it.bindingContainers }
    contributions
      .asSequence()
      .filter { it.classId != null && it.classId in containersById }
      .filter { it.scopeKeys.any(scopes::contains) && it.classId !in excludes }
      .mapTo(containerRoots) { it.classId!! }

    val containers = hashSetOf<ClassId>()
    val queue = ArrayDeque(containerRoots)
    while (queue.isNotEmpty()) {
      val id = queue.removeFirst()
      if (!containers.add(id)) continue
      containersById[id]?.includes?.forEach(queue::add)
    }

    return GraphContext(
      chain = chain,
      scopes = scopes,
      scopingAnnotations = chain.flatMapTo(hashSetOf()) { it.scopingAnnotations },
      excludes = excludes,
      containers = containers,
      includedDependencies = includedDependencies,
      graphClassIds = graphClassIds,
    )
  }

  private fun isInContext(entry: KaBinding, context: GraphContext): Boolean {
    if (entry.originClassId != null && entry.originClassId in context.excludes) return false
    // Scoped bindings only live in graphs declaring a matching scope (explicitly or implicitly
    // via the aggregation scope's conveyed @SingleIn)
    if (entry.scope != null && entry.scope !in context.scopingAnnotations) return false
    if (
      entry.contributionScopes.isNotEmpty() &&
        entry.contributionScopes.none { it in context.scopes }
    ) {
      return false
    }
    return when (entry.kind) {
      // Container callables are only live in graphs that wire their container in (or that
      // declare them directly on the graph)
      BindingKind.PROVIDES,
      BindingKind.BINDS,
      BindingKind.MULTIBINDING_DECLARATION -> {
        entry.contributionScopes.isNotEmpty() ||
          entry.containerId == null ||
          entry.containerId in context.graphClassIds ||
          entry.containerId in context.containers
      }
      BindingKind.INSTANCE -> entry.containerId in context.graphClassIds
      BindingKind.INCLUDED -> entry.containerId in context.includedDependencies
      // Injected classes are implicit bindings; contributed bindings already passed the
      // scope/excludes checks above
      else -> true
    }
  }

  /** Drops bindings replaced by other surviving contributions, mirroring compiler aggregation. */
  private fun applyReplaces(entries: List<KaBinding>): List<KaBinding> {
    if (entries.size < 2) return entries
    val replaced = entries.flatMapTo(hashSetOf()) { it.replaces }
    if (replaced.isEmpty()) return entries
    return entries.filter { it.originClassId == null || it.originClassId !in replaced }
  }

  /** Sites consuming any of [bindingEntries], joining multibinding contributions by id. */
  fun consumersFor(bindingEntries: Collection<KaBinding>): List<ConsumerEntry> {
    val bindingSet = bindingEntries.toSet()
    val result = LinkedHashSet<ConsumerEntry>()
    val candidates = LinkedHashSet<ConsumerEntry>()
    for (entry in bindingSet) {
      if (entry.multibindingId != null) {
        candidates += consumersByMultibindingId[entry.multibindingId].orEmpty()
      } else {
        candidates += consumersByKey[entry.key].orEmpty()
      }
    }
    if (graphs.isEmpty()) return candidates.toList()

    for (consumer in candidates) {
      val resolution = resolveConsumer(consumer)
      val graphBindings = resolution.perGraph.values.flatten()
      if (graphBindings.any { it in bindingSet }) {
        result += consumer
      }
    }
    return result.toList()
  }

  fun bindingEntriesAt(element: KtElement): List<KaBinding> {
    val file = element.containingFile?.virtualFile ?: return emptyList()
    return bindingsByFile[file].orEmpty().filter { it.pointer.element === element }
  }

  fun consumerEntryAt(element: KtElement): ConsumerEntry? {
    val file = element.containingFile?.virtualFile ?: return null
    return consumersByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  fun graphEntryAt(element: KtElement): KaGraphNode? {
    val file = element.containingFile?.virtualFile ?: return null
    return graphsByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  fun assistedSiteAt(element: KtElement): AssistedSite? {
    val file = element.containingFile?.virtualFile ?: return null
    return assistedSitesByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  fun contributionsForScopes(scopeKeys: Set<ClassId>): List<ContributionEntry> {
    if (scopeKeys.isEmpty()) return emptyList()
    return contributions.filter { contribution -> contribution.scopeKeys.any(scopeKeys::contains) }
  }

  fun graphsForScopes(scopeKeys: Set<ClassId>): List<KaGraphNode> {
    if (scopeKeys.isEmpty()) return emptyList()
    return graphs.filter { graph -> graph.scopeKeys.any(scopeKeys::contains) }
  }

  companion object {
    val EMPTY = BindingIndex(emptyList(), emptyList(), emptyList(), emptyList())
  }
}

/** Groups entries by their pointers' virtual files without dereferencing any PSI. */
private inline fun <T> List<T>.groupByFile(
  pointer: (T) -> SmartPsiElementPointer<*>
): Map<VirtualFile, List<T>> {
  val result = HashMap<VirtualFile, MutableList<T>>()
  for (entry in this) {
    val file = pointer(entry).virtualFile ?: continue
    result.getOrPut(file, ::mutableListOf) += entry
  }
  return result
}

/** The result of resolving a consumer against every graph in the project. */
internal class ConsumerResolution(
  /** Unfiltered project-wide candidates. */
  val global: List<KaBinding>,
  /** Graph-filtered candidates per graph that can resolve the consumer. */
  val perGraph: Map<KaGraphNode, List<KaBinding>>,
) {
  /** The deduplicated union of per-graph results, or [global] when no graph resolves it. */
  val effective: List<KaBinding> =
    if (perGraph.isEmpty()) global else perGraph.values.flatten().distinct()
}
