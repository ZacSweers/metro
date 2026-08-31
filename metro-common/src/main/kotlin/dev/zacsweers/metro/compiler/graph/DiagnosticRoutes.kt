// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.calculateInitialCapacity

/** Indexes shortest routes from graph roots to bindings for diagnostic traces. */
public class DiagnosticRoutes<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Entry : BaseBindingStack.BaseEntry<Type, TypeKey, ContextualTypeKey>,
>(
  private val roots: Map<ContextualTypeKey, Entry>,
  private val adjacency: Map<TypeKey, Set<TypeKey>>,
) {
  private var parents: Map<TypeKey, TypeKey>? = null
  private var rootEntries: Map<TypeKey, Entry>? = null

  /**
   * Returns stack entries from a root to [key], preserving the original root request.
   *
   * @param ensureActive cancellation callback polled while indexing and building the route.
   */
  public fun routeToRoot(
    key: TypeKey,
    ensureActive: () -> Unit = {},
    createDependencyEntry: (callingKey: TypeKey, dependencyKey: TypeKey) -> Entry,
  ): List<Entry> {
    // No need to walk through the graph without roots.
    if (roots.isEmpty()) return emptyList()
    if (parents == null) {
      buildIndex(ensureActive)
    }

    val indexedParents = checkNotNull(parents)
    if (key !in indexedParents) return emptyList()

    // Walk backwards from this binding until its root is reached.
    val path = ArrayList<TypeKey>()
    var current = key
    var parent = indexedParents.getValue(current)
    while (parent != current) {
      ensureActive()
      path.add(current)
      current = parent
      parent = indexedParents.getValue(current)
    }
    ensureActive()
    path.add(current)

    // Add entries root-first so pushing them preserves the existing binding stack order.
    val rootIndex = path.lastIndex
    val result = ArrayList<Entry>(path.size)
    ensureActive()
    result.add(checkNotNull(rootEntries).getValue(path[rootIndex]))
    for (index in rootIndex - 1 downTo 0) {
      ensureActive()
      result.add(createDependencyEntry(path[index + 1], path[index]))
    }
    return result
  }

  /** Builds deterministic shortest paths from every graph root without recursion. */
  private fun buildIndex(ensureActive: () -> Unit) {
    val indexedParents = HashMap<TypeKey, TypeKey>(calculateInitialCapacity(adjacency.size))
    val indexedRoots = HashMap<TypeKey, Entry>(calculateInitialCapacity(roots.size))
    val queue = ArrayDeque<TypeKey>(roots.size)

    // A stable sort preserves the first contextual request when roots share a type key.
    val sortedRoots = ArrayList<Map.Entry<ContextualTypeKey, Entry>>(roots.size)
    for (root in roots.entries) {
      ensureActive()
      sortedRoots += root
    }
    sortedRoots.sortWith { left, right ->
      ensureActive()
      left.key.typeKey.compareTo(right.key.typeKey)
    }
    for ((contextKey, entry) in sortedRoots) {
      ensureActive()
      val rootKey = contextKey.typeKey
      if (rootKey in indexedParents) continue
      indexedParents[rootKey] = rootKey
      indexedRoots[rootKey] = entry
      queue.addLast(rootKey)
    }

    // Forward adjacency already lists dependencies in deterministic sorted order.
    while (queue.isNotEmpty()) {
      ensureActive()
      val callingKey = queue.removeFirst()
      for (dependencyKey in adjacency[callingKey].orEmpty()) {
        ensureActive()
        if (dependencyKey in indexedParents) continue
        indexedParents[dependencyKey] = callingKey
        queue.addLast(dependencyKey)
      }
    }

    parents = indexedParents
    rootEntries = indexedRoots
  }
}
