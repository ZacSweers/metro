// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.aggregateMultibindingId
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * The Analysis API analog of the compiler's `BindingLookup`. Resolves bindings for requested keys
 * on demand, so only keys reachable from the seal roots are ever looked up.
 *
 * Direct keys pull from the index's membership-gated view of [context]. That context merges the
 * extension parent chain, which is what makes parent-provided bindings resolvable. Aggregate keys
 * synthesize multibinding nodes.
 */
internal class KaBindingLookup(
  private val index: BindingIndex,
  private val graph: KaGraphNode,
  private val context: GraphContext,
  private val options: MetroOptions,
  /** The graph declaration's module, gating lookups to bindings its module can see. */
  private val useSiteModule: KaModule?,
) {
  /** One [KaBinding.Multibinding] aggregate and the contributions collected into it. */
  class AggregateNode(val binding: KaBinding.Multibinding, val contributions: List<KaBinding>)

  /**
   * Element bindings by their synthetic qualifier-swapped keys. An aggregate's dependencies use
   * these keys, so the graph core requests them right after populating the aggregate. This map
   * answers those requests.
   */
  private val syntheticElements = HashMap<KaTypeKey, KaBinding>()

  /** Every aggregate synthesized during this seal, kept for the post-seal multibinding checks. */
  private val mutableAggregates = mutableListOf<AggregateNode>()

  /** Aggregates synthesized so far, for post-seal multibinding validation. */
  val aggregates: List<AggregateNode>
    get() = mutableAggregates

  /** Releases lookup state once the graph is populated and validated. */
  fun clear() {
    syntheticElements.clear()
    mutableAggregates.clear()
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

    val candidates = index.bindingsForKey(typeKey, context, useSiteModule)
    val aggregateId = contextKey.aggregateMultibindingId(options)
    if (aggregateId != null) {
      val declaration = candidates.filterIsInstance<KaBinding.Multibinding>().firstOrNull()
      val contributions = index.multibindingContributions(aggregateId, context, useSiteModule)
      if (contributions.isNotEmpty() || declaration != null) {
        return synthesizeAggregate(contextKey, aggregateId, contributions, declaration)
      }
    }

    val direct = candidates.filter { it !is KaBinding.Multibinding }
    return when {
      direct.isEmpty() -> emptySet()
      direct.size == 1 -> setOf(direct.single())
      else -> {
        onDuplicate(typeKey, direct)
        setOf(direct.first())
      }
    }
  }

  /**
   * Builds the aggregate node plus one element binding per contribution. Each element is the
   * contribution re-keyed under a synthetic qualifier, matching the compiler's
   * `@MultibindingElement` key swap.
   */
  private fun synthesizeAggregate(
    contextKey: KaContextualTypeKey,
    aggregateId: String,
    contributions: List<KaBinding>,
    declaration: KaBinding.Multibinding?,
  ): Set<KaBinding> {
    val elements = contributions.mapIndexed { i, contribution ->
      val elementId = "${contribution.originClassId?.asFqNameString() ?: "element"}#$i"
      val qualifier =
        KaAnnotationSnapshot(
          MULTIBINDING_ELEMENT_CLASS_ID,
          listOf(
            Name.identifier("bindingId") to KaAnnotationValueSnapshot.Literal(aggregateId),
            Name.identifier("elementId") to KaAnnotationValueSnapshot.Literal(elementId),
          ),
        )
      contribution.withElementKey(contribution.typeKey.copy(qualifier = qualifier))
    }
    // Aggregates can share contributions, like Map<K, V> and Map<K, Provider<V>>. First write
    // wins so both aggregates reference the same element nodes.
    for (element in elements) {
      syntheticElements.putIfAbsent(element.typeKey, element)
    }

    val anchor = declaration ?: contributions.firstOrNull()
    val aggregate =
      KaBinding.Multibinding(
        pointer = anchor?.pointer ?: graph.pointer,
        typeKey = contextKey.typeKey,
        allowEmpty = declaration?.allowEmpty == true,
        dependencies = elements.map { it.contextualTypeKey },
      )
    mutableAggregates += AggregateNode(aggregate, contributions)
    return setOf(aggregate) + elements
  }

  private companion object {
    private val MULTIBINDING_ELEMENT_CLASS_ID =
      ClassId.fromString("dev/zacsweers/metro/MultibindingElement")
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
