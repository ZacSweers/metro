// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/** See the analogous type in the gradle plugin. */
public enum class OptionalDependencyBehavior {
  DISABLED,
  DEFAULT,
  REQUIRE_OPTIONAL_DEPENDENCY;

  internal val requiresAnnotatedParameters: Boolean
    get() = this == REQUIRE_OPTIONAL_DEPENDENCY
}
