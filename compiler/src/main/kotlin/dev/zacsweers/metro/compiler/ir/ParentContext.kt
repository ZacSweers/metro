// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyCollector
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/** Tracks one child graph's parent requests. Reads and writes stay on the main compiler thread. */
internal class UsedKeyCollector {
  private val usedKeys = mutableSetOf<IrContextualTypeKey>()
  private val sharedReads = mutableMapOf<IrTypeKey, MutableSet<IrTypeKey?>>()

  fun record(contextKey: IrContextualTypeKey) {
    usedKeys.add(contextKey)
  }

  /** A null consumer requires a getter for the dependency itself. */
  fun recordShared(key: IrTypeKey, consumer: IrTypeKey?) {
    if (consumer == key) {
      return
    }
    sharedReads.getOrPut(key) { mutableSetOf() }.add(consumer)
  }

  fun keys(): Set<IrContextualTypeKey> = usedKeys.toSet()

  fun sharedReads(): Map<IrTypeKey, Set<IrTypeKey?>> = sharedReads.mapValues { it.value.toSet() }
}

private enum class ParentContextLookupMode {
  MARK_USED,
  TOKEN_ONLY,
}

/**
 * Common interface for reading from a parent context. [ParentContext.asReader] creates one for each
 * child graph.
 */
internal interface ParentContextReader {
  /** Checks if a key is available in the parent context. */
  operator fun contains(key: IrTypeKey): Boolean

  /** Checks if a scope matches any level in the parent context. */
  fun containsScope(scope: IrAnnotation): Boolean

  /** Returns all scopes across all levels in the parent context. */
  fun scopes(): Set<IrAnnotation>

  /** Returns all available keys in the parent context. */
  fun availableKeys(): Set<IrTypeKey>

  /** Returns graph-private keys from parent graphs (for hinting in missing binding messages). */
  fun graphPrivateKeys(): Set<IrTypeKey> = emptySet()

  /** The current (topmost) parent graph. */
  val currentParentGraph: IrClass

  /** Returns a token without marking the key as used. */
  fun findToken(key: IrTypeKey): ParentContext.Token?

  /**
   * Marks a key as used and returns a token for later property resolution.
   *
   * @param key The typekey to mark
   * @param scope Optional scope annotation for scoped bindings
   * @param requiresProviderProperty If true, marks this as Provider<T> access
   */
  fun mark(
    key: IrTypeKey,
    scope: IrAnnotation? = null,
    requiresProviderProperty: Boolean = scope != null,
  ): ParentContext.Token?

  /**
   * Returns how many ancestor graphs in a row declare [binding] the same way, starting with the
   * nearest parent. A binding can only be shared with that many ancestors.
   */
  fun sharedDepth(binding: IrBinding): Int = 0

  /**
   * Returns how many levels up the ancestor graph [graphKey] is. The nearest parent is 1. Graphs
   * that aren't ancestors return 0.
   */
  fun ancestorDepth(graphKey: IrTypeKey): Int = 0

  /**
   * Records that this graph reads [key] and the nearest [depth] ancestor graphs can build it the
   * same way. The consumer can be built by the nearest [consumerDepth] ancestors. A null consumer
   * means the child needs a getter for the dependency itself.
   */
  fun markShared(key: IrTypeKey, depth: Int, consumer: IrTypeKey?, consumerDepth: Int) {}

  /**
   * Returns true when the ancestor graph that owns [key] can hand each request a new instance.
   * Lifecycle warnings use this for callback parameters that a child reads from a parent.
   */
  fun ownerCreatesFreshInstances(key: IrTypeKey): Boolean = false
}

/**
 * Captures the parent's available keys for child graph preparation. Each child records requests in
 * its own [UsedKeyCollector]. Lookups stay on the main compiler thread.
 */
