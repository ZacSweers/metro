// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

internal enum class BindingKind(val label: String) {
  /** A `@Provides` callable. */
  PROVIDES("provides"),
  /** A `@Binds` callable. */
  BINDS("binds"),
  /** An injected class providing its own type. */
  INJECT("injected class"),
  /** A `@ContributesBinding`-style class bound to a supertype. */
  CONTRIBUTED("contributed binding"),
  /** An `@IntoSet`/`@ContributesIntoSet`-style multibinding contribution. */
  MULTIBINDING_CONTRIBUTION("multibinding contribution"),
  /** A `@Multibinds` declaration. */
  MULTIBINDING_DECLARATION("multibinding declaration"),
  /** An instance binding from a graph factory `@Provides` parameter. */
  INSTANCE("instance binding"),
  /** An `@AssistedFactory` providing its own type. */
  ASSISTED_FACTORY("assisted factory"),
  /** An accessor of an `@Includes` graph dependency. */
  INCLUDED("included dependency accessor"),
  /** A `@BindsOptionalOf` (Dagger interop) binding exposing `Optional<T>`. */
  OPTIONAL("optional binding"),
}
