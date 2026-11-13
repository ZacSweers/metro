// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.DerivedCandidateSelection
import dev.zacsweers.metro.compiler.graph.selectBinding
import dev.zacsweers.metro.compiler.graph.selectMostSpecificCandidate
import dev.zacsweers.metro.idea.checkCanceledEvery

/**
 * Selected declarations, with collection inputs retained for graph validation.
 *
 * A [BindingTier.DERIVED] selection holds one derived binding. [lessSpecificDerivedBindings] are
 * the other matches the most specific one beat. [ambiguousDerivedBindings] holds every match when
 * none was most specific. The selected binding is then the first of them.
 */
internal class KaBindingSelection(
  val tier: BindingTier,
  val bindings: List<KaBinding>,
  val multibindingContributions: List<KaBinding> = emptyList(),
  val multibindingDeclarations: List<KaBinding.Multibinding> = emptyList(),
  val lessSpecificDerivedBindings: List<KaBinding> = emptyList(),
  val ambiguousDerivedBindings: List<KaBinding> = emptyList(),
)

/**
 * Selects the same binding tier for validation and editor queries. [visibleCandidates] lets editor
 * queries reuse their module-filtered candidates. Collection nodes and parent edges are built by
 * the validator after selection. Validation passes [derivedBindingVerifier] so the derived stage
 * settles bounds the snapshots can't decide.
 */
internal fun BindingIndex.selectBindingsForKey(
  contextKey: KaContextualTypeKey,
  plan: BindingIndex.GraphQueryPlan,
  visibleCandidates: List<KaBinding>? = null,
  derivedBindingVerifier: DerivedBindingVerifier? = null,
): KaBindingSelection? {
  val typeKey = contextKey.typeKey
  val instance = plan.generatedBindings.instance(typeKey)
  if (instance != null) {
    return KaBindingSelection(BindingTier.GENERATED_GRAPH, listOf(instance))
  }

  val collectionId = contextKey.multibindingId()
  val unqualified = typeKey.qualifier == null
  var indexedTier: BindingTier? = null
  val candidates = visibleCandidates ?: bindingsForKey(typeKey, plan)
  for ((index, candidate) in candidates.withIndex()) {
    checkCanceledEvery(index)
    val candidateTier = candidate.selectionTier(unqualified)
    if (candidateTier == BindingTier.MULTIBINDING && collectionId == null) continue
    val previousTier = indexedTier
    if (previousTier == null || candidateTier < previousTier) indexedTier = candidateTier
  }

  // The compiler rejects these requests during FIR injection-site checks, before graph lookup.
  if (indexedTier == BindingTier.ASSISTED_TARGET) {
    val target = candidates.first { it.selectionTier(unqualified) == BindingTier.ASSISTED_TARGET }
    return KaBindingSelection(BindingTier.ASSISTED_TARGET, listOf(target))
  }

  val request =
    SelectionRequest(
      typeKey = typeKey,
      plan = plan,
      candidates = candidates,
      indexedTier = indexedTier,
      collectionId = collectionId,
      visibleCandidates = visibleCandidates,
    )

  return selectBinding(
    registered = { selectRegisteredBinding(request) },
    multibinding = { selectMultibinding(request) },
    optional = { selectOptionalBinding(request) },
    derived = { selectDerivedBinding(typeKey, plan, derivedBindingVerifier) },
    implicit = { selectImplicitBinding(request) },
  )
}

/**
 * What each selection stage reads for one request. [indexedTier] is the best tier among
 * [candidates], or null when none applies. [collectionId] is set for a multibinding request.
 * [visibleCandidates] is the caller's module-filtered list, when it passed one.
 */
private class SelectionRequest(
  val typeKey: KaTypeKey,
  val plan: BindingIndex.GraphQueryPlan,
  val candidates: List<KaBinding>,
  val indexedTier: BindingTier?,
  val collectionId: String?,
  val visibleCandidates: List<KaBinding>?,
) {
  /** The candidates whose tier is [tier]. */
  fun candidatesIn(tier: BindingTier): List<KaBinding> {
    val unqualified = typeKey.qualifier == null
    return candidates.filter { it.selectionTier(unqualified) == tier }
  }
}

/**
 * Selects the explicit declarations from the nearest graph that owns the key. Without any, it
 * selects the binding the graph generates for the key.
 */
private fun BindingIndex.selectRegisteredBinding(request: SelectionRequest): KaBindingSelection? {
  if (request.indexedTier == BindingTier.EXPLICIT) {
    val explicit = request.candidatesIn(BindingTier.EXPLICIT)
    val nearest = nearestExplicitBindings(explicit, request.plan)
    return KaBindingSelection(BindingTier.EXPLICIT, nearest)
  }

  val generated = request.plan.generatedBindings.forKey(request.typeKey) ?: return null
  return KaBindingSelection(BindingTier.GENERATED_GRAPH, listOf(generated))
}

/**
 * Selects the collection binding for a multibinding request that has contributions or a
 * declaration.
 */
