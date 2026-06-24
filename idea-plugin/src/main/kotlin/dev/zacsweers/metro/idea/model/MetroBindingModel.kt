// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement

internal enum class MetroProviderKind(val label: String) {
  /** A `@Provides` callable. */
  PROVIDES("provides"),
  /** A `@Binds` callable. */
  BINDS("binds"),
  /** An injected class providing its own type. */
  INJECT("injected class"),
  /** A `@ContributesBinding`-style class bound to a supertype. */
  CONTRIBUTED("contributed binding"),
  /** An `@IntoSet`/`@ContributesIntoSet`-style multibinding contribution. */
  MULTIBINDING_CONTRIBUTION("multibinding contribution"),
  /** A `@Multibinds` declaration. */
  MULTIBINDING_DECLARATION("multibinding declaration"),
  /** An instance binding from a graph factory `@Provides` parameter. */
  INSTANCE("instance binding"),
  /** An `@AssistedFactory` providing its own type. */
  ASSISTED_FACTORY("assisted factory"),
  /** An accessor of an `@Includes` graph dependency. */
  INCLUDED("included dependency accessor"),
}

/**
 * A declaration that originates a binding for [key]. The pointer usually targets a source
 * [KtElement], but may target a decompiled library declaration for externally-resolved inject
 * classes.
 */
internal class MetroProviderEntry(
  val pointer: SmartPsiElementPointer<out PsiElement>,
  val key: KaTypeKey,
  val kind: MetroProviderKind,
  /** Scope annotation, e.g. `@SingleIn(AppScope::class)`, if present. */
  val scope: MetroKaAnnotation?,
  /**
   * Short name of the concrete implementation backing this binding when it differs from the key
   * type (e.g. the bound impl class of a `@Binds` or `@ContributesBinding`).
   */
  val implementationName: String?,
  /**
   * For multibinding contributions, the aggregate binding id this element belongs to, mirroring the
   * compiler's `@MultibindingElement(bindingId, ...)` qualifier: the rendered element key for sets,
   * prefixed with the map key type for maps. [key] stays the element key as declared.
   */
  val multibindingId: String? = null,
  /** The contributed/injected class a binding originates from, for excludes/replaces matching. */
  val originClassId: ClassId? = null,
  /**
   * The class whose graph membership gates this binding: the containing binding container for
   * `@Provides`/`@Binds` callables, the owning graph for instance bindings, or the dependency type
   * for included accessors. Null for membership-free bindings (injected classes).
   */
  val containerId: ClassId? = null,
  /** Contribution classes this binding replaces in graphs where both are aggregated. */
  val replaces: Set<ClassId> = emptySet(),
  /** Scopes this binding is contributed to; empty for non-contributed bindings. */
  val contributionScopes: Set<ClassId> = emptySet(),
)

/** A site that consumes a binding for [key]: an injected parameter/property or graph accessor. */
internal class MetroConsumerEntry(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val key: KaTypeKey,
  /** Whether the declared type is an interface or abstract class (drives implementation inlays). */
  val isAbstractType: Boolean = false,
  /** For `Set`/`Map` aggregate sites, the multibinding id collecting contributed elements. */
  val multibindingId: String? = null,
  /** The consumed type's class, when it is a class type. Used to resolve library inject classes. */
  val typeClassId: ClassId? = null,
)

/**
 * A parameter supplied at runtime rather than injected from the graph: `@Assisted` parameters and
 * Circuit-provided types (`Screen`, `Navigator`, etc.) on `@CircuitInject` declarations.
 */
internal class MetroAssistedSite(
  val pointer: SmartPsiElementPointer<out KtElement>,
  /** Short description of what supplies the value, e.g. `@Assisted` or `Circuit`. */
  val supplier: String,
  /**
   * True when nothing in the source marks the parameter as assisted (e.g. Circuit-provided types),
   * as opposed to an explicit `@Assisted` annotation. Implicit sites get an `assisted` inlay;
   * explicit ones don't need a second marker.
   */
  val isImplicit: Boolean,
)

