// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

/** Render mode for Metro's build-output diagnostics. */
@ExperimentalMetroGradleApi
public enum class DiagnosticsRenderMode {
  /**
   * Resolved to [PLAIN] or [RICH] at configuration time:
   * - [PLAIN] when
   *     - The `NO_COLOR` environment variable is set
   *     - The build runs with `--console=plain`
   *     - The build is invoked from an IDE (`idea.active` system property, because IDE build output
   *       windows do not render ANSI escape codes).
   * - [RICH] otherwise.
   *
   * The compiler only ever receives the resolved concrete mode.
   */
  AUTO,

  /** ASCII structure with no ANSI styling. Safe for any log consumer. */
  PLAIN,

  /** Unicode glyphs and ANSI color/styling for terminal consumption. */
  RICH,
}
