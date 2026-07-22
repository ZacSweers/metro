// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.name.ClassId

/**
 * A sealed class hierarchy representing the different types of wrapping for a type. This is useful
 * because Metro's runtime supports multiple layers of wrapping that need to be canonicalized when
 * performing binding lookups. For example, all of these point to the same `Map<Int, Int>` canonical
 * type key.
 * - `Map<Int, Int>`
 * - `Map<Int, Provider<Int>>`
 * - `Provider<Map<Int, Int>>`
 * - `Provider<Map<Int, Provider<Int>>>`
 * - `Lazy<Map<Int, Provider<Int>>>`
 * - `Provider<Lazy<<Map<Int, Provider<Int>>>>>`
 * - `Provider<Lazy<Map<Int, Provider<Lazy<Int>>>>>`
 */
public sealed interface WrappedType<T : Any> {
  /** The canonical type with no wrapping. */
  public class Canonical<T : Any>(public val type: T) : WrappedType<T> {
    private val cachedHashCode by memoize { type.hashCode() }
    private val cachedToString by memoize { type.toString() }

    public override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Canonical<*>

      if (cachedHashCode != other.cachedHashCode) return false

      return type == other.type
    }

    public override fun hashCode(): Int = cachedHashCode

    public override fun toString(): String = cachedToString
  }

  /** A type wrapped in a Provider. */
  public class Provider<T : Any>(
    public val innerType: WrappedType<T>,
    public val providerType: ClassId,
  ) : WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + providerType.hashCode()
      result
    }

    private val cachedToString by memoize { "${providerType.asFqNameString()}<$innerType>" }

    public override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Provider<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (providerType != other.providerType) return false

      return true
    }

    public override fun hashCode(): Int = cachedHashCode

    public override fun toString(): String = cachedToString
  }

  /** A type wrapped in a SuspendProvider. */
  public class SuspendProvider<T : Any>(
    public val innerType: WrappedType<T>,
    public val providerType: ClassId,
  ) : WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + providerType.hashCode()
      result
    }

    private val cachedToString by memoize { "${providerType.asFqNameString()}<$innerType>" }

    public override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as SuspendProvider<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (providerType != other.providerType) return false

      return true
    }

    public override fun hashCode(): Int = cachedHashCode

    public override fun toString(): String = cachedToString
  }

  /** A type wrapped in a Lazy. */
  public class Lazy<T : Any>(
    public val innerType: WrappedType<T>,
    public val lazyType: ClassId,
  ) : WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + lazyType.hashCode()
      result
    }

    private val cachedToString by memoize { "${lazyType.asFqNameString()}<$innerType>" }

    public override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Lazy<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (lazyType != other.lazyType) return false

      return true
    }

    public override fun hashCode(): Int = cachedHashCode

    public override fun toString(): String = cachedToString
  }

  /** A type wrapped in a SuspendLazy. */
  public class SuspendLazy<T : Any>(
    public val innerType: WrappedType<T>,
    public val lazyType: ClassId,
  ) : WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + lazyType.hashCode()
      result
    }

    private val cachedToString by memoize { "${lazyType.asFqNameString()}<$innerType>" }

    public override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as SuspendLazy<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (lazyType != other.lazyType) return false

      return true
    }

    public override fun hashCode(): Int = cachedHashCode

    public override fun toString(): String = cachedToString
  }

  /** A map type with special handling for the value type. */
  public class Map<T : Any>(
    public val keyType: T,
    public val valueType: WrappedType<T>,
    public val type: () -> T,
  ) : WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = keyType.hashCode()
      result = 31 * result + valueType.hashCode()
      result
    }

    private val cachedToString by memoize { "Map<$keyType, $valueType>" }

    public override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Map<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (keyType != other.keyType) return false
      if (valueType != other.valueType) return false

      return true
    }

    public override fun hashCode(): Int = cachedHashCode

    public override fun toString(): String = cachedToString
  }

  /** Unwraps all layers and returns the canonical type. */
  public fun canonicalType(): T =
    when (this) {
      is Canonical -> type
      is Provider -> innerType.canonicalType()
      is SuspendProvider -> innerType.canonicalType()
      is Lazy -> innerType.canonicalType()
      is SuspendLazy -> innerType.canonicalType()
      is Map -> type()
    }

  /** Returns true if this type is wrapped in Provider, Lazy, or a suspend counterpart. */
  public fun isDeferrable(): Boolean =
    when (this) {
      is Canonical -> false
      is Provider -> true
      is SuspendProvider -> true
      is Lazy -> true
      is SuspendLazy -> true
      is Map -> valueType.isDeferrable()
    }

  public fun findMapValueType(): WrappedType<T>? {
    return when (this) {
      is Canonical -> this
      is Provider -> innerType.findMapValueType()
      is SuspendProvider -> innerType.findMapValueType()
      is Lazy -> innerType.findMapValueType()
      is SuspendLazy -> innerType.findMapValueType()
      is Map -> valueType
    }
  }

  /** Returns the type directly inside this scalar wrapper, or null at a scalar boundary. */
  public fun immediateInnerType(): WrappedType<T>? =
    when (this) {
      is Canonical,
      is Map -> null
      is Provider -> innerType
      is SuspendProvider -> innerType
      is Lazy -> innerType
      is SuspendLazy -> innerType
    }

  /** Returns the canonical type or Map at the end of this scalar wrapper stack. */
  public fun scalarLeaf(): WrappedType<T> = immediateInnerType()?.scalarLeaf() ?: this

  /** Returns true if this type is a canonical type or Map rather than a scalar wrapper. */
  public fun isScalarLeaf(): Boolean = immediateInnerType() == null

  /** Returns the scalar wrapper directly enclosing the scalar leaf, or null without a wrapper. */
  public fun innermostWrapper(): WrappedType<T>? {
    if (isScalarLeaf()) return null
    var current: WrappedType<T> = this
    while (true) {
      val inner = current.immediateInnerType() ?: return current
      if (inner.isScalarLeaf()) return current
      current = inner
    }
  }

  /** Whether this type requires SuspendProvider, or null if it has no scalar wrapper. */
  public fun usesSuspendProvider(): Boolean? =
    when (this) {
      is Canonical,
      is Map -> null
      is Provider -> innerType.usesSuspendProvider() ?: false
      is Lazy -> innerType.usesSuspendProvider() ?: false
      is SuspendProvider -> innerType.usesSuspendProvider() ?: true
      is SuspendLazy -> innerType.usesSuspendProvider() ?: true
    }

  /** Whether this type requires SuspendProvider, falling back when it has no scalar wrapper. */
  public fun usesSuspendProvider(default: Boolean): Boolean = usesSuspendProvider() ?: default

  /** Returns true if any wrapper layer, including a Map value, is a SuspendLazy. */
  public fun containsSuspendLazy(): Boolean =
    when (this) {
      is Canonical -> false
      is Provider -> innerType.containsSuspendLazy()
      is SuspendProvider -> innerType.containsSuspendLazy()
      is Lazy -> innerType.containsSuspendLazy()
      is SuspendLazy -> true
      is Map -> valueType.containsSuspendLazy()
    }

  /** Returns true if any wrapper layer, including a Map value, can suspend. */
  public fun containsSuspendWrapper(): Boolean =
    when (this) {
      is Canonical -> false
      is Provider -> innerType.containsSuspendWrapper()
      is Lazy -> innerType.containsSuspendWrapper()
      is SuspendProvider,
      is SuspendLazy -> true
      is Map -> valueType.containsSuspendWrapper()
    }

  /** Returns true if the scalar wrapper chain contains an adjacent Provider<Lazy<...>> pair. */
  public fun containsProviderOfLazy(): Boolean =
    when (this) {
      is Canonical,
      is Map -> false
      is Provider -> innerType is Lazy || innerType.containsProviderOfLazy()
      is SuspendProvider -> innerType.containsProviderOfLazy()
      is Lazy -> innerType.containsProviderOfLazy()
      is SuspendLazy -> innerType.containsProviderOfLazy()
    }

  /** Whether unwrapping this scalar stack to its canonical value crosses a suspend wrapper. */
  public fun requiresSuspendToUnwrap(): Boolean =
    when (this) {
      is Canonical,
      is Map -> false
      is Provider -> innerType.requiresSuspendToUnwrap()
      is Lazy -> innerType.requiresSuspendToUnwrap()
      is SuspendProvider,
      is SuspendLazy -> true
    }

  /** Returns the synchronous wrapper nearest the bound value, if there is one. */
  public fun lowestSynchronousWrapperName(): String? {
    if (usesSuspendProvider() != false) return null
    return when (innermostWrapper()) {
      is Provider -> "Provider"
      is Lazy -> "Lazy"
      else -> error("Expected an innermost synchronous wrapper for $this")
    }
  }

  /** Describes whether [wrapper] is the outer wrapper or is nested under another wrapper. */
  public fun blockingWrapperPhrase(wrapper: String): String {
    val hasOuterWrapper = immediateInnerType()?.isScalarLeaf() == false
    return if (hasOuterWrapper) {
      "because the wrapper nearest it is $wrapper"
    } else {
      "via $wrapper"
    }
  }

  public fun render(renderType: (T) -> String): String =
    when (this) {
      is Canonical -> renderType(type)
      is Provider -> "Provider<${innerType.render(renderType)}>"
      is SuspendProvider -> "SuspendProvider<${innerType.render(renderType)}>"
      is Lazy -> "Lazy<${innerType.render(renderType)}>"
      is SuspendLazy -> "SuspendLazy<${innerType.render(renderType)}>"
      is Map -> "Map<${renderType(keyType)}, ${valueType.render(renderType)}>"
    }

  public val innerTypesSequence: Sequence<WrappedType<T>>
    get() =
      when (this) {
        is Canonical -> sequenceOf(this)
        is Lazy -> sequenceOf<WrappedType<T>>(this) + innerType.innerTypesSequence
        is Map -> sequenceOf<WrappedType<T>>(this) + valueType.innerTypesSequence
        is Provider -> sequenceOf<WrappedType<T>>(this) + innerType.innerTypesSequence
        is SuspendProvider -> sequenceOf<WrappedType<T>>(this) + innerType.innerTypesSequence
        is SuspendLazy -> sequenceOf<WrappedType<T>>(this) + innerType.innerTypesSequence
      }
}