/** A `@DependencyGraph`/`@GraphExtension`-annotated class and its aggregation metadata. */
internal class MetroGraphEntry(
  val pointer: SmartPsiElementPointer<KtClassOrObject>,
  val scopeKeys: Set<ClassId>,
  val classId: ClassId? = null,
  /** Contribution classes excluded via the graph annotation's `excludes`. */
  val excludes: Set<ClassId> = emptySet(),
  /** Binding containers wired via the graph annotation's `bindingContainers`. */
  val bindingContainers: Set<ClassId> = emptySet(),
  /** Graph dependencies wired via factory `@Includes` parameters. */
  val includedDependencies: Set<ClassId> = emptySet(),
  /** True for `@GraphExtension` declarations, which inherit their parent graphs' bindings. */
  val isExtension: Boolean = false,
  /** This graph's class plus nested factory classes, used for parent/extension matching. */
  val selfIds: Set<ClassId> = emptySet(),
  /** Class ids referenced by this graph's accessors, used to find extensions it instantiates. */
  val accessorTypeIds: Set<ClassId> = emptySet(),
  /**
   * The scope annotations this graph declares: explicit scope annotations on the class plus the
   * implicit `@SingleIn(X::class)` conveyed by each aggregation scope in the graph annotation
   * (`@DependencyGraph(AppScope::class)` implies `@SingleIn(AppScope::class)`). Scoped bindings are
   * only members of graphs whose declared scopes include theirs.
   */
  val scopingAnnotations: Set<MetroKaAnnotation> = emptySet(),
) {
  val name: String?
    get() = classId?.shortClassName?.asString()
}

/** A `@BindingContainer`-annotated class and the containers it transitively includes. */
internal class MetroBindingContainerEntry(val classId: ClassId, val includes: Set<ClassId>)

/**
 * The aggregated view a single graph (plus its parent chain, for extensions) has of the project:
 * the inputs to per-graph binding membership.
 */
internal class MetroGraphContext(
  /** The graph itself followed by its parent chain, nearest first. */
  val chain: List<MetroGraphEntry>,
  val scopes: Set<ClassId>,
  /** Declared scope annotations across the chain, gating scoped-binding membership. */
  val scopingAnnotations: Set<MetroKaAnnotation>,
  val excludes: Set<ClassId>,
  /** Transitively expanded binding containers, including contributed ones. */
  val containers: Set<ClassId>,
  val includedDependencies: Set<ClassId>,
  val graphClassIds: Set<ClassId>,
) {
  val graph: MetroGraphEntry
    get() = chain.first()
}

/**
 * A declaration contributing to aggregation scopes: a `@Contributes*`-annotated class or a
 * `@CircuitInject`-annotated declaration whose generated factory is contributed.
 */
internal class MetroContributionEntry(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val scopeKeys: Set<ClassId>,
  val classId: ClassId? = null,
)

/**
 * Project-wide snapshot of Metro declarations, built from stub indexes + the Analysis API.
 *
 * Resolution starts with project-wide key matches, then filters those candidates through each
 * graph's aggregation context for editor features that need graph membership.
 */
