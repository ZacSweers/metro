// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

public enum class DiagnosticSeverity {
  /** Emits no diagnostics/does not check. */
  NONE,

  /** Emits a compiler warning if encountered. */
  WARN,

  /** Emits a compiler error if encountered and fails compilation. */
  ERROR;

  public val isEnabled: Boolean
    get() = this != DiagnosticSeverity.NONE
}
