// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

/** Controls the behavior of optional dependencies on a per-compilation basis. */
public enum class OptionalDependencyBehavior {
  /**
   * Disable all optional dependencies. Default values are allowed on parameters but ignored by the
   * compiler.
   */
  DISABLED,

  /**
   * In this mode, the presence of a default value on a parameter alone indicates that dependency is
   * optional.
   *
   * Note that for optional graph accessors, `@OptionalDependency` is still necessary.
   */
  DEFAULT,

  /**
   * In this mode, all optional dependencies must be annotated with `@OptionalDependency` (in both
   * graph accessors as well as injected parameters). This can be desirable for consistency with
   * accessors and/or to otherwise make the behavior more explicit.
   */
  REQUIRE_OPTIONAL_DEPENDENCY,
}
