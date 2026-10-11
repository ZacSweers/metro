// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.MutableObjectIntMap
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.asCanonicalProviderKey
import dev.zacsweers.metro.compiler.ir.canonicalize
import java.util.Objects
import kotlin.reflect.KClass
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
 * @property sortedKeys This graph's keys in dependency order. Used to compare bindings with the
 *   parent graph.
 * @property graphKey The type key of the graph this context belongs to. Used to populate
 *   [BindingProperty.ownerGraphKey] when properties are found via parent lookup.
 * @property parent Optional parent context for hierarchical lookup in extension graphs
 */
internal class BindingPropertyContext(
  private val bindingGraph: IrBindingGraph,
  private val sortedKeys: List<IrTypeKey>,
  private val graphKey: IrTypeKey? = null,
  private val parent: BindingPropertyContext? = null,
) {
  private val properties = mutableMapOf<IrContextualTypeKey, IrProperty>()
  private val shardProperties = mutableMapOf<IrContextualTypeKey, IrProperty>()
  private val shardIndices = MutableObjectIntMap<IrContextualTypeKey>()

  /**
   * Fingerprints of this graph's bindings, keyed by type key. Bindings that can't be compared with
   * another graph's are left out.
   */
  private val fingerprints: Map<IrTypeKey, BindingFingerprint> by lazy {
    buildMap {
      // Dependencies come before their consumers, so their fingerprints already exist.
      for (key in sortedKeys) {
        val binding = bindingGraph.findBinding(key) ?: continue
        fingerprint(binding, this)?.let { put(key, it) }
      }
    }
  }

  private fun fingerprint(
    binding: IrBinding,
    known: Map<IrTypeKey, BindingFingerprint>,
  ): BindingFingerprint? {
    if (binding.isSuspend) {
      return null
    }
    val owner = binding.sharedInstanceOwner(graphKey)
    if (owner != null) {
      return BindingFingerprint.Shared(owner)
    }
    val source = binding.sourceDeclaration() ?: return null
    val dependencyFingerprints =
      binding.dependencies.map { dependency ->
        if (dependency.hasDefault) {
          return null
        }
        known[dependency.typeKey] ?: return null
      }
    return BindingFingerprint.Built(
      kind = binding::class,
      source = source,
      dependencies = binding.dependencies,
      dependencyFingerprints = dependencyFingerprints,
    )
  }

  /**
   * Returns the graph that owns this binding's shared instance, or null if each graph builds its
   * own.
   */
  private fun IrBinding.sharedInstanceOwner(graphKey: IrTypeKey?): IrTypeKey? =
    when (this) {
      is IrBinding.GraphDependency -> token?.ownerGraphKey
      is IrBinding.BoundInstance -> token?.ownerGraphKey ?: graphKey
      else -> graphKey.takeIf { isScoped() }
    }

  /** Returns the declaration this binding's value comes from, or null if it can't be compared. */
  private fun IrBinding.sourceDeclaration(): Any? =
    when (this) {
      is IrBinding.ConstructorInjected -> classFactory.factoryClass
      is IrBinding.Provided -> providerFactory.factoryClass
      is IrBinding.Alias -> bindsCallable?.function ?: aliasedType
      is IrBinding.ObjectClass -> type
      // Contributions are the multibinding's dependencies.
      is IrBinding.Multibinding -> typeKey
      else -> null
    }

  /**
   * Finds an ancestor property this graph can reuse for [key] without changing either resolved
   * graph.
   *
   * The nearest ancestor that has a binding for [key] decides. Its binding must build the same
   * value, so its fingerprint has to match this graph's. Bindings an ancestor owns are shared
   * through tokens instead.
   */
  context(metroContext: IrMetroContext)
  fun reusableBinding(key: IrContextualTypeKey): BindingProperty? {
    if (key.hasDefault) {
      return null
    }
    val fingerprint = fingerprints[key.typeKey] as? BindingFingerprint.Built ?: return null
    val ancestor = nearestAncestorWith(key.typeKey) ?: return null
    if (fingerprint != ancestor.fingerprints[key.typeKey]) {
      return null
    }
    val property = ancestor.get(key.canonicalize()) ?: return null
    return property.copy(ownerGraphKey = property.ownerGraphKey ?: ancestor.graphKey)
  }

  /**
   * Returns the nearest ancestor whose graph has a binding for [key].
   *
   * This walks up one level at a time. Graph trees are shallow, so it's only a few lookups. A
   * precomputed map from keys to ancestors would copy every ancestor's keys into each graph.
   */
  private fun nearestAncestorWith(key: IrTypeKey): BindingPropertyContext? {
    var current = parent
    while (current != null) {
      if (key in current.bindingGraph) {
        return current
      }
      current = current.parent
    }
    return null
  }

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
    fun localProperty(storedKey: IrContextualTypeKey): BindingProperty? {
      val property = properties[storedKey] ?: return null
      return BindingProperty(
        property = property,
        storedKey = storedKey,
        shardProperty = shardProperties[storedKey],
        shardIndex = shardIndices.getOrDefault(storedKey, -1).takeUnless { it == -1 },
      )
    }

    localProperty(key)?.let {
      return it
    }

    // Properties use one canonical Metro Provider/SuspendProvider layer regardless of how many
    // scalar wrappers the consumer requested. Preserve map value structure while normalizing the
    // outer stack, and prefer the provider kind required by the innermost scalar wrapper.
    val canonicalKey = key.canonicalize()
    val providerKey = canonicalKey.asCanonicalProviderKey(usesSuspendProvider = false)
    val suspendProviderKey = canonicalKey.asCanonicalProviderKey(usesSuspendProvider = true)
    val providerLookupKeys =
      if (key.wrappedType.usesSuspendProvider() == true) {
        listOf(suspendProviderKey, providerKey)
      } else {
        listOf(providerKey, suspendProviderKey)
      }
    for (providerLookupKey in providerLookupKeys) {
      localProperty(providerLookupKey)?.let {
        return it
      }
    }

    reusableBinding(key)?.let {
      return it
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

/** How a binding's value is produced, including everything it depends on. */
private sealed interface BindingFingerprint {
  /** An instance owned by [owner] and shared with its descendants. */
  data class Shared(val owner: IrTypeKey) : BindingFingerprint

  /** A value each graph builds from [source] and the given dependencies. */
  class Built(
    val kind: KClass<out IrBinding>,
    val source: Any,
    val dependencies: List<IrContextualTypeKey>,
    val dependencyFingerprints: List<BindingFingerprint>,
  ) : BindingFingerprint {
    // Fingerprints nest, so compare cached hashes first to keep mismatches cheap.
    private val hash = Objects.hash(kind, source, dependencies, dependencyFingerprints)

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
      if (this === other) {
        return true
      }
      if (other !is Built || hash != other.hash) {
        return false
      }
      return kind == other.kind &&
        source == other.source &&
        dependencies == other.dependencies &&
        dependencyFingerprints == other.dependencyFingerprints
    }
  }
}
