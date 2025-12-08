// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.declarations.IrProperty

internal class BindingPropertyContext(private val bindingGraph: IrBindingGraph) {
  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Properties for this graph and other instance params
  private val instanceProperties = mutableMapOf<IrTypeKey, IrProperty>()
  // Properties for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerProperties = mutableMapOf<IrTypeKey, IrProperty>()

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceProperties.keys

  val availableProviderKeys: Set<IrTypeKey>
    get() = providerProperties.keys

  fun hasKey(key: IrTypeKey): Boolean = key in instanceProperties || key in providerProperties

  fun putInstanceProperty(key: IrTypeKey, property: IrProperty) {
    instanceProperties[key] = property
  }

  fun putProviderProperty(key: IrTypeKey, property: IrProperty) {
    providerProperties[key] = property
  }

  fun instanceProperty(key: IrTypeKey): IrProperty? {
    instanceProperties[key]?.let {
      return it
    }
    bindingGraph.findBinding(key)?.let {
      if (it is IrBinding.Alias) {
        // try the aliased target
        return instanceProperty(it.aliasedType)
      }
    }
    return null
  }

  fun providerProperty(key: IrTypeKey): IrProperty? {
    return providerProperties[key]
  }

  operator fun contains(key: IrTypeKey): Boolean =
    instanceProperties.containsKey(key) || providerProperties.containsKey(key)
}
