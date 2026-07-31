// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

/**
 * Shared headline text for diagnostics emitted from more than one compiler phase.
 *
 * Keep common titles here so FIR checkers, graph validation, and IR transformers report the same
 * problem with the same wording.
 */
public object DiagnosticHeadlines {
  /** Hard dependency cycle between bindings. Followed by the owning graph's name. */
  public const val DEPENDENCY_CYCLE_PREFIX: String = "Found a dependency cycle while processing "

  /** Graph-level cycle (a graph depending on or extending itself). */
  public const val GRAPH_DEPENDENCY_CYCLE: String = "Graph dependency cycle detected"

  /** Duplicate bindings for a single type key. Followed by the rendered type key. */
  public const val DUPLICATE_BINDING_PREFIX: String = "Multiple bindings found for "

  /** Duplicate keys in a map multibinding. Followed by the rendered multibinding type key. */
  public const val DUPLICATE_MAP_KEYS_PREFIX: String = "Duplicate map keys found for multibinding "
}
