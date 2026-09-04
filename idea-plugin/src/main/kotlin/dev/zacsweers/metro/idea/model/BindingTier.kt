// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

/**
 * Binding categories used by editor selection and explanations, ordered by indexed precedence.
 * Unqualified assisted targets come first so validation can report their invalid direct use.
 * Authored bindings precede generated graph aliases, collection synthesis, and class injection.
 */
internal enum class BindingTier {
  ASSISTED_TARGET,
  EXPLICIT,
  GENERATED_GRAPH,
  MULTIBINDING,
  OPTIONAL,
  IMPLICIT,
}
