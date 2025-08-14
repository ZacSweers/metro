// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrClass

internal class ParentContext {

  private data class Level(
    val node: DependencyGraphNode,
    val deltaProvided: MutableSet<IrTypeKey> = mutableSetOf(),
    val usedKeys: MutableSet<IrTypeKey> = mutableSetOf(),
  )

  // Stack of parent graphs (root at 0, top is last)
  private val levels = ArrayDeque<Level>()

  // Fast membership of “currently available anywhere in stack”, not including pending
  private val available = mutableSetOf<IrTypeKey>()

  // For each key, the stack of level indices where it was introduced (nearest provider = last)
  private val keyIntroStack = mutableMapOf<IrTypeKey, ArrayDeque<Int>>()

  // All active scopes (union of level.node.scopes)
  private val parentScopes = mutableSetOf<IrAnnotation>()

  // Keys collected before the next push
  private val pending = mutableSetOf<IrTypeKey>()

  fun add(key: IrTypeKey) {
    pending.add(key)
  }

  fun addAll(keys: Collection<IrTypeKey>) {
    if (keys.isNotEmpty()) pending.addAll(keys)
  }

  fun mark(key: IrTypeKey, scope: IrAnnotation? = null) {
    // Prefer the nearest provider (deepest level that introduced this key)
    keyIntroStack[key]?.lastOrNull()?.let { providerIdx ->
      // Mark used from provider -> top (inclusive)
      for (i in providerIdx..levels.lastIndex) {
        levels[i].usedKeys.add(key)
      }
      return
    }

    // Not found but is scoped. Treat as constructor-injected with matching scope.
    if (scope != null) {
      for (i in levels.lastIndex downTo 0) {
        val level = levels[i]
        if (scope in level.node.scopes) {
          introduceAtLevel(i, key)
          // Mark used from that level -> top
          for (j in i..levels.lastIndex) {
            levels[j].usedKeys.add(key)
          }
          return
        }
      }
    }
    // Else: no-op (unknown key without scope)
  }

  fun pushParentGraph(node: DependencyGraphNode) {
    val idx = levels.size
    val level = Level(node)
    levels.addLast(level)
    parentScopes.addAll(node.scopes)

    if (pending.isNotEmpty()) {
      // Introduce each pending key *at this level only*
      for (k in pending) {
        introduceAtLevel(idx, k)
      }
      pending.clear()
    }
  }

  fun popParentGraph() {
    check(levels.isNotEmpty()) { "No parent graph to pop" }
    val idx = levels.lastIndex
    val removed = levels.removeLast()

    // Remove scope union
    parentScopes.removeAll(removed.node.scopes)

    // Roll back introductions made at this level
    for (k in removed.deltaProvided) {
      val stack = keyIntroStack[k]!!
      check(stack.removeLast() == idx)
      if (stack.isEmpty()) {
        keyIntroStack.remove(k)
        available.remove(k)
      }
      // If non-empty, key remains available due to an earlier level
    }
  }

  val currentParentGraph: IrClass
    get() =
      levels.lastOrNull()?.node?.metroGraphOrFail
        ?: error(
          "No parent graph on stack - this should only be accessed when processing extensions"
        )

  fun containsScope(scope: IrAnnotation): Boolean = scope in parentScopes

  operator fun contains(key: IrTypeKey): Boolean {
    return key in pending || key in available
  }

  fun availableKeys(): Set<IrTypeKey> {
    // Pending + all currently available
    if (pending.isEmpty()) return available.toSet()
    return buildSet(available.size + pending.size) {
      addAll(available)
      addAll(pending)
    }
  }

  fun usedKeys(): Set<IrTypeKey> {
    return levels.lastOrNull()?.usedKeys ?: emptySet()
  }

  private fun introduceAtLevel(levelIdx: Int, key: IrTypeKey) {
    val level = levels[levelIdx]
    // If already introduced earlier, avoid duplicating per-level delta
    if (key !in level.deltaProvided) {
      level.deltaProvided.add(key)
      available.add(key)
      keyIntroStack.getOrPut(key) { ArrayDeque() }.addLast(levelIdx)
    }
  }
}
