// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

/** Controls the behavior of optional bindings on a per-compilation basis. */
public enum class OptionalBindingBehavior {
  /**
   * Disable all optional bindings. Default values are allowed on parameters but ignored by the
   * compiler.
   */
  DISABLED,

  /**
   * In this mode, the presence of a default value on a parameter alone indicates that binding is
   * optional.
   *
   * Note that for optional graph accessors, `@OptionalBinding` is still necessary.
   */
  DEFAULT,

  /**
   * In this mode, all optional bindings must be annotated with `@OptionalBinding` (in both
   * graph accessors as well as injected parameters). This can be desirable for consistency with
   * accessors and/or to otherwise make the behavior more explicit.
   */
  REQUIRE_OPTIONAL_BINDING,
}
