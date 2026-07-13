// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import org.jetbrains.kotlin.name.StandardClassIds

/** The Analysis API analog of the compiler's contextual type key. */
internal class KaContextualTypeKey(
  override val typeKey: KaTypeKey,
  override val wrappedType: WrappedType<KaTypeSnapshot>,
  override val hasDefault: Boolean = false,
  override val rawType: KaTypeSnapshot? = null,
) : BaseContextualTypeKey<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey> {

  fun withDefault(hasDefault: Boolean): KaContextualTypeKey {
    if (hasDefault == this.hasDefault) return this
    return KaContextualTypeKey(typeKey, wrappedType, hasDefault, rawType)
  }

  override fun render(
    short: Boolean,
    includeQualifier: Boolean,
    useRelativeClassNames: Boolean,
  ): String {
    return wrappedType.render { snapshot ->
      if (snapshot == typeKey.type) {
        typeKey.render(short, includeQualifier, useRelativeClassNames)
      } else if (short || useRelativeClassNames) {
        snapshot.shortType
      } else {
        snapshot.renderedType
      }
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaContextualTypeKey) return false
    return typeKey == other.typeKey && wrappedType == other.wrappedType
  }

  override fun hashCode(): Int = 31 * typeKey.hashCode() + wrappedType.hashCode()

  override fun toString(): String = render(short = false)
}

/**
 * The multibinding id collected by a requested `Set` or `Map`, or null for regular keys. Deduced
 * from the key itself, mirroring how the compiler derives ids from `IrTypeKey`.
 */
internal fun KaContextualTypeKey.multibindingId(options: MetroOptions): String? {
  val mapNode =
    wrappedType.innerTypesSequence.filterIsInstance<WrappedType.Map<KaTypeSnapshot>>().firstOrNull()
  if (mapNode != null) {
    val valueKey = typeKey.copy(type = mapNode.valueType.canonicalType())
    return createMapBindingId(mapNode.keyType.renderedType, valueKey)
  }
  if (typeKey.type.classId != StandardClassIds.Set) return null
  val elementType = typeKey.type.typeArguments.singleOrNull()?.canonicalType(options) ?: return null
  return typeKey.copy(type = elementType).computeMultibindingId()
}

/** Peels `Provider`/`Lazy` wrappers off a snapshot to its underlying type. */
private tailrec fun KaTypeSnapshot.canonicalType(options: MetroOptions): KaTypeSnapshot {
  val classId = classId ?: return this
  if (classId !in options.providerTypes && classId !in options.lazyTypes) return this
  val inner = typeArguments.firstOrNull() ?: return this
  return inner.canonicalType(options)
}
