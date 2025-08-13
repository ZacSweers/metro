// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrClass

internal class ParentContext {
  // Track which keys are available at each level of the parent hierarchy
  private data class ParentLevel(
    val node: DependencyGraphNode,
    val providedKeys: MutableSet<IrTypeKey> = mutableSetOf(),
    val usedKeys: MutableSet<IrTypeKey> = mutableSetOf()
  )

  private val parentGraphStack = ArrayDeque<ParentLevel>()
  private val parentScopes = mutableSetOf<IrAnnotation>()
  // Keys pending to be added to the next pushed parent
  private val pendingKeys = mutableSetOf<IrTypeKey>()

  fun add(key: IrTypeKey) {
    // Keys are collected before pushParentGraph, so store them temporarily
    pendingKeys.add(key)
  }

  fun addAll(keys: Collection<IrTypeKey>) {
    pendingKeys.addAll(keys)
  }

  fun mark(key: IrTypeKey, scope: IrAnnotation? = null) {
    // Find which parent provides this key, searching from most recent to oldest
    var foundAtIndex = -1
    for (i in parentGraphStack.lastIndex downTo 0) {
      val level = parentGraphStack[i]
      if (key in level.providedKeys) {
        foundAtIndex = i
        break
      }
    }

    if (foundAtIndex >= 0) {
      // Mark the key as used in the provider
      parentGraphStack[foundAtIndex].usedKeys.add(key)

      // Also mark it in all intermediate graphs between the provider and the current level
      // This ensures scoped bindings are kept across intermediate graphs
      for (i in foundAtIndex + 1..parentGraphStack.lastIndex) {
        // Only mark in intermediate graphs that also have this key available
        // (they would have inherited it from their parent)
        if (key in parentGraphStack[i].providedKeys) {
          parentGraphStack[i].usedKeys.add(key)
        }
      }
    } else if (scope != null) {
      // If not found in any parent's providedKeys, it might be a constructor-injected class
      // with a matching scope discovered by the child. Add it to the level with matching scopes.
      for (i in parentGraphStack.lastIndex downTo 0) {
        val level = parentGraphStack[i]
        if (scope in level.node.scopes) {
          // Add this key to the level's providedKeys so it can be inherited by children
          level.providedKeys.add(key)
          level.usedKeys.add(key)

          // Also propagate to intermediate levels
          for (j in i + 1..parentGraphStack.lastIndex) {
            parentGraphStack[j].providedKeys.add(key)
            parentGraphStack[j].usedKeys.add(key)
          }
          break
        }
      }
    }
  }

  fun pushParentGraph(node: DependencyGraphNode) {
    val level = ParentLevel(node)
    // Transfer pending keys to this new level
    level.providedKeys.addAll(pendingKeys)
    pendingKeys.clear()

    // Also inherit all keys from parent levels
    // This ensures child graphs can see all keys available from their ancestors
    for (parentLevel in parentGraphStack) {
      level.providedKeys.addAll(parentLevel.providedKeys)
    }

    parentGraphStack.addLast(level)
    parentScopes.addAll(node.scopes)
  }

  fun popParentGraph() {
    val removed = parentGraphStack.removeLast()
    parentScopes.removeAll(removed.node.scopes)
  }

  val currentParentGraph: IrClass
    get() =
      parentGraphStack.lastOrNull()?.node?.metroGraphOrFail
        ?: error(
          "No parent graph on stack - this should only be accessed when processing extensions"
        )

  fun containsScope(scope: IrAnnotation): Boolean = scope in parentScopes

  operator fun contains(key: IrTypeKey): Boolean {
    // Check both pending keys and keys in the stack
    return key in pendingKeys || parentGraphStack.any { key in it.providedKeys }
  }

  fun availableKeys(): Set<IrTypeKey> {
    // Return all available keys from all parent levels plus pending
    val allKeys = mutableSetOf<IrTypeKey>()
    allKeys.addAll(pendingKeys)
    parentGraphStack.forEach { allKeys.addAll(it.providedKeys) }
    return allKeys
  }

  fun usedKeys(): Set<IrTypeKey> {
    // Return only the used keys for the current (most recent) parent level
    // This ensures that each parent only keeps the keys its direct children need
    return parentGraphStack.lastOrNull()?.usedKeys ?: emptySet()
  }
}
