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
 * keys. This lookup goes back to the owner's own declaration. Diagnostics point at it, and
 * [inheritedElementBinding] reads the element's map key from it.
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

  // Every entry in a list shares the element key, so the first one stands for all of them.
  val factory = owner.providerFactories[elementKey]?.firstOrNull()
  if (factory != null) {
    return GraphBindingDeclaration.Provider(factory)
  }

  val callable = owner.bindsCallables[elementKey]?.firstOrNull() ?: return null
  return GraphBindingDeclaration.Binds(callable)
}

/**
 * Returns the binding that the owner graph declares for a multibinding element that [node]
 * inherits.
 *
 * [node] sees the element only as [dependency], which reads it from the owner. That binding has no
 * annotations or parameters. The owner's binding has both, so the element's map key is read from
 * it. An implicit class key needs this, since only the owner's declaration names the class.
 *
 * Returns null when the owner's declaration can't be found.
 */
internal fun inheritedElementBinding(
  node: GraphNode,
  dependency: IrBinding.GraphDependency,
): IrBinding.BindingWithAnnotations? {
  val declaration =
    inheritedElementDeclaration(node, dependency.ownerKey, dependency.typeKey) ?: return null

  return declaration.elementBinding()
}

/**
 * Returns the binding that this declaration registers in its graph, or null when it has none.
 *
 * Creator parameters, included accessors and scoped `@Inject` classes never declare multibinding
 * elements.
 */
private fun GraphBindingDeclaration.elementBinding(): IrBinding.BindingWithAnnotations? {
  return when (this) {
    is GraphBindingDeclaration.Provider -> factory.providedBinding

    is GraphBindingDeclaration.Binds -> {
      // Parameterless binds use constructor bindings and have no alias.
      if (callable.source == null) {
        null
      } else {
        callable.aliasBinding
      }
    }

    is GraphBindingDeclaration.CreatorParameter,
    is GraphBindingDeclaration.IncludedAccessor,
    is GraphBindingDeclaration.ScopedInjectedClass -> null
  }
}

/**
 * Returns the map key that an inherited map element emits, or null when it can't be read from
 * [elementKey] alone.
 *
 * The key comes from the element's [IrTypeKey.MultibindingKeyData]. It's null for set elements.
 * It's also null for an implicit class key that's still the `Nothing::class` sentinel. Resolving
 * that class needs the owner's binding from [inheritedElementBinding].
 */
context(context: IrMetroContext)
internal fun inheritedElementMapKey(elementKey: IrTypeKey): IrAnnotation? {
  val mapKey = elementKey.multibindingKeyData?.mapKey ?: return null
  if (isImplicitClassKeySentinel(mapKey.ir)) {
    return null
  }

  return mapKey
}
