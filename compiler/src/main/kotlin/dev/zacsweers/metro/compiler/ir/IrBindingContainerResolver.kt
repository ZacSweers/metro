// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

// TODO merge logic with BindingContainerTransformer's impl
internal class IrBindingContainerResolver(private val bindingContainerAnnotations: Set<ClassId>) {
  constructor(symbols: Symbols) : this(symbols.classIds.bindingContainerAnnotations)

  /**
   * Cache for transitive closure of all included binding containers. Maps [ClassId] ->
   * [Set<IrClass>][BindingContainer] where the values represent all transitively included binding
   * containers starting from the given [ClassId].
   */
  private val transitiveBindingContainerCache = mutableMapOf<ClassId, Set<IrClass>>()

  /**
   * Resolves all binding containers transitively starting from the given roots. This method handles
   * caching and cycle detection to build the transitive closure of all included binding containers.
   */
  internal fun resolveAllBindingContainersCached(roots: Set<IrClass>): Set<IrClass> {
    val result = mutableSetOf<IrClass>()
    val visitedClasses = mutableSetOf<ClassId>()

    for (root in roots) {
      val classId = root.classIdOrFail

      // Check if we already have this in cache
      transitiveBindingContainerCache[classId]?.let { cachedResult ->
        result.addAll(cachedResult)
        continue
      }

      // Compute transitive closure for this root
      val rootTransitiveClosure = computeTransitiveBindingContainers(root, visitedClasses)

      // Cache the result
      transitiveBindingContainerCache[classId] = rootTransitiveClosure
      result.addAll(rootTransitiveClosure)
    }

    return result
  }

  private fun computeTransitiveBindingContainers(
    root: IrClass,
    globalVisited: MutableSet<ClassId>,
  ): Set<IrClass> {
    val result = mutableSetOf<IrClass>()
    val localVisited = mutableSetOf<ClassId>()
    val queue = ArrayDeque<IrClass>()

    queue += root

    while (queue.isNotEmpty()) {
      val bindingContainerClass = queue.removeFirst()
      val classId = bindingContainerClass.classIdOrFail

      // Skip if we've already processed this class in any context
      if (classId in globalVisited || classId in localVisited) continue
      localVisited += classId
      globalVisited += classId

      // Check cache first for this specific class
      transitiveBindingContainerCache[classId]?.let { cachedResult ->
        result += cachedResult
        continue
      }

      val bindingContainerAnno =
        bindingContainerClass.annotationsIn(bindingContainerAnnotations).firstOrNull() ?: continue
      result += bindingContainerClass

      // Add included binding containers to the queue
      for (includedClass in
        bindingContainerAnno.includedClasses().map { it.classType.rawTypeOrNull() }) {
        if (includedClass != null && includedClass.classIdOrFail !in localVisited) {
          queue += includedClass
        }
      }
    }

    return result
  }
}
