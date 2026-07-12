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
internal sealed interface WrappedType<T : Any> {
  /** The canonical type with no wrapping. */
  class Canonical<T : Any>(val type: T) : WrappedType<T> {
    private val cachedHashCode by memoize { type.hashCode() }
    private val cachedToString by memoize { type.toString() }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Canonical<*>

      if (cachedHashCode != other.cachedHashCode) return false

      return type == other.type
    }

    override fun hashCode() = cachedHashCode

    override fun toString() = cachedToString
  }

  /** A type wrapped in a Provider. */
  class Provider<T : Any>(val innerType: WrappedType<T>, val providerType: ClassId) :
    WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + providerType.hashCode()
      result
    }

    private val cachedToString by memoize { "${providerType.asFqNameString()}<$innerType>" }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Provider<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (providerType != other.providerType) return false

      return true
    }

    override fun hashCode() = cachedHashCode

    override fun toString() = cachedToString
  }

  /** A type wrapped in a SuspendProvider. */
  class SuspendProvider<T : Any>(val innerType: WrappedType<T>, val providerType: ClassId) :
    WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + providerType.hashCode()
      result
    }

    private val cachedToString by memoize { "${providerType.asFqNameString()}<$innerType>" }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as SuspendProvider<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (providerType != other.providerType) return false

      return true
    }

    override fun hashCode() = cachedHashCode

    override fun toString() = cachedToString
  }

  /** A type wrapped in a Lazy. */
  class Lazy<T : Any>(val innerType: WrappedType<T>, val lazyType: ClassId) : WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + lazyType.hashCode()
      result
    }

    private val cachedToString by memoize { "${lazyType.asFqNameString()}<$innerType>" }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Lazy<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (lazyType != other.lazyType) return false

      return true
    }

    override fun hashCode() = cachedHashCode

    override fun toString() = cachedToString
  }

  /** A type wrapped in a SuspendLazy. */
  class SuspendLazy<T : Any>(val innerType: WrappedType<T>, val lazyType: ClassId) :
    WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = innerType.hashCode()
      result = 31 * result + lazyType.hashCode()
      result
    }

    private val cachedToString by memoize { "${lazyType.asFqNameString()}<$innerType>" }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as SuspendLazy<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (innerType != other.innerType) return false
      if (lazyType != other.lazyType) return false

      return true
    }

    override fun hashCode() = cachedHashCode

    override fun toString() = cachedToString
  }

  /** A map type with special handling for the value type. */
  class Map<T : Any>(val keyType: T, val valueType: WrappedType<T>, val type: () -> T) :
    WrappedType<T> {
    private val cachedHashCode by memoize {
      var result = keyType.hashCode()
      result = 31 * result + valueType.hashCode()
      result
    }

    private val cachedToString by memoize { "Map<$keyType, $valueType>" }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Map<*>

      if (cachedHashCode != other.cachedHashCode) return false

      if (keyType != other.keyType) return false
      if (valueType != other.valueType) return false

      return true
    }

    override fun hashCode() = cachedHashCode

    override fun toString() = cachedToString
  }

  /** Unwraps all layers and returns the canonical type. */
  fun canonicalType(): T =
    when (this) {
      is Canonical -> type
      is Provider -> innerType.canonicalType()
      is SuspendProvider -> innerType.canonicalType()
      is Lazy -> innerType.canonicalType()
      is SuspendLazy -> innerType.canonicalType()
      is Map -> type()
    }

  /** Returns true if this type is wrapped in Provider, Lazy, or a suspend counterpart. */
  fun isDeferrable(): Boolean =
    when (this) {
      is Canonical -> false
      is Provider -> true
      is SuspendProvider -> true
      is Lazy -> true
      is SuspendLazy -> true
      is Map -> valueType.isDeferrable()
    }

  fun findMapValueType(): WrappedType<T>? {
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
  fun immediateInnerType(): WrappedType<T>? {
    return when (this) {
      is Canonical,
      is Map -> null
      is Provider -> innerType
      is SuspendProvider -> innerType
      is Lazy -> innerType
      is SuspendLazy -> innerType
    }
  }

  /** Returns the canonical type or Map at the end of this scalar wrapper stack. */
  fun scalarLeaf(): WrappedType<T> {
    return immediateInnerType()?.scalarLeaf() ?: this
  }

  /** Returns true if this type is a canonical type or Map rather than a scalar wrapper. */
  fun isScalarLeaf(): Boolean = immediateInnerType() == null

  /** Whether this type requires SuspendProvider, or null if it has no scalar wrapper. */
  fun usesSuspendProvider(): Boolean? {
    return when (this) {
      is Canonical,
      is Map -> null
      is Provider -> innerType.usesSuspendProvider() ?: false
      is Lazy -> innerType.usesSuspendProvider() ?: false
      is SuspendProvider -> innerType.usesSuspendProvider() ?: true
      is SuspendLazy -> innerType.usesSuspendProvider() ?: true
    }
  }

  /** Whether this type requires SuspendProvider, falling back when it has no scalar wrapper. */
  fun usesSuspendProvider(default: Boolean): Boolean {
    return usesSuspendProvider() ?: default
  }

  /** Returns true if any wrapper layer, including a Map value, is a SuspendLazy. */
  fun containsSuspendLazy(): Boolean {
    return when (this) {
      is Canonical -> false
      is Provider -> innerType.containsSuspendLazy()
      is SuspendProvider -> innerType.containsSuspendLazy()
      is Lazy -> innerType.containsSuspendLazy()
      is SuspendLazy -> true
      is Map -> valueType.containsSuspendLazy()
    }
  }

  /** Whether unwrapping this scalar stack to its canonical value crosses a suspend wrapper. */
  fun requiresSuspendToUnwrap(): Boolean {
    return when (this) {
      is Canonical,
      is Map -> false
      is Provider -> innerType.requiresSuspendToUnwrap()
      is Lazy -> innerType.requiresSuspendToUnwrap()
      is SuspendProvider,
      is SuspendLazy -> true
    }
  }

  fun render(renderType: (T) -> String): String =
    when (this) {
      is Canonical -> renderType(type)
      is Provider -> "Provider<${innerType.render(renderType)}>"
      is SuspendProvider -> "SuspendProvider<${innerType.render(renderType)}>"
      is Lazy -> "Lazy<${innerType.render(renderType)}>"
      is SuspendLazy -> "SuspendLazy<${innerType.render(renderType)}>"
      is Map -> "Map<${renderType(keyType)}, ${valueType.render(renderType)}>"
    }

  val innerTypesSequence: Sequence<WrappedType<T>>
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