/**
 * Structurally maps every stored type in this wrapped type with [transform], preserving the
 * Provider/Lazy/Map structure. Useful when the navigated and stored type representations differ
 * (like building a snapshot tree from live compiler types).
 */
public fun <T : Any, R : Any> WrappedType<T>.mapTypes(transform: (T) -> R): WrappedType<R> =
  when (this) {
    is WrappedType.Canonical -> WrappedType.Canonical(transform(type))
    is WrappedType.Provider -> WrappedType.Provider(innerType.mapTypes(transform), providerType)
    is WrappedType.SuspendProvider ->
      WrappedType.SuspendProvider(innerType.mapTypes(transform), providerType)
    is WrappedType.Lazy -> WrappedType.Lazy(innerType.mapTypes(transform), lazyType)
    is WrappedType.SuspendLazy -> WrappedType.SuspendLazy(innerType.mapTypes(transform), lazyType)
    is WrappedType.Map -> {
      val mappedKey = transform(keyType)
      val mappedValue = valueType.mapTypes(transform)
      val mappedCanonical = transform(type())
      WrappedType.Map(mappedKey, mappedValue) { mappedCanonical }
    }
  }

/**
 * Recursively classifies a type's provider/lazy/map wrapping into a [WrappedType], navigating the
 * platform's type representation through [classIdOf] and [argumentsOf].
 */
