// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

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
    private val cachedHashCode by lazy(LazyThreadSafetyMode.PUBLICATION) { type.hashCode() }
    private val cachedToString by lazy(LazyThreadSafetyMode.PUBLICATION) { type.toString() }

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
    private val cachedHashCode by
      lazy(LazyThreadSafetyMode.PUBLICATION) {
        var result = innerType.hashCode()
        result = 31 * result + providerType.hashCode()
        result
      }

    private val cachedToString by
      lazy(LazyThreadSafetyMode.PUBLICATION) {
        "${providerType.asFqNameString()}<$innerType>"
      }

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

  /** A type wrapped in a Lazy. */
  public class Lazy<T : Any>(
    public val innerType: WrappedType<T>,
    public val lazyType: ClassId,
  ) : WrappedType<T> {
    private val cachedHashCode by
      lazy(LazyThreadSafetyMode.PUBLICATION) {
        var result = innerType.hashCode()
        result = 31 * result + lazyType.hashCode()
        result
      }

    private val cachedToString by
      lazy(LazyThreadSafetyMode.PUBLICATION) {
        "${lazyType.asFqNameString()}<$innerType>"
      }

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

  /** A map type with special handling for the value type. */
  public class Map<T : Any>(
    public val keyType: T,
    public val valueType: WrappedType<T>,
    public val type: () -> T,
  ) : WrappedType<T> {
    private val cachedHashCode by
      lazy(LazyThreadSafetyMode.PUBLICATION) {
        var result = keyType.hashCode()
        result = 31 * result + valueType.hashCode()
        result
      }

    private val cachedToString by
      lazy(LazyThreadSafetyMode.PUBLICATION) {
        "Map<$keyType, $valueType>"
      }

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
      is Lazy -> innerType.canonicalType()
      is Map -> type()
    }

  /** Returns true if this type is wrapped in a Provider or Lazy at any level. */
  public fun isDeferrable(): Boolean =
    when (this) {
      is Canonical -> false
      is Provider -> true
      is Lazy -> true
      is Map -> valueType.isDeferrable()
    }

  public fun findMapValueType(): WrappedType<T>? {
    return when (this) {
      is Canonical -> this
      is Provider -> innerType.findMapValueType()
      is Lazy -> innerType.findMapValueType()
      is Map -> valueType
    }
  }

  public fun render(renderType: (T) -> String): String =
    when (this) {
      is Canonical -> renderType(type)
      is Provider -> "Provider<${innerType.render(renderType)}>"
      is Lazy -> "Lazy<${innerType.render(renderType)}>"
      is Map -> "Map<${renderType(keyType)}, ${valueType.render(renderType)}>"
    }

  public val innerTypesSequence: Sequence<WrappedType<T>>
    get() =
      when (this) {
        is Canonical -> sequenceOf(this)
        is Lazy -> sequenceOf<WrappedType<T>>(this) + innerType.innerTypesSequence
        is Map -> sequenceOf<WrappedType<T>>(this) + valueType.innerTypesSequence
        is Provider -> sequenceOf<WrappedType<T>>(this) + innerType.innerTypesSequence
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
    is WrappedType.Lazy -> WrappedType.Lazy(innerType.mapTypes(transform), lazyType)
    is WrappedType.Map -> {
      val mappedKey = transform(keyType)
      val mappedValue = valueType.mapTypes(transform)
      val mappedCanonical = transform(type())
      WrappedType.Map(mappedKey, mappedValue) { mappedCanonical }
    }
  }

/**
 * Recursively classifies a type's Provider/Lazy/Map wrapping into a [WrappedType], navigating the
 * platform's type representation through [classIdOf] and [argumentsOf]. The compiler frontends and
 * the IDE Analysis API layer can supply their own navigators over this one algorithm.
 */
public fun <T : Any> buildWrappedType(
  type: T,
  mapClassId: ClassId,
  providerTypes: Set<ClassId>,
  lazyTypes: Set<ClassId>,
  classIdOf: (T) -> ClassId?,
  argumentsOf: (T) -> List<T>,
): WrappedType<T> {
  fun recurse(inner: T) =
    buildWrappedType(inner, mapClassId, providerTypes, lazyTypes, classIdOf, argumentsOf)

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
  if (classId != null && classId in lazyTypes) {
    argumentsOf(type).firstOrNull()?.let {
      return WrappedType.Lazy(recurse(it), classId)
    }
  }
  return WrappedType.Canonical(type)
}
