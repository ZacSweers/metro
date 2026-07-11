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
  private val replacedOriginsByContext = ConcurrentHashMap<GraphQueryContext, Set<ClassId>>()
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

  private val accessorsByGraph: ScatterMap<GraphDeclarationId, List<ConsumerEntry>> by lazy {
    consumers.groupToScatter { it.graphId }
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
    val useSiteModule = moduleFor(consumer.pointer.element)
    val resolutionScope = useSiteModule?.resolutionScope()
    return visibleBindingsFor(consumer, useSiteModule, resolutionScope)
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
    val visible =
      visibleBindingsFor(consumer, queryContext.graphModule, queryContext.resolutionScope)
    return applyReplaces(visible.filter { isBindingInContext(it, queryContext) })
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
    val global = visibleBindingsFor(consumer, consumerModule, consumerResolutionScope)
    if (graphs.isEmpty()) return ConsumerResolution(global, emptyMap(), hasGraphs = false)

    val perContext = LinkedHashMap<GraphContext, List<KaBinding>>()
    val visibleByModule = HashMap<KaModule, List<KaBinding>>()
    for (graph in graphs) {
      for (context in contextsFor(graph)) {
        val queryContext = queryContext(context) ?: continue
        if (!isConsumerInContext(consumer, queryContext)) continue
        val visible =
          visibleByModule.getOrPut(queryContext.graphModule) {
            visibleBindingsFor(
              consumer,
              queryContext.graphModule,
              queryContext.resolutionScope,
            )
          }
        perContext[context] = filterBindingsInContext(visible, queryContext)
      }
    }
    return ConsumerResolution(global, perContext, hasGraphs = true)
  }

  /**
   * Filters precomputed [visible] candidates to those live in [queryContext]'s graph. Consumer-site
   * membership is checked separately so applicable contexts remain represented when this returns an
   * empty binding list.
   */
  private fun filterBindingsInContext(
    visible: List<KaBinding>,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    return applyReplaces(visible.filter { isBindingInContext(it, queryContext) })
  }

  /**
   * The bindings for [key] that are members of [queryContext]'s graph. Multibinding contributions
   * are resolved separately by [multibindingContributions].
   */
  fun bindingsForKey(
    key: KaTypeKey,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    // Membership filtering already applies context-wide excludes and replaces via the cached
    // replacedOrigins set.
    return bindingsByKey[key].orEmpty().filter { isBindingInContext(it, queryContext) }
  }

  /** Contributions collected into [multibindingId] in [queryContext]'s graph. */
  fun multibindingContributions(
    multibindingId: String,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    return contributionsByMultibindingId[multibindingId].orEmpty().filter {
      isBindingInContext(it, queryContext)
    }
  }

  /**
   * Every binding that is a member of [queryContext]'s graph. Linear over all bindings, so call on
   * demand only.
   */
  fun bindingsInContext(queryContext: GraphQueryContext): List<KaBinding> {
    return bindings.filter { isBindingInContext(it, queryContext) }
  }

  /** The consumer sites declared on [graph] itself, used as seal roots. */
  fun accessorsFor(graph: KaGraphNode): List<ConsumerEntry> {
    return accessorsByGraph[graph.declarationId].orEmpty()
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
    val resolutionScope = graphModule.resolutionScope()
    val containers = containersFor(context, graphModule, resolutionScope)
    val queryContext = GraphQueryContext(context, graphModule, resolutionScope, containers)
    return graphQueryContexts.putIfAbsent(context, queryContext) ?: queryContext
  }

  /** Finds the current index's context for a path retained across an index rebuild. */
  fun findContext(path: GraphPath): GraphContext? {
    val graphSegment = path.segments.firstOrNull() ?: return null
    return graphs
      .asSequence()
      .filter { it.declarationId == graphSegment }
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
      it.classId !in context.excludes && isVisibleFrom(it, queryContext)
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
        isVisibleFrom(it, queryContext)
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
    val includedBindingContainers = chain.flatMapToSet { it.includedBindingContainers }
    val includedDependencies = chain.flatMapToSet { it.includedDependencies }
    val graphIds = chain.mapTo(mutableSetOf()) { it.declarationId }

    return GraphContext(
      chain = chain,
      scopes = scopes,
      scopingAnnotations = chain.flatMapToSet { it.scopingAnnotations },
      excludes = excludes,
      includedBindingContainers = includedBindingContainers,
      includedDependencies = includedDependencies,
      graphIds = graphIds,
      graphClassIds = graphClassIds,
    )
  }

  private fun containersFor(
    context: GraphContext,
    useSiteModule: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): Set<ClassId> {
    // Containers are declared on the graphs, contributed into scope, or transitively included.
    val containerRoots = context.chain.flatMapTo(hashSetOf()) { it.bindingContainers }
    for (containerKey in context.includedBindingContainers) {
      val containerId = containerKey.type.classId ?: continue
      containersById[containerId]?.includes?.forEach(containerRoots::add)
    }
    contributions
      .asSequence()
      .filter { it.classId != null && it.classId in containersById }
      .filter { it.scopeKeys.any(context.scopes::contains) && it.classId !in context.excludes }
      .filter {
        isVisibleFrom(it.pointer, it.hintAvailability, useSiteModule, resolutionScope)
      }
      .mapTo(containerRoots) { it.classId!! }

    val containers = hashSetOf<ClassId>()
    val queue = ArrayDeque(containerRoots)
    while (queue.isNotEmpty()) {
      val id = queue.removeFirst()
      if (!containers.add(id)) continue
      containersById[id]?.includes?.forEach(queue::add)
    }
    return containers
  }

  private fun visibleBindingsFor(
    consumer: ConsumerEntry,
    useSiteModule: KaModule?,
    resolutionScope: DeclarationResolutionScope?,
  ): List<KaBinding> {
    return candidateBindingsFor(consumer).filter {
      isVisibleFrom(it.pointer, it.hintAvailability, useSiteModule, resolutionScope)
    }
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
    if (
      !isVisibleFrom(
        consumer.pointer,
        hintAvailability = null,
        useSiteModule = queryContext.graphModule,
        resolutionScope = queryContext.resolutionScope,
      )
    ) {
      return false
    }
    val context = queryContext.graphContext
    val originClassId = consumer.originClassId
    if (originClassId != null) {
      if (originClassId in context.excludes) return false
      // A replaced origin's consumers stay live only while it still has surviving bindings
      if (
        originClassId in replacedOrigins(queryContext) &&
          !hasOriginBindingInContext(originClassId, queryContext)
      ) {
        return false
      }
    }

    val graphId = consumer.graphId
    if (graphId != null) return graphId in context.graphIds

    val includedContainerKey = consumer.includedContainerKey
    if (includedContainerKey != null) {
      return includedContainerKey in context.includedBindingContainers
    }

    val containerId = consumer.containerId
    if (containerId != null) {
      return containerId in context.graphClassIds || containerId in queryContext.containers
    }

    if (consumer.contributionScopes.isNotEmpty()) {
      return consumer.contributionScopes.any { it in context.scopes }
    }

    if (originClassId != null) {
      return hasOriginBindingInContext(originClassId, queryContext)
    }

    return true
  }

  private fun hasOriginBindingInContext(
    originClassId: ClassId,
    queryContext: GraphQueryContext,
  ): Boolean {
    return bindings.any { binding ->
      binding.originClassId == originClassId && isBindingInContext(binding, queryContext)
    }
  }

  private fun isBindingInContext(entry: KaBinding, queryContext: GraphQueryContext): Boolean {
    if (!isBindingCandidateInContext(entry, queryContext)) return false
    // Replaces removes the origin's contributions only; its own injectable type stays available
    // (a replacing stub can inject the replaced implementation directly).
    if (entry.contributionScopes.isEmpty()) return true
    val originClassId = entry.originClassId ?: return true
    return originClassId !in replacedOrigins(queryContext)
  }

  private fun isBindingCandidateInContext(
    entry: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean {
    if (!isVisibleFrom(entry, queryContext)) return false
    val context = queryContext.graphContext
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
        val includedContainerKey = entry.includedContainerKey
        if (includedContainerKey != null) {
          includedContainerKey in context.includedBindingContainers
        } else {
          entry.contributionScopes.isNotEmpty() ||
            entry.containerId == null ||
            entry.containerId in context.graphClassIds ||
            entry.containerId in queryContext.containers
        }
      }
      is KaBinding.BoundInstance -> {
        if (entry.isGraphInput) {
          entry.typeKey in context.includedDependencies
        } else {
          entry.containerId in context.graphClassIds
        }
      }
      is KaBinding.GraphDependency -> entry.ownerKey in context.includedDependencies
      // Injected classes and assisted factories are implicit bindings. Graph instances are
      // seal-time nodes that never appear in the index.
      is KaBinding.ConstructorInjected,
      is KaBinding.AssistedFactory,
      is KaBinding.GraphInstance -> true
    }
  }

  private fun replacedOrigins(queryContext: GraphQueryContext): Set<ClassId> {
    return replacedOriginsByContext.computeIfAbsent(queryContext) {
      bindings
        .asSequence()
        .filter { binding -> isBindingCandidateInContext(binding, queryContext) }
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
    return graphs.firstOrNull { it.declarationId == graph.declarationId }
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
  /** Candidates visible from the consumer's module. */
  val global: List<KaBinding>,
  /** Graph-filtered candidates for every concrete parent path containing the consumer. */
  val perContext: Map<GraphContext, List<KaBinding>>,
  hasGraphs: Boolean,
) {
  /** Bindings available in at least one applicable context, retained for navigation. */
  val candidateBindings: List<KaBinding>

  /**
   * Bindings shared by every applicable graph context, or [global] when the index has no graphs.
   * `null` means the contexts produce different binding sets; an empty list means no binding was
   * found in any applicable context.
   */
  val uniformBindings: List<KaBinding>?

  /** Applicable graph contexts where no binding was found. */
  val emptyContexts: Set<GraphContext>

  init {
    if (!hasGraphs) {
      candidateBindings = global
      uniformBindings = global
      emptyContexts = emptySet()
    } else {
      candidateBindings = perContext.values.flatten().distinct()
      emptyContexts = perContext.filterValues { it.isEmpty() }.keys
      val firstBindings = perContext.values.firstOrNull()?.distinct().orEmpty()
      val firstBindingSet = firstBindings.toSet()
      val contextsAgree =
        perContext.isNotEmpty() && perContext.values.all { it.toSet() == firstBindingSet }
      uniformBindings = if (perContext.isEmpty() || contextsAgree) firstBindings else null
    }
  }
}

private fun moduleFor(element: KtElement?): KaModule? {
  return element?.let { KaModuleProvider.getModule(it.project, it, useSiteModule = null) }
}

private fun isVisibleFrom(
  pointer: SmartPsiElementPointer<*>,
  hintAvailability: HintAvailability?,
  useSiteModule: KaModule?,
  resolutionScope: DeclarationResolutionScope?,
): Boolean {
  if (hintAvailability != null) {
    if (useSiteModule == null || !hintAvailability.isVisibleFrom(useSiteModule)) return false
  }
  if (resolutionScope == null) return true
  val element = pointer.element ?: return true
  return resolutionScope.contains(element)
}

private fun isVisibleFrom(entry: KaBinding, queryContext: GraphQueryContext): Boolean {
  return isVisibleFrom(
    entry.pointer,
    entry.hintAvailability,
    queryContext.graphModule,
    queryContext.resolutionScope,
  )
}

private fun isVisibleFrom(entry: ContributionEntry, queryContext: GraphQueryContext): Boolean {
  return isVisibleFrom(
    entry.pointer,
    entry.hintAvailability,
    queryContext.graphModule,
    queryContext.resolutionScope,
  )
}

@OptIn(KaPlatformInterface::class)
private fun KaModule.resolutionScope(): DeclarationResolutionScope {
  val resolutionScope = KaResolutionScope.forModule(this)
  return DeclarationResolutionScope(resolutionScope::contains)
}
