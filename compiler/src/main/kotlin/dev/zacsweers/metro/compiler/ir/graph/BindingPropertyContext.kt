// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrProperty

/**
 * Represents a binding property along with the contextual type key it was stored under. This allows
 * consumers to know whether the property returns a provider or instance type.
 *
 * @property property The binding property itself
 * @property storedKey The contextual type key the property was stored under
 * @property shardProperty If non-null, the property lives in a shard class and must be accessed
 *   through this shard property (e.g., `graph.shard1.providerProperty`)
 * @property shardIndex If non-null, the index of the shard containing this property
 */
internal data class BindingProperty(
  val property: IrProperty,
  val storedKey: IrContextualTypeKey,
  val shardProperty: IrProperty? = null,
  val shardIndex: Int? = null,
)

/**
 * Tracks binding properties by their contextual type key. The contextual type key distinguishes
 * between scalar and provider access (e.g., `Foo` vs `Provider<Foo>`) as well as multibinding
 * variants (e.g., `Map<K, V>` vs `Map<K, Provider<V>>`).
 */
internal class BindingPropertyContext(private val bindingGraph: IrBindingGraph) {
  private val properties = mutableMapOf<IrContextualTypeKey, IrProperty>()
  private val shardProperties = mutableMapOf<IrContextualTypeKey, IrProperty>()
  private val shardIndices = mutableMapOf<IrContextualTypeKey, Int>()

  fun put(
    key: IrContextualTypeKey,
    property: IrProperty,
    shardProperty: IrProperty? = null,
    shardIndex: Int? = null,
  ) {
    properties[key] = property
    if (shardProperty != null) {
      shardProperties[key] = shardProperty
    }
    if (shardIndex != null) {
      shardIndices[key] = shardIndex
    }
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
      return BindingProperty(it, key, shardProperties[key], shardIndices[key])
    }

    // For non-provider requests, try provider key (a provider can satisfy an instance request)
    // - if it's a scalar (non-provider/lazy type)
    // - if it's a provider but _not_ a metro provider
    val tryReWrapping =
      !key.isWrappedInProvider ||
        key.isWrappedInLazy ||
        (key.wrappedType is WrappedType.Provider &&
          key.wrappedType.providerType != Symbols.ClassIds.metroProvider)
    if (tryReWrapping) {
      val providerKey = key.stripOuterProviderOrLazy().wrapInProvider()
      properties[providerKey]?.let {
        return BindingProperty(
          property = it,
          storedKey = providerKey,
          shardProperty = shardProperties[providerKey],
          shardIndex = shardIndices[providerKey],
        )
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
