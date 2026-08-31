// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.graph.applyExcludesAndReplaces
import dev.zacsweers.metro.compiler.graph.computeLowerPriorityContributions
import dev.zacsweers.metro.compiler.graph.computeMergePlan
import dev.zacsweers.metro.idea.checkCanceledEvery
import java.util.IdentityHashMap
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/**
 * Project-wide snapshot of Metro declarations, built from stub indexes + the Analysis API.
 *
 * Resolution starts with project-wide key matches, then filters those candidates through each
 * graph's aggregation context for editor features that need graph membership.
 */
internal class BindingIndex private constructor(data: FrozenBindingIndexData) {
  val generationToken = data.generationToken
  val bindings = data.bindings
  val consumers = data.consumers
  val graphs = data.graphs
  val contributions = data.contributions
  val assistedSites = data.assistedSites
  val bindingContainers = data.bindingContainers
  private val incompleteAssistedFactories = data.incompleteAssistedFactories
  val dynamicGraphs = data.dynamicGraphs
  internal val resolutionInputs = data.resolutionInputs
  private val lookups = data.lookups

  /** Creates mutable query state for one operation. Concurrent access is unsupported. */
  fun createResolutionSession(): BindingResolutionSession = BindingResolutionSession(this)

  /** Creates a session for [block]'s queries. */
  fun <T> withResolutionSession(block: (BindingResolutionSession) -> T): T {
    return block(createResolutionSession())
  }

  /**
   * Bindings satisfying [consumer]: direct key matches plus, for `Set`/`Map` multibinding sites,
   * the multibinding contributions collected into them.
   */
  fun bindingsFor(consumer: ConsumerEntry): List<KaBinding> {
    return withResolutionSession { session -> session.bindingsFor(consumer) }
  }

  internal fun bindingsFor(
    session: BindingResolutionSession,
    consumer: ConsumerEntry,
  ): List<KaBinding> {
    val view = session.resolutionViewFor(consumer.sourceIdentity, consumer.pointer)
    return visibleBindingsFor(consumer, view?.module, view?.resolutionScope)
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
    return withResolutionSession { session -> session.bindingsFor(consumer, queryContext) }
  }

  internal fun bindingsFor(
    session: BindingResolutionSession,
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    val plan = editorPlan(session, queryContext)
    val visible =
      visibleBindingsFor(consumer, queryContext.graphModule, queryContext.resolutionScope)
    return applyReplaces(visible.filter { isBindingInContext(it, plan) })
  }

  /**
   * Per-context resolution of [consumer]: which bindings satisfy it in each concrete graph path,
   * plus the use-site-visible candidates as a fallback for files/projects without graphs.
   */
  fun resolveConsumer(consumer: ConsumerEntry): ConsumerResolution {
    return withResolutionSession { session -> session.resolveConsumer(consumer) }
  }

  internal fun resolveConsumer(
    session: BindingResolutionSession,
    consumer: ConsumerEntry,
  ): ConsumerResolution {
    return session.consumerResolution(consumer) {
      buildConsumerResolution(session, consumer)
    }
  }

  private fun buildConsumerResolution(
    session: BindingResolutionSession,
    consumer: ConsumerEntry,
  ): ConsumerResolution {
    val consumerView = session.resolutionViewFor(consumer.sourceIdentity, consumer.pointer)
    val global = visibleBindingsFor(consumer, consumerView?.module, consumerView?.resolutionScope)
    if (graphs.isEmpty()) {
      return ConsumerResolution(global, emptyMap(), hasGraphs = false, index = this)
    }

    val perContext = LinkedHashMap<GraphContext, List<KaBinding>>()
    val visibleByModule = HashMap<KaModule, List<KaBinding>>()
    for (context in candidateContextsFor(session, consumer)) {
      ProgressManager.checkCanceled()
      val queryContext = queryContext(session, context) ?: continue
      val plan = editorPlan(session, queryContext)
      if (!isConsumerInContext(consumer, plan)) continue
      val visible =
        visibleByModule.getOrPut(queryContext.graphModule) {
          visibleBindingsFor(
            consumer,
            queryContext.graphModule,
            queryContext.resolutionScope,
          )
        }
      perContext[context] = filterBindingsInContext(visible, plan)
    }
    return ConsumerResolution(global, perContext, hasGraphs = true, index = this)
  }

  private fun candidateContextsFor(
    session: BindingResolutionSession,
    consumer: ConsumerEntry,
  ): List<GraphContext> {
    val graphId = consumer.graphId
    if (graphId != null) {
      val candidateGraphs = lookups.graphsByReachableAncestor[graphId].orEmpty()
      return buildList {
        for ((index, graph) in candidateGraphs.withIndex()) {
          checkCanceledEvery(index)
          for (context in session.contextsFor(graph)) {
            ProgressManager.checkCanceled()
            // A graph may have several parent paths. Keep only paths containing this consumer's
            // owner.
            if (graphId in context.graphIds) add(context)
          }
        }
      }
    }

    if (consumer.contributionScopes.isNotEmpty()) {
      val candidateGraphs = linkedSetOf<KaGraphDeclaration>()
      for ((index, scope) in consumer.contributionScopes.withIndex()) {
        checkCanceledEvery(index)
        candidateGraphs += lookups.graphsByReachableScope[scope].orEmpty()
      }
      val contexts = linkedSetOf<GraphContext>()
      for ((index, graph) in candidateGraphs.withIndex()) {
        checkCanceledEvery(index)
        contexts += session.contextsFor(graph)
      }
      return contexts.toList()
    }

    return session.allGraphContexts()
  }

  /**
   * Filters precomputed [visible] candidates to those live in [queryContext]'s graph. Consumer-site
   * membership is checked separately so applicable contexts remain represented when this returns an
   * empty binding list.
   */
  private fun filterBindingsInContext(
    visible: List<KaBinding>,
    plan: GraphQueryPlan,
  ): List<KaBinding> {
    return applyReplaces(visible.filter { isBindingInContext(it, plan) })
  }

  /**
   * The bindings for [key] that are members of [queryContext]'s graph. Multibinding contributions
   * are resolved separately by [multibindingContributions].
   */
  fun bindingsForKey(
    key: KaTypeKey,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    return withResolutionSession { session -> session.bindingsForKey(key, queryContext) }
  }

  internal fun bindingsForKey(
    session: BindingResolutionSession,
    key: KaTypeKey,
    queryContext: GraphQueryContext,
  ): List<KaBinding> = bindingsForKey(key, validationPlan(session, queryContext))

  internal fun bindingsForKey(
    key: KaTypeKey,
    plan: GraphQueryPlan,
  ): List<KaBinding> {
    // Membership filtering already applies context-wide excludes and replaces from this plan.
    return lookups.bindingsByKey[key].orEmpty().withoutDuplicateAssistedFactories(key).filter {
      isBindingInContext(it, plan)
    }
  }

  /** All indexed bindings for the same unqualified type, regardless of graph membership. */
  fun bindingsWithType(key: KaTypeKey): List<KaBinding> {
    return lookups.bindingsByType[key.type].orEmpty().withoutDuplicateAssistedFactories()
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
    return lookups.assistedFactoriesByTarget[key].orEmpty().withoutDuplicateAssistedFactories()
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
    return lookups.consumersByKey[key].orEmpty()
  }

  /** Contributions collected into [multibindingId] in [queryContext]'s graph. */
  fun multibindingContributions(
    multibindingId: String,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    return withResolutionSession { session ->
      session.multibindingContributions(multibindingId, queryContext)
    }
  }

  internal fun multibindingContributions(
    session: BindingResolutionSession,
    multibindingId: String,
    queryContext: GraphQueryContext,
  ): List<KaBinding> =
    multibindingContributions(multibindingId, validationPlan(session, queryContext))

  internal fun multibindingContributions(
    multibindingId: String,
    plan: GraphQueryPlan,
  ): List<KaBinding> {
    return lookups.contributionsByMultibindingId[multibindingId].orEmpty().filter {
      isBindingInContext(it, plan)
    }
  }

  /**
   * Every binding that is a member of [queryContext]'s graph. Linear over all bindings, so call on
   * demand only.
   */
  fun bindingsInContext(queryContext: GraphQueryContext): List<KaBinding> {
    return withResolutionSession { session -> session.bindingsInContext(queryContext) }
  }

  internal fun bindingsInContext(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): List<KaBinding> {
    val plan = editorPlan(session, queryContext)
    val result = buildList {
      for ((index, binding) in bindings.withIndex()) {
        checkCanceledEvery(index)
        if (!binding.isValidationOnlyAssistedTarget() && isBindingInContext(binding, plan)) {
          add(binding)
        }
      }
    }
    return result.withoutDuplicateAssistedFactories()
  }

