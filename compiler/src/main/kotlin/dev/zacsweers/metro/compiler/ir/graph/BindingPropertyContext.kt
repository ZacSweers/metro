// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.MutableObjectIntMap
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
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
 * @property ownerGraphKey If non-null, this property was found in an ancestor context with this
 *   graph key. Used to determine if ancestor chain access is needed.
 */
internal data class BindingProperty(
  val property: IrProperty,
  val storedKey: IrContextualTypeKey,
  val shardProperty: IrProperty? = null,
  val shardIndex: Int? = null,
  val ownerGraphKey: IrTypeKey? = null,
)

/**
 * Tracks binding properties by their contextual type key. The contextual type key distinguishes
 * between scalar and provider access (e.g., `Foo` vs `Provider<Foo>`) as well as multibinding
 * variants (e.g., `Map<K, V>` vs `Map<K, Provider<V>>`).
 *
 * Supports hierarchical lookup through parent contexts for extension graphs. When a property is
 * found in a parent context, the returned [BindingProperty] includes the
 * [BindingProperty.ownerGraphKey] to indicate which ancestor owns the property.
 *
 * @property bindingGraph The binding graph for this context
 * @property graphKey The type key of the graph this context belongs to. Used to populate
 *   [BindingProperty.ownerGraphKey] when properties are found via parent lookup.
 * @property parent Optional parent context for hierarchical lookup in extension graphs
 */
internal class BindingPropertyContext(
  private val bindingGraph: IrBindingGraph,
  private val graphKey: IrTypeKey? = null,
  private val parent: BindingPropertyContext? = null,
) {
  private val properties = mutableMapOf<IrContextualTypeKey, IrProperty>()
  private val shardProperties = mutableMapOf<IrContextualTypeKey, IrProperty>()
  private val shardIndices = MutableObjectIntMap<IrContextualTypeKey>()

  /** Lazily computed map of ancestor graph keys to their contexts. */
  private val ancestorContextCache: Map<IrTypeKey, BindingPropertyContext> by lazy {
    buildMap {
      var current: BindingPropertyContext? = parent
      while (current != null) {
        current.graphKey?.let { put(it, current) }
        current = current.parent
      }
    }
  }

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
   * When [searchParents] is true, this will search parent contexts if the key is not found locally.
   * Properties found in parent contexts will have [BindingProperty.ownerGraphKey] set to indicate
   * which ancestor owns the property. By default, only the local context is searched.
   *
   * @param key The contextual type key to look up
   * @param searchParents Whether to search parent contexts if not found locally (default: false)
   * @return A [BindingProperty] containing both the property and the key it was stored under, or
   *   null if no matching property exists.
   */
  context(metroContext: IrMetroContext)
  fun get(key: IrContextualTypeKey, searchParents: Boolean = false): BindingProperty? {
    // Direct match in current context
    properties[key]?.let { property ->
      return BindingProperty(
        property = property,
        storedKey = key,
        shardProperty = shardProperties[key],
        shardIndex = shardIndices.getOrDefault(key, -1).takeUnless { it == -1 },
      )
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
          shardIndex = shardIndices.getOrDefault(providerKey, -1).takeUnless { it == -1 },
        )
      }
    }

    // For aliases, try the aliased target
    bindingGraph.findBinding(key.typeKey)?.let {
      if (it is IrBinding.Alias) {
        return get(key.withIrTypeKey(it.aliasedType), searchParents)
      }
    }

    // Search parent context if allowed
    if (searchParents && parent != null) {
      parent.get(key, searchParents = true)?.let { parentResult ->
        // Mark with the owner graph key (use parent's key or propagate existing)
        return parentResult.copy(ownerGraphKey = parentResult.ownerGraphKey ?: parent.graphKey)
      }
    }

    return null
  }

  /**
   * Finds the ancestor context with the given graph key.
   *
   * @param targetGraphKey The graph type key to find
   * @return The ancestor context with the matching graph key, or null if not found
   */
  fun findAncestorContext(targetGraphKey: IrTypeKey): BindingPropertyContext? =
    ancestorContextCache[targetGraphKey]

  context(metroContext: IrMetroContext)
  operator fun contains(key: IrContextualTypeKey): Boolean = get(key) != null
}