public fun <T : Any> buildWrappedType(
  type: T,
  mapClassId: ClassId,
  providerTypes: Set<ClassId>,
  lazyTypes: Set<ClassId>,
  suspendProviderTypes: Set<ClassId> = emptySet(),
  suspendLazyTypes: Set<ClassId> = emptySet(),
  classIdOf: (T) -> ClassId?,
  argumentsOf: (T) -> List<T>,
): WrappedType<T> {
  fun recurse(inner: T) =
    buildWrappedType(
      inner,
      mapClassId,
      providerTypes,
      lazyTypes,
      suspendProviderTypes,
      suspendLazyTypes,
      classIdOf,
      argumentsOf,
    )

  val classId = classIdOf(type)
  if (classId == mapClassId) {
    val arguments = argumentsOf(type)
    if (arguments.size >= 2) {
      return WrappedType.Map(arguments[0], recurse(arguments[1])) { type }
    }
  }
  if (classId != null && classId in providerTypes) {
    argumentsOf(type).firstOrNull()?.let {
      return WrappedType.Provider(recurse(it), classId)
    }
  }
  if (classId != null && classId in suspendProviderTypes) {
    argumentsOf(type).firstOrNull()?.let {
      return WrappedType.SuspendProvider(recurse(it), classId)
    }
  }
  if (classId != null && classId in lazyTypes) {
    argumentsOf(type).firstOrNull()?.let {
      return WrappedType.Lazy(recurse(it), classId)
    }
  }
  if (classId != null && classId in suspendLazyTypes) {
    argumentsOf(type).firstOrNull()?.let {
      return WrappedType.SuspendLazy(recurse(it), classId)
    }
  }
  return WrappedType.Canonical(type)
}
