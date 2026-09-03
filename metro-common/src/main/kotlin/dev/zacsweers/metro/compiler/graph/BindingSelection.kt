// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Selection order after graph instances and synthetic collection element keys are checked.
 * Unqualified assisted targets come first so validation can report their invalid direct use.
 * Authored bindings precede generated graph aliases, collection synthesis, and class injection.
 */
public enum class BindingTier {
  ASSISTED_TARGET,
  EXPLICIT,
  GENERATED_GRAPH,
  MULTIBINDING,
  OPTIONAL,
  IMPLICIT,
}

/** Stops at the first populated tier, leaving lower-priority lookups unevaluated. */
public inline fun selectBindingTier(hasBindings: (BindingTier) -> Boolean): BindingTier? {
  for (tier in BindingTier.entries) {
    if (hasBindings(tier)) return tier
  }
  return null
}
