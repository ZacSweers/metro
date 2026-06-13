// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

/**
 * Shared headline text for diagnostics emitted from more than one compiler phase.
 *
 * Keep common titles here so FIR checkers, graph validation, and IR transformers report the same
 * problem with the same wording.
 */
internal object DiagnosticHeadlines {
  /** Hard dependency cycle between bindings. Followed by the owning graph's name. */
  const val DEPENDENCY_CYCLE_PREFIX = "Found a dependency cycle while processing "

  /** Graph-level cycle (a graph depending on or extending itself). */
  const val GRAPH_DEPENDENCY_CYCLE = "Graph dependency cycle detected"

  /** Duplicate bindings for a single type key. Followed by the rendered type key. */
  const val DUPLICATE_BINDING_PREFIX = "Multiple bindings found for "

  /** Duplicate keys in a map multibinding. Followed by the rendered multibinding type key. */
  const val DUPLICATE_MAP_KEYS_PREFIX = "Duplicate map keys found for multibinding "
}
