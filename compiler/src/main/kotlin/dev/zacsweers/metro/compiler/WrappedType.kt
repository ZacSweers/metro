// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.drewhamilton.poko.Poko

/** A sealed class hierarchy representing the different types of wrapping for a type. */
internal sealed class WrappedType<T : Any> {
  /** The canonical type with no wrapping. */
  data class Canonical<T : Any>(val type: T) : WrappedType<T>()

  /** A type wrapped in a Provider. */
  data class Provider<T : Any>(val innerType: WrappedType<T>) : WrappedType<T>()

  /** A type wrapped in a Lazy. */
  data class Lazy<T : Any>(val innerType: WrappedType<T>) : WrappedType<T>()

  /** A map type with special handling for the value type. */
  @Poko
  class Map<T : Any>(val keyType: T, val valueType: WrappedType<T>, @Poko.Skip val type: () -> T) :
    WrappedType<T>()

  /** Unwraps all layers and returns the canonical type. */
  fun canonicalType(): T =
    when (this) {
      is Canonical -> type
      is Provider -> innerType.canonicalType()
      is Lazy -> innerType.canonicalType()
      is Map -> type()
    }

  /** Returns true if this type is wrapped in a Provider or Lazy at any level. */
  fun isDeferrable(): Boolean =
    when (this) {
      is Canonical -> false
      is Provider -> true
      is Lazy -> true
      is Map -> valueType.isDeferrable()
    }

  fun findMapValueType(): WrappedType<T>? {
    return when (this) {
      is Canonical -> null
      is Provider -> innerType.findMapValueType()
      is Lazy -> innerType.findMapValueType()
      is Map -> valueType
    }
  }

  /** Renders this type as a string. */
  fun render(renderType: (T) -> String): String =
    when (this) {
      is Canonical -> renderType(type)
      is Provider -> "Provider<${innerType.render(renderType)}>"
      is Lazy -> "Lazy<${innerType.render(renderType)}>"
      is Map -> "Map<${renderType(keyType)}, ${valueType.render(renderType)}>"
    }
}
