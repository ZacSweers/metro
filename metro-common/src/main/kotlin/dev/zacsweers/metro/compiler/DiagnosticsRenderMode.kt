// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.Locale

/**
 * Controls how Metro renders diagnostics in build output.
 *
 * [AUTO] is a Gradle-facing value. The Gradle plugin resolves it from the build console settings,
 * non-empty `NO_COLOR`, and IDE invocation state before invoking the compiler.
 *
 * The compiler itself renders only [PLAIN] and [RICH]. If [AUTO] reaches the compiler, Metro falls
 * back to [PLAIN].
 */
public enum class DiagnosticsRenderMode {
  AUTO,
  PLAIN,
  RICH;

  public companion object {
    /** Parses compiler option and system property values. */
    public fun parse(value: String): DiagnosticsRenderMode =
      valueOf(value.trim().uppercase(Locale.US))
  }
}
