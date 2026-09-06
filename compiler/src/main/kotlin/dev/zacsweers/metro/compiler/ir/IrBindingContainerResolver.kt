// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

@Inject
@SingleIn(IrScope::class)
internal class IrBindingContainerResolver(private val transformer: BindingContainerTransformer) :
  IrMetroContext by transformer {

  /**
   * Cache for transitive closure of all included binding containers. Maps [ClassId] ->
   * [Set<BindingContainer>][BindingContainer] where the values represent all transitively included
   * binding containers starting from the given [ClassId].
   *
   * Thread-safe for concurrent access during parallel graph validation.
   */
  private val transitiveBindingContainerCache = ConcurrentHashMap<ClassId, Set<BindingContainer>>()

  /** Resolves complete include closures for each root, preserving their iteration order. */
  context(traceScope: TraceScope)
  fun resolve(roots: Set<IrClass>): Set<BindingContainer> =
    trace("Resolve binding containers") {
      if (roots.isEmpty()) {
        return@trace emptySet()
      }
      if (roots.size == 1) {
        return@trace resolve(roots.first())
      }

      val result = mutableSetOf<BindingContainer>()

      for (root in roots) {
        result.addAll(resolve(root))
      }

      return@trace result
    }

  /** Returns a complete cached closure or computes it without publishing intermediate results. */
  fun resolve(root: IrClass): Set<BindingContainer> = getOrComputeClosure(root)

  /** Returns the declarations in the roots' complete include closures. */
  context(traceScope: TraceScope)
  internal fun resolveTransitiveClosure(roots: Set<IrClass>): Set<IrClass> {
    return resolve(roots).mapTo(mutableSetOf()) { it.ir }
  }

  private fun getOrComputeClosure(root: IrClass): Set<BindingContainer> {
    val classId = root.classIdOrFail
    // Cached root closures are complete even when the include graph contains cycles.
    transitiveBindingContainerCache[classId]?.let {
      return it
    }

    val closure = buildSet {
      val visited = mutableSetOf<ClassId>()
      val pending = ArrayDeque<IrClass>()
      pending.addLast(root)

      while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        val currentId = current.classIdOrFail
        // Visit each container once per root to break cycles and skip shared includes.
        if (!visited.add(currentId)) {
          continue
        }

        // A cached closure already covers every include reachable from this container.
        val cached = transitiveBindingContainerCache[currentId]
        if (cached != null) {
          addAll(cached)
          continue
        }

        // A missing root container produces an empty closure that can still be cached.
        val container = transformer.findContainer(current) ?: continue
        add(container)

        // The stack visits includes in declaration order without recursive calls.
        for (includedClassId in container.includes.reversed()) {
          val includedClass = current.lookupClass(includedClassId)?.owner ?: continue
          pending.addLast(includedClass)
        }
      }
    }

    // Only completed root traversals are safe to cache because cycles can leave subtree results
    // incomplete.
    return transitiveBindingContainerCache.putIfAbsent(classId, closure) ?: closure
  }
}
