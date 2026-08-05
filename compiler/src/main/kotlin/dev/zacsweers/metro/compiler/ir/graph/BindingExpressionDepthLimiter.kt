// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyCollector.CollectedProperty

/**
 * Maximum number of consecutive bindings generated without a property boundary. If we exceed this,
 * we use a stack-less approach to process via [BindingExpressionDepthLimiter].
 */
internal const val MAX_INLINE_BINDING_DEPTH = 64

/**
 * Limits binding expression depth by inserting graph getter properties.
 *
 * Getter properties keep unscoped bindings unscoped while limiting both expression-generation
 * recursion and the depth of generated Kotlin IR.
 *
 * Before
 *
 * ```
 * val root: A get() = A(B(C(D(E()))))
 * ```
 *
 * After
 *
 * ```
 * private val d: D get() = D(E())
 * val root: A get() = A(B(C(d)))
 * ```
 */
internal class BindingExpressionDepthLimiter(
  private val graph: IrBindingGraph,
  private val sortedKeys: List<IrTypeKey>,
  private val reachableKeys: Set<IrTypeKey>,
  private val properties: MutableMap<IrContextualTypeKey, CollectedProperty>,
  private val requiresProviderGetter: (IrContextualTypeKey) -> Boolean,
) {
  /** Inserts getter properties wherever an eager binding chain reaches the maximum depth. */
  fun limitDepth() {
    val inlineDepths = HashMap<IrTypeKey, Int>(sortedKeys.size)
    val propertyKeys = properties.keys.mapTo(HashSet(properties.size)) { it.typeKey }

    // Dependencies precede their consumers in the existing topological order.
    for (key in sortedKeys) {
      if (key !in reachableKeys) continue

      val binding = graph.findBinding(key) ?: continue
      if (key in propertyKeys) {
        inlineDepths[key] = 0
        continue
      }

      var inlineDepth = 1
      for (dependency in binding.dependencies) {
        inlineDepth = maxOf(inlineDepth, (inlineDepths[dependency.typeKey] ?: 0) + 1)
      }

      if (inlineDepth < MAX_INLINE_BINDING_DEPTH || !binding.canUseDepthLimitingGetter()) {
        inlineDepths[key] = inlineDepth
        continue
      }

      val contextKey = binding.contextualTypeKey
      val isSuspendBinding = binding.isSuspend || graph.isTransitivelySuspend(key)
      // A suspend depth-limiting getter returns its provider so it never invokes suspend code.
      val needsProviderGetter = isSuspendBinding || requiresProviderGetter(contextKey)
      properties[contextKey] =
        CollectedProperty(
          binding = binding,
          propertyKind = PropertyKind.GETTER,
          contextualTypeKey = contextKey,
          // Factory-only paths need a Provider getter so provider requests find the boundary.
          isProviderType = needsProviderGetter,
        )
      propertyKeys += key
      inlineDepths[key] = 0
    }
  }

  /** Only ordinary constructor and provided bindings can safely use a depth-limiting getter. */
  private fun IrBinding.canUseDepthLimitingGetter(): Boolean {
    return when (this) {
      is IrBinding.ConstructorInjected -> !isAssisted
      is IrBinding.Provided -> true
      else -> false
    }
  }
}
