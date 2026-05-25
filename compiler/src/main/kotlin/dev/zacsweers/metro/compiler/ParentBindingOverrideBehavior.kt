// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/**
 * Controls what happens when a graph extension declares a binding for a key that an ancestor graph
 * already binds, _without_ an explicit `@OverridesParentBinding` annotation.
 *
 * See the analogous type in the Gradle plugin.
 */
public enum class ParentBindingOverrideBehavior {
  /** The extension's binding silently wins (legacy behavior). */
  ALLOW,
  /**
   * The extension's binding wins, but Metro reports a warning suggesting `@OverridesParentBinding`.
   */
  WARN,
  /** An unannotated shadow is an error. `@OverridesParentBinding` is required to override. */
  REQUIRE_ANNOTATION,
  /** Overriding an inherited binding is not allowed at all, even with `@OverridesParentBinding`. */
  DISALLOW,
}
