// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.SimilarBindingItem

internal data class MissingBindingHints(
  val notes: List<Note> = emptyList(),
  /** Extra body sections (e.g. a near-miss binding's location + signature). */
  val sections: List<DiagnosticSection> = emptyList(),
  val similarBindings: List<SimilarBindingItem> = emptyList(),
)
