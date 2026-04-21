// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

public enum class DiagnosticSeverity {
  /** Emits no diagnostics/does not check. */
  NONE,

  /** Emits a compiler warning if encountered. */
  WARN,

  /** Emits a compiler error if encountered and fails compilation. */
  ERROR,

  /**
   * Like [WARN], but only reports when the Metro compiler is running inside an IDE session. CLI
   * compilations treat this as [NONE].
   *
   * Useful for diagnostics you only want to surface to readers in the IDE without emitting compiler
   * warnings in real (CLI) compilations.
   */
  IDE_WARN,

  /**
   * Like [ERROR], but only reports when the Metro compiler is running inside an IDE session. CLI
   * compilations treat this as [NONE].
   *
   * Useful for diagnostics you only want to surface to readers in the IDE without failing real
   * (CLI) compilations.
   */
  IDE_ERROR;

  public val isEnabled: Boolean
    get() = this != NONE

  public val isIdeOnly: Boolean
    get() = this == IDE_ERROR || this == IDE_WARN
}
