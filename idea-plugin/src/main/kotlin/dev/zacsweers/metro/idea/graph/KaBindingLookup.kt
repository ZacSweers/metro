// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.GraphComposition
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.model.graphTypeKey
import dev.zacsweers.metro.idea.model.multibindingId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/** The index and compiler options belonging to a parent graph's own declaration module. */
internal class ParentGraphLookup(
  val index: BindingIndex,
  val session: BindingResolutionSession,
  val queryContext: GraphQueryContext,
  val options: MetroOptions,
)

/**
 * The Analysis API analog of the compiler's `BindingLookup`. Resolves bindings for requested keys
 * on demand, so only keys reachable from the seal roots are ever looked up.
 *
 * Direct keys pull from the index's membership-gated view of [queryContext]. Its graph context
 * merges the extension parent chain, while its module gates declaration visibility. Multibinding
 * keys synthesize multibinding nodes.
 */
internal class KaBindingLookup(
  private val index: BindingIndex,
  private val session: BindingResolutionSession,
  private val queryContext: GraphQueryContext,
  private val options: MetroOptions,
  private val resolveParentGraph: (GraphContext) -> ParentGraphLookup? = { null },
) {
  private val graph: KaGraphDeclaration = queryContext.graphContext.graph
  internal val queryPlan = session.validationPlan(queryContext)

  /**
   * Element bindings by their synthetic qualifier-swapped keys. A multibinding's dependencies use
   * these keys, so the graph core requests them right after populating the multibinding. This map
   * answers those requests.
   */
  private val syntheticElements = HashMap<KaTypeKey, KaBinding>()

  /** Graph-supertype aliases and extension factories for this exact parent path. */
  private var syntheticGraphBindings: Map<KaTypeKey, KaBinding>? = null

  /** Direct child accessors kept by this graph's seal, excluding factory creators. */
  private var directGraphExtensionBindings: List<KaBinding.GraphExtension>? = null

  /** Parent-scoped bindings remapped to graph dependency nodes, one per key. */
  private val parentDependencies = HashMap<KaTypeKey, KaBinding>()

  /** Bindings this context delegates upward. The parent seal must validate them as its own. */
  private val reservedForParent = LinkedHashMap<KaTypeKey, KaBinding>()

  /** Containers and classes the child context itself wires, whose bindings never delegate. */
  private val childLocalContainerIds: Set<ClassId> by
    lazy(LazyThreadSafetyMode.NONE) {
      val child = queryContext.graphContext.chain.first()
      buildSet {
        addAll(child.selfIds)
        addAll(child.supertypeIds)
        for (supertype in index.graphComposition(queryPlan).supertypeDeclarations) {
          add(supertype.classId)
        }
        addAll(index.graphOwnContainers(child, queryPlan))
      }
    }

  /** A lazily built view of the parent context for transitive suspend resolution. */
  private var parentSuspendLookup: KaBindingLookup? = null
  private var parentSuspendAnalysis: SuspendBindingAnalysis? = null
  private var parentSuspendAnalysisChecked = false

  val reservedParentBindings: Map<KaTypeKey, KaBinding>
    get() = reservedForParent

  /** Makes a child-only synthetic multibinding element available to its owning parent graph. */
  fun registerReservedBinding(binding: KaBinding) {
    syntheticElements.putIfAbsent(binding.typeKey, binding)
  }

  /** Releases lookup state once the graph is populated and validated. */
  fun clear() {
    syntheticElements.clear()
    syntheticGraphBindings = null
    directGraphExtensionBindings = null
    parentDependencies.clear()
    reservedForParent.clear()
    parentSuspendLookup?.clear()
    parentSuspendLookup = null
    parentSuspendAnalysis = null
    parentSuspendAnalysisChecked = false
  }

  /**
   * Resolves the bindings satisfying [contextKey]. An empty result means missing. Duplicates are
   * reported through [onDuplicate] and resolution continues with the first.
   */
  fun lookup(
    contextKey: KaContextualTypeKey,
    onDuplicate: (KaTypeKey, List<KaBinding>) -> Unit,
  ): Set<KaBinding> {
    ProgressManager.checkCanceled()
    val typeKey = contextKey.typeKey
    syntheticElements[typeKey]?.let {
      return setOf(delegateToParentIfScoped(it))
    }
    graphInstance(typeKey)?.let {
      return setOf(it)
    }

    val candidates = index.bindingsForKey(typeKey, queryPlan)
    val explicit = mutableListOf<KaBinding>()
    val implicit = mutableListOf<KaBinding>()
    val optional = mutableListOf<KaBinding>()
    val multibindingDeclarations = mutableListOf<KaBinding.Multibinding>()
    for (candidate in candidates) {
      when (candidate) {
        is KaBinding.Multibinding -> multibindingDeclarations += candidate
        // Class-derived bindings the compiler discovers through class-based lookup.
        is KaBinding.ConstructorInjected,
        is KaBinding.AssistedFactory -> implicit += candidate
        // @BindsOptionalOf declarations resolve on their own tier in the compiler.
        is KaBinding.CustomWrapper -> optional += candidate
        // Everything else maps to the compiler's explicit binding cache, like provides,
        // aliases, graph factory inputs, includes, and extensions.
        else -> explicit += candidate
      }
    }

    // An unqualified assisted type can only be created through its factory. The compiler rejects
    // direct requests even when an explicit provider exists, so resolve to the assisted class
    // first and let validation report it.
    if (typeKey.qualifier == null) {
      implicit
        .filterIsInstance<KaBinding.ConstructorInjected>()
        .firstOrNull { it.isAssisted }
        ?.let {
          return setOf(it)
        }
    }

    // Explicit bindings win over ordinary inject constructors and multibinding synthesis,
    // matching the compiler's cache-first lookup. Only same-tier collisions are duplicates.
    if (explicit.isNotEmpty()) {
      if (explicit.size > 1) {
        onDuplicate(typeKey, explicit)
      }
      return setOf(delegateToParentIfScoped(explicit.first()))
    }

    // The compiler inserts graph-supertype aliases and extension factories only after explicit
    // bindings. Keep them on the same tier here so an authored provider or @Binds can override one.
    graphBindings()[typeKey]?.let {
      return setOf(it)
    }

    val multibindingId = contextKey.multibindingId(options)
    if (multibindingId != null) {
      val contributions = index.multibindingContributions(multibindingId, queryPlan)
      if (contributions.isNotEmpty() || multibindingDeclarations.isNotEmpty()) {
        return synthesizeMultibinding(
          contextKey,
          multibindingId,
          contributions,
          multibindingDeclarations,
        )
      }
    }

    // The compiler consumes the first optional declaration and never treats repeats as
    // duplicates, so they resolve after multibindings on their own tier.
    optional.firstOrNull()?.let {
      return setOf(it)
    }

    return when {
      implicit.isEmpty() -> emptySet()
      implicit.size == 1 -> setOf(delegateToParentIfScoped(implicit.single()))
      else -> {
        onDuplicate(typeKey, implicit)
        setOf(delegateToParentIfScoped(implicit.first()))
      }
    }
  }

  /**
   * Remaps a binding scoped to an ancestor graph onto a dependency on that graph, matching the
   * compiler's child-graph lookup. The ancestor's own seal resolves the binding and its
   * dependencies, so the child only records the parent edge.
   */
  private fun delegateToParentIfScoped(binding: KaBinding): KaBinding {
    val chain = queryContext.graphContext.chain
    if (chain.size < 2) {
      return binding
    }

    // A public parent alias may expose a private implementation without exposing the private key.
    // Resolve that alias entirely in the parent, where its implementation remains visible.
    if (binding is KaBinding.Alias && !binding.isGraphPrivate) {
      val consumedKey = binding.consumedKey
      val belongsToAncestor = !index.isBindingOwnedByCurrentGraph(binding, queryPlan)
      if (
        belongsToAncestor &&
          consumedKey != null &&
          session.hasPrivateAncestorBinding(consumedKey.typeKey, queryContext)
      ) {
        return delegateToParent(binding, chain)
      }
    }

    val scope = binding.scope ?: return binding
    val child = chain.first()
    if (scope in child.scopingAnnotations) {
      return binding
    }
    // A scope matching no graph in the chain stays inline so scope validation reports it here.
    if (chain.none { scope in it.scopingAnnotations }) {
      return binding
    }
    // Everything the child itself declares or wires stays local even when its scope names an
    // ancestor, like the compiler's locally declared keys. Class-derived bindings have no local
    // owner and always delegate by scope.
    val isClassDerived =
      binding is KaBinding.ConstructorInjected || binding is KaBinding.AssistedFactory
    if (!isClassDerived) {
      if (binding.contributionScopes.isNotEmpty()) {
        // Contributions the child aggregates merge into the child.
        if (binding.contributionScopes.any { it in child.scopeKeys }) {
          return binding
        }
      } else {
        val includedContainerKey = binding.includedContainerKey
        if (includedContainerKey != null) {
          // Factory-included containers carry their concrete input key instead of a container ID.
          // Their graph ownership must survive synthetic multibinding element re-keying.
          if (index.isBindingOwnedByCurrentGraph(binding, queryPlan)) {
            return binding
          }
        } else {
          val containerId = binding.containerId
          // A null container means the declaring owner could not be identified, like a local graph
          // class. Treat it as child-owned rather than delegating it upward.
          if (containerId == null || containerId in childLocalContainerIds) {
            return binding
          }
        }
      }
    }
    return delegateToParent(binding, chain)
  }

  private fun delegateToParent(
    binding: KaBinding,
    chain: List<KaGraphDeclaration>,
  ): KaBinding {
    val parentKey = chain[1].graphTypeKey() ?: return binding
    reservedForParent.putIfAbsent(binding.typeKey, binding)
    return parentDependencies.getOrPut(binding.typeKey) {
      KaBinding.GraphDependency(
        pointer = binding.pointer,
        contextualTypeKey = binding.typeKey.canonicalContextKey(),
        ownerKey = parentKey,
        accessorIsSuspend = parentTransitiveSuspend(binding),
        isParentScoped = true,
        multibindingId = binding.multibindingId,
        mapKeyValue = binding.mapKeyValue,
      )
    }
  }

  /**
   * Whether the delegated binding requires suspend initialization in its owning graph, resolved
   * through the parent context's own lookup so transitive suspend dependencies count.
   */
  private fun parentTransitiveSuspend(binding: KaBinding): Boolean {
    if (binding.isSuspend) {
      return true
    }
    var analysis = parentSuspendAnalysis
    if (analysis == null) {
      if (parentSuspendAnalysisChecked) {
        return false
      }
      parentSuspendAnalysisChecked = true
      analysis = createParentSuspendAnalysis() ?: return false
    }
    if (binding.typeKey.qualifier?.classId == MULTIBINDING_ELEMENT_CLASS_ID) {
      parentSuspendLookup?.registerReservedBinding(binding)
    }
    return binding.typeKey in analysis.analyze(listOf(binding.typeKey))
  }

  private fun createParentSuspendAnalysis(): SuspendBindingAnalysis? {
    val chain = queryContext.graphContext.chain
    val parentPath =
      GraphPath(
        chain.drop(1).map { it.declarationId },
        queryContext.graphContext.dynamicGraph?.id,
      )
    val parentContext = session.findContext(parentPath) ?: return null
    val resolvedParent = resolveParentGraph(parentContext)
    val parent =
      if (resolvedParent != null) {
        resolvedParent
      } else {
        val parentQueryContext = session.queryContext(parentContext) ?: return null
        ParentGraphLookup(index, session, parentQueryContext, options)
      }
    if (!parent.options.enableSuspendProviders) {
      return null
    }
    val lookup =
      KaBindingLookup(
        parent.index,
        parent.session,
        parent.queryContext,
        parent.options,
        resolveParentGraph,
      )
    parentSuspendLookup = lookup
    val analysis = SuspendBindingAnalysis { key ->
      lookup.lookup(key.canonicalContextKey()) { _, _ -> }.firstOrNull { it.typeKey == key }
    }
    parentSuspendAnalysis = analysis
    return analysis
  }

  private fun graphInstance(typeKey: KaTypeKey): KaBinding.GraphInstance? {
    if (typeKey.qualifier != null) return null
    val graph =
      queryContext.graphContext.chain.firstOrNull { it.graphTypeKey() == typeKey } ?: return null
    return KaBinding.GraphInstance(graph.pointer, typeKey)
  }

  /**
   * Direct child creations are kept even when no ordinary accessor requests their keys. The exact
   * child reference distinguishes them from accessors returning a factory and from factory SAMs.
   */
  fun directExtensionBindings(): List<KaBinding.GraphExtension> {
    directGraphExtensionBindings?.let {
      return it
    }
    val ownerKey = graph.graphTypeKey()
    if (ownerKey == null) {
      directGraphExtensionBindings = emptyList()
      return emptyList()
    }

    val composition = index.graphComposition(queryPlan)
    val activeGraphIds = queryContext.graphContext.graphIds
    val bindings = mutableListOf<KaBinding.GraphExtension>()
    for (extension in index.extensionsOf(queryPlan)) {
      ProgressManager.checkCanceled()
      if (extension.declarationId in activeGraphIds) continue
      val classId = extension.classId ?: continue
      val reference = GraphReference(classId, extension.pointer.virtualFile)
      if (reference !in composition.extensionCreations) continue
      val extensionKey = extension.graphTypeKey() ?: continue
      bindings += KaBinding.GraphExtension(extension.pointer, extensionKey, ownerKey)
    }
    directGraphExtensionBindings = bindings
    return bindings
  }

  private fun graphBindings(): Map<KaTypeKey, KaBinding> {
    syntheticGraphBindings?.let {
      return it
    }

    val chain = queryContext.graphContext.chain
    val compositions = LinkedHashMap<KaGraphDeclaration, GraphComposition>(chain.size)
    val bindings = LinkedHashMap<KaTypeKey, KaBinding>()
    for (owner in chain) {
      ProgressManager.checkCanceled()
      val ownerKey = owner.graphTypeKey() ?: continue
      val composition = index.graphComposition(queryPlan, owner)
      compositions[owner] = composition
      val consumedKey = ownerKey.canonicalContextKey()
      for (supertypeKey in composition.supertypeKeys) {
        if (supertypeKey == ownerKey) continue
        bindings.putIfAbsent(
          supertypeKey,
          KaBinding.Alias(
            pointer = owner.pointer,
            typeKey = supertypeKey,
            consumedKey = consumedKey,
            containerId = owner.classId,
            ownerGraphId = owner.declarationId,
          ),
        )
      }
    }

    // Resolve aliases for every ancestor before creating separate factories. In particular, a
    // child's inherited factory accessor must still return a parent that implements that factory.
    for ((owner, composition) in compositions) {
      ProgressManager.checkCanceled()
      val ownerKey = owner.graphTypeKey() ?: continue
      val implementedClasses = composition.supertypeKeys.mapNotNullTo(HashSet()) { it.type.classId }
      for (accessor in composition.extensionFactories) {
        val factoryClassId = accessor.factoryKey.type.classId
        if (factoryClassId != null && factoryClassId in implementedClasses) continue
        bindings.putIfAbsent(
          accessor.factoryKey,
          KaBinding.GraphExtension(
            pointer = accessor.pointer,
            typeKey = accessor.factoryKey,
            ownerKey = ownerKey,
            isFactory = true,
          ),
        )
      }
    }

    for (binding in directExtensionBindings()) {
      bindings.putIfAbsent(binding.typeKey, binding)
    }

    syntheticGraphBindings = bindings
    return bindings
  }

  /**
   * Builds the multibinding and registers one element binding per contribution. Each element is the
   * contribution re-keyed under a synthetic qualifier, matching the compiler's
   * `@MultibindingElement` key swap. The graph resolves those elements through the multibinding's
   * dependencies rather than inserting them again alongside each collection view.
   */
  private fun synthesizeMultibinding(
    contextKey: KaContextualTypeKey,
    multibindingId: String,
    contributions: List<KaBinding>,
    declarations: List<KaBinding.Multibinding>,
  ): Set<KaBinding> {
    val elementKeys = contributions.mapIndexed { i, contribution ->
      val elementId = "${contribution.originClassId?.asFqNameString() ?: "element"}#$i"
      val qualifier =
        KaAnnotationSnapshot(
          MULTIBINDING_ELEMENT_CLASS_ID,
          listOf(
            Name.identifier("bindingId") to KaAnnotationValueSnapshot.Literal(multibindingId),
            Name.identifier("elementId") to KaAnnotationValueSnapshot.Literal(elementId),
          ),
        )
      val elementKey = contribution.typeKey.copy(qualifier = qualifier)
      // Multibindings can share contributions, like Map<K, V> and Map<K, Provider<V>>. First write
      // wins so both views, and any child reservation, reference the same element node.
      val element =
        syntheticElements.getOrPut(elementKey) {
          delegateToParentIfScoped(contribution.withElementKey(elementKey))
        }
      element.typeKey
    }

    val anchor = declarations.firstOrNull() ?: contributions.firstOrNull()
    val multibinding =
      KaBinding.Multibinding(
        pointer = anchor?.pointer ?: graph.pointer,
        typeKey = contextKey.typeKey,
        contextualTypeKey = contextKey,
        allowEmpty = declarations.any { it.allowEmpty },
        sourceBindings = elementKeys,
        isGraphPrivate = declarations.any { it.isGraphPrivate },
      )
    return setOf(multibinding)
  }

  private companion object {
    private val MULTIBINDING_ELEMENT_CLASS_ID = MetroClassIds.multibindingElement
  }
}

