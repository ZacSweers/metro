// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

internal class IrBindingContainerResolver(private val transformer: BindingContainerTransformer) :
  IrMetroContext by transformer {

  /**
   * Cache for transitive closure of all included binding containers. Maps [ClassId] ->
   * [Set<BindingContainer>][BindingContainer] where the values represent all transitively included
   * binding containers starting from the given [ClassId].
   */
  private val transitiveBindingContainerCache = mutableMapOf<ClassId, Set<BindingContainer>>()

  /**
   * Resolves all binding containers transitively starting from the given roots. This method handles
   * caching and cycle detection to build the transitive closure of all included binding containers.
   */
  context(traceScope: TraceScope)
  fun resolve(roots: Set<IrClass>): Set<BindingContainer> =
    trace("Resolve binding containers") {
      if (roots.isEmpty()) return@trace emptySet()
      if (roots.size == 1) return@trace resolve(roots.first())

      val result = mutableSetOf<BindingContainer>()
      // Path tracking for cycle detection within the current traversal stack
      val path = mutableSetOf<ClassId>()

      for (root in roots) {
        result.addAll(getOrComputeClosure(root, path))
      }

      return@trace result
    }

  fun resolve(root: IrClass): Set<BindingContainer> {
    return getOrComputeClosure(root, mutableSetOf())
  }

  fun getCached(irClass: IrClass): Set<BindingContainer>? {
    return transitiveBindingContainerCache[irClass.classIdOrFail]
  }

  /**
   * Resolves all binding containers transitively starting from the given roots. This method handles
   * caching and cycle detection to build the transitive closure of all included binding containers.
   */
  context(traceScope: TraceScope)
  internal fun resolveTransitiveClosure(roots: Set<IrClass>): Set<IrClass> {
    return resolve(roots).mapTo(mutableSetOf()) { it.ir }
  }

  private fun getOrComputeClosure(
    irClass: IrClass,
    path: MutableSet<ClassId>,
  ): Set<BindingContainer> {
    val classId = irClass.classIdOrFail

    // 1. Check Global Cache (Memoization)
    transitiveBindingContainerCache[classId]?.let {
      return it
    }

    // 2. Cycle Detection (Recursion Stack)
    // If we see a node currently in our path, we stop recursing to break the cycle.
    // In a dependency graph "includes" relationship, A -> B -> A implies {A, B} are in the set.
    // Returning emptySet here is safe because the upstream caller (A) will eventually add itself
    // to the set.
    if (!path.add(classId)) {
      return emptySet()
    }

    try {
      // 3. Resolve Direct Container
      // If this isn't a valid container, we cache empty set to avoid re-processing.
      val container = transformer.findContainer(irClass)
      if (container == null) {
        val empty = emptySet<BindingContainer>()
        transitiveBindingContainerCache[classId] = empty
        return empty
      }

      // 4. Compute Closure (Recursive Step)
      val closure = mutableSetOf<BindingContainer>()
      closure.add(container)

      for (includedClassId in container.includes) {
        // Resolve ClassId to IrClass
        val includedClass = pluginContext.referenceClass(includedClassId)?.owner ?: continue
        closure.addAll(getOrComputeClosure(includedClass, path))
      }

      // 5. Store in Global Cache
      transitiveBindingContainerCache[classId] = closure
      return closure
    } finally {
      // Backtrack
      path.remove(classId)
    }
  }
}
