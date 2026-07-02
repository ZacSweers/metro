// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/**
 * Strategy for naming generated private members (provider/instance/factory backing fields and
 * properties in graph, factory, and members-injector classes).
 *
 * Maps onto the internal `MemberNamer` subtypes in the IR backend.
 */
public enum class MemberNamingStrategy {
  /** Use caller-supplied descriptive names derived from types and parameters. */
  DESCRIPTIVE,

  /** Short typed prefixes: `provider`, `instance`, `factory`. */
  TYPED,

  /** Single short vocabulary; all member kinds collapse to `provider`. */
  MINIMAL,
}
