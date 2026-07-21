// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.render.DiagnosticRenderer
import dev.zacsweers.metro.compiler.diagnostics.render.RenderProfile

/**
 * Interface for reporting structured diagnostics from binding graph validation. Implementations
 * collect diagnostics and flush them as batched reports.
 */
public interface ErrorReporter<BindingStack : BaseBindingStack<*, *, *, *, BindingStack>> {
  /** Records a non-fatal diagnostic. Processing continues after this call. */
  public fun report(diagnostic: MetroDiagnostic, stack: BindingStack)

  /**
   * Records a fatal diagnostic and halts processing. Implementations should call [report], [flush],
   * then throw.
   */
  public fun reportFatal(diagnostic: MetroDiagnostic, stack: BindingStack): Nothing

  /** Flushes all collected diagnostics, batching messages that target the same diagnostic slot. */
  public fun flush()

  public companion object {
    /** Default reporter that immediately throws on any error. Useful for tests. */
    public fun <BindingStack : BaseBindingStack<*, *, *, *, BindingStack>> throwing():
      ErrorReporter<BindingStack> =
      object : ErrorReporter<BindingStack> {
        private val renderer = DiagnosticRenderer(RenderProfile.PLAIN)

        override fun report(diagnostic: MetroDiagnostic, stack: BindingStack) =
          error(renderer.render(diagnostic))

        override fun reportFatal(diagnostic: MetroDiagnostic, stack: BindingStack): Nothing =
          error(renderer.render(diagnostic))

        override fun flush() {}
      }
  }
}
