// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.memoize
import kotlin.getValue

internal abstract class BaseTypeKey<
  Type,
  Qualifier,
  Subtype : BaseTypeKey<Type, Qualifier, Subtype>,
> : Comparable<Subtype> {

  abstract val type: Type
  abstract val qualifier: Qualifier?

  private val cachedToString by memoize { render(short = false, includeQualifier = true) }

  final override fun toString(): String = cachedToString

  final override fun compareTo(other: Subtype): Int {
    if (this == other) return 0
    return toString().compareTo(other.toString())
  }

  final override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as BaseTypeKey<*, *, *>

    return cachedToString == other.cachedToString
  }

  final override fun hashCode() = cachedToString.hashCode()

  abstract fun copy(type: Type = this.type, qualifier: Qualifier? = this.qualifier): Subtype

  abstract fun render(short: Boolean, includeQualifier: Boolean = true): String
}
