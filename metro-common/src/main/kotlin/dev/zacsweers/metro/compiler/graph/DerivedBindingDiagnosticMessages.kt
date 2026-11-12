// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Derived binding diagnostic wording shared by the compiler and the IDE. The compiler/IDE parity
 * suite compares diagnostic titles, so both frontends must render these identically.
 */
public object DerivedBindingDiagnosticMessages {
  public const val DERIVED_BINDINGS_NOT_ENABLED: String =
    "Type parameters on `@Provides` and `@Binds` functions need derived bindings, which are " +
      "disabled. Enable the `enable-derived-bindings` compiler option or set " +
      "`metro.enableDerivedBindings` to true."
}
