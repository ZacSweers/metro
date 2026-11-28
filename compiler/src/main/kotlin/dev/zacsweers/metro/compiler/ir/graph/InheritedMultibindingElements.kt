// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.isImplicitClassKeySentinel

/**
 * Finds the declaration that an ancestor graph uses for a multibinding element that [node]
 * inherits.
 *
 * Some elements stay on the graph that owns them. Scoped `@Provides` entries and `@Binds` entries
 * with a graph-private source are examples. A graph extension sees them only as parent context
 * keys. This lookup goes back to the owner's own declaration so diagnostics can point at it.
 *
 * [ownerKey] must be a key of [GraphNode.allParentGraphs]. [elementKey] is the element's
 * `@MultibindingElement`-qualified key. Returns null when the owner isn't an ancestor of [node] or
 * doesn't declare [elementKey] itself.
 */
internal fun inheritedElementDeclaration(
  node: GraphNode,
  ownerKey: IrTypeKey,
  elementKey: IrTypeKey,
): GraphBindingDeclaration? {
  val owner = node.allParentGraphs[ownerKey] ?: return null

  // Every entry in a list shares the element key. The first one is enough for a location.
  val factory = owner.providerFactories[elementKey]?.firstOrNull()
  if (factory != null) {
    return GraphBindingDeclaration.Provider(factory)
  }

  val callable = owner.bindsCallables[elementKey]?.firstOrNull() ?: return null
  return GraphBindingDeclaration.Binds(callable)
}

/**
 * Returns the map key that an inherited map element emits, or null when it can't be read from
 * [elementKey] alone.
 *
 * The key comes from the element's [IrTypeKey.MultibindingKeyData]. It's null for set elements.
 * It's also null for an implicit class key that's still the `Nothing::class` sentinel, since
 * resolving that class needs the owner's binding.
 */
context(context: IrMetroContext)
internal fun inheritedElementMapKey(elementKey: IrTypeKey): IrAnnotation? {
  val mapKey = elementKey.multibindingKeyData?.mapKey ?: return null
  if (isImplicitClassKeySentinel(mapKey.ir)) {
    return null
  }

  return mapKey
}
