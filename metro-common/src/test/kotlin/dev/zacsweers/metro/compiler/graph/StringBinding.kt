// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.drewhamilton.poko.Poko

@Poko
internal class StringBinding(
  override val contextualTypeKey: StringContextualTypeKey,
  override val dependencies: List<StringContextualTypeKey> = emptyList(),
) : BaseBinding<String, StringTypeKey, StringContextualTypeKey> {

  override fun renderLocationDiagnostic(
    short: Boolean,
    shortLocation: Boolean,
    underlineTypeKey: Boolean,
  ): LocationDiagnostic {
    return LocationDiagnostic(contextualTypeKey.typeKey.render(short = true), null)
  }

  override fun renderDescriptionDiagnostic(short: Boolean, underlineTypeKey: Boolean): String {
    return buildString {
      append(contextualTypeKey.render(short = short))
      if (dependencies.isNotEmpty()) {
        append(" -> ")
        append(dependencies.joinToString(", ") { it.render(short = true) })
      }
    }
  }

  override fun toString() = renderDescriptionDiagnostic(short = true, underlineTypeKey = false)

  companion object {
    operator fun invoke(
      typeKey: StringTypeKey,
      dependencies: List<StringContextualTypeKey> = emptyList(),
    ) = StringBinding(StringContextualTypeKey.create(typeKey), dependencies)
  }
}
