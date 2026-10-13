// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.graph.compareTypeKeys
import dev.zacsweers.metro.compiler.graph.identity.TypeKeyIdentity

/** The Analysis API analog of the compiler's `FirTypeKey`/`IrTypeKey`. */
internal class KaTypeKey(
  override val type: KaTypeSnapshot,
  override val qualifier: KaAnnotationSnapshot? = null,
) : BaseTypeKey<KaTypeSnapshot, KaAnnotationSnapshot, KaTypeKey> {
  val renderedType: String
    get() = type.renderedType

  override val identity: TypeKeyIdentity = TypeKeyIdentity(type.identity, qualifier?.identity)

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
    return identity == other.identity
  }

  override fun hashCode(): Int = identity.hashCode()

  override fun toString(): String = render(short = false)

  override fun compareTo(other: KaTypeKey): Int = compareTypeKeys(this, other)
}
