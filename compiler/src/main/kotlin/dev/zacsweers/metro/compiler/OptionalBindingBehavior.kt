// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/** See the analogous type in the Gradle plugin. */
public enum class OptionalBindingBehavior {
  DISABLED,
  DEFAULT,
  REQUIRE_OPTIONAL_BINDING;

  internal val requiresAnnotatedParameters: Boolean
    get() = this == REQUIRE_OPTIONAL_BINDING
}
