// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.drewhamilton.poko.Poko

@Poko
internal class StringBinding(
  override val contextualTypeKey: StringContextualTypeKey,
  override val dependencies: List<StringContextualTypeKey> = emptyList(),
) : BaseBinding<String, StringTypeKey, StringContextualTypeKey> {

  override fun toString(): String {
    return buildString {
      append(contextualTypeKey.render(short = true))
      if (dependencies.isNotEmpty()) {
        append(" -> ")
        append(dependencies.joinToString(", ") { it.render(short = true) })
      }
    }
  }

  companion object {
    operator fun invoke(
      typeKey: StringTypeKey,
      dependencies: List<StringContextualTypeKey> = emptyList(),
    ) = StringBinding(StringContextualTypeKey(typeKey), dependencies)
  }
}
