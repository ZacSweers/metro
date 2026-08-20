// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.BaseTypeKey

/** The Analysis API analog of the compiler's `FirTypeKey`/`IrTypeKey`. */
internal class KaTypeKey(
  override val type: KaTypeSnapshot,
  override val qualifier: KaAnnotationSnapshot? = null,
) : BaseTypeKey<KaTypeSnapshot, KaAnnotationSnapshot, KaTypeKey> {
  val renderedType: String
    get() = type.renderedType

  override fun copy(type: KaTypeSnapshot, qualifier: KaAnnotationSnapshot?): KaTypeKey {
    return KaTypeKey(type, qualifier)
  }

  override fun render(
    short: Boolean,
    includeQualifier: Boolean,
    useRelativeClassNames: Boolean,
  ): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append(it.render(short, useRelativeClassNames))
        append(' ')
      }
    }
    append(if (short || useRelativeClassNames) type.shortType else renderedType)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaTypeKey) return false
    return renderedType == other.renderedType && qualifier == other.qualifier
  }

  override fun hashCode(): Int = 31 * renderedType.hashCode() + (qualifier?.hashCode() ?: 0)

  override fun toString(): String = render(short = false)

  override fun compareTo(other: KaTypeKey): Int {
    if (this == other) return 0
    return toString().compareTo(other.toString())
  }
}
