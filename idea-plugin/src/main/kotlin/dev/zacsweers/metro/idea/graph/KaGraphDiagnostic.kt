// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.render.DiagnosticRenderer
import dev.zacsweers.metro.compiler.diagnostics.render.RenderProfile
import dev.zacsweers.metro.idea.model.KaBinding

/** A structured diagnostic from a graph seal, with navigable stack entries. */
internal class KaGraphDiagnostic(
  val diagnostic: MetroDiagnostic,
  val stack: List<KaBindingStack.Entry>,
  /** The bindings this diagnostic is about, such as the sources of a duplicate. */
  val related: List<KaBinding> = emptyList(),
) {
  val id: MetroDiagnosticId
    get() = diagnostic.id

  val severity: MetroSeverity
    get() = diagnostic.severity

  /** Renders the full diagnostic with the compiler's plain console renderer. */
  fun render(): String = PLAIN_RENDERER.render(diagnostic)

  private companion object {
    private val PLAIN_RENDERER = DiagnosticRenderer(RenderProfile.PLAIN)
  }
}