  /** The consumer sites declared on [graph] itself, used as seal roots. */
  fun accessorsFor(graph: KaGraphDeclaration): List<ConsumerEntry> {
    return lookups.accessorsByGraph[graph.declarationId].orEmpty().filter {
      it.graphContribution == null
    }
  }

  /** The actual seal roots after selecting this graph's contributed interface surface. */
  fun accessorsFor(queryContext: GraphQueryContext): List<ConsumerEntry> {
    return withResolutionSession { session -> session.accessorsFor(queryContext) }
  }

  internal fun accessorsFor(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): List<ConsumerEntry> {
    return graphComposition(session, queryContext).accessors
  }

  /** The selected surface of [graph] in this exact root module and ancestor suffix. */
  fun graphComposition(
    queryContext: GraphQueryContext,
    graph: KaGraphDeclaration = queryContext.graphContext.graph,
  ): GraphComposition {
    return withResolutionSession { session -> session.graphComposition(queryContext, graph) }
  }

  internal fun graphComposition(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
    graph: KaGraphDeclaration = queryContext.graphContext.graph,
  ): GraphComposition {
    val selected = selectedGraphComposition(session, queryContext, graph.declarationId)
    requireNotNull(selected) { "Graph is not in the requested parent path" }
    return selected.composition
  }

  internal fun graphComposition(
    plan: GraphQueryPlan,
    graph: KaGraphDeclaration = plan.structure.queryContext.graphContext.graph,
  ): GraphComposition {
    val selected = selectedGraphComposition(plan.structure, graph.declarationId)
    requireNotNull(selected) { "Graph is not in the requested parent path" }
    return selected.composition
  }

  private fun selectedGraphComposition(
    structure: GraphQueryStructure,
    ownerId: GraphDeclarationId,
  ): SelectedGraphComposition? {
    return structure.compositionsByOwner[ownerId]
  }

  private fun selectedGraphComposition(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
    ownerId: GraphDeclarationId,
  ): SelectedGraphComposition? {
    val context = queryContext.graphContext
    if (ownerId !in context.graphIds) return null
    val graphIndex = context.chain.indexOfFirst { graph -> graph.declarationId == ownerId }
    check(graphIndex >= 0) { "Graph is not in the requested parent path" }
    return selectedGraphComposition(
      session,
      context.chain.subList(graphIndex, context.chain.size),
      queryContext.graphModule,
      queryContext.resolutionScope,
    )
  }

  private fun selectedGraphComposition(
    session: BindingResolutionSession,
    chain: List<KaGraphDeclaration>,
    module: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): SelectedGraphComposition {
    val graphPath =
      GraphPath(
        chain.mapIndexed { index, graph ->
          checkCanceledEvery(index)
          graph.declarationId
        }
      )
    return session.graphComposition(graphPath, module) {
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
          val declaration = implementation.declaration
          if (
            !isVisibleFrom(
              declaration.pointer,
              declaration.sourceIdentity,
              null,
              module,
              resolutionScope,
            )
          )
            continue
          // A fake override can still point at the concrete declaration itself. Its optional
          // request, if any, is retained by isImplementedGraphRequest below.
          declaration.sourceIdentity?.let(identities::add)
          for (overridden in implementation.overriddenDeclarations) {
            overridden.sourceIdentity?.let(identities::add)
          }
        }
      }

      fun addAccessor(consumer: ConsumerEntry) {
        if (consumer.graphRequestKind == null) return
        val source = consumer.sourceIdentity
        val identity =
          if (source == null) consumer
          else
            GraphAccessorIdentity(
              source,
              consumer.contextKey,
              consumer.graphRequestKind,
              consumer.injectedMemberSourceIdentity,
              consumer.isOptional,
              consumer.isSuspend,
            )
        if (accessorIdentities.add(identity)) accessors += consumer
      }

