// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.model.graphTypeKey
import dev.zacsweers.metro.idea.model.multibindingId
import org.jetbrains.kotlin.name.Name

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
  private val queryContext: GraphQueryContext,
  private val options: MetroOptions,
) {
  private val graph: KaGraphDeclaration = queryContext.graphContext.graph

  /**
   * Element bindings by their synthetic qualifier-swapped keys. A multibinding's dependencies use
   * these keys, so the graph core requests them right after populating the multibinding. This map
   * answers those requests.
   */
  private val syntheticElements = HashMap<KaTypeKey, KaBinding>()

  /** Parent-scoped bindings remapped to graph dependency nodes, one per key. */
  private val parentDependencies = HashMap<KaTypeKey, KaBinding>()

  /** Releases lookup state once the graph is populated and validated. */
  fun clear() {
    syntheticElements.clear()
    parentDependencies.clear()
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
      return setOf(it)
    }
    graphInstance(typeKey)?.let {
      return setOf(it)
    }

    val candidates = index.bindingsForKey(typeKey, queryContext)
    val explicit = mutableListOf<KaBinding>()
    val implicit = mutableListOf<KaBinding>()
    val multibindingDeclarations = mutableListOf<KaBinding.Multibinding>()
    for (candidate in candidates) {
      when (candidate) {
        is KaBinding.Multibinding -> multibindingDeclarations += candidate
        // Class-derived bindings the compiler discovers through class-based lookup.
        is KaBinding.ConstructorInjected,
        is KaBinding.AssistedFactory -> implicit += candidate
        // Everything else corresponds to the compiler's explicit binding cache: provides,
        // aliases, graph factory inputs, includes, extensions, and custom wrappers.
        else -> explicit += candidate
      }
    }

    // Explicit bindings win over class-derived ones and multibinding synthesis, matching the
    // compiler's cache-first lookup. Only same-tier collisions are duplicates.
    if (explicit.isNotEmpty()) {
      if (explicit.size > 1) {
        onDuplicate(typeKey, explicit)
      }
      return setOf(delegateToParentIfScoped(explicit.first()))
    }

    val multibindingId = contextKey.multibindingId(options)
    if (multibindingId != null) {
      val contributions = index.multibindingContributions(multibindingId, queryContext)
      if (contributions.isNotEmpty() || multibindingDeclarations.isNotEmpty()) {
        return synthesizeMultibinding(
          contextKey,
          multibindingId,
          contributions,
          multibindingDeclarations,
        )
      }
    }

    if (typeKey.qualifier == null) {
      implicit
        .filterIsInstance<KaBinding.ConstructorInjected>()
        .firstOrNull { it.isAssisted }
        ?.let {
          return setOf(it)
        }
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
    val scope = binding.scope ?: return binding
    val chain = queryContext.graphContext.chain
    if (chain.size < 2) return binding
    val child = chain.first()
    if (scope in child.scopingAnnotations) return binding
    // A scope matching no graph in the chain stays inline so scope validation reports it here.
    if (chain.none { scope in it.scopingAnnotations }) return binding
    // Bindings declared on the child itself stay local even when their scope names an ancestor.
    val containerId = binding.containerId
    if (
      containerId != null && (containerId in child.selfIds || containerId in child.supertypeIds)
    ) {
      return binding
    }
    val parentKey = chain[1].graphTypeKey() ?: return binding
    return parentDependencies.getOrPut(binding.typeKey) {
      KaBinding.GraphDependency(
        pointer = binding.pointer,
        contextualTypeKey = binding.typeKey.canonicalContextKey(),
        ownerKey = parentKey,
        // Direct suspendness only. The compiler also resolves transitive parent suspendness,
        // which the child seal cannot see; accepted approximation.
        accessorIsSuspend = binding.isSuspend,
        isParentScoped = true,
      )
    }
  }

  private fun graphInstance(typeKey: KaTypeKey): KaBinding.GraphInstance? {
    if (typeKey.qualifier != null) return null
    val classId = typeKey.type.classId ?: return null
    val graph = queryContext.graphContext.chain.firstOrNull { it.classId == classId } ?: return null
    return KaBinding.GraphInstance(graph.pointer, typeKey)
  }

  /**
   * Builds the multibinding plus one element binding per contribution. Each element is the
   * contribution re-keyed under a synthetic qualifier, matching the compiler's
   * `@MultibindingElement` key swap.
   */
  private fun synthesizeMultibinding(
    contextKey: KaContextualTypeKey,
    multibindingId: String,
    contributions: List<KaBinding>,
    declarations: List<KaBinding.Multibinding>,
  ): Set<KaBinding> {
    val elements = contributions.mapIndexed { i, contribution ->
      val elementId = "${contribution.originClassId?.asFqNameString() ?: "element"}#$i"
      val qualifier =
        KaAnnotationSnapshot(
          MULTIBINDING_ELEMENT_CLASS_ID,
          listOf(
            Name.identifier("bindingId") to KaAnnotationValueSnapshot.Literal(multibindingId),
            Name.identifier("elementId") to KaAnnotationValueSnapshot.Literal(elementId),
          ),
        )
      contribution.withElementKey(contribution.typeKey.copy(qualifier = qualifier))
    }
    // Multibindings can share contributions, like Map<K, V> and Map<K, Provider<V>>. First write
    // wins so both multibindings reference the same element nodes.
    for (element in elements) {
      syntheticElements.putIfAbsent(element.typeKey, element)
    }

    val anchor = declarations.firstOrNull() ?: contributions.firstOrNull()
    val multibinding =
      KaBinding.Multibinding(
        pointer = anchor?.pointer ?: graph.pointer,
        typeKey = contextKey.typeKey,
        contextualTypeKey = contextKey,
        allowEmpty = declarations.any { it.allowEmpty },
        sourceBindings = elements.map { it.typeKey },
      )
    return setOf(multibinding) + elements
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
        replaces = replaces,
        contributionScopes = contributionScopes,
        dependencies = dependencies,
        isSuspend = isSuspend,
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
        replaces = replaces,
        contributionScopes = contributionScopes,
        isClassContribution = isClassContribution,
      )
    else -> error("Unexpected multibinding contribution: ${javaClass.simpleName} for $typeKey")
  }
}