internal class ParentContext(
  private val metroContext: IrMetroContext,
  /** Available keys mapped to their ownership info. */
  private val keyOwnership: Map<IrTypeKey, KeyOwnership>,
  /** Scopes available at each level (for scope matching). */
  private val levelScopes: List<LevelInfo>,
  /** The current (topmost) parent graph. */
  val currentParentGraph: IrClass,
  /**
   * Delegates missing keys and scopes to earlier ancestors. A child graph's snapshot includes its
   * local parent level and uses this reader to reach the rest of the hierarchy.
   */
  private val ancestorReader: ParentContextReader? = null,
  /** Graph-private keys from parent graphs (for hinting in missing binding messages). */
  private val graphPrivateKeys: Set<IrTypeKey> = emptySet(),
  /** The nearest parent graph's key, or null if there's no parent graph. */
  private val parentGraphKey: IrTypeKey? = null,
  /** Whether the nearest parent graph declares a binding the same way. */
  private val declaresSameBinding: (IrBinding) -> Boolean = { false },
) {
  /** Ownership information for a key - which graph owns it and how to access it. */
  data class KeyOwnership(
    val ownerGraphKey: IrTypeKey,
    val receiverParameter: IrValueParameter,
    val isSuspend: (IrTypeKey) -> Boolean,
    val createsFreshInstances: (IrTypeKey) -> Boolean,
  )

  /** Information about a level for scope matching. */
  data class LevelInfo(val scopes: Set<IrAnnotation>, val ownership: KeyOwnership)

  private val allScopes: Set<IrAnnotation> = buildSet {
    for ((scopes) in levelScopes) {
      addAll(scopes)
    }
    ancestorReader?.scopes()?.let(::addAll)
  }

  operator fun contains(key: IrTypeKey): Boolean =
    key in keyOwnership || ancestorReader?.let { key in it } == true

  fun containsScope(scope: IrAnnotation): Boolean = scope in allScopes

  fun scopes(): Set<IrAnnotation> = allScopes

  fun availableKeys(): Set<IrTypeKey> {
    val ancestorKeys = ancestorReader?.availableKeys()
    return if (ancestorKeys.isNullOrEmpty()) {
      keyOwnership.keys
    } else {
      keyOwnership.keys + ancestorKeys
    }
  }

  /** Returns a token without recording the key in a child graph's collector. */
  fun findToken(key: IrTypeKey): ParentContext.Token? =
    resolve(
      key = key,
      scope = null,
      requiresProviderProperty = false,
      collector = null,
      mode = ParentContextLookupMode.TOKEN_ONLY,
    )

  /** Records a key in the child's [collector] and returns a token for later property resolution. */
  fun mark(
    key: IrTypeKey,
    scope: IrAnnotation? = null,
    requiresProviderProperty: Boolean = scope != null,
    collector: UsedKeyCollector,
  ): ParentContext.Token? =
    resolve(
      key = key,
      scope = scope,
      requiresProviderProperty = requiresProviderProperty,
      collector = collector,
      mode = ParentContextLookupMode.MARK_USED,
    )

  private fun resolve(
    key: IrTypeKey,
    scope: IrAnnotation?,
    requiresProviderProperty: Boolean,
    collector: UsedKeyCollector?,
    mode: ParentContextLookupMode,
  ): ParentContext.Token? {
    val contextKey =
      metroContext.createParentContextKey(
        key,
        isProvider = requiresProviderProperty || scope != null,
      )

    // Check if key is already available
    keyOwnership[key]?.let { ownership ->
      collector?.record(contextKey)
      return ParentContext.Token(
        contextKey = contextKey,
        ownerGraphKey = ownership.ownerGraphKey,
        receiverParameter = ownership.receiverParameter,
        isSuspend = ownership.isSuspend(key),
      )
    }

    // For scoped keys, find a matching level
    if (scope != null) {
      for ((scopes, ownership) in levelScopes.asReversed()) {
        if (scope in scopes) {
          collector?.record(contextKey)
          return ParentContext.Token(
            contextKey = contextKey,
            ownerGraphKey = ownership.ownerGraphKey,
            receiverParameter = ownership.receiverParameter,
            isSuspend = ownership.isSuspend(key),
          )
        }
      }
    }

    // Key not found locally, delegate to ancestor reader
    ancestorReader?.let { reader ->
      val token =
        when (mode) {
          ParentContextLookupMode.MARK_USED -> reader.mark(key, scope, requiresProviderProperty)
          ParentContextLookupMode.TOKEN_ONLY -> reader.findToken(key)
        }
      token?.let {
        collector?.record(contextKey)
        return token
      }
    }

    return null
  }

  fun graphPrivateKeys(): Set<IrTypeKey> = graphPrivateKeys

  fun ownerCreatesFreshInstances(key: IrTypeKey): Boolean {
    val ownership =
      keyOwnership[key] ?: return ancestorReader?.ownerCreatesFreshInstances(key) == true
    return ownership.createsFreshInstances(key)
  }

  /** Creates a [ParentContextReader] backed by this snapshot and the given collector. */
  fun asReader(collector: UsedKeyCollector): ParentContextReader =
    object : ParentContextReader {
      override fun contains(key: IrTypeKey) = key in this@ParentContext

      override fun containsScope(scope: IrAnnotation) = this@ParentContext.containsScope(scope)

      override fun scopes() = this@ParentContext.scopes()

      override fun availableKeys() = this@ParentContext.availableKeys()

      override fun graphPrivateKeys() = this@ParentContext.graphPrivateKeys()

      override val currentParentGraph = this@ParentContext.currentParentGraph

      override fun findToken(key: IrTypeKey) = this@ParentContext.findToken(key)

      override fun ownerCreatesFreshInstances(key: IrTypeKey) =
        this@ParentContext.ownerCreatesFreshInstances(key)

      override fun sharedDepth(binding: IrBinding): Int {
        if (!declaresSameBinding(binding)) return 0
        val depthAbove = ancestorReader?.sharedDepth(binding) ?: 0
        return depthAbove + 1
      }

      override fun ancestorDepth(graphKey: IrTypeKey): Int {
        if (graphKey == parentGraphKey) return 1
        val depthAbove = ancestorReader?.ancestorDepth(graphKey) ?: 0
        return if (depthAbove == 0) {
          0
        } else {
          depthAbove + 1
        }
      }

      override fun markShared(
        key: IrTypeKey,
        depth: Int,
        consumer: IrTypeKey?,
        consumerDepth: Int,
      ) {
        val sharedConsumer =
          if (consumerDepth > 0) {
            consumer
          } else {
            null
          }
        collector.recordShared(key, sharedConsumer)
        if (depth > 1) {
          ancestorReader?.markShared(key, depth - 1, sharedConsumer, consumerDepth - 1)
        }
      }

      override fun mark(
        key: IrTypeKey,
        scope: IrAnnotation?,
        requiresProviderProperty: Boolean,
      ) =
        this@ParentContext.mark(
          key,
          scope,
          requiresProviderProperty,
          collector,
        )
    }

  /**
   * Token returned by [mark] during validation phase. Contains enough information to resolve the
   * actual property during generation phase, after parent's [BindingPropertyCollector] has
   * finalized property kinds.
   */
  data class Token(
    val contextKey: IrContextualTypeKey,
    /** The typekey of the parent graph that owns this binding. */
    val ownerGraphKey: IrTypeKey,
    /** The receiver parameter for the parent graph (for generating access expressions). */
    val receiverParameter: IrValueParameter,
    /** Whether resolving the underlying binding requires a suspend context in the owner graph. */
    val isSuspend: Boolean,
  )

  /**
   * Data for property access tracking (used during generation phase).
   *
   * Encapsulates all the information needed to generate a property access expression, including
   * ancestor graph chains (for extension graphs) and shard navigation. This provides a unified
   * abstraction for property access that handles:
   * - Direct property access: `receiver.property`
   * - Sharded property access: `receiver.shardProperty.property`
   * - Ancestor graph access: `receiver.parentGraph.grandparentGraph.property`
   * - Combined: `receiver.parentGraph.shardProperty.property`
   *
   * @property ownerGraphKey The type key of the graph that owns this property
   * @property property The property to access (may be on the graph directly or on a shard)
   * @property shardProperty If non-null, the property must be accessed through this shard property
   *   first (i.e., `receiver.shardProperty.property` instead of `receiver.property`)
   * @property ancestorChain If non-null, properties to chain through to reach the ancestor graph
   *   that owns this binding. For extension graphs accessing parent bindings.
   * @property shardGraphProperty For shard contexts, the property on the shard that references the
   *   main graph class. Must be accessed first before the ancestor chain.
   * @property isProviderProperty Whether the property returns a Provider type
   */
  @Poko
  class PropertyAccess(
    val ownerGraphKey: IrTypeKey,
    private val property: IrProperty,
    private val shardProperty: IrProperty? = null,
    private val ancestorChain: List<IrProperty>? = null,
    private val shardGraphProperty: IrProperty? = null,
    // TODO Use AccessType for parent-property tokens and collected binding properties.
    val isProviderProperty: Boolean,
    /** Whether the property stores a SuspendProvider (suspend binding fields). */
    val isSuspendProviderProperty: Boolean = false,
  ) {

    private val ancestorPropertiesChain by memoize {
      // Build the full chain of properties to traverse
      buildList {
        shardGraphProperty?.let(::add)
        ancestorChain?.let(::addAll)
        shardProperty?.let(::add)
        add(property)
      }
    }

    /**
     * Generates an IR expression to access this property on the receiver.
     *
     * Handles the full property access chain:
     * 1. If in shard context, first access the graph property: `receiver.graphProperty`
     * 2. Chain through ancestor graphs if present: `...parentGraph.grandparentGraph`
     * 3. Chain through shard property if present: `...shardProperty`
     * 4. Finally access the target property: `...property`
     *
     * @param baseReceiver The base receiver expression (typically `irGet(thisReceiver)`)
     */
    context(scope: IrBuilderWithScope)
    fun accessProperty(baseReceiver: IrExpression): IrExpression {
      // Fold through the chain to build the access expression
      return ancestorPropertiesChain.fold(baseReceiver) { receiver, prop ->
        scope.irGetProperty(receiver, prop)
      }
    }
  }
}

/** Keeps map and provider wrappers consistent with the keys stored by addBoundInstanceProperty. */
private fun IrMetroContext.createParentContextKey(
  key: IrTypeKey,
  isProvider: Boolean,
): IrContextualTypeKey {
  // Map types use WrappedType.Map so the token's contextKey matches the property stored in
  // BindingPropertyContext by addBoundInstanceProperty.
  val baseKey =
    key.type.asContextualTypeKey(
      qualifierAnnotation = key.qualifier,
      hasDefault = false,
      patchMutableCollections = false,
      declaration = null,
    )
  return if (isProvider) {
    baseKey.wrapInProvider()
  } else {
    baseKey
  }
}