      for ((index, consumer) in
        lookups.accessorsByGraph[graph.declarationId].orEmpty().withIndex()) {
        checkCanceledEvery(index)
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
        for ((bindingIndex, binding) in candidate.bindings.withIndex()) {
          checkCanceledEvery(bindingIndex)
          if (hasWrittenBinding(binding, graph)) continue
          val source = bindingSourceIdentity(binding)
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
        for ((consumerIndex, consumer) in candidate.consumers.withIndex()) {
          checkCanceledEvery(consumerIndex)
          addAccessor(consumer)
        }
      }
      val implementedRequests = implementedDeclarations.orEmpty()
      if (implementedRequests.isNotEmpty()) {
        accessors.removeAll { isImplementedGraphRequest(it, implementedRequests) }
      }
      SelectedGraphComposition(
        GraphComposition(
          typeKeys.toSet(),
          declarations.toSet(),
          creations.toSet(),
          factories.toList(),
          selectedContributions.toList(),
          accessors.toList(),
          memberOwners.toSet(),
        ),
        selection,
        contributionIds.toSet(),
        selectedBindings.toSet(),
        implementedRequests.toSet(),
      )
    }
  }

  private fun hasWrittenBinding(binding: KaBinding, graph: KaGraphDeclaration): Boolean {
    val source = bindingSourceIdentity(binding) ?: return false
    val specialization =
      SpecializedBindingIdentity(graph.declarationId, source, binding.javaClass, binding.typeKey)
    if (specialization in lookups.specializedBindingIdentities) return true
    val declaration = BindingDeclarationIdentity(source, binding.javaClass, binding.typeKey)
    return lookups.unownedBindingDeclarations[declaration].orEmpty().any { raw ->
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
    var excludeIndex = 0
    val excludes = buildSet {
      for (graph in chain) {
        checkCanceledEvery(excludeIndex++)
        for (excluded in graph.excludes) {
          checkCanceledEvery(excludeIndex++)
          add(excluded)
        }
      }
    }
    return selectContributions(
      chain.first().scopeKeys,
      excludes,
      module,
      resolutionScope,
    )
  }

  private fun selectContributions(
    scopes: Set<ClassId>,
    excludes: Set<ClassId>,
    module: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): ContributionSelection {
    val candidates =
      contributionsForScopes(scopes).filterIndexed { index, contribution ->
        checkCanceledEvery(index)
        isVisibleFrom(
          contribution.pointer,
          contribution.sourceIdentity,
          contribution.hintAvailability,
          module,
          resolutionScope,
        )
      }
    val byId = linkedMapOf<ClassId?, MutableList<ContributionEntry>>()
    for ((index, candidate) in candidates.withIndex()) {
      checkCanceledEvery(index)
      byId.getOrPut(candidate.classId, ::mutableListOf) += candidate
    }
    val presentIds = mutableSetOf<ClassId>()
    for ((index, classId) in byId.keys.withIndex()) {
      checkCanceledEvery(index)
      classId?.let(presentIds::add)
    }
    val nestedFactories = mutableMapOf<ClassId, MutableSet<ClassId>>()
    for ((index, contribution) in candidates.withIndex()) {
      checkCanceledEvery(index)
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
        ensureActive = ProgressManager::checkCanceled,
        replacesOf = { id ->
          buildSet {
            for ((index, contribution) in byId[id].orEmpty().withIndex()) {
              checkCanceledEvery(index)
              addAll(contribution.replaces)
            }
          }
        },
      )
    val selected = candidates.filterIndexed { index, contribution ->
      checkCanceledEvery(index)
      contribution.classId !in plan.removed
    }
    val declarationIds = mutableSetOf<GraphReference>()
    for ((index, contribution) in selected.withIndex()) {
      checkCanceledEvery(index)
      contribution.declarationId?.let(declarationIds::add)
    }
    return ContributionSelection(
      selected.toList(),
      declarationIds.toSet(),
      plan.removed.toSet(),
    )
  }

  /** The extension graphs created by [graph]'s accessors. */
  fun extensionsOf(graph: KaGraphDeclaration): List<KaGraphDeclaration> {
    if (graph.extensionCreations.isEmpty()) return emptyList()
    return buildList {
      for ((candidateIndex, candidate) in graphs.withIndex()) {
        checkCanceledEvery(candidateIndex)
        if (!candidate.isExtension) continue
        for ((referenceIndex, reference) in candidate.selfReferences.withIndex()) {
          checkCanceledEvery(referenceIndex)
          if (reference in graph.extensionCreations) {
            add(candidate)
            break
          }
        }
      }
    }
  }

  /** Child declarations created by the selected surface, excluding recursive parent paths. */
  fun extensionsOf(queryContext: GraphQueryContext): List<KaGraphDeclaration> {
    return withResolutionSession { session -> session.extensionsOf(queryContext) }
  }

  internal fun extensionsOf(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): List<KaGraphDeclaration> {
    return extensionsOf(queryContext, graphComposition(session, queryContext))
  }

  internal fun extensionsOf(plan: GraphQueryPlan): List<KaGraphDeclaration> {
    val queryContext = plan.structure.queryContext
    return extensionsOf(queryContext, graphComposition(plan))
  }

  private fun extensionsOf(
    queryContext: GraphQueryContext,
    composition: GraphComposition,
  ): List<KaGraphDeclaration> {
    val context = queryContext.graphContext
    val result = linkedSetOf<KaGraphDeclaration>()
    for ((referenceIndex, reference) in composition.extensionCreations.withIndex()) {
      checkCanceledEvery(referenceIndex)
      for ((candidateIndex, candidate) in
        lookups.graphsByReference[reference].orEmpty().withIndex()) {
        checkCanceledEvery(candidateIndex)
        if (!candidate.isExtension || candidate.declarationId in context.graphIds) continue
        if (
          !isVisibleFrom(
            candidate.pointer,
            candidate.sourceIdentity,
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
    return withResolutionSession { session -> session.contextsFor(graph) }
  }

  internal fun contextsFor(
    session: BindingResolutionSession,
    graph: KaGraphDeclaration,
  ): List<GraphContext> {
    return session.cachedContextsFor(graph) { buildContexts(session, graph) }
  }

  /** Builds the module-aware query view for [context], or null if its graph disappeared. */
  fun queryContext(context: GraphContext): GraphQueryContext? {
    return withResolutionSession { session -> session.queryContext(context) }
  }

  internal fun queryContext(
    session: BindingResolutionSession,
    context: GraphContext,
  ): GraphQueryContext? {
    session.plannedQuery(context)?.let {
      return it.queryContext
    }
    val sourceIdentity = context.dynamicGraph?.sourceIdentity ?: context.rootGraph.sourceIdentity
    val pointer = context.dynamicGraph?.pointer ?: context.rootGraph.pointer
    val view = session.resolutionViewFor(sourceIdentity, pointer) ?: return null
    val aggregateSelection =
      selectContributions(
        context.scopes,
        context.excludes,
        view.module,
        view.resolutionScope,
      )
    val containers = containersFor(context, view.module, view.resolutionScope, aggregateSelection)
    val queryContext = GraphQueryContext(context, view.module, view.resolutionScope, containers)
    val planned = BindingResolutionSession.PlannedGraphQuery(queryContext, aggregateSelection)
    ProgressManager.checkCanceled()
    return session.plannedQuery(context) { planned }.queryContext
  }

  /** Includes bindings with incompatible scopes so validation can report them. */
  internal fun validationPlan(queryContext: GraphQueryContext): GraphQueryPlan {
    return withResolutionSession { session -> session.validationPlan(queryContext) }
  }

  internal fun validationPlan(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): GraphQueryPlan {
    val published = session.plannedQuery(queryContext.graphContext)
    if (published == null || published.queryContext !== queryContext) {
      return createGraphQueryPlan(session, queryContext, includeIncompatibleScopes = true)
    }
    val structure = structureFor(session, published)
    return createGraphQueryPlan(structure, includeIncompatibleScopes = true)
  }

  private fun editorPlan(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): GraphQueryPlan {
    val published = session.plannedQuery(queryContext.graphContext)
    if (published == null || published.queryContext !== queryContext) {
      return createGraphQueryPlan(session, queryContext, includeIncompatibleScopes = false)
    }
    published.editorPlan?.let {
      return it
    }
    val structure = structureFor(session, published)
    val computed = createGraphQueryPlan(structure, includeIncompatibleScopes = false)
    ProgressManager.checkCanceled()
    published.editorPlan = computed
    return computed
  }

  private fun structureFor(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): GraphQueryStructure {
    val published = session.plannedQuery(queryContext.graphContext)
    if (published?.queryContext === queryContext) return structureFor(session, published)
    return createGraphQueryStructure(session, queryContext, aggregateSelection(queryContext))
  }

  private fun structureFor(
    session: BindingResolutionSession,
    published: BindingResolutionSession.PlannedGraphQuery,
  ): GraphQueryStructure {
    published.structure?.let {
      return it
    }
    val computed =
      createGraphQueryStructure(session, published.queryContext, published.aggregateSelection)
    ProgressManager.checkCanceled()
    published.structure = computed
    return computed
  }

  private fun aggregateSelection(queryContext: GraphQueryContext): ContributionSelection {
    val context = queryContext.graphContext
    return selectContributions(
      context.scopes,
      context.excludes,
      queryContext.graphModule,
      queryContext.resolutionScope,
    )
  }

  private fun createGraphQueryPlan(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
    includeIncompatibleScopes: Boolean,
  ): GraphQueryPlan {
    val aggregateSelection = aggregateSelection(queryContext)
    return createGraphQueryPlan(
      session,
      queryContext,
      aggregateSelection,
      includeIncompatibleScopes,
    )
  }

  private fun createGraphQueryPlan(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
    aggregateSelection: ContributionSelection,
    includeIncompatibleScopes: Boolean,
  ): GraphQueryPlan {
    val structure = createGraphQueryStructure(session, queryContext, aggregateSelection)
    return createGraphQueryPlan(structure, includeIncompatibleScopes)
  }

  private fun createGraphQueryPlan(
    structure: GraphQueryStructure,
    includeIncompatibleScopes: Boolean,
  ): GraphQueryPlan {
    val pruning = contributionPruning(structure, includeIncompatibleScopes)
    ProgressManager.checkCanceled()
    return GraphQueryPlan(structure, pruning, includeIncompatibleScopes)
  }

  private fun createGraphQueryStructure(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
    aggregateSelection: ContributionSelection,
  ): GraphQueryStructure {
    val context = queryContext.graphContext
    val compositionsByOwner =
      LinkedHashMap<GraphDeclarationId, SelectedGraphComposition>(context.chain.size)
    for ((graphIndex, graph) in context.chain.withIndex()) {
      checkCanceledEvery(graphIndex)
      compositionsByOwner[graph.declarationId] =
        selectedGraphComposition(
          session,
          context.chain.subList(graphIndex, context.chain.size),
          queryContext.graphModule,
          queryContext.resolutionScope,
        )
    }

    val currentOwner = context.graph
    val currentSelection = checkNotNull(compositionsByOwner[currentOwner.declarationId]).selection
    val ownContainers = buildOwnContainers(queryContext, currentOwner, currentSelection)
    val nearestInputOwners = buildNearestFactoryInputOwners(context)
    ProgressManager.checkCanceled()
    return GraphQueryStructure(
      queryContext,
      aggregateSelection,
      compositionsByOwner.toMap(),
      ownContainers,
      nearestInputOwners,
    )
  }

  private fun buildNearestFactoryInputOwners(context: GraphContext): NearestFactoryInputOwners {
    val dependencyOwners = mutableMapOf<KaTypeKey, GraphDeclarationId>()
    val containerOwners = mutableMapOf<KaTypeKey, GraphDeclarationId>()
    for ((graphIndex, graph) in context.chain.withIndex()) {
      checkCanceledEvery(graphIndex)
      for ((keyIndex, key) in graph.includedDependencies.withIndex()) {
        checkCanceledEvery(keyIndex)
        dependencyOwners.putIfAbsent(key, graph.declarationId)
      }
      for ((keyIndex, key) in graph.includedBindingContainers.withIndex()) {
        checkCanceledEvery(keyIndex)
        containerOwners.putIfAbsent(key, graph.declarationId)
      }
    }
    return NearestFactoryInputOwners(dependencyOwners.toMap(), containerOwners.toMap())
  }

  /** Finds the current index's context for a path retained across an index rebuild. */
  fun findContext(path: GraphPath): GraphContext? {
    return withResolutionSession { session -> session.findContext(path) }
  }

  internal fun findContext(
    session: BindingResolutionSession,
    path: GraphPath,
  ): GraphContext? {
    val graphSegment = path.segments.firstOrNull() ?: return null
    for ((graphIndex, graph) in graphs.withIndex()) {
      checkCanceledEvery(graphIndex)
      if (graph.declarationId != graphSegment) continue
      for ((contextIndex, context) in contextsFor(session, graph).withIndex()) {
        checkCanceledEvery(contextIndex)
        if (context.path == path) return context
      }
    }
    return null
  }

  /** Concrete child contexts created directly from [parent]'s exact graph path. */
  fun extensionContextsOf(parent: GraphContext): List<GraphContext> {
    return withResolutionSession { session -> session.extensionContextsOf(parent) }
  }

  internal fun extensionContextsOf(
    session: BindingResolutionSession,
    parent: GraphContext,
  ): List<GraphContext> {
    val queryContext = queryContext(session, parent) ?: return emptyList()
    return buildList {
      for ((extensionIndex, extension) in extensionsOf(session, queryContext).withIndex()) {
        checkCanceledEvery(extensionIndex)
        for ((contextIndex, child) in contextsFor(session, extension).withIndex()) {
          checkCanceledEvery(contextIndex)
          if (
            child.chain.drop(1) == parent.chain && child.dynamicGraph?.id == parent.dynamicGraph?.id
          ) {
            add(child)
          }
        }
      }
    }
  }

  /**
   * Contributions aggregated by [queryContext]'s graph itself: matched against the graph's own
   * aggregation scopes, minus excluded. Contributions a graph extension sees through its parent
   * chain are reported separately by [inheritedContributionsFor].
   */
  fun contributionsFor(queryContext: GraphQueryContext): List<ContributionEntry> {
    return withResolutionSession { session -> session.contributionsFor(queryContext) }
  }

  internal fun contributionsFor(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): List<ContributionEntry> {
    val plan = editorPlan(session, queryContext)
    val context = queryContext.graphContext
    val removedOrigins = plan.pruning.removedOrigins
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
    return withResolutionSession { session -> session.inheritedContributionsFor(queryContext) }
  }

  internal fun inheritedContributionsFor(
    session: BindingResolutionSession,
    queryContext: GraphQueryContext,
  ): List<ContributionEntry> {
    val plan = editorPlan(session, queryContext)
    val context = queryContext.graphContext
    val inheritedScopes = context.scopes - context.graph.scopeKeys
    val removedOrigins = plan.pruning.removedOrigins
    return contributionsForScopes(inheritedScopes).filter {
      it.classId !in context.excludes &&
        it.classId !in removedOrigins &&
        it.scopeKeys.none(context.graph.scopeKeys::contains) &&
        isVisibleFrom(it, queryContext)
    }
  }

  private fun buildContexts(
    session: BindingResolutionSession,
    graph: KaGraphDeclaration,
  ): List<GraphContext> {
    return buildList {
      for ((chainIndex, chain) in buildChains(session, graph, visited = setOf(graph)).withIndex()) {
        checkCanceledEvery(chainIndex)
        add(buildContext(session, chain, dynamicGraph = null))
        val root = chain.last()
        val rootReference =
          root.classId?.let { classId -> GraphReference(classId, root.pointer.virtualFile) }
        for ((dynamicIndex, dynamicGraph) in
          rootReference?.let(lookups.dynamicGraphsByTarget::get).orEmpty().withIndex()) {
          checkCanceledEvery(dynamicIndex)
          add(buildContext(session, chain, dynamicGraph))
        }
      }
    }
  }

  private fun buildChains(
    session: BindingResolutionSession,
    graph: KaGraphDeclaration,
    visited: Set<KaGraphDeclaration>,
  ): List<List<KaGraphDeclaration>> {
    if (!graph.isExtension) return listOf(listOf(graph))

    val parents = linkedSetOf<KaGraphDeclaration>()
    for ((referenceIndex, reference) in graph.selfReferences.withIndex()) {
      checkCanceledEvery(referenceIndex)
      for ((candidateIndex, candidate) in
        lookups.potentialParentsByReference[reference].orEmpty().withIndex()) {
        checkCanceledEvery(candidateIndex)
        if (candidate !in visited) parents += candidate
      }
    }
    if (parents.isEmpty()) return listOf(listOf(graph))

    val chains = mutableListOf<List<KaGraphDeclaration>>()
    for (parent in parents) {
      ProgressManager.checkCanceled()
      val parentChains = buildChains(session, parent, visited + parent)
      for ((chainIndex, parentChain) in parentChains.withIndex()) {
        checkCanceledEvery(chainIndex)
        val root = parentChain.last()
        val view = session.resolutionViewFor(root.sourceIdentity, root.pointer) ?: continue
        if (
          !isVisibleFrom(
            graph.pointer,
            graph.sourceIdentity,
            null,
            view.module,
            view.resolutionScope,
          )
        )
          continue
        val composition =
          selectedGraphComposition(
              session,
              parentChain,
              view.module,
              view.resolutionScope,
            )
            .composition
        if (composition.extensionCreations.none(graph.selfReferences::contains)) continue
        chains += listOf(graph) + parentChain
      }
    }
    return chains.ifEmpty { listOf(listOf(graph)) }
  }

  private fun buildContext(
    session: BindingResolutionSession,
    chain: List<KaGraphDeclaration>,
    dynamicGraph: DynamicGraphCall?,
  ): GraphContext {
    fun <T> collectFromChain(values: (KaGraphDeclaration) -> Iterable<T>): Set<T> {
      var workIndex = 0
      return buildSet {
        for (graph in chain) {
          checkCanceledEvery(workIndex++)
          for (value in values(graph)) {
            checkCanceledEvery(workIndex++)
            add(value)
          }
        }
      }
    }

    val scopes = collectFromChain { it.scopeKeys }
    val excludes = collectFromChain { it.excludes }
    // Supertype members merge into the graph, so their classes gate membership like the graph
    val graphClassIds = collectFromChain { it.selfIds + it.supertypeIds }
    var containerIndex = 0
    val includedBindingContainers = buildSet {
      for (graph in chain) {
        checkCanceledEvery(containerIndex++)
        for (container in graph.includedBindingContainers) {
          checkCanceledEvery(containerIndex++)
          add(container)
        }
      }
      for (container in dynamicGraph?.containerKeys.orEmpty()) {
        checkCanceledEvery(containerIndex++)
        add(container)
      }
    }
    val includedDependencies = collectFromChain { it.includedDependencies }
    val graphIds = buildSet {
      for ((index, graph) in chain.withIndex()) {
        checkCanceledEvery(index)
        add(graph.declarationId)
      }
    }
    val daggerAnvilInteropEnabled =
      if (dynamicGraph == null) {
        chain.last().daggerAnvilInteropEnabled
      } else {
        session
          .resolutionViewFor(dynamicGraph.sourceIdentity, dynamicGraph.pointer)
          ?.daggerAnvilInteropEnabled ?: chain.last().daggerAnvilInteropEnabled
      }
    return GraphContext(
      chain = chain,
      scopes = scopes,
      scopingAnnotations = collectFromChain { it.scopingAnnotations },
      excludes = excludes,
      includedBindingContainers = includedBindingContainers,
      includedDependencies = includedDependencies,
      injectedMemberOwnerIds = collectFromChain { it.injectedMemberOwnerIds },
      daggerAnvilInteropEnabled = daggerAnvilInteropEnabled,
      graphIds = graphIds,
      graphClassIds = graphClassIds,
      dynamicGraph = dynamicGraph,
    )
  }

  private fun containersFor(
    context: GraphContext,
    useSiteModule: KaModule,
    resolutionScope: DeclarationResolutionScope,
    aggregateSelection: ContributionSelection,
  ): Set<ClassId> {
    // Containers are declared on the graphs, contributed into scope, or transitively included.
    val containerRoots = hashSetOf<ClassId>()
    for ((graphIndex, graph) in context.chain.withIndex()) {
      checkCanceledEvery(graphIndex)
      containerRoots += graph.bindingContainers
    }
    for ((containerKeyIndex, containerKey) in context.includedBindingContainers.withIndex()) {
      checkCanceledEvery(containerKeyIndex)
      val containerId = containerKey.type.classId ?: continue
      for ((containerIndex, container) in
        visibleContainers(containerId, useSiteModule, resolutionScope).withIndex()) {
        checkCanceledEvery(containerIndex)
        for ((includeIndex, included) in container.includes.withIndex()) {
          checkCanceledEvery(includeIndex)
          containerRoots += included
        }
      }
    }
    for ((index, contribution) in aggregateSelection.entries.withIndex()) {
      checkCanceledEvery(index)
      val classId = contribution.classId ?: continue
      if (classId in lookups.containersById) containerRoots += classId
    }

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
    return withResolutionSession { session -> session.graphOwnContainers(graph, queryContext) }
  }

  internal fun graphOwnContainers(
    session: BindingResolutionSession,
    graph: KaGraphDeclaration,
    queryContext: GraphQueryContext,
  ): Set<ClassId> = graphOwnContainers(graph, structureFor(session, queryContext))

  internal fun graphOwnContainers(
    graph: KaGraphDeclaration,
    plan: GraphQueryPlan,
  ): Set<ClassId> = graphOwnContainers(graph, plan.structure)

  private fun graphOwnContainers(
    graph: KaGraphDeclaration,
    structure: GraphQueryStructure,
  ): Set<ClassId> {
    val ownerId = graph.declarationId
    val queryContext = structure.queryContext
    val context = queryContext.graphContext
    require(ownerId in context.graphIds) { "Graph is not in the requested parent path" }
    if (ownerId == context.graph.declarationId) return structure.currentOwnerOwnContainers

    val owner = context.chain.first { candidate -> candidate.declarationId == ownerId }
    val selection = checkNotNull(structure.compositionsByOwner[ownerId]).selection
    return buildOwnContainers(queryContext, owner, selection)
  }

  private fun buildOwnContainers(
    queryContext: GraphQueryContext,
    owner: KaGraphDeclaration,
    selection: ContributionSelection,
  ): Set<ClassId> {
    val context = queryContext.graphContext
    val roots = hashSetOf<ClassId>()
    roots += owner.bindingContainers
    for ((index, containerKey) in owner.includedBindingContainers.withIndex()) {
      checkCanceledEvery(index)
      containerKey.type.classId?.let(roots::add)
    }
    if (owner.declarationId == context.rootGraph.declarationId) {
      for ((index, containerKey) in context.dynamicGraph?.containerKeys.orEmpty().withIndex()) {
        checkCanceledEvery(index)
        containerKey.type.classId?.let(roots::add)
      }
    }
    for ((index, contribution) in selection.entries.withIndex()) {
      checkCanceledEvery(index)
      val classId = contribution.classId ?: continue
      if (classId in lookups.containersById) roots += classId
    }
    return resolveContainerClosure(roots, queryContext.graphModule, queryContext.resolutionScope)
  }

  /** Whether [binding] belongs to this graph itself rather than one of its ancestors. */
  fun isBindingOwnedByCurrentGraph(
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean {
    return withResolutionSession { session ->
      session.isBindingOwnedByCurrentGraph(binding, queryContext)
    }
  }

  internal fun isBindingOwnedByCurrentGraph(
    session: BindingResolutionSession,
    binding: KaBinding,
    queryContext: GraphQueryContext,
  ): Boolean = isBindingOwnedByCurrentGraph(binding, structureFor(session, queryContext))

  internal fun isBindingOwnedByCurrentGraph(
    binding: KaBinding,
    plan: GraphQueryPlan,
  ): Boolean = isBindingOwnedByCurrentGraph(binding, plan.structure)

  private fun isBindingOwnedByCurrentGraph(
    binding: KaBinding,
    structure: GraphQueryStructure,
  ): Boolean {
    val queryContext = structure.queryContext
    val context = queryContext.graphContext
    val graph = context.graph
    val ownerGraphId = binding.ownerGraphId
    if (ownerGraphId != null) {
      if (binding is KaBinding.BoundInstance) {
        return isFactoryInputOwnedBy(binding, graph.declarationId)
      }
      return ownerGraphId == graph.declarationId
    }
    val includedContainerKey = binding.includedContainerKey
    if (includedContainerKey != null) {
      if (includedContainerKey in graph.includedBindingContainers) return true
      val isDynamicRoot = graph.declarationId == context.rootGraph.declarationId
      return isDynamicRoot && includedContainerKey in context.dynamicGraph?.containerKeys.orEmpty()
    }

    val containerId = binding.containerId
    if (containerId != null) {
      val composition =
        checkNotNull(selectedGraphComposition(structure, graph.declarationId)).composition
      return containerId in graph.selfIds ||
        composition.supertypeDeclarations.any { it.classId == containerId } ||
        containerId in structure.currentOwnerOwnContainers
    }

    if (binding.contributionScopes.isNotEmpty()) {
      return binding.contributionScopes.any { it in graph.scopeKeys }
    }

    return true
  }

  /** Whether an ancestor can resolve [key] through one of its private bindings. */
  fun hasPrivateAncestorBinding(key: KaTypeKey, queryContext: GraphQueryContext): Boolean {
    return withResolutionSession { session ->
      session.hasPrivateAncestorBinding(key, queryContext)
    }
  }

  internal fun hasPrivateAncestorBinding(
    session: BindingResolutionSession,
    key: KaTypeKey,
    queryContext: GraphQueryContext,
  ): Boolean {
    return hasPrivateAncestorBinding(session, key, structureFor(session, queryContext))
  }

  private fun hasPrivateAncestorBinding(
    session: BindingResolutionSession,
    key: KaTypeKey,
    structure: GraphQueryStructure,
  ): Boolean {
    val candidates = lookups.bindingsByKey[key].orEmpty()
    val hasPrivateCandidate =
      candidates.withIndex().any { (index, binding) ->
        checkCanceledEvery(index)
        binding.isGraphPrivate
      }
    if (!hasPrivateCandidate) {
      return false
    }

    val context = structure.queryContext.graphContext
    val chain = context.chain
    for (ancestorIndex in 1 until chain.size) {
      checkCanceledEvery(ancestorIndex - 1)
      val ancestorSegments = ArrayList<GraphDeclarationId>(chain.size - ancestorIndex)
      for (chainIndex in ancestorIndex until chain.size) {
        checkCanceledEvery(chainIndex - ancestorIndex)
        ancestorSegments += chain[chainIndex].declarationId
      }
      val ancestorPath =
        GraphPath(
          ancestorSegments,
          context.dynamicGraph?.id,
        )
      val ancestorContext = findContext(session, ancestorPath) ?: continue
      val ancestorQueryContext = queryContext(session, ancestorContext) ?: continue
      val ancestorStructure = structureFor(session, ancestorQueryContext)
      for ((candidateIndex, binding) in candidates.withIndex()) {
        checkCanceledEvery(candidateIndex)
        if (
          binding.isGraphPrivate &&
            isBindingCandidateInContext(
              binding,
              ancestorStructure,
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
    var workIndex = 0
    while (queue.isNotEmpty()) {
      checkCanceledEvery(workIndex++)
      val id = queue.removeFirst()
      containers += id
      for (container in visibleContainers(id, useSiteModule, resolutionScope)) {
        checkCanceledEvery(workIndex++)
        if (!visitedDeclarations.add(container.declarationId)) continue
        for (included in container.includes) {
          checkCanceledEvery(workIndex++)
          queue.add(included)
        }
      }
    }
    return containers.toSet()
  }

  private fun visibleContainers(
    classId: ClassId,
    useSiteModule: KaModule,
    resolutionScope: DeclarationResolutionScope,
  ): List<BindingContainerEntry> {
    return lookups.containersById[classId].orEmpty().filter { container ->
      isVisibleFrom(
        container.pointer,
        container.sourceIdentity,
        null,
        useSiteModule,
        resolutionScope,
      )
    }
  }

  private fun visibleBindingsFor(
    consumer: ConsumerEntry,
    useSiteModule: KaModule?,
    resolutionScope: DeclarationResolutionScope?,
  ): List<KaBinding> {
    return candidateBindingsFor(consumer).filter {
      isVisibleFrom(
        it.pointer,
        bindingSourceIdentity(it),
        it.hintAvailability,
        useSiteModule,
        resolutionScope,
      )
    }
  }

  private fun candidateBindingsFor(consumer: ConsumerEntry): List<KaBinding> {
    // Assisted targets are kept in the index for graph diagnostics, but are never ordinary
    // injectable bindings for editor resolution or navigation.
    val direct =
      lookups.bindingsByKey[consumer.key]
        .orEmpty()
        .withoutDuplicateAssistedFactories(consumer.key)
        .filterNot {
          it.isValidationOnlyAssistedTarget()
        }
    val contributions =
      consumer.multibindingId?.let { lookups.contributionsByMultibindingId[it] }.orEmpty()
    return direct + contributions
  }

  internal fun isConsumerInContext(
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): Boolean {
    return withResolutionSession { session ->
      session.isConsumerInContext(consumer, queryContext)
    }
  }

  internal fun isConsumerInContext(
    session: BindingResolutionSession,
    consumer: ConsumerEntry,
    queryContext: GraphQueryContext,
  ): Boolean = isConsumerInContext(consumer, editorPlan(session, queryContext))

  private fun isConsumerInContext(
    consumer: ConsumerEntry,
    plan: GraphQueryPlan,
  ): Boolean {
    val structure = plan.structure
    val queryContext = structure.queryContext
    if (
      !isVisibleFrom(
        consumer.pointer,
        consumer.sourceIdentity,
        hintAvailability = null,
        useSiteModule = queryContext.graphModule,
        resolutionScope = queryContext.resolutionScope,
      )
    ) {
      return false
    }
    val context = queryContext.graphContext
    if (!isContributedConsumerActive(consumer, structure)) return false

    val isUnspecializedContainerConsumer = consumer.graphId == null && consumer.containerId != null
    if (isUnspecializedContainerConsumer) {
      if (isSupersededInheritedConsumer(consumer, structure)) return false
    }

    val originClassId = consumer.originClassId
    if (originClassId != null) {
      if (originClassId in context.excludes) return false
      // A replaced origin's consumers stay live only while it still has surviving bindings
      if (
        originClassId in plan.pruning.replacedOrigins &&
          !hasOriginBindingInContext(originClassId, plan)
      ) {
        return false
      }
    }

    val graphId = consumer.graphId
    if (graphId != null) {
      if (graphId !in context.graphIds) return false
      if (consumer.graphRequestKind == null || consumer.isOptional) return true
      val selected = selectedGraphComposition(structure, graphId) ?: return false
      return !isImplementedGraphRequest(consumer, selected.implementedRequests)
    }

    val memberOwnerClassId = consumer.memberOwnerClassId
    if (memberOwnerClassId != null) {
      if (memberOwnerClassId in context.excludes) return false
      return memberOwnerClassId in context.injectedMemberOwnerIds ||
        context.chain.any { graph ->
          memberOwnerClassId in graphComposition(plan, graph).injectedMemberOwnerIds
        } ||
        lookups.bindingsByMemberOwner[memberOwnerClassId].orEmpty().any { binding ->
          isBindingInContext(binding, plan)
        }
    }

    val includedContainerKey = consumer.includedContainerKey
    if (includedContainerKey != null) {
      return includedContainerKey in context.includedBindingContainers
    }

    val containerId = consumer.containerId
    if (containerId != null) {
      return isGraphMemberContainer(containerId, consumer.pointer.virtualFile, structure) ||
        containerId in queryContext.containers
    }

    if (consumer.contributionScopes.isNotEmpty()) {
      return consumer.contributionScopes.any { it in context.scopes }
    }

    if (originClassId != null) {
      return hasOriginBindingInContext(originClassId, plan)
    }

    return true
  }

  private fun hasOriginBindingInContext(
    originClassId: ClassId,
    plan: GraphQueryPlan,
  ): Boolean {
    return lookups.bindingsByOrigin[originClassId].orEmpty().any { binding ->
      isBindingInContext(binding, plan)
    }
  }

  private fun isBindingInContext(
    entry: KaBinding,
    plan: GraphQueryPlan,
  ): Boolean {
    if (
      !isBindingCandidateInContext(
        entry,
        plan.structure,
        plan.includeIncompatibleScopes,
      )
    ) {
      return false
    }
    // Replaces removes the origin's contributions only; its own injectable type stays available
    // (a replacing stub can inject the replaced implementation directly).
    if (entry.contributionScopes.isEmpty()) return true
    val originClassId = entry.originClassId ?: return true
    if (originClassId in plan.pruning.replacedOrigins) return false
    return entry !in plan.pruning.lowerPriorityBindings
  }

  private fun isBindingCandidateInContext(
    entry: KaBinding,
    structure: GraphQueryStructure,
    includeIncompatibleScopes: Boolean,
  ): Boolean {
    val queryContext = structure.queryContext
    if (!isVisibleFrom(entry, queryContext)) return false
    if (!isContributedBindingActive(entry, structure)) return false
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
      if (isSupersededInheritedBinding(entry, structure)) return false
    }
    if (entry.isGraphPrivate && !isBindingOwnedByCurrentGraph(entry, structure)) return false
    val context = queryContext.graphContext
    if (isSupersededByDynamicBinding(entry, context)) return false
    val ownerGraphId = entry.ownerGraphId
    if (ownerGraphId != null) {
      if (entry is KaBinding.BoundInstance) {
        val ownedByContext =
          ownerGraphId in context.graphIds ||
            context.graphIds.any { it in entry.additionalOwnerGraphIds }
        if (!ownedByContext) return false
        if (isSupersededByNearerFactoryInput(entry, structure)) return false
      } else {
        if (ownerGraphId !in context.graphIds) return false
        if (isSupersededByNearerInheritedBinding(entry, structure)) return false
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
            isGraphMemberContainer(containerId, entry.pointer.virtualFile, structure) ||
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

  private fun isSupersededByDynamicBinding(
    binding: KaBinding,
    context: GraphContext,
  ): Boolean {
    val dynamicGraph = context.dynamicGraph ?: return false
    if (binding.multibindingId != null) return false
    val hasDynamicReplacement =
      binding.typeKey in dynamicGraph.bindingKeys || binding.typeKey in dynamicGraph.containerKeys
    if (!hasDynamicReplacement) return false

    val includedContainerKey = binding.includedContainerKey
    if (includedContainerKey != null && includedContainerKey in dynamicGraph.containerKeys) {
      return false
    }
    if (binding is KaBinding.BoundInstance && binding.isBindingContainerInput) {
      val source = bindingSourceIdentity(binding)
      val isDynamicInput =
        dynamicGraph.containerInputs.any { input ->
          input.typeKey == binding.typeKey && bindingSourceIdentity(input) == source
        }
      if (isDynamicInput) return false
    }
    return true
  }

  private fun isContributedBindingActive(
    binding: KaBinding,
    structure: GraphQueryStructure,
  ): Boolean {
    if (binding !in lookups.contributedBindingOwners) return true
    val ownerId = binding.ownerGraphId ?: return false
    val selected = selectedGraphComposition(structure, ownerId) ?: return false
    return binding in selected.bindings
  }

  private fun isContributedConsumerActive(
    consumer: ConsumerEntry,
    structure: GraphQueryStructure,
  ): Boolean {
    val contribution = consumer.graphContribution ?: return true
    val ownerId = consumer.graphId ?: return false
    val selected = selectedGraphComposition(structure, ownerId) ?: return false
    return contribution in selected.contributionIds
  }

  /** Uses the same precomputed selection for graph roots and source/library dependency seeding. */
  private fun isImplementedGraphRequest(
    consumer: ConsumerEntry,
    implementedRequests: Set<SourcePointerIdentity>,
  ): Boolean {
    if (consumer.graphRequestKind == null || consumer.isOptional) return false
    if (implementedRequests.isEmpty()) return false
    val source = consumer.sourceIdentity ?: return false
    return source in implementedRequests
  }

  private fun isGraphMemberContainer(
    containerId: ClassId,
    file: VirtualFile?,
    structure: GraphQueryStructure,
  ): Boolean {
    val queryContext = structure.queryContext
    val context = queryContext.graphContext
    if (containerId in context.graphClassIds) return true
    val reference = GraphReference(containerId, file)
    for ((index, graph) in context.chain.withIndex()) {
      checkCanceledEvery(index)
      val composition = selectedGraphComposition(structure, graph.declarationId)?.composition
      if (composition != null && reference in composition.supertypeDeclarations) return true
    }
    return false
  }

  private fun isSupersededInheritedBinding(
    binding: KaBinding,
    structure: GraphQueryStructure,
  ): Boolean {
    val pointerIdentity = bindingSourceIdentity(binding) ?: return false
    for ((graphIndex, graphId) in structure.queryContext.graphContext.graphIds.withIndex()) {
      checkCanceledEvery(graphIndex)
      val identity = SpecializedDeclarationIdentity(graphId, pointerIdentity, binding.javaClass)
      if (identity in lookups.specializedDeclarationIdentities) return true
      for ((candidateIndex, candidate) in
        lookups.contributedSpecializedDeclarations[identity].orEmpty().withIndex()) {
        checkCanceledEvery(candidateIndex)
        if (isContributedBindingActive(candidate, structure)) return true
      }
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
    structure: GraphQueryStructure,
  ): Boolean {
    val context = structure.queryContext.graphContext
    if (context.chain.size < 2) return false
    if (!binding.isGraphInput && !binding.isBindingContainerInput) return false

    val inputOwners = structure.nearestFactoryInputOwners
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
    structure: GraphQueryStructure,
  ): Boolean {
    val queryContext = structure.queryContext
    val context = queryContext.graphContext
    if (context.chain.size < 2) return false
    val ownerGraphId = binding.ownerGraphId ?: return false
    val sourceIdentity = bindingSourceIdentity(binding) ?: return false
    for ((graphIndex, graph) in context.chain.withIndex()) {
      checkCanceledEvery(graphIndex)
      val graphId = graph.declarationId
      if (graphId == ownerGraphId) return false
      val identity =
        SpecializedBindingIdentity(graphId, sourceIdentity, binding.javaClass, binding.typeKey)
      // An intermediate graph's private declaration cannot hide a public farther ancestor from a
      // grandchild. A private declaration is only available to the graph that owns it.
      if (identity in lookups.specializedBindingIdentities) {
        if (graphId == context.graph.declarationId) return true
        if (lookups.specializedBindingIdentities[identity] == true) return true
      }
      for ((candidateIndex, candidate) in
        lookups.contributedSpecializedBindings[identity].orEmpty().withIndex()) {
        checkCanceledEvery(candidateIndex)
        if (!isContributedBindingActive(candidate, structure)) continue
        if (graphId == context.graph.declarationId || !candidate.isGraphPrivate) return true
      }
    }
    return false
  }

  private fun isSupersededInheritedConsumer(
    consumer: ConsumerEntry,
    structure: GraphQueryStructure,
  ): Boolean {
    val pointerIdentity = consumer.sourceIdentity ?: return false
    for ((graphIndex, graphId) in structure.queryContext.graphContext.graphIds.withIndex()) {
      checkCanceledEvery(graphIndex)
      val identity = SpecializedConsumerIdentity(graphId, pointerIdentity)
      if (identity in lookups.specializedConsumerIdentities) return true
      for ((candidateIndex, candidate) in
        lookups.contributedSpecializedConsumers[identity].orEmpty().withIndex()) {
        checkCanceledEvery(candidateIndex)
        if (isContributedConsumerActive(candidate, structure)) return true
      }
    }
    return false
  }

  private fun <T : KaBinding> List<T>.withoutDuplicateAssistedFactories(
    requestedKey: KaTypeKey? = null
  ): List<T> {
    if (size < 2) return this
    if (requestedKey != null && requestedKey !in lookups.duplicatedAssistedFactoryKeys) return this
    if (requestedKey == null && none { it is KaBinding.AssistedFactory }) return this
    if (requestedKey == null && lookups.duplicatedAssistedFactoryKeys.isEmpty()) return this
    val seen = HashSet<Triple<ClassId?, VirtualFile?, KaTypeKey>>()
    return filter { binding ->
      binding !is KaBinding.AssistedFactory ||
        seen.add(Triple(binding.originClassId, binding.pointer.virtualFile, binding.typeKey))
    }
  }

  internal fun pointerIdentity(pointer: SmartPsiElementPointer<*>): SourcePointerIdentity? {
    return resolutionInputs.sourceIdentity(pointer)
  }

  private fun bindingSourceIdentity(binding: KaBinding): SourcePointerIdentity? {
    lookups.bindingSourceIdentities[binding]?.let {
      return it
    }
    return null
  }

  internal fun sourceIdentityFor(binding: KaBinding): SourcePointerIdentity? {
    return bindingSourceIdentity(binding)
  }

  private fun isVisibleFrom(entry: KaBinding, queryContext: GraphQueryContext): Boolean {
    return isVisibleFrom(
      entry.pointer,
      bindingSourceIdentity(entry),
      entry.hintAvailability,
      queryContext.graphModule,
      queryContext.resolutionScope,
    )
  }

  private fun isVisibleFrom(
    entry: ContributionEntry,
    queryContext: GraphQueryContext,
  ): Boolean {
    return isVisibleFrom(
      entry.pointer,
      entry.sourceIdentity,
      entry.hintAvailability,
      queryContext.graphModule,
      queryContext.resolutionScope,
    )
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
      val sourceIdentity = bindingSourceIdentity(binding)
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
      val sourceIdentity = bindingSourceIdentity(binding)
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

  private data class BindingResolutionIdentity(
    val pointer: SourcePointerIdentity,
    val bindingClass: Class<*>,
    val contextKey: KaContextualTypeKey,
    val dependencies: List<KaContextualTypeKey>,
  )

  internal class ContributionSelection(
    val entries: List<ContributionEntry>,
    val declarationIds: Set<GraphReference>,
    val removed: Set<ClassId>,
  )

  internal class SelectedGraphComposition(
    val composition: GraphComposition,
    val selection: ContributionSelection,
    val contributionIds: Set<GraphReference>,
    val bindings: Set<KaBinding>,
    val implementedRequests: Set<SourcePointerIdentity>,
  )

  internal class GraphQueryStructure(
    val queryContext: GraphQueryContext,
    val aggregateSelection: ContributionSelection,
    val compositionsByOwner: Map<GraphDeclarationId, SelectedGraphComposition>,
    val currentOwnerOwnContainers: Set<ClassId>,
    val nearestFactoryInputOwners: NearestFactoryInputOwners,
  )

  internal class ContributionPruning(
    val replacedOrigins: Set<ClassId>,
    val lowerPriorityBindings: Set<KaBinding>,
    val removedOrigins: Set<ClassId>,
  )

  internal class GraphQueryPlan(
    val structure: GraphQueryStructure,
    val pruning: ContributionPruning,
    val includeIncompatibleScopes: Boolean,
  )

  private data class GraphAccessorIdentity(
    val pointer: SourcePointerIdentity,
    val contextKey: KaContextualTypeKey,
    val kind: ConsumerEntry.GraphRequestKind,
    val injectedMemberPointer: SourcePointerIdentity?,
    val optional: Boolean,
    val isSuspend: Boolean,
  )

  internal data class NearestFactoryInputOwners(
    val dependencies: Map<KaTypeKey, GraphDeclarationId>,
    val containers: Map<KaTypeKey, GraphDeclarationId>,
  )

  private data class MapPriorityKey(
    val multibindingId: String,
    val mapKeyValue: String,
  )

  private fun contributionPruning(
    structure: GraphQueryStructure,
    includeIncompatibleScopes: Boolean,
  ): ContributionPruning {
    val replaced = replacedOrigins(structure, includeIncompatibleScopes)
    val lowerPriority = lowerPriorityBindings(structure, includeIncompatibleScopes, replaced)
    val removed =
      removedContributionOrigins(
        structure,
        includeIncompatibleScopes,
        replaced,
        lowerPriority,
      )
    return ContributionPruning(replaced, lowerPriority, removed)
  }

  private fun replacedOrigins(
    structure: GraphQueryStructure,
    includeIncompatibleScopes: Boolean,
  ): Set<ClassId> {
    // Replacements may come from interface contributions with no binding. Start with the shared
    // merge plan, then add replacements from active bindings.
    return buildSet {
      addAll(structure.aggregateSelection.removed)
      for ((index, binding) in lookups.bindingsWithReplacements.withIndex()) {
        checkCanceledEvery(index)
        if (!isBindingCandidateInContext(binding, structure, includeIncompatibleScopes)) continue
        addAll(binding.replaces)
      }
    }
  }

  /** Priority applies to individual surviving contributed bindings after explicit replacements. */
  private fun lowerPriorityBindings(
    structure: GraphQueryStructure,
    includeIncompatibleScopes: Boolean,
    replaced: Set<ClassId>,
  ): Set<KaBinding> {
    val prioritized = buildList {
      for ((index, binding) in lookups.priorityEligibleBindings.withIndex()) {
        checkCanceledEvery(index)
        val originClassId = binding.originClassId ?: continue
        if (
          originClassId !in replaced &&
            isBindingCandidateInContext(binding, structure, includeIncompatibleScopes)
        ) {
          add(binding)
        }
      }
    }
    if (prioritized.size < 2) return emptySet()

    val lowerPriority = mutableSetOf<KaBinding>()
    for ((graphIndex, graph) in structure.queryContext.graphContext.chain.withIndex()) {
      checkCanceledEvery(graphIndex)
      val levelBindings = prioritized.filterIndexed { index, binding ->
        checkCanceledEvery(index)
        binding.contributionScopes.any(graph.scopeKeys::contains)
      }
      lowerPriority += selectLowerPriorityBindings(levelBindings, structure)
    }
    return lowerPriority.toSet()
  }

  private fun removedContributionOrigins(
    structure: GraphQueryStructure,
    includeIncompatibleScopes: Boolean,
    replaced: Set<ClassId>,
    lowerPriority: Set<KaBinding>,
  ): Set<ClassId> {
    if (lowerPriority.isEmpty()) return replaced

    val queryContext = structure.queryContext
    return buildSet {
      addAll(replaced)
      for ((index, binding) in lowerPriority.withIndex()) {
        checkCanceledEvery(index)
        val originClassId = binding.originClassId ?: continue
        if (originClassId in this) continue

        val hasSurvivingBinding =
          lookups.bindingsByOrigin[originClassId].orEmpty().any { candidate ->
            candidate.contributionScopes.isNotEmpty() &&
              candidate !in lowerPriority &&
              isBindingCandidateInContext(candidate, structure, includeIncompatibleScopes)
          }
        if (hasSurvivingBinding) continue

        val hasNonBindingContribution =
          lookups.contributionsByOrigin[originClassId].orEmpty().withIndex().any {
            (contributionIndex, contribution) ->
            checkCanceledEvery(contributionIndex)
            contribution.kind != ContributionEntry.Kind.OTHER &&
              contribution.scopeKeys.any(queryContext.graphContext.scopes::contains) &&
              isVisibleFrom(contribution, queryContext)
          }
        if (!hasNonBindingContribution) add(originClassId)
      }
    }
  }

  private fun selectLowerPriorityBindings(
    candidates: List<KaBinding>,
    structure: GraphQueryStructure,
  ): Set<KaBinding> {
    val interopEnabled = structure.queryContext.graphContext.daggerAnvilInteropEnabled
    return computeLowerPriorityContributions(
      candidates,
      ensureActive = ProgressManager::checkCanceled,
      conflictKeySelector = { binding ->
        val multibindingId = binding.multibindingId
        if (multibindingId == null) {
          binding.typeKey
        } else {
          MapPriorityKey(multibindingId, checkNotNull(binding.mapKeyValue))
        }
      },
      prioritySelector = { binding ->
        if (binding.priorityFromAnvilRank && !interopEnabled) {
          Int.MIN_VALUE
        } else {
          binding.priority
        }
      },
    )
  }

  /** Drops bindings replaced by other surviving contributions, via the shared merge engine. */
  private fun applyReplaces(entries: List<KaBinding>): List<KaBinding> {
    return applyExcludesAndReplaces(entries, ensureActive = ProgressManager::checkCanceled)
  }

  /** Sites consuming any of [bindingEntries], joining multibinding contributions by id. */
  fun consumersFor(
    bindingEntries: Collection<KaBinding>,
    graphPath: GraphPath? = null,
  ): List<ConsumerEntry> {
    return withResolutionSession { session -> session.consumersFor(bindingEntries, graphPath) }
  }

  internal fun consumersFor(
    session: BindingResolutionSession,
    bindingEntries: Collection<KaBinding>,
    graphPath: GraphPath? = null,
  ): List<ConsumerEntry> {
    val bindingSet = bindingEntries.toSet()
    val result = LinkedHashSet<ConsumerEntry>()
    val candidates = LinkedHashSet<ConsumerEntry>()
    for (entry in bindingSet) {
      val multibindingId = entry.multibindingId
      if (multibindingId != null) {
        candidates += lookups.consumersByMultibindingId[multibindingId].orEmpty()
      } else {
        candidates += lookups.consumersByKey[entry.typeKey].orEmpty()
      }
    }
    if (graphs.isEmpty()) return candidates.toList()

    for (consumer in candidates) {
      val resolution = resolveConsumer(session, consumer)
      val pinnedBindings = graphPath?.let { resolution.perContext.matchingContextEntry(it)?.value }
      val resolvesToEntry =
        if (pinnedBindings != null) {
          pinnedBindings.any { it in bindingSet }
        } else {
          resolution.perContext.values.any { contextBindings ->
            contextBindings.any { it in bindingSet }
          }
        }
      if (resolvesToEntry) {
        result += consumer
      }
    }
    return result.toList()
  }

  fun bindingEntriesAt(element: KtElement): List<KaBinding> {
    val file = element.containingFile?.virtualFile ?: return emptyList()
    return lookups.bindingsByFile[file].orEmpty().withoutDuplicateAssistedFactories().filter {
      !it.isValidationOnlyAssistedTarget() && it.pointer.element === element
    }
  }

  internal fun bindingEntriesInFile(file: VirtualFile): List<KaBinding> {
    return lookups.bindingsByFile[file].orEmpty().withoutDuplicateAssistedFactories().filterNot {
      it.isValidationOnlyAssistedTarget()
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

  internal fun consumerEntriesInFile(file: VirtualFile): List<ConsumerEntry> {
    val entries = lookups.consumersByFile[file].orEmpty()
    val specializedIdentities =
      entries
        .asSequence()
        .filter { it.graphId != null && it.graphRequestKind == null }
        .mapNotNull { it.sourceIdentity }
        .toSet()
    if (specializedIdentities.isEmpty()) return entries
    return entries.filter { entry ->
      val identity = entry.sourceIdentity
      identity == null || identity !in specializedIdentities || entry.graphId != null
    }
  }

  /** All consumer entries anchored at [element]. Injector members anchor one per injected key. */
  fun consumerEntriesAt(element: KtElement): List<ConsumerEntry> {
    val file = element.containingFile?.virtualFile ?: return emptyList()
    val entries = lookups.consumersByFile[file].orEmpty().filter { it.pointer.element === element }
    val hasSpecializedEntries = entries.any { it.graphId != null && it.graphRequestKind == null }
    if (!hasSpecializedEntries) return entries
    return entries.filter { it.graphId != null }
  }

  fun graphEntryAt(element: KtElement): KaGraphDeclaration? {
    val file = element.containingFile?.virtualFile ?: return null
    return lookups.graphsByFile[file].orEmpty().firstOrNull { it.pointer.element === element }
  }

  internal fun graphEntriesInFile(file: VirtualFile): List<KaGraphDeclaration> {
    return lookups.graphsByFile[file].orEmpty()
  }

  /** Refreshes a retained graph declaration against this index. */
  fun graphFor(graph: KaGraphDeclaration): KaGraphDeclaration? {
    return graphs.firstOrNull { it.declarationId == graph.declarationId }
  }

  fun assistedSiteAt(element: KtElement): AssistedSite? {
    val file = element.containingFile?.virtualFile ?: return null
    return lookups.assistedSitesByFile[file].orEmpty().firstOrNull {
      it.pointer.element === element
    }
  }

  internal fun assistedSitesInFile(file: VirtualFile): List<AssistedSite> {
    return lookups.assistedSitesByFile[file].orEmpty()
  }

  fun contributionsForScopes(scopeKeys: Set<ClassId>): List<ContributionEntry> {
    if (scopeKeys.isEmpty()) return emptyList()
    if (scopeKeys.size == 1) return lookups.contributionsByScope[scopeKeys.first()].orEmpty()
    val result = linkedSetOf<ContributionEntry>()
    for ((index, scope) in scopeKeys.withIndex()) {
      checkCanceledEvery(index)
      result += lookups.contributionsByScope[scope].orEmpty()
    }
    return result.toList()
  }

  fun graphsForScopes(scopeKeys: Set<ClassId>): List<KaGraphDeclaration> {
    if (scopeKeys.isEmpty()) return emptyList()
    return graphs.filterIndexed { index, graph ->
      checkCanceledEvery(index)
      graph.scopeKeys.any(scopeKeys::contains)
    }
  }

  companion object {
    internal fun fromBuilder(data: FrozenBindingIndexData): BindingIndex = BindingIndex(data)

    val EMPTY =
      BindingIndexBuilder(IndexGenerationToken.EMPTY)
        .apply {
          resolutionInputs =
            BindingIndexResolutionInputs(FileOrdinalTable.EMPTY, emptyMap(), emptyMap())
          capturedBindingSourceIdentities = emptyMap()
        }
        .build()
  }
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

private fun isVisibleFrom(
  pointer: SmartPsiElementPointer<*>,
  sourceIdentity: BindingIndex.SourcePointerIdentity?,
  hintAvailability: HintAvailability?,
  useSiteModule: KaModule?,
  resolutionScope: DeclarationResolutionScope?,
): Boolean {
  if (hintAvailability != null) {
    if (useSiteModule == null || !hintAvailability.isVisibleFrom(useSiteModule)) return false
  }
  if (resolutionScope is FrozenDeclarationResolutionScope) {
    val file = sourceIdentity?.file ?: pointer.virtualFile
    return resolutionScope.contains(file)
  }
  val element = pointer.element ?: return false
  return resolutionScope?.contains(element) ?: true
}
