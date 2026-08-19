// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.graph.applyExcludesAndReplaces
import dev.zacsweers.metro.compiler.graph.computeMergePlan
import dev.zacsweers.metro.compiler.graph.computeOutrankedBindings
import java.util.IdentityHashMap
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
  val graphs: List<KaGraphDeclaration>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite> = emptyList(),
  val bindingContainers: List<BindingContainerEntry> = emptyList(),
  private val incompleteAssistedFactories:
    Map<KaModule, Map<SourceAssistedFactoryIdentity, String>> =
    emptyMap(),
) {
  private val containersById: ScatterMap<ClassId, List<BindingContainerEntry>> by lazy {
    bindingContainers.groupToScatter { it.classId }
  }

  private val bindingsByOrigin: ScatterMap<ClassId, List<KaBinding>> by lazy {
    bindings.groupToScatter { it.originClassId }
  }

  private val bindingsByMemberOwner: ScatterMap<ClassId, List<KaBinding>> by lazy {
    val result = MutableScatterMap<ClassId, MutableList<KaBinding>>()
    for (binding in bindings) {
      for (ownerId in binding.memberInjectionOwnerIds) {
        result.getOrPut(ownerId, ::mutableListOf) += binding
      }
    }
    @Suppress("UNCHECKED_CAST")
    result as ScatterMap<ClassId, List<KaBinding>>
  }

  private val graphContexts = ConcurrentHashMap<KaGraphDeclaration, List<GraphContext>>()
  private val graphQueryContexts = ConcurrentHashMap<GraphContext, GraphQueryContext>()
  private val ownContainersByContext =
    ConcurrentHashMap<GraphQueryContext, ConcurrentHashMap<GraphDeclarationId, Set<ClassId>>>()
  private val replacedOriginsByContext = ConcurrentHashMap<GraphQueryContext, Set<ClassId>>()
  private val validationReplacedOriginsByContext =
    ConcurrentHashMap<GraphQueryContext, Set<ClassId>>()
  private val outrankedOriginsByContext = ConcurrentHashMap<GraphQueryContext, Set<ClassId>>()
  private val validationOutrankedOriginsByContext =
    ConcurrentHashMap<GraphQueryContext, Set<ClassId>>()
  private val removedOriginsByContext = ConcurrentHashMap<GraphQueryContext, Set<ClassId>>()
  private val validationRemovedOriginsByContext =
    ConcurrentHashMap<GraphQueryContext, Set<ClassId>>()
  private val consumerResolutions = ConcurrentHashMap<ConsumerEntry, ConsumerResolution>()
  private val nearestFactoryInputOwners =
    ConcurrentHashMap<GraphContext, NearestFactoryInputOwners>()
  private val graphCompositions = ConcurrentHashMap<GraphCompositionKey, SelectedGraphComposition>()
  /** Avoid rebuilding a suffix path for every binding checked in the same immutable query view. */
  private val graphCompositionsByOwner =
    ConcurrentHashMap<
      GraphQueryContext,
      ConcurrentHashMap<GraphDeclarationId, SelectedGraphComposition>,
    >()
  private val contributionSelections =
    ConcurrentHashMap<GraphCompositionKey, ContributionSelection>()

  private val contributionsByScope: ScatterMap<ClassId, List<ContributionEntry>> by lazy {
    val result = MutableScatterMap<ClassId, MutableList<ContributionEntry>>()
    for (contribution in contributions) {
      for (scope in contribution.scopeKeys) {
        result.getOrPut(scope, ::mutableListOf) += contribution
      }
    }
    @Suppress("UNCHECKED_CAST")
    result as ScatterMap<ClassId, List<ContributionEntry>>
  }

  /** These are exact candidate objects from this immutable index, not declaration-name aliases. */
  private val contributedBindingOwners: Map<KaBinding, GraphInterfaceContribution> by lazy {
    val result = IdentityHashMap<KaBinding, GraphInterfaceContribution>()
    for (graph in graphs) {
      for (contribution in graph.contributedInterfaces) {
        for (binding in contribution.bindings) result[binding] = contribution
      }
    }
    result
  }

  private val graphsByReference: Map<GraphReference, List<KaGraphDeclaration>> by lazy {
    val result = linkedMapOf<GraphReference, MutableList<KaGraphDeclaration>>()
    for (graph in graphs) {
      for (reference in graph.selfReferences) {
        result.getOrPut(reference, ::mutableListOf) += graph
      }
    }
    result
  }

  /** Potential edges only. Their contribution must survive in the eventual parent's root module. */
  private val potentialParentsByReference: Map<GraphReference, List<KaGraphDeclaration>> by lazy {
    val result = linkedMapOf<GraphReference, MutableList<KaGraphDeclaration>>()
    for (graph in graphs) {
      val references = linkedSetOf<GraphReference>()
      references += graph.extensionCreations
      for (contribution in graph.contributedInterfaces) references +=
        contribution.extensionCreations
      for (reference in references) result.getOrPut(reference, ::mutableListOf) += graph
    }
    result
  }

  private val allGraphContexts: List<GraphContext> by lazy {
    graphs.flatMap { graph ->
      ProgressManager.checkCanceled()
      contextsFor(graph)
    }
  }

  private val contextsByGraphId: ScatterMap<GraphDeclarationId, List<GraphContext>> by lazy {
    val result = MutableScatterMap<GraphDeclarationId, MutableList<GraphContext>>()
    for (context in allGraphContexts) {
      for (graphId in context.graphIds) {
        result.getOrPut(graphId, ::mutableListOf) += context
      }
    }
    @Suppress("UNCHECKED_CAST")
    result as ScatterMap<GraphDeclarationId, List<GraphContext>>
  }

  /** Session-free identities also record whether an inherited declaration is publicly visible. */
  private val specializedBindingIdentities: Map<SpecializedBindingIdentity, Boolean> by lazy {
    buildMap {
      for (binding in bindings) {
        if (binding is KaBinding.BoundInstance) continue
        if (binding in contributedBindingOwners) continue
        val graphId = binding.ownerGraphId ?: continue
        val identity = pointerIdentity(binding.pointer) ?: continue
        val specialization =
          SpecializedBindingIdentity(graphId, identity, binding.javaClass, binding.typeKey)
        val alreadyPublic = get(specialization) == true
        put(specialization, alreadyPublic || !binding.isGraphPrivate)
      }
    }
  }

  /** A concrete specialization replaces its raw declaration even when its return key changes. */
  private val specializedDeclarationIdentities: Set<SpecializedDeclarationIdentity> by lazy {
    if (specializedBindingIdentities.isEmpty()) {
      emptySet()
    } else {
      buildSet {
        for (identity in specializedBindingIdentities.keys) {
          add(
            SpecializedDeclarationIdentity(
              identity.graphId,
              identity.pointer,
              identity.bindingClass,
            )
          )
        }
      }
    }
  }

  private val contributedSpecializedBindings:
    Map<SpecializedBindingIdentity, List<KaBinding>> by lazy {
    val result = linkedMapOf<SpecializedBindingIdentity, MutableList<KaBinding>>()
    for (binding in contributedBindingOwners.keys) {
      val owner = binding.ownerGraphId ?: continue
      val source = pointerIdentity(binding.pointer) ?: continue
      val identity = SpecializedBindingIdentity(owner, source, binding.javaClass, binding.typeKey)
      result.getOrPut(identity, ::mutableListOf) += binding
    }
    result
  }

  private val contributedSpecializedDeclarations:
    Map<SpecializedDeclarationIdentity, List<KaBinding>> by lazy {
    val result = linkedMapOf<SpecializedDeclarationIdentity, MutableList<KaBinding>>()
    for (binding in contributedBindingOwners.keys) {
      val owner = binding.ownerGraphId ?: continue
      val source = pointerIdentity(binding.pointer) ?: continue
      val identity = SpecializedDeclarationIdentity(owner, source, binding.javaClass)
      result.getOrPut(identity, ::mutableListOf) += binding
    }
    result
  }

  /** Raw source callables remain authoritative when their interface was written explicitly. */
  private val unownedBindingDeclarations: Map<BindingDeclarationIdentity, List<KaBinding>> by lazy {
    val result = linkedMapOf<BindingDeclarationIdentity, MutableList<KaBinding>>()
    for (binding in bindings) {
      if (binding.ownerGraphId != null || binding.containerId == null) continue
      val source = pointerIdentity(binding.pointer) ?: continue
      val identity = BindingDeclarationIdentity(source, binding.javaClass, binding.typeKey)
      result.getOrPut(identity, ::mutableListOf) += binding
    }
    result
  }

  private val contextsByScope: ScatterMap<ClassId, List<GraphContext>> by lazy {
    val result = MutableScatterMap<ClassId, MutableList<GraphContext>>()
    for (context in allGraphContexts) {
      for (scope in context.scopes) {
        result.getOrPut(scope, ::mutableListOf) += context
      }
    }
    @Suppress("UNCHECKED_CAST")
    result as ScatterMap<ClassId, List<GraphContext>>
  }

  // Contributions are keyed solely by multibindingId, mirroring the compiler's
  // @MultibindingElement qualifier swap. Their element key must not satisfy plain consumers.
  private val bindingsByKey: ScatterMap<KaTypeKey, List<KaBinding>> by lazy {
    bindings.groupToScatter { binding ->
      binding.typeKey.takeIf { binding.multibindingId == null }
    }
  }

  private val bindingsByType: ScatterMap<KaTypeSnapshot, List<KaBinding>> by lazy {
    bindings.groupToScatter { it.typeKey.type }
  }

  private val assistedFactoriesByTarget:
    ScatterMap<KaTypeKey, List<KaBinding.AssistedFactory>> by lazy {
    bindings.filterIsInstance<KaBinding.AssistedFactory>().groupToScatter { it.targetTypeKey }
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

  private val specializedConsumerIdentities: Set<SpecializedConsumerIdentity> by lazy {
    buildSet {
      for (consumer in consumers) {
        val graphId = consumer.graphId ?: continue
        if (consumer.graphRequestKind != null) continue
        if (consumer.graphContribution != null) continue
        val identity = pointerIdentity(consumer.pointer) ?: continue
        add(SpecializedConsumerIdentity(graphId, identity))
      }
    }
  }

  private val contributedSpecializedConsumers:
    Map<SpecializedConsumerIdentity, List<ConsumerEntry>> by lazy {
    val result = linkedMapOf<SpecializedConsumerIdentity, MutableList<ConsumerEntry>>()
    for (consumer in consumers) {
      if (consumer.graphContribution == null || consumer.graphRequestKind != null) continue
      val graphId = consumer.graphId ?: continue
      val source = pointerIdentity(consumer.pointer) ?: continue
      val identity = SpecializedConsumerIdentity(graphId, source)
      result.getOrPut(identity, ::mutableListOf) += consumer
    }
    result
  }

  /** Specializations can be discovered independently from several consuming source-file shards. */
  private val duplicatedAssistedFactoryKeys: Set<KaTypeKey> by lazy {
    val seen = HashSet<Triple<ClassId?, VirtualFile?, KaTypeKey>>()
    buildSet {
      for (binding in bindings) {
        if (binding !is KaBinding.AssistedFactory) continue
        val identity = Triple(binding.originClassId, binding.pointer.virtualFile, binding.typeKey)
        if (!seen.add(identity)) add(binding.typeKey)
      }
    }
  }

  private val graphsByFile: ScatterMap<VirtualFile, List<KaGraphDeclaration>> by lazy {
    graphs.groupToScatter { it.pointer.virtualFile }
  }

  private val assistedSitesByFile: ScatterMap<VirtualFile, List<AssistedSite>> by lazy {
    assistedSites.groupToScatter { it.pointer.virtualFile }
  }

  /**
   * Bindings satisfying [consumer]: direct key matches plus, for `Set`/`Map` multibinding sites,
   * the multibinding contributions collected into them.
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
    if (graphs.isEmpty()) {
      return ConsumerResolution(global, emptyMap(), hasGraphs = false, index = this)
    }

    val perContext = LinkedHashMap<GraphContext, List<KaBinding>>()
    val visibleByModule = HashMap<KaModule, List<KaBinding>>()
    for (context in candidateContextsFor(consumer)) {
      ProgressManager.checkCanceled()
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
    return ConsumerResolution(global, perContext, hasGraphs = true, index = this)
  }

  private fun candidateContextsFor(consumer: ConsumerEntry): List<GraphContext> {
    val graphId = consumer.graphId
    if (graphId != null) return contextsByGraphId[graphId].orEmpty()

    if (consumer.contributionScopes.isNotEmpty()) {
      val contexts = linkedSetOf<GraphContext>()
      for (scope in consumer.contributionScopes) {
        contexts += contextsByScope[scope].orEmpty()
      }
      return contexts.toList()
    }

    return allGraphContexts
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
    return bindingsByKey[key].orEmpty().withoutDuplicateAssistedFactories(key).filter {
      isBindingInContext(it, queryContext, includeIncompatibleScopes = true)
    }
  }

  /** All indexed bindings for the same unqualified type, regardless of graph membership. */
  fun bindingsWithType(key: KaTypeKey): List<KaBinding> {
    return bindingsByType[key.type].orEmpty().withoutDuplicateAssistedFactories()
  }

  /** Type-level factory checks use module visibility, not a graph's binding exclusions. */
  fun assistedFactoryForType(
    key: KaTypeKey,
    queryContext: GraphQueryContext,
  ): KaBinding.AssistedFactory? {
    for (binding in bindingsWithType(key)) {
      if (binding is KaBinding.AssistedFactory && isVisibleFrom(binding, queryContext)) {
        return binding
      }
    }
    return null
  }

  /** Known assisted factories creating [key], regardless of graph membership. */
  fun assistedFactoriesForTarget(key: KaTypeKey): List<KaBinding.AssistedFactory> {
    return assistedFactoriesByTarget[key].orEmpty().withoutDuplicateAssistedFactories()
  }

  /** Why this exact factory's dependency expansion stopped in the graph's compilation module. */
  fun incompleteAssistedFactoryReason(
    binding: KaBinding.AssistedFactory,
    queryContext: GraphQueryContext,
  ): String? {
    if (incompleteAssistedFactories.isEmpty()) return null
    val boundaries = incompleteAssistedFactories[queryContext.graphModule] ?: return null
    val file = binding.pointer.virtualFile ?: return null
    return boundaries[SourceAssistedFactoryIdentity(binding.typeKey, binding.originClassId, file)]
  }

  /** Indexed source sites for [key], used when a graph diagnostic needs its real declaration. */
  fun consumerEntriesForKey(key: KaTypeKey): List<ConsumerEntry> {
    return consumersByKey[key].orEmpty()
  }

  /** Contributions collected into [multibindingId] in [queryContext]'s graph. */
  fun multibindingContributions(
    multibindingId: String,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    return contributionsByMultibindingId[multibindingId].orEmpty().filter {
      isBindingInContext(it, queryContext, includeIncompatibleScopes = true)
    }
  }

  /**
   * Every binding that is a member of [queryContext]'s graph. Linear over all bindings, so call on
   * demand only.
   */
  fun bindingsInContext(queryContext: GraphQueryContext): List<KaBinding> {
    return bindings
      .filter {
        !it.isValidationOnlyAssistedTarget() && isBindingInContext(it, queryContext)
      }
      .withoutDuplicateAssistedFactories()
  }

  /** The consumer sites declared on [graph] itself, used as seal roots. */
  fun accessorsFor(graph: KaGraphDeclaration): List<ConsumerEntry> {
    return accessorsByGraph[graph.declarationId].orEmpty().filter {
      it.graphContribution == null
    }
  }

  /** The actual seal roots after selecting this graph's contributed interface surface. */
  fun accessorsFor(queryContext: GraphQueryContext): List<ConsumerEntry> {
    return graphComposition(queryContext).accessors
  }

  /** The selected surface of [graph] in this exact root module and ancestor suffix. */
  fun graphComposition(
    queryContext: GraphQueryContext,
    graph: KaGraphDeclaration = queryContext.graphContext.graph,
  ): GraphComposition {
    val selected = selectedGraphComposition(queryContext, graph.declarationId)
    requireNotNull(selected) { "Graph is not in the requested parent path" }
    return selected.composition
  }

  private fun selectedGraphComposition(
    queryContext: GraphQueryContext,
    ownerId: GraphDeclarationId,
  ): SelectedGraphComposition? {
    graphCompositionsByOwner[queryContext]?.get(ownerId)?.let {
      return it
    }
    val context = queryContext.graphContext
    if (ownerId !in context.graphIds) return null
    val byOwner = graphCompositionsByOwner.computeIfAbsent(queryContext) { ConcurrentHashMap() }
    return byOwner.computeIfAbsent(ownerId) {
      val chain = context.chain
      val graphIndex = chain.indexOfFirst { graph -> graph.declarationId == ownerId }
      check(graphIndex >= 0) { "Graph is not in the requested parent path" }
      selectedGraphComposition(
        chain.subList(graphIndex, chain.size),
        queryContext.graphModule,
        queryContext.resolutionScope,
      )
    }
  }

  private fun selectedGraphComposition(
    chain: List<KaGraphDeclaration>,
    module: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): SelectedGraphComposition {
    val key = GraphCompositionKey(GraphPath(chain.map { it.declarationId }), module)
    return graphCompositions.computeIfAbsent(key) {
      val graph = chain.first()
      val selection = contributionSelection(chain, module, resolutionScope)
      val typeKeys = LinkedHashSet(graph.supertypeKeys)
      val declarations = LinkedHashSet(graph.supertypeDeclarations)
      val creations = LinkedHashSet(graph.extensionCreations)
      val factories = ArrayList(graph.extensionFactories)
      val memberOwners = LinkedHashSet(graph.injectedMemberOwnerIds)
      val selectedContributions = mutableListOf<ContributionEntry>()
      val contributionIds = linkedSetOf<GraphReference>()
      val selectedBindings =
        java.util.Collections.newSetFromMap(IdentityHashMap<KaBinding, Boolean>())
      val bindingIdentities = hashSetOf<Any>()
      val accessors = mutableListOf<ConsumerEntry>()
      val accessorIdentities = hashSetOf<Any>()
      var implementedDeclarations: MutableSet<SourcePointerIdentity>? = null

      fun addDefaultImplementations(implementations: List<GraphDefaultImplementation>) {
        if (implementations.isEmpty()) return
        val identities =
          implementedDeclarations
            ?: HashSet<SourcePointerIdentity>().also {
              implementedDeclarations = it
            }
        for (implementation in implementations) {
          ProgressManager.checkCanceled()
          val declaration = implementation.declaration.pointer
          if (!isVisibleFrom(declaration, null, module, resolutionScope)) continue
          // A fake override can still point at the concrete declaration itself. Its optional
          // request, if any, is retained by isImplementedGraphRequest below.
          pointerIdentity(declaration)?.let(identities::add)
          for (overridden in implementation.overriddenDeclarations) {
            pointerIdentity(overridden.pointer)?.let(identities::add)
          }
        }
      }

      fun addAccessor(consumer: ConsumerEntry) {
        if (consumer.graphRequestKind == null) return
        val source = pointerIdentity(consumer.pointer)
        val identity =
          if (source == null) consumer
          else
            GraphAccessorIdentity(
              source,
              consumer.contextKey,
              consumer.graphRequestKind,
              consumer.injectedMemberPointer?.let(::pointerIdentity),
              consumer.isOptional,
              consumer.isSuspend,
            )
        if (accessorIdentities.add(identity)) accessors += consumer
      }

      for (consumer in accessorsByGraph[graph.declarationId].orEmpty()) {
        if (consumer.graphContribution == null) addAccessor(consumer)
      }
      addDefaultImplementations(graph.defaultImplementations)
      for (candidate in graph.contributedInterfaces) {
        ProgressManager.checkCanceled()
        val reference = candidate.contribution.declarationId ?: continue
        if (reference !in selection.declarationIds) continue
        // A written supertype remains present even when its implicit contribution is removed.
        // Its ordinary extraction also supplies the members, so do not materialize them twice.
        if (reference in graph.supertypeDeclarations) continue
        contributionIds += reference
        selectedContributions += candidate.contribution
        typeKeys += candidate.supertypeKeys
        declarations += candidate.supertypeDeclarations
        creations += candidate.extensionCreations
        factories += candidate.extensionFactories
        memberOwners += candidate.injectedMemberOwnerIds
        addDefaultImplementations(candidate.defaultImplementations)
        for (binding in candidate.bindings) {
          if (hasWrittenBinding(binding, graph)) continue
          val source = pointerIdentity(binding.pointer)
          val identity =
            if (source == null) binding
            else
              BindingResolutionIdentity(
                source,
                binding.javaClass,
                binding.contextualTypeKey,
                binding.dependencies,
              )
          if (bindingIdentities.add(identity)) selectedBindings += binding
        }
        candidate.consumers.forEach(::addAccessor)
      }
      val implementedRequests = implementedDeclarations.orEmpty()
      if (implementedRequests.isNotEmpty()) {
        accessors.removeAll { isImplementedGraphRequest(it, implementedRequests) }
      }
      SelectedGraphComposition(
        GraphComposition(
          typeKeys,
          declarations,
          creations,
          factories,
          selectedContributions,
          accessors,
          memberOwners,
        ),
        contributionIds,
        selectedBindings,
        implementedRequests,
      )
    }
  }

  private fun hasWrittenBinding(binding: KaBinding, graph: KaGraphDeclaration): Boolean {
    val source = pointerIdentity(binding.pointer) ?: return false
    val specialization =
      SpecializedBindingIdentity(graph.declarationId, source, binding.javaClass, binding.typeKey)
    if (specialization in specializedBindingIdentities) return true
    val declaration = BindingDeclarationIdentity(source, binding.javaClass, binding.typeKey)
    return unownedBindingDeclarations[declaration].orEmpty().any { raw ->
      val owner = raw.containerId ?: return@any false
      val reference = GraphReference(owner, raw.pointer.virtualFile)
      reference in graph.supertypeDeclarations || reference in graph.selfReferences
    }
  }

  private fun contributionSelection(
    chain: List<KaGraphDeclaration>,
    module: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): ContributionSelection {
    val key = GraphCompositionKey(GraphPath(chain.map { it.declarationId }), module)
    return contributionSelections.computeIfAbsent(key) {
      selectContributions(
        chain.first().scopeKeys,
        chain.flatMapToSet { it.excludes },
        module,
        resolutionScope,
      )
    }
  }

  private fun selectContributions(
    scopes: Set<ClassId>,
    excludes: Set<ClassId>,
    module: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): ContributionSelection {
    val candidates =
      contributionsForScopes(scopes).filter { contribution ->
        isVisibleFrom(contribution.pointer, contribution.hintAvailability, module, resolutionScope)
      }
    val byId = candidates.groupBy { it.classId }
    val presentIds = byId.keys.filterNotNullTo(mutableSetOf())
    val nestedFactories = mutableMapOf<ClassId, MutableSet<ClassId>>()
    for (contribution in candidates) {
      val child = contribution.graphExtension ?: continue
      val factoryId = contribution.classId ?: continue
      nestedFactories.getOrPut(child.classId, ::mutableSetOf) += factoryId
    }
    val plan =
      computeMergePlan(
        presentIds = presentIds,
        excluded = excludes,
        // Compiler exclusions expand ChildGraph to its contributed Factory; replacements do not.
        nestedChildrenOf = { nestedFactories[it].orEmpty() },
        replacesOf = { id -> byId[id].orEmpty().flatMapToSet { it.replaces } },
      )
    val selected = candidates.filter { it.classId !in plan.removed }
    return ContributionSelection(
      selected,
      selected.mapNotNullTo(mutableSetOf()) { it.declarationId },
      plan.removed,
    )
  }

  /** The extension graphs created by [graph]'s accessors. */
  fun extensionsOf(graph: KaGraphDeclaration): List<KaGraphDeclaration> {
    if (graph.extensionCreations.isEmpty()) return emptyList()
    return graphs.filter { candidate ->
      candidate.isExtension && candidate.selfReferences.any { it in graph.extensionCreations }
    }
  }

  /** Child declarations created by the selected surface, excluding recursive parent paths. */
  fun extensionsOf(queryContext: GraphQueryContext): List<KaGraphDeclaration> {
    val context = queryContext.graphContext
    val result = linkedSetOf<KaGraphDeclaration>()
    for (reference in graphComposition(queryContext).extensionCreations) {
      for (candidate in graphsByReference[reference].orEmpty()) {
        if (!candidate.isExtension || candidate.declarationId in context.graphIds) continue
        if (
          !isVisibleFrom(
            candidate.pointer,
            null,
            queryContext.graphModule,
            queryContext.resolutionScope,
          )
        )
          continue
        result += candidate
      }
    }
    return result.toList()
  }

  /** Every valid aggregation context for [graph]. Extensions can have multiple parent paths. */
  fun contextsFor(graph: KaGraphDeclaration): List<GraphContext> {
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
    val queryContext = queryContext(parent) ?: return emptyList()
    return extensionsOf(queryContext).flatMap { extension ->
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
    val removedOrigins = removedContributionOrigins(queryContext)
    return contributionsForScopes(context.graph.scopeKeys).filter {
      it.classId !in context.excludes &&
        it.classId !in removedOrigins &&
        isVisibleFrom(it, queryContext)
    }
  }

  /**
   * Contributions [queryContext]'s graph receives from its parent chain rather than aggregating
   * itself: matched against ancestor scopes only, minus excluded. Empty for non-extension graphs.
   */
  fun inheritedContributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    val context = queryContext.graphContext
    val inheritedScopes = context.scopes - context.graph.scopeKeys
    val removedOrigins = removedContributionOrigins(queryContext)
    return contributionsForScopes(inheritedScopes).filter {
      it.classId !in context.excludes &&
        it.classId !in removedOrigins &&
        it.scopeKeys.none(context.graph.scopeKeys::contains) &&
        isVisibleFrom(it, queryContext)
    }
  }

  private fun buildContexts(graph: KaGraphDeclaration): List<GraphContext> {
    return buildChains(graph, visited = setOf(graph)).map(::buildContext)
  }

  private fun buildChains(
    graph: KaGraphDeclaration,
    visited: Set<KaGraphDeclaration>,
  ): List<List<KaGraphDeclaration>> {
    if (!graph.isExtension) return listOf(listOf(graph))

    val parents = linkedSetOf<KaGraphDeclaration>()
    for (reference in graph.selfReferences) {
      for (candidate in potentialParentsByReference[reference].orEmpty()) {
        if (candidate !in visited) parents += candidate
      }
    }
    if (parents.isEmpty()) return listOf(listOf(graph))

    val chains = mutableListOf<List<KaGraphDeclaration>>()
    for (parent in parents) {
      ProgressManager.checkCanceled()
      val parentChains = buildChains(parent, visited + parent)
      for (parentChain in parentChains) {
        val module = moduleFor(parentChain.last().pointer.element) ?: continue
        val resolutionScope = module.resolutionScope()
        if (!isVisibleFrom(graph.pointer, null, module, resolutionScope)) continue
        val composition = selectedGraphComposition(parentChain, module, resolutionScope).composition
        if (composition.extensionCreations.none(graph.selfReferences::contains)) continue
        chains += listOf(graph) + parentChain
      }
    }
    return chains.ifEmpty { listOf(listOf(graph)) }
  }

  private fun buildContext(chain: List<KaGraphDeclaration>): GraphContext {
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
      injectedMemberOwnerIds = chain.flatMapToSet { it.injectedMemberOwnerIds },
      daggerAnvilInteropEnabled = chain.last().daggerAnvilInteropEnabled,
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
      visibleContainers(containerId, useSiteModule, resolutionScope).forEach { container ->
        container.includes.forEach(containerRoots::add)
      }
    }
    selectContributions(context.scopes, context.excludes, useSiteModule, resolutionScope)
      .entries
      .asSequence()
      .filter { it.classId != null && it.classId in containersById }
      .mapTo(containerRoots) { it.classId!! }

    return resolveContainerClosure(containerRoots, useSiteModule, resolutionScope)
  }

  /**
   * Containers [graph] itself wires: declared, factory-included, contributed into its own
   * aggregation scopes, and everything those include transitively. Bindings from these stay local
   * to [graph]'s context like the compiler's locally declared keys.
   */
  fun graphOwnContainers(
    graph: KaGraphDeclaration,
    queryContext: GraphQueryContext,
  ): Set<ClassId> {
    val ownerId = graph.declarationId
    ownContainersByContext[queryContext]?.get(ownerId)?.let {
      return it
    }
    val context = queryContext.graphContext
    require(ownerId in context.graphIds) { "Graph is not in the requested parent path" }
    val byOwner = ownContainersByContext.computeIfAbsent(queryContext) { ConcurrentHashMap() }
    return byOwner.computeIfAbsent(ownerId) {
      val chain = context.chain
      val graphIndex = chain.indexOfFirst { candidate -> candidate.declarationId == ownerId }
      check(graphIndex >= 0) { "Graph is not in the requested parent path" }
      val ownerChain = chain.subList(graphIndex, chain.size)
      val owner = ownerChain.first()
      val roots = hashSetOf<ClassId>()
      roots += owner.bindingContainers
      for (containerKey in owner.includedBindingContainers) {
        containerKey.type.classId?.let(roots::add)
      }
      contributionSelection(
          ownerChain,
          queryContext.graphModule,
          queryContext.resolutionScope,
        )
        .entries
        .asSequence()
        .filter { it.classId != null && it.classId in containersById }
        .mapTo(roots) { it.classId!! }
      resolveContainerClosure(roots, queryContext.graphModule, queryContext.resolutionScope)
    }
  }

  /** Whether [binding] belongs to this graph itself rather than one of its ancestors. */
  fun isBindingOwnedByCurrentGraph(
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean {
    val graph = queryContext.graphContext.graph
    val ownerGraphId = binding.ownerGraphId
    if (ownerGraphId != null) {
      if (binding is KaBinding.BoundInstance) {
        return isFactoryInputOwnedBy(binding, graph.declarationId)
      }
      return ownerGraphId == graph.declarationId
    }
    val includedContainerKey = binding.includedContainerKey
    if (includedContainerKey != null) {
      return includedContainerKey in graph.includedBindingContainers
    }

    val containerId = binding.containerId
    if (containerId != null) {
      return containerId in graph.selfIds ||
        graphComposition(queryContext).supertypeDeclarations.any { it.classId == containerId } ||
        containerId in graphOwnContainers(graph, queryContext)
    }

    if (binding.contributionScopes.isNotEmpty()) {
      return binding.contributionScopes.any { it in graph.scopeKeys }
    }

    return true
  }

  /** Whether an ancestor can resolve [key] through one of its private bindings. */
  fun hasPrivateAncestorBinding(key: KaTypeKey, queryContext: GraphQueryContext): Boolean {
    val candidates = bindingsByKey[key].orEmpty()
    if (candidates.none { it.isGraphPrivate }) {
      return false
    }

    val chain = queryContext.graphContext.chain
    for (ancestorIndex in 1 until chain.size) {
      val ancestorPath = GraphPath(chain.drop(ancestorIndex).map { it.declarationId })
      val ancestorContext = findContext(ancestorPath) ?: continue
      val ancestorQueryContext = queryContext(ancestorContext) ?: continue
      for (binding in candidates) {
        if (
          binding.isGraphPrivate &&
            isBindingCandidateInContext(
              binding,
              ancestorQueryContext,
              includeIncompatibleScopes = true,
            )
        ) {
          return true
        }
      }
    }
    return false
  }

  private fun resolveContainerClosure(
    containerRoots: Set<ClassId>,
    useSiteModule: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): Set<ClassId> {
    val containers = hashSetOf<ClassId>()
    val visitedDeclarations = hashSetOf<GraphReference>()
    val queue = ArrayDeque(containerRoots)
    while (queue.isNotEmpty()) {
      val id = queue.removeFirst()
      containers += id
      for (container in visibleContainers(id, useSiteModule, resolutionScope)) {
        if (!visitedDeclarations.add(container.declarationId)) continue
        container.includes.forEach(queue::add)
      }
    }
    return containers
  }

  private fun visibleContainers(
    classId: ClassId,
    useSiteModule: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): List<BindingContainerEntry> {
    return containersById[classId].orEmpty().filter { container ->
      isVisibleFrom(container.pointer, null, useSiteModule, resolutionScope)
    }
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
    // Assisted targets are kept in the index for graph diagnostics, but are never ordinary
    // injectable bindings for editor resolution or navigation.
    val direct =
      bindingsByKey[consumer.key]
        .orEmpty()
        .withoutDuplicateAssistedFactories(consumer.key)
        .filterNot {
          it.isValidationOnlyAssistedTarget()
        }
    val contributions = consumer.multibindingId?.let { contributionsByMultibindingId[it] }.orEmpty()
    return direct + contributions
  }

  internal fun isConsumerInContext(
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
    if (!isContributedConsumerActive(consumer, queryContext)) return false

    val isUnspecializedContainerConsumer = consumer.graphId == null && consumer.containerId != null
    if (isUnspecializedContainerConsumer) {
      if (isSupersededInheritedConsumer(consumer, queryContext)) return false
    }

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
    if (graphId != null) {
      if (graphId !in context.graphIds) return false
      if (consumer.graphRequestKind == null || consumer.isOptional) return true
      val selected = selectedGraphComposition(queryContext, graphId) ?: return false
      return !isImplementedGraphRequest(consumer, selected.implementedRequests)
    }

    val memberOwnerClassId = consumer.memberOwnerClassId
    if (memberOwnerClassId != null) {
      if (memberOwnerClassId in context.excludes) return false
      return memberOwnerClassId in context.injectedMemberOwnerIds ||
        context.chain.any { graph ->
          memberOwnerClassId in graphComposition(queryContext, graph).injectedMemberOwnerIds
        } ||
        bindingsByMemberOwner[memberOwnerClassId].orEmpty().any { binding ->
          isBindingInContext(binding, queryContext)
        }
    }

    val includedContainerKey = consumer.includedContainerKey
    if (includedContainerKey != null) {
      return includedContainerKey in context.includedBindingContainers
    }

    val containerId = consumer.containerId
    if (containerId != null) {
      return isGraphMemberContainer(containerId, consumer.pointer.virtualFile, queryContext) ||
        containerId in queryContext.containers
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
    return bindingsByOrigin[originClassId].orEmpty().any { binding ->
      isBindingInContext(binding, queryContext)
    }
  }

  private fun isBindingInContext(
    entry: KaBinding,
    queryContext: GraphQueryContext,
    includeIncompatibleScopes: Boolean = false,
  ): Boolean {
    if (!isBindingCandidateInContext(entry, queryContext, includeIncompatibleScopes)) return false
    // Replaces removes the origin's contributions only; its own injectable type stays available
    // (a replacing stub can inject the replaced implementation directly).
    if (entry.contributionScopes.isEmpty()) return true
    val originClassId = entry.originClassId ?: return true
    return originClassId !in removedContributionOrigins(queryContext, includeIncompatibleScopes)
  }

  private fun isBindingCandidateInContext(
    entry: KaBinding,
    queryContext: GraphQueryContext,
    includeIncompatibleScopes: Boolean = false,
  ): Boolean {
    if (!isVisibleFrom(entry, queryContext)) return false
    if (!isContributedBindingActive(entry, queryContext)) return false
    val isUnspecializedContainerCallable =
      entry.ownerGraphId == null &&
        entry.containerId != null &&
        when (entry) {
          is KaBinding.Provided,
          is KaBinding.Alias,
          is KaBinding.Multibinding,
          is KaBinding.CustomWrapper -> true
          else -> false
        }
    if (isUnspecializedContainerCallable) {
      if (isSupersededInheritedBinding(entry, queryContext)) return false
    }
    if (entry.isGraphPrivate && !isBindingOwnedByCurrentGraph(entry, queryContext)) return false
    val context = queryContext.graphContext
    val ownerGraphId = entry.ownerGraphId
    if (ownerGraphId != null) {
      if (entry is KaBinding.BoundInstance) {
        val ownedByContext =
          ownerGraphId in context.graphIds ||
            context.graphIds.any { it in entry.additionalOwnerGraphIds }
        if (!ownedByContext) return false
        if (isSupersededByNearerFactoryInput(entry, context)) return false
      } else {
        if (ownerGraphId !in context.graphIds) return false
        if (isSupersededByNearerInheritedBinding(entry, queryContext)) return false
      }
    }
    if (entry.originClassId != null && entry.originClassId in context.excludes) return false
    // Scoped bindings only live in graphs declaring a matching scope (explicitly or implicitly
    // via the aggregation scope's conveyed @SingleIn)
    if (
      !includeIncompatibleScopes &&
        entry.scope != null &&
        entry.scope !in context.scopingAnnotations
    ) {
      return false
    }
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
        val containerId = entry.containerId
        if (includedContainerKey != null) {
          includedContainerKey in context.includedBindingContainers
        } else {
          entry.contributionScopes.isNotEmpty() ||
            containerId == null ||
            isGraphMemberContainer(containerId, entry.pointer.virtualFile, queryContext) ||
            containerId in queryContext.containers
        }
      }
      is KaBinding.BoundInstance -> {
        when {
          entry.isGraphInput -> entry.typeKey in context.includedDependencies
          entry.isBindingContainerInput -> entry.typeKey in context.includedBindingContainers
          else -> entry.containerId in context.graphClassIds
        }
      }
      is KaBinding.GraphDependency -> entry.ownerKey in context.includedDependencies
      // Injected classes and assisted factories are implicit bindings. Graph instances are
      // seal-time nodes that never appear in the index.
      is KaBinding.ConstructorInjected,
      is KaBinding.AssistedFactory,
      is KaBinding.GraphInstance,
      is KaBinding.GraphExtension -> true
    }
  }

  private fun isContributedBindingActive(
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean {
    if (binding !in contributedBindingOwners) return true
    val ownerId = binding.ownerGraphId ?: return false
    val selected = selectedGraphComposition(queryContext, ownerId) ?: return false
    return binding in selected.bindings
  }

  private fun isContributedConsumerActive(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): Boolean {
    val contribution = consumer.graphContribution ?: return true
    val ownerId = consumer.graphId ?: return false
    val selected = selectedGraphComposition(queryContext, ownerId) ?: return false
    return contribution in selected.contributionIds
  }

  /** Uses the same precomputed selection for graph roots and source/library dependency seeding. */
  private fun isImplementedGraphRequest(
    consumer: ConsumerEntry,
    implementedRequests: Set<SourcePointerIdentity>,
  ): Boolean {
    if (consumer.graphRequestKind == null || consumer.isOptional) return false
    if (implementedRequests.isEmpty()) return false
    val source = pointerIdentity(consumer.pointer) ?: return false
    return source in implementedRequests
  }

  private fun isGraphMemberContainer(
    containerId: ClassId,
    file: VirtualFile?,
    queryContext: GraphQueryContext,
  ): Boolean {
    val context = queryContext.graphContext
    if (containerId in context.graphClassIds) return true
    val reference = GraphReference(containerId, file)
    return context.chain.any { graph ->
      reference in graphComposition(queryContext, graph).supertypeDeclarations
    }
  }

  private fun isSupersededInheritedBinding(
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean {
    val pointerIdentity = pointerIdentity(binding.pointer) ?: return false
    for (graphId in queryContext.graphContext.graphIds) {
      val identity = SpecializedDeclarationIdentity(graphId, pointerIdentity, binding.javaClass)
      if (identity in specializedDeclarationIdentities) return true
      if (
        contributedSpecializedDeclarations[identity].orEmpty().any {
          isContributedBindingActive(it, queryContext)
        }
      )
        return true
    }
    return false
  }

  private fun isFactoryInputOwnedBy(
    binding: KaBinding.BoundInstance,
    graphId: GraphDeclarationId,
  ): Boolean {
    return binding.ownerGraphId == graphId || graphId in binding.additionalOwnerGraphIds
  }

  /** A child factory input replaces an ancestor's separate parameter of the same type. */
  private fun isSupersededByNearerFactoryInput(
    binding: KaBinding.BoundInstance,
    context: GraphContext,
  ): Boolean {
    if (context.chain.size < 2) return false
    if (!binding.isGraphInput && !binding.isBindingContainerInput) return false

    val inputOwners =
      nearestFactoryInputOwners.computeIfAbsent(context) {
        val dependencyOwners = mutableMapOf<KaTypeKey, GraphDeclarationId>()
        val containerOwners = mutableMapOf<KaTypeKey, GraphDeclarationId>()
        for (graph in context.chain) {
          ProgressManager.checkCanceled()
          for (key in graph.includedDependencies) {
            dependencyOwners.putIfAbsent(key, graph.declarationId)
          }
          for (key in graph.includedBindingContainers) {
            containerOwners.putIfAbsent(key, graph.declarationId)
          }
        }
        NearestFactoryInputOwners(dependencyOwners, containerOwners)
      }
    val nearestOwner =
      if (binding.isGraphInput) {
        inputOwners.dependencies[binding.typeKey]
      } else {
        inputOwners.containers[binding.typeKey]
      }
    return nearestOwner != null && !isFactoryInputOwnedBy(binding, nearestOwner)
  }

  /** The same inherited declaration belongs to the nearest graph that can expose it. */
  private fun isSupersededByNearerInheritedBinding(
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean {
    val context = queryContext.graphContext
    if (context.chain.size < 2) return false
    val ownerGraphId = binding.ownerGraphId ?: return false
    val sourceIdentity = pointerIdentity(binding.pointer) ?: return false
    for (graph in context.chain) {
      ProgressManager.checkCanceled()
      val graphId = graph.declarationId
      if (graphId == ownerGraphId) return false
      val identity =
        SpecializedBindingIdentity(graphId, sourceIdentity, binding.javaClass, binding.typeKey)
      // An intermediate graph's private declaration cannot hide a public farther ancestor from a
      // grandchild. A private declaration is only available to the graph that owns it.
      if (identity in specializedBindingIdentities) {
        if (graphId == context.graph.declarationId) return true
        if (specializedBindingIdentities[identity] == true) return true
      }
      for (candidate in contributedSpecializedBindings[identity].orEmpty()) {
        if (!isContributedBindingActive(candidate, queryContext)) continue
        if (graphId == context.graph.declarationId || !candidate.isGraphPrivate) return true
      }
    }
    return false
  }

  private fun isSupersededInheritedConsumer(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): Boolean {
    val pointerIdentity = pointerIdentity(consumer.pointer) ?: return false
    for (graphId in queryContext.graphContext.graphIds) {
      val identity = SpecializedConsumerIdentity(graphId, pointerIdentity)
      if (identity in specializedConsumerIdentities) return true
      if (
        contributedSpecializedConsumers[identity].orEmpty().any {
          isContributedConsumerActive(it, queryContext)
        }
      )
        return true
    }
    return false
  }

  private fun <T : KaBinding> List<T>.withoutDuplicateAssistedFactories(
    requestedKey: KaTypeKey? = null
  ): List<T> {
    if (size < 2) return this
    if (requestedKey != null && requestedKey !in duplicatedAssistedFactoryKeys) return this
    if (requestedKey == null && none { it is KaBinding.AssistedFactory }) return this
    if (requestedKey == null && duplicatedAssistedFactoryKeys.isEmpty()) return this
    val seen = HashSet<Triple<ClassId?, VirtualFile?, KaTypeKey>>()
    return filter { binding ->
      binding !is KaBinding.AssistedFactory ||
        seen.add(Triple(binding.originClassId, binding.pointer.virtualFile, binding.typeKey))
    }
  }

  internal fun pointerIdentity(pointer: SmartPsiElementPointer<*>): SourcePointerIdentity? {
    val file = pointer.virtualFile ?: return null
    val range = pointer.psiRange ?: return null
    return SourcePointerIdentity(file, range.startOffset, range.endOffset)
  }

  /** The same session-free identity for an enclosing source declaration already under a read. */
  internal fun sourceIdentity(element: PsiElement): SourcePointerIdentity? {
    val file = element.containingFile?.virtualFile ?: return null
    val range = element.textRange ?: return null
    return SourcePointerIdentity(file, range.startOffset, range.endOffset)
  }

  /** Separate graph specializations can share a navigation target without resolving identically. */
  fun distinctBindingDeclarations(entries: Collection<KaBinding>): List<KaBinding> {
    if (entries.size < 2) return entries.toList()
    val seen = HashSet<BindingDeclarationIdentity>()
    val result = ArrayList<KaBinding>(entries.size)
    for (binding in entries) {
      val sourceIdentity = pointerIdentity(binding.pointer)
      if (sourceIdentity == null) {
        result += binding
        continue
      }
      val identity = BindingDeclarationIdentity(sourceIdentity, binding.javaClass, binding.typeKey)
      if (seen.add(identity)) result += binding
    }
    return result
  }

  /** The same source declaration can consume different concrete dependencies in each graph. */
  internal fun bindingResolutionIdentities(entries: Collection<KaBinding>): Set<Any> {
    if (entries.isEmpty()) return emptySet()
    val result = HashSet<Any>(entries.size)
    for (binding in entries) {
      val sourceIdentity = pointerIdentity(binding.pointer)
      val identity =
        if (sourceIdentity == null) {
          binding
        } else {
          BindingResolutionIdentity(
            sourceIdentity,
            binding.javaClass,
            binding.contextualTypeKey,
            binding.dependencies,
          )
        }
      result += identity
    }
    return result
  }

  internal data class SourcePointerIdentity(
    val file: VirtualFile,
    val startOffset: Int,
    val endOffset: Int,
  )

  private data class BindingDeclarationIdentity(
    val pointer: SourcePointerIdentity,
    val bindingClass: Class<*>,
    val bindingKey: KaTypeKey,
  )

  private data class BindingResolutionIdentity(
    val pointer: SourcePointerIdentity,
    val bindingClass: Class<*>,
    val contextKey: KaContextualTypeKey,
    val dependencies: List<KaContextualTypeKey>,
  )

  private data class SpecializedBindingIdentity(
    val graphId: GraphDeclarationId,
    val pointer: SourcePointerIdentity,
    val bindingClass: Class<*>,
    val bindingKey: KaTypeKey,
  )

  private data class SpecializedDeclarationIdentity(
    val graphId: GraphDeclarationId,
    val pointer: SourcePointerIdentity,
    val bindingClass: Class<*>,
  )

  private data class SpecializedConsumerIdentity(
    val graphId: GraphDeclarationId,
    val pointer: SourcePointerIdentity,
  )

  private data class GraphCompositionKey(val path: GraphPath, val module: KaModule)

  private class ContributionSelection(
    val entries: List<ContributionEntry>,
    val declarationIds: Set<GraphReference>,
    val removed: Set<ClassId>,
  )

  private class SelectedGraphComposition(
    val composition: GraphComposition,
    val contributionIds: Set<GraphReference>,
    val bindings: Set<KaBinding>,
    val implementedRequests: Set<SourcePointerIdentity>,
  )

  private data class GraphAccessorIdentity(
    val pointer: SourcePointerIdentity,
    val contextKey: KaContextualTypeKey,
    val kind: ConsumerEntry.GraphRequestKind,
    val injectedMemberPointer: SourcePointerIdentity?,
    val optional: Boolean,
    val isSuspend: Boolean,
  )

  private data class NearestFactoryInputOwners(
    val dependencies: Map<KaTypeKey, GraphDeclarationId>,
    val containers: Map<KaTypeKey, GraphDeclarationId>,
  )

  private fun replacedOrigins(
    queryContext: GraphQueryContext,
    includeIncompatibleScopes: Boolean = false,
  ): Set<ClassId> {
    val cache =
      if (includeIncompatibleScopes) {
        validationReplacedOriginsByContext
      } else {
        replacedOriginsByContext
      }
    return cache.computeIfAbsent(queryContext) {
      val context = queryContext.graphContext
      val removed =
        selectContributions(
            context.scopes,
            context.excludes,
            queryContext.graphModule,
            queryContext.resolutionScope,
          )
          .removed
      // Interface contributions can replace another origin without declaring any binding at all.
      // Keep their shared merge plan alongside binding-derived replacements (including generated
      // contribution providers that have no separate source contribution entry).
      buildSet {
        addAll(removed)
        for (binding in bindings) {
          if (binding.replaces.isEmpty()) continue
          if (!isBindingCandidateInContext(binding, queryContext, includeIncompatibleScopes))
            continue
          addAll(binding.replaces)
        }
      }
    }
  }

  private fun removedContributionOrigins(
    queryContext: GraphQueryContext,
    includeIncompatibleScopes: Boolean = false,
  ): Set<ClassId> {
    val cache =
      if (includeIncompatibleScopes) {
        validationRemovedOriginsByContext
      } else {
        removedOriginsByContext
      }
    return cache.computeIfAbsent(queryContext) {
      val replaced = replacedOrigins(queryContext, includeIncompatibleScopes)
      if (!queryContext.graphContext.daggerAnvilInteropEnabled) return@computeIfAbsent replaced
      val outranked = outrankedOrigins(queryContext, includeIncompatibleScopes)
      if (outranked.isEmpty()) replaced else replaced + outranked
    }
  }

  /** Rank is applied after explicit replacements, matching compiler contribution merging. */
  private fun outrankedOrigins(
    queryContext: GraphQueryContext,
    includeIncompatibleScopes: Boolean,
  ): Set<ClassId> {
    val cache =
      if (includeIncompatibleScopes) {
        validationOutrankedOriginsByContext
      } else {
        outrankedOriginsByContext
      }
    return cache.computeIfAbsent(queryContext) {
      val replaced = replacedOrigins(queryContext, includeIncompatibleScopes)
      val ranked = bindings.filter { binding ->
        val isClassContribution =
          when (binding) {
            is KaBinding.Alias -> binding.isClassContribution
            is KaBinding.Provided -> binding.isClassContribution
            else -> false
          }
        isClassContribution &&
          binding.multibindingId == null &&
          binding.originClassId != null &&
          binding.originClassId !in replaced &&
          isBindingCandidateInContext(binding, queryContext, includeIncompatibleScopes)
      }
      val outranked = mutableSetOf<ClassId>()
      for (graph in queryContext.graphContext.chain) {
        val levelBindings = ranked.filter { binding ->
          binding.contributionScopes.any(graph.scopeKeys::contains)
        }
        outranked +=
          computeOutrankedBindings(
            levelBindings,
            typeKeySelector = { it.typeKey },
            rankSelector = { it.contributionRank },
            classId = { checkNotNull(it.originClassId) },
          )
      }
      outranked
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
    return bindingsByFile[file].orEmpty().withoutDuplicateAssistedFactories().filter {
      !it.isValidationOnlyAssistedTarget() && it.pointer.element === element
    }
  }

  fun consumerEntryAt(element: KtElement): ConsumerEntry? {
    val entries = consumerEntriesAt(element)
    if (entries.size == 1) return entries.single()
    if (entries.any { it.graphRequestKind == null }) {
      val first = entries.firstOrNull() ?: return null
      val firstBindings = resolveConsumer(first).uniformBindings ?: return null
      val firstResolution = bindingResolutionIdentities(firstBindings)
      // Separate graphs can inherit the same concrete parameter and the same implementation. Keep
      // its ordinary inlay unless either the requested key or the resolved bindings really differ.
      for (entryIndex in 1 until entries.size) {
        val entry = entries[entryIndex]
        if (entry.contextKey != first.contextKey) return null
        val bindings = resolveConsumer(entry).uniformBindings ?: return null
        if (bindingResolutionIdentities(bindings) != firstResolution) return null
      }
      return first
    }
    return entries.firstOrNull()
  }

  /** All consumer entries anchored at [element]. Injector members anchor one per injected key. */
  fun consumerEntriesAt(element: KtElement): List<ConsumerEntry> {
    val file = element.containingFile?.virtualFile ?: return emptyList()
    val entries = consumersByFile[file].orEmpty().filter { it.pointer.element === element }
    val hasSpecializedEntries = entries.any { it.graphId != null && it.graphRequestKind == null }
    if (!hasSpecializedEntries) return entries
    return entries.filter { it.graphId != null }
  }

  fun graphEntryAt(element: KtElement): KaGraphDeclaration? {
    val file = element.containingFile?.virtualFile ?: return null
    return graphsByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  /** Refreshes a retained graph declaration against this index. */
  fun graphFor(graph: KaGraphDeclaration): KaGraphDeclaration? {
    return graphs.firstOrNull { it.declarationId == graph.declarationId }
  }

  fun assistedSiteAt(element: KtElement): AssistedSite? {
    val file = element.containingFile?.virtualFile ?: return null
    return assistedSitesByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  fun contributionsForScopes(scopeKeys: Set<ClassId>): List<ContributionEntry> {
    if (scopeKeys.isEmpty()) return emptyList()
    if (scopeKeys.size == 1) return contributionsByScope[scopeKeys.first()].orEmpty()
    val result = linkedSetOf<ContributionEntry>()
    for (scope in scopeKeys) result += contributionsByScope[scope].orEmpty()
    return result.toList()
  }

  fun graphsForScopes(scopeKeys: Set<ClassId>): List<KaGraphDeclaration> {
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

private fun KaBinding.isValidationOnlyAssistedTarget(): Boolean {
  return this is KaBinding.ConstructorInjected && isAssisted
}

/** The result of resolving a consumer against every concrete graph context in the project. */
internal class ConsumerResolution(
  /** Candidates visible from the consumer's module. */
  val global: List<KaBinding>,
  /** Graph-filtered candidates for every concrete parent path containing the consumer. */
  val perContext: Map<GraphContext, List<KaBinding>>,
  hasGraphs: Boolean,
  index: BindingIndex,
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
    } else if (perContext.isEmpty()) {
      candidateBindings = emptyList()
      uniformBindings = emptyList()
      emptyContexts = emptySet()
    } else if (perContext.size == 1) {
      val (context, bindings) = perContext.entries.single()
      val distinctBindings = bindings.distinct()
      candidateBindings = index.distinctBindingDeclarations(distinctBindings)
      uniformBindings = distinctBindings
      emptyContexts = if (distinctBindings.isEmpty()) setOf(context) else emptySet()
    } else {
      candidateBindings = index.distinctBindingDeclarations(perContext.values.flatten())
      emptyContexts = perContext.filterValues { it.isEmpty() }.keys
      val firstBindings = perContext.values.first().distinct()
      val firstBindingSet = index.bindingResolutionIdentities(firstBindings)
      val contextsAgree =
        perContext.values.all { index.bindingResolutionIdentities(it) == firstBindingSet }
      uniformBindings = if (contextsAgree) firstBindings else null
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
  val element = pointer.element ?: return false
  if (resolutionScope == null) return true
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
