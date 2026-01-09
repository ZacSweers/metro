// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.stripIfLazy
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import org.jetbrains.kotlin.ir.declarations.IrProperty

/**
 * Represents a binding property along with the contextual type key it was stored under. This allows
 * consumers to know whether the property returns a provider or instance type.
 */
internal data class BindingProperty(val property: IrProperty, val storedKey: IrContextualTypeKey)

/**
 * Tracks binding properties by their contextual type key. The contextual type key distinguishes
 * between scalar and provider access (e.g., `Foo` vs `Provider<Foo>`) as well as multibinding
 * variants (e.g., `Map<K, V>` vs `Map<K, Provider<V>>`).
 */
internal class BindingPropertyContext(private val bindingGraph: IrBindingGraph) {
  private val properties = mutableMapOf<IrContextualTypeKey, IrProperty>()

  fun put(key: IrContextualTypeKey, property: IrProperty) {
    properties[key] = property
  }

  /**
   * Looks up a property for the given contextual type key.
   *
   * For non-provider requests, this will also try the provider variant of the key since a provider
   * property can satisfy an instance request (via .invoke()).
   *
   * @return A [BindingProperty] containing both the property and the key it was stored under, or
   *   null if no matching property exists.
   */
  context(metroContext: IrMetroContext)
  fun get(key: IrContextualTypeKey): BindingProperty? {
    // Direct match
    properties[key]?.let {
      return BindingProperty(it, key)
    }

    // For non-provider requests, try provider key (a provider can satisfy an instance request)
    if (!key.isWrappedInProvider || key.isWrappedInLazy) {
      val providerKey = key.stripIfLazy().wrapInProvider()
      properties[providerKey]?.let {
        return BindingProperty(it, providerKey)
      }
    }

    // For aliases, try the aliased target
    bindingGraph.findBinding(key.typeKey)?.let {
      if (it is IrBinding.Alias) {
        return get(key.withIrTypeKey(it.aliasedType))
      }
    }
    return null
  }

  context(metroContext: IrMetroContext)
  operator fun contains(key: IrContextualTypeKey): Boolean = get(key) != null
}