internal class MetroBindingIndex(
  val providers: List<MetroProviderEntry>,
  val consumers: List<MetroConsumerEntry>,
  val graphs: List<MetroGraphEntry>,
  val contributions: List<MetroContributionEntry>,
  val assistedSites: List<MetroAssistedSite> = emptyList(),
  val bindingContainers: List<MetroBindingContainerEntry> = emptyList(),
) {
  private val containersById: Map<ClassId, MetroBindingContainerEntry> by lazy {
    bindingContainers.associateBy { it.classId }
  }
  private val graphContexts = ConcurrentHashMap<MetroGraphEntry, List<MetroGraphContext>>()
  // Contributions are keyed solely by multibindingId, mirroring the compiler's
  // @MultibindingElement qualifier swap — their element key must not satisfy plain consumers.
  private val providersByKey: Map<KaTypeKey, List<MetroProviderEntry>> by lazy {
    providers.filter { it.multibindingId == null }.groupBy { it.key }
  }
  private val consumersByKey: Map<KaTypeKey, List<MetroConsumerEntry>> by lazy {
    consumers.groupBy { it.key }
  }
  private val contributionsByMultibindingId: Map<String, List<MetroProviderEntry>> by lazy {
    providers.filter { it.multibindingId != null }.groupBy { it.multibindingId!! }
  }
  private val consumersByMultibindingId: Map<String, List<MetroConsumerEntry>> by lazy {
    consumers.filter { it.multibindingId != null }.groupBy { it.multibindingId!! }
  }

  // PSI-identity lookups for editor features classifying the element under the caret/pass.
  // Bucketed by the pointers' virtual files (no PSI dereference) so the index never pins PSI
  // project-wide; only the queried file's bucket dereferences its pointers. Must be accessed in
  // a read action.
  private val providersByFile: Map<VirtualFile, List<MetroProviderEntry>> by lazy {
    providers.groupByFile { it.pointer }
  }
  private val consumersByFile: Map<VirtualFile, List<MetroConsumerEntry>> by lazy {
    consumers.groupByFile { it.pointer }
  }
  private val graphsByFile: Map<VirtualFile, List<MetroGraphEntry>> by lazy {
    graphs.groupByFile { it.pointer }
  }
  private val assistedSitesByFile: Map<VirtualFile, List<MetroAssistedSite>> by lazy {
    assistedSites.groupByFile { it.pointer }
  }

  /**
   * Bindings satisfying [consumer]: direct key matches plus, for `Set`/`Map` aggregate sites, the
   * multibinding contributions collected into them.
   */
  fun providersFor(consumer: MetroConsumerEntry): List<MetroProviderEntry> {
    val direct = providersByKey[consumer.key].orEmpty()
    val contributions = consumer.multibindingId?.let { contributionsByMultibindingId[it] }.orEmpty()
    return direct + contributions
  }

  /** [providersFor] filtered to bindings that are members of [context]'s graph. */
  fun providersFor(
    consumer: MetroConsumerEntry,
    context: MetroGraphContext,
  ): List<MetroProviderEntry> {
    return applyReplaces(providersFor(consumer).filter { isInContext(it, context) })
  }

  /**
   * Per-graph resolution of [consumer]: which bindings satisfy it in each graph that can resolve
   * it, plus the unfiltered project-wide candidates as a fallback for projects without graphs.
   */
  fun resolveConsumer(consumer: MetroConsumerEntry): MetroConsumerResolution {
    val global = providersFor(consumer)
    if (global.isEmpty() || graphs.isEmpty()) {
      return MetroConsumerResolution(global, emptyMap())
    }
    val perGraph = LinkedHashMap<MetroGraphEntry, List<MetroProviderEntry>>()
    for (graph in graphs) {
      val filtered =
        contextsFor(graph).flatMap { context -> providersFor(consumer, context) }.distinct()
      if (filtered.isNotEmpty()) {
        perGraph[graph] = filtered
      }
    }
    return MetroConsumerResolution(global, perGraph)
  }

  /** The aggregated context of [graph], following extension parent chains. */
  fun contextFor(graph: MetroGraphEntry): MetroGraphContext {
    val contexts = contextsFor(graph)
    if (contexts.size == 1) return contexts.single()

    val orderedChain = contexts.flatMap { it.chain }.distinct()
    val chain =
      if (orderedChain.firstOrNull() == graph) {
        orderedChain
      } else {
        listOf(graph) + orderedChain.filter { it != graph }
      }
    return MetroGraphContext(
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
  fun contextsFor(graph: MetroGraphEntry): List<MetroGraphContext> {
    return graphContexts.computeIfAbsent(graph) { buildContexts(it) }
  }

  /**
   * Contributions aggregated by [context]'s graph itself: matched against the graph's own
   * aggregation scopes, minus excluded. Contributions a graph extension sees through its parent
   * chain are reported separately by [inheritedContributionsFor].
   */
  fun contributionsFor(context: MetroGraphContext): List<MetroContributionEntry> {
    return contributionsForScopes(context.graph.scopeKeys).filter {
      it.classId !in context.excludes
    }
  }

  /**
   * Contributions [context]'s graph receives from its parent chain rather than aggregating itself:
   * matched against ancestor scopes only, minus excluded. Empty for non-extension graphs.
   */
  fun inheritedContributionsFor(context: MetroGraphContext): List<MetroContributionEntry> {
    val inheritedScopes = context.scopes - context.graph.scopeKeys
    return contributionsForScopes(inheritedScopes).filter {
      it.classId !in context.excludes && it.scopeKeys.none(context.graph.scopeKeys::contains)
    }
  }

  private fun buildContexts(graph: MetroGraphEntry): List<MetroGraphContext> {
    return buildChains(graph, visited = setOf(graph)).map(::buildContext)
  }

  private fun buildChains(
    graph: MetroGraphEntry,
    visited: Set<MetroGraphEntry>,
  ): List<List<MetroGraphEntry>> {
    if (!graph.isExtension) return listOf(listOf(graph))

    val parents = graphs.filter { candidate ->
      candidate !in visited && candidate.accessorTypeIds.any { it in graph.selfIds }
    }
    if (parents.isEmpty()) return listOf(listOf(graph))

    val chains = mutableListOf<List<MetroGraphEntry>>()
    for (parent in parents) {
      val parentChains = buildChains(parent, visited + parent)
      for (parentChain in parentChains) {
        chains += listOf(graph) + parentChain
      }
    }
    return chains
  }

  private fun buildContext(chain: List<MetroGraphEntry>): MetroGraphContext {
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
    return MetroGraphContext(
      chain = chain,
      scopes = scopes,
      scopingAnnotations = chain.flatMapTo(hashSetOf()) { it.scopingAnnotations },
      excludes = excludes,
      containers = containers,
      includedDependencies = includedDependencies,
      graphClassIds = graphClassIds,
    )
  }

  private fun isInContext(entry: MetroProviderEntry, context: MetroGraphContext): Boolean {
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
      MetroProviderKind.PROVIDES,
      MetroProviderKind.BINDS,
      MetroProviderKind.MULTIBINDING_DECLARATION -> {
        entry.contributionScopes.isNotEmpty() ||
          entry.containerId == null ||
          entry.containerId in context.graphClassIds ||
          entry.containerId in context.containers
      }
      MetroProviderKind.INSTANCE -> entry.containerId in context.graphClassIds
      MetroProviderKind.INCLUDED -> entry.containerId in context.includedDependencies
      // Injected classes are implicit bindings; contributed bindings already passed the
      // scope/excludes checks above
      else -> true
    }
  }

  /** Drops bindings replaced by other surviving contributions, mirroring compiler aggregation. */
  private fun applyReplaces(entries: List<MetroProviderEntry>): List<MetroProviderEntry> {
    if (entries.size < 2) return entries
    val replaced = entries.flatMapTo(hashSetOf()) { it.replaces }
    if (replaced.isEmpty()) return entries
    return entries.filter { it.originClassId == null || it.originClassId !in replaced }
  }

  /** Sites consuming any of [providerEntries], joining multibinding contributions by id. */
  fun consumersFor(providerEntries: Collection<MetroProviderEntry>): List<MetroConsumerEntry> {
    val providerSet = providerEntries.toSet()
    val result = LinkedHashSet<MetroConsumerEntry>()
    val candidates = LinkedHashSet<MetroConsumerEntry>()
    for (entry in providerSet) {
      if (entry.multibindingId != null) {
        candidates += consumersByMultibindingId[entry.multibindingId].orEmpty()
      } else {
        candidates += consumersByKey[entry.key].orEmpty()
      }
    }
    if (graphs.isEmpty()) return candidates.toList()

    for (consumer in candidates) {
      val resolution = resolveConsumer(consumer)
      val graphProviders = resolution.perGraph.values.flatten()
      if (graphProviders.any { it in providerSet }) {
        result += consumer
      }
    }
    return result.toList()
  }

  fun providerEntriesAt(element: KtElement): List<MetroProviderEntry> {
    val file = element.containingFile?.virtualFile ?: return emptyList()
    return providersByFile[file].orEmpty().filter { it.pointer.element === element }
  }

  fun consumerEntryAt(element: KtElement): MetroConsumerEntry? {
    val file = element.containingFile?.virtualFile ?: return null
    return consumersByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  fun graphEntryAt(element: KtElement): MetroGraphEntry? {
    val file = element.containingFile?.virtualFile ?: return null
    return graphsByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  fun assistedSiteAt(element: KtElement): MetroAssistedSite? {
    val file = element.containingFile?.virtualFile ?: return null
    return assistedSitesByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  fun contributionsForScopes(scopeKeys: Set<ClassId>): List<MetroContributionEntry> {
    if (scopeKeys.isEmpty()) return emptyList()
    return contributions.filter { contribution -> contribution.scopeKeys.any(scopeKeys::contains) }
  }

  fun graphsForScopes(scopeKeys: Set<ClassId>): List<MetroGraphEntry> {
    if (scopeKeys.isEmpty()) return emptyList()
    return graphs.filter { graph -> graph.scopeKeys.any(scopeKeys::contains) }
  }

  companion object {
    val EMPTY = MetroBindingIndex(emptyList(), emptyList(), emptyList(), emptyList())
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
internal class MetroConsumerResolution(
  /** Unfiltered project-wide candidates. */
  val global: List<MetroProviderEntry>,
  /** Graph-filtered candidates per graph that can resolve the consumer. */
  val perGraph: Map<MetroGraphEntry, List<MetroProviderEntry>>,
) {
  /** The deduplicated union of per-graph results, or [global] when no graph resolves it. */
  val effective: List<MetroProviderEntry> =
    if (perGraph.isEmpty()) global else perGraph.values.flatten().distinct()
}