/** Copies a multibinding contribution under its synthetic element key. */
private fun KaBinding.withElementKey(elementKey: KaTypeKey): KaBinding {
  return when (this) {
    is KaBinding.Provided ->
      KaBinding.Provided(
        pointer = pointer,
        typeKey = elementKey,
        scope = scope,
        implementationName = implementationName,
        multibindingId = multibindingId,
        mapKeyValue = mapKeyValue,
        originClassId = originClassId,
        containerId = containerId,
        ownerGraphId = ownerGraphId,
        includedContainerKey = includedContainerKey,
        replaces = replaces,
        contributionScopes = contributionScopes,
        priority = priority,
        priorityFromAnvilRank = priorityFromAnvilRank,
        isClassContribution = isClassContribution,
        dependencies = dependencies,
        memberInjectionOwnerIds = memberInjectionOwnerIds,
        isSuspend = isSuspend,
        hintAvailability = hintAvailability,
        isGraphPrivate = isGraphPrivate,
      )
    is KaBinding.Alias ->
      KaBinding.Alias(
        pointer = pointer,
        typeKey = elementKey,
        consumedKey = consumedKey,
        scope = scope,
        implementationName = implementationName,
        multibindingId = multibindingId,
        mapKeyValue = mapKeyValue,
        originClassId = originClassId,
        containerId = containerId,
        ownerGraphId = ownerGraphId,
        includedContainerKey = includedContainerKey,
        replaces = replaces,
        contributionScopes = contributionScopes,
        priority = priority,
        priorityFromAnvilRank = priorityFromAnvilRank,
        isClassContribution = isClassContribution,
        hintAvailability = hintAvailability,
        isGraphPrivate = isGraphPrivate,
      )
    else -> error("Unexpected multibinding contribution: ${javaClass.simpleName} for $typeKey")
  }
}
