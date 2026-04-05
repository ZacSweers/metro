// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Interface for reporting errors from binding graph validation. Implementations collect errors and
 * flush them as batched diagnostics.
 */
internal interface ErrorReporter<BindingStack : BaseBindingStack<*, *, *, *, BindingStack>> {
  /** Records a non-fatal error. Processing continues after this call. */
  fun report(kind: BindingGraphDiagnosticKind, message: String, stack: BindingStack)

  /**
   * Records a fatal error and halts processing. Implementations should call [report], [flush], then
   * throw.
   */
  fun reportFatal(kind: BindingGraphDiagnosticKind, message: String, stack: BindingStack): Nothing

  /** Flushes all collected errors, batching messages that target the same diagnostic slot. */
  fun flush()

  companion object {
    /** Default reporter that immediately throws on any error. Useful for tests. */
    fun <BindingStack : BaseBindingStack<*, *, *, *, BindingStack>> throwing():
      ErrorReporter<BindingStack> =
      object : ErrorReporter<BindingStack> {
        override fun report(
          kind: BindingGraphDiagnosticKind,
          message: String,
          stack: BindingStack,
        ) = error(message)

        override fun reportFatal(
          kind: BindingGraphDiagnosticKind,
          message: String,
          stack: BindingStack,
        ): Nothing = error(message)

        override fun flush() {}
      }
  }
}