private fun BindingIndex.selectMultibinding(request: SelectionRequest): KaBindingSelection? {
  val collectionId = request.collectionId ?: return null
  val visibleCandidates = request.visibleCandidates
  val contributions =
    if (visibleCandidates == null) {
      multibindingContributions(collectionId, request.plan)
    } else {
      visibleCandidates.filter { it.multibindingId != null }
    }

  val hasDeclaration = request.indexedTier == BindingTier.MULTIBINDING
  if (contributions.isEmpty() && !hasDeclaration) {
    return null
  }

  val declarations = request.candidates.filterIsInstance<KaBinding.Multibinding>()
  return KaBindingSelection(
    BindingTier.MULTIBINDING,
    contributions.ifEmpty { declarations },
    contributions,
    declarations,
  )
}

/** Selects the first optional declaration. The compiler uses it without reporting duplicates. */
private fun selectOptionalBinding(request: SelectionRequest): KaBindingSelection? {
  if (request.indexedTier != BindingTier.OPTIONAL) {
    return null
  }

  val declaration = request.candidatesIn(BindingTier.OPTIONAL).first()
  return KaBindingSelection(BindingTier.OPTIONAL, listOf(declaration))
}

/** Selects the bindings the requested class provides itself, like an `@Inject` constructor. */
private fun selectImplicitBinding(request: SelectionRequest): KaBindingSelection? {
  if (request.indexedTier != BindingTier.IMPLICIT) {
    return null
  }
  return KaBindingSelection(BindingTier.IMPLICIT, request.candidatesIn(BindingTier.IMPLICIT))
}

/**
 * Mirrors the compiler's derived stage. Generic declarations in the graph whose pattern matches
 * [key] become derived bindings, and the most specific one wins. When none is most specific, the
 * first match is used so no missing binding is reported for the key as well. [verifier] is passed
 * on to [BindingIndex.derivedBindingFor].
 */
private fun BindingIndex.selectDerivedBinding(
  key: KaTypeKey,
  plan: BindingIndex.GraphQueryPlan,
  verifier: DerivedBindingVerifier?,
): KaBindingSelection? {
  // Most projects have no generic declarations, so they skip everything below.
  if (!hasGenericBindings) {
    return null
  }

  val candidates = derivedCandidates(key, plan)
  if (candidates.isEmpty()) {
    return null
  }

  val matches = candidates.mapIndexedNotNull { index, template ->
    checkCanceledEvery(index)
    derivedBindingFor(template, key, verifier)
  }

  val selection =
    selectMostSpecificCandidate(
      candidates = matches,
      patternOf = { match -> checkNotNull(match.derivation).template.typeKey.type.identity },
      isVariableOf = { match, typeParameter ->
        checkNotNull(match.derivation).signature.isVariable(typeParameter)
      },
    )

  return when (selection) {
    DerivedCandidateSelection.None -> null

    is DerivedCandidateSelection.Single ->
      KaBindingSelection(BindingTier.DERIVED, listOf(selection.candidate))

    is DerivedCandidateSelection.MostSpecific ->
      KaBindingSelection(
        BindingTier.DERIVED,
        listOf(selection.winner),
        lessSpecificDerivedBindings = selection.others,
      )

    is DerivedCandidateSelection.Ambiguous ->
      KaBindingSelection(
        BindingTier.DERIVED,
        listOf(selection.candidates.first()),
        ambiguousDerivedBindings = selection.candidates,
      )
  }
}

/**
 * Keeps explicit declarations from the closest graph that owns this key. All bindings at that graph
 * survive so validation can report duplicates.
 */
private fun BindingIndex.nearestExplicitBindings(
  candidates: List<KaBinding>,
  plan: BindingIndex.GraphQueryPlan,
): List<KaBinding> {
  val chain = plan.structure.queryContext.graphContext.chain
  if (candidates.size < 2 || chain.size < 2) {
    return candidates
  }

  // Included dependency accessors retain the compiler's duplicate checks against inherited
  // bindings.
  val allDirectDeclarations = candidates.all {
    when (it) {
      is KaBinding.Provided,
      is KaBinding.Alias -> true
      is KaBinding.BoundInstance -> !it.isGraphInput && !it.isBindingContainerInput
      else -> false
    }
  }
  if (!allDirectDeclarations) {
    return candidates
  }

  for ((graphIndex, graph) in chain.withIndex()) {
    checkCanceledEvery(graphIndex)
    val owned = candidates.filter { isBindingOwnedByGraph(it, graph, plan) }
    if (owned.isNotEmpty()) {
      return owned
    }
  }
  return candidates
}

private fun KaBinding.selectionTier(unqualified: Boolean): BindingTier {
  if (multibindingId != null) return BindingTier.MULTIBINDING
  return when (this) {
    is KaBinding.Multibinding -> BindingTier.MULTIBINDING
    // An explicit provider cannot make direct injection of an unqualified assisted target valid.
    is KaBinding.ConstructorInjected ->
      if (unqualified && isAssisted) BindingTier.ASSISTED_TARGET else BindingTier.IMPLICIT
    is KaBinding.AssistedFactory -> BindingTier.IMPLICIT
    is KaBinding.CustomWrapper -> BindingTier.OPTIONAL
    else -> BindingTier.EXPLICIT
  }
}
