// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.WrappedType

/** The Analysis API analog of the compiler's contextual type key. */
internal class KaContextualTypeKey(
  override val typeKey: KaTypeKey,
  override val wrappedType: WrappedType<KaTypeSnapshot>,
  override val hasDefault: Boolean = false,
  override val rawType: KaTypeSnapshot? = null,
) : BaseContextualTypeKey<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey> {
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
