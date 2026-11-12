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

  /**
   * Reported once on a graph that collects generic providers or binds functions from dependencies
   * while derived bindings are disabled. [providerNames] are their rendered names in a stable
   * order.
   */
  public fun derivedBindingsNotEnabledForDependencies(providerNames: List<String>): String {
    val quoted = providerNames.map { "`$it`" }
    val renderedNames =
      when (quoted.size) {
        1 -> quoted.single()
        2 -> "${quoted[0]} and ${quoted[1]}"
        else -> quoted.dropLast(1).joinToString(", ") + ", and " + quoted.last()
      }
    val verb =
      if (quoted.size == 1) {
        "declares"
      } else {
        "declare"
      }
    return "This graph includes $renderedNames, which $verb type parameters. Generic providers " +
      "need derived bindings, which are disabled. Enable the `enable-derived-bindings` compiler " +
      "option or set `metro.enableDerivedBindings` to true."
  }
}
