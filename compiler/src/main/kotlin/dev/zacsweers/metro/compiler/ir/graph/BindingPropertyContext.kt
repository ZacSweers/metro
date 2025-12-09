// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

internal class BindingPropertyContext(private val bindingGraph: IrBindingGraph) {
  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Properties for this graph and other instance params
  private val instanceProperties = mutableMapOf<IrTypeKey, BindingProperty>()
  // Properties for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerProperties = mutableMapOf<IrTypeKey, BindingProperty>()

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceProperties.keys

  val availableProviderKeys: Set<IrTypeKey>
    get() = providerProperties.keys

  fun hasKey(key: IrTypeKey): Boolean = key in instanceProperties || key in providerProperties

  // TODO replace with BindingProperty param overloads?

  fun putInstanceProperty(
    key: IrTypeKey,
    property: IrProperty,
    location: PropertyLocation = PropertyLocation.InGraphImpl,
  ) {
    instanceProperties[key] = BindingProperty(property, location)
  }

  fun putProviderProperty(
    key: IrTypeKey,
    property: IrProperty,
    location: PropertyLocation = PropertyLocation.InGraphImpl,
  ) {
    providerProperties[key] = BindingProperty(property, location)
  }

  /** Returns the property entry for an instance property, including its location. */
  fun instancePropertyEntry(key: IrTypeKey): BindingProperty? {
    instanceProperties[key]?.let {
      return it
    }
    bindingGraph.findBinding(key)?.let {
      if (it is IrBinding.Alias) {
        // try the aliased target
        return instancePropertyEntry(it.aliasedType)
      }
    }
    return null
  }

  /** Returns the property entry for a provider property, including its location. */
  fun providerPropertyEntry(key: IrTypeKey): BindingProperty? {
    return providerProperties[key]
  }

  operator fun contains(key: IrTypeKey): Boolean =
    instanceProperties.containsKey(key) || providerProperties.containsKey(key)
}

/**
 * Represents where a property is located in the generated graph class structure.
 *
 * With sharding, provider properties may be located either directly on the graph class or within a
 * shard class.
 */
internal sealed interface PropertyLocation {
  /** Property is directly on the graph class. */
  data object InGraphImpl : PropertyLocation

  /**
   * Property is within a shard class.
   *
   * @param shardField The property on the graph class that holds the shard instance
   * @param shardClass The shard class containing the property
   */
  data class InShard(val shardField: IrProperty, val shardClass: IrClass) : PropertyLocation
}

/**
 * A property entry that includes both the property and its location in the graph structure.
 *
 * @param irProperty The actual IR property
 * @param location Where the property is located (graph direct or in a shard)
 */
internal data class BindingProperty(val irProperty: IrProperty, val location: PropertyLocation)

/**
 * Context for generating property access expressions, handling both graph-direct and shard contexts.
 *
 * In graph-direct context (`graphBackingField == null`):
 * - `currentClass` is the graph impl class
 * - `thisReceiver` is the graph's `this`
 * - Properties are accessed directly via `this`
 *
 * In shard context (`graphBackingField != null`):
 * - `currentClass` is the shard class being initialized
 * - `thisReceiver` is the shard's `this`
 * - Same-shard properties accessed via `this`
 * - Cross-shard/graph properties accessed via `this.graph`
 *
 * @param currentClass The class where code is being generated (graph impl or shard)
 * @param thisReceiver The `this` receiver for the current class
 * @param graphBackingField The backing field for graph access from a shard, or null if in graph-direct context
 */
internal data class ShardContext(
  val currentClass: IrClass,
  val thisReceiver: IrValueParameter,
  val graphBackingField: IrField?,
)
