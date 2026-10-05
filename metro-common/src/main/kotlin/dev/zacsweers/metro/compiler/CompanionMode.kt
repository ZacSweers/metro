// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.Locale

/** Controls generated graph creators and construction and member-injection helpers. */
public enum class CompanionMode {
  /** Keeps helper companion objects and graph companion factory contracts. */
  COMPANION_OBJECT,
  /** Generates receiverless functions on graph, factory, and injector classes. */
  COMPANION_BLOCK,
  /** Keeps companion APIs and adds receiverless functions that delegate to them. */
  COMPATIBILITY,
  /** Omits graph entry points and keeps required factory and injector helper companions. */
  NONE;

  /** Selects receiverless functions as the canonical generated helper API. */
  public val usesStaticHelpers: Boolean
    get() = this == COMPANION_BLOCK

  /** Adds receiverless delegates while keeping companion helpers as the canonical API. */
  public val addsStaticHelperBridges: Boolean
    get() = this == COMPATIBILITY

  /** These modes require Kotlin's companion-block feature to be enabled. */
  public val requiresCompanionBlocks: Boolean
    get() = this == COMPANION_BLOCK || this == COMPATIBILITY

  public companion object {
    /** Accepts enum names and their hyphenated compiler-option spellings. */
    public fun parse(value: String): CompanionMode {
      val name = value.trim().replace('-', '_').uppercase(Locale.US)
      return entries.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException(
          "Unknown companion-mode '$value'. Expected " +
            "companion-object, companion-block, compatibility, or none."
        )
    }
  }
}
