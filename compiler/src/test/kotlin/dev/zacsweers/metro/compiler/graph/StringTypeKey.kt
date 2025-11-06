// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.memoize

@Poko
internal class StringTypeKey(override val type: String, override val qualifier: String? = null) :
  BaseTypeKey<String, String, StringTypeKey> {

  private val cachedRender by memoize { render(short = false, includeQualifier = true) }

  override fun copy(type: String, qualifier: String?): StringTypeKey =
    StringTypeKey(type, qualifier)

  override fun render(short: Boolean, includeQualifier: Boolean) =
    if (short) type else qualifier?.let { "$it:$type" } ?: type

  override fun compareTo(other: StringTypeKey): Int {
    return cachedRender.compareTo(other.cachedRender)
  }
}
