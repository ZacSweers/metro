// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.isExplicitDeclaration

/**
 * A graph extension binding that replaces a binding the extension inherits from [owner]. The
 * Analysis API analog of the compiler's `ParentBindingOverride`.
 *
 * [declarations] are the extension's own declarations for [typeKey]. [inheritedDeclarations] are
 * [owner]'s declarations for it. [owner] is the nearest ancestor that declares the key. For a
 * scoped `@Inject` class, it's the nearest ancestor that owns the class's scope.
 */
internal class KaParentBindingOverride(
  val typeKey: KaTypeKey,
  val owner: KaGraphDeclaration,
  val declarations: List<KaBinding>,
  val inheritedDeclarations: List<KaBinding>,
) {
  /** True when the inherited binding is a scoped `@Inject` class. No graph declares that one. */
  val inheritsInjectedClass: Boolean
    get() = inheritedDeclarations.any { it is KaBinding.ConstructorInjected }
}

/**
 * Finds the bindings in [plan]'s graph that override bindings from its ancestor graphs. It also
 * finds the scoped `@Inject` classes that the graph's declarations replace. The Analysis API analog
 * of the compiler's `ParentBindingOverrideResolver`.
 *
 * It checks every explicit declaration the graph makes, whether or not anything requests it. The
 * compiler does the same before it seals the graph.
 *
 * [resolve] reads every declaration in the index. Skip it when `parentBindingOverrideSeverity` is
 * `NONE`.
 */
internal class KaParentBindingOverrideResolver(
  private val index: BindingIndex,
  private val plan: BindingIndex.GraphQueryPlan,
) {
  /** The graph itself followed by its ancestors, nearest first. */
  private val chain = plan.structure.queryContext.graphContext.chain

  /** Returns the overrides sorted by key. Keys that aren't overrides are left out. */
  fun resolve(): List<KaParentBindingOverride> {
    val inheritsBindings = chain.size > 1
    if (!inheritsBindings) {
      return emptyList()
    }

    return index
      .ownExplicitDeclarations(plan)
      .entries
      .sortedBy { it.key }
      .mapNotNull { (key, declarations) -> resolveOverride(key, declarations) }
  }

  private fun resolveOverride(
    key: KaTypeKey,
    declarations: List<KaBinding>,
  ): KaParentBindingOverride? {
    ProgressManager.checkCanceled()
    val declared = nearestInheritedDeclarations(key)

    // A key that an ancestor declares keeps that declaration. The class only counts without one.
    val inherited =
      if (declared != null) {
        declared
      } else {
        scopedInjectedClass(key)
      }
    if (inherited == null) {
      return null
    }

    // The same declaration at both levels, like a shared binding container, isn't an override.
    val isSharedDeclaration = declarations.any { declaration ->
      inherited.declarations.any { isSameDeclaration(declaration, it) }
    }
    if (isSharedDeclaration) {
      return null
    }

    // Several declarations here are also a duplicate binding, and the report lists all of them.
    return KaParentBindingOverride(
      typeKey = key,
      owner = inherited.owner,
      declarations = declarations,
      inheritedDeclarations = inherited.declarations,
    )
  }

  /**
   * Walks ancestors nearest first and returns the first one that explicitly declares [key]. Returns
   * null when none does, like a type an ancestor only offers through an `@Inject` constructor.
   * [scopedInjectedClass] handles the scoped ones.
   *
   * The graph's membership rules already drop an ancestor's `@GraphPrivate` bindings and the ones a
   * dynamic graph replaces. An ancestor's `@Includes` accessor counts as a declaration here.
   */
  private fun nearestInheritedDeclarations(key: KaTypeKey): InheritedDeclarations? {
    val candidates =
      index.bindingsForKey(key, plan).filter { candidate ->
        candidate.isExplicitDeclaration() || candidate is KaBinding.GraphDependency
      }
    if (candidates.isEmpty()) {
      return null
    }

    for (depth in 1 until chain.size) {
      val ancestor = chain[depth]
      val declarations = candidates.filter { index.isDeclaredByGraph(it, ancestor, plan) }
      if (declarations.isNotEmpty()) {
        return InheritedDeclarations(ancestor, declarations)
      }
    }

    return null
  }

  /**
   * Returns the scoped `@Inject` class behind [key] with the nearest ancestor that owns its scope.
   * No graph declares the class. The ancestor that owns its scope keeps the one instance, so this
   * graph inherits it like that ancestor's scoped `@Provides`.
   *
   * Returns null for an unscoped class, since every graph builds its own instances of it. Also
   * returns null when this graph owns the scope, or when an ancestor up to the scope's owner
   * declares [key] itself.
   */
  private fun scopedInjectedClass(key: KaTypeKey): InheritedDeclarations? {
    val injectedClass =
      index.bindingsForKey(key, plan).firstNotNullOfOrNull { it.asScopedInjectedClass() }
    val scope = injectedClass?.scope
    if (injectedClass == null || scope == null) {
      return null
    }

    val ownsScope = scope in chain.first().scopingAnnotations
    if (ownsScope) {
      return null
    }

    for (depth in 1 until chain.size) {
      val ancestor = chain[depth]

      // An ancestor's own declaration wins over the class there, even one this graph can't see.
      if (index.declaresUninheritedBinding(key, ancestor, plan)) {
        return null
      }

      if (scope in ancestor.scopingAnnotations) {
        return InheritedDeclarations(ancestor, listOf(injectedClass))
      }
    }

    return null
  }

  /**
   * Returns this binding when it's a scoped class that the graph can build from its `@Inject`
   * constructor. An object has no constructor, and an assisted-inject class is only reachable
   * through its factory.
   */
  private fun KaBinding.asScopedInjectedClass(): KaBinding.ConstructorInjected? {
    if (this !is KaBinding.ConstructorInjected) {
      return null
    }

    val isBuiltByGraph = !isObject && !isAssisted
    if (scope == null || !isBuiltByGraph) {
      return null
    }

    return this
  }

  /**
   * Returns true when both bindings come from one source declaration. One binding object can belong
   * to two graph levels. Two levels can also each hold their own copy of an inherited member.
   */
  private fun isSameDeclaration(first: KaBinding, second: KaBinding): Boolean {
    if (first === second) {
      return true
    }

    val source = index.sourceIdentityFor(first) ?: return false
    return first.javaClass == second.javaClass && source == index.sourceIdentityFor(second)
  }

  private class InheritedDeclarations(
    val owner: KaGraphDeclaration,
    val declarations: List<KaBinding>,
  )
}
