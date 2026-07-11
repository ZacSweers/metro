// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.graph.applyExcludesAndReplaces
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
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
  private val graphQueryContexts = ConcurrentHashMap<GraphContext, GraphQueryContext>()
  private val replacedOriginsByContext = ConcurrentHashMap<GraphContext, Set<ClassId>>()
  private val consumerResolutions = ConcurrentHashMap<ConsumerEntry, ConsumerResolution>()

  // Contributions are keyed solely by multibindingId, mirroring the compiler's
  // @MultibindingElement qualifier swap. Their element key must not satisfy plain consumers.
  private val bindingsByKey: ScatterMap<KaTypeKey, List<KaBinding>> by lazy {
    bindings.groupToScatter { binding ->
      binding.typeKey.takeIf { binding.multibindingId == null }
    }
  }

  private val consumersByKey: ScatterMap<KaTypeKey, List<ConsumerEntry>> by lazy {
    consumers.groupToScatter { it.key }
  }

  private val contributionsByMultibindingId: ScatterMap<String, List<KaBinding>> by lazy {
    bindings.groupToScatter { it.multibindingId }
  }

  private val consumersByMultibindingId: ScatterMap<String, List<ConsumerEntry>> by lazy {
    consumers.groupToScatter { it.multibindingId }
  }

  // PSI-identity lookups for editor features classifying the element under the caret/pass.
  // Bucketed by the pointers' virtual files (no PSI dereference) so the index never pins PSI
  // project-wide; only the queried file's bucket dereferences its pointers. Must be accessed in
  // a read action.
  private val bindingsByFile: ScatterMap<VirtualFile, List<KaBinding>> by lazy {
    bindings.groupToScatter { it.pointer.virtualFile }
  }

  private val consumersByFile: ScatterMap<VirtualFile, List<ConsumerEntry>> by lazy {
    consumers.groupToScatter { it.pointer.virtualFile }
  }

  private val graphsByFile: ScatterMap<VirtualFile, List<KaGraphNode>> by lazy {
    graphs.groupToScatter { it.pointer.virtualFile }
  }

  private val assistedSitesByFile: ScatterMap<VirtualFile, List<AssistedSite>> by lazy {
    assistedSites.groupToScatter { it.pointer.virtualFile }
  }

  /**
   * Bindings satisfying [consumer]: direct key matches plus, for `Set`/`Map` aggregate sites, the
   * multibinding contributions collected into them.
   */
  fun bindingsFor(consumer: ConsumerEntry): List<KaBinding> {
    return candidateBindingsFor(consumer)
  }

  /**
   * The bindings for [consumer]'s key that are members of [queryContext]'s graph. This is a
   * binding-membership query: it does not constrain by whether [consumer]'s own site belongs to the
   * graph (that is [resolveConsumer]'s job), so a consumer can probe any query context.
   */
  fun bindingsFor(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    val visible = visibleBindingsFor(consumer, queryContext.resolutionScope)
    val context = queryContext.graphContext
    return applyReplaces(visible.filter { isBindingInContext(it, context) })
  }

  /**
   * Per-context resolution of [consumer]: which bindings satisfy it in each concrete graph path,
   * plus the use-site-visible candidates as a fallback for files/projects without graphs.
   */
  fun resolveConsumer(consumer: ConsumerEntry): ConsumerResolution {
    return consumerResolutions.computeIfAbsent(consumer, ::buildConsumerResolution)
  }

  private fun buildConsumerResolution(consumer: ConsumerEntry): ConsumerResolution {
    val consumerModule = moduleFor(consumer.pointer.element)
    val consumerResolutionScope = consumerModule?.resolutionScope()
    val global = visibleBindingsFor(consumer, consumerResolutionScope)
    if (graphs.isEmpty()) return ConsumerResolution(global, emptyMap(), hasGraphs = false)

    val perContext = LinkedHashMap<GraphContext, List<KaBinding>>()
    val visibleByModule = HashMap<KaModule, List<KaBinding>>()
    for (graph in graphs) {
      for (context in contextsFor(graph)) {
        val queryContext = queryContext(context) ?: continue
        val visible =
          visibleByModule.getOrPut(queryContext.graphModule) {
            visibleBindingsFor(consumer, queryContext.resolutionScope)
          }
        val filtered = bindingsInContext(visible, consumer, queryContext)
        if (filtered.isNotEmpty()) {
          perContext[context] = filtered
        }
      }
    }
    return ConsumerResolution(global, perContext, hasGraphs = true)
  }

  /**
   * Filters precomputed [visible] candidates to those live in [queryContext]'s graph, gating on
   * whether [consumer]'s site itself resolves in that graph. Used by [resolveConsumer]'s
   * per-context pass; the binding-membership probe [bindingsFor] deliberately skips the
   * consumer-site gate.
   */
  private fun bindingsInContext(
    visible: List<KaBinding>,
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    if (!isConsumerInContext(consumer, queryContext)) return emptyList()
    val context = queryContext.graphContext
    return applyReplaces(visible.filter { isBindingInContext(it, context) })
  }

  /**
   * The bindings for [key] that are members of [queryContext]'s graph. Multibinding contributions
   * are resolved separately by [multibindingContributions].
   */
  fun bindingsForKey(
    key: KaTypeKey,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    val context = queryContext.graphContext
    // Membership filtering already applies context-wide excludes and replaces via the cached
    // replacedOrigins set.
    return bindingsByKey[key].orEmpty().filter {
      isVisibleFrom(it.pointer, queryContext.resolutionScope) && isBindingInContext(it, context)
    }
  }

  /** Contributions collected into [multibindingId] in [queryContext]'s graph. */
  fun multibindingContributions(
    multibindingId: String,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    val context = queryContext.graphContext
    return contributionsByMultibindingId[multibindingId].orEmpty().filter {
      isVisibleFrom(it.pointer, queryContext.resolutionScope) && isBindingInContext(it, context)
    }
  }

  /**
   * Every binding that is a member of [queryContext]'s graph. Linear over all bindings, so call on
   * demand only.
   */
  fun bindingsInContext(queryContext: GraphQueryContext): List<KaBinding> {
    val context = queryContext.graphContext
    return bindings.filter {
      isVisibleFrom(it.pointer, queryContext.resolutionScope) && isBindingInContext(it, context)
    }
  }

  /** The consumer sites declared on [graph] itself, used as seal roots. */
  fun accessorsFor(graph: KaGraphNode): List<ConsumerEntry> {
    val graphClassId = graph.classId ?: return emptyList()
    return consumers.filter { it.graphClassId == graphClassId }
  }

  /** The extension graphs created by [graph]'s accessors. */
  fun extensionsOf(graph: KaGraphNode): List<KaGraphNode> {
    if (graph.extensionCreationIds.isEmpty()) return emptyList()
    return graphs.filter { candidate ->
      candidate.isExtension && candidate.selfIds.any { it in graph.extensionCreationIds }
    }
  }

  /** Every valid aggregation context for [graph]. Extensions can have multiple parent paths. */
  fun contextsFor(graph: KaGraphNode): List<GraphContext> {
    return graphContexts.computeIfAbsent(graph) { buildContexts(it) }
  }

  /** Builds the module-aware query view for [context], or null if its graph disappeared. */
  fun queryContext(context: GraphContext): GraphQueryContext? {
    graphQueryContexts[context]?.let {
      return it
    }
    val graphElement = context.rootGraph.pointer.element ?: return null
    val graphModule = moduleFor(graphElement) ?: return null
    val queryContext = GraphQueryContext(context, graphModule, graphModule.resolutionScope())
    return graphQueryContexts.putIfAbsent(context, queryContext) ?: queryContext
  }

  /** Finds the current index's context for a path retained across an index rebuild. */
  fun findContext(path: GraphPath): GraphContext? {
    val graphSegment = path.segments.firstOrNull() ?: return null
    return graphs
      .asSequence()
      .filter {
        it.classId == graphSegment.classId && it.pointer.virtualFile == graphSegment.file
      }
      .flatMap { contextsFor(it).asSequence() }
      .firstOrNull { it.path == path }
  }

  /** Concrete child contexts created directly from [parent]'s exact graph path. */
  fun extensionContextsOf(parent: GraphContext): List<GraphContext> {
    return extensionsOf(parent.graph).flatMap { extension ->
      contextsFor(extension).filter { child -> child.chain.drop(1) == parent.chain }
    }
  }

  /**
   * Contributions aggregated by [queryContext]'s graph itself: matched against the graph's own
   * aggregation scopes, minus excluded. Contributions a graph extension sees through its parent
   * chain are reported separately by [inheritedContributionsFor].
   */
  fun contributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    val context = queryContext.graphContext
    return contributionsForScopes(context.graph.scopeKeys).filter {
      it.classId !in context.excludes && isVisibleFrom(it.pointer, queryContext.resolutionScope)
    }
  }

  /**
   * Contributions [queryContext]'s graph receives from its parent chain rather than aggregating
   * itself: matched against ancestor scopes only, minus excluded. Empty for non-extension graphs.
   */
  fun inheritedContributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    val context = queryContext.graphContext
    val inheritedScopes = context.scopes - context.graph.scopeKeys
    return contributionsForScopes(inheritedScopes).filter {
      it.classId !in context.excludes &&
        it.scopeKeys.none(context.graph.scopeKeys::contains) &&
        isVisibleFrom(it.pointer, queryContext.resolutionScope)
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
      candidate !in visited && candidate.extensionCreationIds.any { it in graph.selfIds }
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
    val scopes = chain.flatMapToSet { it.scopeKeys }
    val excludes = chain.flatMapToSet { it.excludes }
    // Supertype members merge into the graph, so their classes gate membership like the graph
    val graphClassIds = chain.flatMapToSet { it.selfIds + it.supertypeIds }
    val includedDependencies = chain.flatMapToSet { it.includedDependencies }

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
      scopingAnnotations = chain.flatMapToSet { it.scopingAnnotations },
      excludes = excludes,
      containers = containers,
      includedDependencies = includedDependencies,
      graphClassIds = graphClassIds,
    )
  }

  private fun visibleBindingsFor(
    consumer: ConsumerEntry,
    resolutionScope: DeclarationResolutionScope?,
  ): List<KaBinding> {
    return candidateBindingsFor(consumer).filter { isVisibleFrom(it.pointer, resolutionScope) }
  }

  private fun candidateBindingsFor(consumer: ConsumerEntry): List<KaBinding> {
    val direct = bindingsByKey[consumer.key].orEmpty()
    val contributions = consumer.multibindingId?.let { contributionsByMultibindingId[it] }.orEmpty()
    return direct + contributions
  }

  private fun isConsumerInContext(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): Boolean {
    if (!isVisibleFrom(consumer.pointer, queryContext.resolutionScope)) return false
    val context = queryContext.graphContext
    val originClassId = consumer.originClassId
    if (originClassId != null) {
      if (originClassId in context.excludes) return false
      // A replaced origin's consumers stay live only while it still has surviving bindings
      if (
        originClassId in replacedOrigins(context) &&
          !hasOriginBindingInContext(originClassId, context)
      ) {
        return false
      }
    }

    val graphClassId = consumer.graphClassId
    if (graphClassId != null) return graphClassId in context.graphClassIds

    val containerId = consumer.containerId
    if (containerId != null) {
      return containerId in context.graphClassIds || containerId in context.containers
    }

    if (consumer.contributionScopes.isNotEmpty()) {
      return consumer.contributionScopes.any { it in context.scopes }
    }

    if (originClassId != null) {
      return hasOriginBindingInContext(originClassId, context)
    }

    return true
  }

  private fun hasOriginBindingInContext(originClassId: ClassId, context: GraphContext): Boolean {
    return bindings.any { binding ->
      binding.originClassId == originClassId && isBindingInContext(binding, context)
    }
  }

  private fun isBindingInContext(entry: KaBinding, context: GraphContext): Boolean {
    if (!isBindingCandidateInContext(entry, context)) return false
    // Replaces removes the origin's contributions only; its own injectable type stays available
    // (a replacing stub can inject the replaced implementation directly).
    if (entry.contributionScopes.isEmpty()) return true
    val originClassId = entry.originClassId ?: return true
    return originClassId !in replacedOrigins(context)
  }

  private fun isBindingCandidateInContext(entry: KaBinding, context: GraphContext): Boolean {
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
    return when (entry) {
      // Container callables are only live in graphs that wire their container in (or that
      // declare them directly on the graph). Contributed bindings pass via their scopes.
      is KaBinding.Provided,
      is KaBinding.Alias,
      is KaBinding.Multibinding,
      is KaBinding.CustomWrapper -> {
        entry.contributionScopes.isNotEmpty() ||
          entry.containerId == null ||
          entry.containerId in context.graphClassIds ||
          entry.containerId in context.containers
      }
      is KaBinding.BoundInstance -> entry.containerId in context.graphClassIds
      is KaBinding.GraphDependency -> entry.containerId in context.includedDependencies
      // Injected classes and assisted factories are implicit bindings. Graph instances are
      // seal-time nodes that never appear in the index.
      is KaBinding.ConstructorInjected,
      is KaBinding.AssistedFactory,
      is KaBinding.GraphInstance -> true
    }
  }

  private fun replacedOrigins(context: GraphContext): Set<ClassId> {
    return replacedOriginsByContext.computeIfAbsent(context) {
      bindings
        .asSequence()
        .filter { binding -> isBindingCandidateInContext(binding, context) }
        .flatMap { binding -> binding.replaces.asSequence() }
        .toSet()
    }
  }

  /** Drops bindings replaced by other surviving contributions, via the shared merge engine. */
  private fun applyReplaces(entries: List<KaBinding>): List<KaBinding> {
    return applyExcludesAndReplaces(entries)
  }

  /** Sites consuming any of [bindingEntries], joining multibinding contributions by id. */
  fun consumersFor(bindingEntries: Collection<KaBinding>): List<ConsumerEntry> {
    val bindingSet = bindingEntries.toSet()
    val result = LinkedHashSet<ConsumerEntry>()
    val candidates = LinkedHashSet<ConsumerEntry>()
    for (entry in bindingSet) {
      val multibindingId = entry.multibindingId
      if (multibindingId != null) {
        candidates += consumersByMultibindingId[multibindingId].orEmpty()
      } else {
        candidates += consumersByKey[entry.typeKey].orEmpty()
      }
    }
    if (graphs.isEmpty()) return candidates.toList()

    for (consumer in candidates) {
      val resolution = resolveConsumer(consumer)
      val resolvesToEntry =
        resolution.perContext.values.any { contextBindings ->
          contextBindings.any { it in bindingSet }
        }
      if (resolvesToEntry) {
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

  /** All consumer entries anchored at [element]. Injector members anchor one per injected key. */
  fun consumerEntriesAt(element: KtElement): List<ConsumerEntry> {
    val file = element.containingFile?.virtualFile ?: return emptyList()
    return consumersByFile[file].orEmpty().filter { it.pointer.element === element }
  }

  fun graphEntryAt(element: KtElement): KaGraphNode? {
    val file = element.containingFile?.virtualFile ?: return null
    return graphsByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  /** Refreshes a retained graph declaration against this index. */
  fun graphFor(graph: KaGraphNode): KaGraphNode? {
    val classId = graph.classId ?: return graphs.firstOrNull { it === graph }
    val file = graph.pointer.virtualFile
    return graphs.firstOrNull { it.classId == classId && it.pointer.virtualFile == file }
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

/**
 * Groups entries into a memory-compact ScatterMap, skipping entries whose key is null. These maps
 * live for the whole index lifetime, so the per-entry savings over HashMap add up.
 */
private inline fun <T, K : Any> List<T>.groupToScatter(keyOf: (T) -> K?): ScatterMap<K, List<T>> {
  val result = MutableScatterMap<K, MutableList<T>>()
  for (entry in this) {
    val key = keyOf(entry) ?: continue
    result.getOrPut(key, ::mutableListOf) += entry
  }
  @Suppress("UNCHECKED_CAST")
  return result as ScatterMap<K, List<T>>
}

/** The result of resolving a consumer against every concrete graph context in the project. */
internal class ConsumerResolution(
  /** Unfiltered project-wide candidates. */
  val global: List<KaBinding>,
  /** Graph-filtered candidates per concrete parent path that can resolve the consumer. */
  val perContext: Map<GraphContext, List<KaBinding>>,
  private val hasGraphs: Boolean,
) {
  /** The deduplicated union of context results, or [global] for graphless files/projects. */
  val effective: List<KaBinding> = if (hasGraphs) perContext.values.flatten().distinct() else global
}

private fun moduleFor(element: KtElement?): KaModule? {
  return element?.let { KaModuleProvider.getModule(it.project, it, useSiteModule = null) }
}

private fun isVisibleFrom(
  pointer: SmartPsiElementPointer<*>,
  resolutionScope: DeclarationResolutionScope?,
): Boolean {
  if (resolutionScope == null) return true
  val element = pointer.element ?: return true
  return resolutionScope.contains(element)
}

@OptIn(KaPlatformInterface::class)
private fun KaModule.resolutionScope(): DeclarationResolutionScope {
  val resolutionScope = KaResolutionScope.forModule(this)
  return DeclarationResolutionScope(resolutionScope::contains)
}
