// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.calculateInitialCapacity
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/** Indexes shortest routes from graph roots to bindings for diagnostic traces. */
internal class DiagnosticRoutes(
  private val roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
  private val adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
) {
  private var parents: Map<IrTypeKey, IrTypeKey>? = null
  private var rootEntries: Map<IrTypeKey, IrBindingStack.Entry>? = null

  /** Returns stack entries from a root to [key], preserving the original root request. */
  fun routeToRoot(
    key: IrTypeKey,
    createDependencyEntry:
      (callingKey: IrTypeKey, dependencyKey: IrTypeKey) -> IrBindingStack.Entry,
  ): List<IrBindingStack.Entry> {
    // No need to walk through the graph without roots.
    if (roots.isEmpty()) return emptyList()

    if (parents == null) {
      buildIndex()
    }

    val indexedParents = checkNotNull(parents)
    if (key !in indexedParents) return emptyList()

    // Walk backwards from this binding until its root is reached.
    val path = ArrayList<IrTypeKey>()
    var current = key
    var parent = indexedParents.getValue(current)
    while (parent != current) {
      path.add(current)
      current = parent
      parent = indexedParents.getValue(current)
    }
    path.add(current)

    // Add entries root-first so pushing them preserves the existing binding stack order.
    val rootIndex = path.lastIndex
    val result = ArrayList<IrBindingStack.Entry>(path.size)
    result.add(checkNotNull(rootEntries).getValue(path[rootIndex]))
    for (index in rootIndex - 1 downTo 0) {
      result.add(createDependencyEntry(path[index + 1], path[index]))
    }
    return result
  }

  /** Builds deterministic shortest paths from every graph root without recursion. */
  private fun buildIndex() {
    val indexedParents = HashMap<IrTypeKey, IrTypeKey>(calculateInitialCapacity(adjacency.size))
    val indexedRoots =
      HashMap<IrTypeKey, IrBindingStack.Entry>(calculateInitialCapacity(roots.size))
    val queue = ArrayDeque<IrTypeKey>(roots.size)

    // A stable sort preserves the first contextual request when roots share a type key.
    for ((contextKey, entry) in roots.entries.sortedBy { it.key.typeKey }) {
      val rootKey = contextKey.typeKey
      if (rootKey in indexedParents) continue
      indexedParents[rootKey] = rootKey
      indexedRoots[rootKey] = entry
      queue.addLast(rootKey)
    }

    // Forward adjacency already lists dependencies in deterministic sorted order.
    while (queue.isNotEmpty()) {
      val callingKey = queue.removeFirst()
      for (dependencyKey in adjacency[callingKey].orEmpty()) {
        if (dependencyKey in indexedParents) continue
        indexedParents[dependencyKey] = callingKey
        queue.addLast(dependencyKey)
      }
    }

    parents = indexedParents
    rootEntries = indexedRoots
  }
}
