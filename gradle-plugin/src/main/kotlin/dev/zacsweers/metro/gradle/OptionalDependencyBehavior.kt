// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

@Deprecated("Use OptionalBindingBehavior instead")
public enum class OptionalDependencyBehavior {
  DISABLED,
  DEFAULT,
  REQUIRE_OPTIONAL_DEPENDENCY;

  internal fun mapToOptionalBindingBehavior(): OptionalBindingBehavior =
    when (this) {
      DISABLED -> OptionalBindingBehavior.DISABLED
      DEFAULT -> OptionalBindingBehavior.DEFAULT
      REQUIRE_OPTIONAL_DEPENDENCY -> OptionalBindingBehavior.REQUIRE_OPTIONAL_BINDING
    }
}
